# Service Health Monitor: Complete Code Walkthrough

*Every production source file shown in full and explained block by block. The code is copied directly from the repository (github.com/jeevithrao/OOP_uptime), so it is exactly what runs.*

## 0. How to use this document

For each of the 36 production files you get: **Role** (what it is for), **Depends on** (its imports, the quickest way to see what a class may know), then the **code cut into blocks**, each followed by an explanation of what it does, why it was written that way, and what to watch for. After the production code: the tests (section 10), build files (11) and a Java cheat sheet (12). Read section 12 first if any syntax is unfamiliar.

The sections run from classes that depend on nothing to classes that depend on everything, so whatever a class uses has already been explained.

| Section | Package | Purpose |
|---|---|---|
| 1 | `exception` | Five custom exceptions |
| 2 | `model` | Services, their behaviour, the factory |
| 3 | `healthcheck` | Probing and judging (Strategy) |
| 4 | `incident` | Detecting outages (Observer subject) |
| 5 | `notification` | Alert channels |
| 6 | `report` | Statistics |
| 7 | `storage` | CSV persistence and the log |
| 8 | `engine` | The coordinator |
| 9 | `console`, `Main` | Menu and entry point |
| 10 to 12 | tests, build files, Java cheat sheet | |

**Five ideas to hold while reading:**

1. A **`Service`** simulates a component and may fail. It never judges its own health.
2. A **`HealthChecker`** probes a service and returns a **`HealthResult`**. A failure is a *result*, never an exception that escapes.
3. The **`IncidentManager`** counts consecutive failures. At the threshold (3) it opens an **`Incident`** and tells its **listeners**.
4. **Notifiers** are listeners: they deliver alerts and know nothing about detection.
5. The **`MonitoringEngine`** connects everything; the **`ConsoleMenu`** is only a screen on top.

```
Main -> ConsoleMenu -> MonitoringEngine
                         |-> HealthChecker -> Service -> exception
                         |-> IncidentManager -> Incident, HealthResult, IncidentListener
                         |-> Storage (interface) <- FileStorage
                         |-> ReportGenerator
                         '-> AppLogger
Notifier (implements IncidentListener) -> Incident
```


## 1. Package `exception`

An *exception* says "something went wrong, and the caller must decide what to do". The project names each failure after what happened.

### 1.1 `exception/MonitorException.java`: The base of all five

Parent of every custom exception, so one `catch (MonitorException e)` handles any of them while a specific catch can still single one out.

**Depends on:** nothing (extends `java.lang.Exception`).

```java
package exception;

/**
 * Base class of every exception this application defines.
 * Checked on purpose: the compiler forces callers to deal with failures
 * (a service being down is an expected event, not a programming bug).
 */
public class MonitorException extends Exception {

    private static final long serialVersionUID = 1L;

    public MonitorException(String message) {
        super(message);
    }

    // Keeps the original cause (e.g. an IOException) so stack traces stay useful.
    public MonitorException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- `extends Exception` makes it **checked**: the compiler forces every caller to catch it or declare `throws`. Intended: a down service or unreadable file is *expected*. (Programmer mistakes use unchecked `IllegalArgumentException`.)
- `serialVersionUID` silences a `-Xlint:all` warning (exceptions are `Serializable`); the build has zero warnings.
- The second constructor keeps a `cause`: wrapping an `IOException` this way preserves the full stack trace.


### 1.2 `exception/ServiceDownException.java`: A service failed outright

Thrown by a simulated service when a request fails completely (connection refused, HTTP 503).

**Depends on:** the base class in its own package.

```java
package exception;

/** Thrown by a service when a simulated request fails outright (connection refused, HTTP 5xx...). */
public class ServiceDownException extends MonitorException {

    private static final long serialVersionUID = 1L;

    public ServiceDownException(String message) {
        super(message);
    }
}
```

The *type* carries the meaning. `BasicHealthChecker` catches exactly this and turns it into a DOWN result.


### 1.3 `exception/ServiceTimeoutException.java`: A service did not answer in time

Thrown by `ApiService` on a simulated timeout. A sibling (not subclass) of `ServiceDownException`, so the system must handle more than one failure type.

**Depends on:** the base class.

```java
package exception;

/** Thrown by a service when a simulated request does not answer in time. */
public class ServiceTimeoutException extends MonitorException {

    private static final long serialVersionUID = 1L;

    public ServiceTimeoutException(String message) {
        super(message);
    }
}
```

Same shape as the previous class; keeping them separate lets the code treat "failed" and "too slow to answer" differently later.


### 1.4 `exception/InvalidServiceException.java`: Bad input about a service

Thrown for invalid input (blank id, rate outside 0 to 1, unknown type, duplicate id) or an unknown service id. The menu shows its message to the user.

**Depends on:** the base class.

```java
package exception;

/** Thrown when a service is created with bad input (blank id, failure rate outside 0.0 to 1.0...). */
public class InvalidServiceException extends MonitorException {

    private static final long serialVersionUID = 1L;

    public InvalidServiceException(String message) {
        super(message);
    }
}
```

Checked on purpose: user input must be handled gracefully, never crash the program. Compare `IllegalArgumentException`, for programmer errors.


### 1.5 `exception/StorageException.java`: A file problem

Thrown when a file cannot be read, written or parsed; always names the file (and line for parse errors).

**Depends on:** the base class.

```java
package exception;

/** Thrown when saving or loading data fails. Wraps the underlying IOException as the cause. */
public class StorageException extends MonitorException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

The `cause` constructor wraps an `IOException`, so the rest of the program needs to know only one storage exception type.



## 2. Package `model`

The "world" being monitored: enums naming states, the abstract `Service`, three concrete services that behave differently, and a factory.

### 2.1 `model/ServiceStatus.java`: UP, DEGRADED or DOWN

The shared vocabulary for a service's health, used by results, services, reports and storage.

**Depends on:** nothing.

```java
package model;

/** Health state of a service. Set by the engine from health-check results, never by the service itself. */
public enum ServiceStatus {
    UP,
    DEGRADED,
    DOWN
}
```

An **enum** is a fixed list of values: `ServiceStatus.DOWN` instead of the text `"down"` turns a typo into a compile error. `DEGRADED` means "answered, but slower than 400 ms". The rule used everywhere: **DEGRADED counts as success**, because the service answered. The javadoc states a design rule: the engine sets the status, never the service itself.


### 2.2 `model/IncidentStatus.java`: OPEN or RESOLVED

The two states of an incident; kept beside `ServiceStatus` because several packages need both.

**Depends on:** nothing.

```java
package model;

/** Lifecycle of an incident. Kept beside ServiceStatus because both are plain shared vocabulary. */
public enum IncidentStatus {
    OPEN,
    RESOLVED
}
```

An incident is `OPEN` from the threshold failure until the first success, then `RESOLVED`. Storage writes these names to CSV and reads them back with `valueOf`.


### 2.3 `model/Service.java`: The abstract base of every monitored component

The centre of the inheritance and polymorphism marks. It defines *what a service is* and how it is asked to work (`simulateRequest()`), leaving *how each kind behaves* to subclasses.

**Depends on:** the three exceptions and `Random`. Nothing from `healthcheck`, `incident` or `engine`, so a service is independent of how it is monitored.

#### 2.3.1 Package and imports

```java
package model;

import exception.InvalidServiceException;
import exception.ServiceDownException;
import exception.ServiceTimeoutException;

import java.util.Random;
```

#### 2.3.2 The class and its state

```java
/**
 * A monitored component. Models behaviour only: it never judges whether it is healthy.
 * Subclasses define HOW they behave (latency, failure style) by implementing doSimulateRequest().
 */
public abstract class Service {

    private final String id;            // immutable: used as a map key everywhere
    private final String name;
    private double failureRate;         // probability 0.0 to 1.0 that a request fails
    private ServiceStatus status = ServiceStatus.UP;
    private boolean forceFailure = false; // demo mode switch
    private final Random random;        // injected, so demos and tests can be seeded
```

`abstract` means `new Service(...)` is impossible; only subclasses can be created (there is no generic service, only a database, API or cache).

Fields: `id` and `name` are **`final`** (the id is a map key everywhere, so it never changes); `failureRate` is a probability from 0.0 to 1.0; `status` starts `UP`; `forceFailure` is the **demo switch**; `random` is *injected* (**dependency injection**), so a seeded `Random(42)` makes demos and tests repeatable. All fields are `private`: **encapsulation**, the only way in is through methods that validate.

#### 2.3.3 Constructor: refuse bad input

```java
    protected Service(String id, String name, double failureRate, Random random)
            throws InvalidServiceException {
        if (id == null || id.isBlank()) {
            throw new InvalidServiceException("Service id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new InvalidServiceException("Service name must not be blank");
        }
        if (random == null) {
            throw new InvalidServiceException("Random source must not be null");
        }
        this.id = id.trim();
        this.name = name.trim();
        this.failureRate = validateRate(failureRate);
        this.random = random;
    }
```

`protected` so only subclasses call it (via `super`). It checks every argument before storing anything. `id == null || id.isBlank()` catches a missing id and a spaces-only one; the `||` short-circuits so `isBlank()` is never called on `null`. `trim()` removes stray spaces. `validateRate` checks the range. Result: **an invalid `Service` can never exist**, so no other class re-checks.

#### 2.3.4 The template method

```java
    /**
     * Template method: the fixed part (demo override) lives here, the varying part is delegated.
     * Final so no subclass can accidentally bypass forceFailure.
     *
     * @return simulated response time in milliseconds
     */
    public final int simulateRequest() throws ServiceDownException, ServiceTimeoutException {
        if (forceFailure) {
            throw new ServiceDownException(name + " is forced down (demo mode)");
        }
        return doSimulateRequest();
    }
```

The key method: the base class fixes the skeleton, subclasses fill in one step. Callers use `simulateRequest()`. It is **`final`**, so no subclass can skip the demo switch: if `forceFailure` is on it throws at once (that is the entire "force a failure" feature); otherwise it calls `doSimulateRequest()` and returns the **simulated response time in ms**. A failure is signalled by throwing.

#### 2.3.5 The step subclasses must write

```java
    /** Each subclass defines its own latency and failure behaviour. */
    protected abstract int doSimulateRequest() throws ServiceDownException, ServiceTimeoutException;
```

No body: every concrete subclass **must** implement it or not compile. This is the polymorphism hook: the engine calls `simulateRequest()` on a `Service` and Java runs the real subclass's version at run time.

#### 2.3.6 Shared random helpers

```java
    /** True with probability failureRate. */
    protected boolean shouldFail() {
        return random.nextDouble() < failureRate;
    }

    /** True with the given probability (used for "slow query" or "latency spike" chances). */
    protected boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /** Random int in [minInclusive, maxInclusive]. */
    protected int between(int minInclusive, int maxInclusive) {
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }

    protected boolean coinFlip() {
        return random.nextBoolean();
    }
```

Written once for all subclasses: `shouldFail()` is true with probability `failureRate` (`nextDouble()` is 0.0 to under 1.0); `chance(p)` does the same for any probability (slow query, latency spike); `between(min, max)` is a random int, both ends included (`nextInt(n)` gives 0 to n-1, so ask for `max-min+1` values and shift by `min`); `coinFlip()` is 50/50.

#### 2.3.7 Range check that also rejects NaN

```java
    private static double validateRate(double rate) throws InvalidServiceException {
        // Written as !(in range) so NaN is rejected too: every comparison with NaN is false.
        if (!(rate >= 0.0 && rate <= 1.0)) {
            throw new InvalidServiceException("Failure rate must be between 0.0 and 1.0, got " + rate);
        }
        return rate;
    }
```

Written `!(rate >= 0.0 && rate <= 1.0)` rather than `rate < 0.0 || rate > 1.0`: every comparison with `NaN` is false, so the second form would *accept* NaN. Used by both constructor and setter, so the rule lives in one place.

#### 2.3.8 Getters and setFailureRate

```java
    public String getId() { return id; }

    public String getName() { return name; }

    public double getFailureRate() { return failureRate; }

    public void setFailureRate(double failureRate) throws InvalidServiceException {
        this.failureRate = validateRate(failureRate);
    }

    public ServiceStatus getStatus() { return status; }
```

Plain accessors. No `setId`/`setName`: that is what immutable means here.

#### 2.3.9 Status, demo switch, toString

```java
    /** Called by the engine after a health check. Public because the engine lives in another package. */
    public void setStatus(ServiceStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        this.status = status;
    }

    public boolean isForceFailure() { return forceFailure; }

    public void setForceFailure(boolean forceFailure) { this.forceFailure = forceFailure; }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[id=" + id + ", name=" + name + ", status=" + status + "]";
    }
}
```

`setStatus` is public because the engine (another package) calls it after each check; it rejects `null`. `setForceFailure` is the demo switch. `toString()` prints e.g. `ApiService[id=api-1, name=Payments API, status=UP]`; `getClass().getSimpleName()` gives the real subclass name.


### 2.4 `model/DatabaseService.java`: Reliable, with occasional slow queries

Fails rarely (3%); about one request in ten is slow (300 to 800 ms instead of 20 to 60).

**Depends on:** `Service` (same package), one exception, `Random`.

#### 2.4.1 Package and imports

```java
package model;

import exception.InvalidServiceException;
import exception.ServiceDownException;

import java.util.Random;
```

#### 2.4.2 Class and tuning constants

```java
/** Reliable and fairly quick, but now and then a query runs slowly. Rarely fails. */
public class DatabaseService extends Service {

    private static final double DEFAULT_FAILURE_RATE = 0.03;
    private static final double SLOW_QUERY_CHANCE = 0.10;
    private static final int NORMAL_MIN_MS = 20, NORMAL_MAX_MS = 60;
    private static final int SLOW_MIN_MS = 300, SLOW_MAX_MS = 800;
```

`extends Service` is **inheritance** ("is a"). All tuning numbers are named `static final` constants, so a flakier database is a one-number change.

#### 2.4.3 Two constructors

```java
    public DatabaseService(String id, String name, Random random) throws InvalidServiceException {
        this(id, name, DEFAULT_FAILURE_RATE, random);
    }

    public DatabaseService(String id, String name, double failureRate, Random random)
            throws InvalidServiceException {
        super(id, name, failureRate, random);
    }
```

Default rate, or a custom one. The first calls the second with `this(...)`; the second calls `super(...)` where validation lives. No duplicated checks.

#### 2.4.4 This service's behaviour

```java
    @Override
    protected int doSimulateRequest() throws ServiceDownException {
        if (shouldFail()) {
            throw new ServiceDownException(getName() + ": connection refused");
        }
        return chance(SLOW_QUERY_CHANCE) ? between(SLOW_MIN_MS, SLOW_MAX_MS)
                                         : between(NORMAL_MIN_MS, NORMAL_MAX_MS);
    }
}
```

`@Override` makes a typo a compile error. If `shouldFail()`: throw `ServiceDownException` ("connection refused"). Otherwise return a time: `condition ? a : b` (the **ternary operator**) picks 300 to 800 ms when `chance(0.10)` is true, else 20 to 60. It never touches `forceFailure`: the final `simulateRequest()` already handled it.


### 2.5 `model/ApiService.java`: Flaky: spikes and two failure styles

15% failure rate; 20% of successful requests are spikes (500 to 1500 ms); failures are timeouts or HTTP 503. Best for demonstrating incidents and DEGRADED results.

**Depends on:** `Service` and both failure exceptions.

#### 2.5.1 Package and imports

```java
package model;

import exception.InvalidServiceException;
import exception.ServiceDownException;
import exception.ServiceTimeoutException;

import java.util.Random;
```

#### 2.5.2 Class and constants

```java
/** Flaky: frequent latency spikes and random failures, either 5xx errors or timeouts. */
public class ApiService extends Service {

    private static final double DEFAULT_FAILURE_RATE = 0.15;
    private static final double SPIKE_CHANCE = 0.20;
    private static final int NORMAL_MIN_MS = 50, NORMAL_MAX_MS = 200;
    private static final int SPIKE_MIN_MS = 500, SPIKE_MAX_MS = 1500;
    private static final int TIMEOUT_MS = 2000;
```

Spikes (500 to 1500) are above the 400 ms threshold so every spike becomes DEGRADED, while normal answers (50 to 200) stay UP.

#### 2.5.3 Two constructors

```java
    public ApiService(String id, String name, Random random) throws InvalidServiceException {
        this(id, name, DEFAULT_FAILURE_RATE, random);
    }

    public ApiService(String id, String name, double failureRate, Random random)
            throws InvalidServiceException {
        super(id, name, failureRate, random);
    }
```

Same pattern as `DatabaseService`.

#### 2.5.4 Two failure styles, then latency

```java
    @Override
    protected int doSimulateRequest() throws ServiceDownException, ServiceTimeoutException {
        if (shouldFail()) {
            // Two failure styles, so the system must handle more than one exception type.
            if (coinFlip()) {
                throw new ServiceTimeoutException(getName() + ": timed out after " + TIMEOUT_MS + " ms");
            }
            throw new ServiceDownException(getName() + ": HTTP 503");
        }
        return chance(SPIKE_CHANCE) ? between(SPIKE_MIN_MS, SPIKE_MAX_MS)
                                    : between(NORMAL_MIN_MS, NORMAL_MAX_MS);
    }
}
```

New idea: a failing request throws **one of two exceptions**, chosen by `coinFlip()` (`ServiceTimeoutException` or `ServiceDownException` "HTTP 503"); `throws` lists both. That is why the checker handles two types. If it does not fail, a spike (20%) or a normal time is returned.


### 2.6 `model/CacheService.java`: Very fast, sometimes drops out

Always 1 to 5 ms, but fails 5% of the time. With rate 0.0 it fails only when forced, which makes tests deterministic.

**Depends on:** `Service` and `ServiceDownException`.

#### 2.6.1 Package and imports

```java
package model;

import exception.InvalidServiceException;
import exception.ServiceDownException;

import java.util.Random;
```

#### 2.6.2 Class and constants

```java
/** Extremely fast. Never slow, but occasionally drops out completely. */
public class CacheService extends Service {

    private static final double DEFAULT_FAILURE_RATE = 0.05;
    private static final int MIN_MS = 1, MAX_MS = 5;
```

No slow case, so a cache never produces DEGRADED.

#### 2.6.3 Two constructors

```java
    public CacheService(String id, String name, Random random) throws InvalidServiceException {
        this(id, name, DEFAULT_FAILURE_RATE, random);
    }

    public CacheService(String id, String name, double failureRate, Random random)
            throws InvalidServiceException {
        super(id, name, failureRate, random);
    }
```

Same pattern; constructors are not inherited in Java, hence the repetition.

#### 2.6.4 Behaviour

```java
    @Override
    protected int doSimulateRequest() throws ServiceDownException {
        if (shouldFail()) {
            throw new ServiceDownException(getName() + ": cache node dropped");
        }
        return between(MIN_MS, MAX_MS);
    }
}
```

Fail ("cache node dropped") 5% of the time, else 1 to 5 ms. Three subclasses, three bodies for one method: **polymorphism**.


### 2.7 `model/ServiceFactory.java`: Builds the right Service from a type name

The **Factory** pattern: the menu says `"api"` and gets an `ApiService` without naming concrete classes. A new service type means changing only this class.

**Depends on:** `InvalidServiceException`, `List`, `Locale`, `Random`, and the three services.

#### 2.7.1 Package and imports

```java
package model;

import exception.InvalidServiceException;

import java.util.List;
import java.util.Locale;
import java.util.Random;
```

#### 2.7.2 Fields and constructors

```java
/**
 * Factory: builds the right Service subclass from a type name, so callers (menu, file loader) never
 * need to know the concrete classes.
 *
 * Every service gets its OWN Random, seeded from one master Random. Seed the master and the whole
 * session is reproducible (demo mode); services still do not disturb each other's sequences.
 */
public class ServiceFactory {

    /** Type names accepted by create(), for menus and error messages. */
    public static final List<String> TYPES = List.of("database", "api", "cache");

    private final Random seedSource;

    public ServiceFactory() {
        this(new Random());
    }

    public ServiceFactory(Random seedSource) {
        if (seedSource == null) throw new IllegalArgumentException("seedSource must not be null");
        this.seedSource = seedSource;
    }
```

`TYPES` lists accepted names (used in prompts and errors). `seedSource` is a **master Random**: every service gets its *own* Random seeded from it, so a seeded master (demo mode) makes the whole session repeatable while services do not disturb each other's sequences.

#### 2.7.3 The reverse mapping

```java
    /** The type name that create() accepts for this service; used when saving, so loading can rebuild it. */
    public static String typeNameOf(Service service) {
        if (service instanceof DatabaseService) return "database";
        if (service instanceof ApiService) return "api";
        if (service instanceof CacheService) return "cache";
        throw new IllegalArgumentException("Unsupported service class: " + service.getClass().getName());
    }
```

For saving: service in, type name out, so loading can rebuild it. `instanceof` tests the real class; an unknown subclass throws `IllegalArgumentException`.

#### 2.7.4 The two create methods

```java
    /** Creates a service with the default failure rate of its type. */
    public Service create(String type, String id, String name) throws InvalidServiceException {
        return build(type, id, name, null);
    }

    /** Creates a service with a custom failure rate (0.0 to 1.0). */
    public Service create(String type, String id, String name, double failureRate)
            throws InvalidServiceException {
        return build(type, id, name, failureRate);
    }
```

Default rate, or custom. Both call `build`; `Double` (capital) allows `null` meaning "use the default".

#### 2.7.5 The real work

```java
    private Service build(String type, String id, String name, Double failureRate)
            throws InvalidServiceException {
        String key = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        // Validate the type BEFORE drawing a seed, so a typo does not change later randomness.
        if (!key.equals("database") && !key.equals("db") && !key.equals("api") && !key.equals("cache")) {
            throw new InvalidServiceException("Unknown service type '" + type
                    + "'. Valid types: " + String.join(", ", TYPES));
        }
        Random random = new Random(seedSource.nextLong());
        switch (key) {
            case "api":
                return failureRate == null ? new ApiService(id, name, random)
                                           : new ApiService(id, name, failureRate, random);
            case "cache":
                return failureRate == null ? new CacheService(id, name, random)
                                           : new CacheService(id, name, failureRate, random);
            default: // "database" or "db"
                return failureRate == null ? new DatabaseService(id, name, random)
                                           : new DatabaseService(id, name, failureRate, random);
        }
    }
}
```

1. Normalise the type (null to empty, trim, lower-case with `Locale.ROOT`). 2. **Validate the type before drawing a seed**, so a typo does not consume a random number and change later services (a test pins this). 3. `new Random(seedSource.nextLong())` gives the service its own generator. 4. `switch` picks the class (`db` shares `database`'s branch); `failureRate == null` selects the default-rate constructor.



## 3. Package `healthcheck`

How we decide a service is healthy: the **Strategy** pattern (one interface, swappable implementations) plus an immutable result.

### 3.1 `healthcheck/HealthResult.java`: The record of one check

Everything about one check: service id, time, verdict, response time, error message. **Immutable**: reports and incidents are built from it later, so a history record must never change.

**Depends on:** `ServiceStatus` and `LocalDateTime`. Not `Service`: it carries only the id.

#### 3.1.1 Package and imports

```java
package healthcheck;

import model.ServiceStatus;

import java.time.LocalDateTime;
```

#### 3.1.2 Fields

```java
/**
 * The outcome of one health check. Immutable: once a check has happened its record must never
 * change, because reports and incident history are built from these objects.
 *
 * The verdict is stored once, as a ServiceStatus. "Success" is derived from it, so the two can
 * never contradict each other (no "success=true but status=DOWN" objects can exist).
 */
public final class HealthResult {

    private final String serviceId;
    private final LocalDateTime timestamp;
    private final ServiceStatus status;
    private final int responseTimeMs;   // 0 when the check failed: no response was received
    private final String errorMessage;  // empty when the check did not fail
```

`final` class, all fields `final`, no setters. A failed check records `responseTimeMs = 0` (no response, so reports must not average it). There is **no success field**: the verdict is stored once as `status` and success is derived, so "success but DOWN" is impossible.

#### 3.1.3 Constructor with validation

```java
    /**
     * Public so FileStorage (Phase 5) can rebuild results from saved lines, with the original timestamp.
     * Bad arguments here are programming errors, not user input, so they throw unchecked exceptions.
     */
    public HealthResult(String serviceId, LocalDateTime timestamp, ServiceStatus status,
                        int responseTimeMs, String errorMessage) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (responseTimeMs < 0) {
            throw new IllegalArgumentException("responseTimeMs must not be negative: " + responseTimeMs);
        }
        this.serviceId = serviceId;
        this.timestamp = timestamp;
        this.status = status;
        this.responseTimeMs = responseTimeMs;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }
```

Throws unchecked `IllegalArgumentException` for a blank id, missing timestamp/status or negative time (programming errors). `null` message becomes `""`. Public so storage can rebuild a result with its original timestamp.

#### 3.1.4 Three named factories

```java
    // Named factories read better at the call site than a 5-argument constructor.

    public static HealthResult up(String serviceId, int responseTimeMs) {
        return new HealthResult(serviceId, LocalDateTime.now(), ServiceStatus.UP, responseTimeMs, "");
    }

    public static HealthResult degraded(String serviceId, int responseTimeMs) {
        return new HealthResult(serviceId, LocalDateTime.now(), ServiceStatus.DEGRADED, responseTimeMs, "");
    }

    public static HealthResult down(String serviceId, String errorMessage) {
        return new HealthResult(serviceId, LocalDateTime.now(), ServiceStatus.DOWN, 0, errorMessage);
    }
```

`HealthResult.up("api-1", 120)` reads better than a 5-argument constructor. Each fills in the current time and status; `down` takes a message and records 0 ms. (**Static factory method** idiom.)

#### 3.1.5 The derived success rule

```java
    /** A check succeeded if the service answered at all. DEGRADED is slow, but it is not a failure. */
    public boolean isSuccess() {
        return status != ServiceStatus.DOWN;
    }

    public boolean isDegraded() {
        return status == ServiceStatus.DEGRADED;
    }
```

`return status != ServiceStatus.DOWN;` defines success for the **whole project**: DEGRADED counts. The incident logic, uptime formula and reports all use it, so they cannot disagree.

#### 3.1.6 Getters and toString

```java
    public String getServiceId() { return serviceId; }

    public LocalDateTime getTimestamp() { return timestamp; }

    public ServiceStatus getStatus() { return status; }

    public int getResponseTimeMs() { return responseTimeMs; }

    public String getErrorMessage() { return errorMessage; }

    @Override
    public String toString() {
        return isSuccess()
                ? serviceId + " " + status + " (" + responseTimeMs + " ms)"
                : serviceId + " " + status + " (" + errorMessage + ")";
    }
}
```

Read-only accessors. `toString()` gives `api-1 UP (120 ms)` or `api-1 DOWN (HTTP 503)`, the log-file format.


### 3.2 `healthcheck/HealthChecker.java`: The Strategy interface

The contract every checker follows; the engine talks only to this.

**Depends on:** `Service`, `HealthResult`.

```java
package healthcheck;

import model.Service;

/**
 * Strategy interface: "how do we judge a service?". The engine depends on this interface only,
 * so checking logic can be swapped without touching the engine.
 *
 * Contract: check() never throws for a service failure. A failure is a normal outcome and is
 * returned as a DOWN result, so callers need no try/catch.
 */
public interface HealthChecker {

    HealthResult check(Service service);
}
```

An **interface** says *what*, not *how*. Key rule: **`check()` never throws for a service failure**; it returns DOWN. The engine needs no try/catch and cannot forget one. Any class writing `implements HealthChecker` is a strategy.


### 3.3 `healthcheck/BasicHealthChecker.java`: UP or DOWN

Calls the service once: answer = UP (any speed), exception = DOWN.

**Depends on:** two exceptions, `Service`, `HealthResult`, `HealthChecker`.

```java
package healthcheck;

import exception.ServiceDownException;
import exception.ServiceTimeoutException;
import model.Service;

/** Simplest strategy: the service either answers (UP) or it does not (DOWN). Speed is ignored. */
public class BasicHealthChecker implements HealthChecker {

    @Override
    public HealthResult check(Service service) {
        try {
            int responseTimeMs = service.simulateRequest();
            return HealthResult.up(service.getId(), responseTimeMs);
        } catch (ServiceDownException | ServiceTimeoutException e) {
            // Listed explicitly rather than catching MonitorException: if a new failure type is
            // ever added to simulateRequest(), the compiler forces us to decide how to treat it.
            return HealthResult.down(service.getId(), e.getMessage());
        }
    }
}
```

1. `service.simulateRequest()` can throw (polymorphism picks the real behaviour). 2. Success: `HealthResult.up(id, ms)`. 3. `catch (ServiceDownException | ServiceTimeoutException e)` (a **multi-catch**) returns `HealthResult.down(...)`. It names the two types instead of the parent `MonitorException`: a new failure type added later makes this fail to compile, forcing a decision rather than being silently swallowed.


### 3.4 `healthcheck/LatencyHealthChecker.java`: Slow means DEGRADED

Like the basic checker, but a response slower than 400 ms is DEGRADED. The checker the app actually uses.

**Depends on:** `Service`, `HealthChecker`, `HealthResult`, `BasicHealthChecker`.

#### 3.4.1 Package and imports

```java
package healthcheck;

import model.Service;
```

#### 3.4.2 Fields and constructors

```java
/**
 * Like BasicHealthChecker, but a response slower than the threshold is DEGRADED instead of UP.
 * Reuses BasicHealthChecker for the call and failure handling, and only adds the speed rule.
 */
public class LatencyHealthChecker implements HealthChecker {

    public static final int DEFAULT_SLOW_THRESHOLD_MS = 400;

    private final HealthChecker basic = new BasicHealthChecker();
    private final int slowThresholdMs;

    public LatencyHealthChecker() {
        this(DEFAULT_SLOW_THRESHOLD_MS);
    }

    public LatencyHealthChecker(int slowThresholdMs) {
        if (slowThresholdMs <= 0) {
            throw new IllegalArgumentException("slowThresholdMs must be positive: " + slowThresholdMs);
        }
        this.slowThresholdMs = slowThresholdMs;
    }
```

The `basic` field is **composition**: it *has a* basic checker and reuses it instead of copying the call-and-catch code. A non-positive threshold throws.

#### 3.4.3 The speed rule

```java
    @Override
    public HealthResult check(Service service) {
        HealthResult result = basic.check(service);
        // Only a successful answer can be "slow"; a DOWN result is passed through untouched.
        // Strictly greater: a response exactly at the threshold is still acceptable.
        if (result.isSuccess() && result.getResponseTimeMs() > slowThresholdMs) {
            return HealthResult.degraded(service.getId(), result.getResponseTimeMs());
        }
        return result;
    }
```

Ask the basic checker; only a *successful* answer can be slow; the test is **strictly greater**: exactly 400 ms is UP, 401 is DEGRADED (a test pins it). 400 separates the API's normal answers (50 to 200) from its spikes (500 to 1500).

#### 3.4.4 Getter

```java
    public int getSlowThresholdMs() { return slowThresholdMs; }
}
```

Lets tests read the threshold.


### 3.5 `healthcheck/HealthCheckerFactory.java`: Creates a checker from a name

The second **Factory**: `"basic"` or `"latency"` in, a `HealthChecker` out.

**Depends on:** `Locale` and the two checkers.

```java
package healthcheck;

import java.util.Locale;

/**
 * Factory: creates the right HealthChecker from a type name ("basic", "latency"), so callers
 * (menu, config file) never need to know the concrete classes.
 * Static and non-instantiable: it holds no state, so there is nothing to create an object for.
 */
public final class HealthCheckerFactory {

    private HealthCheckerFactory() { }

    public static HealthChecker create(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Checker type must not be blank");
        }
        switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "basic":
                return new BasicHealthChecker();
            case "latency":
                return new LatencyHealthChecker();
            default:
                throw new IllegalArgumentException(
                        "Unknown checker type '" + type + "'. Valid types: basic, latency");
        }
    }
}
```

`final` plus a **private constructor**: no state, nothing to instantiate; everything is `static`. Input is trimmed and lower-cased. An unknown name throws unchecked `IllegalArgumentException` (the name comes from code, so a wrong one is a programming error).



## 4. Package `incident`

The core of the project: turning results into incidents and announcing them with the **Observer** pattern.

### 4.1 `incident/Incident.java`: One outage of one service

One outage from detection to recovery: id (`INC-1`), service, start/end, status, failure count, optional note.

**Depends on:** `IncidentStatus`, `LocalDateTime`, `Optional`. Not `Service`: it stores the id and name as strings.

#### 4.1.1 Package and imports

```java
package incident;

import model.IncidentStatus;

import java.time.LocalDateTime;
import java.util.Optional;
```

#### 4.1.2 Fields

```java
/**
 * One outage of one service, from detection to recovery.
 *
 * Stores the service's id and name as plain strings instead of a Service reference: the incident
 * is a historical record, so it must stay valid (and easy to save as CSV) even after the service
 * is removed or changed.
 *
 * Only IncidentManager may change an incident. The mutators are package-private, so listeners in
 * other packages (notifiers) receive it as read-only.
 */
public class Incident {

    private final String id;
    private final String serviceId;
    private final String serviceName;
    private final LocalDateTime startTime;
    private LocalDateTime endTime;      // null while OPEN
    private IncidentStatus status;
    private int failureCount;           // failed checks during this incident, including the ones that opened it
    private String resolutionNote;      // why it was closed when that was not a normal recovery; "" otherwise
```

Most fields are `final`; only `endTime`, `status`, `failureCount`, `resolutionNote` change, because an incident really does change over its life. Strings, not a `Service` reference: an incident is a **historical record** that must stay valid after the service is removed, saves easily as CSV, and keeps this package independent of `model.Service`.

#### 4.1.3 Opening constructor (package-private)

```java
    /** Opens a new incident. Package-private: only IncidentManager creates live incidents. */
    Incident(String id, String serviceId, String serviceName, LocalDateTime startTime, int failureCount) {
        this(id, serviceId, serviceName, startTime, null, IncidentStatus.OPEN, failureCount);
    }
```

No `public`: only `IncidentManager` can create a *live* incident. Delegates to the full constructor (no end time, OPEN, no note).

#### 4.1.4 Convenience constructor

```java
    /** Same as the full constructor, with no resolution note. */
    public Incident(String id, String serviceId, String serviceName, LocalDateTime startTime,
                    LocalDateTime endTime, IncidentStatus status, int failureCount) {
        this(id, serviceId, serviceName, startTime, endTime, status, failureCount, "");
    }
```

Public, without the note, for code that does not care about notes.

#### 4.1.5 Full constructor: refuses impossible states

```java
    /** Full constructor, public so storage can rebuild saved incidents (Phase 5). */
    public Incident(String id, String serviceId, String serviceName, LocalDateTime startTime,
                    LocalDateTime endTime, IncidentStatus status, int failureCount, String resolutionNote) {
        requireText(id, "id");
        requireText(serviceId, "serviceId");
        requireText(serviceName, "serviceName");
        if (startTime == null) throw new IllegalArgumentException("startTime must not be null");
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (failureCount < 1) throw new IllegalArgumentException("failureCount must be at least 1: " + failureCount);
        // Reject impossible combinations so a corrupted save file cannot create a broken incident.
        String note = resolutionNote == null ? "" : resolutionNote;
        if (status == IncidentStatus.OPEN && endTime != null) {
            throw new IllegalArgumentException("An OPEN incident cannot have an end time");
        }
        if (status == IncidentStatus.OPEN && !note.isEmpty()) {
            throw new IllegalArgumentException("An OPEN incident cannot have a resolution note");
        }
        if (status == IncidentStatus.RESOLVED) {
            if (endTime == null) throw new IllegalArgumentException("A RESOLVED incident needs an end time");
            if (endTime.isBefore(startTime)) throw new IllegalArgumentException("endTime is before startTime");
        }
        this.id = id;
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
        this.failureCount = failureCount;
        this.resolutionNote = note;
    }
```

Storage uses it, so it trusts nothing. It rejects blank ids, null time/status, failure count below 1, and **impossible combinations**: OPEN with an end time or note, RESOLVED without an end time, end before start. A corrupt save file cannot create a broken object; `FileStorage` turns the exception into a message naming the line.

#### 4.1.6 Validation helper

```java
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
```

Avoids repeating "not null, not blank" three times.

#### 4.1.7 Controlled changes (package-private)

```java
    /** Another failed check while the incident is already open. */
    void recordFailure() {
        requireOpen();
        failureCount++;
    }

    /** Closes the incident at the time of the first successful check. */
    void resolve(LocalDateTime endTime) {
        resolve(endTime, "");
    }

    /** Closes the incident and records why, for closures that are not a normal recovery. */
    void resolve(LocalDateTime endTime, String note) {
        requireOpen();
        if (endTime == null) throw new IllegalArgumentException("endTime must not be null");
        if (endTime.isBefore(startTime)) throw new IllegalArgumentException("endTime is before startTime");
        this.endTime = endTime;
        this.status = IncidentStatus.RESOLVED;
        this.resolutionNote = note == null ? "" : note;
    }

    private void requireOpen() {
        if (status != IncidentStatus.OPEN) {
            throw new IllegalStateException("Incident " + id + " is already " + status);
        }
    }
```

The **only** ways an incident changes, all package-private: `recordFailure()` (one more failure while open), `resolve(end[, note])` (close it; the note explains non-recovery closures), `requireOpen()` (changing a resolved incident throws `IllegalStateException`). Notifiers in another package can read but never alter an incident.

#### 4.1.8 Getters and toString

```java
    public String getId() { return id; }

    public String getServiceId() { return serviceId; }

    public String getServiceName() { return serviceName; }

    public LocalDateTime getStartTime() { return startTime; }

    /** Empty while the incident is still open. Optional makes callers handle that case. */
    public Optional<LocalDateTime> getEndTime() { return Optional.ofNullable(endTime); }

    public IncidentStatus getStatus() { return status; }

    public boolean isOpen() { return status == IncidentStatus.OPEN; }

    public int getFailureCount() { return failureCount; }

    /** Empty for a normal recovery; explains the closure otherwise (e.g. the service was removed). */
    public String getResolutionNote() { return resolutionNote; }

    @Override
    public String toString() {
        return "Incident[" + id + ", " + serviceName + ", " + status + ", failures=" + failureCount + "]";
    }
}
```

`getEndTime()` returns an **`Optional`** (a box that holds a value or nothing), forcing callers to handle "open incidents have no end time" instead of risking a `NullPointerException`.


### 4.2 `incident/IncidentListener.java`: The Observer interface

What anything must implement to hear about incidents.

**Depends on:** `Incident`.

```java
package incident;

/**
 * Observer interface. Anything that wants to react to incidents implements this and registers with
 * IncidentManager. The manager knows only this interface, never the concrete listeners.
 */
public interface IncidentListener {

    void onIncidentOpened(Incident incident);

    void onIncidentResolved(Incident incident);
}
```

Two methods: opened, resolved. Console, file, email and the engine's logger implement it. The manager knows only this interface, so a new channel is one new class plus one `addListener` call. **Observer**: the subject announces, observers react, neither knows the other's details.


### 4.3 `incident/IncidentManager.java`: The failure-counting rule and the Subject

Receives health results, counts consecutive failures per service, opens an incident at the threshold, resolves it on the first success, and tells listeners. It sends no alerts and makes no checks.

**Depends on:** `HealthResult` (input), collection classes, `LocalDateTime`, `Optional`. Not `Service`, `Notifier` or the engine.

#### 4.3.1 Package and imports

```java
package incident;

import healthcheck.HealthResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
```

#### 4.3.2 State

```java
/**
 * Decides when an outage is real and tells listeners about it (Subject in the Observer pattern).
 *
 * Rule: a service that fails `threshold` checks IN A ROW opens an incident; the first successful
 * check resolves it. DEGRADED counts as success (see HealthResult.isSuccess()).
 *
 * It knows nothing about alert channels (only the IncidentListener interface) and nothing about
 * how checks are made (it receives finished HealthResults).
 */
public class IncidentManager {

    public static final int DEFAULT_THRESHOLD = 3;

    // Consecutive failures per service id. A service with no failing streak has no entry.
    private final Map<String, Integer> consecutiveFailures = new HashMap<>();
    // The currently open incident per service id (at most one per service).
    private final Map<String, Incident> openIncidents = new HashMap<>();
    // Every incident ever opened, in order. ArrayList keeps insertion order for the history view.
    private final List<Incident> history = new ArrayList<>();
    private final List<IncidentListener> listeners = new ArrayList<>();

    private int threshold;
    private int nextIncidentNumber = 1;
```

`consecutiveFailures`: id to streak (no entry when not failing, so it stays small). `openIncidents`: at most one open incident per service (prevents duplicate alerts). `history`: every incident, in order. `listeners`: the observers. Plus `threshold` (default 3) and `nextIncidentNumber`.

#### 4.3.3 Constructors

```java
    public IncidentManager() {
        this(DEFAULT_THRESHOLD);
    }

    public IncidentManager(int threshold) {
        this.threshold = validateThreshold(threshold);
    }
```

Default threshold 3; the other validates through `validateThreshold`, so "at least 1" lives in one place.

#### 4.3.4 Overload without a name

```java
    /** Convenience overload: the service id doubles as the display name. */
    public void recordResult(HealthResult result) {
        recordResult(result, null);
    }
```

Calls the main method with `null` name (meaning "use the id").

#### 4.3.5 recordResult: entry

```java
    /**
     * Feed one finished health check into the incident logic.
     *
     * @param serviceName display name for alerts. HealthResult only carries the id, and passing the
     *                    name in keeps this class independent of the Service class. Null means "use the id".
     */
    public void recordResult(HealthResult result, String serviceName) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        String serviceId = result.getServiceId();
        String displayName = (serviceName == null || serviceName.isBlank()) ? serviceId : serviceName;
```

The main method. The name is a parameter because `HealthResult` has only the id; passing it keeps this class independent of `Service`. Rejects `null`, then picks `displayName`.

#### 4.3.6 Branch 1: the check succeeded

```java
        if (result.isSuccess()) {
            consecutiveFailures.remove(serviceId);               // a success breaks the streak
            Incident open = openIncidents.remove(serviceId);
            if (open != null) {
                open.resolve(result.getTimestamp());
                notifyResolved(open);                            // state is updated BEFORE notifying
            }
            return;
        }
```

Remember `isSuccess()` is true for UP **and** DEGRADED. (1) Remove the streak entry. (2) `openIncidents.remove(...)` returns the open incident or `null`. (3) If one existed: `resolve(...)` with the check's timestamp, then `notifyResolved`. (4) `return`. State is updated **before** notifying, so a listener always sees the final state.

#### 4.3.7 Branch 2: the check failed

```java
        int streak = consecutiveFailures.merge(serviceId, 1, Integer::sum);
        Incident open = openIncidents.get(serviceId);
        if (open != null) {
            open.recordFailure();                                // already open: just count it
        } else if (streak >= threshold) {
            // >= rather than ==, so lowering the threshold while a streak is running still triggers.
            Incident incident = new Incident("INC-" + nextIncidentNumber++, serviceId,
                    displayName, result.getTimestamp(), streak);
            openIncidents.put(serviceId, incident);
            history.add(incident);
            notifyOpened(incident);
        }
    }
```

`merge(id, 1, Integer::sum)` is "add one" (store 1 if absent, else add; `Integer::sum` is a **method reference**) and returns the new total. If an incident is **already open**, only `recordFailure()`: one outage gives exactly one OPENED and one RESOLVED alert (no alert storm). Otherwise, if `streak >= threshold`, create `INC-n`, store it, add to history, `notifyOpened`. It is `>=`, not `==`: lowering the threshold mid-streak could skip over `==`.

#### 4.3.8 Removing a service

```java
    /**
     * Called when a service is removed: clears its failure streak and closes any open incident
     * with the given reason. Listeners are NOT notified: the service did not recover, so a
     * "RECOVERED" alert would be false.
     *
     * @return the incident that was closed, if there was one
     */
    public Optional<Incident> forgetService(String serviceId, LocalDateTime when, String reason) {
        consecutiveFailures.remove(serviceId);
        Incident open = openIncidents.remove(serviceId);
        if (open == null) {
            return Optional.empty();
        }
        // Clamp so a clock quirk can never produce an end time before the start time.
        LocalDateTime end = when.isBefore(open.getStartTime()) ? open.getStartTime() : when;
        open.resolve(end, reason);
        return Optional.of(open);
    }
```

Clears the streak and closes any open incident with the reason. Listeners are **not** told (the service did not recover; "RECOVERED" would be false). End time is clamped so it can never precede the start. Returns the closed incident.

#### 4.3.9 Loading saved incidents

```java
    /**
     * Loads saved incidents into a fresh manager (used at startup). Open incidents become active
     * again, so the next success resolves them, and numbering continues after the highest saved id.
     * Validates everything first and changes nothing if the data is inconsistent.
     * Listeners are not notified: these events already happened in an earlier session.
     */
    public void restoreHistory(List<Incident> saved) {
        if (!history.isEmpty() || !openIncidents.isEmpty()) {
            throw new IllegalStateException("History can only be restored into an empty manager");
        }
        Set<String> seenIds = new HashSet<>();
        Map<String, Incident> opens = new HashMap<>();
        int highest = 0;
        for (Incident incident : saved) {
            if (!seenIds.add(incident.getId())) {
                throw new IllegalArgumentException("Duplicate incident id " + incident.getId());
            }
            if (incident.isOpen() && opens.put(incident.getServiceId(), incident) != null) {
                throw new IllegalArgumentException("More than one open incident for service " + incident.getServiceId());
            }
            highest = Math.max(highest, numberOf(incident.getId()));
        }
        // Everything is valid: apply it.
        history.addAll(saved);
        openIncidents.putAll(opens);
        for (Incident open : opens.values()) {
            consecutiveFailures.put(open.getServiceId(), open.getFailureCount());   // the outage is still counting
        }
        nextIncidentNumber = Math.max(nextIncidentNumber, highest + 1);
    }
```

**Validate first, apply second**: refuse unless empty; check for duplicate ids and two open incidents on one service while tracking the highest `INC-n`; any problem throws *before anything changes*. Then add to history, re-activate open incidents (streak = failure count) and continue numbering after the highest id. Listeners are not notified (old events).

#### 4.3.10 Number from "INC-12"

```java
    /** "INC-12" gives 12; ids in any other shape do not affect numbering. */
    private static int numberOf(String id) {
        if (id.startsWith("INC-")) {
            try {
                return Integer.parseInt(id.substring(4));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
```

12 for `"INC-12"`; 0 for other shapes, which then do not affect numbering.

#### 4.3.11 Registering observers

```java
    public void addListener(IncidentListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        listeners.add(listener);
    }

    public void removeListener(IncidentListener listener) {
        listeners.remove(listener);
    }
```

Rejects `null`, appends; `removeListener` removes. Anything implementing `IncidentListener` may register.

#### 4.3.12 Notifying safely

```java
    private void notifyOpened(Incident incident) {
        // Iterate over a copy so a listener may register or unregister during the callback.
        for (IncidentListener l : new ArrayList<>(listeners)) {
            try {
                l.onIncidentOpened(incident);
            } catch (RuntimeException e) {
                reportListenerFailure(l, e);
            }
        }
    }

    private void notifyResolved(Incident incident) {
        for (IncidentListener l : new ArrayList<>(listeners)) {
            try {
                l.onIncidentResolved(incident);
            } catch (RuntimeException e) {
                reportListenerFailure(l, e);
            }
        }
    }

    /** One broken alert channel (e.g. unwritable file) must not stop monitoring or the other channels. */
    private void reportListenerFailure(IncidentListener listener, RuntimeException e) {
        System.err.println("Listener " + listener.getClass().getSimpleName() + " failed: " + e.getMessage());
    }
```

Two protections: the loop runs over a **copy** (`new ArrayList<>(listeners)`), so a listener may unregister during its callback; and **each call has its own `try/catch (RuntimeException)`**, so one broken channel cannot stop monitoring or starve the others (a test covers it).

#### 4.3.13 Configuration and read-only queries

```java
    public int getThreshold() { return threshold; }

    public void setThreshold(int threshold) {
        this.threshold = validateThreshold(threshold);
    }

    private static int validateThreshold(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("Threshold must be at least 1, got " + threshold);
        }
        return threshold;
    }

    public int getConsecutiveFailures(String serviceId) {
        return consecutiveFailures.getOrDefault(serviceId, 0);
    }

    public boolean hasOpenIncident(String serviceId) {
        return openIncidents.containsKey(serviceId);
    }

    public List<Incident> getOpenIncidents() {
        return List.copyOf(openIncidents.values());
    }

    /** Read-only view: callers can look at the history but cannot change it. */
    public List<Incident> getHistory() {
        return Collections.unmodifiableList(history);
    }
}
```

`getHistory()` returns `Collections.unmodifiableList(...)`; `getOpenIncidents()` returns `List.copyOf(...)`. Handing out views is **defensive copying**: outside code cannot break internal state.



## 5. Package `notification`

Notifiers are the **observers**: told about incidents, each delivers an alert its own way, knowing nothing about detection.

### 5.1 `notification/Notifier.java`: The shared base of all channels

Abstract class implementing `IncidentListener`. It writes the alert text **once** so every channel agrees, and leaves one question to subclasses: how is it delivered? (A second **Template Method**.)

**Depends on:** `Incident`, `IncidentListener`, `DateTimeFormatter`. Only the incident data, not the manager's logic.

#### 5.1.1 Package and imports

```java
package notification;

import incident.Incident;
import incident.IncidentListener;

import java.time.format.DateTimeFormatter;
```

#### 5.1.2 Class and time format

```java
/**
 * Base class of every alert channel. It is an IncidentListener, so a notifier can be registered with
 * IncidentManager directly, and it never knows how failures are detected.
 *
 * Template method again: this class decides WHAT the alert says (written once, so all channels
 * agree); each subclass decides only HOW it is delivered.
 */
public abstract class Notifier implements IncidentListener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
```

`implements IncidentListener` lets a notifier register with the manager. `TIME` formats the start as `HH:mm:ss`.

#### 5.1.3 The two steps subclasses supply

```java
    /** Short channel label used in the alert prefix, e.g. "console". */
    protected abstract String channelName();

    /** Send one finished, formatted message through this channel. */
    protected abstract void deliver(String message);
```

`channelName()` is the label (`console`, `file`, `email`); `deliver(message)` sends a finished message. These are all a new channel writes.

#### 5.1.4 The "opened" alert

```java
    // final: subclasses customise delivery, not the alert logic.
    @Override
    public final void onIncidentOpened(Incident incident) {
        deliver(prefix() + incident.getServiceName() + " is DOWN (" + incident.getId() + ", "
                + incident.getFailureCount() + " consecutive failures, since "
                + TIME.format(incident.getStartTime()) + ")");
    }
```

Builds `[ALERT -> console] Payments API is DOWN (INC-1, 3 consecutive failures, since 10:42:15)` and calls `deliver`. **`final`**: channels customise delivery, not wording.

#### 5.1.5 The "recovered" alert

```java
    @Override
    public final void onIncidentResolved(Incident incident) {
        deliver(prefix() + incident.getServiceName() + " has RECOVERED (" + incident.getId()
                + ", downtime: " + incident.getFailureCount() + " rounds)");
    }
```

`... has RECOVERED (INC-1, downtime: 5 rounds)`; downtime is the incident's failure count.

#### 5.1.6 Alert prefix

```java
    private String prefix() {
        return "[ALERT -> " + channelName() + "] ";
    }
}
```

Builds `[ALERT -> console] ` from the channel name.


### 5.2 `notification/ConsoleNotifier.java`: Prints on screen

The simplest channel.

**Depends on:** `PrintStream` (what `System.out` is) and `Notifier`.

#### 5.2.1 Package and imports

```java
package notification;

import java.io.PrintStream;
```

#### 5.2.2 Field and constructors

```java
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
```

The stream is **injected**: default `System.out`, but a test passes one that captures text.

#### 5.2.3 Channel name and delivery

```java
    @Override
    protected String channelName() { return "console"; }

    @Override
    protected void deliver(String message) {
        out.println(message);
    }
}
```

Returns `"console"`; `deliver` is `out.println(message)`. That is the whole channel.


### 5.3 `notification/FileNotifier.java`: Appends alerts to a file

One timestamped line per alert in a file (`data/alerts.log` in the app), creating the folder if needed.

**Depends on:** `java.nio.file` classes, `UncheckedIOException`, `LocalDateTime`.

#### 5.3.1 Package and imports

```java
package notification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
```

#### 5.3.2 Field and constructor

```java
/** Appends one timestamped line per alert to a file, creating the file and its folder if needed. */
public class FileNotifier extends Notifier {

    private final Path file;

    public FileNotifier(Path file) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        this.file = file;
    }
```

Stores the target `Path`; `null` rejected.

#### 5.3.3 Channel name

```java
    @Override
    protected String channelName() { return "file"; }
```

Returns `"file"`.

#### 5.3.4 Writing the line

```java
    @Override
    protected void deliver(String message) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, LocalDateTime.now() + " " + message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Listener methods cannot throw checked exceptions. IncidentManager catches runtime
            // exceptions per listener, so a broken file never blocks the other channels.
            throw new UncheckedIOException("Could not write alert to " + file, e);
        }
    }
}
```

(1) Create the parent folder if missing. (2) `Files.writeString(..., CREATE, APPEND)`: create if absent, add to the end instead of overwriting. (3) An `IOException` is re-thrown as **`UncheckedIOException`**, because listener methods cannot declare checked exceptions; the manager's per-listener catch contains it so a broken file never blocks other channels.


### 5.4 `notification/EmailNotifier.java`: A simulated email

Nothing is sent (real networks are out of scope); it prints what an email would contain, a third distinct channel.

**Depends on:** `PrintStream`, `Notifier`.

#### 5.4.1 Package and imports

```java
package notification;

import java.io.PrintStream;
```

#### 5.4.2 Fields and constructors

```java
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
```

Holds the recipient and an injected stream; a blank recipient is rejected.

#### 5.4.3 Simulated sending

```java
    @Override
    protected String channelName() { return "email"; }

    @Override
    protected void deliver(String message) {
        out.println("[EMAIL sent to " + recipient + "] " + message);
    }
}
```

Prints `[EMAIL sent to ops@example.com] ...`. Real SMTP could replace this method without touching any other class.



## 6. Package `report`

Pure calculation: plain lists in, numbers out. It never changes data and knows nothing about the engine, files or screen.

### 6.1 `report/ReportOrder.java`: How to rank

The three ranking choices of the reports menu.

**Depends on:** nothing.

```java
package report;

/** How to rank services in a report. */
public enum ReportOrder {
    UPTIME,          // best uptime first
    RESPONSE_TIME,   // fastest average response first
    NAME             // alphabetical
}
```

`UPTIME` (best first), `RESPONSE_TIME` (fastest average first), `NAME`. `ReportGenerator.sort` switches on it.


### 6.2 `report/ServiceReport.java`: Statistics of one service

Counts, uptime, average and slowest response, incident count for one service. A Java **record**.

**Depends on:** `OptionalDouble`, `OptionalInt`.

```java
package report;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Statistics for one service. A Java record: a plain, immutable bundle of values with no behaviour
 * worth hiding, so a record is shorter and safer than a hand-written class.
 *
 * Values that may not exist yet (no checks run, no successful response) are Optionals instead of
 * a fake 0, so "0% uptime" can never be confused with "no data".
 */
public record ServiceReport(
        String serviceId,
        String serviceName,
        int totalChecks,
        int upChecks,
        int degradedChecks,
        int failedChecks,
        OptionalDouble uptimePercent,          // successful checks / total checks * 100
        OptionalDouble recentUptimePercent,    // same, over the last RECENT_WINDOW checks only
        OptionalDouble averageResponseMs,      // over successful checks only
        OptionalInt slowestResponseMs,         // over successful checks only
        int incidentCount,
        int downtimeRounds) {                  // sum of failed checks across this service's incidents

    /** DEGRADED answered, just slowly, so it counts as successful (same rule as HealthResult.isSuccess()). */
    public int successfulChecks() {
        return upChecks + degradedChecks;
    }
}
```

A **record** auto-creates private final fields, a constructor, accessors named after components (`totalChecks()`), `equals`, `hashCode` and `toString`. The key choice is **Optional**: a never-checked service has *no* uptime, which differs from 0%; `empty()` lets the menu print `n/a` instead of a misleading `0.0%`. Averages are empty when there is no successful check. `successfulChecks()` is up + degraded (same "DEGRADED is success" rule).


### 6.3 `report/ReportGenerator.java`: Results to statistics

A final class of static, pure functions: same input, same output, nothing modified. The module table says Report "does NOT modify data"; this makes it true by construction.

**Depends on:** `HealthResult`, `Incident`, collections. No link to engine, storage or menu.

#### 6.3.1 Package and imports

```java
package report;

import healthcheck.HealthResult;
import incident.Incident;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
```

#### 6.3.2 Constants

```java
/**
 * Turns stored check results and incidents into statistics.
 *
 * Read-only by design (the module table says it must not modify data): every method is a pure
 * function. It receives plain lists, so it knows nothing about the engine, files or the console.
 */
public final class ReportGenerator {

    /** How many of the latest checks the "recent uptime" figure looks at. */
    public static final int RECENT_WINDOW = 10;

    private ReportGenerator() { }   // static utility, nothing to instantiate
```

`RECENT_WINDOW = 10` checks for "recent uptime". Private constructor: only static methods.

#### 6.3.3 Inputs

```java
    /**
     * @param results       this service's checks, oldest first
     * @param allIncidents  the whole incident history; this method picks out the service's own
     */
    public static ServiceReport forService(String serviceId, String serviceName,
                                           List<HealthResult> results, List<Incident> allIncidents) {
```

The service id/name, **its own** results (oldest first), and the whole incident history to pick from.

#### 6.3.4 Counting the checks

```java
        int up = 0, degraded = 0, failed = 0;
        long responseSum = 0;
        int responseCount = 0;
        int slowest = -1;

        for (HealthResult r : results) {
            switch (r.getStatus()) {
                case UP:       up++; break;
                case DEGRADED: degraded++; break;
                default:       failed++; break;
            }
            // A failed check has no response time (stored as 0), so it must not drag the average down.
            if (r.isSuccess()) {
                responseSum += r.getResponseTimeMs();
                responseCount++;
                slowest = Math.max(slowest, r.getResponseTimeMs());
            }
        }
```

One pass counts UP, DEGRADED and DOWN. **Only successful checks feed response-time stats**: a failed check stores 0 ms and would make a failing service look fast.

#### 6.3.5 Incidents and building the report

```java
        int incidentCount = 0;
        int downtimeRounds = 0;
        for (Incident i : allIncidents) {
            if (i.getServiceId().equals(serviceId)) {
                incidentCount++;
                downtimeRounds += i.getFailureCount();
            }
        }

        return new ServiceReport(serviceId, serviceName, results.size(), up, degraded, failed,
                uptime(up + degraded, results.size()),
                recentUptime(results),
                responseCount == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) responseSum / responseCount),
                slowest < 0 ? OptionalInt.empty() : OptionalInt.of(slowest),
                incidentCount, downtimeRounds);
    }
```

Counts this service's incidents and sums their failure counts as `downtimeRounds`, then builds the `ServiceReport` (Optionals empty when there is no data).

#### 6.3.6 The uptime formula

```java
    /** Uptime % = successful checks / total checks * 100; empty when nothing has been checked. */
    private static OptionalDouble uptime(int successful, int total) {
        return total == 0 ? OptionalDouble.empty() : OptionalDouble.of(100.0 * successful / total);
    }
```

`100.0 * successful / total`; `100.0` forces decimal arithmetic. Zero checks gives an empty `OptionalDouble` (no divide by zero, no "0% vs no data" confusion).

#### 6.3.7 Recent uptime

```java
    private static OptionalDouble recentUptime(List<HealthResult> results) {
        List<HealthResult> window = recent(results, RECENT_WINDOW);
        int successful = 0;
        for (HealthResult r : window) {
            if (r.isSuccess()) successful++;
        }
        return uptime(successful, window.size());
    }
```

Uptime over only the last 10 checks: overall shows history, recent shows *current health*.

#### 6.3.8 The sliding window

```java
    /**
     * The last n results, oldest first. A fixed-size sliding window: a single pass that never holds
     * more than n items, dropping the oldest from the front in O(1) (ArrayDeque).
     */
    public static List<HealthResult> recent(List<HealthResult> results, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be at least 1, got " + n);
        }
        Deque<HealthResult> window = new ArrayDeque<>();
        for (HealthResult r : results) {
            if (window.size() == n) {
                window.pollFirst();
            }
            window.addLast(r);
        }
        return List.copyOf(window);
    }
```

One pass keeping at most `n` items in a **`Deque`**: if full, `pollFirst()` drops the oldest; `addLast` adds the newest. `ArrayDeque` does both in constant time. `List.copyOf` returns an immutable copy.

#### 6.3.9 Ranking with Comparators

```java
    /** Returns a NEW sorted list; the input is left untouched. Services with no data always sort last. */
    public static List<ServiceReport> sort(List<ServiceReport> reports, ReportOrder order) {
        Comparator<ServiceReport> byName = Comparator
                .comparing(ServiceReport::serviceName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ServiceReport::serviceId);   // ids are unique, so the order is always total

        Comparator<ServiceReport> comparator;
        switch (order) {
            case UPTIME:
                // Highest first. "No data" maps to -1 so it falls below even 0% when reversed.
                comparator = Comparator.<ServiceReport>comparingDouble(r -> r.uptimePercent().orElse(-1.0))
                        .reversed().thenComparing(byName);
                break;
            case RESPONSE_TIME:
                // Lowest first. "No data" maps to the largest value so it falls to the end.
                comparator = Comparator.<ServiceReport>comparingDouble(r -> r.averageResponseMs().orElse(Double.MAX_VALUE))
                        .thenComparing(byName);
                break;
            default:
                comparator = byName;
        }
        List<ServiceReport> sorted = new ArrayList<>(reports);
        sorted.sort(comparator);
        return sorted;
    }
}
```

A **Comparator** defines an order. `byName` (case-insensitive, then id) is the tie-breaker so the order is total. UPTIME: descending (`.reversed()`), "no data" mapped to -1 so it ends last. RESPONSE_TIME: ascending, no data mapped to `Double.MAX_VALUE`. It sorts a **new** list; the input is untouched.



## 7. Package `storage`

Objects to files and back, plus the log. No business rules: `Incident` validates itself, storage only converts.

### 7.1 `storage/Storage.java`: The storage contract

The engine depends on this interface, not on CSV, so tests use an in-memory fake and the format can change freely.

**Depends on:** `StorageException`, `StorageData`.

```java
package storage;

import exception.StorageException;

/**
 * Where data is kept. The engine depends on this interface only, so it never knows about CSV or
 * files, and tests can use an in-memory version.
 */
public interface Storage {

    void save(StorageData data) throws StorageException;

    /** Returns empty data (not an error) when nothing has been saved yet. */
    StorageData load() throws StorageException;
}
```

`save` writes a snapshot, `load` returns one; both declare `throws StorageException`. `load()` returns *empty* data (not an error) when nothing is saved, so the first run starts cleanly.


### 7.2 `storage/ServiceRecord.java`: What is saved about a service

Plain values: type, id, name, failure rate, status. A record.

**Depends on:** `ServiceStatus`.

```java
package storage;

import model.ServiceStatus;

/**
 * What is saved about a service: plain values, not a live Service object. A Service carries a
 * Random and demo switches that are not worth saving; the engine rebuilds a real Service from
 * this record through ServiceFactory. (Records are immutable value carriers.)
 */
public record ServiceRecord(String type, String id, String name, double failureRate, ServiceStatus status) { }
```

Why not save the `Service`? It holds a `Random` and demo switches. A record is just what is needed to *rebuild* one through `ServiceFactory`.


### 7.3 `storage/StorageData.java`: One whole snapshot

Everything persisted: service records, every result, the incident history.

**Depends on:** `HealthResult`, `Incident`, `List`.

```java
package storage;

import healthcheck.HealthResult;
import incident.Incident;

import java.util.List;

/** Everything that is persisted, as one value: services, every check result, and the incident history. */
public record StorageData(List<ServiceRecord> services, List<HealthResult> results, List<Incident> incidents) {

    /** Compact constructor: defensive copies, so the saved snapshot cannot change after it is built. */
    public StorageData {
        services = List.copyOf(services);
        results = List.copyOf(results);
        incidents = List.copyOf(incidents);
    }

    public static StorageData empty() {
        return new StorageData(List.of(), List.of(), List.of());
    }
}
```

The **compact constructor** (no parameter list) runs before fields are assigned; here it replaces each list with `List.copyOf(...)`, an immutable copy (and a null check), so a snapshot cannot change afterwards. `empty()` is the "nothing saved" snapshot.


### 7.4 `storage/Csv.java`: Reading and writing CSV safely

CSV looks easy until a value has a comma: `Payments, EU` would be cut in two by `split(",")`. This helper quotes such values on the way out and understands quotes on the way in. Package-private.

**Depends on:** `ArrayList`, `List`.

#### 7.4.1 Package and imports

```java
package storage;

import java.util.ArrayList;
import java.util.List;
```

#### 7.4.2 Class

```java
/**
 * Minimal CSV reading and writing. Hand-written because names and error messages can contain
 * commas and quotes ("Payments, EU"), and splitting on "," would silently corrupt them.
 * Rules: a field containing a comma or quote is wrapped in quotes, and inner quotes are doubled.
 */
final class Csv {

    private Csv() { }
```

`final` + private constructor: static utilities only.

#### 7.4.3 join

```java
    static String join(String... fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) line.append(',');
            line.append(escape(fields[i]));
        }
        return line.toString();
    }
```

`String...` is **varargs** (any number of arguments). Escapes each field and joins with commas.

#### 7.4.4 escape

```java
    private static String escape(String field) {
        if (field == null) return "";
        // Files are read line by line, so a line break inside a field would split one record in two.
        String value = field.replace('\r', ' ').replace('\n', ' ');
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
```

`null` becomes empty; CR/LF become spaces (files are read line by line, so a line break would split a record); a value with a comma or quote is wrapped in quotes and inner quotes **doubled** (`say "hi"` becomes `"say ""hi"""`), the standard rule.

#### 7.4.5 split

```java
    /** @throws IllegalArgumentException if the line is malformed (e.g. an unterminated quote) */
    static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean closedQuote = false;     // a quoted field just ended; only a comma may follow

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');             // "" inside quotes is one literal quote
                        i++;
                    } else {
                        inQuotes = false;
                        closedQuote = true;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
                closedQuote = false;
            } else if (closedQuote) {
                throw new IllegalArgumentException("unexpected text after closing quote at position " + i);
            } else if (c == '"') {
                if (current.length() > 0) {
                    throw new IllegalArgumentException("quote in the middle of a field at position " + i);
                }
                inQuotes = true;
            } else {
                current.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("unterminated quote");
        }
        fields.add(current.toString());
        return fields;
    }
}
```

A small state machine over the characters. Inside quotes, `""` is one literal quote and a single `"` ends the field; outside quotes a comma ends it. Malformed lines (unterminated quote, text after a closing quote, quote mid-field) throw `IllegalArgumentException`, which `FileStorage` turns into an error naming the file and line.


### 7.5 `storage/FileStorage.java`: CSV persistence with safe writes

Implements `Storage` with three CSV files in one folder: `services.csv`, `results.csv`, `incidents.csv`.

**Depends on:** `StorageException`, the domain types it converts, `java.nio.file`, `java.time`.

#### 7.5.1 Package and imports

```java
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
```

#### 7.5.2 Constants and folder

```java
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
```

File names and header lines are constants. The header is written first and **checked** on load (catches wrong file or old format).

#### 7.5.3 save: convert, then write

```java
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
```

Each list becomes CSV lines via `Csv.join`: rate with `String.valueOf`, timestamps with ISO `toString()`, enums with `name()`, an open incident's end time as an empty string. Three files are written through `writeAtomically`; any `IOException` becomes a `StorageException` naming the folder.

#### 7.5.4 writeAtomically: crash-safe

```java
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
```

The key safety feature: write `services.csv.tmp` completely, then **move** it over the real file in one atomic step. A crash or power cut mid-write leaves the previous complete file, never a half-written one. Falls back to a normal replace where atomic moves are unsupported.

#### 7.5.5 load: read, parse, convert

```java
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
```

For each file: read its rows and `parse` each with a **lambda** (`f -> ...`) building the object from the fields `f` (`Double.parseDouble`, `ServiceStatus.valueOf`, `LocalDateTime.parse`). An empty end-time column becomes `null`.

#### 7.5.6 Row and readRows

```java
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
```

`Row` pairs a line number with its text so errors can say "line 3". `readRows`: a **missing file** gives an empty list (nothing saved yet); the header must match (a BOM is tolerated); blank lines are skipped; the stored number is `i + 1` (humans count from 1 and the header is line 1).

#### 7.5.7 RowParser and parse

```java
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
```

`RowParser` is a **functional interface**, so a lambda fits. `parse` splits the row, checks the column count, runs the parser, and turns *any* `IllegalArgumentException`/`DateTimeException` (bad number, unknown enum, bad time, an impossible `Incident`) into a `StorageException` such as `results.csv line 3: ...`.


### 7.6 `storage/AppLogger.java`: The application log (Singleton)

One shared log for the whole program: the second **Singleton**.

**Depends on:** `java.nio.file`, `java.time` only.

#### 7.6.1 Package and imports

```java
package storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
```

#### 7.6.2 The single instance

```java
/**
 * Singleton: one application-wide log, so every module writes to the same file in order.
 *
 * Logging must never break monitoring, so no log method throws. If the file cannot be written, a
 * single warning goes to stderr and the application carries on.
 */
public final class AppLogger {

    public static final Path DEFAULT_FILE = Path.of("data", "monitor.log");

    private static final AppLogger INSTANCE = new AppLogger();   // created once, when the class loads (thread-safe)
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Path file = DEFAULT_FILE;
    private boolean warned;
```

`private static final AppLogger INSTANCE = new AppLogger();` is created **when the class loads**, which the JVM guarantees thread-safe. `STAMP` formats timestamps; `warned` remembers whether a write failure was already reported.

#### 7.6.3 Private constructor and getInstance

```java
    private AppLogger() { }   // private: nobody else can create a second logger

    public static AppLogger getInstance() {
        return INSTANCE;
    }
```

The **private constructor** is what makes it a Singleton: nobody else can `new` one; everyone calls `getInstance()`.

#### 7.6.4 Choosing the log file

```java
    /** Redirects the log (used by tests, or to choose another location). */
    public synchronized void setLogFile(Path file) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        this.file = file;
        this.warned = false;
    }

    public synchronized Path getLogFile() { return file; }
```

Lets tests redirect into a temp folder. `synchronized`: one thread at a time.

#### 7.6.5 info, warn, error

```java
    public void info(String message)  { log("INFO", message); }

    public void warn(String message)  { log("WARN", message); }

    public void error(String message) { log("ERROR", message); }
```

One-liners calling `log` with a level.

#### 7.6.6 Writing a line, never failing

```java
    private synchronized void log(String level, String message) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file,
                    LocalDateTime.now().format(STAMP) + " [" + level + "] " + message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!warned) {            // warn once, not on every line
                warned = true;
                System.err.println("Logging disabled, cannot write " + file + ": " + e.getMessage());
            }
        }
    }
}
```

Builds `2026-11-10 14:30:00 [WARN] message`, creates the folder, appends. The rule is in the `catch`: **logging never throws**; on failure it prints one warning to `System.err` (the `warned` flag stops repeats) and carries on. A broken log must never break monitoring.



## 8. Package `engine`

One class that connects the modules. If you can explain this section you can explain how the program runs.

### 8.1 `engine/MonitoringEngine.java`: The Singleton that runs the show

Owns the services and all results and runs the main flow: check, record the verdict, tell the observer, hand the result to the incident logic. It also saves, loads and builds reports. It contains **no health or incident rules**; those live in their own modules, used through interfaces.

**Depends on:** more than any class, by design (it is the coordinator): exceptions, `HealthChecker`, `IncidentManager`, `Service`/`ServiceFactory`, the three notifiers (default wiring only), `ReportGenerator`, `Storage`/`FileStorage`/`AppLogger`. It uses `Storage` and `HealthChecker` as *interfaces*.

#### 8.1.1 Package and imports

```java
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
```

#### 8.1.2 Class and the CheckObserver callback

```java
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
```

`CheckObserver` is a nested one-method interface; the engine calls it for every check so the *screen* can print result lines. It runs **after** the result is recorded but **before** the incident logic, so output reads naturally: the round line, *then* the alert it caused. `@FunctionalInterface` lets a method reference (`this::printCheck`) be passed.

#### 8.1.3 Constants and fields

```java
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
```

`DEFAULT_CHECKER = "latency"`. `data` and `data-demo` are separate folders so a demo never overwrites real data. `instance` is the Singleton's single instance. Injected collaborators are `final`. `services` is a **`LinkedHashMap`** (remembers insertion order). `results` maps each id to all its `HealthResult`s, the raw data for reports. `roundCounter` is the `[Round n]` number.

#### 8.1.4 Constructor: package-private, fully injected

```java
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
```

No `public`: only the Singleton code and tests (same package) can call it. All dependencies are passed in, so production uses `getInstance()` while a test builds an engine with fake storage. The end registers an **anonymous class** (`new IncidentListener() {...}`, written inline) that logs incidents: the log is just another observer.

#### 8.1.5 Singleton access

```java
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
```

`getInstance()` creates the engine once and returns the same one after (`synchronized` for thread safety). `initialize(seed)` makes a **seeded demo engine** and throws if one already exists (a seed must come first; failing loudly beats ignoring it).

#### 8.1.6 The default wiring

```java
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
```

The one place real parts are chosen and connected: an `IncidentManager` with console, file (`data/alerts.log`) and simulated-email listeners, a seeded `ServiceFactory`, the latency checker, the logger and a `FileStorage`. Read this to see the whole system assembled.

#### 8.1.7 Adding services

```java
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
```

Two overloads: build from a type name, or register a ready-made `Service` (used by loading and tests). It rejects `null` and a **duplicate id** (`InvalidServiceException`), stores the service, creates its empty result list, and logs.

#### 8.1.8 Removing a service

```java
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
```

Removes the service and its results (a reused id starts clean) and asks the manager to *forget* it: an open incident is closed with the note "Service removed while incident was open" and returned so the menu can say so. No recovered alert.

#### 8.1.9 Lookups and require()

```java
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
```

`getServices()` returns an immutable snapshot in insertion order. `setForceFailure` flips the demo switch. `require(id)` is the shared private lookup: trims the id and throws "No service with id ..." so that rule exists once.

#### 8.1.10 runRound: the main loop

```java
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
```

The program's main flow: (1) no services: return empty, no round counted; (2) `++roundCounter`; (3) loop over a **copy** of the services; (4) `checker.check(service)` (never throws); (5) `service.setStatus(...)`: **the engine** records the verdict; (6) store the result and log it (`info`/`warn`); (7) if an observer exists, compute the streak *including this check* (`current + 1` for a failure, since the manager has not counted it yet) and call it; (8) **only then** `incidentManager.recordResult(...)`, which may open or resolve an incident and fire alerts.

#### 8.1.11 runRounds

```java
    public void runRounds(int count, CheckObserver observer) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, got " + count);
        }
        for (int i = 0; i < count; i++) {
            runRound(observer);
        }
    }
```

Repeats `runRound`; a count below 1 throws `IllegalArgumentException`.

#### 8.1.12 Building reports

```java
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
```

Asks `ReportGenerator.forService` for each service, passing its results and the incident history. No calculation here.

#### 8.1.13 save

```java
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
```

Converts live objects to plain data (each `Service` becomes a `ServiceRecord` using `typeNameOf`), gathers all results, adds the incident history, and hands a `StorageData` to the `Storage`. A failure is logged and re-thrown for the menu to report.

#### 8.1.14 load: guard

```java
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
```

Restores into an **empty** engine (else `IllegalStateException`). Not saved on purpose: demo switches, the threshold, the round counter. **All-or-nothing**: everything is built first, committed at the end, so bad data never leaves a half-loaded engine.

#### 8.1.15 load, step 1: rebuild services

```java
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
```

Each `ServiceRecord` becomes a real `Service` via the factory, collected in a *local* map. A duplicate id or a record the factory refuses throws a `StorageException`. The engine has not changed yet.

#### 8.1.16 load, step 2: restore incidents

```java
        // Step 2: restore incidents. The manager validates everything first and changes nothing on failure.
        try {
            incidentManager.restoreHistory(data.incidents());
        } catch (IllegalArgumentException e) {
            throw new StorageException("Saved incidents are inconsistent: " + e.getMessage(), e);
        }
```

`restoreHistory` validates and applies saved incidents (nothing changes if inconsistent); its `IllegalArgumentException` becomes a `StorageException`.

#### 8.1.17 load, step 3: commit

```java
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
```

Now safe to change the engine: put services and empty result lists in place, attach saved results (ones for unknown services are counted and ignored), and close any **open incident whose service no longer exists** with the note "Service missing when saved data was loaded". A log line summarises.

#### 8.1.18 Read-only access

```java
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
```

Small methods so the menu never reaches into internals: round count, threshold (the setter validates through the manager), streaks, incident history and open incidents, one service's results (unmodifiable).



## 9. Packages `console` and `Main`

The menu only reads input, prints output and calls the engine. If it held business logic, the modules would not be independent.

### 9.1 `console/ConsoleMenu.java`: The text menu

Everything the user sees and types. Its streams are injected, so tests can type a whole session and read the screen.

**Depends on:** the engine, the domain types it prints, the exceptions it handles, `ReportGenerator`/`ReportOrder`. Nothing depends on it.

#### 9.1.1 Package and imports

```java
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
```

#### 9.1.2 Constants and fields

```java
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
```

`MAX_ROUNDS = 1000` guards against a typo flooding the screen. Fields: engine, input reader, output stream, all injected.

#### 9.1.3 Constructor

```java
    public ConsoleMenu(MonitoringEngine engine, BufferedReader in, PrintStream out) {
        if (engine == null || in == null || out == null) {
            throw new IllegalArgumentException("engine, in and out must not be null");
        }
        this.engine = engine;
        this.in = in;
        this.out = out;
    }
```

Rejects `null` collaborators.

#### 9.1.4 EndOfInput

```java
    /** Thrown internally when input ends (Ctrl+D / Ctrl+Z, or a script runs out), to leave the loop cleanly. */
    private static final class EndOfInput extends RuntimeException {
        private static final long serialVersionUID = 1L;
        EndOfInput() { super("end of input", null, false, false); }
    }
```

A private nested exception used for control flow: when input ends (Ctrl+D/Ctrl+Z, or a test script runs out) `prompt` throws it and `run` catches it, so exit is clean, no stack trace. `super(..., null, false, false)` skips stack-trace creation (cheap).

#### 9.1.5 The main loop

```java
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
```

Loop: print menu, read choice, handle it (`handle` returns false only for Exit). `catch (EndOfInput e)` leaves quietly. Either way it then **saves**, prints `Data saved.` (or a warning), and `Goodbye.`.

#### 9.1.6 Printing the menu

```java
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
```

Ten `println` calls: the menu from the project plan.

#### 9.1.7 Dispatching a choice

```java
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
```

A `switch` on the text. Options 1, 2, 4, 5 change data, so each is followed by `save()`: a crash loses at most the current action. Read-only options (3, 6, 7, 8, 9) do not save. Anything else prints "Invalid choice". Returns `false` only for `0`.

#### 9.1.8 Option 1

```java
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
```

Prints valid types, asks type, id, name, calls `engine.addService`; an `InvalidServiceException` is caught and its message printed.

#### 9.1.9 Option 2

```java
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
```

Asks an id, calls `engine.removeService`, and reports a closed incident if any.

#### 9.1.10 Option 3

```java
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
```

A `printf` table: `%-10s` is a left-aligned 10-character column, `%d` a whole number. Shows id, name, class, status, rate as a percentage (`Math.round(rate*100)`), forced flag, streak.

#### 9.1.11 Option 4

```java
    private void runOneRound() {
        if (engine.getServices().isEmpty()) {
            out.println("No services to check. Use option 1 to add one.");
            return;
        }
        engine.runRound(this::printCheck);
    }
```

No services: says so. Else `engine.runRound(this::printCheck)`, a **method reference** passing the menu's own `printCheck` as the `CheckObserver`.

#### 9.1.12 Option 5

```java
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
```

`promptInt` (null for non-numeric), range 1 to 1000, then `engine.runRounds`.

#### 9.1.13 Printing one check

```java
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
```

Formats `[Round 3] Payments API  FAIL  (error)  failures: 3`: response time for a success, error message for a failure. `label` maps UP to `OK`, DEGRADED to `DEGRADED`, DOWN to `FAIL`.

#### 9.1.14 Option 6

```java
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
```

"N open, M total", then one line per incident: id, service, status, start/end (`ongoing` if open), failure count, and the `note:` when closed by a removal.

#### 9.1.15 Option 7

```java
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
```

Gets reports, asks how to rank (blank = uptime; unknown falls back with a message), sorts with `ReportGenerator.sort`, prints a table and a footnote (RECENT = last 10 checks; response times cover successful checks only).

#### 9.1.16 Formatting Optionals

```java
    private static String percent(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%.1f%%", value.getAsDouble()) : "n/a";
    }

    private static String decimal(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%.1f", value.getAsDouble()) : "n/a";
    }

    private static String whole(OptionalInt value) {
        return value.isPresent() ? String.valueOf(value.getAsInt()) : "n/a";
    }
```

Turn an `OptionalDouble`/`OptionalInt` into text, or `n/a` when empty. `Locale.ROOT` forces a dot decimal separator on every computer.

#### 9.1.17 Option 8

```java
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
```

Shows the current value, reads a new one, calls `engine.setFailureThreshold`; a value below 1 triggers `IllegalArgumentException`, caught here and printed.

#### 9.1.18 Option 9: demo mode

```java
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
```

Lists services (forced ones marked), asks an id, **toggles** that service's `forceFailure`. This is how a failure happens on cue in a presentation.

#### 9.1.19 Saving

```java
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
```

Calls `engine.save()`, returns success. A `StorageException` is shown as a warning and never stops the program.

#### 9.1.20 Reading input

```java
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
```

`prompt` prints, flushes (so the question shows before waiting), reads and trims a line; `null` (end of input) or `IOException` becomes `EndOfInput`. `promptInt` adds `Integer.parseInt`; on `NumberFormatException` it prints "'abc' is not a whole number" and returns `null`.


### 9.2 `Main.java`: The entry point

The class Java starts; deliberately thin: pick the engine, load data, start the menu. In the default package, hence `java -cp out Main`.

**Depends on:** `ConsoleMenu`, `MonitoringEngine`, `StorageException`, standard input classes.

#### 9.2.1 Package and imports

```java
import console.ConsoleMenu;
import engine.MonitoringEngine;
import exception.StorageException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
```

#### 9.2.2 Class

```java
/**
 * Entry point. Deliberately thin: pick the engine, load saved data, start the menu. No logic lives here.
 * Run with "--seed 42" for demo mode: reproducible randomness, a clean start, and a separate
 * data folder so demos never touch real saved data.
 */
public class Main {
```

The javadoc explains `--seed`: reproducible randomness, empty start, separate data folder.

#### 9.2.3 main

```java
    public static void main(String[] args) {
        Long seed = parseSeed(args);
        MonitoringEngine engine;
        if (seed == null) {
            engine = MonitoringEngine.getInstance();
            loadSavedData(engine);
        } else {
            engine = MonitoringEngine.initialize(seed);
            System.out.println("Demo mode: random seed " + seed + ", starting empty (runs are reproducible)\n");
        }
        new ConsoleMenu(engine, new BufferedReader(new InputStreamReader(System.in)), System.out).run();
    }
```

No seed: `getInstance()` and **load** saved data. Seed: `initialize(seed)`, print a notice, start empty. Then a `ConsoleMenu` around `System.in`/`System.out` and `run()`.

#### 9.2.4 Reading --seed

```java
    /** @return the seed, or null when demo mode was not requested */
    private static Long parseSeed(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--seed")) {
                try {
                    return Long.parseLong(args[i + 1]);
                } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                    System.out.println("Usage: java -cp out Main [--seed <whole number>]");
                    System.exit(1);
                }
            }
        }
        return null;
    }
```

Finds `--seed` and parses the number after it; missing or non-numeric prints usage and exits with code 1. Returns a boxed `Long` so `null` means "no seed".

#### 9.2.5 Loading saved data

```java
    /** A corrupt data file stops the program instead of starting empty, because the first save would overwrite it. */
    private static void loadSavedData(MonitoringEngine engine) {
        try {
            engine.load();
            if (!engine.getServices().isEmpty()) {
                System.out.println("Loaded " + engine.getServices().size() + " service(s) and "
                        + engine.getIncidentHistory().size() + " incident(s) from saved data.\n");
            }
        } catch (StorageException e) {
            System.out.println("Could not load saved data: " + e.getMessage());
            System.out.println("Nothing was changed. Fix or delete the files in the '"
                    + MonitoringEngine.DEFAULT_DATA_DIR + "' folder, then start again.");
            System.exit(1);
        }
    }
}
```

Calls `engine.load()` and reports what was restored. On a corrupt file it prints the message (file and line) and **exits with code 1** instead of starting empty: the next save would overwrite the broken file and destroy the evidence.



## 10. Tests

**154 tests** in 10 classes (about 1,900 lines). A test states one rule precisely, so reading them shows what each class is *supposed* to do.

1. **Test doubles instead of randomness:** a `StubService` with an exact latency/failure; a `CacheService` with rate 0.0 that fails only when forced.
2. **Time comes from the data:** incident times use the `HealthResult` timestamp, so tests assert exact times.
3. **Scripted sessions:** `ConsoleMenu` takes injected streams, so a test types a whole session and checks the screen.
4. **Edge cases on purpose:** the 400/401 ms boundary, a broken streak, a throwing listener, corrupt CSV lines.
5. **Mutation checks:** bugs were injected deliberately (e.g. `>` to `>=`) and each time a test failed, then the code was restored, proving the tests can fail.

Run with `mvn test`. A test is a method marked `@Test`; it fails if an `assert...` is false or an unexpected exception is thrown.

### The two test doubles (in full)

### 10.1 `src/test/java/healthcheck/StubService.java`: A service whose behaviour the test chooses

Extends `Service` but replaces randomness with whatever the test supplies.

**Depends on:** `Service` and the exceptions. It lives in the *test* folder, never in the real program.

#### 10.1.1 Package and imports

```java
package healthcheck;

import exception.InvalidServiceException;
import exception.ServiceDownException;
import exception.ServiceTimeoutException;
import model.Service;

import java.util.Random;
```

#### 10.1.2 Class and Behaviour interface

```java
/**
 * Test double: a Service whose behaviour is chosen by the test, with no randomness.
 * This is possible because Service.doSimulateRequest() is the only part subclasses define.
 */
class StubService extends Service {

    /** What the stub does when asked for a request. */
    interface Behaviour {
        int run() throws ServiceDownException, ServiceTimeoutException;
    }

    private final Behaviour behaviour;

    StubService(Behaviour behaviour) throws InvalidServiceException {
        super("stub-1", "Stub", 0.0, new Random(0));
        this.behaviour = behaviour;
    }
```

`Behaviour` is a tiny functional interface (return a number or throw). The stub holds one and `doSimulateRequest()` just runs it. Failure rate `0.0` and fixed `Random(0)`: it never fails by chance.

#### 10.1.3 Three creators

```java
    static StubService respondingIn(int ms) throws InvalidServiceException {
        return new StubService(() -> ms);
    }

    static StubService failingDown(String message) throws InvalidServiceException {
        return new StubService(() -> { throw new ServiceDownException(message); });
    }

    static StubService failingTimeout(String message) throws InvalidServiceException {
        return new StubService(() -> { throw new ServiceTimeoutException(message); });
    }

    @Override
    protected int doSimulateRequest() throws ServiceDownException, ServiceTimeoutException {
        return behaviour.run();
    }
}
```

`respondingIn(120)`, `failingDown(msg)`, `failingTimeout(msg)`, each a one-line **lambda**. It works because `Service` has a single overridable step: the template-method design pays off in tests.


### 10.2 `src/test/java/engine/InMemoryStorage.java`: A fake Storage that can fail

Lets engine and menu tests avoid the disk.

**Depends on:** `Storage`, `StorageData`.

```java
package engine;

import exception.StorageException;
import storage.Storage;
import storage.StorageData;

/** Test double for Storage: keeps data in memory, counts saves, and can be told to fail. */
class InMemoryStorage implements Storage {

    StorageData saved = StorageData.empty();
    int saveCount = 0;
    boolean failOnSave = false;
    boolean failOnLoad = false;

    @Override
    public void save(StorageData data) throws StorageException {
        if (failOnSave) throw new StorageException("disk full");
        saved = data;
        saveCount++;
    }

    @Override
    public StorageData load() throws StorageException {
        if (failOnLoad) throw new StorageException("corrupt");
        return saved;
    }
}
```

Keeps the last snapshot (`saved`), counts saves (`saveCount`), and has `failOnSave`/`failOnLoad` switches. Tests use `saveCount` to prove the menu saves after each change, and the switches to prove a failed save is reported without crashing. Possible only because the engine depends on the `Storage` *interface*.

### Key tests (in full, explained)


#### The 400 ms boundary, part 1

`HealthCheckerTest.java`

```java
    @Test
    void latency_exactlyAtThresholdIsStillUp() throws InvalidServiceException {
        HealthResult r = new LatencyHealthChecker(400).check(StubService.respondingIn(400));
        assertEquals(ServiceStatus.UP, r.getStatus());
    }
```

**Proves:** exactly 400 ms is UP (the rule is "strictly greater"). `StubService.respondingIn(400)` gives exactly 400; `assertEquals(expected, actual)` fails on a difference.


#### The 400 ms boundary, part 2

`HealthCheckerTest.java`

```java
    @Test
    void latency_oneMsOverThresholdIsDegraded() throws InvalidServiceException {
        HealthResult r = new LatencyHealthChecker(400).check(StubService.respondingIn(401));
        assertEquals(ServiceStatus.DEGRADED, r.getStatus());
        assertTrue(r.isDegraded());
        assertEquals(401, r.getResponseTimeMs());
    }
```

**Proves:** 401 ms is DEGRADED, keeps its response time, and still counts as success. With the previous test, changing `>` to `>=` fails one of them: exactly what the mutation check confirmed.


#### An incident opens at exactly three failures

`IncidentManagerTest.java`

```java
    @Test
    void exactlyAtThreshold_opensOneIncidentAndNotifiesOnce() {
        feed("a", DOWN, DOWN, DOWN);
        assertEquals(1, manager.getHistory().size());
        Incident inc = manager.getHistory().get(0);
        assertEquals(IncidentStatus.OPEN, inc.getStatus());
        assertEquals(3, inc.getFailureCount());
        assertEquals(List.of("OPENED INC-1"), listener.events);
    }
```

**Proves:** the core rule. Three DOWN results give one OPEN incident with failure count 3 and exactly one listener event, `OPENED INC-1`. `RecordingListener` (top of the test file) is how Observer is verified: it just remembers every call.


#### Failures must be consecutive

`IncidentManagerTest.java`

```java
    @Test
    void aSuccessBreaksTheStreak_failuresMustBeConsecutive() {
        feed("a", DOWN, DOWN, UP, DOWN, DOWN);
        assertTrue(manager.getHistory().isEmpty());
        assertEquals(2, manager.getConsecutiveFailures("a"));
    }
```

**Proves:** DOWN, DOWN, UP, DOWN, DOWN opens **no** incident and the streak is 2, not 4: the "in a row" requirement.


#### Recovery then a second outage

`IncidentManagerTest.java`

```java
    @Test
    void afterResolving_aNewStreakOpensANewIncident() {
        feed("a", DOWN, DOWN, DOWN, UP, DOWN, DOWN, DOWN);
        assertEquals(2, manager.getHistory().size());
        assertEquals("INC-2", manager.getHistory().get(1).getId());
        assertEquals(List.of("OPENED INC-1", "RESOLVED INC-1", "OPENED INC-2"), listener.events);
    }
```

**Proves:** after resolution a new streak of three opens `INC-2`, and the listener saw `OPENED INC-1`, `RESOLVED INC-1`, `OPENED INC-2` in order.


#### A broken notifier is contained

`IncidentManagerTest.java`

```java
    @Test
    void aFailingListenerDoesNotStopOthersOrCorruptState() {
        IncidentManager m = new IncidentManager();
        RecordingListener good = new RecordingListener();
        m.addListener(new IncidentListener() {
            @Override public void onIncidentOpened(Incident i) { throw new RuntimeException("channel broken"); }
            @Override public void onIncidentResolved(Incident i) { throw new RuntimeException("channel broken"); }
        });
        m.addListener(good);

        for (int i = 0; i < 3; i++) m.recordResult(result("a", DOWN));
        assertEquals(List.of("OPENED INC-1"), good.events);       // still notified
        assertEquals(1, m.getHistory().size());                   // state intact

        m.recordResult(result("a", UP));
        assertEquals(List.of("OPENED INC-1", "RESOLVED INC-1"), good.events);
    }
```

**Proves:** the isolation rule. One listener (an **anonymous class**) throws on every call; the healthy second listener still gets both events and the manager's state is correct.


#### The whole flow and its ordering

`MonitoringEngineTest.java`

```java
    @Test
    void forcedFailureOpensAnIncidentAtTheThreshold_andTheObserverSeesEachCheckFirst() throws InvalidServiceException {
        engine.addService(reliable("a"));
        engine.setForceFailure("a", true);
        engine.runRounds(3, recordStreaks());
        // The CHECK line for the 3rd failure must come BEFORE the alert it triggers.
        assertEquals(List.of("CHECK 1", "CHECK 2", "CHECK 3", "OPENED INC-1"), events);
        assertEquals(ServiceStatus.DOWN, engine.getService("a").getStatus());
        assertEquals(1, engine.getOpenIncidents().size());
    }
```

**Proves:** three forced rounds give `CHECK 1`, `CHECK 2`, `CHECK 3`, `OPENED INC-1`. `CHECK` entries come from the `CheckObserver`, `OPENED` from the incident listener, so the exact order proves the observer runs **before** the alert.


#### Removing a service mid-outage

`MonitoringEngineTest.java`

```java
    @Test
    void removingAServiceWithAnOpenIncidentClosesItWithANote_withoutARecoveredAlert() throws InvalidServiceException {
        engine.addService(reliable("a"));
        engine.setForceFailure("a", true);
        engine.runRounds(3, null);

        Optional<Incident> closed = engine.removeService("a");

        assertTrue(closed.isPresent());
        assertEquals(IncidentStatus.RESOLVED, closed.get().getStatus());
        assertEquals("Service removed while incident was open", closed.get().getResolutionNote());
        assertTrue(engine.getOpenIncidents().isEmpty());
        assertEquals(0, engine.getConsecutiveFailures("a"));
        assertEquals(List.of("OPENED INC-1"), events);          // no RESOLVED/RECOVERED alert
        assertTrue(engine.getServices().isEmpty());
        assertThrows(InvalidServiceException.class, () -> engine.getResults("a"));
    }
```

**Proves:** the incident closes with the note, the streak clears, the service and results vanish, and the events are only `OPENED INC-1`: no RESOLVED, so no false "recovered".


#### Save, restart, carry on

`MonitoringEngineTest.java`

```java
    @Test
    void aSavedSessionIsFullyRestored_andMonitoringContinuesFromIt() throws Exception {
        engine.addService(reliable("a"));
        engine.addService(reliable("b"));
        engine.setForceFailure("a", true);
        engine.runRounds(4, null);
        engine.save();

        List<String> events2 = new ArrayList<>();
        MonitoringEngine restored = secondEngine(events2);
        restored.load();

        assertEquals(List.of("a", "b"), idsOf(restored));
        assertEquals("Cache a", restored.getService("a").getName());
        assertEquals(0.0, restored.getService("a").getFailureRate());
        assertEquals(ServiceStatus.DOWN, restored.getService("a").getStatus());
        assertEquals(ServiceStatus.UP, restored.getService("b").getStatus());
        assertEquals(4, restored.getResults("a").size());
        assertEquals(1, restored.getOpenIncidents().size());
        assertEquals(4, restored.getOpenIncidents().get(0).getFailureCount());
        assertEquals(0, restored.getRoundCount());                       // rounds belong to one session
        assertFalse(restored.getService("a").isForceFailure());          // demo switches are not saved

        restored.runRound(null);                                         // no longer forced: "a" answers
        assertEquals(List.of("RESOLVED INC-1"), events2);

        restored.setForceFailure("a", true);
        restored.runRounds(3, null);
        assertEquals("INC-2", restored.getOpenIncidents().get(0).getId());   // numbering continues
    }
```

**Proves:** persistence end to end with a second engine on the same `InMemoryStorage`: services, rates, statuses, results and the open incident are restored; the demo switch is *not*; the next success resolves the old incident; the next outage is `INC-2` (ids stay unique across sessions).


#### A scripted user session

`ConsoleMenuTest.java`

```java
    @Test
    void theFullDetectAlertRecoverCycleThroughTheMenu() throws InvalidServiceException {
        presetReliableService();
        String output = run("9", "c-1",          // force it down
                "5", "4",                         // 4 rounds
                "6",                              // view incidents
                "9", "c-1",                       // clear the force
                "4",                              // one more round
                "6", "0");

        assertTrue(output.contains("will now fail on every check"));
        assertTrue(output.contains("failures: 3"));
        assertTrue(output.contains("[ALERT -> console] Cache is DOWN"));
        assertTrue(output.contains("1 open, 1 total"));
        assertTrue(output.contains("is back to normal behaviour"));
        assertTrue(output.contains("[ALERT -> console] Cache has RECOVERED"));
        assertTrue(output.contains("0 open, 1 total"));
        // The round line for the 3rd failure must appear BEFORE the alert it triggered.
        assertTrue(output.indexOf("failures: 3") < output.indexOf("[ALERT -> console] Cache is DOWN"));
    }
```

**Proves:** the system as a user sees it. The input lines are typed choices (force down, run 4 rounds, view incidents, clear force, one round, view again); assertions check the printed streak, DOWN alert, "1 open", RECOVERED alert, "0 open". The last assertion compares `indexOf` positions to prove the round line prints before its alert.


#### A real file round trip

`FileStorageTest.java`

```java
    @Test
    void everythingSurvivesASaveAndLoad(@TempDir Path dir) throws StorageException {
        FileStorage storage = new FileStorage(dir);
        storage.save(sample());
        assertSameData(sample(), new FileStorage(dir).load());   // a NEW instance: proves it came from disk
    }
```

**Proves:** awkward data (comma and quotes in a name, accents, an open and a resolved incident with a note) is written to real CSV in a temp folder and read back by a **new** `FileStorage`, so it truly came from disk. `assertSameData` compares field by field since the domain classes define no `equals`.


#### Corrupt files give useful errors

`FileStorageTest.java`

```java
    @Test
    void aBadNumberNamesTheFileAndLine(@TempDir Path dir) throws IOException {
        write(dir, "results.csv", "serviceId,timestamp,status,responseTimeMs,errorMessage\n"
                + "a," + T0 + ",UP,10,\n"
                + "a," + T0 + ",UP,fast,\n");
        String message = loadError(dir);
        assertTrue(message.contains("results.csv"), message);
        assertTrue(message.contains("line 3"), message);
    }
```

**Proves:** the text `fast` where a number belongs makes `load()` throw a `StorageException` containing `results.csv` and `line 3`.


#### Failures do not distort response times

`ReportGeneratorTest.java`

```java
    @Test
    void averageAndSlowestIgnoreFailedChecks() {
        // Failed checks store 0 ms; if they were counted the average would be 200, not 300.
        ServiceReport r = report(List.of(up(100), slow(500), down()));
        assertEquals(300.0, r.averageResponseMs().getAsDouble(), 1e-9);
        assertEquals(500, r.slowestResponseMs().getAsInt());
    }
```

**Proves:** UP 100 ms, DEGRADED 500 ms, DOWN gives average **300** (not 200) and slowest 500.


#### CSV quoting is lossless

`CsvTest.java`

```java
    @Test
    void roundTripPreservesTrickyValues() {
        String[] values = {"plain", "with,comma", "with \"quotes\"", "", "ünïcödé ✓", ",", "\"", "a,\"b\",c", " spaced "};
        assertEquals(List.of(values), Csv.split(Csv.join(values)));
    }
```

**Proves:** join then split on plain text, commas, quotes, empty, Unicode, a lone comma, a lone quote and padded spaces returns the original list.

### Every test, listed

Generated from the test source, so it is complete: one row per `@Test` method, name turned into words.


##### `ConsoleMenuTest.java`: 20 tests

| # | Test | What it proves |
|---|---|---|
| 1 | `showsTheMenuAndSaysGoodbye` | Shows the menu and says goodbye |
| 2 | `addingAServiceThroughTheMenuWorks` | Adding aservice through the menu works |
| 3 | `anUnknownTypeIsReportedNotCrashed` | An unknown type is reported not crashed |
| 4 | `aDuplicateIdIsReported` | A duplicate id is reported |
| 5 | `anInvalidChoiceIsReportedAndTheMenuContinues` | An invalid choice is reported and the menu continues |
| 6 | `endOfInputExitsCleanlyWithoutChoosingExit` | End of input exits cleanly without choosing exit |
| 7 | `checkingWithNoServicesExplainsWhatToDo` | Checking with no services explains what to do |
| 8 | `theFullDetectAlertRecoverCycleThroughTheMenu` | The full detect alert recover cycle through the menu |
| 9 | `roundCountInputIsValidated` | Round count input is validated |
| 10 | `thresholdCanBeChangedAndBadValuesAreRejected` | Threshold can be changed and bad values are rejected |
| 11 | `removingAServiceWithAnOpenIncidentTellsTheUser` | Removing aservice with an open incident tells the user |
| 12 | `removingAnUnknownServiceIsReported` | Removing an unknown service is reported |
| 13 | `reportsExplainWhenThereAreNoServices` | Reports explain when there are no services |
| 14 | `reportsShowUptimeAndResponseTimesForCheckedServices` | Reports show uptime and response times for checked services |
| 15 | `aServiceWithNoChecksShowsNaInsteadOfZero` | A service with no checks shows na instead of zero |
| 16 | `anUnknownRankingChoiceFallsBackToUptime` | An unknown ranking choice falls back to uptime |
| 17 | `savesAfterAChangeAndOnExit` | Saves after achange and on exit |
| 18 | `everyDataChangingActionSaves_andReadOnlyActionsDoNot` | Every data changing action saves: and read only actions do not |
| 19 | `endOfInputAlsoSaves` | End of input also saves |
| 20 | `aFailedSaveIsReportedButTheMenuKeepsWorking` | A failed save is reported but the menu keeps working |

##### `MonitoringEngineTest.java`: 29 tests

| # | Test | What it proves |
|---|---|---|
| 1 | `addedServicesAreListedInInsertionOrder` | Added services are listed in insertion order |
| 2 | `duplicateIdIsRejected` | Duplicate id is rejected |
| 3 | `addByTypeUsesTheFactory_andRejectsUnknownTypes` | Add by type uses the factory: and rejects unknown types |
| 4 | `unknownIdsAreRejected` | Unknown ids are rejected |
| 5 | `serviceListIsASnapshot` | Service list is asnapshot |
| 6 | `aRoundCheckingAHealthyServiceRecordsResultAndStatus` | A round checking ahealthy service records result and status |
| 7 | `aRoundWithNoServicesDoesNothing` | A round with no services does nothing |
| 8 | `runRoundsRunsTheRequestedNumberOfRounds_andRejectsBadCounts` | Run rounds runs the requested number of rounds: and rejects bad counts |
| 9 | `resultsViewIsReadOnly` | Results view is read only |
| 10 | `forcedFailureOpensAnIncidentAtTheThreshold_andTheObserverSeesEachCheckFirst` | Forced failure opens an incident at the threshold: and the observer sees each check first |
| 11 | `clearingTheForcedFailureResolvesTheIncident` | Clearing the forced failure resolves the incident |
| 12 | `changingTheThresholdAffectsWhenIncidentsOpen` | Changing the threshold affects when incidents open |
| 13 | `removingAServiceWithAnOpenIncidentClosesItWithANote_withoutARecoveredAlert` | Removing aservice with an open incident closes it with anote: without arecovered alert |
| 14 | `removingAHealthyServiceClosesNothing` | Removing ahealthy service closes nothing |
| 15 | `aRemovedIdCanBeReusedWithAFreshStreak` | A removed id can be reused with afresh streak |
| 16 | `importantEventsAreWrittenToTheLog` | Important events are written to the log |
| 17 | `getInstanceAlwaysReturnsTheSameEngine_andInitializeMustComeFirst` | Get instance always returns the same engine: and initialize must come first |
| 18 | `nullDependenciesAreRejected` | Null dependencies are rejected |
| 19 | `reportsReflectTheChecksAndIncidents` | Reports reflect the checks and incidents |
| 20 | `aServiceWithNoChecksHasNoReportData` | A service with no checks has no report data |
| 21 | `aSavedSessionIsFullyRestored_andMonitoringContinuesFromIt` | A saved session is fully restored: and monitoring continues from it |
| 22 | `savedServiceRecordsUseTheFactoryTypeName` | Saved service records use the factory type name |
| 23 | `loadingIntoANonEmptyEngineIsRefused` | Loading into anon empty engine is refused |
| 24 | `invalidSavedServicesAreStorageExceptions_andLeaveTheEngineEmpty` | Invalid saved services are storage exceptions: and leave the engine empty |
| 25 | `duplicateSavedServiceIdsAreRejected` | Duplicate saved service ids are rejected |
| 26 | `inconsistentIncidentsAbortTheWholeLoad` | Inconsistent incidents abort the whole load |
| 27 | `resultsOfUnknownServicesAreIgnored` | Results of unknown services are ignored |
| 28 | `anOpenIncidentOfAMissingServiceIsClosedWithANote` | An open incident of amissing service is closed with anote |
| 29 | `storageFailuresPropagateToTheCaller` | Storage failures propagate to the caller |

##### `HealthCheckerTest.java`: 17 tests

| # | Test | What it proves |
|---|---|---|
| 1 | `basic_successfulRequestIsUpAndKeepsResponseTime` | Basic: successful request is up and keeps response time |
| 2 | `basic_downFailureBecomesDownResultWithMessage` | Basic: down failure becomes down result with message |
| 3 | `basic_timeoutBecomesDownResult` | Basic: timeout becomes down result |
| 4 | `basic_ignoresSlowness` | Basic: ignores slowness |
| 5 | `basic_forcedFailureIsReportedAsDown` | Basic: forced failure is reported as down |
| 6 | `latency_exactlyAtThresholdIsStillUp` | Latency: exactly at threshold is still up |
| 7 | `latency_oneMsOverThresholdIsDegraded` | Latency: one ms over threshold is degraded |
| 8 | `latency_degradedStillCountsAsSuccess` | Latency: degraded still counts as success |
| 9 | `latency_failureStaysDownNotDegraded` | Latency: failure stays down not degraded |
| 10 | `latency_defaultThresholdIs400` | Latency: default threshold is400 |
| 11 | `latency_rejectsNonPositiveThreshold` | Latency: rejects non positive threshold |
| 12 | `strategySwap_sameServiceGivesDifferentVerdicts` | Strategy swap: same service gives different verdicts |
| 13 | `result_rejectsInvalidArguments` | Result: rejects invalid arguments |
| 14 | `result_nullErrorMessageBecomesEmpty` | Result: null error message becomes empty |
| 15 | `factory_createsCorrectTypes` | Factory: creates correct types |
| 16 | `factory_ignoresCaseAndSurroundingSpaces` | Factory: ignores case and surrounding spaces |
| 17 | `factory_rejectsUnknownBlankAndNull` | Factory: rejects unknown blank and null |

##### `IncidentManagerTest.java`: 34 tests

| # | Test | What it proves |
|---|---|---|
| 1 | `belowThreshold_noIncident` | Below threshold: no incident |
| 2 | `exactlyAtThreshold_opensOneIncidentAndNotifiesOnce` | Exactly at threshold: opens one incident and notifies once |
| 3 | `incidentStartsAtTheCheckThatCrossedTheThreshold` | Incident starts at the check that crossed the threshold |
| 4 | `failuresWhileOpen_countButDoNotOpenASecondIncident` | Failures while open: count but do not open asecond incident |
| 5 | `serviceNameIsUsedWhenGiven_otherwiseTheId` | Service name is used when given: otherwise the id |
| 6 | `firstSuccessResolvesAndNotifiesOnce` | First success resolves and notifies once |
| 7 | `degradedCountsAsSuccess_andResolvesAnOpenIncident` | Degraded counts as success: and resolves an open incident |
| 8 | `afterResolving_aNewStreakOpensANewIncident` | After resolving: a new streak opens anew incident |
| 9 | `aSuccessBreaksTheStreak_failuresMustBeConsecutive` | A success breaks the streak: failures must be consecutive |
| 10 | `aDegradedResultAlsoBreaksTheStreak` | A degraded result also breaks the streak |
| 11 | `successWithNoFailuresAndNoIncident_doesNothing` | Success with no failures and no incident: does nothing |
| 12 | `servicesAreTrackedIndependently` | Services are tracked independently |
| 13 | `interleavedServices_oneRecoveringDoesNotResolveTheOther` | Interleaved services: one recovering does not resolve the other |
| 14 | `customThresholdIsRespected` | Custom threshold is respected |
| 15 | `invalidThresholdIsRejected` | Invalid threshold is rejected |
| 16 | `loweringTheThresholdMidStreakOpensOnTheNextFailure` | Lowering the threshold mid streak opens on the next failure |
| 17 | `removedListenerIsNoLongerNotified` | Removed listener is no longer notified |
| 18 | `allRegisteredListenersAreNotified` | All registered listeners are notified |
| 19 | `aFailingListenerDoesNotStopOthersOrCorruptState` | A failing listener does not stop others or corrupt state |
| 20 | `historyViewIsReadOnly` | History view is read only |
| 21 | `nullInputsAreRejected` | Null inputs are rejected |
| 22 | `incidentRejectsImpossibleStates` | Incident rejects impossible states |
| 23 | `forgetService_closesTheOpenIncidentWithANote_clearsTheStreak_andDoesNotNotify` | Forget service: closes the open incident with anote_clears the streak_and does not notify |
| 24 | `forgetService_withNoOpenIncident_returnsEmptyButStillClearsTheStreak` | Forget service: with no open incident_returns empty but still clears the streak |
| 25 | `forgetService_neverProducesAnEndTimeBeforeTheStart` | Forget service: never produces an end time before the start |
| 26 | `aNormalRecoveryHasNoResolutionNote` | A normal recovery has no resolution note |
| 27 | `anOpenIncidentCannotCarryAResolutionNote` | An open incident cannot carry aresolution note |
| 28 | `restoreHistory_bringsBackHistoryAndOpenIncidents_withoutNotifying` | Restore history: brings back history and open incidents_without notifying |
| 29 | `aRestoredOpenIncidentCanBeResolvedByTheNextSuccess` | A restored open incident can be resolved by the next success |
| 30 | `aRestoredOpenIncidentKeepsCountingFailures_withoutOpeningASecondOne` | A restored open incident keeps counting failures: without opening asecond one |
| 31 | `numberingContinuesAfterTheHighestSavedId` | Numbering continues after the highest saved id |
| 32 | `idsInAnotherShapeDoNotBreakNumbering` | Ids in another shape do not break numbering |
| 33 | `inconsistentDataIsRejectedAndChangesNothing` | Inconsistent data is rejected and changes nothing |
| 34 | `restoreIsOnlyAllowedIntoAnEmptyManager` | Restore is only allowed into an empty manager |

##### `ServiceFactoryTest.java`: 10 tests

| # | Test | What it proves |
|---|---|---|
| 1 | `createsTheRightSubclassForEachType` | Creates the right subclass for each type |
| 2 | `dbIsAnAliasForDatabase_andInputIsTrimmedAndCaseInsensitive` | Db is an alias for database: and input is trimmed and case insensitive |
| 3 | `unknownBlankOrNullTypeIsRejectedWithTheValidTypesListed` | Unknown blank or null type is rejected with the valid types listed |
| 4 | `customFailureRateIsApplied_andValidated` | Custom failure rate is applied: and validated |
| 5 | `badIdOrNameStillSurfacesAsInvalidServiceException` | Bad id or name still surfaces as invalid service exception |
| 6 | `sameMasterSeedGivesIdenticalBehaviour` | Same master seed gives identical behaviour |
| 7 | `differentMasterSeedsGiveDifferentBehaviour` | Different master seeds give different behaviour |
| 8 | `aMistypedTypeDoesNotDisturbLaterRandomness` | A mistyped type does not disturb later randomness |
| 9 | `nullSeedSourceIsRejected` | Null seed source is rejected |
| 10 | `typeNameOfMatchesWhatCreateAccepts` | Type name of matches what create accepts |

##### `NotifierTest.java`: 6 tests

| # | Test | What it proves |
|---|---|---|
| 1 | `console_openedAlertNamesServiceAndTime` | Console: opened alert names service and time |
| 2 | `console_resolvedAlertShowsDowntime` | Console: resolved alert shows downtime |
| 3 | `email_printsRecipientAndSentMarker` | Email: prints recipient and sent marker |
| 4 | `email_rejectsBlankRecipient` | Email: rejects blank recipient |
| 5 | `file_appendsOneLinePerAlert_andCreatesMissingFolders` | File: appends one line per alert_and creates missing folders |
| 6 | `file_reportsUnwritableLocationAsUncheckedException` | File: reports unwritable location as unchecked exception |

##### `ReportGeneratorTest.java`: 13 tests

| # | Test | What it proves |
|---|---|---|
| 1 | `uptimeIsSuccessfulOverTotal_andDegradedCountsAsSuccessful` | Uptime is successful over total: and degraded counts as successful |
| 2 | `noChecksMeansNoDataNotZeroPercent` | No checks means no data not zero percent |
| 3 | `onlyFailuresGiveZeroUptimeButNoResponseData` | Only failures give zero uptime but no response data |
| 4 | `averageAndSlowestIgnoreFailedChecks` | Average and slowest ignore failed checks |
| 5 | `recentReturnsTheLastNOldestFirst` | Recent returns the last noldest first |
| 6 | `recentWithFewerResultsThanNReturnsAll` | Recent with fewer results than nreturns all |
| 7 | `recentRejectsNonPositiveN` | Recent rejects non positive n |
| 8 | `recentUptimeShowsCurrentHealthWhileOverallShowsHistory` | Recent uptime shows current health while overall shows history |
| 9 | `incidentCountAndDowntimeOnlyCoverThisService` | Incident count and downtime only cover this service |
| 10 | `sortByUptimeIsBestFirst_tiesByName_andNoDataLast` | Sort by uptime is best first: ties by name_and no data last |
| 11 | `sortByResponseTimeIsFastestFirst_andNoDataLast` | Sort by response time is fastest first: and no data last |
| 12 | `sortByNameIgnoresCase` | Sort by name ignores case |
| 13 | `sortReturnsANewListAndLeavesTheInputAlone` | Sort returns anew list and leaves the input alone |

##### `AppLoggerTest.java`: 4 tests

| # | Test | What it proves |
|---|---|---|
| 1 | `isASingleton` | Is asingleton |
| 2 | `writesTimestampedLinesWithLevel_andCreatesFolders` | Writes timestamped lines with level: and creates folders |
| 3 | `anUnwritableLogNeverThrows` | An unwritable log never throws |
| 4 | `nullFileIsRejected` | Null file is rejected |

##### `CsvTest.java`: 7 tests

| # | Test | What it proves |
|---|---|---|
| 1 | `plainFieldsAreJoinedWithoutQuotes` | Plain fields are joined without quotes |
| 2 | `fieldsWithCommasOrQuotesAreQuotedAndQuotesDoubled` | Fields with commas or quotes are quoted and quotes doubled |
| 3 | `roundTripPreservesTrickyValues` | Round trip preserves tricky values |
| 4 | `nullBecomesEmpty_andLineBreaksBecomeSpaces` | Null becomes empty: and line breaks become spaces |
| 5 | `emptyLineIsOneEmptyField_andTrailingCommaGivesTrailingEmptyField` | Empty line is one empty field: and trailing comma gives trailing empty field |
| 6 | `emptyQuotedFieldIsEmpty` | Empty quoted field is empty |
| 7 | `malformedLinesAreRejected` | Malformed lines are rejected |

##### `FileStorageTest.java`: 14 tests

| # | Test | What it proves |
|---|---|---|
| 1 | `everythingSurvivesASaveAndLoad` | Everything survives asave and load |
| 2 | `loadingFromAnEmptyOrMissingFolderGivesEmptyData` | Loading from an empty or missing folder gives empty data |
| 3 | `savingCreatesTheFolder_andLeavesNoTemporaryFiles` | Saving creates the folder: and leaves no temporary files |
| 4 | `aSecondSaveReplacesTheFirstInsteadOfAppending` | A second save replaces the first instead of appending |
| 5 | `blankLinesInTheFilesAreIgnored` | Blank lines in the files are ignored |
| 6 | `aBadNumberNamesTheFileAndLine` | A bad number names the file and line |
| 7 | `aWrongColumnCountIsRejected` | A wrong column count is rejected |
| 8 | `anUnexpectedHeaderIsRejected` | An unexpected header is rejected |
| 9 | `anUnknownStatusIsRejected` | An unknown status is rejected |
| 10 | `aBadTimestampIsRejected` | A bad timestamp is rejected |
| 11 | `anImpossibleIncidentIsRejected` | An impossible incident is rejected |
| 12 | `aMalformedQuoteIsReportedAsAStorageException` | A malformed quote is reported as astorage exception |
| 13 | `savingIntoAPlaceThatCannotBeCreatedThrowsStorageException` | Saving into aplace that cannot be created throws storage exception |
| 14 | `nullDirectoryIsRejected` | Null directory is rejected |



## 11. Build and configuration files

### 11.1 `pom.xml`: The Maven build file

Tells Maven how to build and test. Optional (plain `javac` works) but it is how `mvn test` runs the tests.

**Depends on:** not applicable.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.jeevith</groupId>
  <artifactId>oop-uptime</artifactId>
  <version>1.0-SNAPSHOT</version>
  <name>Service Health Monitoring and Incident Management System</name>

  <properties>
    <!-- release=17 makes the compiler reject APIs newer than Java 17, even if you run JDK 21 -->
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <junit.version>5.10.2</junit.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- Surefire 3.x is needed to discover JUnit 5 tests -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
    </plugins>
  </build>
</project>
```

`groupId`/`artifactId`/`version` identify the project. `maven.compiler.release` = `17` accepts Java 17 and **rejects** newer APIs even on a newer JDK. UTF-8 keeps accents correct everywhere. The one dependency is **JUnit 5** with `<scope>test</scope>`: used only for tests, never shipped. `maven-surefire-plugin` 3.2.5 discovers and runs JUnit 5 tests (older versions cannot).


### 11.2 `.gitignore`: What git must not store

Files git should ignore.

**Depends on:** not applicable.

```text
# Build output
target/
out/
*.class

# IDE files
.idea/
*.iml
.vscode/
.classpath
.project
.settings/

# Runtime files produced by the app (logs, saved data) are not source
*.log
data/

# OS noise
.DS_Store
Thumbs.db

# Demo-mode data
data-demo/
```

Generated or personal files stay out of the repo: build output (`target/`, `out/`, `*.class`), IDE settings, logs, and the folders the program writes at run time (`data/`, `data-demo/`).



## 12. Java cheat sheet

| Feature | Meaning | Where |
|---|---|---|
| `abstract class` | Cannot be created directly; subclasses complete it | `Service`, `Notifier` |
| `interface` | A contract of methods | `HealthChecker`, `IncidentListener`, `Storage` |
| `enum` | Fixed set of named values | `ServiceStatus`, `IncidentStatus`, `ReportOrder` |
| `record` | Short immutable data class (compiler writes constructor, accessors, `equals`, `toString`) | `ServiceReport`, `StorageData`, `ServiceRecord` |
| Compact constructor | Record constructor without parameter list, to validate/copy | `StorageData` |
| `extends` / `implements` | Inheritance ("is a") / promise to follow an interface | `ApiService extends Service`, `LatencyHealthChecker implements HealthChecker` |
| Nested / anonymous class | A class inside another / a one-off class written inline | `EndOfInput`, `Row` / the logging listener in `MonitoringEngine` |
| `public`, `protected`, `private`, (none) | Visible everywhere / subclasses+package / this class / same package only | `Service.shouldFail()`, `Incident.resolve`, `Csv` |
| `final` | Field: assigned once. Method: not overridable. Class: not extendable | `Service.id`, `simulateRequest()`, `HealthResult` |
| `static` | Belongs to the class, not an object | `HealthCheckerFactory.create` |
| `synchronized` | One thread at a time | `AppLogger.log`, `getInstance()` |
| `@Override`, `super(...)`, `this(...)` | Replace a parent method / call parent or sibling constructor | service classes |
| Polymorphism | One call, real object's behaviour chosen at run time | `service.simulateRequest()` |
| Static factory method | Named method returning a new object | `HealthResult.up(...)` |
| Singleton | One instance: private constructor + static `getInstance()` | `AppLogger`, `MonitoringEngine` |
| Checked / unchecked exception | Compiler forces handling / programmer error | `MonitorException` family / `IllegalArgumentException` |
| `throws`, `try`/`catch`, multi-catch `A \| B` | Declare / handle / handle several types | `BasicHealthChecker` |
| `List<String>`, `ArrayList`, `HashMap`, `LinkedHashMap`, `ArrayDeque` | Generic list, resizable list, key-value map, ordered map, double-ended queue | `IncidentManager`, `MonitoringEngine`, `ReportGenerator.recent` |
| `Optional`, `OptionalDouble` | A value that may be absent, handled explicitly | `Incident.getEndTime()`, `ServiceReport` |
| `List.of`, `List.copyOf`, `unmodifiableList` | Immutable lists / read-only view | `StorageData`, `getHistory()` |
| `Comparator` | Rule for ordering | `ReportGenerator.sort` |
| Lambda `f -> ...`, method reference `A::b` | Short anonymous function / named method as a function | `FileStorage.load`, `Integer::sum`, `this::printCheck` |
| `@FunctionalInterface` | Exactly one method; usable with lambdas | `CheckObserver`, `RowParser` |
| Ternary `c ? a : b`, `switch`, varargs `String...`, `instanceof` | One-line if/else, branch by value, any number of arguments, test real class | `DatabaseService`, `ConsoleMenu.handle`, `Csv.join`, `typeNameOf` |
| `printf` / `String.format` | `%-10s` left-aligned column, `%d` whole number, `%.1f` one decimal | `ConsoleMenu` |
| `Random`, `LocalDateTime`, `Files`/`Path`, `Locale.ROOT` | Seeded randomness, dates, files, locale-independent text | `Service`, `HealthResult`, `FileStorage`, `ConsoleMenu` |
| JUnit 5 | `@Test`, `assertEquals`, `assertTrue`, `assertThrows`, `@TempDir` | all tests |

## 13. Where to go next

- `docs/PROJECT_GUIDE.md`: architecture, every decision, deviations from the plan, limitations, 22 viva questions, a 5-minute demo script.
- `docs/DECISIONS.md`: the phase-by-phase decision log. `docs/uml/`: the diagrams. `README.md`: build, run, demo.

**A suggested way to study.** Read sections 1 and 2 (how a `Service` behaves). Read 3 and 4 and trace `recordResult` with a pencil: three DOWN results, then one UP. Read section 8 to see the engine call it all. Then run the program, force a service down, and match each output line to the code that printed it.
