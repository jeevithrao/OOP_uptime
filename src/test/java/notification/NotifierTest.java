package notification;

import incident.Incident;
import model.IncidentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NotifierTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 10, 42, 15);

    private static Incident open() {
        return new Incident("INC-1", "api-1", "Payments API", START, null, IncidentStatus.OPEN, 3);
    }

    private static Incident resolved() {
        return new Incident("INC-1", "api-1", "Payments API", START, START.plusSeconds(5),
                IncidentStatus.RESOLVED, 4);
    }

    private static String capture(java.util.function.Consumer<PrintStream> action) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        action.accept(ps);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void console_openedAlertNamesServiceAndTime() {
        String out = capture(ps -> new ConsoleNotifier(ps).onIncidentOpened(open()));
        assertTrue(out.contains("[ALERT -> console]"));
        assertTrue(out.contains("Payments API is DOWN"));
        assertTrue(out.contains("INC-1"));
        assertTrue(out.contains("10:42:15"));
        assertTrue(out.contains("3 consecutive failures"));
    }

    @Test
    void console_resolvedAlertShowsDowntime() {
        String out = capture(ps -> new ConsoleNotifier(ps).onIncidentResolved(resolved()));
        assertTrue(out.contains("Payments API has RECOVERED"));
        assertTrue(out.contains("downtime: 4 rounds"));
    }

    @Test
    void email_printsRecipientAndSentMarker() {
        String out = capture(ps -> new EmailNotifier("ops@example.com", ps).onIncidentOpened(open()));
        assertTrue(out.contains("[EMAIL sent to ops@example.com]"));
        assertTrue(out.contains("[ALERT -> email]"));
        assertTrue(out.contains("Payments API is DOWN"));
    }

    @Test
    void email_rejectsBlankRecipient() {
        assertThrows(IllegalArgumentException.class, () -> new EmailNotifier(" "));
    }

    @Test
    void file_appendsOneLinePerAlert_andCreatesMissingFolders(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("logs").resolve("alerts.log");   // "logs" does not exist yet
        FileNotifier notifier = new FileNotifier(log);
        notifier.onIncidentOpened(open());
        notifier.onIncidentResolved(resolved());

        List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("[ALERT -> file]"));
        assertTrue(lines.get(0).contains("is DOWN"));
        assertTrue(lines.get(1).contains("has RECOVERED"));
    }

    @Test
    void file_reportsUnwritableLocationAsUncheckedException(@TempDir Path dir) throws IOException {
        Path blocker = dir.resolve("a-file");
        Files.writeString(blocker, "x");
        // A path "inside" a regular file can never be created, so the write must fail.
        FileNotifier notifier = new FileNotifier(blocker.resolve("alerts.log"));
        assertThrows(java.io.UncheckedIOException.class, () -> notifier.onIncidentOpened(open()));
    }
}
