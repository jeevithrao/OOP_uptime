package model;

import exception.InvalidServiceException;
import exception.ServiceDownException;
import exception.ServiceTimeoutException;

import java.util.Random;

/**
 * A monitored component. Models behaviour only: it never judges whether it is healthy.
 * Subclasses define HOW they behave (latency, failure style) by implementing doSimulateRequest().
 */
public abstract class Service {

    private final String id;            // immutable: used as a map key everywhere
    private final String name;
    private double failureRate;         // probability 0.0 to 1.0 that a request fails
    private ServiceStatus status = ServiceStatus.UP;
    private boolean forceFailure = false; // demo mode switch
    private final Random random;        // injected, so demos and tests can be seeded

    protected Service(String id, String name, double failureRate, Random random)
            throws InvalidServiceException {
        if (id == null || id.isBlank()) {
            throw new InvalidServiceException("Service id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new InvalidServiceException("Service name must not be blank");
        }
        if (random == null) {
            throw new InvalidServiceException("Random source must not be null");
        }
        this.id = id.trim();
        this.name = name.trim();
        this.failureRate = validateRate(failureRate);
        this.random = random;
    }

    /**
     * Template method: the fixed part (demo override) lives here, the varying part is delegated.
     * Final so no subclass can accidentally bypass forceFailure.
     *
     * @return simulated response time in milliseconds
     */
    public final int simulateRequest() throws ServiceDownException, ServiceTimeoutException {
        if (forceFailure) {
            throw new ServiceDownException(name + " is forced down (demo mode)");
        }
        return doSimulateRequest();
    }

    /** Each subclass defines its own latency and failure behaviour. */
    protected abstract int doSimulateRequest() throws ServiceDownException, ServiceTimeoutException;

    // ---- helpers shared by subclasses, so the random logic is written once ----

    /** True with probability failureRate. */
    protected boolean shouldFail() {
        return random.nextDouble() < failureRate;
    }

    /** True with the given probability (used for "slow query" or "latency spike" chances). */
    protected boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /** Random int in [minInclusive, maxInclusive]. */
    protected int between(int minInclusive, int maxInclusive) {
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }

    protected boolean coinFlip() {
        return random.nextBoolean();
    }

    private static double validateRate(double rate) throws InvalidServiceException {
        // Written as !(in range) so NaN is rejected too: every comparison with NaN is false.
        if (!(rate >= 0.0 && rate <= 1.0)) {
            throw new InvalidServiceException("Failure rate must be between 0.0 and 1.0, got " + rate);
        }
        return rate;
    }

    // ---- accessors ----

    public String getId() { return id; }

    public String getName() { return name; }

    public double getFailureRate() { return failureRate; }

    public void setFailureRate(double failureRate) throws InvalidServiceException {
        this.failureRate = validateRate(failureRate);
    }

    public ServiceStatus getStatus() { return status; }

    /** Called by the engine after a health check. Public because the engine lives in another package. */
    public void setStatus(ServiceStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        this.status = status;
    }

    public boolean isForceFailure() { return forceFailure; }

    public void setForceFailure(boolean forceFailure) { this.forceFailure = forceFailure; }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[id=" + id + ", name=" + name + ", status=" + status + "]";
    }
}
