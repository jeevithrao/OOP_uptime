package storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Singleton: one application-wide log, so every module writes to the same file in order.
 *
 * Logging must never break monitoring, so no log method throws. If the file cannot be written, a
 * single warning goes to stderr and the application carries on.
 */
public final class AppLogger {

    public static final Path DEFAULT_FILE = Path.of("data", "monitor.log");

    private static final AppLogger INSTANCE = new AppLogger();   // created once, when the class loads (thread-safe)
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Path file = DEFAULT_FILE;
    private boolean warned;

    private AppLogger() { }   // private: nobody else can create a second logger

    public static AppLogger getInstance() {
        return INSTANCE;
    }

    /** Redirects the log (used by tests, or to choose another location). */
    public synchronized void setLogFile(Path file) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        this.file = file;
        this.warned = false;
    }

    public synchronized Path getLogFile() { return file; }

    public void info(String message)  { log("INFO", message); }

    public void warn(String message)  { log("WARN", message); }

    public void error(String message) { log("ERROR", message); }

    private synchronized void log(String level, String message) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file,
                    LocalDateTime.now().format(STAMP) + " [" + level + "] " + message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!warned) {            // warn once, not on every line
                warned = true;
                System.err.println("Logging disabled, cannot write " + file + ": " + e.getMessage());
            }
        }
    }
}
