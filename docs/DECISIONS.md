# Design Decisions Log

Every non-obvious choice, with the reason. Useful for the viva.

## Phase 0: Project skeleton
| Decision | Why |
|---|---|
| Maven, Java 17 (`release=17`) | JUnit comes from one dependency line; `release` stops accidental use of APIs newer than 17. |
| `src/main/java` and `src/test/java` | Maven's standard layout, so any IDE or grader opens it without configuration. |
| `.gitkeep` in empty packages | Git does not track empty folders; the structure must be visible from the first commit. |
| `.gitignore` for `target/`, logs, `data/` | Build output and runtime files are generated, not source. |

## Phase 1: Foundation
| Decision | Why |
|---|---|
| All exceptions extend one checked `MonitorException` | One `catch (MonitorException)` handles everything; checked forces callers to handle expected failures. |
| `simulateRequest()` is a final template method; subclasses implement `doSimulateRequest()` | `forceFailure` is handled once in the base class, so no subclass can bypass demo mode. Polymorphism is unchanged. (Deviation from the plan, which had `simulateRequest()` abstract.) |
| `Random` injected through the constructor | Seeded demos and deterministic tests, with no global state. |
| Shared helpers `shouldFail()`, `chance()`, `between()` in `Service` | Random logic is written once instead of copied into three subclasses. |
| `id` and `name` are `final` | `id` is a map key everywhere, so it must never change; fewer mutable fields means fewer bugs. |
| Validation rejects NaN as well as out-of-range rates | `!(rate >= 0 && rate <= 1)` is false-safe: every comparison with NaN is false. |
| `setStatus()` is public | The engine (another package) sets it from health results. The service never decides its own health. |
| Each service type has two constructors (default rate, custom rate) | Factory can create sensible defaults; tests can set exact rates. |
| `Main` is temporary | It only proves Phase 1 works; Phase 4 replaces it with the real menu. |

## Phase 2: Health checking
| Decision | Why |
|---|---|
| `HealthResult` is immutable (`final` class, `final` fields, no setters) | It is a historical record. Reports and incidents read it later, so nothing may alter it. |
| Verdict stored once as `ServiceStatus`; `isSuccess()` is derived (`status != DOWN`) | A separate success flag could contradict the status. Deriving it makes the contradiction impossible. (Plan listed a "success flag"; the method provides it.) |
| DEGRADED counts as success | Slow is not down. Incidents track consecutive real failures; slowness is reported separately. |
| Named factories `up()`, `degraded()`, `down()` plus one public constructor | Call sites read clearly; the public constructor lets storage rebuild results with their original timestamp. |
| Failed checks record `responseTimeMs = 0` | No response was received. Phase 5 reports must average response time over successful checks only. |
| Bad `HealthResult` arguments throw `IllegalArgumentException` (unchecked) | They are programming errors, not user input. Checked exceptions are reserved for things a caller can recover from. |
| `HealthChecker.check()` never throws for a service failure | A failure is a normal outcome, returned as a DOWN result. The engine needs no try/catch. |
| `BasicHealthChecker` catches the two exception types explicitly, not `MonitorException` | If a new failure type is added later, the compiler forces a decision instead of silently swallowing it. |
| `LatencyHealthChecker` reuses a `BasicHealthChecker` internally | The call and failure handling are written once. Only the speed rule is new. |
| Degraded rule is strictly `> threshold`, default 400 ms | A response exactly at the limit is acceptable. 400 ms makes API spikes (500 to 1500) DEGRADED while normal API responses (50 to 200) stay UP. |
| `HealthCheckerFactory` is static with a private constructor | It holds no state, so there is nothing to instantiate. Input is trimmed and case-insensitive. |
| Unknown checker type throws `IllegalArgumentException` | The type comes from code or config, not from the "add service" form, so `InvalidServiceException` would be the wrong meaning. |
| Tests use a `StubService` with controlled behaviour | Boundary rules (400 vs 401 ms) need exact latencies. The Phase 1 template-method design makes a stub trivial to write. |
| Mutation check done: changing `>` to `>=` fails the boundary test | Confirms the test can actually fail, so a pass means something. |

## Phase 3: Incidents and alerts
| Decision | Why |
|---|---|
| Incident opens when the failure streak is `>= threshold` (default 3), not `== threshold` | If the threshold is lowered mid-streak (menu option 8), `==` could be skipped over and never fire. |
| DEGRADED resets the streak and resolves an open incident | It is a success (`HealthResult.isSuccess()`). Slow is not down. |
| Streak stored in `Map<String,Integer>`; a success *removes* the entry | The map holds only services currently failing, so it cannot grow without bound. |
| At most one open incident per service (`Map<String,Incident>`), further failures only increment `failureCount` | Prevents alert storms: one outage, one OPENED alert, one RESOLVED alert. |
| `failureCount` includes the failures that opened the incident | It equals the "downtime in rounds" shown on recovery. |
| Incident start time = timestamp of the check that crossed the threshold; end time = first successful check | Taken from the `HealthResult`, not the clock, so tests are deterministic and the times match the checks. |
| Incident stores `serviceId` and `serviceName` as strings, not a `Service` reference | It is a historical record: it stays valid after the service is removed, and saves easily as CSV. It also keeps the `incident` package independent of `Service`. |
| `Incident` mutators are package-private; the constructor that restores saved data is public | Listeners (other packages) get a read-only view. Only `IncidentManager` can change state. Storage can still rebuild incidents. |
| `Incident` constructor rejects impossible states (OPEN with an end time, RESOLVED without one, end before start) | A corrupted save file cannot produce a broken object. |
| `getEndTime()` returns `Optional` | An open incident has no end time; `Optional` forces callers to handle that. |
| `recordResult(result, serviceName)` takes the name as an argument | `HealthResult` only has the id. Passing the name keeps `IncidentManager` independent of `Service`. (A resolver hook was tried first and removed: more moving parts for no benefit.) |
| State is updated BEFORE listeners are notified | A listener that inspects the manager always sees the new state. |
| Each listener call is wrapped in `try/catch (RuntimeException)`; the manager iterates over a copy of the list | One broken channel (e.g. unwritable file) must not stop monitoring or starve the other channels, and a listener may unregister itself during a callback. |
| `getHistory()` returns an unmodifiable view | Callers can read history but not rewrite it. |
| `Notifier` is an abstract class (template method), not just an interface | Alert wording is written once so all channels agree. Subclasses implement only `deliver()`. The manager still depends only on the `IncidentListener` interface. |
| Notifier output streams and the file path are injected | Tests capture output and use temp files; nothing is hard-wired to `System.out`. |
| `FileNotifier` throws `UncheckedIOException` | Listener methods cannot throw checked exceptions. The manager's per-listener catch handles it. (`StorageException` is reserved for the storage module.) |
| Threshold / null arguments throw `IllegalArgumentException` | They are programming-contract violations. The Phase 4 menu validates user input before calling. |
| 28 new tests, plus 4 mutation checks (no streak reset, `>` vs `>=`, never removing the open incident, uncaught listener errors) | Each deliberate bug made tests fail, proving the suite detects real mistakes. |

### Notes carried into Phase 4
- The engine should print a check's result line **before** calling `recordResult`, so the alert appears after the round line, as in the sample output.
- When a service is removed (menu option 2), the engine must decide what happens to its streak and any open incident. `IncidentManager` has no `forget(serviceId)` yet.
- Incident ids restart at `INC-1` on each run. Phase 5 must continue numbering after loading saved incidents.

## Phase 4: Engine, factory, logger and menu
| Decision | Why |
|---|---|
| `MonitoringEngine` is a lazy, synchronized Singleton, but its constructor is package-private and takes all dependencies | One coordinator in production; tests build engines with their own collaborators. The pattern is kept without making the engine untestable. |
| `initialize(long seed)` creates the engine in demo mode, and throws if the engine already exists | Seeded randomness must be set up before first use; failing loudly beats silently ignoring the seed. |
| Default wiring (console + file + simulated email alerts, latency checker) lives in `createDefault()` | One place to see how the modules are connected. |
| The engine only connects modules: check -> record verdict -> incident logic | It holds no health or incident rules, as the module table requires. |
| `ServiceFactory` gives every service its own `Random`, seeded from one master `Random` | Seed the master and the whole session repeats; services still do not disturb each other's sequences. |
| `ServiceFactory` validates the type BEFORE drawing a seed | A mistyped type must not change the randomness of later services. A test pins this. |
| Type names `database` / `db`, `api`, `cache`; unknown type throws `InvalidServiceException` | The type comes from user input, so it uses the checked exception meant for bad input. |
| `AppLogger` is an eagerly created Singleton; its log methods never throw | Logging must not be able to break monitoring. An unwritable file produces one stderr warning, then the app continues. |
| `AppLogger.setLogFile()` exists | Tests (and a future config) can redirect the log; without it the singleton would write into the project during tests. |
| The engine logs incidents by registering an `IncidentListener` | The log is just another observer. It needed no new mechanism, and the manager stays unaware of it. |
| `LinkedHashMap` for services | A `HashMap` that remembers insertion order, so listings are stable. |
| Engine keeps every `HealthResult` per service in a `List`, exposed read-only | Phase 5 reports read this; callers cannot rewrite history. Dropped when the service is removed so a reused id starts clean. |
| `addService(Service)` is public alongside `addService(type, id, name)` | The file loader (Phase 5) will hand over ready-made services, and tests inject controlled ones. |
| `CheckObserver` callback runs after the result is recorded but BEFORE the incident logic | The menu prints "[Round 4] ... FAIL" before the alert it triggers, as in the sample output. The streak it receives adds the current failure by hand, because the manager has not counted it yet. A test pins the ordering. |
| Removing a service with an open incident: close it with the note "Service removed while incident was open", clear the streak, send NO recovered alert | The service did not recover, so a RECOVERED alert would be false; nothing should stay open for a service that no longer exists. `Incident` gained a `resolutionNote` for this. |
| `IncidentManager.forgetService()` clamps the end time to the start time if needed | A clock quirk can never produce an incident that ends before it starts. |
| Menu lives in a new `console` package, with injected input and output streams | Keeps `Main` thin and lets tests script whole sessions (12 menu tests). Deviation from the plan's package list. |
| End of input (Ctrl+D / Ctrl+Z) exits cleanly through a private exception | No stack trace for a normal way to leave. |
| Round count limited to 1 to 1000 | A mistyped huge number would flood the screen. |
| Option 7 (reports) prints "not implemented yet" | Reports are Phase 5. Saying so is more honest than faking output. |
| `Main` only parses `--seed N` and starts the menu | The plan's tip: if the menu contains business logic, the modules are not independent. |
| 48 new tests plus 5 mutation checks (verdict not recorded, streak off by one, incident left open on removal, seed drawn too early, alert before round line) | Each injected bug made tests fail, so a green suite means something. |

### Notes carried into Phase 5
- `ReportGenerator` should average response time over successful results only (failed checks record 0 ms).
- Loading saved incidents must continue numbering after the highest saved `INC-n`; `IncidentManager` has no way to restore history yet.
- `Incident` has a public constructor with `resolutionNote`, ready for CSV loading.

## Phase 5: Reports and storage
| Decision | Why |
|---|---|
| `ReportGenerator` is a final class of pure static functions over plain lists | The module table says Report must not modify data and must stay independent: it knows nothing about the engine, files or console. |
| `ServiceReport` is a Java record with `Optional` fields for "may not exist" values | A record is an immutable value carrier in one declaration. `Optional` makes "no data" different from "0%" (a service never checked is not a service that always fails). |
| Uptime % = successful / total x 100, where DEGRADED counts as successful | Same rule as `HealthResult.isSuccess()` and the incident logic, so all three modules agree. |
| Average and slowest response time use successful checks only | Failed checks store 0 ms; counting them would make a failing service look fast. A test pins this. |
| "Recent uptime" over the last 10 checks, computed with an `ArrayDeque` sliding window | Overall uptime hides a recovery (or a collapse); the recent figure shows current health. The deque holds at most N items and drops the oldest in O(1), which is why it was chosen over a list. |
| Ranking by `Comparator` (uptime, response time, name), with services lacking data always last and name/id as tie-breakers | Gives a total, stable order; `sort()` returns a new list and leaves the input alone. |
| The engine depends on a `Storage` interface; `FileStorage` implements it | The engine never knows about CSV, and tests use an in-memory version that can be told to fail. |
| Saved data is plain records (`ServiceRecord`, `StorageData`), not live objects | A `Service` carries a `Random` and demo switches. The engine rebuilds real services through `ServiceFactory`. |
| Hand-written `Csv` helper with quoting | Names and error messages contain commas and quotes ("Payments, EU"); splitting on commas would corrupt them silently. Line breaks are replaced by spaces because files are read line by line. |
| Files are written to a `.tmp` file, then moved over the real one (atomic move, with a fallback) | A crash mid-write cannot leave a truncated data file; the old file stays intact until the new one is complete. |
| A missing file means "nothing saved yet", not an error | The first run must start cleanly. |
| Parse errors name the file and line; `Incident`/`HealthResult` validation errors are wrapped the same way | The user can find and fix the bad line. Domain classes keep their own rules, so storage contains none. |
| `Engine.load()` is all-or-nothing (build and validate first, commit last) and refuses a non-empty engine | Bad data must never leave a half-loaded engine. `restoreHistory()` also validates before changing anything. |
| A corrupt file stops the program at startup instead of starting empty | The next save would overwrite the corrupt file. Stopping keeps the evidence. |
| Open incidents are restored as open, with the streak set to their failure count | An outage that was ongoing when the program stopped is still ongoing; the next success resolves it. |
| An open incident whose service is missing is closed with the note "Service missing when saved data was loaded" | It can never recover, so it must not stay open forever. |
| Results of unknown services are ignored (and logged) | They can only come from a partial save or a hand-edited file; they cannot be attached to anything. |
| Incident numbering continues after the highest saved `INC-n` | Ids stay unique across sessions. |
| Not saved: force-failure switches, threshold, round counter | The first is a demo switch, the second is a setting, the third belongs to one session. Saving them would add format and migration cost for little value. |
| Menu saves after add, remove and each check round, and on exit (including end of input); a failed save prints a warning and the program carries on | A crash loses at most the current action. Read-only actions (list, view, reports, threshold, force) do not save. |
| Demo mode (`--seed`) starts empty and uses `data-demo/` | Demos are reproducible and can never overwrite real data. |
| 19 new tests plus 7 mutation checks (average includes failures, uptime ignores degraded, ranking reversed, CSV unquoted, open incidents of missing services kept, numbering restarts, no save after a run) | Each injected bug made tests fail. |
| End-to-end check with two real processes sharing a `data/` folder, then a corrupted file | Proves the data really survives a restart, beyond what unit tests with in-memory fakes can show. |
