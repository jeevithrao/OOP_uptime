package exception;

/**
 * Base class of every exception this application defines.
 * Checked on purpose: the compiler forces callers to deal with failures
 * (a service being down is an expected event, not a programming bug).
 */
public class MonitorException extends Exception {

    public MonitorException(String message) {
        super(message);
    }

    // Keeps the original cause (e.g. an IOException) so stack traces stay useful.
    public MonitorException(String message, Throwable cause) {
        super(message, cause);
    }
}
