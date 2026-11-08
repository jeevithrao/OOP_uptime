package model;

import exception.InvalidServiceException;
import exception.ServiceDownException;

import java.util.Random;

/** Reliable and fairly quick, but now and then a query runs slowly. Rarely fails. */
public class DatabaseService extends Service {

    private static final double DEFAULT_FAILURE_RATE = 0.03;
    private static final double SLOW_QUERY_CHANCE = 0.10;
    private static final int NORMAL_MIN_MS = 20, NORMAL_MAX_MS = 60;
    private static final int SLOW_MIN_MS = 300, SLOW_MAX_MS = 800;

    public DatabaseService(String id, String name, Random random) throws InvalidServiceException {
        this(id, name, DEFAULT_FAILURE_RATE, random);
    }

    public DatabaseService(String id, String name, double failureRate, Random random)
            throws InvalidServiceException {
        super(id, name, failureRate, random);
    }

    @Override
    protected int doSimulateRequest() throws ServiceDownException {
        if (shouldFail()) {
            throw new ServiceDownException(getName() + ": connection refused");
        }
        return chance(SLOW_QUERY_CHANCE) ? between(SLOW_MIN_MS, SLOW_MAX_MS)
                                         : between(NORMAL_MIN_MS, NORMAL_MAX_MS);
    }
}
