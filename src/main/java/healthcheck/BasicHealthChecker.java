package healthcheck;

import exception.ServiceDownException;
import exception.ServiceTimeoutException;
import model.Service;

/** Simplest strategy: the service either answers (UP) or it does not (DOWN). Speed is ignored. */
public class BasicHealthChecker implements HealthChecker {

    @Override
    public HealthResult check(Service service) {
        try {
            int responseTimeMs = service.simulateRequest();
            return HealthResult.up(service.getId(), responseTimeMs);
        } catch (ServiceDownException | ServiceTimeoutException e) {
            // Listed explicitly rather than catching MonitorException: if a new failure type is
            // ever added to simulateRequest(), the compiler forces us to decide how to treat it.
            return HealthResult.down(service.getId(), e.getMessage());
        }
    }
}
