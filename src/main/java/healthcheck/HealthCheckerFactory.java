package healthcheck;

import java.util.Locale;

/**
 * Factory: creates the right HealthChecker from a type name ("basic", "latency"), so callers
 * (menu, config file) never need to know the concrete classes.
 * Static and non-instantiable: it holds no state, so there is nothing to create an object for.
 */
public final class HealthCheckerFactory {

    private HealthCheckerFactory() { }

    public static HealthChecker create(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Checker type must not be blank");
        }
        switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "basic":
                return new BasicHealthChecker();
            case "latency":
                return new LatencyHealthChecker();
            default:
                throw new IllegalArgumentException(
                        "Unknown checker type '" + type + "'. Valid types: basic, latency");
        }
    }
}
