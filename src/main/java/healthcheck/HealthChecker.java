package healthcheck;

import model.Service;

/**
 * Strategy interface: "how do we judge a service?". The engine depends on this interface only,
 * so checking logic can be swapped without touching the engine.
 *
 * Contract: check() never throws for a service failure. A failure is a normal outcome and is
 * returned as a DOWN result, so callers need no try/catch.
 */
public interface HealthChecker {

    HealthResult check(Service service);
}
