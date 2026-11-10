package report;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Statistics for one service. A Java record: a plain, immutable bundle of values with no behaviour
 * worth hiding, so a record is shorter and safer than a hand-written class.
 *
 * Values that may not exist yet (no checks run, no successful response) are Optionals instead of
 * a fake 0, so "0% uptime" can never be confused with "no data".
 */
public record ServiceReport(
        String serviceId,
        String serviceName,
        int totalChecks,
        int upChecks,
        int degradedChecks,
        int failedChecks,
        OptionalDouble uptimePercent,          // successful checks / total checks * 100
        OptionalDouble recentUptimePercent,    // same, over the last RECENT_WINDOW checks only
        OptionalDouble averageResponseMs,      // over successful checks only
        OptionalInt slowestResponseMs,         // over successful checks only
        int incidentCount,
        int downtimeRounds) {                  // sum of failed checks across this service's incidents

    /** DEGRADED answered, just slowly, so it counts as successful (same rule as HealthResult.isSuccess()). */
    public int successfulChecks() {
        return upChecks + degradedChecks;
    }
}
