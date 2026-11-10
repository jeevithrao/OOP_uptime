package console;

import engine.MonitoringEngine;
import exception.InvalidServiceException;
import exception.StorageException;
import healthcheck.HealthResult;
import incident.Incident;
import model.Service;
import model.ServiceFactory;
import model.ServiceStatus;
import report.ReportGenerator;
import report.ReportOrder;
import report.ServiceReport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The text menu. It only reads input, prints output and calls the engine; every rule lives in
 * the modules. Input and output streams are injected, so tests can script a whole session.
 */
public class ConsoleMenu {

    private static final int MAX_ROUNDS = 1000;     // guards against typing a huge number by accident
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final MonitoringEngine engine;
    private final BufferedReader in;
    private final PrintStream out;

    public ConsoleMenu(MonitoringEngine engine, BufferedReader in, PrintStream out) {
        if (engine == null || in == null || out == null) {
            throw new IllegalArgumentException("engine, in and out must not be null");
        }
        this.engine = engine;
        this.in = in;
        this.out = out;
    }

    /** Thrown internally when input ends (Ctrl+D / Ctrl+Z, or a script runs out), to leave the loop cleanly. */
    private static final class EndOfInput extends RuntimeException {
        private static final long serialVersionUID = 1L;
        EndOfInput() { super("end of input", null, false, false); }
    }

    public void run() {
        try {
            boolean running = true;
            while (running) {
                printMenu();
                running = handle(prompt("Choose an option: "));
                out.println();
            }
        } catch (EndOfInput e) {
            out.println();
        }
        if (save()) {
            out.println("Data saved.");
        }
        out.println("Goodbye.");
    }

    private void printMenu() {
        out.println("===== Service Health Monitor =====");
        out.println("1. Add service");
        out.println("2. Remove service");
        out.println("3. List services and status");
        out.println("4. Run one check round");
        out.println("5. Run monitoring (N rounds)");
        out.println("6. View incidents");
        out.println("7. View reports");
        out.println("8. Configure failure threshold");
        out.println("9. Force a service to fail (demo mode)");
        out.println("0. Exit");
    }

    /** @return false when the user chose Exit */
    private boolean handle(String choice) {
        switch (choice) {
            case "1": addService(); save(); break;
            case "2": removeService(); save(); break;
            case "3": listServices(); break;
            case "4": runOneRound(); save(); break;
            case "5": runManyRounds(); save(); break;
            case "6": viewIncidents(); break;
            case "7": viewReports(); break;
            case "8": configureThreshold(); break;
            case "9": toggleForcedFailure(); break;
            case "0": return false;
            default: out.println("Invalid choice '" + choice + "'. Enter a number from 0 to 9.");
        }
        return true;
    }

    // ---------------- actions ----------------

    private void addService() {
        out.println("Service types: " + String.join(", ", ServiceFactory.TYPES));
        String type = prompt("Type: ");
        String id = prompt("Id (e.g. api-1): ");
        String name = prompt("Name: ");
        try {
            Service service = engine.addService(type, id, name);
            out.println("Added " + service);
        } catch (InvalidServiceException e) {
            out.println("Could not add service: " + e.getMessage());
        }
    }

    private void removeService() {
        if (engine.getServices().isEmpty()) {
            out.println("There are no services to remove.");
            return;
        }
        String id = prompt("Id of the service to remove: ");
        try {
            Optional<Incident> closed = engine.removeService(id);
            out.println("Removed service '" + id + "'.");
            closed.ifPresent(i -> out.println("Its open incident " + i.getId() + " was closed because the service was removed."));
        } catch (InvalidServiceException e) {
            out.println("Could not remove service: " + e.getMessage());
        }
    }

    private void listServices() {
        List<Service> services = engine.getServices();
        if (services.isEmpty()) {
            out.println("No services yet. Use option 1 to add one.");
            return;
        }
        out.printf("%-10s %-16s %-16s %-9s %-6s %-7s %s%n",
                "ID", "NAME", "TYPE", "STATUS", "FAIL%", "FORCED", "STREAK");
        for (Service s : services) {
            out.printf("%-10s %-16s %-16s %-9s %-6s %-7s %d%n",
                    s.getId(), s.getName(), s.getClass().getSimpleName(), s.getStatus(),
                    Math.round(s.getFailureRate() * 100) + "%",
                    s.isForceFailure() ? "yes" : "no",
                    engine.getConsecutiveFailures(s.getId()));
        }
    }

    private void runOneRound() {
        if (engine.getServices().isEmpty()) {
            out.println("No services to check. Use option 1 to add one.");
            return;
        }
        engine.runRound(this::printCheck);
    }

    private void runManyRounds() {
        if (engine.getServices().isEmpty()) {
            out.println("No services to check. Use option 1 to add one.");
            return;
        }
        Integer rounds = promptInt("How many rounds (1-" + MAX_ROUNDS + ")? ");
        if (rounds == null) return;
        if (rounds < 1 || rounds > MAX_ROUNDS) {
            out.println("Please enter a number between 1 and " + MAX_ROUNDS + ".");
            return;
        }
        engine.runRounds(rounds, this::printCheck);
    }

    /** One line per check, e.g. "[Round 4] Payments API  FAIL  (HTTP 503)   failures: 3". */
    private void printCheck(int round, Service service, HealthResult result, int streak) {
        out.printf("[Round %d] %-14s %-9s %-44s failures: %d%n", round, service.getName(),
                label(result.getStatus()),
                result.isSuccess() ? "(" + result.getResponseTimeMs() + " ms)" : "(" + result.getErrorMessage() + ")",
                streak);
    }

    private static String label(ServiceStatus status) {
        switch (status) {
            case UP: return "OK";
            case DEGRADED: return "DEGRADED";
            default: return "FAIL";
        }
    }

    private void viewIncidents() {
        List<Incident> history = engine.getIncidentHistory();
        if (history.isEmpty()) {
            out.println("No incidents recorded.");
            return;
        }
        out.println(engine.getOpenIncidents().size() + " open, " + history.size() + " total:");
        for (Incident i : history) {
            String end = i.getEndTime().map(t -> t.format(TIME)).orElse("ongoing");
            out.printf("%-7s %-16s %-9s %s -> %-8s failures: %d%s%n", i.getId(), i.getServiceName(),
                    i.getStatus(), i.getStartTime().format(TIME), end, i.getFailureCount(),
                    i.getResolutionNote().isEmpty() ? "" : "  note: " + i.getResolutionNote());
        }
    }

    private void viewReports() {
        List<ServiceReport> reports = engine.getReports();
        if (reports.isEmpty()) {
            out.println("No services yet. Use option 1 to add one.");
            return;
        }
        out.println("Rank by: 1) uptime  2) average response time  3) name");
        String choice = prompt("Choice [1]: ");
        ReportOrder order = ReportOrder.UPTIME;
        switch (choice) {
            case "": case "1": break;
            case "2": order = ReportOrder.RESPONSE_TIME; break;
            case "3": order = ReportOrder.NAME; break;
            default: out.println("Unknown choice '" + choice + "', ranking by uptime.");
        }
        out.printf("%-10s %-16s %6s %8s %8s %9s %9s %5s %6s %9s%n",
                "ID", "NAME", "CHECKS", "UPTIME", "RECENT", "AVG ms", "SLOWEST", "SLOW", "FAILED", "INCIDENTS");
        for (ServiceReport r : ReportGenerator.sort(reports, order)) {
            out.printf("%-10s %-16s %6d %8s %8s %9s %9s %5d %6d %9d%n", r.serviceId(), r.serviceName(),
                    r.totalChecks(), percent(r.uptimePercent()), percent(r.recentUptimePercent()),
                    decimal(r.averageResponseMs()), whole(r.slowestResponseMs()),
                    r.degradedChecks(), r.failedChecks(), r.incidentCount());
        }
        out.println("RECENT = uptime over the last " + ReportGenerator.RECENT_WINDOW + " checks. "
                + "SLOW = degraded checks. Response times cover successful checks only.");
    }

    private static String percent(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%.1f%%", value.getAsDouble()) : "n/a";
    }

    private static String decimal(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%.1f", value.getAsDouble()) : "n/a";
    }

    private static String whole(OptionalInt value) {
        return value.isPresent() ? String.valueOf(value.getAsInt()) : "n/a";
    }

    private void configureThreshold() {
        out.println("Current failure threshold: " + engine.getFailureThreshold() + " consecutive failures");
        Integer value = promptInt("New threshold (1 or more): ");
        if (value == null) return;
        try {
            engine.setFailureThreshold(value);
            out.println("Threshold is now " + value + ".");
        } catch (IllegalArgumentException e) {
            out.println(e.getMessage());
        }
    }

    private void toggleForcedFailure() {
        List<Service> services = engine.getServices();
        if (services.isEmpty()) {
            out.println("No services yet. Use option 1 to add one.");
            return;
        }
        for (Service s : services) {
            out.println("  " + s.getId() + "  " + s.getName() + (s.isForceFailure() ? "  [FORCED DOWN]" : ""));
        }
        String id = prompt("Id of the service to toggle: ");
        try {
            boolean nowForced = !engine.getService(id).isForceFailure();
            engine.setForceFailure(id, nowForced);
            out.println(engine.getService(id).getName() + (nowForced
                    ? " will now fail on every check."
                    : " is back to normal behaviour."));
        } catch (InvalidServiceException e) {
            out.println(e.getMessage());
        }
    }

    // ---------------- saving ----------------

    /**
     * Saves through the engine. A failed save is reported but never stops the program:
     * the user can keep working and the next action tries again.
     */
    private boolean save() {
        try {
            engine.save();
            return true;
        } catch (StorageException e) {
            out.println("Warning: could not save data: " + e.getMessage());
            return false;
        }
    }

    // ---------------- input helpers ----------------

    private String prompt(String text) {
        out.print(text);
        out.flush();
        try {
            String line = in.readLine();
            if (line == null) throw new EndOfInput();
            return line.trim();
        } catch (IOException e) {
            throw new EndOfInput();
        }
    }

    /** Reads a whole number; prints a message and returns null if the input is not one. */
    private Integer promptInt(String text) {
        String line = prompt(text);
        try {
            return Integer.parseInt(line);
        } catch (NumberFormatException e) {
            out.println("'" + line + "' is not a whole number.");
            return null;
        }
    }
}
