# Service Health Monitoring and Incident Management System

A console-based Java application that monitors simulated services, detects failures, manages
incidents, sends alerts, and produces reports. The focus is **OOP design**: independent modules,
interfaces, inheritance, polymorphism, design patterns and UML.

Each simulated service (database, API, cache) misbehaves on purpose. The system checks them
repeatedly, decides when something is truly broken (N failures in a row), opens an incident,
alerts through three channels, records history, and reports uptime and response times.

## Requirements
- JDK 17 or newer (`java -version`, `javac -version`)
- Maven 3.8+ is optional (for `mvn test`)

## Build and run
```bash
mkdir out
javac --release 17 -d out $(find src/main/java -name "*.java")     # Linux / macOS
java -cp out Main
```
Windows PowerShell:
```powershell
mkdir out
javac --release 17 -d out (Get-ChildItem -Recurse src/main/java -Filter *.java).FullName
java -cp out Main
```
Demo mode: `java -cp out Main --seed 42` gives reproducible randomness and a clean start. It uses its
own `data-demo/` folder, so it never touches real saved data.

## Demo script (about 2 minutes)
1. **1** Add service: type `api`, id `api-1`, name `Payments API`. Add a `cache` and a `database` too.
2. **9** Force `api-1` to fail (demo mode).
3. **5** Run 4 rounds. At the third consecutive failure the incident opens and three alerts fire.
4. **6** View incidents (1 open).
5. **9** Toggle `api-1` back to normal, **4** run one round: the incident resolves ("RECOVERED").
6. **7** View reports (uptime, response times, ranking), then **0** to exit.
7. Start again: your services and incident history are back.

## Menu
```
1. Add service                    6. View incidents
2. Remove service                 7. View reports
3. List services and status       8. Configure failure threshold
4. Run one check round            9. Force a service to fail (demo mode)
5. Run monitoring (N rounds)      0. Exit
```

## Saved data
Everything is saved automatically after each change (add, remove, check rounds) and on exit, as
CSV files in `data/`: `services.csv`, `results.csv`, `incidents.csv`. They are loaded at startup, so
an incident that was open when you quit is still open (and resolves when the service recovers).
If a file is corrupt the program refuses to start and names the file and line, so nothing is
overwritten; fix or delete the file. Not saved on purpose: demo force-failure switches, the failure
threshold, and round numbers. Logs: `data/monitor.log` (everything), `data/alerts.log` (alerts).

## Package structure (`src/main/java`)
| Package | Responsibility |
|---|---|
| `model` | Services and their simulated behaviour, `ServiceFactory`, status enums |
| `healthcheck` | Probing a service and judging the result (Strategy) |
| `incident` | Failure streaks, opening and resolving incidents (Observer subject) |
| `notification` | Delivering alerts through console, file and simulated email |
| `report` | Uptime, response-time statistics and rankings (read-only) |
| `storage` | `Storage` interface, CSV `FileStorage`, `AppLogger` |
| `exception` | Custom checked exceptions rooted at `MonitorException` |
| `engine` | `MonitoringEngine` (Singleton): coordinates every module |
| `console` | The text menu: input and output only, no rules |
| *(default)* | `Main`: starts the menu |

## Design patterns
| Pattern | Where | Why |
|---|---|---|
| Singleton | `MonitoringEngine`, `AppLogger` | One coordinator, one log |
| Factory | `ServiceFactory`, `HealthCheckerFactory` | Create the right object from a type name |
| Strategy | `HealthChecker` with `BasicHealthChecker` and `LatencyHealthChecker` | Swap checking logic without touching the engine |
| Observer | `IncidentManager` (subject) and `IncidentListener` / `Notifier` | Alerts react to incidents without coupling |
| Template Method (extra) | `Service.simulateRequest()`, `Notifier.onIncident...()` | Fixed skeleton in the base class, varying step in subclasses |

## How this matches the faculty's 8 points
| Point | Where |
|---|---|
| 1. Independent modules with clear responsibilities | The packages above; each module's "does not do" list is respected |
| 2. Module interaction | Engine flow; `HealthChecker`, `IncidentListener` and `Storage` interfaces; Observer |
| 3. Core OOP principles | `Service` hierarchy (abstraction, inheritance, polymorphism), private state with validation (encapsulation) |
| 4. Class hierarchies and relationships | [Class diagrams](docs/uml/) |
| 5. Collections and exceptions | `HashMap`, `LinkedHashMap`, `ArrayList`, `ArrayDeque`, `Comparator`; five custom checked exceptions |
| 6. Design patterns | Table above |
| 7. Components working together | `MonitoringEngine`, demo mode, scripted end-to-end tests |
| 8. UML diagrams | [docs/uml](docs/uml/): use case, two class diagrams, sequence, activity |

## UML diagrams (`docs/uml`, PlantUML source plus PNG and SVG)
| Diagram | File |
|---|---|
| Use case | `use-case` |
| Class (core: services, checking, incidents, alerts, exceptions) | `class-diagram` |
| Class (supporting: engine, console, reports, storage) | `class-diagram-supporting` |
| Sequence (a check that fails for the third time) | `sequence-third-failure` |
| Activity (the check cycle) | `activity-check-cycle` |

Render again with `plantuml -tpng docs/uml/*.puml`.

## Tests
154 JUnit 5 tests (`src/test/java`): `mvn test`. They cover the incident rules (exact threshold,
streak reset, listener isolation), the checkers (400 ms boundary), reports, CSV and file storage
(corrupt files, atomic writes), the engine, and whole scripted menu sessions.

## Documentation
- [docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md): the full explanation of the project, every module, the decisions and the viva questions
- [docs/DECISIONS.md](docs/DECISIONS.md): the decision log, phase by phase

## Progress
- [x] Phase 0: project skeleton
- [x] Phase 1: foundation (enums, exceptions, service hierarchy)
- [x] Phase 2: health checking
- [x] Phase 3: incidents and alerts
- [x] Phase 4: engine and console menu
- [x] Phase 5: reports and storage
- [x] Phase 6: UML and documentation
