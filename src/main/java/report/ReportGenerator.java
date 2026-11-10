package report;

import healthcheck.HealthResult;
import incident.Incident;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Turns stored check results and incidents into statistics.
 *
 * Read-only by design (the module table says it must not modify data): every method is a pure
 * function. It receives plain lists, so it knows nothing about the engine, files or the console.
 */
public final class ReportGenerator {

    /** How many of the latest checks the "recent uptime" figure looks at. */
    public static final int RECENT_WINDOW = 10;

    private ReportGenerator() { }   // static utility, nothing to instantiate

    /**
     * @param results       this service's checks, oldest first
     * @param allIncidents  the whole incident history; this method picks out the service's own
     */
    public static ServiceReport forService(String serviceId, String serviceName,
                                           List<HealthResult> results, List<Incident> allIncidents) {
        int up = 0, degraded = 0, failed = 0;
        long responseSum = 0;
        int responseCount = 0;
        int slowest = -1;

        for (HealthResult r : results) {
            switch (r.getStatus()) {
                case UP:       up++; break;
                case DEGRADED: degraded++; break;
                default:       failed++; break;
            }
            // A failed check has no response time (stored as 0), so it must not drag the average down.
            if (r.isSuccess()) {
                responseSum += r.getResponseTimeMs();
                responseCount++;
                slowest = Math.max(slowest, r.getResponseTimeMs());
            }
        }

        int incidentCount = 0;
        int downtimeRounds = 0;
        for (Incident i : allIncidents) {
            if (i.getServiceId().equals(serviceId)) {
                incidentCount++;
                downtimeRounds += i.getFailureCount();
            }
        }

        return new ServiceReport(serviceId, serviceName, results.size(), up, degraded, failed,
                uptime(up + degraded, results.size()),
                recentUptime(results),
                responseCount == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) responseSum / responseCount),
                slowest < 0 ? OptionalInt.empty() : OptionalInt.of(slowest),
                incidentCount, downtimeRounds);
    }

    /** Uptime % = successful checks / total checks * 100; empty when nothing has been checked. */
    private static OptionalDouble uptime(int successful, int total) {
        return total == 0 ? OptionalDouble.empty() : OptionalDouble.of(100.0 * successful / total);
    }

    private static OptionalDouble recentUptime(List<HealthResult> results) {
        List<HealthResult> window = recent(results, RECENT_WINDOW);
        int successful = 0;
        for (HealthResult r : window) {
            if (r.isSuccess()) successful++;
        }
        return uptime(successful, window.size());
    }

    /**
     * The last n results, oldest first. A fixed-size sliding window: a single pass that never holds
     * more than n items, dropping the oldest from the front in O(1) (ArrayDeque).
     */
    public static List<HealthResult> recent(List<HealthResult> results, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be at least 1, got " + n);
        }
        Deque<HealthResult> window = new ArrayDeque<>();
        for (HealthResult r : results) {
            if (window.size() == n) {
                window.pollFirst();
            }
            window.addLast(r);
        }
        return List.copyOf(window);
    }

    /** Returns a NEW sorted list; the input is left untouched. Services with no data always sort last. */
    public static List<ServiceReport> sort(List<ServiceReport> reports, ReportOrder order) {
        Comparator<ServiceReport> byName = Comparator
                .comparing(ServiceReport::serviceName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ServiceReport::serviceId);   // ids are unique, so the order is always total

        Comparator<ServiceReport> comparator;
        switch (order) {
            case UPTIME:
                // Highest first. "No data" maps to -1 so it falls below even 0% when reversed.
                comparator = Comparator.<ServiceReport>comparingDouble(r -> r.uptimePercent().orElse(-1.0))
                        .reversed().thenComparing(byName);
                break;
            case RESPONSE_TIME:
                // Lowest first. "No data" maps to the largest value so it falls to the end.
                comparator = Comparator.<ServiceReport>comparingDouble(r -> r.averageResponseMs().orElse(Double.MAX_VALUE))
                        .thenComparing(byName);
                break;
            default:
                comparator = byName;
        }
        List<ServiceReport> sorted = new ArrayList<>(reports);
        sorted.sort(comparator);
        return sorted;
    }
}
