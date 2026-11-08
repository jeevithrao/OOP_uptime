import exception.InvalidServiceException;
import healthcheck.HealthChecker;
import healthcheck.HealthCheckerFactory;
import healthcheck.HealthResult;
import model.ApiService;
import model.CacheService;
import model.DatabaseService;
import model.ServiceStatus;
import model.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * TEMPORARY Phase 2 runner: shows the Strategy pattern at work. Replaced by the real menu in Phase 4.
 */
public class Main {

    private static final int CHECKS = 200;

    public static void main(String[] args) throws InvalidServiceException {
        System.out.println("=== 1. Strategy: same services, same random seeds, different checker ===");
        for (String type : List.of("basic", "latency")) {
            HealthChecker checker = HealthCheckerFactory.create(type);   // factory picks the class
            System.out.println("\nchecker = " + type + " (" + checker.getClass().getSimpleName() + ")");
            for (Service s : newServices()) {                            // fresh services = same sequence
                Map<ServiceStatus, Integer> tally = new EnumMap<>(ServiceStatus.class);
                for (int i = 0; i < CHECKS; i++) {
                    tally.merge(checker.check(s).getStatus(), 1, Integer::sum);
                }
                System.out.printf("   %-14s UP=%-3d DEGRADED=%-3d DOWN=%-3d%n", s.getName(),
                        tally.getOrDefault(ServiceStatus.UP, 0),
                        tally.getOrDefault(ServiceStatus.DEGRADED, 0),
                        tally.getOrDefault(ServiceStatus.DOWN, 0));
            }
        }

        System.out.println("\n=== 2. A few individual results (latency checker, Payments API) ===");
        HealthChecker latency = HealthCheckerFactory.create("latency");
        Service api = newServices().get(1);
        for (int i = 1; i <= 8; i++) {
            HealthResult r = latency.check(api);
            System.out.println("   check " + i + ": " + r);
        }

        System.out.println("\n=== 3. Factory rejects unknown types ===");
        try {
            HealthCheckerFactory.create("fancy");
        } catch (IllegalArgumentException e) {
            System.out.println("   rejected: " + e.getMessage());
        }
    }

    private static List<Service> newServices() throws InvalidServiceException {
        return List.of(
                new DatabaseService("db-1", "Orders DB", new Random(1)),
                new ApiService("api-1", "Payments API", new Random(2)),
                new CacheService("cache-1", "Session Cache", new Random(3)));
    }
}
