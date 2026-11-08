package notification;

import java.io.PrintStream;

/** Prints alerts to a stream (System.out by default). The stream is injectable so tests can capture output. */
public class ConsoleNotifier extends Notifier {

    private final PrintStream out;

    public ConsoleNotifier() {
        this(System.out);
    }

    public ConsoleNotifier(PrintStream out) {
        if (out == null) throw new IllegalArgumentException("out must not be null");
        this.out = out;
    }

    @Override
    protected String channelName() { return "console"; }

    @Override
    protected void deliver(String message) {
        out.println(message);
    }
}
