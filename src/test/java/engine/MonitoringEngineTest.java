package engine;

import exception.InvalidServiceException;
import healthcheck.HealthCheckerFactory;
import incident.Incident;
import incident.IncidentListener;
import incident.IncidentManager;
import model.ApiService;
import model.CacheService;
import model.IncidentStatus;
import model.Service;
import model.ServiceFactory;
import model.ServiceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MonitoringEngineTest {

    private final List<String> events = new ArrayList<>();
    private IncidentManager manager;
    private MonitoringEngine engine;
    private Path logFile;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        logFile = dir.resolve("test.log");
        AppLogger.getInstance().setLogFile(logFile);
        manager = new IncidentManager();
        manager.addListener(new IncidentListener() {
            @Override public void onIncidentOpened(Incident i) { events.add("OPENED " + i.getId()); }
            @Override public void onIncidentResolved(Incident i) { events.add("RESOLVED " + i.getId()); }
        });
        engine = new MonitoringEngine(new ServiceFactory(new Random(1)),
                HealthCheckerFactory.create("latency"), manager, AppLogger.getInstance());
    }

    @AfterEach
    void restoreLogger() {
        AppLogger.getInstance().setLogFile(AppLogger.DEFAULT_FILE);
    }

    /** A cache with a 0% failure rate: always answers in 1 to 5 ms, so only forced failures can fail it. */
    private static CacheService reliable(String id) throws InvalidServiceException {
        return new CacheService(id, "Cache " + id, 0.0, new Random(1));
    }

    private MonitoringEngine.CheckObserver recordStreaks() {
        return (round, service, result, streak) -> events.add("CHECK " + streak);
    }

    // ---------- managing services ----------

    @Test
    void addedServicesAreListedInInsertionOrder() throws InvalidServiceException {
        engine.addService(reliable("b"));
        engine.addService(reliable("a"));
        engine.addService(reliable("c"));
        List<String> ids = new ArrayList<>();
        engine.getServices().forEach(s -> ids.add(s.getId()));
        assertEquals(List.of("b", "a", "c"), ids);
    }

    @Test
    void duplicateIdIsRejected() throws InvalidServiceException {
        engine.addService(reliable("a"));
        assertThrows(InvalidServiceException.class, () -> engine.addService(reliable("a")));
        assertEquals(1, engine.getServices().size());
    }

    @Test
    void addByTypeUsesTheFactory_andRejectsUnknownTypes() throws InvalidServiceException {
        assertInstanceOf(ApiService.class, engine.addService("api", "api-1", "Payments"));
        assertThrows(InvalidServiceException.class, () -> engine.addService("fancy", "x", "X"));
        assertEquals(1, engine.getServices().size());
    }

    @Test
    void unknownIdsAreRejected() {
        assertThrows(InvalidServiceException.class, () -> engine.removeService("nope"));
        assertThrows(InvalidServiceException.class, () -> engine.getService("nope"));
        assertThrows(InvalidServiceException.class, () -> engine.setForceFailure("nope", true));
        assertThrows(InvalidServiceException.class, () -> engine.getResults("nope"));
    }

    @Test
    void serviceListIsASnapshot() throws InvalidServiceException {
        engine.addService(reliable("a"));
        assertThrows(UnsupportedOperationException.class, () -> engine.getServices().clear());
    }

    // ---------- running checks ----------

    @Test
    void aRoundCheckingAHealthyServiceRecordsResultAndStatus() throws InvalidServiceException {
        Service s = reliable("a");
        engine.addService(s);
        assertEquals(1, engine.runRound(null).size());
        assertEquals(ServiceStatus.UP, s.getStatus());
        assertEquals(1, engine.getResults("a").size());
        assertEquals(1, engine.getRoundCount());
    }

    @Test
    void aRoundWithNoServicesDoesNothing() {
        assertTrue(engine.runRound(null).isEmpty());
        assertEquals(0, engine.getRoundCount());
    }

    @Test
    void runRoundsRunsTheRequestedNumberOfRounds_andRejectsBadCounts() throws InvalidServiceException {
        engine.addService(reliable("a"));
        engine.runRounds(3, null);
        assertEquals(3, engine.getRoundCount());
        assertEquals(3, engine.getResults("a").size());
        assertThrows(IllegalArgumentException.class, () -> engine.runRounds(0, null));
    }

    @Test
    void resultsViewIsReadOnly() throws InvalidServiceException {
        engine.addService(reliable("a"));
        engine.runRound(null);
        assertThrows(UnsupportedOperationException.class, () -> engine.getResults("a").clear());
    }

    // ---------- the full cycle ----------

    @Test
    void forcedFailureOpensAnIncidentAtTheThreshold_andTheObserverSeesEachCheckFirst() throws InvalidServiceException {
        engine.addService(reliable("a"));
        engine.setForceFailure("a", true);
        engine.runRounds(3, recordStreaks());
        // The CHECK line for the 3rd failure must come BEFORE the alert it triggers.
        assertEquals(List.of("CHECK 1", "CHECK 2", "CHECK 3", "OPENED INC-1"), events);
        assertEquals(ServiceStatus.DOWN, engine.getService("a").getStatus());
        assertEquals(1, engine.getOpenIncidents().size());
    }

    @Test
    void clearingTheForcedFailureResolvesTheIncident() throws InvalidServiceException {
        engine.addService(reliable("a"));
        engine.setForceFailure("a", true);
        engine.runRounds(4, null);
        engine.setForceFailure("a", false);
        engine.runRound(recordStreaks());
        assertEquals(List.of("OPENED INC-1", "CHECK 0", "RESOLVED INC-1"), events);
        assertEquals(ServiceStatus.UP, engine.getService("a").getStatus());
        assertEquals(4, engine.getIncidentHistory().get(0).getFailureCount());
        assertTrue(engine.getOpenIncidents().isEmpty());
    }

    @Test
    void changingTheThresholdAffectsWhenIncidentsOpen() throws InvalidServiceException {
        engine.setFailureThreshold(2);
        engine.addService(reliable("a"));
        engine.setForceFailure("a", true);
        engine.runRounds(2, null);
        assertEquals(1, engine.getIncidentHistory().size());
        assertThrows(IllegalArgumentException.class, () -> engine.setFailureThreshold(0));
        assertEquals(2, engine.getFailureThreshold());
    }

    // ---------- removing a service ----------

    @Test
    void removingAServiceWithAnOpenIncidentClosesItWithANote_withoutARecoveredAlert() throws InvalidServiceException {
        engine.addService(reliable("a"));
        engine.setForceFailure("a", true);
        engine.runRounds(3, null);

        Optional<Incident> closed = engine.removeService("a");

        assertTrue(closed.isPresent());
        assertEquals(IncidentStatus.RESOLVED, closed.get().getStatus());
        assertEquals("Service removed while incident was open", closed.get().getResolutionNote());
        assertTrue(engine.getOpenIncidents().isEmpty());
        assertEquals(0, engine.getConsecutiveFailures("a"));
        assertEquals(List.of("OPENED INC-1"), events);          // no RESOLVED/RECOVERED alert
        assertTrue(engine.getServices().isEmpty());
        assertThrows(InvalidServiceException.class, () -> engine.getResults("a"));
    }

    @Test
    void removingAHealthyServiceClosesNothing() throws InvalidServiceException {
        engine.addService(reliable("a"));
        assertTrue(engine.removeService("a").isEmpty());
    }

    @Test
    void aRemovedIdCanBeReusedWithAFreshStreak() throws InvalidServiceException {
        engine.addService(reliable("a"));
        engine.setForceFailure("a", true);
        engine.runRounds(2, null);
        engine.removeService("a");
        engine.addService(reliable("a"));
        engine.setForceFailure("a", true);
        engine.runRounds(2, null);
        assertTrue(engine.getIncidentHistory().isEmpty());       // 2 fresh failures, not 4
    }

    // ---------- logging and Singleton ----------

    @Test
    void importantEventsAreWrittenToTheLog() throws InvalidServiceException, IOException {
        engine.addService(reliable("a"));
        engine.setForceFailure("a", true);
        engine.runRounds(3, null);
        String log = Files.readString(logFile);
        assertTrue(log.contains("Service added"));
        assertTrue(log.contains("[WARN] [Round 3]"));
        assertTrue(log.contains("Incident INC-1 OPENED"));
    }

    @Test
    void getInstanceAlwaysReturnsTheSameEngine_andInitializeMustComeFirst() {
        assertSame(MonitoringEngine.getInstance(), MonitoringEngine.getInstance());
        assertThrows(IllegalStateException.class, () -> MonitoringEngine.initialize(42));
    }

    @Test
    void nullDependenciesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MonitoringEngine(null, HealthCheckerFactory.create("basic"), manager, AppLogger.getInstance()));
    }
}
