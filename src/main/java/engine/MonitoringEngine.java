package engine;

import exception.InvalidServiceException;
import healthcheck.HealthChecker;
import healthcheck.HealthCheckerFactory;
import healthcheck.HealthResult;
import incident.Incident;
import incident.IncidentListener;
import incident.IncidentManager;
import model.Service;
import model.ServiceFactory;
import notification.ConsoleNotifier;
import notification.EmailNotifier;
import notification.FileNotifier;
import storage.AppLogger;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * The coordinator (Singleton). It owns the services and runs the flow:
 * check a service -> record the verdict -> hand the result to the incident logic.
 * It contains no health-check or incident rules itself; it only connects the modules,
 * each of which is used through its interface.
 */
public class MonitoringEngine {

    /**
     * Called for every check, AFTER the result is recorded but BEFORE the incident logic runs.
     * That ordering lets the console print "[Round 4] ... FAIL" before the alert it triggers.
     * consecutiveFailures is the streak including this check.
     */
    @FunctionalInterface
    public interface CheckObserver {
        void onCheck(int round, Service service, HealthResult result, int consecutiveFailures);
    }

    public static final String DEFAULT_CHECKER = "latency";

    private static MonitoringEngine instance;

    private final ServiceFactory serviceFactory;
    private final HealthChecker checker;
    private final IncidentManager incidentManager;
    private final AppLogger logger;

    // LinkedHashMap is a HashMap that remembers insertion order, so listings are stable.
    private final Map<String, Service> services = new LinkedHashMap<>();
    // Every check result per service, kept for the reports (Phase 5).
    private final Map<String, List<HealthResult>> results = new HashMap<>();
    private int roundCounter = 0;

    /**
     * Package-private: production code uses getInstance(); tests build an engine with their own
     * collaborators. That keeps the Singleton while every dependency stays injectable.
     */
    MonitoringEngine(ServiceFactory serviceFactory, HealthChecker checker,
                     IncidentManager incidentManager, AppLogger logger) {
        if (serviceFactory == null || checker == null || incidentManager == null || logger == null) {
            throw new IllegalArgumentException("engine dependencies must not be null");
        }
        this.serviceFactory = serviceFactory;
        this.checker = checker;
        this.incidentManager = incidentManager;
        this.logger = logger;

        // The log is just another observer of incidents.
        incidentManager.addListener(new IncidentListener() {
            @Override
            public void onIncidentOpened(Incident i) {
                logger.warn("Incident " + i.getId() + " OPENED for " + i.getServiceName()
                        + " after " + i.getFailureCount() + " consecutive failures");
            }

            @Override
            public void onIncidentResolved(Incident i) {
                logger.info("Incident " + i.getId() + " RESOLVED for " + i.getServiceName()
                        + ", downtime: " + i.getFailureCount() + " rounds");
            }
        });
    }

    // ---------------- Singleton access ----------------

    public static synchronized MonitoringEngine getInstance() {
        if (instance == null) {
            instance = createDefault(new Random());
        }
        return instance;
    }

    /** Demo mode: a seeded engine behaves identically every run. Must be called before getInstance(). */
    public static synchronized MonitoringEngine initialize(long seed) {
        if (instance != null) {
            throw new IllegalStateException("MonitoringEngine already created; initialize() must come first");
        }
        instance = createDefault(new Random(seed));
        return instance;
    }

    /** The production wiring: console + file + simulated email alerts, latency-aware checking. */
    private static MonitoringEngine createDefault(Random master) {
        IncidentManager manager = new IncidentManager();
        manager.addListener(new ConsoleNotifier());
        manager.addListener(new FileNotifier(Path.of("data", "alerts.log")));
        manager.addListener(new EmailNotifier("ops@example.com"));
        return new MonitoringEngine(new ServiceFactory(master),
                HealthCheckerFactory.create(DEFAULT_CHECKER), manager, AppLogger.getInstance());
    }

    // ---------------- managing services ----------------

    /** Creates a service from a type name ("database", "api", "cache") and starts monitoring it. */
    public Service addService(String type, String id, String name) throws InvalidServiceException {
        Service service = serviceFactory.create(type, id, name);
        addService(service);
        return service;
    }

    /** Starts monitoring a ready-made service (also what the file loader uses in Phase 5). */
    public void addService(Service service) throws InvalidServiceException {
        if (service == null) {
            throw new IllegalArgumentException("service must not be null");
        }
        if (services.containsKey(service.getId())) {
            throw new InvalidServiceException("A service with id '" + service.getId() + "' already exists");
        }
        services.put(service.getId(), service);
        results.put(service.getId(), new ArrayList<>());
        logger.info("Service added: " + service);
    }

    /**
     * Stops monitoring a service. If it had an open incident, that incident is closed with a note
     * (it did not recover, so no RECOVERED alert is sent) and its failure streak is cleared.
     * Its stored check results are dropped so a later service reusing the id starts clean.
     *
     * @return the incident that was closed by the removal, if any
     */
    public Optional<Incident> removeService(String id) throws InvalidServiceException {
        Service removed = require(id);
        services.remove(removed.getId());
        results.remove(removed.getId());
        Optional<Incident> closed = incidentManager.forgetService(
                removed.getId(), LocalDateTime.now(), "Service removed while incident was open");
        logger.info("Service removed: " + removed
                + closed.map(i -> " (closed open incident " + i.getId() + ")").orElse(""));
        return closed;
    }

    public Service getService(String id) throws InvalidServiceException {
        return require(id);
    }

    /** Snapshot in insertion order; changing the returned list does not affect the engine. */
    public List<Service> getServices() {
        return List.copyOf(services.values());
    }

    /** Demo mode: makes a service fail on cue (or stops forcing it). */
    public void setForceFailure(String id, boolean forced) throws InvalidServiceException {
        Service service = require(id);
        service.setForceFailure(forced);
        logger.info("Demo mode: " + service.getName() + " forced failure = " + forced);
    }

    private Service require(String id) throws InvalidServiceException {
        Service service = id == null ? null : services.get(id.trim());
        if (service == null) {
            throw new InvalidServiceException("No service with id '" + id + "'");
        }
        return service;
    }

    // ---------------- running checks ----------------

    /** Checks every service once. Returns that round's results (empty if there are no services). */
    public List<HealthResult> runRound(CheckObserver observer) {
        if (services.isEmpty()) {
            return List.of();
        }
        int round = ++roundCounter;
        List<HealthResult> roundResults = new ArrayList<>();
        // Iterate over a copy so a CheckObserver that changes services cannot break the loop.
        for (Service service : new ArrayList<>(services.values())) {
            HealthResult result = checker.check(service);
            service.setStatus(result.getStatus());          // the engine, not the service, records the verdict
            results.get(service.getId()).add(result);

            String line = "[Round " + round + "] " + result;
            if (result.isSuccess()) {
                logger.info(line);
            } else {
                logger.warn(line);
            }

            if (observer != null) {
                // The incident logic has not seen this result yet, so add this check to the streak by hand.
                int streak = result.isSuccess() ? 0 : incidentManager.getConsecutiveFailures(service.getId()) + 1;
                observer.onCheck(round, service, result, streak);
            }
            incidentManager.recordResult(result, service.getName());
            roundResults.add(result);
        }
        return roundResults;
    }

    public void runRounds(int count, CheckObserver observer) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, got " + count);
        }
        for (int i = 0; i < count; i++) {
            runRound(observer);
        }
    }

    // ---------------- read access for the menu and reports ----------------

    public int getRoundCount() { return roundCounter; }

    public int getFailureThreshold() { return incidentManager.getThreshold(); }

    public void setFailureThreshold(int threshold) {
        incidentManager.setThreshold(threshold);            // validates; throws IllegalArgumentException if < 1
        logger.info("Failure threshold set to " + threshold);
    }

    public int getConsecutiveFailures(String serviceId) {
        return incidentManager.getConsecutiveFailures(serviceId);
    }

    public List<Incident> getIncidentHistory() { return incidentManager.getHistory(); }

    public List<Incident> getOpenIncidents() { return incidentManager.getOpenIncidents(); }

    /** Read-only view of one service's check results, oldest first. */
    public List<HealthResult> getResults(String serviceId) throws InvalidServiceException {
        Service service = require(serviceId);
        return Collections.unmodifiableList(results.get(service.getId()));
    }
}
