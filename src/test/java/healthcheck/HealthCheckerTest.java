package healthcheck;

import exception.InvalidServiceException;
import model.CacheService;
import model.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckerTest {

    // ---------- BasicHealthChecker ----------

    @Test
    void basic_successfulRequestIsUpAndKeepsResponseTime() throws InvalidServiceException {
        HealthResult r = new BasicHealthChecker().check(StubService.respondingIn(120));
        assertEquals(ServiceStatus.UP, r.getStatus());
        assertTrue(r.isSuccess());
        assertEquals(120, r.getResponseTimeMs());
        assertEquals("stub-1", r.getServiceId());
    }

    @Test
    void basic_downFailureBecomesDownResultWithMessage() throws InvalidServiceException {
        HealthResult r = new BasicHealthChecker().check(StubService.failingDown("connection refused"));
        assertEquals(ServiceStatus.DOWN, r.getStatus());
        assertFalse(r.isSuccess());
        assertEquals("connection refused", r.getErrorMessage());
        assertEquals(0, r.getResponseTimeMs());
    }

    @Test
    void basic_timeoutBecomesDownResult() throws InvalidServiceException {
        HealthResult r = new BasicHealthChecker().check(StubService.failingTimeout("timed out"));
        assertEquals(ServiceStatus.DOWN, r.getStatus());
        assertEquals("timed out", r.getErrorMessage());
    }

    @Test
    void basic_ignoresSlowness() throws InvalidServiceException {
        HealthResult r = new BasicHealthChecker().check(StubService.respondingIn(5000));
        assertEquals(ServiceStatus.UP, r.getStatus());
    }

    @Test
    void basic_forcedFailureIsReportedAsDown() throws InvalidServiceException {
        CacheService cache = new CacheService("c-1", "Cache", new Random(1));
        cache.setForceFailure(true);
        HealthResult r = new BasicHealthChecker().check(cache);
        assertEquals(ServiceStatus.DOWN, r.getStatus());
        assertTrue(r.getErrorMessage().contains("forced down"));
    }

    // ---------- LatencyHealthChecker ----------

    @Test
    void latency_exactlyAtThresholdIsStillUp() throws InvalidServiceException {
        HealthResult r = new LatencyHealthChecker(400).check(StubService.respondingIn(400));
        assertEquals(ServiceStatus.UP, r.getStatus());
    }

    @Test
    void latency_oneMsOverThresholdIsDegraded() throws InvalidServiceException {
        HealthResult r = new LatencyHealthChecker(400).check(StubService.respondingIn(401));
        assertEquals(ServiceStatus.DEGRADED, r.getStatus());
        assertTrue(r.isDegraded());
        assertEquals(401, r.getResponseTimeMs());
    }

    @Test
    void latency_degradedStillCountsAsSuccess() throws InvalidServiceException {
        HealthResult r = new LatencyHealthChecker(400).check(StubService.respondingIn(900));
        assertTrue(r.isSuccess());
    }

    @Test
    void latency_failureStaysDownNotDegraded() throws InvalidServiceException {
        HealthResult r = new LatencyHealthChecker().check(StubService.failingDown("boom"));
        assertEquals(ServiceStatus.DOWN, r.getStatus());
        assertEquals("boom", r.getErrorMessage());
    }

    @Test
    void latency_defaultThresholdIs400() {
        assertEquals(400, new LatencyHealthChecker().getSlowThresholdMs());
    }

    @Test
    void latency_rejectsNonPositiveThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new LatencyHealthChecker(0));
        assertThrows(IllegalArgumentException.class, () -> new LatencyHealthChecker(-5));
    }

    // ---------- Strategy: same service, different verdict ----------

    @Test
    void strategySwap_sameServiceGivesDifferentVerdicts() throws InvalidServiceException {
        StubService slow = StubService.respondingIn(700);
        assertEquals(ServiceStatus.UP, new BasicHealthChecker().check(slow).getStatus());
        assertEquals(ServiceStatus.DEGRADED, new LatencyHealthChecker().check(slow).getStatus());
    }

    // ---------- HealthResult ----------

    @Test
    void result_rejectsInvalidArguments() {
        LocalDateTime now = LocalDateTime.now();
        assertThrows(IllegalArgumentException.class,
                () -> new HealthResult(" ", now, ServiceStatus.UP, 1, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HealthResult("a", null, ServiceStatus.UP, 1, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HealthResult("a", now, null, 1, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HealthResult("a", now, ServiceStatus.UP, -1, ""));
    }

    @Test
    void result_nullErrorMessageBecomesEmpty() {
        HealthResult r = new HealthResult("a", LocalDateTime.now(), ServiceStatus.UP, 5, null);
        assertEquals("", r.getErrorMessage());
    }

    // ---------- HealthCheckerFactory ----------

    @Test
    void factory_createsCorrectTypes() {
        assertInstanceOf(BasicHealthChecker.class, HealthCheckerFactory.create("basic"));
        assertInstanceOf(LatencyHealthChecker.class, HealthCheckerFactory.create("latency"));
    }

    @Test
    void factory_ignoresCaseAndSurroundingSpaces() {
        assertInstanceOf(LatencyHealthChecker.class, HealthCheckerFactory.create("  LaTeNcY "));
    }

    @Test
    void factory_rejectsUnknownBlankAndNull() {
        assertThrows(IllegalArgumentException.class, () -> HealthCheckerFactory.create("fancy"));
        assertThrows(IllegalArgumentException.class, () -> HealthCheckerFactory.create(" "));
        assertThrows(IllegalArgumentException.class, () -> HealthCheckerFactory.create(null));
    }
}
