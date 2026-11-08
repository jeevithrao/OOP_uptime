package exception;

/** Thrown by a service when a simulated request does not answer in time. */
public class ServiceTimeoutException extends MonitorException {

    public ServiceTimeoutException(String message) {
        super(message);
    }
}
