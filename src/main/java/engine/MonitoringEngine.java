package engine;

import exception.InvalidServiceException;
import exception.StorageException;
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
import report.ReportGenerator;
import report.ServiceReport;
import storage.AppLogger;
import storage.FileStorage;
import storage.ServiceRecord;
import storage.Storage;
import storage.StorageData;

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
    /** Normal runs keep their data here; demo runs use a separate folder so they never touch real data. */
    public static final Path DEFAULT_DATA_DIR = Path.of("data");
    public static final Path DEMO_DATA_DIR = Path.of("data-demo");

    private static MonitoringEngine instance;

    private final ServiceFactory serviceFactory;
    private final HealthChecker checker;
    private final IncidentManager incidentManager;
    private final AppLogger logger;
    private final Storage storage;

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
                     IncidentManager incidentManager, AppLogger logger, Storage storage) {
        if (serviceFactory == null || checker == null || incidentManager == null
                || logger == null || storage == null) {
            throw new IllegalArgumentException("engine dependencies must not be null");
        }
        this.storage = storage;
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
            instance = createDefault(new Random(), DEFAULT_DATA_DIR);
        }
        return instance;
    }

    /** Demo mode: a seeded engine behaves identically every run. Must be called before getInstance(). */
    public static synchronized MonitoringEngine initialize(long seed) {
        if (instance != null) {
            throw new IllegalStateException("MonitoringEngine already created; initialize() must come first");
        }
        instance = createDefault(new Random(seed), DEMO_DATA_DIR);
        return instance;
    }

    /** The production wiring: console + file + simulated email alerts, latency-aware checking. */
    private static MonitoringEngine createDefault(Random master, Path dataDir) {
        IncidentManager manager = new IncidentManager();
        manager.addListener(new ConsoleNotifier());
        manager.addListener(new FileNotifier(Path.of("data", "alerts.log")));
        manager.addListener(new EmailNotifier("ops@example.com"));
        return new MonitoringEngine(new ServiceFactory(master),
                HealthCheckerFactory.create(DEFAULT_CHECKER), manager, AppLogger.getInstance(),
                new FileStorage(dataDir));
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

    // ---------------- reports ----------------

    /** One report per service, in insertion order. Read-only: nothing is modified. */
    public List<ServiceReport> getReports() {
        List<Incident> history = incidentManager.getHistory();
        List<ServiceReport> reports = new ArrayList<>();
        for (Service service : services.values()) {
            reports.add(ReportGenerator.forService(service.getId(), service.getName(),
                    results.get(service.getId()), history));
        }
        return reports;
    }

    // ---------------- saving and loading ----------------

    /** Writes services, all check results and the incident history through the Storage. */
    public void save() throws StorageException {
        List<ServiceRecord> records = new ArrayList<>();
        List<HealthResult> allResults = new ArrayList<>();
        for (Service service : services.values()) {
            String type;
            try {
                type = ServiceFactory.typeNameOf(service);
            } catch (IllegalArgumentException e) {
                throw new StorageException("Cannot save service '" + service.getId() + "': " + e.getMessage(), e);
            }
            records.add(new ServiceRecord(type, service.getId(), service.getName(),
                    service.getFailureRate(), service.getStatus()));
            allResults.addAll(results.get(service.getId()));
        }
        try {
            storage.save(new StorageData(records, allResults, incidentManager.getHistory()));
        } catch (StorageException e) {
            logger.error("Save failed: " + e.getMessage());
            throw e;
        }
        logger.info("Saved " + records.size() + " service(s), " + allResults.size() + " result(s), "
                + incidentManager.getHistory().size() + " incident(s)");
    }

    /**
     * Restores saved data into this (empty) engine. All-or-nothing: everything is built and checked
     * first, and only then put in place, so bad data never leaves the engine half-loaded.
     * Not restored on purpose: demo force-failure switches, the failure threshold, and the round
     * counter (a round number belongs to one session).
     */
    public void load() throws StorageException {
        if (!services.isEmpty() || !incidentManager.getHistory().isEmpty()) {
            throw new IllegalStateException("load() must be called on an empty engine");
        }
        StorageData data = storage.load();

        // Step 1: rebuild the services in a local map. Any invalid record aborts before anything changes.
        Map<String, Service> loaded = new LinkedHashMap<>();
        for (ServiceRecord record : data.services()) {
            try {
                Service service = serviceFactory.create(record.type(), record.id(), record.name(), record.failureRate());
                service.setStatus(record.status());
                if (loaded.putIfAbsent(service.getId(), service) != null) {
                    throw new StorageException("Saved data lists service '" + service.getId() + "' twice");
                }
            } catch (InvalidServiceException e) {
                throw new StorageException("Saved service '" + record.id() + "' is invalid: " + e.getMessage(), e);
            }
        }

        // Step 2: restore incidents. The manager validates everything first and changes nothing on failure.
        try {
            incidentManager.restoreHistory(data.incidents());
        } catch (IllegalArgumentException e) {
            throw new StorageException("Saved incidents are inconsistent: " + e.getMessage(), e);
        }

        // Step 3: nothing can fail from here on, so commit.
        for (Service service : loaded.values()) {
            services.put(service.getId(), service);
            results.put(service.getId(), new ArrayList<>());
        }
        int ignored = 0;
        for (HealthResult result : data.results()) {
            List<HealthResult> list = results.get(result.getServiceId());
            if (list == null) {
                ignored++;                 // belongs to a service that is no longer saved
            } else {
                list.add(result);
            }
        }
        // An open incident for a service that no longer exists can never recover, so close it.
        for (Incident open : new ArrayList<>(incidentManager.getOpenIncidents())) {
            if (!services.containsKey(open.getServiceId())) {
                incidentManager.forgetService(open.getServiceId(), LocalDateTime.now(),
                        "Service missing when saved data was loaded");
            }
        }
        logger.info("Loaded " + services.size() + " service(s) and " + incidentManager.getHistory().size()
                + " incident(s)" + (ignored > 0 ? "; ignored " + ignored + " result(s) of unknown services" : ""));
    }

    // ---------------- read access for the menu ----------------

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
