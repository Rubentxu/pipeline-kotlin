# Risk Register

| ID | Riesgo | Prob. | Impacto | Mitigación | Gate |
|---|---|---:|---:|---|---|
| R1 | API Kotlin Scripting cambia | M | M | adapter versionado + pin + corpus | M1/M2 |
| R2 | Replay diverge con Kotlin dinámico | M | H | operation fingerprints + fail closed | M3 |
| R3 | Side effect repetido | L/M | Critical | effect policy + durable confirmation + idempotency | M3/M9 |
| R4 | Protocol split brain | M | Critical | leases/fencing | M4 |
| R5 | K8s cold start alto | H | M | benchmarks/warm pools/cache affinity | M5/M9 |
| R6 | Controller sigue cargado por projections/logs | M | H | gateway/projectors externos + direct artifacts | M6/M8 |
| R7 | Plugin ecosystem insuficiente | M | H product | familiarity catalog + M7 prioritization | M7 |
| R8 | Secret leakage | M | Critical | projection model + redaction + secret schema tests | M5/M9 |
| R9 | Graph añade complejidad antes de valor | M | M | projection-only, fuera hot path, M8 | M3/M8 |
| R10 | Jenkins FlowExecution persistence edge cases | M | H | early spike M3/M4, harness/restart tests | M6 |
| R11 | BTA no disponible para third party | H near-term | L | no dependencia V2.0 | spike |
| R12 | FIR/IR churn | H | M/H | KSP/context params; plugin optional | M2 |
| R13 | Migration familiarity insuficiente | M | H | corpus Jenkins + F0-F3 metrics + migrator | M7/M10 |
| R14 | Event log crece demasiado | H | M | snapshots/compaction/log separation | M8/M9 |
| R15 | OCI plugin distribution complejidad | M | M | JAR/local catalog first, OCI incremental | M7 |

## Critical risk rule

R3, R4 y R8 son release blockers independientemente de disponibilidad/performance. No pueden aceptarse mediante “known issue” para GA.
