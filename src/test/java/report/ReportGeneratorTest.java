package report;

import healthcheck.HealthResult;
import incident.Incident;
import model.IncidentStatus;
import model.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class ReportGeneratorTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    private static HealthResult up(int ms)   { return new HealthResult("a", T0, ServiceStatus.UP, ms, ""); }
    private static HealthResult slow(int ms) { return new HealthResult("a", T0, ServiceStatus.DEGRADED, ms, ""); }
    private static HealthResult down()       { return new HealthResult("a", T0, ServiceStatus.DOWN, 0, "boom"); }

    private static ServiceReport report(List<HealthResult> results) {
        return ReportGenerator.forService("a", "Alpha", results, List.of());
    }

    // ---------- uptime and counts ----------

    @Test
    void uptimeIsSuccessfulOverTotal_andDegradedCountsAsSuccessful() {
        ServiceReport r = report(List.of(up(100), slow(500), down(), up(100)));
        assertEquals(75.0, r.uptimePercent().getAsDouble(), 1e-9);   // 3 of 4
        assertEquals(4, r.totalChecks());
        assertEquals(2, r.upChecks());
        assertEquals(1, r.degradedChecks());
        assertEquals(1, r.failedChecks());
        assertEquals(3, r.successfulChecks());
    }

    @Test
    void noChecksMeansNoDataNotZeroPercent() {
        ServiceReport r = report(List.of());
        assertEquals(0, r.totalChecks());
        assertTrue(r.uptimePercent().isEmpty());
        assertTrue(r.recentUptimePercent().isEmpty());
        assertTrue(r.averageResponseMs().isEmpty());
        assertTrue(r.slowestResponseMs().isEmpty());
    }

    @Test
    void onlyFailuresGiveZeroUptimeButNoResponseData() {
        ServiceReport r = report(List.of(down(), down()));
        assertEquals(0.0, r.uptimePercent().getAsDouble());
        assertTrue(r.averageResponseMs().isEmpty());
        assertTrue(r.slowestResponseMs().isEmpty());
    }

    // ---------- response times ----------

    @Test
    void averageAndSlowestIgnoreFailedChecks() {
        // Failed checks store 0 ms; if they were counted the average would be 200, not 300.
        ServiceReport r = report(List.of(up(100), slow(500), down()));
        assertEquals(300.0, r.averageResponseMs().getAsDouble(), 1e-9);
        assertEquals(500, r.slowestResponseMs().getAsInt());
    }

    // ---------- recent window ----------

    @Test
    void recentReturnsTheLastNOldestFirst() {
        List<HealthResult> all = new ArrayList<>();
        for (int i = 1; i <= 7; i++) all.add(up(i));
        List<HealthResult> last3 = ReportGenerator.recent(all, 3);
        assertEquals(List.of(5, 6, 7), last3.stream().map(HealthResult::getResponseTimeMs).toList());
        assertEquals(7, all.size());   // input untouched
    }

    @Test
    void recentWithFewerResultsThanNReturnsAll() {
        assertEquals(2, ReportGenerator.recent(List.of(up(1), up(2)), 10).size());
        assertTrue(ReportGenerator.recent(List.of(), 10).isEmpty());
    }

    @Test
    void recentRejectsNonPositiveN() {
        assertThrows(IllegalArgumentException.class, () -> ReportGenerator.recent(List.of(), 0));
    }

    @Test
    void recentUptimeShowsCurrentHealthWhileOverallShowsHistory() {
        List<HealthResult> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) all.add(down());
        for (int i = 0; i < ReportGenerator.RECENT_WINDOW; i++) all.add(up(10));
        ServiceReport r = report(all);
        assertEquals(100.0, r.recentUptimePercent().getAsDouble(), 1e-9);
        assertEquals(100.0 * 10 / 15, r.uptimePercent().getAsDouble(), 1e-9);
    }

    // ---------- incidents ----------

    @Test
    void incidentCountAndDowntimeOnlyCoverThisService() {
        List<Incident> history = List.of(
                new Incident("INC-1", "a", "Alpha", T0, T0.plusSeconds(4), IncidentStatus.RESOLVED, 4),
                new Incident("INC-2", "b", "Beta", T0, T0.plusSeconds(9), IncidentStatus.RESOLVED, 9),
                new Incident("INC-3", "a", "Alpha", T0, null, IncidentStatus.OPEN, 3));
        ServiceReport r = ReportGenerator.forService("a", "Alpha", List.of(), history);
        assertEquals(2, r.incidentCount());
        assertEquals(7, r.downtimeRounds());
    }

    // ---------- ranking ----------

    private static ServiceReport rep(String id, String name, Double uptime, Double avg) {
        return new ServiceReport(id, name, uptime == null ? 0 : 10, 0, 0, 0,
                uptime == null ? OptionalDouble.empty() : OptionalDouble.of(uptime),
                OptionalDouble.empty(),
                avg == null ? OptionalDouble.empty() : OptionalDouble.of(avg),
                OptionalInt.empty(), 0, 0);
    }

    private static List<String> ids(List<ServiceReport> reports) {
        return reports.stream().map(ServiceReport::serviceId).toList();
    }

    @Test
    void sortByUptimeIsBestFirst_tiesByName_andNoDataLast() {
        List<ServiceReport> input = List.of(
                rep("nodata", "Zed", null, null),
                rep("low", "Low", 50.0, 10.0),
                rep("tieB", "bravo", 90.0, 10.0),
                rep("zero", "Zero", 0.0, null),
                rep("tieA", "Alpha", 90.0, 10.0));
        assertEquals(List.of("tieA", "tieB", "low", "zero", "nodata"),
                ids(ReportGenerator.sort(input, ReportOrder.UPTIME)));
    }

    @Test
    void sortByResponseTimeIsFastestFirst_andNoDataLast() {
        List<ServiceReport> input = List.of(
                rep("none", "A", 100.0, null),
                rep("slow", "B", 100.0, 300.0),
                rep("fast", "C", 100.0, 20.0));
        assertEquals(List.of("fast", "slow", "none"),
                ids(ReportGenerator.sort(input, ReportOrder.RESPONSE_TIME)));
    }

    @Test
    void sortByNameIgnoresCase() {
        List<ServiceReport> input = List.of(rep("1", "banana", 1.0, 1.0), rep("2", "Apple", 1.0, 1.0),
                rep("3", "cherry", 1.0, 1.0));
        assertEquals(List.of("2", "1", "3"), ids(ReportGenerator.sort(input, ReportOrder.NAME)));
    }

    @Test
    void sortReturnsANewListAndLeavesTheInputAlone() {
        List<ServiceReport> input = new ArrayList<>(List.of(rep("2", "B", 1.0, 1.0), rep("1", "A", 1.0, 1.0)));
        ReportGenerator.sort(input, ReportOrder.NAME);
        assertEquals(List.of("2", "1"), ids(input));
    }
}
