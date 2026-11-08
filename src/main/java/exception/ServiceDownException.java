package exception;

/** Thrown by a service when a simulated request fails outright (connection refused, HTTP 5xx...). */
public class ServiceDownException extends MonitorException {

    public ServiceDownException(String message) {
        super(message);
    }
}
