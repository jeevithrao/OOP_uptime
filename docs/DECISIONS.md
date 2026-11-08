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
