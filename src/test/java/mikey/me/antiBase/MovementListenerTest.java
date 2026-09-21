package mikey.me.antiBase;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.entity.FallingBlock;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.papermc.paper.math.Position;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovementListenerTest {
    private AntiBase plugin;
    private MovementListener listener;
    private Server server;
    private World world;
    private Player viewer;
    private UUID viewerId;
    private BaseObfuscator obfuscator;
    private Runnable tick;

    @BeforeEach
    void setUp() {
        plugin = mock(AntiBase.class);
        when(plugin.clientViews()).thenReturn(new ClientViewTracker());
        when(plugin.interactions()).thenReturn(new InteractionVisibility());
        when(plugin.diagnostics()).thenReturn(new ConsoleDebug());
        server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenAnswer(call -> {
            tick = call.getArgument(1);
            return mock(BukkitTask.class);
        });
        when(plugin.isObfuscationEnabled()).thenReturn(true);
        obfuscator = mock(BaseObfuscator.class);
        when(obfuscator.getScanRadius()).thenReturn(16);
        when(obfuscator.getMaxScanBlocks()).thenReturn(1000);
        when(obfuscator.getMaxRaySteps()).thenReturn(10000);
        listener = new MovementListener(plugin, obfuscator);
        world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        viewer = mock(Player.class);
        viewerId = UUID.randomUUID();
        when(viewer.getUniqueId()).thenReturn(viewerId);
        when(viewer.getWorld()).thenReturn(world);
        when(viewer.getLocation()).thenReturn(new Location(world, 0, -8, 0));
        when(plugin.getViewerState(viewerId)).thenReturn(
                new ViewerState(worldId, -64, true, 0, -8, 0, VisibilitySnapshot.EMPTY));
    }

    @AfterEach
    void tearDown() { listener.shutdown(); }

    @Test
    void hiddenFlagBeforeHidePacket() {
        Player target = mock(Player.class);
        UUID targetId = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(new Location(world, 40, -8, 0));
        listener.updateEntityForViewer(viewer, target);
        InOrder order = inOrder(plugin, viewer);
        order.verify(plugin).setHidden(viewerId, targetId, true);
        order.verify(viewer).hidePlayer(plugin, target);
        listener.updateEntityForViewer(viewer, target);
        verify(viewer, times(1)).hidePlayer(plugin, target);
        when(plugin.isObfuscationEnabled()).thenReturn(false);
        listener.updateEntityForViewer(viewer, target);
        order.verify(plugin).setHidden(viewerId, targetId, false);
        order.verify(viewer).showPlayer(plugin, target);
    }

    @Test
    void teleportClearsBeforePublish() {
        Location destination = new Location(world, 200, -40, 200);
        listener.resetPlayer(viewer, destination);
        InOrder order = inOrder(plugin);
        order.verify(plugin).setVisibility(viewerId, VisibilitySnapshot.EMPTY);
        order.verify(plugin).updatePosition(viewer, destination);
    }

    @Test
    void crossWorldTeleportClearsVisibility() {
        World destinationWorld = mock(World.class);
        when(destinationWorld.getUID()).thenReturn(UUID.randomUUID());
        Location destination = new Location(destinationWorld, 0, -8, 0);
        listener.resetPlayer(viewer, destination);
        InOrder order = inOrder(plugin);
        order.verify(plugin).setVisibility(viewerId, VisibilitySnapshot.EMPTY);
        order.verify(plugin).updatePosition(viewer, destination);
    }

    @Test
    void resetRestoresHiddenEntities() {
        Player target = mock(Player.class);
        UUID targetId = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(new Location(world, 40, -8, 0));
        when(server.getEntity(targetId)).thenReturn(target);
        listener.updateEntityForViewer(viewer, target);
        listener.resetPlayer(viewer, viewer.getLocation());
        verify(viewer).showPlayer(plugin, target);
        verify(plugin).setHidden(viewerId, targetId, false);
    }

    @Test
    void breakSendsAirNextTick() {
        verifyBreakCorrection(false);
    }

    @Test
    void cancelledBreakRestoresRealBlock() {
        verifyBreakCorrection(true);
    }

    @Test
    void unknownBlockGetsNoBypass() {
        Block block = interactionBlock();
        listener.protectInteraction(viewer, block, "damage", false);
        assertFalse(plugin.interactions().allows(viewerId, world.getUID(), 1, -8, 0));
        assertTrue(listener.queueStatus().contains("urgent=0"));
    }

    @Test
    void fullScanStartsNextTick() {
        Scene scene = prepareScene();
        tick.run(); // old scan window would be shut for four ticks now
        listener.updateVisibility(viewer);
        tick.run();
        verify(scene.chunk).getChunkSnapshot(false, false, false);
        assertTrue(listener.queueStatus().contains("running=1"));
    }

    @Test
    void repeatedExplosionsCoalesce() {
        Scene scene = prepareScene();
        trackVisibility(viewerId);
        when(world.getPlayers()).thenReturn(List.of(viewer));
        // paper grabs immutable snapshots. dont restub a mock the worker is reading:
        // concurrent mockito stubbing can attach the post blast answer to the wrong call
        ChunkSnapshot beforeBlast = mock(ChunkSnapshot.class);
        when(beforeBlast.getBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                (int) call.getArgument(1) == -8 && (int) call.getArgument(2) == 0
                        && (int) call.getArgument(0) < 12 ? scene.air : scene.solid);
        when(scene.chunk.getChunkSnapshot(false, false, false)).thenReturn(beforeBlast, scene.snapshot);
        when(scene.snapshot.getBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                (int) call.getArgument(1) == -8 && (int) call.getArgument(2) == 0
                        && (int) call.getArgument(0) < 12 ? scene.air : scene.solid);
        listener.updateVisibility(viewer);
        tick.run();
        Block destroyed = mock(Block.class);
        when(destroyed.getY()).thenReturn(-8);
        when(destroyed.getX()).thenReturn(12);
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        when(event.getLocation()).thenReturn(new Location(world, 12, -8, 0));
        when(event.blockList()).thenReturn(List.of(destroyed));
        MiningListener mining = new MiningListener(plugin, obfuscator);
        mining.onExplode(event);
        mining.onExplode(event);
        assertTrue(listener.queueStatus().contains("running=1"));
        assertTrue(listener.queueStatus().contains("explosions=1"));
        verify(viewer, never()).sendMultiBlockChange(anyMap());
        // paper applies the blast after the event, the refresh has to read that committed state
        when(scene.snapshot.getBlockData(12, -8, 0)).thenReturn(scene.air);
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        ArgumentCaptor<Map<Position, BlockData>> updates = ArgumentCaptor.forClass(Map.class);
        verify(viewer).sendMultiBlockChange(updates.capture());
        assertSame(scene.air, updates.getValue().get(Position.block(12, -8, 0)));
        assertSame(scene.solid, updates.getValue().get(Position.block(13, -8, 0)));
        assertFalse(updates.getValue().containsKey(Position.block(14, -8, 0)));
        verify(scene.chunk, times(2)).getChunkSnapshot(false, false, false); // fresh after the schedulers pre blast capture
        verify(world, never()).refreshChunk(anyInt(), anyInt());
        assertTrue(listener.queueStatus().contains("explosions=0"));
        assertTrue(listener.queueStatus().contains("running=1"));
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        verify(viewer).sendMultiBlockChange(anyMap()); // drained batch doesnt repeat
    }

    @Test
    void oldScanCannotEraseBlastReveal() throws Exception {
        Scene scene = prepareScene();
        List<VisibilitySnapshot> published = new ArrayList<>();
        AtomicReference<ViewerState> current = new AtomicReference<>(plugin.getViewerState(viewerId));
        when(plugin.getViewerState(viewerId)).thenAnswer(call -> current.get());
        doAnswer(call -> {
            VisibilitySnapshot result = call.getArgument(1);
            current.updateAndGet(state -> state.withVisibility(result));
            published.add(result);
            return null;
        }).when(plugin).setVisibility(eq(viewerId), any());
        when(world.getPlayers()).thenReturn(List.of(viewer));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ChunkSnapshot old = mock(ChunkSnapshot.class);
        when(old.getBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release the old snapshot");
            return (int) call.getArgument(1) == -8 && (int) call.getArgument(2) == 0
                    && (int) call.getArgument(0) < 12 ? scene.air : scene.solid;
        });
        when(scene.snapshot.getBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                (int) call.getArgument(1) == -8 && (int) call.getArgument(2) == 0
                        && (int) call.getArgument(0) <= 12 ? scene.air : scene.solid);
        when(scene.chunk.getChunkSnapshot(false, false, false)).thenReturn(old, scene.snapshot);
        try {
            listener.updateVisibility(viewer);
            tick.run();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Block destroyed = mock(Block.class);
            when(destroyed.getX()).thenReturn(12);
            when(destroyed.getY()).thenReturn(-8);
            listener.queueExplosion(world, List.of(destroyed));
            listener.onTickEnd(mock(ServerTickEndEvent.class));
            assertEquals(1, published.size());
            assertTrue(published.getFirst().isBlockVisible(13, -8, 0));
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (published.size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5);
                tick.run();
            }
            assertTrue(published.size() >= 2, "background scan should still finish");
            assertTrue(published.get(1).isBlockVisible(13, -8, 0), "old scan shouldnt erase the blast reveal");
        } finally {
            release.countDown();
        }
    }

    @Test
    void explosionRevealsForStationaryObservers() {
        Scene scene = prepareScene();
        trackVisibility(viewerId);
        Player observer = mock(Player.class);
        UUID observerId = UUID.randomUUID();
        when(observer.getUniqueId()).thenReturn(observerId);
        when(observer.getWorld()).thenReturn(world);
        when(observer.isOnline()).thenReturn(true);
        when(observer.getEyeLocation()).thenReturn(new Location(world, 0.5, -7.5, 0.5));
        when(observer.getSentChunkKeys()).thenReturn(Set.of(0L));
        when(server.getPlayer(observerId)).thenReturn(observer);
        ViewerState observerState = plugin.getViewerState(viewerId);
        when(plugin.getViewerState(observerId)).thenReturn(observerState);
        trackVisibility(observerId);
        when(world.getPlayers()).thenReturn(List.of(viewer, observer));
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        when(event.getLocation()).thenReturn(new Location(world, 1, -8, 0));
        Block destroyed = interactionBlock();
        when(event.blockList()).thenReturn(List.of(destroyed));
        when(event.isCancelled()).thenReturn(true);
        MiningListener mining = new MiningListener(plugin, obfuscator);
        mining.onExplode(event);
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        verify(viewer, never()).sendMultiBlockChange(anyMap());
        verify(observer, never()).sendMultiBlockChange(anyMap());
        when(event.isCancelled()).thenReturn(false);
        mining.onExplode(event);
        when(scene.snapshot.getBlockData(1, -8, 0)).thenReturn(scene.air);
        // soft budget can defer the second observer, let the next end of tick pass run
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        ArgumentCaptor<Map<Position, BlockData>> updates = ArgumentCaptor.forClass(Map.class);
        verify(viewer).sendMultiBlockChange(anyMap());
        verify(observer).sendMultiBlockChange(updates.capture());
        assertSame(scene.solid, updates.getValue().get(Position.block(2, -8, 0)));
    }

    @Test
    void chunkArrivalRetriesDistantBlocks() {
        Scene scene = prepareScene();
        LongHashSet visible = new LongHashSet(1);
        visible.add(Coordinates.block(12, -8, 0));
        ViewerState state = new ViewerState(world.getUID(), -64, true,
                0, -8, 0, new VisibilitySnapshot(visible, new LongHashSet(0)));
        when(plugin.getViewerState(viewerId)).thenReturn(state);
        listener.clientChunkSent(viewerId, world.getUID(), 0, 0);
        listener.clientChunkSent(viewerId, world.getUID(), 0, 0);
        tick.run();
        ArgumentCaptor<Map<Position, BlockData>> updates = ArgumentCaptor.forClass(Map.class);
        verify(viewer, atLeastOnce()).sendMultiBlockChange(updates.capture());
        assertEquals(1, updates.getAllValues().stream()
                .filter(batch -> batch.get(Position.block(12, -8, 0)) == scene.solid).count());
        verify(plugin, never()).setVisibility(any(), any()); // retry needs no new full result
        verify(scene.chunk).getChunkSnapshot(false, false, false);
    }

    @Test
    void oldWorldChunkIgnored() {
        Scene scene = prepareScene();
        listener.clientChunkSent(viewerId, UUID.randomUUID(), 0, 0);
        tick.run();
        verify(scene.chunk, never()).getChunkSnapshot(anyBoolean(), anyBoolean(), anyBoolean());
        verify(viewer, never()).sendMultiBlockChange(anyMap());
    }

    private void trackVisibility(UUID id) {
        AtomicReference<ViewerState> current = new AtomicReference<>(plugin.getViewerState(id));
        when(plugin.getViewerState(id)).thenAnswer(call -> current.get());
        doAnswer(call -> {
            current.updateAndGet(state -> state.withVisibility(call.getArgument(1)));
            return null;
        }).when(plugin).setVisibility(eq(id), any());
    }

    @Test
    void preloadsAroundCorner() throws Exception {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        when(viewer.getEyeHeight()).thenReturn(0.5);
        when(scene.snapshot.getBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                bentTunnel(call.getArgument(0), call.getArgument(1), call.getArgument(2)) ? scene.air : scene.solid);
        trackVisibility(viewerId);
        listener.updateVisibility(viewer);
        awaitConnectedRoom();
        assertTrue(plugin.getViewerState(viewerId).visibility().isTerrainVisible(9, -9, 9));
        listener.onMove(new PlayerMoveEvent(viewer, viewer.getLocation(), new Location(world, 9.5, -8, 9.5)));
        assertTrue(listener.queueStatus().contains("queued=0"));
        assertTrue(listener.queueStatus().contains("urgent=0"));
        verify(world, never()).refreshChunk(anyInt(), anyInt());
    }

    @Test
    void connectedResultSurvivesMovement() throws Exception {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        trackVisibility(viewerId);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(scene.snapshot.getBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Snapshot not released");
            return bentTunnel(call.getArgument(0), call.getArgument(1), call.getArgument(2)) ? scene.air : scene.solid;
        });
        try {
            listener.updateVisibility(viewer);
            tick.run();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            when(viewer.getEyeLocation()).thenReturn(new Location(world, 9.5, -7.5, 9.5));
            release.countDown();
            awaitConnectedRoom();
            assertTrue(plugin.getViewerState(viewerId).visibility().isTerrainVisible(9, -9, 9));
        } finally { release.countDown(); }
    }

    @Test
    void noPlayersThroughCorner() {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        when(scene.snapshot.getBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                bentTunnel(call.getArgument(0), call.getArgument(1), call.getArgument(2)) ? scene.air : scene.solid);
        VisibilitySnapshot flood = ConnectedVisibilityScanner.scan((x,y,z) -> bentTunnel(x,y,z) ? 0 : 1,
                .5,-7.5,.5,-64,320,0,16);
        ViewerState initial = plugin.getViewerState(viewerId).withVisibility(flood);
        when(plugin.getViewerState(viewerId)).thenReturn(initial);
        Player target = mock(Player.class);
        UUID targetId = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(new Location(world,9.5,-8,9.5));
        assertTrue(flood.isConnected(9,-8,9));
        listener.updateEntityForViewer(viewer,target);
        verify(viewer).hidePlayer(plugin,target);
        when(target.getLocation()).thenReturn(new Location(world,8.5,-8,.5));
        listener.updateEntityForViewer(viewer,target);
        verify(viewer).showPlayer(plugin,target);
    }

    @Test
    void connectedExplosionSendsNewWall() {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        VisibilitySnapshot flood = ConnectedVisibilityScanner.scan((x,y,z) -> x == 0 && y == -8 && z == 0 ? 0 : 1,
                .5,-7.5,.5,-64,320,0,16);
        ViewerState initial = plugin.getViewerState(viewerId).withVisibility(flood);
        when(plugin.getViewerState(viewerId)).thenReturn(initial);
        trackVisibility(viewerId);
        when(world.getPlayers()).thenReturn(List.of(viewer));
        listener.queueExplosion(world,List.of(interactionBlock()));
        when(scene.snapshot.getBlockData(1,-8,0)).thenReturn(scene.air);
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        ArgumentCaptor<Map<Position,BlockData>> updates = ArgumentCaptor.forClass(Map.class);
        verify(viewer).sendMultiBlockChange(updates.capture());
        assertSame(scene.air,updates.getValue().get(Position.block(1,-8,0)));
        assertSame(scene.solid,updates.getValue().get(Position.block(2,-8,0)));
        assertTrue(plugin.getViewerState(viewerId).visibility().isConnected(1,-8,0));
    }

    private boolean bentTunnel(int x, int y, int z) {
        return (y == -8 || y == -7) && ((x >= 0 && x <= 9 && z == 0) || (x == 9 && z >= 0 && z <= 10));
    }

    @Test
    void closingPassageDuringBlast() throws Exception {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        VisibilitySnapshot open = ConnectedVisibilityScanner.scan((x,y,z) -> y == -8 && z == 0 && x >= 0 && x <= 14 ? 0 : 1,
                .5,-7.5,.5,-64,320,0,16);
        ViewerState initial = plugin.getViewerState(viewerId).withVisibility(open);
        when(plugin.getViewerState(viewerId)).thenReturn(initial);
        trackVisibility(viewerId);
        when(world.getPlayers()).thenReturn(List.of(viewer));
        // close x=5 before capture. keep that immutable pre blast capture held on the worker
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ChunkSnapshot captured = mock(ChunkSnapshot.class);
        when(captured.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Snapshot not released");
            return (int)call.getArgument(1) == -8 && (int)call.getArgument(2) == 0 && (int)call.getArgument(0) != 5
                    && (int)call.getArgument(0) <= 14 ? scene.air : scene.solid;
        });
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            return z == 0 && ((y == -8 && x != 5 && x <= 14) || (x == 0 && y == -9)) ? scene.air : scene.solid;
        });
        when(scene.chunk.getChunkSnapshot(false,false,false)).thenReturn(captured,scene.snapshot);
        try {
            listener.invalidateVisibility(viewer);
            tick.run();
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Block destroyed = mock(Block.class);
            when(destroyed.getX()).thenReturn(0); when(destroyed.getY()).thenReturn(-9);
            listener.queueExplosion(world,List.of(destroyed));
            listener.onTickEnd(mock(ServerTickEndEvent.class));
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            do {
                Thread.sleep(5); tick.run();
                if (!plugin.getViewerState(viewerId).visibility().isTerrainVisible(10,-9,0)) break;
            } while (System.nanoTime()<deadline);
            VisibilitySnapshot result=plugin.getViewerState(viewerId).visibility();
            assertFalse(result.isTerrainVisible(10,-9,0), "old room shouldnt come back");
            assertTrue(result.isTerrainVisible(0,-10,0), "blast patch must survive");
        } finally { release.countDown(); }
    }

    @Test
    void blastInSealedRoomStaysHidden() {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        VisibilitySnapshot open = ConnectedVisibilityScanner.scan((x,y,z) -> y == -8 && z == 0 && x >= 0 && x <= 14 ? 0 : 1,
                .5,-7.5,.5,-64,320,0,16);
        ViewerState initial = plugin.getViewerState(viewerId).withVisibility(open);
        when(plugin.getViewerState(viewerId)).thenReturn(initial);
        trackVisibility(viewerId);
        when(world.getPlayers()).thenReturn(List.of(viewer));
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            return z == 0 && ((y == -8 && x != 5 && x <= 14) || (x == 10 && y == -9)) ? scene.air : scene.solid;
        });
        listener.invalidateVisibility(viewer); // x=5 is a closed opaque passage now
        Block destroyed = mock(Block.class);
        when(destroyed.getX()).thenReturn(10); when(destroyed.getY()).thenReturn(-9);
        listener.queueExplosion(world,List.of(destroyed));
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        assertFalse(plugin.getViewerState(viewerId).visibility().isBlockVisible(10,-10,0),
                "new wall behind the closed passage shouldnt be revealed");
    }

    private void awaitConnectedRoom() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            tick.run();
            if (plugin.getViewerState(viewerId).visibility().isConnected(9,-8,9)) return;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("The worker's connected-room result was never published");
    }

    @Test
    void emptyMovementSendsNothing() {
        Scene scene = prepareScene();
        when(scene.snapshot.getBlockData(anyInt(), anyInt(), anyInt())).thenReturn(scene.air);
        listener.onMove(new PlayerMoveEvent(viewer, new Location(world, -0.3, -8, 0), viewer.getLocation()));
        tick.run();
        assertTrue(plugin.interactions().allows(viewerId, world.getUID(), 2, -8, 0));
        verify(viewer, never()).sendMultiBlockChange(anyMap());
        verify(viewer, never()).sendBlockUpdate(any(), any());
    }

    @Test
    void movementRevealsNearbyFirst() {
        Scene scene = prepareScene();
        listener.onMove(new PlayerMoveEvent(viewer, new Location(world, -0.3, -8, 0), viewer.getLocation()));
        tick.run();
        ArgumentCaptor<Map<Position, BlockData>> updates = ArgumentCaptor.forClass(Map.class);
        verify(viewer).sendMultiBlockChange(updates.capture());
        assertSame(scene.solid, updates.getValue().get(Position.block(1, -8, 0)));
        verify(plugin, never()).setVisibility(any(), any()); // background result not published yet
        verify(scene.chunk).getChunkSnapshot(false, false, false); // local + full scans share one capture
        verify(world, never()).refreshChunk(anyInt(), anyInt());
        assertTrue(listener.queueStatus().contains("running=1"));
    }

    private Scene prepareScene() {
        when(plugin.getMovementListener()).thenReturn(listener);
        when(server.getPlayer(viewerId)).thenReturn(viewer);
        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getEyeLocation()).thenReturn(new Location(world, 0.5, -7.5, 0.5));
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(viewer.getSentChunkKeys()).thenReturn(Set.of(0L));
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        Chunk chunk = mock(Chunk.class);
        ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
        when(world.getChunkAt(0, 0)).thenReturn(chunk);
        when(chunk.getChunkSnapshot(false, false, false)).thenReturn(snapshot);
        when(chunk.getTileEntities(false)).thenReturn(new org.bukkit.block.BlockState[0]);
        when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            Block live = mock(Block.class);
            BlockData data = snapshot.getBlockData(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            when(live.getBlockData()).thenReturn(data);
            return live;
        });
        BlockData solid = mock(BlockData.class), air = mock(BlockData.class);
        when(solid.clone()).thenReturn(solid);
        when(air.clone()).thenReturn(air);
        when(solid.isOccluding()).thenReturn(true);
        when(solid.isFaceSturdy(any(org.bukkit.block.BlockFace.class), eq(org.bukkit.block.BlockSupport.FULL))).thenReturn(true);
        when(air.getMaterial()).thenReturn(Material.AIR);
        when(snapshot.getBlockData(anyInt(), anyInt(), anyInt())).thenReturn(solid);
        when(snapshot.getBlockData(0, -8, 0)).thenReturn(air);
        plugin.clientViews().reset(viewerId, world.getUID());
        plugin.clientViews().get(viewerId, world.getUID()).blockSent(1, -8, 0, true);
        return new Scene(chunk, snapshot, solid, air);
    }

    @Test
    void gravelFallRefreshesForStationaryObservers() {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        java.util.Set<Long> removed = new java.util.HashSet<>();
        VisibilityScanner.BlockAccess map = (x,y,z) -> {
            if (removed.contains(Coordinates.block(x,y,z))) return 0;
            return z == 0 && y >= -8 && y <= -5 && (x == 0 || (x >= 2 && x <= 8)) ? 0 : 1;
        };
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call ->
                map.blockAt(call.getArgument(0),call.getArgument(1),call.getArgument(2)) == 0 ? scene.air : scene.solid);
        seedConnected(map);
        trackVisibility(viewerId);
        when(world.getPlayers()).thenReturn(List.of(viewer));
        Player observer = mock(Player.class);
        UUID observerId = UUID.randomUUID();
        when(observer.getUniqueId()).thenReturn(observerId);
        when(observer.getWorld()).thenReturn(world);
        when(observer.isOnline()).thenReturn(true);
        when(observer.getEyeLocation()).thenReturn(new Location(world,.5,-7.5,.5));
        when(observer.getSentChunkKeys()).thenReturn(Set.of(0L));
        when(server.getPlayer(observerId)).thenReturn(observer);
        ViewerState initial = plugin.getViewerState(viewerId);
        when(plugin.getViewerState(observerId)).thenReturn(initial);
        trackVisibility(observerId);
        when(world.getPlayers()).thenReturn(List.of(viewer,observer));

        new MiningListener(plugin,obfuscator).onBreak(new BlockBreakEvent(physicsBlock(1,-8,0),viewer));
        removed.add(Coordinates.block(1,-8,0));
        EntityListener entities = new EntityListener(plugin,obfuscator);
        for (int y=-7; y<=-5; y++) {
            EntityChangeBlockEvent departure = new EntityChangeBlockEvent(mock(FallingBlock.class), physicsBlock(1,y,0),scene.air);
            entities.onFallingBlockChange(departure);
            removed.add(Coordinates.block(1,y,0)); // commit after the event like paper does
        }
        verify(viewer,never()).sendMultiBlockChange(anyMap());
        assertTrue(listener.queueStatus().contains("physics=2"), "one batch per observer");
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        listener.onTickEnd(mock(ServerTickEndEvent.class)); // soft budget can defer the second observer
        ArgumentCaptor<Map<Position,BlockData>> updates = ArgumentCaptor.forClass(Map.class);
        verify(viewer).sendMultiBlockChange(updates.capture());
        for (int y=-7; y<=-5; y++) assertSame(scene.air,updates.getValue().get(Position.block(1,y,0)));
        assertTrue(plugin.getViewerState(viewerId).visibility().isTerrainVisible(9,-6,0), "far wall shows without movement");
        assertTrue(plugin.getViewerState(observerId).visibility().isTerrainVisible(9,-6,0));
        verify(observer).sendMultiBlockChange(anyMap());
        assertTrue(listener.queueStatus().contains("physics=0"));
        assertTrue(listener.queueStatus().contains("explosions=0"));
        verify(world,never()).refreshChunk(anyInt(),anyInt());
    }

    @Test
    void fallingSourceKeepsWorkerRunning() throws Exception {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        VisibilityScanner.BlockAccess before = (x,y,z) -> y == -8 && z == 0 && x >= 0 && x < 12 ? 0 : 1;
        seedConnected(before);
        trackVisibility(viewerId);
        List<VisibilitySnapshot> published = new ArrayList<>();
        doAnswer(call -> {
            VisibilitySnapshot visibility = call.getArgument(1);
            ViewerState next = plugin.getViewerState(viewerId).withVisibility(visibility);
            when(plugin.getViewerState(viewerId)).thenReturn(next);
            published.add(visibility);
            return null;
        }).when(plugin).setVisibility(eq(viewerId),any());
        when(world.getPlayers()).thenReturn(List.of(viewer));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ChunkSnapshot captured = mock(ChunkSnapshot.class);
        when(captured.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Snapshot not released");
            return before.blockAt(call.getArgument(0),call.getArgument(1),call.getArgument(2)) == 0 ? scene.air : scene.solid;
        });
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call ->
                (int)call.getArgument(1) == -8 && (int)call.getArgument(2) == 0 && (int)call.getArgument(0) <= 12 ? scene.air : scene.solid);
        when(scene.chunk.getChunkSnapshot(false,false,false)).thenReturn(captured,scene.snapshot);
        try {
            listener.updateVisibility(viewer);
            tick.run();
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            new EntityListener(plugin,obfuscator).onFallingBlockChange(
                    new EntityChangeBlockEvent(mock(FallingBlock.class),physicsBlock(12,-8,0),scene.air));
            assertTrue(listener.queueStatus().contains("running=1"));
            listener.onTickEnd(mock(ServerTickEndEvent.class));
            assertTrue(plugin.getViewerState(viewerId).visibility().isTerrainVisible(13,-8,0));
            release.countDown();
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (published.size()<2 && System.nanoTime()<deadline) { Thread.sleep(5); tick.run(); }
            assertTrue(published.size()>=2);
            assertTrue(published.get(1).isTerrainVisible(13,-8,0), "old capture keeps the falling block patch");
        } finally { release.countDown(); }
    }

    @Test
    void waterloggedSourceKeepsWater() {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        seedConnected((x,y,z) -> x == 0 && y == -8 && z == 0 ? 0 : 1);
        trackVisibility(viewerId);
        when(world.getPlayers()).thenReturn(List.of(viewer));
        BlockData water = mock(BlockData.class);
        when(water.clone()).thenReturn(water);
        when(water.getMaterial()).thenReturn(Material.WATER);
        new EntityListener(plugin,obfuscator).onFallingBlockChange(
                new EntityChangeBlockEvent(mock(FallingBlock.class),physicsBlock(1,-8,0),water));
        when(scene.snapshot.getBlockData(1,-8,0)).thenReturn(water);
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        ArgumentCaptor<Map<Position,BlockData>> updates = ArgumentCaptor.forClass(Map.class);
        verify(viewer).sendMultiBlockChange(updates.capture());
        assertSame(water,updates.getValue().get(Position.block(1,-8,0)));
        assertTrue(plugin.getViewerState(viewerId).visibility().isConnected(1,-8,0));
    }

    @Test
    void landingResealsPassage() throws Exception {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        VisibilityScanner.BlockAccess open = (x,y,z) -> y == -8 && z == 0 && x >= 0 && x <= 10 ? 0 : 1;
        seedConnected(open);
        trackVisibility(viewerId);
        when(world.getPlayers()).thenReturn(List.of(viewer));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ChunkSnapshot captured = mock(ChunkSnapshot.class);
        when(captured.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            entered.countDown();
            release.await(5,TimeUnit.SECONDS);
            return open.blockAt(call.getArgument(0),call.getArgument(1),call.getArgument(2)) == 0 ? scene.air : scene.solid;
        });
        boolean[] landed = {false};
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            return open.blockAt(x,y,z) == 0 && !(landed[0] && x==5) ? scene.air : scene.solid;
        });
        when(scene.chunk.getChunkSnapshot(false,false,false)).thenReturn(captured,scene.snapshot);
        try {
            listener.updateVisibility(viewer);
            tick.run();
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            new EntityListener(plugin,obfuscator).onFallingBlockChange(
                    new EntityChangeBlockEvent(mock(FallingBlock.class),physicsBlock(5,-8,0),scene.solid));
            assertTrue(listener.queueStatus().contains("running=0"), "landing should kill the stale scan");
            verify(viewer,never()).sendMultiBlockChange(anyMap());
            landed[0] = true;
            listener.onTickEnd(mock(ServerTickEndEvent.class));
            ArgumentCaptor<Map<Position,BlockData>> updates = ArgumentCaptor.forClass(Map.class);
            verify(viewer).sendMultiBlockChange(updates.capture());
            assertSame(scene.solid,updates.getValue().get(Position.block(5,-8,0)));
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            do { Thread.sleep(5); tick.run(); }
            while (plugin.getViewerState(viewerId).visibility().isTerrainVisible(11,-8,0) && System.nanoTime()<deadline);
            assertFalse(plugin.getViewerState(viewerId).visibility().isTerrainVisible(11,-8,0));
            assertTrue(plugin.getViewerState(viewerId).visibility().isTerrainVisible(5,-8,0));
        } finally { release.countDown(); }
    }

    private void seedConnected(VisibilityScanner.BlockAccess map) {
        VisibilitySnapshot snapshot = ConnectedVisibilityScanner.scan(map,.5,-7.5,.5,-64,320,0,16);
        ViewerState state = plugin.getViewerState(viewerId).withVisibility(snapshot);
        when(plugin.getViewerState(viewerId)).thenReturn(state);
    }

    private Block physicsBlock(int x,int y,int z) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x); when(block.getY()).thenReturn(y); when(block.getZ()).thenReturn(z);
        when(block.getLocation()).thenReturn(new Location(world,x,y,z));
        when(block.getType()).thenReturn(Material.GRAVEL);
        return block;
    }

    private record Scene(Chunk chunk, ChunkSnapshot snapshot, BlockData solid, BlockData air) { }

    @Test void farEndermanCannotBypassWalls() {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        org.bukkit.entity.Enderman target = entity(org.bukkit.entity.Enderman.class, 120.5, 2.9);
        showTerrainAt(120,-8,0);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getChunkAt(anyInt(),anyInt())).thenReturn(scene.chunk);
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call ->
                (int)call.getArgument(0) == 2 ? scene.solid : scene.air);
        listener.updateEntityForViewer(viewer,target);
        verify(viewer).hideEntity(plugin,target);
    }

    @Test void staleTerrainCannotShowEntities() {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(false);
        org.bukkit.entity.Enderman target = entity(org.bukkit.entity.Enderman.class, 4.5, 2.9);
        showTerrainAt(4,-8,0);
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call ->
                (int)call.getArgument(0) == 2 ? scene.solid : scene.air);
        listener.updateEntityForViewer(viewer,target);
        verify(viewer).hideEntity(plugin,target);
    }

    @Test void entityHeightIsUsedForSight() {
        Scene scene = prepareScene();
        org.bukkit.entity.Enderman enderman = entity(org.bukkit.entity.Enderman.class,4.0,2.9);
        org.bukkit.entity.Minecart cart = entity(org.bukkit.entity.Minecart.class,4.0,.7);
        // start hidden, then move the terrain result onto the targets actual cells
        listener.updateEntityForViewer(viewer,enderman);
        listener.updateEntityForViewer(viewer,cart);
        showTerrainAt(4,-8,0);
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call ->
                (int)call.getArgument(0) == 3 && (int)call.getArgument(1) <= -7 ? scene.solid : scene.air);
        listener.updateEntityForViewer(viewer,enderman);
        verify(viewer).showEntity(plugin,enderman); // the head actually pokes above the two block wall
        listener.updateEntityForViewer(viewer,cart);
        verify(viewer,never()).showEntity(plugin,cart);
    }

    @Test void minecartCannotPeekOverWall() {
        Scene scene = prepareScene();
        org.bukkit.entity.Minecart cart = entity(org.bukkit.entity.Minecart.class,4.0,.7);
        showTerrainAt(4,-8,0);
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call ->
                (int)call.getArgument(0) == 3 && (int)call.getArgument(1) == -8 ? scene.solid : scene.air);
        listener.updateEntityForViewer(viewer,cart);
        verify(viewer).hideEntity(plugin,cart);
    }

    @Test void trackedFarMobStillRechecked() {
        Scene scene = prepareScene();
        org.bukkit.entity.Enderman target = entity(org.bukkit.entity.Enderman.class,80.5,2.9);
        showTerrainAt(80,-8,0);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getChunkAt(anyInt(),anyInt())).thenReturn(scene.chunk);
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenReturn(scene.air);
        assertTrue(listener.trackEntity(viewer,target));
        when(viewer.getNearbyEntities(64,64,64)).thenReturn(List.of());
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call ->
                (int)call.getArgument(0) == 2 ? scene.solid : scene.air);
        tick.run();
        verify(viewer).hideEntity(plugin,target);
    }

    @Test void teleportHidesBeforeCommit() {
        Scene scene = prepareScene();
        org.bukkit.entity.Enderman target = entity(org.bukkit.entity.Enderman.class,.5,2.9);
        showTerrainAt(4,-8,0);
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call ->
                (int)call.getArgument(0) == 2 ? scene.solid : scene.air);
        EntityListener events = new EntityListener(plugin,obfuscator);
        Location blocked = new Location(world,4.5,-8,.5);
        events.onTeleport(new org.bukkit.event.entity.EntityTeleportEvent(target,target.getLocation(),blocked));
        verify(viewer).hideEntity(plugin,target);
        when(target.getLocation()).thenReturn(blocked);
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        verify(viewer,never()).showEntity(plugin,target);
        Location open = new Location(world,.5,-8,.5);
        showTerrainAt(0,-8,0);
        events.onTeleport(new org.bukkit.event.entity.EntityTeleportEvent(target,blocked,open));
        verify(viewer,never()).showEntity(plugin,target);
        when(target.getLocation()).thenReturn(open);
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        verify(viewer).showEntity(plugin,target);
    }

    @Test void cancelledTeleportDoesNothing() {
        prepareScene();
        org.bukkit.entity.Enderman target = entity(org.bukkit.entity.Enderman.class,.5,2.9);
        var event = new org.bukkit.event.entity.EntityTeleportEvent(target,target.getLocation(),new Location(world,4.5,-8,.5));
        event.setCancelled(true);
        new EntityListener(plugin,obfuscator).onTeleport(event);
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        verify(viewer,never()).hideEntity(any(),any());
        verify(viewer,never()).showEntity(any(),any());
    }

    @Test void cancelledPairingRecovers() {
        Scene scene = prepareScene();
        org.bukkit.entity.Enderman target = entity(org.bukkit.entity.Enderman.class,4.5,2.9);
        var event = new io.papermc.paper.event.player.PlayerTrackEntityEvent(viewer,target);
        new EntityListener(plugin,obfuscator).onTrack(event);
        assertTrue(event.isCancelled());
        verify(viewer,never()).hideEntity(any(),any()); // no tracker changes from inside its own callback
        showTerrainAt(4,-8,0);
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenReturn(scene.air);
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        var order = inOrder(viewer);
        order.verify(viewer).hideEntity(plugin,target);
        order.verify(viewer).showEntity(plugin,target);
    }

    @Test void localRefreshSendsPadding() {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        when(obfuscator.getTerrainPadding()).thenReturn(2);
        Block block = interactionBlock();
        listener.protectInteraction(viewer,block,"damage",false);
        tick.run();
        ArgumentCaptor<Map<Position,BlockData>> updates = ArgumentCaptor.forClass(Map.class);
        verify(viewer).sendMultiBlockChange(updates.capture());
        assertSame(scene.solid,updates.getValue().get(Position.block(3,-8,0)));
        assertFalse(updates.getValue().containsKey(Position.block(4,-8,0)));
    }

    @Test void fallingBlockPaddingIsNotConnected() {
        Scene scene = prepareScene();
        when(obfuscator.usesConnectedRendering()).thenReturn(true);
        when(obfuscator.getTerrainPadding()).thenReturn(2);
        seedConnected((x,y,z) -> x == 0 && y == -8 && z == 0 ? 0 : 1);
        trackVisibility(viewerId);
        when(world.getPlayers()).thenReturn(List.of(viewer));
        listener.queueFallingBlockChange(physicsBlock(1,-8,0),scene.air);
        when(scene.snapshot.getBlockData(1,-8,0)).thenReturn(scene.air);
        listener.onTickEnd(mock(ServerTickEndEvent.class));
        ArgumentCaptor<Map<Position,BlockData>> updates = ArgumentCaptor.forClass(Map.class);
        verify(viewer).sendMultiBlockChange(updates.capture());
        assertSame(scene.air,updates.getValue().get(Position.block(1,-8,0)));
        assertSame(scene.solid,updates.getValue().get(Position.block(4,-8,0)));
        assertFalse(updates.getValue().containsKey(Position.block(5,-8,0)));
        assertFalse(plugin.getViewerState(viewerId).visibility().isConnected(2,-8,0));
    }

    @Test void untrackedMobDroppedFromChecks() {
        Scene scene = prepareScene();
        org.bukkit.entity.Enderman target = entity(org.bukkit.entity.Enderman.class,80.5,2.9);
        showTerrainAt(80,-8,0);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getChunkAt(anyInt(),anyInt())).thenReturn(scene.chunk);
        when(scene.snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenReturn(scene.air);
        assertTrue(listener.trackEntity(viewer,target));
        listener.untrackEntity(viewer,target);
        clearInvocations(server);
        tick.run();
        verify(server,never()).getEntity(target.getUniqueId());
    }

    private <T extends org.bukkit.entity.Entity> T entity(Class<T> type, double x, double height) {
        T target = mock(type);
        UUID id = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(id);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(new Location(world,x,-8,.5));
        when(target.getHeight()).thenReturn(height);
        when(server.getEntity(id)).thenReturn(target);
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(viewer));
        when(world.getPlayers()).thenReturn(List.of(viewer));
        return target;
    }

    private void showTerrainAt(int x, int y, int z) {
        LongHashSet cells = new LongHashSet(3);
        for (int dy = 0; dy <= 2; dy++) cells.add(Coordinates.block(x,y+dy,z));
        ViewerState next = plugin.getViewerState(viewerId).withVisibility(new VisibilitySnapshot(cells,new LongHashSet(0)));
        when(plugin.getViewerState(viewerId)).thenReturn(next);
    }

    private void verifyBreakCorrection(boolean cancelled) {
        Scene scene = prepareScene();
        Block block = interactionBlock();
        BlockBreakEvent event = new BlockBreakEvent(block, viewer);
        event.setCancelled(cancelled);
        new MiningListener(plugin, obfuscator).onBreak(event);
        assertTrue(plugin.interactions().isPinned(viewerId, world.getUID(), 1, -8, 0));
        verify(viewer, never()).sendMultiBlockChange(anyMap());
        // apply the servers result AFTER the event like paper does
        BlockData committed = cancelled ? scene.solid : scene.air;
        when(scene.snapshot.getBlockData(1, -8, 0)).thenReturn(committed);
        tick.run();
        ArgumentCaptor<Map<Position, BlockData>> updates = ArgumentCaptor.forClass(Map.class);
        verify(viewer).sendMultiBlockChange(updates.capture());
        assertSame(committed, updates.getValue().get(Position.block(1, -8, 0)));
        assertEquals(cancelled, event.isCancelled(), "dont touch other plugins cancellation");
    }

    private Block interactionBlock() {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(1);
        when(block.getY()).thenReturn(-8);
        when(block.getZ()).thenReturn(0);
        when(block.getLocation()).thenReturn(new Location(world, 1, -8, 0));
        return block;
    }
}
