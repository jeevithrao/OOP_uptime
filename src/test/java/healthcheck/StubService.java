package healthcheck;

import exception.InvalidServiceException;
import exception.ServiceDownException;
import exception.ServiceTimeoutException;
import model.Service;

import java.util.Random;

/**
 * Test double: a Service whose behaviour is chosen by the test, with no randomness.
 * This is possible because Service.doSimulateRequest() is the only part subclasses define.
 */
class StubService extends Service {

    /** What the stub does when asked for a request. */
    interface Behaviour {
        int run() throws ServiceDownException, ServiceTimeoutException;
    }

    private final Behaviour behaviour;

    StubService(Behaviour behaviour) throws InvalidServiceException {
        super("stub-1", "Stub", 0.0, new Random(0));
        this.behaviour = behaviour;
    }

    static StubService respondingIn(int ms) throws InvalidServiceException {
        return new StubService(() -> ms);
    }

    static StubService failingDown(String message) throws InvalidServiceException {
        return new StubService(() -> { throw new ServiceDownException(message); });
    }

    static StubService failingTimeout(String message) throws InvalidServiceException {
        return new StubService(() -> { throw new ServiceTimeoutException(message); });
    }

    @Override
    protected int doSimulateRequest() throws ServiceDownException, ServiceTimeoutException {
        return behaviour.run();
    }
}
