package model;

import exception.InvalidServiceException;
import exception.MonitorException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ServiceFactoryTest {

    private final ServiceFactory factory = new ServiceFactory(new Random(1));

    @Test
    void createsTheRightSubclassForEachType() throws InvalidServiceException {
        assertInstanceOf(DatabaseService.class, factory.create("database", "d", "D"));
        assertInstanceOf(ApiService.class, factory.create("api", "a", "A"));
        assertInstanceOf(CacheService.class, factory.create("cache", "c", "C"));
    }

    @Test
    void dbIsAnAliasForDatabase_andInputIsTrimmedAndCaseInsensitive() throws InvalidServiceException {
        assertInstanceOf(DatabaseService.class, factory.create("db", "d", "D"));
        assertInstanceOf(ApiService.class, factory.create("  ApI ", "a", "A"));
    }

    @Test
    void unknownBlankOrNullTypeIsRejectedWithTheValidTypesListed() {
        for (String bad : new String[] {"fancy", " ", null}) {
            InvalidServiceException e = assertThrows(InvalidServiceException.class,
                    () -> factory.create(bad, "x", "X"));
            assertTrue(e.getMessage().contains("database, api, cache"), e.getMessage());
        }
    }

    @Test
    void customFailureRateIsApplied_andValidated() throws InvalidServiceException {
        assertEquals(0.5, factory.create("api", "a", "A", 0.5).getFailureRate());
        assertThrows(InvalidServiceException.class, () -> factory.create("api", "a", "A", 2.0));
    }

    @Test
    void badIdOrNameStillSurfacesAsInvalidServiceException() {
        assertThrows(InvalidServiceException.class, () -> factory.create("api", " ", "A"));
        assertThrows(InvalidServiceException.class, () -> factory.create("api", "a", ""));
    }

    private static List<Integer> latencies(Service s, int n) throws MonitorException {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(s.simulateRequest());
        return out;
    }

    @Test
    void sameMasterSeedGivesIdenticalBehaviour() throws MonitorException {
        Service a = new ServiceFactory(new Random(7)).create("cache", "c", "C", 0.0);
        Service b = new ServiceFactory(new Random(7)).create("cache", "c", "C", 0.0);
        assertEquals(latencies(a, 20), latencies(b, 20));
    }

    @Test
    void differentMasterSeedsGiveDifferentBehaviour() throws MonitorException {
        Service a = new ServiceFactory(new Random(7)).create("cache", "c", "C", 0.0);
        Service b = new ServiceFactory(new Random(8)).create("cache", "c", "C", 0.0);
        assertNotEquals(latencies(a, 20), latencies(b, 20));
    }

    @Test
    void aMistypedTypeDoesNotDisturbLaterRandomness() throws MonitorException {
        ServiceFactory withTypo = new ServiceFactory(new Random(7));
        assertThrows(InvalidServiceException.class, () -> withTypo.create("oops", "x", "X"));
        Service a = withTypo.create("cache", "c", "C", 0.0);
        Service b = new ServiceFactory(new Random(7)).create("cache", "c", "C", 0.0);
        assertEquals(latencies(a, 20), latencies(b, 20));
    }

    @Test
    void nullSeedSourceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ServiceFactory(null));
    }
}
