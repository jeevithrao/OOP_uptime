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
