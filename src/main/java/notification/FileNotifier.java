package notification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/** Appends one timestamped line per alert to a file, creating the file and its folder if needed. */
public class FileNotifier extends Notifier {

    private final Path file;

    public FileNotifier(Path file) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        this.file = file;
    }

    @Override
    protected String channelName() { return "file"; }

    @Override
    protected void deliver(String message) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, LocalDateTime.now() + " " + message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Listener methods cannot throw checked exceptions. IncidentManager catches runtime
            // exceptions per listener, so a broken file never blocks the other channels.
            throw new UncheckedIOException("Could not write alert to " + file, e);
        }
    }
}
