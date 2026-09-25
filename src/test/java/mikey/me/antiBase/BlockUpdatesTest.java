package mikey.me.antiBase;

import io.papermc.paper.math.Position;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockUpdatesTest {
    private AntiBase plugin;
    private Server server;
    private World world;
    private Player player;
    private UUID id, worldId;
    private BlockUpdates updates;
    private ClientViewTracker clients;
    private BlockData air, stone;
    private Chunk chunk;
    private final AtomicReference<VisibilitySnapshot> visibility = new AtomicReference<>(VisibilitySnapshot.EMPTY);
    private final Map<Long, BlockData> data = new HashMap<>();

    @BeforeEach
    void setUp() {
        plugin = mock(AntiBase.class);
        server = mock(Server.class);
        world = mock(World.class);
        player = mock(Player.class);
        id = UUID.randomUUID();
        worldId = UUID.randomUUID();
        clients = new ClientViewTracker();
        clients.reset(id, worldId);
        when(plugin.getServer()).thenReturn(server);
        when(server.isOwnedByCurrentRegion(any(Player.class))).thenReturn(true);
        when(plugin.clientViews()).thenReturn(clients);
        when(plugin.interactions()).thenReturn(new InteractionVisibility());
        when(plugin.diagnostics()).thenReturn(new ConsoleDebug());
        when(plugin.isObfuscationEnabled()).thenReturn(true);
        when(world.getUID()).thenReturn(worldId);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0.5, -7.5, 0.5));
        when(player.getSentChunkKeys()).thenReturn(Set.of(0L, 4L));
        when(server.getPlayer(id)).thenReturn(player);
        when(plugin.getViewerState(id)).thenAnswer(call -> new ViewerState(worldId, -64, true, 0, -8, 0, visibility.get()));
        air = mock(BlockData.class);
        stone = mock(BlockData.class);
        when(server.createBlockData(Material.AIR)).thenReturn(air);
        chunk = mock(Chunk.class);
        when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getTileEntities(false)).thenReturn(new BlockState[0]);
        when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            Block block = mock(Block.class);
            long key = Coordinates.block(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            when(block.getBlockData()).thenReturn(data.getOrDefault(key, stone));
            return block;
        });
        updates = new BlockUpdates(plugin, mock(BaseObfuscator.class));
    }

    @Test
    void visibilityChangeGoesToItsViewer() {
        long target = Coordinates.block(1, -8, 1);
        visibility.set(snapshot(target));
        updates.enqueue(id, worldId, target);
        updates.flush(16384, 4096);
        var outgoing = sentChanges(player);
        assertEquals(Map.of(Position.block(1, -8, 1), stone), outgoing);
        verify(world, never()).refreshChunk(anyInt(), anyInt());
        assertEquals(0, updates.size());
    }

    @Test
    void hiddenBlocksClearWithoutMetadata() {
        long target = Coordinates.block(1, -8, 1);
        clients.get(id, worldId).blockSent(1, -8, 1, true);
        updates.enqueue(id, worldId, target);
        updates.flush(16384, 4096);
        assertSame(air, sentChanges(player).get(Position.block(1, -8, 1)));
        verify(chunk, never()).getBlock(anyInt(), anyInt(), anyInt());
        verify(player, never()).sendBlockUpdate(any(), any());
    }

    @Test
    void queuedRevealsAreRechecked() {
        long target = Coordinates.block(1, -8, 1);
        visibility.set(snapshot(target));
        updates.enqueue(id, worldId, target);
        visibility.set(VisibilitySnapshot.EMPTY); // door closes before this batch goes out
        updates.flush(16384, 4096);
        assertSame(air, sentChanges(player).get(Position.block(1, -8, 1)));
    }

    @Test
    void revealsUseCurrentData() {
        long target = Coordinates.block(1, -8, 1);
        visibility.set(snapshot(target));
        updates.enqueue(id, worldId, target);
        BlockData chestData = mock(BlockData.class);
        data.put(target, chestData); // changed since the sight snapshot was taken
        TileState chest = mock(TileState.class);
        when(chest.getX()).thenReturn(1);
        when(chest.getY()).thenReturn(-8);
        when(chest.getZ()).thenReturn(1);
        Location location = new Location(world, 1, -8, 1);
        when(chest.getLocation()).thenReturn(location);
        TileState hidden = mock(TileState.class);
        when(hidden.getX()).thenReturn(2);
        when(hidden.getY()).thenReturn(-8);
        when(hidden.getZ()).thenReturn(1);
        when(chunk.getTileEntities(false)).thenReturn(new BlockState[]{chest, hidden});
        updates.flush(16384, 4096);
        assertSame(chestData, sentChanges(player).get(Position.block(1, -8, 1)));
        var order = inOrder(player);
        order.verify(player).sendMultiBlockChange(anyMap());
        order.verify(player).sendBlockUpdate(location, chest);
        verify(player, never()).sendBlockUpdate(any(), eq(hidden));
    }

    @Test
    void unloadedChunksAreSkipped() {
        long unloaded = Coordinates.block(1, -8, 1), notSent = Coordinates.block(32, -8, 1);
        visibility.set(snapshot(unloaded, notSent));
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        updates.enqueue(id, worldId, unloaded);
        updates.enqueue(id, worldId, notSent);
        updates.flush(16384, 4096);
        verify(player, never()).sendMultiBlockChange(anyMap());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        assertEquals(0, updates.size());
    }

    @Test
    void nearSectionsGoFirst() {
        long far = Coordinates.block(65, -8, 1), near = Coordinates.block(1, -8, 1);
        visibility.set(snapshot(far, near));
        updates.enqueue(id, worldId, far);
        updates.enqueue(id, worldId, near);
        updates.enqueue(id, worldId, near);
        assertEquals(2, updates.size());
        updates.flush(1, 1);
        assertTrue(sentChanges(player).containsKey(Position.block(1, -8, 1)));
        assertEquals(1, updates.size());
        clearInvocations(player);
        updates.flush(1, 1);
        assertTrue(sentChanges(player).containsKey(Position.block(65, -8, 1)));
        assertEquals(0, updates.size());
    }

    @Test
    void budgetsRotatePerPlayer() {
        Player second = mock(Player.class);
        UUID secondId = UUID.randomUUID();
        when(second.getUniqueId()).thenReturn(secondId);
        when(second.getWorld()).thenReturn(world);
        when(second.getEyeLocation()).thenAnswer(call -> player.getEyeLocation());
        when(second.getSentChunkKeys()).thenReturn(Set.of(0L));
        when(second.isOnline()).thenReturn(true);
        when(server.getPlayer(secondId)).thenReturn(second);
        when(plugin.getViewerState(secondId)).thenAnswer(call -> plugin.getViewerState(id));
        long first = Coordinates.block(1, -8, 1), next = Coordinates.block(2, -8, 1);
        visibility.set(snapshot(first, next));
        updates.enqueue(id, worldId, first);
        updates.enqueue(id, worldId, next);
        updates.enqueue(secondId, worldId, first);
        updates.flush(1, 1);
        verify(second, never()).sendMultiBlockChange(anyMap());
        updates.flush(1, 1);
        verify(second).sendMultiBlockChange(anyMap());
        assertEquals(1, updates.size());
    }

    @Test
    void authoritativeUpdateBeatsStaleTracking() {
        long target = Coordinates.block(1, -8, 1);
        visibility.set(snapshot(target));
        clients.get(id, worldId).blockSent(1, -8, 1, true);
        updates.enqueue(id, worldId, target);
        data.put(target, air);
        updates.flush(16384, 4096);
        assertSame(air, sentChanges(player).get(Position.block(1, -8, 1)));
    }

    @Test
    void worldChangeDropsQueue() {
        updates.enqueue(id, UUID.randomUUID(), Coordinates.block(1, -8, 1));
        updates.flush(16384, 4096);
        verify(player, never()).sendMultiBlockChange(anyMap());
        assertEquals(0, updates.size());
    }

    private static VisibilitySnapshot snapshot(long... keys) {
        LongHashSet blocks = new LongHashSet(keys.length);
        for (long key : keys) blocks.add(key);
        return new VisibilitySnapshot(blocks, new LongHashSet(0));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<Position, BlockData> sentChanges(Player player) {
        ArgumentCaptor<Map<Position, BlockData>> captured = ArgumentCaptor.forClass(Map.class);
        verify(player).sendMultiBlockChange(captured.capture());
        return captured.getValue();
    }
}
