package model;

import exception.InvalidServiceException;
import exception.ServiceDownException;
import exception.ServiceTimeoutException;

import java.util.Random;

/** Flaky: frequent latency spikes and random failures, either 5xx errors or timeouts. */
public class ApiService extends Service {

    private static final double DEFAULT_FAILURE_RATE = 0.15;
    private static final double SPIKE_CHANCE = 0.20;
    private static final int NORMAL_MIN_MS = 50, NORMAL_MAX_MS = 200;
    private static final int SPIKE_MIN_MS = 500, SPIKE_MAX_MS = 1500;
    private static final int TIMEOUT_MS = 2000;

    public ApiService(String id, String name, Random random) throws InvalidServiceException {
        this(id, name, DEFAULT_FAILURE_RATE, random);
    }

    public ApiService(String id, String name, double failureRate, Random random)
            throws InvalidServiceException {
        super(id, name, failureRate, random);
    }

    @Override
    protected int doSimulateRequest() throws ServiceDownException, ServiceTimeoutException {
        if (shouldFail()) {
            // Two failure styles, so the system must handle more than one exception type.
            if (coinFlip()) {
                throw new ServiceTimeoutException(getName() + ": timed out after " + TIMEOUT_MS + " ms");
            }
            throw new ServiceDownException(getName() + ": HTTP 503");
        }
        return chance(SPIKE_CHANCE) ? between(SPIKE_MIN_MS, SPIKE_MAX_MS)
                                    : between(NORMAL_MIN_MS, NORMAL_MAX_MS);
    }
}
