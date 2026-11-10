package model;

import exception.InvalidServiceException;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Factory: builds the right Service subclass from a type name, so callers (menu, file loader) never
 * need to know the concrete classes.
 *
 * Every service gets its OWN Random, seeded from one master Random. Seed the master and the whole
 * session is reproducible (demo mode); services still do not disturb each other's sequences.
 */
public class ServiceFactory {

    /** Type names accepted by create(), for menus and error messages. */
    public static final List<String> TYPES = List.of("database", "api", "cache");

    private final Random seedSource;

    public ServiceFactory() {
        this(new Random());
    }

    public ServiceFactory(Random seedSource) {
        if (seedSource == null) throw new IllegalArgumentException("seedSource must not be null");
        this.seedSource = seedSource;
    }

    /** The type name that create() accepts for this service; used when saving, so loading can rebuild it. */
    public static String typeNameOf(Service service) {
        if (service instanceof DatabaseService) return "database";
        if (service instanceof ApiService) return "api";
        if (service instanceof CacheService) return "cache";
        throw new IllegalArgumentException("Unsupported service class: " + service.getClass().getName());
    }

    /** Creates a service with the default failure rate of its type. */
    public Service create(String type, String id, String name) throws InvalidServiceException {
        return build(type, id, name, null);
    }

    /** Creates a service with a custom failure rate (0.0 to 1.0). */
    public Service create(String type, String id, String name, double failureRate)
            throws InvalidServiceException {
        return build(type, id, name, failureRate);
    }

    private Service build(String type, String id, String name, Double failureRate)
            throws InvalidServiceException {
        String key = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        // Validate the type BEFORE drawing a seed, so a typo does not change later randomness.
        if (!key.equals("database") && !key.equals("db") && !key.equals("api") && !key.equals("cache")) {
            throw new InvalidServiceException("Unknown service type '" + type
                    + "'. Valid types: " + String.join(", ", TYPES));
        }
        Random random = new Random(seedSource.nextLong());
        switch (key) {
            case "api":
                return failureRate == null ? new ApiService(id, name, random)
                                           : new ApiService(id, name, failureRate, random);
            case "cache":
                return failureRate == null ? new CacheService(id, name, random)
                                           : new CacheService(id, name, failureRate, random);
            default: // "database" or "db"
                return failureRate == null ? new DatabaseService(id, name, random)
                                           : new DatabaseService(id, name, failureRate, random);
        }
    }
}
