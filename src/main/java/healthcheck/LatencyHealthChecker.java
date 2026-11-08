package healthcheck;

import model.Service;

/**
 * Like BasicHealthChecker, but a response slower than the threshold is DEGRADED instead of UP.
 * Reuses BasicHealthChecker for the call and failure handling, and only adds the speed rule.
 */
public class LatencyHealthChecker implements HealthChecker {

    public static final int DEFAULT_SLOW_THRESHOLD_MS = 400;

    private final HealthChecker basic = new BasicHealthChecker();
    private final int slowThresholdMs;

    public LatencyHealthChecker() {
        this(DEFAULT_SLOW_THRESHOLD_MS);
    }

    public LatencyHealthChecker(int slowThresholdMs) {
        if (slowThresholdMs <= 0) {
            throw new IllegalArgumentException("slowThresholdMs must be positive: " + slowThresholdMs);
        }
        this.slowThresholdMs = slowThresholdMs;
    }

    @Override
    public HealthResult check(Service service) {
        HealthResult result = basic.check(service);
        // Only a successful answer can be "slow"; a DOWN result is passed through untouched.
        // Strictly greater: a response exactly at the threshold is still acceptable.
        if (result.isSuccess() && result.getResponseTimeMs() > slowThresholdMs) {
            return HealthResult.degraded(service.getId(), result.getResponseTimeMs());
        }
        return result;
    }

    public int getSlowThresholdMs() { return slowThresholdMs; }
}
