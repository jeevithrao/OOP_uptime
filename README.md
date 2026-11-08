# Service Health Monitoring and Incident Management System

A console-based Java application that monitors simulated services, detects
failures, manages incidents, sends alerts, and produces reports.

The focus is OOP design: independent modules, interfaces, inheritance,
polymorphism, design patterns (Singleton, Factory, Strategy, Observer) and UML.

## Requirements
- JDK 17 or newer
- Maven 3.8+ (for build and tests)

## Package structure (`src/main/java`)
| Package | Responsibility |
|---|---|
| `model` | Services and their simulated behaviour |
| `healthcheck` | Probing a service and judging the result |
| `incident` | Consecutive-failure tracking, opening/resolving incidents |
| `notification` | Delivering alerts through different channels |
| `report` | Uptime, response-time statistics, rankings |
| `storage` | File persistence and logging |
| `exception` | Custom checked exceptions |
| `engine` | Coordinates all modules |

## Progress
- [x] Phase 0: project skeleton
- [x] Phase 1: foundation (enums, exceptions, service hierarchy)
- [ ] Phase 2: health checking
- [ ] Phase 3: incidents and alerts
- [ ] Phase 4: engine and console menu
- [ ] Phase 5: reports and storage
- [ ] Phase 6: UML and documentation

Design decisions and the reasoning behind them are logged in
[docs/DECISIONS.md](docs/DECISIONS.md).
