# Service Health Monitoring and Incident Management System

A console-based Java application that monitors simulated services, detects
failures, manages incidents, sends alerts, and produces reports.

The focus is OOP design: independent modules, interfaces, inheritance,
polymorphism, design patterns (Singleton, Factory, Strategy, Observer) and UML.

## Requirements
- JDK 17 or newer
- Maven 3.8+ (for build and tests)

## Run it
```bash
mkdir out
javac --release 17 -d out $(find src/main/java -name "*.java")     # Linux / macOS
java -cp out Main
```
Windows PowerShell: `javac --release 17 -d out (Get-ChildItem -Recurse src/main/java -Filter *.java).FullName`

Demo mode: `java -cp out Main --seed 42` gives reproducible randomness and a clean start. It uses its
own `data-demo/` folder, so it never touches your real saved data.

Typical demo: add a service (1), force it down (9), run 4 rounds (5), watch the incident open
and the alerts fire, clear the force (9), run a round (4) and watch it recover. Alerts are also
written to `data/alerts.log`, and everything is logged to `data/monitor.log`.

With Maven: `mvn test` runs the tests, `mvn compile` builds.

## Saved data
Everything is saved automatically after each change (add, remove, check rounds) and on exit, into
CSV files in `data/`: `services.csv`, `results.csv`, `incidents.csv`. They are loaded at startup,
so incidents that were open when you quit are still open (and resolve when the service recovers).
If a file is corrupt the program refuses to start and names the file and line, so nothing is
overwritten; fix or delete the file. Not saved on purpose: demo force-failure switches, the
failure threshold, and round numbers.

Logs: `data/monitor.log` (everything) and `data/alerts.log` (alerts only).

## Package structure (`src/main/java`)
| Package | Responsibility |
|---|---|
| `model` | Services and their simulated behaviour |
| `healthcheck` | Probing a service and judging the result |
| `incident` | Consecutive-failure tracking, opening/resolving incidents |
| `notification` | Delivering alerts through different channels |
| `report` | Uptime, response-time statistics, rankings (read-only) |
| `storage` | File persistence and logging |
| `exception` | Custom checked exceptions |
| `engine` | Coordinates all modules (`MonitoringEngine`, Singleton) |
| `console` | The text menu: input and output only, no rules |

## Progress
- [x] Phase 0: project skeleton
- [x] Phase 1: foundation (enums, exceptions, service hierarchy)
- [x] Phase 2: health checking
- [x] Phase 3: incidents and alerts
- [x] Phase 4: engine and console menu
- [x] Phase 5: reports and storage
- [ ] Phase 6: UML and documentation

Design decisions and the reasoning behind them are logged in
[docs/DECISIONS.md](docs/DECISIONS.md).
