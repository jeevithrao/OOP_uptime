package storage;

import healthcheck.HealthResult;
import incident.Incident;

import java.util.List;

/** Everything that is persisted, as one value: services, every check result, and the incident history. */
public record StorageData(List<ServiceRecord> services, List<HealthResult> results, List<Incident> incidents) {

    /** Compact constructor: defensive copies, so the saved snapshot cannot change after it is built. */
    public StorageData {
        services = List.copyOf(services);
        results = List.copyOf(results);
        incidents = List.copyOf(incidents);
    }

    public static StorageData empty() {
        return new StorageData(List.of(), List.of(), List.of());
    }
}
