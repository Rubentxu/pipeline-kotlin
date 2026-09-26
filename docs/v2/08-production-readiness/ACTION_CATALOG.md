# Action Catalog

| ID | Hallazgo asociado | Acción | Ubicación | Esfuerzo | Prioridad | Dependencia |
|---|---|---|---|---:|---:|---|
| PR-001 | Drift documental | Crear una proyección materializada de estado actual generada desde Git, receipts y gate state | `docs/v2/08-production-readiness/` + script de generación futuro | 8–12h | P0 | — |
| PR-002 | `SESSION_POINTER` obsoleto | Reducir el puntero a datos operativos mínimos y eliminar narrativa histórica duplicada | `.agent/SESSION_POINTER.md` | 4–6h | P0 | PR-001 |
| PR-003 | UAT matrix contradictoria | Separar matriz normativa de resultado corriente; el estado corriente se genera por SHA | `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` + current view | 8–12h | P0 | PR-001 |
| PR-004 | 25 PRs abiertos | Clasificar cada PR como KEEP / STACK / SUPERSEDE / CLOSE / DEPENDENCY | GitHub PRs + receipt de reconciliación | 8h | P0 | — |
| PR-005 | Dependabot backlog | Agrupar actualizaciones compatibles, ejecutar affected tests y SCA | `.github/dependabot.yml`, version catalog/build files | 12–20h | P1 | PR-004 |
| PR-006 | No required checks | Crear un único check de admission que consuma resultado del harness/receipt exacto | branch protection + workflow/check publisher | 12–16h | P0 | PR-001 |
| PR-007 | Deuda stale | Reconciliar `TECH_DEBT_BACKLOG.md` contra receipts actuales | `.agent/TECH_DEBT_BACKLOG.md` | 4h | P2 | PR-001 |
| PR-008 | 41 fallos en full suite | Reproducir y clasificar cada fallo en mandatory/legacy/fixture/obsolete/real defect | `v2/**/test`, compatibility corpus, receipt | 16–24h | P0 | PR-004 |
| PR-009 | HAR-007 | Implementar y certificar la semántica definida para fallo dentro de `dir` | DSL/domain/coordinator/harness scenario | 16–24h | P0 | PR-008 |
| PR-010 | SHA candidato ambiguo | Congelar un único candidate SHA; prohibir cambios funcionales durante admission | release branch/tag + manifest | 4–8h | P0 | PR-008, PR-009 |
| PR-011 | HEAD sin gate fresco | Ejecutar L5/full suite sobre candidate SHA exacto y emitir evidence bundle | `./gradlew -p v2 check`, receipts | 8–12h runtime+analysis | P0 | PR-010 |
| PR-012 | Supply chain evidence antigua | Ejecutar SAST, secret scan, SBOM, dependency/SCA sobre candidate SHA | CI/harness + artifacts | 8–12h | P1 | PR-010 |
| PR-013 | Coverage antigua | Ejecutar Kover agregado del candidate y extraer riesgo por módulos críticos | Kover reports | 6–10h | P1 | PR-010 |
| PR-014 | Mutation evidence antigua | Reejecutar mutación sólo en replay policies, reconcilers, codecs y failure decisions | PIT configuration/reports | 8–12h | P2 | PR-010 |
| PR-015 | RSS sin SLO | Repetir M5, fijar SLO de memoria y umbral de regresión | perf scripts + RP2 evidence | 8–12h | P1 | PR-010 |
| PR-016 | RP-5 no acreditado | Ejecutar dogfood 2 repos + harness externo + reproducibilidad + receipt final | release harness + `docs/v2/07-uat/` | 12–16h | P0 | PR-011..015 |
| PR-017 | Coordinator gigante | Extraer `RunLifecycleEngine` sin cambiar semántica | `pipeline-application/.../durable` | 16–20h | P1 | PR-016* |
| PR-018 | Coordinator gigante | Extraer `BodyExecutionEngine` y body policy orchestration | mismo módulo | 20–24h | P1 | PR-017 |
| PR-019 | Coordinator gigante / compatibility seams | Consolidar `InvocationEngine` + `RecoveryEngine`; mover compatibilidad al composition root | application/runtime composition | 24–32h | P1 | PR-018 |
| PR-020 | Cherry-pick semántico / mantenibilidad | Reducir coordinator <600 LOC y añadir gate de composición semántica para cambios de modelo compartido | architecture tests + integration tests | 24–32h | P1 | PR-019 |
| PR-021 | Duplicidad POSIX | Consolidar constantes de permisos en autoridad común | domain/common filesystem policy | 2–4h | P3 | — |

`PR-016*`: Phase 3 may start experimentally before PR-016 only in a separate branch, but no coordinator refactor should be mixed into the candidate under admission.
