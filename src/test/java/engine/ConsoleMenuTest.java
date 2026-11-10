package engine;

import console.ConsoleMenu;
import exception.InvalidServiceException;
import healthcheck.HealthCheckerFactory;
import incident.IncidentManager;
import model.CacheService;
import model.ServiceFactory;
import notification.ConsoleNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.AppLogger;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real menu with scripted input. Lives in package engine to reach the engine's test constructor. */
class ConsoleMenuTest {

    private final InMemoryStorage storage = new InMemoryStorage();
    private MonitoringEngine engine;
    private ByteArrayOutputStream buffer;
    private PrintStream out;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        AppLogger.getInstance().setLogFile(dir.resolve("test.log"));
        buffer = new ByteArrayOutputStream();
        out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        IncidentManager manager = new IncidentManager();
        manager.addListener(new ConsoleNotifier(out));          // alerts land in the same captured output
        engine = new MonitoringEngine(new ServiceFactory(new Random(1)),
                HealthCheckerFactory.create("latency"), manager, AppLogger.getInstance(), storage);
    }

    @AfterEach
    void restoreLogger() {
        AppLogger.getInstance().setLogFile(AppLogger.DEFAULT_FILE);
    }

    /** Adds a service that can only fail when forced, so scripted sessions are fully predictable. */
    private void presetReliableService() throws InvalidServiceException {
        engine.addService(new CacheService("c-1", "Cache", 0.0, new Random(1)));
    }

    private String run(String... lines) {
        BufferedReader in = new BufferedReader(new StringReader(String.join("\n", lines) + "\n"));
        new ConsoleMenu(engine, in, out).run();
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void showsTheMenuAndSaysGoodbye() {
        String output = run("0");
        assertTrue(output.contains("===== Service Health Monitor ====="));
        assertTrue(output.contains("9. Force a service to fail (demo mode)"));
        assertTrue(output.contains("Goodbye."));
    }

    @Test
    void addingAServiceThroughTheMenuWorks() {
        String output = run("1", "api", "api-1", "Payments API", "3", "0");
        assertTrue(output.contains("Added ApiService"));
        assertTrue(output.contains("api-1"));
        assertEquals(1, engine.getServices().size());
    }

    @Test
    void anUnknownTypeIsReportedNotCrashed() {
        String output = run("1", "fancy", "x", "X", "0");
        assertTrue(output.contains("Could not add service"));
        assertTrue(output.contains("Unknown service type"));
        assertTrue(engine.getServices().isEmpty());
    }

    @Test
    void aDuplicateIdIsReported() throws InvalidServiceException {
        presetReliableService();
        String output = run("1", "cache", "c-1", "Again", "0");
        assertTrue(output.contains("already exists"));
        assertEquals(1, engine.getServices().size());
    }

    @Test
    void anInvalidChoiceIsReportedAndTheMenuContinues() {
        String output = run("abc", "0");
        assertTrue(output.contains("Invalid choice 'abc'"));
        assertTrue(output.contains("Goodbye."));
    }

    @Test
    void endOfInputExitsCleanlyWithoutChoosingExit() {
        String output = run("3");          // script ends with no "0"
        assertTrue(output.contains("No services yet"));
        assertTrue(output.contains("Goodbye."));
    }

    @Test
    void checkingWithNoServicesExplainsWhatToDo() {
        assertTrue(run("4", "0").contains("No services to check"));
    }

    @Test
    void theFullDetectAlertRecoverCycleThroughTheMenu() throws InvalidServiceException {
        presetReliableService();
        String output = run("9", "c-1",          // force it down
                "5", "4",                         // 4 rounds
                "6",                              // view incidents
                "9", "c-1",                       // clear the force
                "4",                              // one more round
                "6", "0");

        assertTrue(output.contains("will now fail on every check"));
        assertTrue(output.contains("failures: 3"));
        assertTrue(output.contains("[ALERT -> console] Cache is DOWN"));
        assertTrue(output.contains("1 open, 1 total"));
        assertTrue(output.contains("is back to normal behaviour"));
        assertTrue(output.contains("[ALERT -> console] Cache has RECOVERED"));
        assertTrue(output.contains("0 open, 1 total"));
        // The round line for the 3rd failure must appear BEFORE the alert it triggered.
        assertTrue(output.indexOf("failures: 3") < output.indexOf("[ALERT -> console] Cache is DOWN"));
    }

    @Test
    void roundCountInputIsValidated() throws InvalidServiceException {
        presetReliableService();
        String output = run("5", "abc", "5", "0", "5", "1001", "0");
        assertTrue(output.contains("'abc' is not a whole number"));
        assertEquals(2, countOf(output, "between 1 and 1000"));
        assertEquals(0, engine.getRoundCount());
    }

    @Test
    void thresholdCanBeChangedAndBadValuesAreRejected() {
        run("8", "5", "0");
        assertEquals(5, engine.getFailureThreshold());

        String output = run("8", "abc", "8", "0", "0");
        assertTrue(output.contains("not a whole number"));
        assertTrue(output.contains("Threshold must be at least 1"));
        assertEquals(5, engine.getFailureThreshold());
    }

    @Test
    void removingAServiceWithAnOpenIncidentTellsTheUser() throws InvalidServiceException {
        presetReliableService();
        String output = run("9", "c-1", "5", "3", "2", "c-1", "6", "0");
        assertTrue(output.contains("was closed because the service was removed"));
        assertTrue(output.contains("note: Service removed while incident was open"));
        assertTrue(engine.getServices().isEmpty());
    }

    @Test
    void removingAnUnknownServiceIsReported() throws InvalidServiceException {
        presetReliableService();
        assertTrue(run("2", "nope", "0").contains("Could not remove service"));
    }

    private static int countOf(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) count++;
        return count;
    }

    // ---------- reports ----------

    @Test
    void reportsExplainWhenThereAreNoServices() {
        assertTrue(run("7", "0").contains("No services yet"));
    }

    @Test
    void reportsShowUptimeAndResponseTimesForCheckedServices() throws InvalidServiceException {
        presetReliableService();
        engine.runRounds(2, null);
        String output = run("7", "", "0");          // blank choice = default ranking
        assertTrue(output.contains("UPTIME"));
        assertTrue(output.contains("100.0%"));
        assertTrue(output.contains("Cache"));
    }

    @Test
    void aServiceWithNoChecksShowsNaInsteadOfZero() throws InvalidServiceException {
        presetReliableService();
        String output = run("7", "3", "0");
        assertTrue(output.contains("n/a"));
        assertFalse(output.contains("0.0%"));
    }

    @Test
    void anUnknownRankingChoiceFallsBackToUptime() throws InvalidServiceException {
        presetReliableService();
        assertTrue(run("7", "x", "0").contains("ranking by uptime"));
    }

    // ---------- saving ----------

    @Test
    void savesAfterAChangeAndOnExit() {
        String output = run("1", "cache", "c-9", "Nine", "0");
        assertEquals(2, storage.saveCount);                       // after the add, and on exit
        assertEquals(1, storage.saved.services().size());
        assertTrue(output.contains("Data saved."));
    }

    @Test
    void everyDataChangingActionSaves_andReadOnlyActionsDoNot() throws InvalidServiceException {
        presetReliableService();
        run("3", "6", "7", "", "8", "5", "9", "c-1", "0");        // list, incidents, reports, threshold, force
        assertEquals(1, storage.saveCount);                       // only the exit save

        storage.saveCount = 0;
        run("4", "5", "2", "2", "c-1", "0");                      // one round, two rounds, remove
        assertEquals(4, storage.saveCount);                       // round, rounds, remove, exit
    }

    @Test
    void endOfInputAlsoSaves() {
        run("3");
        assertEquals(1, storage.saveCount);
    }

    @Test
    void aFailedSaveIsReportedButTheMenuKeepsWorking() {
        storage.failOnSave = true;
        String output = run("3", "0");
        assertTrue(output.contains("Warning: could not save data: disk full"));
        assertTrue(output.contains("Goodbye."));
        assertFalse(output.contains("Data saved."));
    }
}
