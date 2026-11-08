package incident;

/**
 * Observer interface. Anything that wants to react to incidents implements this and registers with
 * IncidentManager. The manager knows only this interface, never the concrete listeners.
 */
public interface IncidentListener {

    void onIncidentOpened(Incident incident);

    void onIncidentResolved(Incident incident);
}
