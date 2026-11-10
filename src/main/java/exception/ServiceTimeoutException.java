package exception;

/** Thrown by a service when a simulated request does not answer in time. */
public class ServiceTimeoutException extends MonitorException {

    private static final long serialVersionUID = 1L;

    public ServiceTimeoutException(String message) {
        super(message);
    }
}
