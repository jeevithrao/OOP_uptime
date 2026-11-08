package model;

/** Health state of a service. Set by the engine from health-check results, never by the service itself. */
public enum ServiceStatus {
    UP,
    DEGRADED,
    DOWN
}
