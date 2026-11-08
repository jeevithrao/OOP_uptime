package model;

import exception.InvalidServiceException;
import exception.ServiceDownException;

import java.util.Random;

/** Extremely fast. Never slow, but occasionally drops out completely. */
public class CacheService extends Service {

    private static final double DEFAULT_FAILURE_RATE = 0.05;
    private static final int MIN_MS = 1, MAX_MS = 5;

    public CacheService(String id, String name, Random random) throws InvalidServiceException {
        this(id, name, DEFAULT_FAILURE_RATE, random);
    }

    public CacheService(String id, String name, double failureRate, Random random)
            throws InvalidServiceException {
        super(id, name, failureRate, random);
    }

    @Override
    protected int doSimulateRequest() throws ServiceDownException {
        if (shouldFail()) {
            throw new ServiceDownException(getName() + ": cache node dropped");
        }
        return between(MIN_MS, MAX_MS);
    }
}
