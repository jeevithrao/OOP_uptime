package exception;

/** Thrown when saving or loading data fails. Wraps the underlying IOException as the cause. */
public class StorageException extends MonitorException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
