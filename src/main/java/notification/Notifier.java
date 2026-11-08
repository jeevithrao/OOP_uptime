package notification;

import incident.Incident;
import incident.IncidentListener;

import java.time.format.DateTimeFormatter;

/**
 * Base class of every alert channel. It is an IncidentListener, so a notifier can be registered with
 * IncidentManager directly, and it never knows how failures are detected.
 *
 * Template method again: this class decides WHAT the alert says (written once, so all channels
 * agree); each subclass decides only HOW it is delivered.
 */
public abstract class Notifier implements IncidentListener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Short channel label used in the alert prefix, e.g. "console". */
    protected abstract String channelName();

    /** Send one finished, formatted message through this channel. */
    protected abstract void deliver(String message);

    // final: subclasses customise delivery, not the alert logic.
    @Override
    public final void onIncidentOpened(Incident incident) {
        deliver(prefix() + incident.getServiceName() + " is DOWN (" + incident.getId() + ", "
                + incident.getFailureCount() + " consecutive failures, since "
                + TIME.format(incident.getStartTime()) + ")");
    }

    @Override
    public final void onIncidentResolved(Incident incident) {
        deliver(prefix() + incident.getServiceName() + " has RECOVERED (" + incident.getId()
                + ", downtime: " + incident.getFailureCount() + " rounds)");
    }

    private String prefix() {
        return "[ALERT -> " + channelName() + "] ";
    }
}
