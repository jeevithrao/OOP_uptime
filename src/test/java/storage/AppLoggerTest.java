package storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppLoggerTest {

    @AfterEach
    void restoreDefaultFile() {
        AppLogger.getInstance().setLogFile(AppLogger.DEFAULT_FILE);   // the logger is shared state
    }

    @Test
    void isASingleton() {
        assertSame(AppLogger.getInstance(), AppLogger.getInstance());
    }

    @Test
    void writesTimestampedLinesWithLevel_andCreatesFolders(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("logs").resolve("monitor.log");
        AppLogger log = AppLogger.getInstance();
        log.setLogFile(file);
        log.info("hello");
        log.warn("careful");
        log.error("bad");

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\[INFO] hello"), lines.get(0));
        assertTrue(lines.get(1).endsWith("[WARN] careful"));
        assertTrue(lines.get(2).endsWith("[ERROR] bad"));
    }

    @Test
    void anUnwritableLogNeverThrows(@TempDir Path dir) throws IOException {
        Path blocker = dir.resolve("a-file");
        Files.writeString(blocker, "x");
        AppLogger log = AppLogger.getInstance();
        log.setLogFile(blocker.resolve("monitor.log"));   // "inside" a regular file: cannot be created
        assertDoesNotThrow(() -> {
            log.info("one");
            log.info("two");
        });
    }

    @Test
    void nullFileIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AppLogger.getInstance().setLogFile(null));
    }
}
