package exception;

/** Thrown when a service is created with bad input (blank id, failure rate outside 0.0 to 1.0...). */
public class InvalidServiceException extends MonitorException {

    public InvalidServiceException(String message) {
        super(message);
    }
}
