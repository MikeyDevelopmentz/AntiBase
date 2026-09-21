package mikey.me.antiBase;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.entity.Player;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketHandlerTest {
    private final ClientViewTracker clients = new ClientViewTracker();
    private final InteractionVisibility interactions = new InteractionVisibility();
    private final ConsoleDebug debug = new ConsoleDebug();

    private PacketHandler handler(AntiBase plugin, BaseObfuscator obfuscator) {
        when(plugin.clientViews()).thenReturn(clients);
        when(plugin.interactions()).thenReturn(interactions);
        when(plugin.diagnostics()).thenReturn(debug);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("AntiBase-test"));
        return new PacketHandler(plugin, obfuscator);
    }
    private BaseObfuscator obfuscator(int cutoff) {
        BaseObfuscator obfuscator = mock(BaseObfuscator.class);
        when(obfuscator.getHideBelowY()).thenReturn(cutoff);
        when(obfuscator.getAirStateId()).thenReturn(0);
        return obfuscator;
    }

    @Test
    void negativeHeightIsHandled() {
        BaseChunk section = new Chunk_v1_18();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) section.set(x, y, z, 1);
        Column column = new Column(-2, -2, true, new BaseChunk[]{section}, new TileEntity[0]);
        ViewerState viewer = new ViewerState(UUID.randomUUID(), -64, true, 1000, 60, 1000, VisibilitySnapshot.EMPTY);
        Column result = handler(mock(AntiBase.class), obfuscator(-57)).maskColumn(column, viewer);
        for (int y = 0; y < 16; y++) {
            assertEquals(y < 7 ? 0 : 1, result.getChunks()[0].getBlockId(0, y, 0));
            assertEquals(y < 7 ? 0 : 1, result.getChunks()[0].getBlockId(15, y, 15));
            assertEquals(1, section.getBlockId(0, y, 0), "source column should be untouched");
        }
    }

    @Test
    void visibleBlocksAreKept() {
        BaseChunk section = new Chunk_v1_18();
        section.set(0, 0, 0, 1);
        LongHashSet visible = new LongHashSet(1);
        visible.add(Coordinates.section(-2, -4, -2));
        LongHashSet blocks = new LongHashSet(1);
        blocks.add(Coordinates.block(-32, -64, -32));
        ViewerState viewer = new ViewerState(UUID.randomUUID(), -64, true, 1000, 60, 1000,
                new VisibilitySnapshot(blocks, visible));
        Column column = new Column(-2, -2, true, new BaseChunk[]{section}, new TileEntity[0]);
        Column result = handler(mock(AntiBase.class), obfuscator(0)).maskColumn(column, viewer);
        assertEquals(1, result.getChunks()[0].getBlockId(0, 0, 0));
        assertEquals(0, result.getChunks()[0].getBlockId(1, 0, 0));
    }

    @Test
    void noProximityBypass() {
        BaseChunk section = new Chunk_v1_18();
        section.set(0, 0, 0, 1);
        Column column = new Column(0, 0, true, new BaseChunk[]{section}, new TileEntity[0]);
        ViewerState viewer = new ViewerState(UUID.randomUUID(), -64, true, 0, -64, 0, VisibilitySnapshot.EMPTY);
        Column result = handler(mock(AntiBase.class), obfuscator(0)).maskColumn(column, viewer);
        assertEquals(0, result.getChunks()[0].getBlockId(0, 0, 0));
        assertEquals(0, result.getChunks()[0].getBlockId(15, 15, 15));
    }

    @Test
    void hiddenTileEntitiesAreStripped() {
        TileEntity hidden = mock(TileEntity.class), above = mock(TileEntity.class);
        when(hidden.getX()).thenReturn(1);
        when(hidden.getY()).thenReturn(-63);
        when(hidden.getZ()).thenReturn(0);
        when(above.getY()).thenReturn(-57);
        Column column = new Column(-2, -2, true, new BaseChunk[]{new Chunk_v1_18()}, new TileEntity[]{hidden, above});
        ViewerState viewer = new ViewerState(UUID.randomUUID(), -64, true, 1, -63, 2, VisibilitySnapshot.EMPTY);
        Column result = handler(mock(AntiBase.class), obfuscator(-57)).maskColumn(column, viewer);
        assertArrayEquals(new TileEntity[]{above}, result.getTileEntities());
        assertEquals(2, column.getTileEntities().length);
    }

    @Test
    void multiKeepsVisibleBlocks() {
        AntiBase plugin = mock(AntiBase.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(plugin.isObfuscationEnabled()).thenReturn(true);
        LongHashSet sections = new LongHashSet(1);
        sections.add(Coordinates.section(4, -1, 0));
        LongHashSet visibleBlocks = new LongHashSet(1);
        visibleBlocks.add(Coordinates.block(64, -8, 0));
        when(plugin.getViewerState(id)).thenReturn(new ViewerState(UUID.randomUUID(), -64, true, 0, -8, 0,
                new VisibilitySnapshot(visibleBlocks, sections)));
        WrapperPlayServerMultiBlockChange source = mock(WrapperPlayServerMultiBlockChange.class, CALLS_REAL_METHODS);
        source.setBlocks(new WrapperPlayServerMultiBlockChange.EncodedBlock[]{
                new WrapperPlayServerMultiBlockChange.EncodedBlock(1, 48, -8, 0),
                new WrapperPlayServerMultiBlockChange.EncodedBlock(1, 64, -8, 0),
                new WrapperPlayServerMultiBlockChange.EncodedBlock(1, 0, -8, 0)});
        PacketSendEvent event = packetEvent(player, PacketType.Play.Server.MULTI_BLOCK_CHANGE, source);
        handler(plugin, obfuscator(0)).onPacketSend(event);
        ArgumentCaptor<PacketWrapper<?>> rewritten = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(event).setLastUsedWrapper(rewritten.capture());
        var blocks = ((WrapperPlayServerMultiBlockChange) rewritten.getValue()).getBlocks();
        assertEquals(0, blocks[0].getBlockId());
        assertEquals(1, blocks[1].getBlockId());
        assertEquals(0, blocks[2].getBlockId());
        assertEquals(1, source.getBlocks()[0].getBlockId());
        verify(event).markForReEncode(true);
        verify(player, never()).getWorld();
        verify(player, never()).getLocation();
    }

    @Test
    void hiddenBlockEntityIsCancelled() {
        AntiBase plugin = mock(AntiBase.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(plugin.isObfuscationEnabled()).thenReturn(true);
        when(plugin.getViewerState(id)).thenReturn(new ViewerState(UUID.randomUUID(), -64, true, 1000, 60, 1000, VisibilitySnapshot.EMPTY));
        WrapperPlayServerBlockEntityData source = mock(WrapperPlayServerBlockEntityData.class, CALLS_REAL_METHODS);
        source.setPosition(new Vector3i(-32, -63, -32));
        PacketSendEvent event = packetEvent(player, PacketType.Play.Server.BLOCK_ENTITY_DATA, source);
        handler(plugin, obfuscator(0)).onPacketSend(event);
        verify(event).setCancelled(true);
    }

    @Test
    void hiddenUpdatesSuppressedAfterChunk() {
        Scenario s = hiddenScenario();
        MovementListener movement = mock(MovementListener.class);
        when(s.plugin.getMovementListener()).thenReturn(movement);
        BaseChunk section = new Chunk_v1_18();
        section.set(1, 1, 1, 42);
        WrapperPlayServerChunkData source = mock(WrapperPlayServerChunkData.class, CALLS_REAL_METHODS);
        source.setColumn(new Column(0, 0, true, new BaseChunk[]{section}, new TileEntity[0]));
        PacketSendEvent event = packetEvent(s.player, PacketType.Play.Server.CHUNK_DATA, source);
        s.handler.onPacketSend(event);
        assertFalse(s.view.isKnownMasked(1, -63, 1));
        verifyNoInteractions(movement);
        ArgumentCaptor<PacketWrapper<?>> rewritten = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(event).setLastUsedWrapper(rewritten.capture());
        assertEquals(0, ((WrapperPlayServerChunkData) rewritten.getValue()).getColumn().getChunks()[0].getBlockId(1, 1, 1));
        assertEquals(42, section.getBlockId(1, 1, 1));
        event.getTasksAfterSend().forEach(Runnable::run);
        verify(movement).clientChunkSent(s.id, s.world, 0, 0);
        assertTrue(s.view.isKnownMasked(1, -63, 1));
        PacketSendEvent change = single(s.player, 1, -63, 1, 53);
        s.handler.onPacketSend(change);
        verify(change).setCancelled(true);
        assertTrue(change.getTasksAfterSend().isEmpty());
    }

    @Test
    void revealedBlocksGetAirBeforeSuppress() {
        Scenario s = hiddenScenario();
        s.view.blockSent(1, -8, 1, true);
        PacketSendEvent clear = single(s.player, 1, -8, 1, 42);
        s.handler.onPacketSend(clear);
        verify(clear, never()).setCancelled(true);
        ArgumentCaptor<PacketWrapper<?>> rewritten = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(clear).setLastUsedWrapper(rewritten.capture());
        assertEquals(0, ((WrapperPlayServerBlockChange) rewritten.getValue()).getBlockId());
        assertTrue(s.view.wasReal(1, -8, 1), "no record before the send");
        clear.getTasksAfterSend().forEach(Runnable::run);
        assertTrue(s.view.isKnownMasked(1, -8, 1));
        PacketSendEvent later = single(s.player, 1, -8, 1, 53);
        s.handler.onPacketSend(later);
        verify(later).setCancelled(true);
    }

    @Test
    void miningCorrectionBeatsStaleScan() {
        Scenario s = hiddenScenario();
        s.view.chunkSent(0, 0, new LongHashSet(0));
        interactions.pin(s.id, s.world, 1, -8, 1);
        // good break sends AIR, cancelled break sends whatever the server already has
        for (int authoritativeState : new int[]{0, 42}) {
            PacketSendEvent correction = single(s.player, 1, -8, 1, authoritativeState);
            s.handler.onPacketSend(correction);
            verify(correction, never()).setCancelled(true);
            verify(correction, never()).markForReEncode(true);
            ArgumentCaptor<PacketWrapper<?>> outgoing = ArgumentCaptor.forClass(PacketWrapper.class);
            verify(correction).setLastUsedWrapper(outgoing.capture());
            assertEquals(authoritativeState, ((WrapperPlayServerBlockChange) outgoing.getValue()).getBlockId());
        }
        PacketSendEvent neighbor = single(s.player, 2, -8, 1, 42);
        s.handler.onPacketSend(neighbor);
        verify(neighbor).setCancelled(true);
    }

    @Test
    void mixedMultiClearsOldRealData() {
        Scenario s = hiddenScenario();
        s.view.chunkSent(0, 0, new LongHashSet(0));
        s.view.blockSent(3, -8, 1, true);
        interactions.pin(s.id, s.world, 2, -8, 1);
        WrapperPlayServerMultiBlockChange source = mock(WrapperPlayServerMultiBlockChange.class, CALLS_REAL_METHODS);
        source.setBlocks(new WrapperPlayServerMultiBlockChange.EncodedBlock[]{
                new WrapperPlayServerMultiBlockChange.EncodedBlock(42, 1, -8, 1),
                new WrapperPlayServerMultiBlockChange.EncodedBlock(53, 2, -8, 1),
                new WrapperPlayServerMultiBlockChange.EncodedBlock(64, 3, -8, 1)});
        PacketSendEvent event = packetEvent(s.player, PacketType.Play.Server.MULTI_BLOCK_CHANGE, source);
        s.handler.onPacketSend(event);
        ArgumentCaptor<PacketWrapper<?>> rewritten = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(event).setLastUsedWrapper(rewritten.capture());
        var outgoing = ((WrapperPlayServerMultiBlockChange) rewritten.getValue()).getBlocks();
        assertEquals(2, outgoing.length);
        assertEquals(2, outgoing[0].getX());
        assertEquals(53, outgoing[0].getBlockId());
        assertEquals(3, outgoing[1].getX());
        assertEquals(0, outgoing[1].getBlockId());
        assertEquals(64, source.getBlocks()[2].getBlockId());
        event.getTasksAfterSend().forEach(Runnable::run);
        assertTrue(s.view.wasReal(2, -8, 1));
        assertTrue(s.view.isKnownMasked(3, -8, 1));
    }

    @Test
    void fullyMaskedMultiIsCancelled() {
        Scenario s = hiddenScenario();
        s.view.chunkSent(0, 0, new LongHashSet(0));
        WrapperPlayServerMultiBlockChange source = mock(WrapperPlayServerMultiBlockChange.class, CALLS_REAL_METHODS);
        source.setBlocks(new WrapperPlayServerMultiBlockChange.EncodedBlock[]{
                new WrapperPlayServerMultiBlockChange.EncodedBlock(42, 1, -8, 1)});
        PacketSendEvent event = packetEvent(s.player, PacketType.Play.Server.MULTI_BLOCK_CHANGE, source);
        s.handler.onPacketSend(event);
        verify(event).setCancelled(true);
        assertTrue(event.getTasksAfterSend().isEmpty());
    }

    @Test
    void miningAcksAreUntouched() {
        Scenario s = hiddenScenario();
        PacketSendEvent event = packetEvent(s.player, PacketType.Play.Server.ACKNOWLEDGE_BLOCK_CHANGES, null);
        s.handler.onPacketSend(event);
        verify(event, never()).setCancelled(anyBoolean());
        verify(event, never()).markForReEncode(anyBoolean());
        verify(event, never()).setLastUsedWrapper(any());
    }

    private Scenario hiddenScenario() {
        AntiBase plugin = mock(AntiBase.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID(), world = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(plugin.isObfuscationEnabled()).thenReturn(true);
        when(plugin.getViewerState(id)).thenReturn(new ViewerState(world, -64, true, 0, -8, 0, VisibilitySnapshot.EMPTY));
        clients.reset(id, world);
        return new Scenario(player, id, world, clients.get(id, world), handler(plugin, obfuscator(0)), plugin);
    }

    private record Scenario(Player player, UUID id, UUID world, ClientViewTracker.View view, PacketHandler handler, AntiBase plugin) { }

    private PacketSendEvent single(Player player, int x, int y, int z, int state) {
        WrapperPlayServerBlockChange source = mock(WrapperPlayServerBlockChange.class, CALLS_REAL_METHODS);
        source.setBlockPosition(new Vector3i(x, y, z));
        source.setBlockID(state);
        return packetEvent(player, PacketType.Play.Server.BLOCK_CHANGE, source);
    }

    private PacketSendEvent packetEvent(Player player, PacketType.Play.Server type, PacketWrapper<?> source) {
        PacketSendEvent event = mock(PacketSendEvent.class);
        User user = mock(User.class);
        when(user.getClientVersion()).thenReturn(ClientVersion.V_1_21_11);
        when(event.getUser()).thenReturn(user);
        when(event.getServerVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(event.getPlayer()).thenReturn(player);
        when(event.getPacketType()).thenReturn(type);
        doReturn(source).when(event).getLastUsedWrapper();
        when(event.getTasksAfterSend()).thenReturn(new java.util.ArrayList<>());
        return event;
    }
}

