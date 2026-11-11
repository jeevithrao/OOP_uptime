# Service Health Monitor: Project Guide

Everything you need to understand, run, defend and present this project: what each module does, why it was built that way, the decisions behind it, and the questions you are likely to be asked.

## 1. The project at a glance

**What it is.** A console-based Java application that monitors simulated services (a database, an API, a cache). Each service misbehaves on purpose: it fails, answers slowly, or recovers. The system checks every service repeatedly, decides when something is truly broken (N failures in a row), opens an incident, alerts through three channels, saves its history, and reports uptime and response times.

**What it is really about.** OOP design. The scope excludes GUIs, databases and web frameworks on purpose, so every mark comes from structure: independent modules, interfaces between them, a class hierarchy, design patterns, collections, custom exceptions and UML.

| Measure | Value |
|---|---|
| Production code | 36 Java files, about 2,300 lines, 9 packages |
| Test code | 12 files, about 1,900 lines, 154 JUnit 5 tests |
| Design patterns | Singleton, Factory, Strategy, Observer (required) plus Template Method (extra) |
| Custom exceptions | 5, all checked, rooted at `MonitorException` |
| UML diagrams | Use case, 2 class diagrams, sequence, activity (PlantUML, in `docs/uml`) |
| Build phases | 7 (Phase 0 to 6), each committed separately |

**The story in one paragraph.** The engine asks a health checker to probe a service. The checker never throws for a failure; it returns a `HealthResult` saying UP, DEGRADED or DOWN. The engine records the result, then hands it to the `IncidentManager`. The manager counts consecutive failures per service. When the count reaches the threshold (default 3) it opens an incident and tells every registered listener. The listeners (console, file, simulated email) deliver alerts without knowing how failures are detected. The first successful check resolves the incident. Everything is saved to CSV files, and reports are computed from the stored results.

## 2. How to run, test and demo

**Run** (JDK 17 or newer):

```bash
mkdir out
javac --release 17 -d out $(find src/main/java -name "*.java")   # Linux / macOS
java -cp out Main
```

On Windows PowerShell the compile line is `javac --release 17 -d out (Get-ChildItem -Recurse src/main/java -Filter *.java).FullName`. `find` in PowerShell is a different, built-in Windows command, which is why the Linux line fails there.

**Demo mode:** `java -cp out Main --seed 42`. The seed makes randomness reproducible, the run starts empty, and it saves into `data-demo/` instead of `data/`, so a demo can never overwrite real saved data.

**Tests:** `mvn test` runs all 154 tests. During development they were run with the JUnit console launcher, because Maven was not available in the build sandbox. Run `mvn test` once on your own machine before submitting.

**Files the program creates (in `data/`):**

| File | Content |
|---|---|
| `services.csv` | Type, id, name, failure rate, last status of each service |
| `results.csv` | Every health check ever made (id, timestamp, status, response time, error) |
| `incidents.csv` | Full incident history, including open incidents and resolution notes |
| `monitor.log` | Timestamped log of everything (services added, every check, incidents, saves) |
| `alerts.log` | One line per alert sent by the file notifier |

**The 2-minute demo** (details in section 14): add an `api` service, force it down with option 9, run 4 rounds with option 5, watch the incident open at the third failure and the three alerts fire, toggle the force off and run one round to see the recovery, then view reports with option 7 and restart to show the data came back.

## 3. Architecture

### The seven modules

| # | Module (package) | Responsibility | Does NOT do |
|---|---|---|---|
| 1 | Service (`model`) | Models a monitored component and simulates its behaviour | Decide whether it is healthy |
| 2 | Health Check (`healthcheck`) | Probes a service and returns a result | Open incidents |
| 3 | Incident (`incident`) | Tracks consecutive failures, opens and resolves incidents | Send alerts directly |
| 4 | Notification (`notification`) | Delivers alerts through different channels | Know how incidents are detected |
| 5 | Report (`report`) | Calculates uptime, response times, rankings | Modify data |
| 6 | Storage and Logging (`storage`) | Saves and loads data, logs events | Contain business rules |
| 7 | Engine and Console (`engine`, `console`) | Coordinates everything and runs the menu | Contain module logic itself |

The `exception` package holds the shared exception classes. This table comes straight from the project brief, and every module honours its "does not do" column. That is the first thing to point out when asked about independent modules.

### Who talks to whom

```
Main -> ConsoleMenu -> MonitoringEngine (Singleton)
                           |-- HealthChecker (interface, Strategy) --> Service (model)
                           |-- IncidentManager (Subject) --> IncidentListener (interface, Observer)
                           |                                   ^-- Notifier: Console / File / Email
                           |                                   ^-- the engine's own logging listener
                           |-- Storage (interface) <-- FileStorage (CSV)
                           |-- ReportGenerator (pure functions)
                           '-- AppLogger (Singleton)
```

### The rules that keep the modules independent

- The engine uses other modules **only through interfaces** (`HealthChecker`, `IncidentListener`, `Storage`), so each can be replaced or tested alone.
- `incident` does not know the `Service` class. It receives a `HealthResult` and a name string.
- `notification` knows only `Incident`. It cannot see how incidents are produced.
- `report` receives plain lists. It does not know the engine, files or the console.
- `storage` converts objects to text and back and contains no business rules; `Incident` and `HealthResult` validate themselves.
- `console` only reads input, prints output and calls the engine. `Main` only picks the engine and starts the menu.

### The check flow

Engine asks Health Check to check a Service, passes the result to Incident, Incident notifies Notification when the status changes, the engine saves everything through Storage, and Report reads the stored data. The sequence diagram (`docs/uml/sequence-third-failure`) shows this for a check that fails for the third time.

## 4. How this matches the faculty's eight points

| Point | Evidence in the project |
|---|---|
| 1. Independent modules with clear responsibilities | Section 3 table; each package has one job; `IncidentManager` does not send alerts, notifiers do not detect failures |
| 2. Module interaction | Three interfaces (`HealthChecker`, `IncidentListener`, `Storage`); the Observer pattern; the engine is the only coordinator |
| 3. Core OOP principles | Abstraction (`Service`, `Notifier`), inheritance (3 service types, 3 notifiers), polymorphism (`simulateRequest()` behaves differently per type), encapsulation (private fields, validated setters, immutable `HealthResult`) |
| 4. Class hierarchies and relationships | Class diagrams show inheritance, interface realisation, composition, aggregation and multiplicities |
| 5. Collections and exceptions | Section 9: `HashMap`, `LinkedHashMap`, `ArrayList`, `ArrayDeque`, `Comparator`; five custom checked exceptions |
| 6. Design patterns | Section 7: four required patterns plus Template Method |
| 7. Components working together | `MonitoringEngine`, demo mode, and 20 scripted menu tests that drive the whole system |
| 8. UML diagrams | `docs/uml`: use case, class (core and supporting), sequence, activity |

## 5. Walkthrough: one failure, start to finish

This is genuine output from the program (a service called "Payments, EU", forced down with option 9, threshold 3, then switched back to normal). The file notifier writes the same alert to `data/alerts.log`, so it does not appear on screen:

```
[Round 1] Payments, EU   FAIL      (Payments, EU is forced down (demo mode))    failures: 1
[Round 2] Payments, EU   FAIL      (Payments, EU is forced down (demo mode))    failures: 2
[Round 3] Payments, EU   FAIL      (Payments, EU is forced down (demo mode))    failures: 3
[ALERT -> console] Payments, EU is DOWN (INC-1, 3 consecutive failures, since 02:23:41)
[EMAIL sent to ops@example.com] [ALERT -> email] Payments, EU is DOWN (INC-1, 3 consecutive failures, since 02:23:41)
[Round 4] Payments, EU   FAIL      (Payments, EU is forced down (demo mode))    failures: 4
[Round 5] Payments, EU   FAIL      (Payments, EU is forced down (demo mode))    failures: 5
[Round 6] Payments, EU   OK        (199 ms)                                     failures: 0
[ALERT -> console] Payments, EU has RECOVERED (INC-1, downtime: 5 rounds)
[EMAIL sent to ops@example.com] [ALERT -> email] Payments, EU has RECOVERED (INC-1, downtime: 5 rounds)
```

What happened, in order:

1. **The menu** calls `engine.runRound(observer)`.
2. **The engine** takes each registered service and asks the `HealthChecker` to check it.
3. **`LatencyHealthChecker`** delegates to `BasicHealthChecker`, which calls `service.simulateRequest()`.
4. **The service** throws `ServiceTimeoutException` or `ServiceDownException` (or returns a response time). `BasicHealthChecker` catches the exception and returns `HealthResult.down(...)`. Nothing escapes upward.
5. **The engine** records the verdict on the service (`setStatus`), stores the result in the history list, and writes a log line.
6. **The observer callback** prints the `[Round 3] ... failures: 3` line. This happens BEFORE the incident logic runs, so the line appears before the alert it causes.
7. **`IncidentManager.recordResult`** increments the streak to 3, sees `3 >= threshold` and no open incident, creates `INC-1`, and notifies all listeners.
8. **Listeners**: the console prints the alert, the file notifier appends a line to `alerts.log`, the email notifier prints "email sent", and the engine's logging listener writes to `monitor.log`.
9. **After the round** the menu asks the engine to save.
10. **On recovery** the first successful (UP or DEGRADED) check resets the streak and resolves the incident; listeners get "RECOVERED". The downtime shown (5 rounds in this run) is the incident's failure count.

## 6. Module by module

### 6.1 Exceptions (`exception`)

```
MonitorException (base, checked)
 |-- ServiceDownException        a service failed outright (connection refused, HTTP 503)
 |-- ServiceTimeoutException     a service did not answer in time
 |-- InvalidServiceException     bad input when creating or looking up a service
 '-- StorageException            a file could not be read or written (keeps the IOException as its cause)
```

| Decision | Why |
|---|---|
| All five are **checked** exceptions under one base class | A failing service or a missing file is an expected event, not a bug, so the compiler should force callers to deal with it. One `catch (MonitorException)` can handle everything. |
| `IllegalArgumentException` (unchecked) for contract violations: a negative response time, a blank id passed to `HealthResult`, a threshold below 1 | These are programming errors, not conditions a caller can recover from. The rule: checked for expected, recoverable situations and user input; unchecked for violated contracts. |
| `StorageException` keeps the original `IOException` as its **cause** | The stack trace stays useful. |
| `serialVersionUID` added to each | Exceptions are `Serializable`; this keeps the build free of `-Xlint:all` warnings. |

### 6.2 Services (`model`)

**`ServiceStatus`** is `UP`, `DEGRADED` or `DOWN`. **`IncidentStatus`** is `OPEN` or `RESOLVED`. Enums rather than strings, so a typo is a compile error.

**`Service`** is abstract. It holds `id`, `name`, `failureRate` (0.0 to 1.0), `status`, a `forceFailure` demo switch and a `Random`. Its key method is a **template method**:

```java
public final int simulateRequest() throws ServiceDownException, ServiceTimeoutException {
    if (forceFailure) {
        throw new ServiceDownException(name + " is forced down (demo mode)");
    }
    return doSimulateRequest();
}

protected abstract int doSimulateRequest() throws ServiceDownException, ServiceTimeoutException;
```

Line by line: `simulateRequest()` is what callers use. It is `final`, so no subclass can skip the demo switch. If the switch is on it fails on cue; otherwise it calls `doSimulateRequest()`, the one step each subclass defines. It returns the simulated response time in milliseconds, or throws.

| Decision | Why |
|---|---|
| `simulateRequest()` is a final template method, not simply abstract (the plan had it abstract) | Demo mode is handled once, in the base class. Polymorphism is unchanged: each subclass still behaves differently. It also made test doubles trivial: a stub overrides one method. |
| `Random` is passed into the constructor | Demos and tests can be seeded and repeated. A global seed would be hidden shared state. |
| Helpers `shouldFail()`, `chance()`, `between()`, `coinFlip()` live in `Service` | The random logic is written once, not copied into three subclasses. |
| `id` and `name` are `final` | The id is a map key everywhere; it must never change. |
| Failure-rate validation is `!(rate >= 0.0 && rate <= 1.0)` | Written this way, `NaN` is rejected too (every comparison with `NaN` is false). |
| `setStatus()` is public | The engine (another package) records the verdict. The service never judges its own health, as the module table requires. |

**The three subclasses** behave differently, which is the polymorphism demonstration:

| Class | Failure rate | Normal response | Slow case | Failure style |
|---|---|---|---|---|
| `DatabaseService` | 3% | 20 to 60 ms | 10% of requests take 300 to 800 ms | `ServiceDownException` ("connection refused") |
| `ApiService` | 15% | 50 to 200 ms | 20% spikes of 500 to 1500 ms | Half timeouts (`ServiceTimeoutException`), half HTTP 503 |
| `CacheService` | 5% | 1 to 5 ms | never slow | `ServiceDownException` ("cache node dropped") |

Each has two constructors: one with the default rate, one with a custom rate.

**`ServiceFactory`** builds the right subclass from a name (`database` or `db`, `api`, `cache`; trimmed and case-insensitive). An unknown type throws `InvalidServiceException`, because the type comes from user input.

| Decision | Why |
|---|---|
| Each service gets **its own** `Random`, seeded from one master `Random` | Seed the master and the whole session repeats, while services still do not disturb each other's sequences. |
| The type is validated **before** a seed is drawn | A mistyped type must not change the randomness of later services. A test pins this. |
| `typeNameOf(service)` is the reverse mapping | Saving writes the type name, and loading rebuilds the service through the same factory. |

### 6.3 Health checking (`healthcheck`)

**`HealthResult`** is the record of one check. It is immutable (final class, final fields, no setters), because reports and incidents are built from it later and a history record must never change.

```java
public boolean isSuccess() {
    return status != ServiceStatus.DOWN;
}
```

| Decision | Why |
|---|---|
| The verdict is stored once, as a `ServiceStatus`; `isSuccess()` is derived | A separate success flag could contradict the status ("success but DOWN"). Deriving it makes the contradiction impossible. (The plan listed a success flag; this method provides it.) |
| DEGRADED counts as success | Slow is not down. The incident logic, the uptime formula and the reports all use this same rule. |
| Named factories `up()`, `degraded()`, `down()` plus one public constructor | Call sites read clearly; storage can rebuild a result with its original timestamp. |
| Failed checks record `responseTimeMs = 0` | No response was received. Reports therefore average over successful checks only. |
| Bad arguments throw `IllegalArgumentException` | Contract violations, not recoverable conditions. |

**`HealthChecker`** is the Strategy interface: `HealthResult check(Service service)`. Its contract: it never throws for a service failure. A failure is a normal outcome, returned as a DOWN result, so the engine needs no try/catch.

**`BasicHealthChecker`** gives UP or DOWN and ignores speed. It catches `ServiceDownException | ServiceTimeoutException` explicitly rather than their parent `MonitorException`. If a new failure type is ever added to `simulateRequest()`, the compiler forces a decision instead of silently swallowing it.

**`LatencyHealthChecker`** adds a speed rule on top:

```java
HealthResult result = basic.check(service);
if (result.isSuccess() && result.getResponseTimeMs() > slowThresholdMs) {
    return HealthResult.degraded(service.getId(), result.getResponseTimeMs());
}
return result;
```

It reuses a `BasicHealthChecker` for the call and the failure handling, so that logic exists once. Only a successful answer can be "slow". The comparison is strictly greater: a response of exactly 400 ms is still UP, and 401 ms is DEGRADED (the default threshold is 400 ms). That makes the API's spikes (500 to 1500 ms) DEGRADED while its normal responses (50 to 200 ms) stay UP. A test pins the 400/401 boundary.

**`HealthCheckerFactory`** is static with a private constructor (it holds no state, so there is nothing to instantiate). It accepts `basic` and `latency`, ignoring case and spaces. An unknown name throws `IllegalArgumentException`, because the name comes from code, not from the "add service" form.

### 6.4 Incidents (`incident`)

**`Incident`** is one outage: id (`INC-1`), service id and name, start time, end time, status, failure count, and an optional resolution note.

| Decision | Why |
|---|---|
| Stores the service **id and name as strings**, not a `Service` reference | An incident is a historical record. It must stay valid after the service is removed, and strings save easily as CSV. It also keeps the `incident` package independent of `model.Service`. |
| Mutators `recordFailure()` and `resolve()` are **package-private** | Only `IncidentManager` can change an incident. Notifiers in another package receive it read-only. |
| The public constructor rejects impossible states (OPEN with an end time, RESOLVED without one, end before start) | A corrupted save file cannot create a broken object. |
| `getEndTime()` returns `Optional` | An open incident has no end time; callers must handle that. |
| `failureCount` includes the failures that opened the incident | It equals the "downtime in rounds" shown on recovery. |
| Start time is the check that crossed the threshold; end time is the first successful check | Both come from `HealthResult` timestamps, not the clock, so tests are deterministic. |

**`IncidentListener`** is the Observer interface: `onIncidentOpened(Incident)` and `onIncidentResolved(Incident)`.

**`IncidentManager`** is the Subject and the core rule of the system:

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

int streak = consecutiveFailures.merge(serviceId, 1, Integer::sum);
Incident open = openIncidents.get(serviceId);
if (open != null) {
    open.recordFailure();                                // already open: just count it
} else if (streak >= threshold) {
    Incident incident = new Incident("INC-" + nextIncidentNumber++, serviceId,
            displayName, result.getTimestamp(), streak);
    openIncidents.put(serviceId, incident);
    history.add(incident);
    notifyOpened(incident);
}
```

Read it as two branches. **Success:** forget the failure streak, and if an incident was open for this service, resolve it and notify. **Failure:** add one to the streak. If an incident is already open, just count the failure (so one outage gives exactly one OPENED and one RESOLVED alert, no alert storm). Otherwise, if the streak has reached the threshold, open a new incident.

| Decision | Why |
|---|---|
| `streak >= threshold`, not `==` | If you lower the threshold in the menu in the middle of a streak, `==` could be skipped over and never fire. |
| Streak map **removes** the entry on success | The map holds only services that are currently failing, so it cannot grow without bound. |
| At most one open incident per service (`Map<String,Incident>`) | Prevents duplicate incidents and alert storms. |
| State is updated **before** listeners are notified | A listener that inspects the manager always sees the new state. |
| Each listener call has its own `try/catch (RuntimeException)`, and the loop runs over a **copy** of the listener list | One broken alert channel (an unwritable file) must not stop monitoring or starve the other channels, and a listener may unregister during a callback. |
| `recordResult(result, serviceName)` takes the name as an argument | `HealthResult` carries only the id. Passing the name keeps the manager independent of `Service`. |
| `getHistory()` returns an unmodifiable view | Callers can read history but cannot rewrite it. |
| Threshold below 1 throws `IllegalArgumentException` | Contract violation; the menu validates user input before calling. |

Two more methods exist for the engine. `forgetService(id, when, reason)` is used when a service is removed: it clears the streak and closes any open incident with a note, **without** notifying listeners (the service did not recover, so a "RECOVERED" alert would be false; the end time is clamped so it can never precede the start). `restoreHistory(saved)` loads saved incidents at startup: it validates everything first (duplicate ids, two open incidents for one service) and changes nothing on failure, re-activates open incidents (the streak is set to their failure count), and continues numbering after the highest saved `INC-n`.

### 6.5 Alerts (`notification`)

**`Notifier`** is an abstract class that implements `IncidentListener`. It is the second template method in the project:

```java
protected abstract String channelName();
protected abstract void deliver(String message);

@Override
public final void onIncidentOpened(Incident incident) {
    deliver(prefix() + incident.getServiceName() + " is DOWN (" + incident.getId() + ", "
            + incident.getFailureCount() + " consecutive failures, since "
            + TIME.format(incident.getStartTime()) + ")");
}
```

The base class decides **what** the alert says (written once, so every channel agrees); each subclass decides only **how** it is delivered. Notifiers never know how failures are detected.

| Class | Delivery |
|---|---|
| `ConsoleNotifier` | Prints to a `PrintStream` (`System.out` by default; injectable so tests capture output) |
| `FileNotifier` | Appends one timestamped line per alert to a file, creating the folder if needed |
| `EmailNotifier` | Simulated: prints `[EMAIL sent to ops@example.com] ...` |

| Decision | Why |
|---|---|
| `Notifier` is an abstract class, not just an interface | Shared message wording is written once. The manager still depends only on the `IncidentListener` interface. |
| `onIncidentOpened/Resolved` are `final` | Subclasses customise delivery, not the alert logic. |
| Streams and file paths are injected | Tests capture output and use temp files; nothing is hard-wired to `System.out`. |
| `FileNotifier` throws `UncheckedIOException` on a write failure | Listener methods cannot throw checked exceptions. The manager's per-listener catch contains it. (`StorageException` is reserved for the storage module.) |

### 6.6 The engine (`engine`)

**`MonitoringEngine`** is the coordinator and a Singleton. It owns the services (a `LinkedHashMap`, a `HashMap` that remembers insertion order so listings are stable) and every service's result history, and it runs the flow.

```java
HealthResult result = checker.check(service);
service.setStatus(result.getStatus());          // the engine, not the service, records the verdict
results.get(service.getId()).add(result);
...
if (observer != null) {
    int streak = result.isSuccess() ? 0 : incidentManager.getConsecutiveFailures(service.getId()) + 1;
    observer.onCheck(round, service, result, streak);
}
incidentManager.recordResult(result, service.getName());
```

Read it as: check, record the verdict, store the result, tell the observer (the menu prints the round line), and only then give the result to the incident logic. The engine contains no health or incident rules; it only connects modules through interfaces.

| Decision | Why |
|---|---|
| Lazy, synchronized Singleton with a **package-private constructor taking all dependencies** | One coordinator in production (`getInstance()`), while tests build engines with their own collaborators. The pattern is kept without making the engine untestable. |
| `initialize(seed)` creates a seeded demo engine and throws if one already exists | A seed must be set before first use; failing loudly beats silently ignoring it. |
| `CheckObserver` callback runs after the result is recorded but **before** the incident logic | The round line appears before the alert it triggers, as in the sample output. The streak passed to it adds the current failure by hand because the manager has not counted it yet. A test pins the ordering. |
| `addService(Service)` is public alongside `addService(type, id, name)` | The file loader hands over ready-made services, and tests inject controlled ones. |
| `removeService` closes the open incident with a note and clears the streak, with no RECOVERED alert | The service did not recover; nothing should stay open for a service that no longer exists. Stored results are dropped so a reused id starts clean. |
| The engine logs incidents by registering an `IncidentListener` | The log is just another observer; no new mechanism was needed. |

**`save()`** writes services, all results and the incident history through the `Storage` interface. **`load()`** is all-or-nothing: it rebuilds services in a local map, restores incidents (validated first), and only then commits. Open incidents of services that no longer exist are closed with a note; results of unknown services are ignored. What is deliberately not saved: demo force-failure switches (a demo toggle), the threshold (a setting) and the round counter (it belongs to one session).

### 6.7 The console (`console`, `Main`)

**`ConsoleMenu`** reads input, prints output and calls the engine. Its streams are injected, so the tests script entire sessions (20 menu tests).

| Decision | Why |
|---|---|
| A new `console` package (not in the plan's list) | Keeps `Main` thin and the menu testable. |
| End of input (Ctrl+D or Ctrl+Z) exits cleanly through a private exception | A normal way to leave must not print a stack trace. |
| Round count limited to 1 to 1000; threshold and numbers validated | A typo must not flood the screen or crash the app. |
| Saves after add, remove and each check round, and on exit; a failed save prints a warning and the program carries on | A crash loses at most the current action. Read-only actions never save. |
| Option 9 toggles a service's `forceFailure` switch | Makes a failure happen on cue for the demo, with no special case in the engine. |

**`Main`** only parses `--seed N`, picks the engine, loads saved data, and starts the menu. If a data file is corrupt it prints the file and line and exits with code 1, because starting empty would let the next save overwrite the evidence.

### 6.8 Reports (`report`)

**`ReportGenerator`** is a final class of pure static functions. It receives plain lists, so it knows nothing about the engine or files, and it cannot modify anything.

```java
public static List<HealthResult> recent(List<HealthResult> results, int n) {
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

This is a sliding window: one pass that never holds more than `n` items and drops the oldest from the front in constant time, which is why an `ArrayDeque` fits. It feeds the "recent uptime" figure (the last 10 checks).

**`ServiceReport`** is a Java **record**, an immutable bundle of values with no behaviour worth hiding. Values that may not exist yet are `Optional`, so "0% uptime" can never be confused with "no data".

| Statistic | Rule |
|---|---|
| Uptime % | successful checks / total checks x 100, where DEGRADED counts as successful |
| Recent uptime % | the same, over the last 10 checks, so it shows current health while overall uptime shows history |
| Average and slowest response | over **successful** checks only (a failed check stores 0 ms and would drag the average down) |
| Incident count, downtime rounds | counted from the incident history for that service |

Ranking uses `Comparator`s: by uptime (best first), by average response time (fastest first) or by name (case-insensitive). Services with no data always sort last, name and id break ties so the order is total, and `sort()` returns a new list and leaves its input alone.

### 6.9 Storage and logging (`storage`)

**`Storage`** is an interface (`save(StorageData)`, `load()`), so the engine never knows about CSV and tests use an in-memory version. **`StorageData`** and **`ServiceRecord`** are records: saved data is plain values, not live objects (a `Service` carries a `Random` and demo switches), and the engine rebuilds real services through `ServiceFactory`.

**`FileStorage`** writes three CSV files:

| Decision | Why |
|---|---|
| Hand-written `Csv` helper with quoting (a field with a comma or quote is wrapped in quotes, and inner quotes doubled) | Names like "Payments, EU" would be silently corrupted by splitting on commas. Line breaks are replaced by spaces because files are read line by line. |
| Each file is written to a `.tmp` file and then **moved** over the real one (atomic move, with a fallback) | A crash during a write can never leave a truncated data file; the old file stays intact until the new one is complete. |
| A missing file means "nothing saved yet", not an error | The first run must start cleanly. |
| Parse errors name the file and line, e.g. `results.csv line 3: ...` | The user can find and fix the bad line. Validation errors from `Incident` and `HealthResult` are wrapped the same way. |
| A header line is checked | Catches the wrong file or an old format immediately. |

**`AppLogger`** is the second Singleton: one application-wide log (`data/monitor.log`) with timestamped `INFO`, `WARN` and `ERROR` lines. Its log methods never throw: if the file cannot be written, one warning goes to stderr and the app carries on, because logging must never break monitoring. `setLogFile()` lets tests redirect it.

## 7. Design patterns

| Pattern | Where | The 30-second explanation |
|---|---|---|
| **Singleton** | `MonitoringEngine`, `AppLogger` | There must be exactly one coordinator and one log, or two parts of the program would hold different states. `AppLogger` creates its single instance when the class loads (thread-safe, private constructor). The engine creates it lazily in a synchronized `getInstance()`. |
| **Factory** | `ServiceFactory`, `HealthCheckerFactory` | Callers ask for `"api"` or `"latency"` and get the right object without knowing the concrete class. Adding a service type means touching only the factory. |
| **Strategy** | `HealthChecker` with `BasicHealthChecker` and `LatencyHealthChecker` | "How do we judge a service?" is a swappable algorithm behind an interface. The engine never changes when a new checker is added. |
| **Observer** | `IncidentManager` (subject), `IncidentListener`, `Notifier` and subclasses | The manager announces "incident opened or resolved" to whoever registered. Console, file, email and the log are listeners; the manager does not know which exist, and notifiers do not know how incidents are detected. |
| **Template Method** (extra) | `Service.simulateRequest()` and `Notifier.onIncident...()` | A fixed skeleton in the base class (demo switch, alert wording) with one varying step in subclasses. |

**What is not built, and why.** The plan lists a `RetryingHealthChecker` (Decorator) and an Adapter as optional, and says to finish the required four first. They were left out: nothing in the menu could select a decorator, so it would be unreachable code. Be ready to say how it would work: `RetryingHealthChecker implements HealthChecker`, holds another `HealthChecker`, and calls it up to N times until one succeeds. Because it is itself a `HealthChecker`, it plugs in anywhere without changing the engine.

**Why patterns here are justified, not decorative.** Each one removes a concrete problem: Observer lets a failing email channel not affect the file channel, Strategy let the latency rule be added in Phase 2 without touching anything else, Factory keeps the menu ignorant of three subclasses, Singleton gives one place to wire everything.

## 8. OOP principles and SOLID, with examples from this code

| Principle | Example |
|---|---|
| **Abstraction** | `Service` hides how latency and failure are simulated behind `simulateRequest()`. `HealthChecker`, `Storage` and `IncidentListener` hide whole modules behind one or two methods. |
| **Encapsulation** | Private fields with validation (`failureRate`), `final` id, immutable `HealthResult`, package-private `Incident` mutators so only the manager can change an incident, read-only views from `getHistory()` and `getServices()`. |
| **Inheritance** | `DatabaseService`, `ApiService`, `CacheService` extend `Service`; `ConsoleNotifier`, `FileNotifier`, `EmailNotifier` extend `Notifier`; exceptions extend `MonitorException`. |
| **Polymorphism** | The engine calls `checker.check(service)` and `service.simulateRequest()` without knowing which concrete class runs. One loop, three behaviours. |
| **Single responsibility** | `IncidentManager` decides incidents; `Notifier` delivers; `ReportGenerator` computes; `FileStorage` converts to text. |
| **Open/closed** | Add a notifier, a checker or a service type without editing `IncidentManager`, the engine or the existing classes (only the relevant factory). |
| **Liskov substitution** | Any `HealthChecker` or `Notifier` can replace another; the tests swap in stubs and in-memory fakes freely. |
| **Interface segregation** | Small interfaces: `IncidentListener` has two methods, `HealthChecker` one, `Storage` two. |
| **Dependency inversion** | The engine depends on `HealthChecker`, `IncidentListener` and `Storage` (abstractions), not on concrete checkers, notifiers or CSV files. |

Composition appears too: `LatencyHealthChecker` **has a** `BasicHealthChecker` and reuses it instead of inheriting from it.

## 9. Collections and exceptions

| Need | Collection | Where and why |
|---|---|---|
| Services by id | `LinkedHashMap<String, Service>` | Fast lookup by id and stable insertion order for listings |
| Check history per service | `Map<String, List<HealthResult>>` with `ArrayList` | Append-only history, read in order |
| Consecutive failures per service | `HashMap<String, Integer>` | Counter per id; the entry is removed on success so the map stays small |
| Open incidents per service | `HashMap<String, Incident>` | At most one open incident per service, O(1) lookup |
| Incident history | `ArrayList<Incident>` | Insertion order is chronological order |
| Listeners | `ArrayList<IncidentListener>` | Iterated over a copy so a listener can unregister safely |
| Last N results | `ArrayDeque` (sliding window) | Constant-time drop of the oldest entry |
| Ranking | `Comparator` chains with `thenComparing` | Total, stable order with tie-breakers |
| Defensive copies | `List.copyOf`, `Collections.unmodifiableList` | Callers cannot change internal state |

| Exception | Thrown when | Handled by |
|---|---|---|
| `ServiceDownException`, `ServiceTimeoutException` | A simulated request fails | `BasicHealthChecker`, which turns them into a DOWN result |
| `InvalidServiceException` | Bad id, name, rate or type; duplicate or unknown service id | The menu, which prints the message |
| `StorageException` | A file cannot be read, written or parsed | `Main` at startup (exit with a message) and the menu on save (warning) |
| `IllegalArgumentException` / `IllegalStateException` | Violated contracts (negative time, threshold below 1, loading into a non-empty engine) | Not caught: they signal a bug |

## 10. Testing

**154 tests** (JUnit 5) across 10 test classes: `IncidentManagerTest` 34, `MonitoringEngineTest` 29, `ConsoleMenuTest` 20, `HealthCheckerTest` 17, `FileStorageTest` 14, `ReportGeneratorTest` 13, `ServiceFactoryTest` 10, `CsvTest` 7, `NotifierTest` 6, `AppLoggerTest` 4.

- **Test doubles instead of randomness.** A `StubService` has an exact latency or failure, so the 400/401 ms boundary can be tested precisely. A cache with a 0% failure rate plus `forceFailure` makes whole-system tests deterministic. `InMemoryStorage` can be told to fail.
- **Time comes from the data.** Incident times use the `HealthResult` timestamp, so tests assert exact start and end times.
- **Scripted sessions.** `ConsoleMenu` takes injected streams, so tests type whole menu sessions and check the output, including the ordering of the round line before the alert.
- **Mutation checks.** After the tests passed, bugs were injected on purpose (changing `>` to `>=`, never resetting the streak, counting failures in the average, leaving incidents open on removal, unquoted CSV commas, restarting incident numbering, not saving after a run, and more) and each time at least one test failed, then the code was restored. This proves the tests can fail, so a green run means something.
- **End to end.** The real program was run twice as separate processes sharing a data folder: an incident left open in run 1 was loaded and resolved in run 2; a corrupted file made it refuse to start with a clear message.
- **Build hygiene.** `javac -Xlint:all` produces no warnings.

## 11. Decisions that deviate from the original plan

Be upfront about these; each has a reason.

| Plan said | What was built | Why |
|---|---|---|
| `simulateRequest()` abstract | A final template method calling abstract `doSimulateRequest()` | Demo mode handled once; no subclass can bypass it; trivial test stubs |
| `HealthResult` has a success flag | Success derived from the status | A flag could contradict the status |
| `Incident` holds a `Service` | Holds id and name strings | Historical record, CSV-friendly, no dependency on `model.Service` |
| `Notifier` implements `IncidentListener` | An abstract class (template method) | Alert wording written once |
| Package list without `console` | Added `console` | Thin `Main`, testable menu |
| Storage as a `FileStorage` class | `Storage` interface plus `FileStorage` and record types | The engine depends on an abstraction; tests use an in-memory fake |
| `HashMap<String, Service>` | `LinkedHashMap` | Same lookup, stable listing order |
| Save/load "on startup and exit" | Also after each change; corrupt file stops startup | A crash loses at most one action; corrupt data is never overwritten |
| Optional Decorator and Adapter | Not built | Unreachable from the menu; the plan says required patterns first |

## 12. Known limitations (say these before you are asked)

- **Maven is unverified.** The `pom.xml` follows the standard layout, but the project was built and tested with `javac` and the JUnit console launcher. Run `mvn test` once yourself.
- **Results grow without bound and every save rewrites the CSV files.** That is fine for a console demo; a real system would append or use a database (which the scope excludes).
- **Single-threaded.** Checks run one after another; parallel checking was an optional extra and was not built.
- **Email is simulated** by design (it prints "email sent").
- **Not persisted:** force-failure switches, the failure threshold, the round counter.
- **No retry decorator or adapter** (section 7).
- **Times are local** (`LocalDateTime`), with no time-zone handling.
- **Incident ids** continue across sessions, but ids in another shape than `INC-n` are ignored for numbering.
- **Visual Paradigm** cannot import PlantUML; the diagrams are provided as PNG, SVG and PlantUML source and must be redrawn there if the tool is required.

## 13. Viva questions and short answers

1. **Why is `Service` abstract?** A generic "service" has no behaviour of its own; only a database, API or cache does. Abstract forces each type to define `doSimulateRequest()` and lets the engine treat them all as `Service`.
2. **Show polymorphism.** `checker.check(service)` and `service.simulateRequest()`. The same call gives different latency and failure styles per subclass.
3. **Why is `simulateRequest()` final?** It is a template method. The demo `forceFailure` switch lives there once, so no subclass can bypass it.
4. **Why checked exceptions?** A down service or a missing file is expected, so the compiler should force handling. Contract violations use unchecked `IllegalArgumentException`.
5. **Why does `HealthChecker.check` not throw?** A failure is a normal result, returned as DOWN. The engine needs no try/catch and cannot forget one.
6. **How does Observer decouple modules?** The manager knows only the `IncidentListener` interface. Adding a Slack notifier is one new class and one `addListener` line.
7. **What if a notifier throws?** The manager catches runtime exceptions per listener, so the other channels and monitoring continue. A test covers this.
8. **Why `streak >= threshold`?** If the threshold is lowered mid-streak, `==` could be skipped and never fire.
9. **Why does DEGRADED reset the streak?** Slow is not down: the service answered. It also resolves an open incident. The same rule is used by uptime and reports.
10. **How is Singleton testable here?** Production code uses `getInstance()`, but the constructor is package-private and takes its dependencies, so tests build their own engine.
11. **Why inject `Random`?** Seeded demos and deterministic tests, with no hidden global state.
12. **Factory versus Strategy?** Factory decides *which object to create*; Strategy is *which algorithm runs*. Here the factory creates the strategy.
13. **Why is `HealthResult` immutable?** It is a history record; reports and incidents depend on it never changing.
14. **Why `LinkedHashMap`?** Lookup by id plus stable insertion order for listings.
15. **Why an `ArrayDeque` for recent results?** A sliding window that drops the oldest entry in constant time and never holds more than N items.
16. **How is data protected from a crash?** Each file is written to a temporary file and moved over the real one, so a half-written file cannot replace a good one.
17. **Why records?** `ServiceReport`, `StorageData` and `ServiceRecord` are pure immutable value carriers, and a record is the shortest correct way to write one.
18. **Why does the engine, not the service, set the status?** The brief says a service models behaviour but does not decide whether it is healthy. The checker judges; the engine records.
19. **What happens to an open incident when its service is removed?** It is closed with a note, the streak is cleared, and no RECOVERED alert is sent, because the service did not recover.
20. **How did you test it?** Test doubles for exact control, scripted menu sessions, an end-to-end two-process run, and mutation checks that prove the tests can fail.
21. **What would you add next?** The retry Decorator, parallel checks, an Adapter for a third-party notifier, and incident severity levels.
22. **Which SOLID principle is clearest here?** Dependency inversion: the engine depends on `HealthChecker`, `IncidentListener` and `Storage`, never on concrete classes.

## 14. Presentation script (about 5 minutes)

| Step | Do | Say |
|---|---|---|
| 1. Frame it | Show the module table (section 3) | "Seven modules, each with one job and a 'does not do' list. They talk through three interfaces." |
| 2. Start | `java -cp out Main --seed 42`, option 1, add `api`, `cache`, `database` | "The factory builds the right subclass from a name." |
| 3. Normal | Option 4 a couple of times; option 3 | "Each service behaves differently: that is polymorphism." |
| 4. Fail on cue | Option 9 on the API, option 5 with 4 rounds | "Demo mode is a flag on the service. Watch the streak: 1, 2, 3, and at the threshold an incident opens and three channels alert. That is the Observer pattern." |
| 5. One outage, one alert | Point at round 4 | "The fourth failure only counts; no alert storm." |
| 6. Recover | Option 9 again, option 4 | "The first success resolves the incident." |
| 7. Reports | Option 7, try ranking by uptime then name | "Uptime counts degraded as success. Response times ignore failures." |
| 8. Persistence | Exit, start again without `--seed` after a normal run, or show the CSV files | "Atomic writes; a corrupt file stops the program instead of being overwritten." |
| 9. Proof | Show `docs/uml` and run `mvn test` | "154 tests, and mutation checks prove they can fail." |

## 15. Glossary

| Term | Meaning here |
|---|---|
| **Streak** | The count of consecutive failed checks for one service |
| **Threshold** | The streak length that opens an incident (default 3, configurable) |
| **Incident** | One outage of one service: opened at the threshold, resolved at the first success |
| **DEGRADED** | The service answered, but slower than the latency threshold (400 ms) |
| **Round** | One pass of checks over all services |
| **Demo mode** | `--seed N`: reproducible randomness, empty start, separate data folder; plus the option 9 force-failure switch |
| **Template method** | A base-class method that fixes the steps and lets subclasses fill one in |
| **Record** | A Java class for an immutable bundle of values (`ServiceReport`, `StorageData`, `ServiceRecord`) |
| **Test double** | A stand-in used in tests (a stub service, an in-memory storage) |
| **Mutation check** | Deliberately breaking the code to confirm that tests fail |
| **Atomic write** | Writing a temporary file and moving it into place so a crash cannot leave a half-written file |
