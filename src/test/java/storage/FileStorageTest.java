package storage;

import exception.StorageException;
import healthcheck.HealthResult;
import incident.Incident;
import model.IncidentStatus;
import model.ServiceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0, 123_456_789);

    private static StorageData sample() {
        return new StorageData(
                List.of(new ServiceRecord("api", "api-1", "Payments, \"EU\"", 0.15, ServiceStatus.DOWN),
                        new ServiceRecord("cache", "c-1", "Café ✓", 0.05, ServiceStatus.UP)),
                List.of(new HealthResult("api-1", T0, ServiceStatus.DOWN, 0, "HTTP 503, retry \"later\""),
                        new HealthResult("c-1", T0.plusSeconds(1), ServiceStatus.UP, 3, ""),
                        new HealthResult("c-1", T0.plusSeconds(2), ServiceStatus.DEGRADED, 640, "")),
                List.of(new Incident("INC-1", "api-1", "Payments, \"EU\"", T0, T0.plusSeconds(9),
                                IncidentStatus.RESOLVED, 4, "Service removed while incident was open"),
                        new Incident("INC-2", "api-1", "Payments, \"EU\"", T0.plusSeconds(20), null,
                                IncidentStatus.OPEN, 3, "")));
    }

    /** Compares by content: the domain classes deliberately do not override equals(). */
    private static void assertSameData(StorageData expected, StorageData actual) {
        assertEquals(expected.services(), actual.services());   // records have equals()
        assertEquals(expected.results().size(), actual.results().size());
        for (int i = 0; i < expected.results().size(); i++) {
            HealthResult e = expected.results().get(i), a = actual.results().get(i);
            assertEquals(e.getServiceId(), a.getServiceId());
            assertEquals(e.getTimestamp(), a.getTimestamp());
            assertEquals(e.getStatus(), a.getStatus());
            assertEquals(e.getResponseTimeMs(), a.getResponseTimeMs());
            assertEquals(e.getErrorMessage(), a.getErrorMessage());
        }
        assertEquals(expected.incidents().size(), actual.incidents().size());
        for (int i = 0; i < expected.incidents().size(); i++) {
            Incident e = expected.incidents().get(i), a = actual.incidents().get(i);
            assertEquals(e.getId(), a.getId());
            assertEquals(e.getServiceId(), a.getServiceId());
            assertEquals(e.getServiceName(), a.getServiceName());
            assertEquals(e.getStartTime(), a.getStartTime());
            assertEquals(e.getEndTime(), a.getEndTime());
            assertEquals(e.getStatus(), a.getStatus());
            assertEquals(e.getFailureCount(), a.getFailureCount());
            assertEquals(e.getResolutionNote(), a.getResolutionNote());
        }
    }

    // ---------- round trip ----------

    @Test
    void everythingSurvivesASaveAndLoad(@TempDir Path dir) throws StorageException {
        FileStorage storage = new FileStorage(dir);
        storage.save(sample());
        assertSameData(sample(), new FileStorage(dir).load());   // a NEW instance: proves it came from disk
    }

    @Test
    void loadingFromAnEmptyOrMissingFolderGivesEmptyData(@TempDir Path dir) throws StorageException {
        StorageData data = new FileStorage(dir.resolve("never-created")).load();
        assertTrue(data.services().isEmpty());
        assertTrue(data.results().isEmpty());
        assertTrue(data.incidents().isEmpty());
    }

    @Test
    void savingCreatesTheFolder_andLeavesNoTemporaryFiles(@TempDir Path dir) throws StorageException, IOException {
        Path target = dir.resolve("deep").resolve("data");
        new FileStorage(target).save(sample());
        try (Stream<Path> files = Files.list(target)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("incidents.csv", "results.csv", "services.csv"), names);
        }
    }

    @Test
    void aSecondSaveReplacesTheFirstInsteadOfAppending(@TempDir Path dir) throws StorageException {
        FileStorage storage = new FileStorage(dir);
        storage.save(sample());
        storage.save(StorageData.empty());
        StorageData loaded = storage.load();
        assertTrue(loaded.services().isEmpty());
        assertTrue(loaded.results().isEmpty());
        assertTrue(loaded.incidents().isEmpty());
    }

    @Test
    void blankLinesInTheFilesAreIgnored(@TempDir Path dir) throws IOException, StorageException {
        Files.writeString(dir.resolve("services.csv"),
                "type,id,name,failureRate,status\n\ncache,c-1,Cache,0.05,UP\n\n");
        assertEquals(1, new FileStorage(dir).load().services().size());
    }

    // ---------- corrupted data is reported, with file and line ----------

    private static void write(Path dir, String file, String content) throws IOException {
        Files.writeString(dir.resolve(file), content);
    }

    private static String loadError(Path dir) {
        return assertThrows(StorageException.class, () -> new FileStorage(dir).load()).getMessage();
    }

    @Test
    void aBadNumberNamesTheFileAndLine(@TempDir Path dir) throws IOException {
        write(dir, "results.csv", "serviceId,timestamp,status,responseTimeMs,errorMessage\n"
                + "a," + T0 + ",UP,10,\n"
                + "a," + T0 + ",UP,fast,\n");
        String message = loadError(dir);
        assertTrue(message.contains("results.csv"), message);
        assertTrue(message.contains("line 3"), message);
    }

    @Test
    void aWrongColumnCountIsRejected(@TempDir Path dir) throws IOException {
        write(dir, "services.csv", "type,id,name,failureRate,status\ncache,c-1,Cache\n");
        String message = loadError(dir);
        assertTrue(message.contains("expected 5 columns but found 3"), message);
    }

    @Test
    void anUnexpectedHeaderIsRejected(@TempDir Path dir) throws IOException {
        write(dir, "services.csv", "id,name\nc-1,Cache\n");
        assertTrue(loadError(dir).contains("unexpected header"));
    }

    @Test
    void anUnknownStatusIsRejected(@TempDir Path dir) throws IOException {
        write(dir, "services.csv", "type,id,name,failureRate,status\ncache,c-1,Cache,0.05,EXPLODED\n");
        assertTrue(loadError(dir).contains("services.csv line 2"));
    }

    @Test
    void aBadTimestampIsRejected(@TempDir Path dir) throws IOException {
        write(dir, "results.csv", "serviceId,timestamp,status,responseTimeMs,errorMessage\na,yesterday,UP,10,\n");
        assertTrue(loadError(dir).contains("results.csv line 2"));
    }

    @Test
    void anImpossibleIncidentIsRejected(@TempDir Path dir) throws IOException {
        // OPEN but with an end time: Incident's own validation refuses it, and storage reports where.
        write(dir, "incidents.csv", "id,serviceId,serviceName,startTime,endTime,status,failureCount,resolutionNote\n"
                + "INC-1,a,A," + T0 + "," + T0.plusSeconds(1) + ",OPEN,3,\n");
        assertTrue(loadError(dir).contains("incidents.csv line 2"));
    }

    @Test
    void aMalformedQuoteIsReportedAsAStorageException(@TempDir Path dir) throws IOException {
        write(dir, "services.csv", "type,id,name,failureRate,status\ncache,c-1,\"Cache,0.05,UP\n");
        assertTrue(loadError(dir).contains("services.csv line 2"));
    }

    // ---------- write failures ----------

    @Test
    void savingIntoAPlaceThatCannotBeCreatedThrowsStorageException(@TempDir Path dir) throws IOException {
        Path blocker = dir.resolve("a-file");
        Files.writeString(blocker, "x");
        assertThrows(StorageException.class, () -> new FileStorage(blocker.resolve("data")).save(sample()));
    }

    @Test
    void nullDirectoryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FileStorage(null));
    }
}
