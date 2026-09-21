package mikey.me.antiBase;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class EntityListenerTest {
    @Test void cancelledPairingIsLeftAlone() {
        AntiBase plugin = mock(AntiBase.class);
        BaseObfuscator config = mock(BaseObfuscator.class);
        when(plugin.isObfuscationEnabled()).thenReturn(true);
        var event = new io.papermc.paper.event.player.PlayerTrackEntityEvent(mock(org.bukkit.entity.Player.class),mock(Entity.class));
        event.setCancelled(true);
        new EntityListener(plugin,config).onTrack(event);
        org.junit.jupiter.api.Assertions.assertTrue(event.isCancelled());
        verify(plugin,never()).getMovementListener();
    }
    @Test void onlyFallingBlocksQueueWork() {
        AntiBase plugin = mock(AntiBase.class);
        BaseObfuscator config = mock(BaseObfuscator.class);
        when(plugin.isObfuscationEnabled()).thenReturn(true);
        EntityListener listener = new EntityListener(plugin, config);
        EntityChangeBlockEvent cancelled = new EntityChangeBlockEvent(mock(FallingBlock.class), mock(Block.class), mock(BlockData.class));
        cancelled.setCancelled(true);
        listener.onFallingBlockChange(cancelled);
        listener.onFallingBlockChange(new EntityChangeBlockEvent(mock(Entity.class), mock(Block.class), mock(BlockData.class)));
        verify(plugin, never()).getMovementListener();
    }

    @Test void blacklistedWorldsQueueNothing() {
        AntiBase plugin = mock(AntiBase.class);
        BaseObfuscator config = mock(BaseObfuscator.class);
        EntityListener listener = new EntityListener(plugin, config);
        Block block = mock(Block.class);
        World world = mock(World.class);
        when(block.getWorld()).thenReturn(world);
        EntityChangeBlockEvent event = new EntityChangeBlockEvent(mock(FallingBlock.class), block, mock(BlockData.class));
        listener.onFallingBlockChange(event);
        when(plugin.isObfuscationEnabled()).thenReturn(true);
        when(config.isWorldBlacklisted(world)).thenReturn(true);
        listener.onFallingBlockChange(event);
        verify(plugin, never()).getMovementListener();
    }
}
