package incident;

import healthcheck.HealthResult;
import model.IncidentStatus;
import model.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncidentManagerTest {

    /** Records every callback so tests can assert exactly what the observer saw. */
    static class RecordingListener implements IncidentListener {
        final List<String> events = new ArrayList<>();
        @Override public void onIncidentOpened(Incident i) { events.add("OPENED " + i.getId()); }
        @Override public void onIncidentResolved(Incident i) { events.add("RESOLVED " + i.getId()); }
    }

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    private IncidentManager manager;
    private RecordingListener listener;
    private int tick;   // each result is one second later than the previous, so times are predictable

    @BeforeEach
    void setUp() {
        manager = new IncidentManager();   // default threshold 3
        listener = new RecordingListener();
        manager.addListener(listener);
        tick = 0;
    }

    private HealthResult result(String id, ServiceStatus status) {
        int ms = status == ServiceStatus.DOWN ? 0 : 100;
        String err = status == ServiceStatus.DOWN ? "boom" : "";
        return new HealthResult(id, T0.plusSeconds(tick++), status, ms, err);
    }

    private void feed(String id, ServiceStatus... statuses) {
        for (ServiceStatus s : statuses) manager.recordResult(result(id, s));
    }

    private static final ServiceStatus UP = ServiceStatus.UP;
    private static final ServiceStatus DEG = ServiceStatus.DEGRADED;
    private static final ServiceStatus DOWN = ServiceStatus.DOWN;

    // ---------- opening ----------

    @Test
    void belowThreshold_noIncident() {
        feed("a", DOWN, DOWN);
        assertTrue(manager.getHistory().isEmpty());
        assertEquals(2, manager.getConsecutiveFailures("a"));
        assertTrue(listener.events.isEmpty());
    }

    @Test
    void exactlyAtThreshold_opensOneIncidentAndNotifiesOnce() {
        feed("a", DOWN, DOWN, DOWN);
        assertEquals(1, manager.getHistory().size());
        Incident inc = manager.getHistory().get(0);
        assertEquals(IncidentStatus.OPEN, inc.getStatus());
        assertEquals(3, inc.getFailureCount());
        assertEquals(List.of("OPENED INC-1"), listener.events);
    }

    @Test
    void incidentStartsAtTheCheckThatCrossedTheThreshold() {
        feed("a", DOWN, DOWN, DOWN);
        assertEquals(T0.plusSeconds(2), manager.getHistory().get(0).getStartTime());
    }

    @Test
    void failuresWhileOpen_countButDoNotOpenASecondIncident() {
        feed("a", DOWN, DOWN, DOWN, DOWN, DOWN);
        assertEquals(1, manager.getHistory().size());
        assertEquals(5, manager.getHistory().get(0).getFailureCount());
        assertEquals(1, listener.events.size());   // only the single OPENED event
    }

    @Test
    void serviceNameIsUsedWhenGiven_otherwiseTheId() {
        for (int i = 0; i < 3; i++) manager.recordResult(result("api-1", DOWN), "Payments API");
        assertEquals("Payments API", manager.getHistory().get(0).getServiceName());

        for (int i = 0; i < 3; i++) manager.recordResult(result("db-1", DOWN));
        assertEquals("db-1", manager.getHistory().get(1).getServiceName());
    }

    // ---------- resolving ----------

    @Test
    void firstSuccessResolvesAndNotifiesOnce() {
        feed("a", DOWN, DOWN, DOWN, UP);
        Incident inc = manager.getHistory().get(0);
        assertEquals(IncidentStatus.RESOLVED, inc.getStatus());
        assertTrue(inc.getEndTime().isPresent());
        assertEquals(T0.plusSeconds(3), inc.getEndTime().get());
        assertTrue(manager.getOpenIncidents().isEmpty());
        assertFalse(manager.hasOpenIncident("a"));
        assertEquals(List.of("OPENED INC-1", "RESOLVED INC-1"), listener.events);
    }

    @Test
    void degradedCountsAsSuccess_andResolvesAnOpenIncident() {
        feed("a", DOWN, DOWN, DOWN, DEG);
        assertEquals(IncidentStatus.RESOLVED, manager.getHistory().get(0).getStatus());
    }

    @Test
    void afterResolving_aNewStreakOpensANewIncident() {
        feed("a", DOWN, DOWN, DOWN, UP, DOWN, DOWN, DOWN);
        assertEquals(2, manager.getHistory().size());
        assertEquals("INC-2", manager.getHistory().get(1).getId());
        assertEquals(List.of("OPENED INC-1", "RESOLVED INC-1", "OPENED INC-2"), listener.events);
    }

    // ---------- streak rules ----------

    @Test
    void aSuccessBreaksTheStreak_failuresMustBeConsecutive() {
        feed("a", DOWN, DOWN, UP, DOWN, DOWN);
        assertTrue(manager.getHistory().isEmpty());
        assertEquals(2, manager.getConsecutiveFailures("a"));
    }

    @Test
    void aDegradedResultAlsoBreaksTheStreak() {
        feed("a", DOWN, DOWN, DEG, DOWN);
        assertTrue(manager.getHistory().isEmpty());
        assertEquals(1, manager.getConsecutiveFailures("a"));
    }

    @Test
    void successWithNoFailuresAndNoIncident_doesNothing() {
        feed("a", UP, UP, DEG);
        assertTrue(listener.events.isEmpty());
        assertEquals(0, manager.getConsecutiveFailures("a"));
    }

    @Test
    void servicesAreTrackedIndependently() {
        feed("a", DOWN, DOWN, DOWN);
        feed("b", DOWN, DOWN);
        assertTrue(manager.hasOpenIncident("a"));
        assertFalse(manager.hasOpenIncident("b"));
        assertEquals(2, manager.getConsecutiveFailures("b"));
    }

    @Test
    void interleavedServices_oneRecoveringDoesNotResolveTheOther() {
        feed("a", DOWN, DOWN, DOWN);
        feed("b", DOWN, DOWN, DOWN);
        feed("a", UP);
        assertFalse(manager.hasOpenIncident("a"));
        assertTrue(manager.hasOpenIncident("b"));
    }

    // ---------- threshold ----------

    @Test
    void customThresholdIsRespected() {
        IncidentManager m = new IncidentManager(1);
        m.recordResult(result("a", DOWN));
        assertEquals(1, m.getHistory().size());

        IncidentManager m5 = new IncidentManager(5);
        for (int i = 0; i < 4; i++) m5.recordResult(result("a", DOWN));
        assertTrue(m5.getHistory().isEmpty());
        m5.recordResult(result("a", DOWN));
        assertEquals(1, m5.getHistory().size());
    }

    @Test
    void invalidThresholdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new IncidentManager(0));
        assertThrows(IllegalArgumentException.class, () -> manager.setThreshold(-1));
        assertEquals(3, manager.getThreshold());   // unchanged after the rejected call
    }

    @Test
    void loweringTheThresholdMidStreakOpensOnTheNextFailure() {
        manager.setThreshold(5);
        feed("a", DOWN, DOWN, DOWN);
        assertTrue(manager.getHistory().isEmpty());
        manager.setThreshold(2);
        feed("a", DOWN);
        assertEquals(1, manager.getHistory().size());
        assertEquals(4, manager.getHistory().get(0).getFailureCount());
    }

    // ---------- observer behaviour ----------

    @Test
    void removedListenerIsNoLongerNotified() {
        manager.removeListener(listener);
        feed("a", DOWN, DOWN, DOWN);
        assertTrue(listener.events.isEmpty());
        assertEquals(1, manager.getHistory().size());   // manager still works without listeners
    }

    @Test
    void allRegisteredListenersAreNotified() {
        RecordingListener second = new RecordingListener();
        manager.addListener(second);
        feed("a", DOWN, DOWN, DOWN);
        assertEquals(1, listener.events.size());
        assertEquals(1, second.events.size());
    }

    @Test
    void aFailingListenerDoesNotStopOthersOrCorruptState() {
        IncidentManager m = new IncidentManager();
        RecordingListener good = new RecordingListener();
        m.addListener(new IncidentListener() {
            @Override public void onIncidentOpened(Incident i) { throw new RuntimeException("channel broken"); }
            @Override public void onIncidentResolved(Incident i) { throw new RuntimeException("channel broken"); }
        });
        m.addListener(good);

        for (int i = 0; i < 3; i++) m.recordResult(result("a", DOWN));
        assertEquals(List.of("OPENED INC-1"), good.events);       // still notified
        assertEquals(1, m.getHistory().size());                   // state intact

        m.recordResult(result("a", UP));
        assertEquals(List.of("OPENED INC-1", "RESOLVED INC-1"), good.events);
    }

    // ---------- read-only views and input checks ----------

    @Test
    void historyViewIsReadOnly() {
        feed("a", DOWN, DOWN, DOWN);
        Incident inc = manager.getHistory().get(0);
        assertThrows(UnsupportedOperationException.class, () -> manager.getHistory().add(inc));
    }

    @Test
    void nullInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> manager.recordResult(null));
        assertThrows(IllegalArgumentException.class, () -> manager.addListener(null));
    }

    // ---------- Incident invariants ----------

    @Test
    void incidentRejectsImpossibleStates() {
        assertThrows(IllegalArgumentException.class, () ->
                new Incident("I", "a", "A", T0, T0.plusSeconds(1), IncidentStatus.OPEN, 3));
        assertThrows(IllegalArgumentException.class, () ->
                new Incident("I", "a", "A", T0, null, IncidentStatus.RESOLVED, 3));
        assertThrows(IllegalArgumentException.class, () ->
                new Incident("I", "a", "A", T0, T0.minusSeconds(1), IncidentStatus.RESOLVED, 3));
        assertThrows(IllegalArgumentException.class, () ->
                new Incident("I", "a", "A", T0, null, IncidentStatus.OPEN, 0));
    }

    // ---------- forgetService (used when a service is removed) ----------

    @Test
    void forgetService_closesTheOpenIncidentWithANote_clearsTheStreak_andDoesNotNotify() {
        feed("a", DOWN, DOWN, DOWN);
        java.util.Optional<Incident> closed = manager.forgetService("a", T0.plusSeconds(10), "service removed");

        assertTrue(closed.isPresent());
        assertEquals(IncidentStatus.RESOLVED, closed.get().getStatus());
        assertEquals("service removed", closed.get().getResolutionNote());
        assertEquals(T0.plusSeconds(10), closed.get().getEndTime().get());
        assertEquals(0, manager.getConsecutiveFailures("a"));
        assertTrue(manager.getOpenIncidents().isEmpty());
        assertEquals(List.of("OPENED INC-1"), listener.events);   // no RESOLVED: the service did not recover
    }

    @Test
    void forgetService_withNoOpenIncident_returnsEmptyButStillClearsTheStreak() {
        feed("a", DOWN, DOWN);
        assertTrue(manager.forgetService("a", T0, "gone").isEmpty());
        assertEquals(0, manager.getConsecutiveFailures("a"));
        feed("a", DOWN, DOWN);                                     // a fresh streak of 2, so still no incident
        assertTrue(manager.getHistory().isEmpty());
    }

    @Test
    void forgetService_neverProducesAnEndTimeBeforeTheStart() {
        feed("a", DOWN, DOWN, DOWN);
        Incident closed = manager.forgetService("a", T0.minusDays(1), "clock quirk").get();
        assertEquals(closed.getStartTime(), closed.getEndTime().get());
    }

    @Test
    void aNormalRecoveryHasNoResolutionNote() {
        feed("a", DOWN, DOWN, DOWN, UP);
        assertEquals("", manager.getHistory().get(0).getResolutionNote());
    }

    @Test
    void anOpenIncidentCannotCarryAResolutionNote() {
        assertThrows(IllegalArgumentException.class, () ->
                new Incident("I", "a", "A", T0, null, IncidentStatus.OPEN, 3, "why"));
    }

    // ---------- restoreHistory (loading saved incidents at startup) ----------

    private static Incident saved(String id, String service, boolean open, int failures) {
        return open ? new Incident(id, service, service, T0, null, IncidentStatus.OPEN, failures)
                    : new Incident(id, service, service, T0, T0.plusSeconds(5), IncidentStatus.RESOLVED, failures);
    }

    @Test
    void restoreHistory_bringsBackHistoryAndOpenIncidents_withoutNotifying() {
        manager.restoreHistory(List.of(saved("INC-1", "a", false, 4), saved("INC-2", "b", true, 5)));
        assertEquals(2, manager.getHistory().size());
        assertTrue(manager.hasOpenIncident("b"));
        assertFalse(manager.hasOpenIncident("a"));
        assertEquals(5, manager.getConsecutiveFailures("b"));   // the outage is still counting
        assertTrue(listener.events.isEmpty());
    }

    @Test
    void aRestoredOpenIncidentCanBeResolvedByTheNextSuccess() {
        manager.restoreHistory(List.of(saved("INC-1", "a", true, 3)));
        feed("a", UP);
        assertEquals(IncidentStatus.RESOLVED, manager.getHistory().get(0).getStatus());
        assertEquals(List.of("RESOLVED INC-1"), listener.events);
    }

    @Test
    void aRestoredOpenIncidentKeepsCountingFailures_withoutOpeningASecondOne() {
        manager.restoreHistory(List.of(saved("INC-1", "a", true, 3)));
        feed("a", DOWN);
        assertEquals(1, manager.getHistory().size());
        assertEquals(4, manager.getHistory().get(0).getFailureCount());
    }

    @Test
    void numberingContinuesAfterTheHighestSavedId() {
        manager.restoreHistory(List.of(saved("INC-7", "a", false, 3), saved("INC-3", "b", false, 3)));
        feed("c", DOWN, DOWN, DOWN);
        assertEquals("INC-8", manager.getHistory().get(2).getId());
    }

    @Test
    void idsInAnotherShapeDoNotBreakNumbering() {
        manager.restoreHistory(List.of(saved("custom", "a", false, 3), saved("INC-x", "b", false, 3)));
        feed("c", DOWN, DOWN, DOWN);
        assertEquals("INC-1", manager.getHistory().get(2).getId());
    }

    @Test
    void inconsistentDataIsRejectedAndChangesNothing() {
        assertThrows(IllegalArgumentException.class, () ->
                manager.restoreHistory(List.of(saved("INC-1", "a", false, 3), saved("INC-1", "b", false, 3))));
        assertThrows(IllegalArgumentException.class, () ->
                manager.restoreHistory(List.of(saved("INC-1", "a", true, 3), saved("INC-2", "a", true, 3))));
        assertTrue(manager.getHistory().isEmpty());
        assertTrue(manager.getOpenIncidents().isEmpty());
    }

    @Test
    void restoreIsOnlyAllowedIntoAnEmptyManager() {
        feed("a", DOWN, DOWN, DOWN);
        assertThrows(IllegalStateException.class, () -> manager.restoreHistory(List.of()));
    }
}
