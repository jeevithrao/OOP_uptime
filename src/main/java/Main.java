import exception.InvalidServiceException;
import exception.MonitorException;
import model.ApiService;
import model.CacheService;
import model.DatabaseService;
import model.Service;

import java.util.List;
import java.util.Random;

/**
 * TEMPORARY Phase 1 runner. It only proves the Service hierarchy works.
 * It is replaced by the real console menu in Phase 4.
 */
public class Main {

    private static final int REQUESTS = 12;

    public static void main(String[] args) throws InvalidServiceException {
        // One Random per service, each with its own seed: reproducible, and services do not
        // disturb each other's random sequence.
        List<Service> services = List.of(
                new DatabaseService("db-1", "Orders DB", new Random(1)),
                new ApiService("api-1", "Payments API", new Random(2)),
                new CacheService("cache-1", "Session Cache", new Random(3)));

        System.out.println("=== 1. Polymorphism: same call, different behaviour ===");
        for (Service s : services) {
            System.out.println(s);
            for (int i = 1; i <= REQUESTS; i++) {
                System.out.println("   request " + i + ": " + request(s));
            }
        }

        System.out.println("\n=== 2. Demo mode: forceFailure ===");
        Service api = services.get(1);
        api.setForceFailure(true);
        System.out.println("forced : " + request(api));
        api.setForceFailure(false);
        System.out.println("cleared: " + request(api));

        System.out.println("\n=== 3. Validation ===");
        tryCreate("blank id", () -> new CacheService("  ", "x", new Random()));
        tryCreate("rate 1.5", () -> new CacheService("c", "x", 1.5, new Random()));
        tryCreate("rate NaN", () -> new CacheService("c", "x", Double.NaN, new Random()));
    }

    /** Calls the service and turns the outcome into a printable string. */
    private static String request(Service s) {
        try {
            return "OK   " + s.simulateRequest() + " ms";
        } catch (MonitorException e) {
            return "FAIL " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    private interface Creator { Service create() throws InvalidServiceException; }

    private static void tryCreate(String label, Creator c) {
        try {
            c.create();
            System.out.println(label + ": created (unexpected)");
        } catch (InvalidServiceException e) {
            System.out.println(label + ": rejected - " + e.getMessage());
        }
    }
}
