package mikey.me.antiBase;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class PacketHandler extends PacketListenerAbstract {
    enum UpdateAction { REAL, CLEAR, SUPPRESS }
    private final BaseObfuscator obfuscator;
    private final AntiBase plugin;
    private final ClientViewTracker clients;
    private final InteractionVisibility interactions;
    private final ConsoleDebug debug;
    private final AtomicLong lastError = new AtomicLong();

    public PacketHandler(AntiBase plugin, BaseObfuscator obfuscator) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.obfuscator = obfuscator;
        clients = plugin.clientViews();
        interactions = plugin.interactions();
        debug = plugin.diagnostics();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled()) return;
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.CHUNK_DATA && type != PacketType.Play.Server.BLOCK_CHANGE
                && type != PacketType.Play.Server.MULTI_BLOCK_CHANGE && type != PacketType.Play.Server.BLOCK_ENTITY_DATA
                && type != PacketType.Play.Server.PLAYER_INFO_REMOVE && type != PacketType.Play.Server.UNLOAD_CHUNK) return;
        // mining acks are not touched on purpose
        Player player = event.getPlayer();
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        ViewerState state = plugin.getViewerState(playerId);
        if (state == null) return;
        ClientViewTracker.View view = clients.get(playerId, state.worldId());
        try {
            if (type == PacketType.Play.Server.UNLOAD_CHUNK) {
                WrapperPlayServerUnloadChunk packet = new WrapperPlayServerUnloadChunk(event);
                if (view != null) event.getTasksAfterSend().add(() -> view.unload(packet.getChunkX(), packet.getChunkZ()));
                return;
            }
            if (!plugin.isObfuscationEnabled() || !state.protectedWorld()) return;
            if (type == PacketType.Play.Server.CHUNK_DATA) {
                WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(event);
                Column source = packet.getColumn();
                if (source == null || source.getChunks() == null) return;
                MaskedColumn masked = maskColumn(source, state, playerId);
                packet.setColumn(masked.column());
                event.markForReEncode(true);
                if (view != null) event.getTasksAfterSend().add(() -> {
                    view.chunkSent(source.getX(), source.getZ(), masked.real());
                    MovementListener movement = plugin.getMovementListener();
                    if (movement != null && clients.get(playerId, state.worldId()) == view) {
                        movement.clientChunkSent(playerId, state.worldId(), source.getX(), source.getZ());
                    }
                });
                debug.count(playerId, ConsoleDebug.Metric.CHUNKS, 1);
                debug.count(playerId, ConsoleDebug.Metric.MASKED_BLOCKS, masked.masked());
            } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
                WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
                Vector3i position = packet.getBlockPosition();
                UpdateAction action = updateAction(playerId, state, view, position.getX(), position.getY(), position.getZ());
                recordDecision(playerId, state, position.getX(), position.getY(), position.getZ(), action, "single");
                if (action == UpdateAction.SUPPRESS) event.setCancelled(true);
                else {
                    if (action == UpdateAction.CLEAR) {
                        packet.setBlockID(obfuscator.getAirStateId());
                        event.markForReEncode(true);
                    }
                    afterBlock(event, view, position.getX(), position.getY(), position.getZ(), action);
                }
            } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
                WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(event);
                List<WrapperPlayServerMultiBlockChange.EncodedBlock> outgoing = new ArrayList<>();
                for (WrapperPlayServerMultiBlockChange.EncodedBlock block : packet.getBlocks()) {
                    UpdateAction action = updateAction(playerId, state, view, block.getX(), block.getY(), block.getZ());
                    recordDecision(playerId, state, block.getX(), block.getY(), block.getZ(), action, "multi");
                    if (action == UpdateAction.SUPPRESS) continue;
                    outgoing.add(action == UpdateAction.CLEAR
                            ? new WrapperPlayServerMultiBlockChange.EncodedBlock(obfuscator.getAirStateId(),
                                    block.getX(), block.getY(), block.getZ()) : block);
                    afterBlock(event, view, block.getX(), block.getY(), block.getZ(), action);
                }
                if (outgoing.isEmpty()) event.setCancelled(true);
                else {
                    packet.setBlocks(outgoing.toArray(WrapperPlayServerMultiBlockChange.EncodedBlock[]::new));
                    event.markForReEncode(true);
                }
            } else if (type == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
                Vector3i position = new WrapperPlayServerBlockEntityData(event).getPosition();
                if (shouldHide(playerId, state, position.getX(), position.getY(), position.getZ())) {
                    event.setCancelled(true);
                    debug.count(playerId, ConsoleDebug.Metric.METADATA_DROPPED, 1);
                }
            } else {
                WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(event);
                List<UUID> remaining = packet.getProfileIds().stream().filter(id -> !plugin.isHidden(playerId, id)).toList();
                if (remaining.isEmpty()) event.setCancelled(true);
                else { packet.setProfileIds(remaining); event.markForReEncode(true); }
            }
        } catch (Exception exception) {
            event.setCancelled(true);
            long now = System.nanoTime(), previous = lastError.get();
            if ((previous == 0 || now - previous > 10_000_000_000L) && lastError.compareAndSet(previous, now)) {
                plugin.getLogger().log(Level.SEVERE, "Cancelled a protected packet after an obfuscation error.", exception);
            }
        }
    }

    UpdateAction updateAction(UUID player, ViewerState state, ClientViewTracker.View view, int x, int y, int z) {
        if (!shouldHide(player, state, x, y, z)) return UpdateAction.REAL;
        // suppressing is only safe if we actually sent a placeholder before. revealed/unknown
        // blocks need AIR or the client keeps stale solid blocks, cancelling alone isnt enough
        return view != null && view.isKnownMasked(x, y, z) ? UpdateAction.SUPPRESS : UpdateAction.CLEAR;
    }

    private boolean shouldHide(UUID player, ViewerState state, int x, int y, int z) {
        return state.shouldHide(x, y, z, obfuscator.getHideBelowY())
                && (player == null || !interactions.allows(player, state.worldId(), x, y, z));
    }

    private void afterBlock(PacketSendEvent event, ClientViewTracker.View view, int x, int y, int z, UpdateAction action) {
        if (view != null && y < obfuscator.getHideBelowY()) {
            event.getTasksAfterSend().add(() -> view.blockSent(x, y, z, action == UpdateAction.REAL));
        }
    }

    private void recordDecision(UUID player, ViewerState state, int x, int y, int z, UpdateAction action, String packet) {
        ConsoleDebug.Metric metric = switch (action) {
            case REAL -> ConsoleDebug.Metric.REAL_UPDATES;
            case CLEAR -> ConsoleDebug.Metric.AIR_CLEARS;
            case SUPPRESS -> ConsoleDebug.Metric.SUPPRESSED_UPDATES;
        };
        debug.count(player, metric, 1);
        boolean interaction = interactions.allows(player, state.worldId(), x, y, z);
        if (interaction) debug.count(player, ConsoleDebug.Metric.INTERACTION_CORRECTIONS, 1);
        if (debug.accepts(player) && (interaction || action != UpdateAction.REAL)) {
            String message = "packet=" + packet + " world=" + state.worldId() + " pos=" + x + "," + y + "," + z
                    + " action=" + action + " scanVisible=" + state.visibility().isBlockVisible(x, y, z)
                    + " interaction=" + interaction;
            if (interactions.isPinned(player, state.worldId(), x, y, z)) debug.important(player, message);
            else if (action != UpdateAction.REAL) debug.sample(player, message);
        }
    }

    Column maskColumn(Column column, ViewerState state) { return maskColumn(column, state, null).column(); }

    private MaskedColumn maskColumn(Column column, ViewerState state, UUID player) {
        BaseChunk[] chunks = column.getChunks().clone();
        LongHashSet real = new LongHashSet(256);
        int maskedCount = 0;
        for (int index = 0; index < chunks.length; index++) {
            BaseChunk section = chunks[index];
            int baseY = state.minHeight() + index * 16;
            if (baseY >= obfuscator.getHideBelowY()) break;
            if (section == null) continue;
            Chunk_v1_18 masked = new Chunk_v1_18(0, PaletteType.CHUNK.create(), ((Chunk_v1_18) section).getBiomeData());
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int bx = (column.getX() << 4) + x, by = baseY + y, bz = (column.getZ() << 4) + z;
                if (shouldHide(player, state, bx, by, bz)) {
                    masked.set(x, y, z, obfuscator.getAirStateId());
                    maskedCount++;
                } else {
                    masked.set(x, y, z, section.getBlockId(x, y, z));
                    if (by < obfuscator.getHideBelowY()) real.add(Coordinates.block(bx, by, bz));
                }
            }
            chunks[index] = masked;
        }
        TileEntity[] visibleEntities = Arrays.stream(column.getTileEntities())
                .filter(entity -> !shouldHide(player, state, (column.getX() << 4) + entity.getX(),
                        entity.getY(), (column.getZ() << 4) + entity.getZ()))
                .toArray(TileEntity[]::new);
        return new MaskedColumn(new Column(column.getX(), column.getZ(), column.isFullChunk(),
                chunks, visibleEntities, column.getHeightmaps()), real, maskedCount);
    }

    private record MaskedColumn(Column column, LongHashSet real, int masked) { }
}
