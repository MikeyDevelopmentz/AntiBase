package mikey.me.antiBase;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BaseObfuscatorTest {
    @Test void paddingDefaultsAndClamps() {
        YamlConfiguration config = new YamlConfiguration();
        Logger logger = mock(Logger.class);
        assertEquals(2, new BaseObfuscator(config, logger, 0).getTerrainPadding());
        config.set("terrain-padding", 0);
        assertEquals(0, new BaseObfuscator(config, logger, 0).getTerrainPadding());
        config.set("terrain-padding", 100);
        assertEquals(4, new BaseObfuscator(config, logger, 0).getTerrainPadding());
        config.set("terrain-padding", -2);
        assertEquals(0, new BaseObfuscator(config, logger, 0).getTerrainPadding());
    }
    @Test void defaultsToConnectedMode() {
        YamlConfiguration config = new YamlConfiguration();
        Logger logger = mock(Logger.class);
        assertTrue(new BaseObfuscator(config, logger, 0).usesConnectedRendering());
        config.set("render-mode", "line-of-sight");
        assertFalse(new BaseObfuscator(config, logger, 0).usesConnectedRendering());
        verify(logger, never()).warning(contains("Unknown render-mode"));
        config.set("render-mode", "typo");
        assertTrue(new BaseObfuscator(config, logger, 0).usesConnectedRendering());
        verify(logger).warning(contains("Unknown render-mode"));
    }
    @Test
    void legacyStoneIsIgnored() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("replacement-block", "STONE");
        Logger logger = mock(Logger.class);
        BaseObfuscator obfuscator = new BaseObfuscator(config, logger, 0);
        assertEquals(0, obfuscator.getAirStateId());
        verify(logger).info(contains("obsolete and ignored"));
    }

    @Test
    void budgetsAreClamped() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("scan-radius", 100000);
        config.set("max-ray-steps", -1);
        config.set("max-scan-blocks", Integer.MAX_VALUE);
        BaseObfuscator obfuscator = new BaseObfuscator(config, mock(Logger.class), 0);
        assertEquals(96, obfuscator.getScanRadius());
        assertEquals(10000, obfuscator.getMaxRaySteps());
        assertEquals(200000, obfuscator.getMaxScanBlocks());
        assertEquals(0, obfuscator.getHideBelowY());
    }
}
