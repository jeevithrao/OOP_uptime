import exception.InvalidServiceException;
import healthcheck.HealthChecker;
import healthcheck.HealthCheckerFactory;
import healthcheck.HealthResult;
import incident.IncidentManager;
import model.ApiService;
import model.CacheService;
import model.DatabaseService;
import model.Service;
import notification.ConsoleNotifier;
import notification.EmailNotifier;
import notification.FileNotifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * TEMPORARY Phase 3 runner: the full detect -> alert -> recover cycle.
 * It plays the role the MonitoringEngine will play in Phase 4, and is replaced by the real menu then.
 */
public class Main {

    private static final int ROUNDS = 9;

    public static void main(String[] args) throws InvalidServiceException {
        List<Service> services = List.of(
                new DatabaseService("db-1", "Orders DB", new Random(1)),
                new ApiService("api-1", "Payments API", new Random(2)),
                new CacheService("cache-1", "Session Cache", new Random(3)));

        HealthChecker checker = HealthCheckerFactory.create("latency");

        // Wiring: the manager only sees the IncidentListener interface, so adding a channel is one line.
        IncidentManager manager = new IncidentManager();
        manager.addListener(new ConsoleNotifier());
        manager.addListener(new FileNotifier(Path.of("data", "alerts.log")));
        manager.addListener(new EmailNotifier("ops@example.com"));

        Service payments = services.get(1);
        System.out.println("Threshold = " + manager.getThreshold() + " consecutive failures\n");

        for (int round = 1; round <= ROUNDS; round++) {
            // Demo switch: take the Payments API down for rounds 3 to 6, then bring it back.
            payments.setForceFailure(round >= 3 && round <= 6);

            for (Service s : services) {
                HealthResult result = checker.check(s);
                s.setStatus(result.getStatus());                       // engine's job: record the verdict
                manager.recordResult(result, s.getName());             // incident logic + alerts
                System.out.printf("[Round %d] %-14s %-8s %s   failures: %d%n", round, s.getName(),
                        result.getStatus(),
                        result.isSuccess() ? "(" + result.getResponseTimeMs() + " ms)"
                                           : "(" + result.getErrorMessage() + ")",
                        manager.getConsecutiveFailures(s.getId()));
            }
        }

        System.out.println("\nIncident history:");
        manager.getHistory().forEach(i -> System.out.println("   " + i + "  " + i.getStartTime().toLocalTime()
                + " -> " + i.getEndTime().map(t -> t.toLocalTime().toString()).orElse("still open")));
    }
}
