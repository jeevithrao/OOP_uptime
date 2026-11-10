package storage;

import exception.StorageException;
import healthcheck.HealthResult;
import incident.Incident;
import model.IncidentStatus;
import model.ServiceStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves and loads everything as three CSV files in one folder. It contains no business rules: it
 * only converts between objects and text, and reports problems as StorageException.
 */
public class FileStorage implements Storage {

    static final String SERVICES_FILE = "services.csv";
    static final String RESULTS_FILE = "results.csv";
    static final String INCIDENTS_FILE = "incidents.csv";

    private static final String SERVICES_HEADER = "type,id,name,failureRate,status";
    private static final String RESULTS_HEADER = "serviceId,timestamp,status,responseTimeMs,errorMessage";
    private static final String INCIDENTS_HEADER =
            "id,serviceId,serviceName,startTime,endTime,status,failureCount,resolutionNote";

    private final Path directory;

    public FileStorage(Path directory) {
        if (directory == null) throw new IllegalArgumentException("directory must not be null");
        this.directory = directory;
    }

    // ---------------- save ----------------

    @Override
    public void save(StorageData data) throws StorageException {
        try {
            Files.createDirectories(directory);

            List<String> services = new ArrayList<>();
            for (ServiceRecord s : data.services()) {
                services.add(Csv.join(s.type(), s.id(), s.name(), String.valueOf(s.failureRate()), s.status().name()));
            }
            List<String> results = new ArrayList<>();
            for (HealthResult r : data.results()) {
                results.add(Csv.join(r.getServiceId(), r.getTimestamp().toString(), r.getStatus().name(),
                        String.valueOf(r.getResponseTimeMs()), r.getErrorMessage()));
            }
            List<String> incidents = new ArrayList<>();
            for (Incident i : data.incidents()) {
                incidents.add(Csv.join(i.getId(), i.getServiceId(), i.getServiceName(),
                        i.getStartTime().toString(), i.getEndTime().map(LocalDateTime::toString).orElse(""),
                        i.getStatus().name(), String.valueOf(i.getFailureCount()), i.getResolutionNote()));
            }

            writeAtomically(SERVICES_FILE, SERVICES_HEADER, services);
            writeAtomically(RESULTS_FILE, RESULTS_HEADER, results);
            writeAtomically(INCIDENTS_FILE, INCIDENTS_HEADER, incidents);
        } catch (IOException e) {
            throw new StorageException("Could not save data in " + directory + ": " + e.getMessage(), e);
        }
    }

    /**
     * Writes to a temporary file and then moves it over the real one, so a crash half-way through
     * a write can never leave a truncated data file: the old file stays intact until the new one is complete.
     */
    private void writeAtomically(String fileName, String header, List<String> lines) throws IOException {
        Path target = directory.resolve(fileName);
        Path temp = directory.resolve(fileName + ".tmp");
        List<String> all = new ArrayList<>(lines.size() + 1);
        all.add(header);
        all.addAll(lines);
        Files.write(temp, all, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);   // fallback on odd file systems
        }
    }

    // ---------------- load ----------------

    @Override
    public StorageData load() throws StorageException {
        List<ServiceRecord> services = new ArrayList<>();
        for (Row row : readRows(SERVICES_FILE, SERVICES_HEADER)) {
            parse(SERVICES_FILE, row, 5, f ->
                    services.add(new ServiceRecord(f.get(0), f.get(1), f.get(2),
                            Double.parseDouble(f.get(3)), ServiceStatus.valueOf(f.get(4)))));
        }
        List<HealthResult> results = new ArrayList<>();
        for (Row row : readRows(RESULTS_FILE, RESULTS_HEADER)) {
            parse(RESULTS_FILE, row, 5, f ->
                    results.add(new HealthResult(f.get(0), LocalDateTime.parse(f.get(1)),
                            ServiceStatus.valueOf(f.get(2)), Integer.parseInt(f.get(3)), f.get(4))));
        }
        List<Incident> incidents = new ArrayList<>();
        for (Row row : readRows(INCIDENTS_FILE, INCIDENTS_HEADER)) {
            parse(INCIDENTS_FILE, row, 8, f ->
                    incidents.add(new Incident(f.get(0), f.get(1), f.get(2), LocalDateTime.parse(f.get(3)),
                            f.get(4).isEmpty() ? null : LocalDateTime.parse(f.get(4)),
                            IncidentStatus.valueOf(f.get(5)), Integer.parseInt(f.get(6)), f.get(7))));
        }
        return new StorageData(services, results, incidents);
    }

    private record Row(int lineNumber, String text) { }

    /** Data lines of one file (blank lines skipped). A missing file is not an error: nothing was saved yet. */
    private List<Row> readRows(String fileName, String expectedHeader) throws StorageException {
        Path file = directory.resolve(fileName);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Could not read " + file + ": " + e.getMessage(), e);
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        String header = lines.get(0).replace("\uFEFF", "").trim();   // tolerate a BOM added by some editors
        if (!header.equals(expectedHeader)) {
            throw new StorageException(fileName + " has an unexpected header '" + header
                    + "' (expected '" + expectedHeader + "')");
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                rows.add(new Row(i + 1, lines.get(i)));   // +1: line numbers start at 1, the header is line 1
            }
        }
        return rows;
    }

    @FunctionalInterface
    private interface RowParser {
        void accept(List<String> fields);
    }

    /** Splits one row and runs the parser; any parsing problem becomes a StorageException naming file and line. */
    private void parse(String fileName, Row row, int expectedColumns, RowParser parser) throws StorageException {
        try {
            List<String> fields = Csv.split(row.text());
            if (fields.size() != expectedColumns) {
                throw new IllegalArgumentException("expected " + expectedColumns + " columns but found " + fields.size());
            }
            parser.accept(fields);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new StorageException(fileName + " line " + row.lineNumber() + ": " + e.getMessage(), e);
        }
    }
}
