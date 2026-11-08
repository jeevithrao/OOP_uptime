package notification;

import java.io.PrintStream;

/** Simulated email: nothing is really sent, it prints what an email would contain. */
public class EmailNotifier extends Notifier {

    private final String recipient;
    private final PrintStream out;

    public EmailNotifier(String recipient) {
        this(recipient, System.out);
    }

    public EmailNotifier(String recipient, PrintStream out) {
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient must not be blank");
        }
        if (out == null) throw new IllegalArgumentException("out must not be null");
        this.recipient = recipient;
        this.out = out;
    }

    @Override
    protected String channelName() { return "email"; }

    @Override
    protected void deliver(String message) {
        out.println("[EMAIL sent to " + recipient + "] " + message);
    }
}
