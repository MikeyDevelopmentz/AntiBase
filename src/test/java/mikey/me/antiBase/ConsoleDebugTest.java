package mikey.me.antiBase;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleDebugTest {
    @Test
    void disabledWritesNothing() {
        ConsoleDebug debug = new ConsoleDebug();
        Logger logger = mock(Logger.class);
        debug.count(UUID.randomUUID(), ConsoleDebug.Metric.MINING, 1);
        debug.sample(UUID.randomUUID(), "hidden");
        debug.flush(logger, "queued=0");
        verifyNoInteractions(logger);
    }

    @Test
    void filtersPlayerAndCapsSamples() {
        ConsoleDebug debug = new ConsoleDebug();
        UUID watched = UUID.randomUUID(), other = UUID.randomUUID();
        debug.configure(true, watched, "Miner");
        Logger logger = mock(Logger.class);
        debug.count(watched, ConsoleDebug.Metric.MINING, 2);
        debug.count(other, ConsoleDebug.Metric.MINING, 99);
        debug.sample(other, "wrong player");
        for (int i = 0; i < 1000; i++) debug.sample(watched, "background=" + i);
        debug.important(watched, "interaction=break cancelled=true");
        debug.flush(logger, "queued=2");
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(logger, times(21)).info(lines.capture());
        assertTrue(lines.getAllValues().getFirst().contains("mining=2"));
        assertTrue(lines.getAllValues().stream().anyMatch(line -> line.contains("interaction=break cancelled=true")));
        assertFalse(lines.getAllValues().stream().anyMatch(line -> line.contains("wrong player")));
    }

    @Test
    void switchingCaptureStartsClean() {
        ConsoleDebug debug = new ConsoleDebug();
        UUID player = UUID.randomUUID();
        debug.configure(true, null, "all players");
        debug.sample(player, "old session");
        debug.count(player, ConsoleDebug.Metric.SCANS, 10);
        debug.configure(true, player, "Miner");
        Logger logger = mock(Logger.class);
        debug.flush(logger, "queued=0");
        verify(logger).info(argThat((String line) -> line.contains("scans=0") && !line.contains("old session")));
        debug.configure(false, null, "all players");
        debug.flush(logger, "queued=0");
        verifyNoMoreInteractions(logger);
    }
}
