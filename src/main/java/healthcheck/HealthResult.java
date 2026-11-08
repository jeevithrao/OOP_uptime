package healthcheck;

import model.ServiceStatus;

import java.time.LocalDateTime;

/**
 * The outcome of one health check. Immutable: once a check has happened its record must never
 * change, because reports and incident history are built from these objects.
 *
 * The verdict is stored once, as a ServiceStatus. "Success" is derived from it, so the two can
 * never contradict each other (no "success=true but status=DOWN" objects can exist).
 */
public final class HealthResult {

    private final String serviceId;
    private final LocalDateTime timestamp;
    private final ServiceStatus status;
    private final int responseTimeMs;   // 0 when the check failed: no response was received
    private final String errorMessage;  // empty when the check did not fail

    /**
     * Public so FileStorage (Phase 5) can rebuild results from saved lines, with the original timestamp.
     * Bad arguments here are programming errors, not user input, so they throw unchecked exceptions.
     */
    public HealthResult(String serviceId, LocalDateTime timestamp, ServiceStatus status,
                        int responseTimeMs, String errorMessage) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (responseTimeMs < 0) {
            throw new IllegalArgumentException("responseTimeMs must not be negative: " + responseTimeMs);
        }
        this.serviceId = serviceId;
        this.timestamp = timestamp;
        this.status = status;
        this.responseTimeMs = responseTimeMs;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    // Named factories read better at the call site than a 5-argument constructor.

    public static HealthResult up(String serviceId, int responseTimeMs) {
        return new HealthResult(serviceId, LocalDateTime.now(), ServiceStatus.UP, responseTimeMs, "");
    }

    public static HealthResult degraded(String serviceId, int responseTimeMs) {
        return new HealthResult(serviceId, LocalDateTime.now(), ServiceStatus.DEGRADED, responseTimeMs, "");
    }

    public static HealthResult down(String serviceId, String errorMessage) {
        return new HealthResult(serviceId, LocalDateTime.now(), ServiceStatus.DOWN, 0, errorMessage);
    }

    /** A check succeeded if the service answered at all. DEGRADED is slow, but it is not a failure. */
    public boolean isSuccess() {
        return status != ServiceStatus.DOWN;
    }

    public boolean isDegraded() {
        return status == ServiceStatus.DEGRADED;
    }

    public String getServiceId() { return serviceId; }

    public LocalDateTime getTimestamp() { return timestamp; }

    public ServiceStatus getStatus() { return status; }

    public int getResponseTimeMs() { return responseTimeMs; }

    public String getErrorMessage() { return errorMessage; }

    @Override
    public String toString() {
        return isSuccess()
                ? serviceId + " " + status + " (" + responseTimeMs + " ms)"
                : serviceId + " " + status + " (" + errorMessage + ")";
    }
}
