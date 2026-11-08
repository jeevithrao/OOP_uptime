package incident;

import healthcheck.HealthResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides when an outage is real and tells listeners about it (Subject in the Observer pattern).
 *
 * Rule: a service that fails `threshold` checks IN A ROW opens an incident; the first successful
 * check resolves it. DEGRADED counts as success (see HealthResult.isSuccess()).
 *
 * It knows nothing about alert channels (only the IncidentListener interface) and nothing about
 * how checks are made (it receives finished HealthResults).
 */
public class IncidentManager {

    public static final int DEFAULT_THRESHOLD = 3;

    // Consecutive failures per service id. A service with no failing streak has no entry.
    private final Map<String, Integer> consecutiveFailures = new HashMap<>();
    // The currently open incident per service id (at most one per service).
    private final Map<String, Incident> openIncidents = new HashMap<>();
    // Every incident ever opened, in order. ArrayList keeps insertion order for the history view.
    private final List<Incident> history = new ArrayList<>();
    private final List<IncidentListener> listeners = new ArrayList<>();

    private int threshold;
    private int nextIncidentNumber = 1;

    public IncidentManager() {
        this(DEFAULT_THRESHOLD);
    }

    public IncidentManager(int threshold) {
        this.threshold = validateThreshold(threshold);
    }

    // ---------------- core rule ----------------

    /** Convenience overload: the service id doubles as the display name. */
    public void recordResult(HealthResult result) {
        recordResult(result, null);
    }

    /**
     * Feed one finished health check into the incident logic.
     *
     * @param serviceName display name for alerts. HealthResult only carries the id, and passing the
     *                    name in keeps this class independent of the Service class. Null means "use the id".
     */
    public void recordResult(HealthResult result, String serviceName) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        String serviceId = result.getServiceId();
        String displayName = (serviceName == null || serviceName.isBlank()) ? serviceId : serviceName;

        if (result.isSuccess()) {
            consecutiveFailures.remove(serviceId);               // a success breaks the streak
            Incident open = openIncidents.remove(serviceId);
            if (open != null) {
                open.resolve(result.getTimestamp());
                notifyResolved(open);                            // state is updated BEFORE notifying
            }
            return;
        }

        int streak = consecutiveFailures.merge(serviceId, 1, Integer::sum);
        Incident open = openIncidents.get(serviceId);
        if (open != null) {
            open.recordFailure();                                // already open: just count it
        } else if (streak >= threshold) {
            // >= rather than ==, so lowering the threshold while a streak is running still triggers.
            Incident incident = new Incident("INC-" + nextIncidentNumber++, serviceId,
                    displayName, result.getTimestamp(), streak);
            openIncidents.put(serviceId, incident);
            history.add(incident);
            notifyOpened(incident);
        }
    }

    // ---------------- observer registration ----------------

    public void addListener(IncidentListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        listeners.add(listener);
    }

    public void removeListener(IncidentListener listener) {
        listeners.remove(listener);
    }

    private void notifyOpened(Incident incident) {
        // Iterate over a copy so a listener may register or unregister during the callback.
        for (IncidentListener l : new ArrayList<>(listeners)) {
            try {
                l.onIncidentOpened(incident);
            } catch (RuntimeException e) {
                reportListenerFailure(l, e);
            }
        }
    }

    private void notifyResolved(Incident incident) {
        for (IncidentListener l : new ArrayList<>(listeners)) {
            try {
                l.onIncidentResolved(incident);
            } catch (RuntimeException e) {
                reportListenerFailure(l, e);
            }
        }
    }

    /** One broken alert channel (e.g. unwritable file) must not stop monitoring or the other channels. */
    private void reportListenerFailure(IncidentListener listener, RuntimeException e) {
        System.err.println("Listener " + listener.getClass().getSimpleName() + " failed: " + e.getMessage());
    }

    // ---------------- configuration and queries ----------------

    public int getThreshold() { return threshold; }

    public void setThreshold(int threshold) {
        this.threshold = validateThreshold(threshold);
    }

    private static int validateThreshold(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("Threshold must be at least 1, got " + threshold);
        }
        return threshold;
    }

    public int getConsecutiveFailures(String serviceId) {
        return consecutiveFailures.getOrDefault(serviceId, 0);
    }

    public boolean hasOpenIncident(String serviceId) {
        return openIncidents.containsKey(serviceId);
    }

    public List<Incident> getOpenIncidents() {
        return List.copyOf(openIncidents.values());
    }

    /** Read-only view: callers can look at the history but cannot change it. */
    public List<Incident> getHistory() {
        return Collections.unmodifiableList(history);
    }
}
