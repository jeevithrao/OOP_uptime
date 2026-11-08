package incident;

import model.IncidentStatus;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * One outage of one service, from detection to recovery.
 *
 * Stores the service's id and name as plain strings instead of a Service reference: the incident
 * is a historical record, so it must stay valid (and easy to save as CSV) even after the service
 * is removed or changed.
 *
 * Only IncidentManager may change an incident. The mutators are package-private, so listeners in
 * other packages (notifiers) receive it as read-only.
 */
public class Incident {

    private final String id;
    private final String serviceId;
    private final String serviceName;
    private final LocalDateTime startTime;
    private LocalDateTime endTime;      // null while OPEN
    private IncidentStatus status;
    private int failureCount;           // failed checks during this incident, including the ones that opened it

    /** Opens a new incident. Package-private: only IncidentManager creates live incidents. */
    Incident(String id, String serviceId, String serviceName, LocalDateTime startTime, int failureCount) {
        this(id, serviceId, serviceName, startTime, null, IncidentStatus.OPEN, failureCount);
    }

    /** Full constructor, public so storage can rebuild saved incidents (Phase 5). */
    public Incident(String id, String serviceId, String serviceName, LocalDateTime startTime,
                    LocalDateTime endTime, IncidentStatus status, int failureCount) {
        requireText(id, "id");
        requireText(serviceId, "serviceId");
        requireText(serviceName, "serviceName");
        if (startTime == null) throw new IllegalArgumentException("startTime must not be null");
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (failureCount < 1) throw new IllegalArgumentException("failureCount must be at least 1: " + failureCount);
        // Reject impossible combinations so a corrupted save file cannot create a broken incident.
        if (status == IncidentStatus.OPEN && endTime != null) {
            throw new IllegalArgumentException("An OPEN incident cannot have an end time");
        }
        if (status == IncidentStatus.RESOLVED) {
            if (endTime == null) throw new IllegalArgumentException("A RESOLVED incident needs an end time");
            if (endTime.isBefore(startTime)) throw new IllegalArgumentException("endTime is before startTime");
        }
        this.id = id;
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
        this.failureCount = failureCount;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    /** Another failed check while the incident is already open. */
    void recordFailure() {
        requireOpen();
        failureCount++;
    }

    /** Closes the incident at the time of the first successful check. */
    void resolve(LocalDateTime endTime) {
        requireOpen();
        if (endTime == null) throw new IllegalArgumentException("endTime must not be null");
        if (endTime.isBefore(startTime)) throw new IllegalArgumentException("endTime is before startTime");
        this.endTime = endTime;
        this.status = IncidentStatus.RESOLVED;
    }

    private void requireOpen() {
        if (status != IncidentStatus.OPEN) {
            throw new IllegalStateException("Incident " + id + " is already " + status);
        }
    }

    public String getId() { return id; }

    public String getServiceId() { return serviceId; }

    public String getServiceName() { return serviceName; }

    public LocalDateTime getStartTime() { return startTime; }

    /** Empty while the incident is still open. Optional makes callers handle that case. */
    public Optional<LocalDateTime> getEndTime() { return Optional.ofNullable(endTime); }

    public IncidentStatus getStatus() { return status; }

    public boolean isOpen() { return status == IncidentStatus.OPEN; }

    public int getFailureCount() { return failureCount; }

    @Override
    public String toString() {
        return "Incident[" + id + ", " + serviceName + ", " + status + ", failures=" + failureCount + "]";
    }
}
