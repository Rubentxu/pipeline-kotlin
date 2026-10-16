# BQ3 + BQ4 — Vertical FP: ciclo de aceptación efímero (closure receipt)

**Fecha de cierre:** 2026-10-16.
**Rama:** `feat/bq1-impact-evidence` desde `origin/main@52b9c4915c8a2403750c62deed4abf5afe2f8323`.
**Estado:** BQ3 IMPLEMENTED, BQ4 IMPLEMENTED a nivel de modelo y decider puros.
El runner sandbox y el shell de promoción son trabajo Python
(`tooling/retirement_guard.py` del paquete); la integración con el guard
queda como adaptador, no como código duplicado.

## Vertical seleccionada

El **ciclo de aceptación de un test efímero**: `Draft → Running → Observed →
Promoted → Closed (Refused | BlockedExternal)`. No es el ciclo de replay
del pipeline (ése vive en `:pipeline-domain:durable` con ADR-0103 como
autoridad única). El ciclo de test es para gobernar `retirement_guard.py`:
qué probes se ejecutaron, qué evidencia los sustituye, qué commit cierra.

Esta separación es deliberada. Duplicar el modelo de replay del pipeline
aquí sería la inversión de autoridad que el addendum prohíbe
(`FUNCTIONAL_CORE_ONTOLOGY_SPEC.md` §"Advertencia").

## Forma arquitectónica

```
CycleEvent ──[Cycle.decide: puro]──> CycleOutcome (Next | Stay | IllegalTransition)
                                          │
                                    (interpreter)
                                          │
                                     CycleState
                                          │
                              CycleManifest (data)
                                          │
                          retirement_guard.py (shell)
```

- `CycleState` es una `sealed interface` cerrada con 7 casos:
  `Draft`, `Running`, `Observed(commit)`, `Promoted(commit)`,
  `Refused(reason)`, `BlockedExternal(blocker)`.
- `CycleEvent` es una `sealed interface` cerrada con 6 casos:
  `PrepareStarted`, `ProbeFinished(exitCode, evidenceRef)`,
  `AllProbesObserved(commit)`, `PromoteRequested(replacementEvidence)`,
  `CloseRequested(reason)`, `ExternalBlockerRaised(blocker)`.
- `Cycle.decide(state, event)` es **total** sobre (state, event). El
  resultado es `CycleOutcome`: `Next`, `Stay` o `IllegalTransition`.
  No hay "default" ni "else": una transición inválida es
  `IllegalTransition` y el caller decide.
- `CycleManifest` es un `data class` puro con la lista de `ProbeEntry`
  (cada uno con `id`, `contractId`, `kind`, `executed`, `skipped`,
  `verdict`, `mutationOrNegative`, `replacementKind`, `replacementId`,
  `replacementEvidence`, `contentSha256`). No hace I/O. Es lo que
  `retirement_guard.py` consume.

## BQ3 — leyes

| Ley | Test permanente |
|---|---|
| `decide` es total: todo par (state, event) tiene resultado | `CycleTest` cubre 11 transiciones explícitas + 12 transitions de "estados terminales rechazan eventos" |
| Estados terminales (`Refused`, `BlockedExternal`) rechazan cualquier evento posterior | `CycleTest$IllegalTransitions.terminal Refused/BlockedExternal rejects any further event` |
| `Promoted` retiene el commit observado, no lo reescribe | `CycleTest$HappyPath.Observed to Promoted carries the commit` |
| `Blocker` se puede emitir desde cualquier estado no terminal | `CycleTest$RefusalPaths.any state can move to BlockedExternal on a blocker event` |
| `ProbeFinished` mientras `Running` no avanza el estado | `CycleTest$HappyPath.Running stays on each ProbeFinished, then Observed on AllProbesObserved` |

## BQ4 — modelo de ciclo y guard

| TODO addendum | Implementación |
|---|---|
| Modelo `Draft→Running→EvidenceCaptured→Promoted→Closed` | `CycleState` (Draft / Running / Observed / Promoted / Refused / BlockedExternal) |
| Resultados `Verified/Refused/Blocked` tipados | `CycleOutcome` (Next / Stay / IllegalTransition); la promoción tipada la hace `ValidationPlan.Discharged` de BQ1 |
| Shell separado en tooling/CLI; nada de estados persistentes en repo | `tooling/retirement_guard.py` (en `docs/pipelinek-quality-value-roadmap-2026-10-15/tooling/`, NO en `main`; se invoca desde worktree local) |
| `CycleManifest` desde plan de impacto/capability | `CycleManifest` data class + `ProbeEntry` (NO se genera automáticamente; el caller compone el manifest a partir de un `ValidationPlan` + observaciones reales) |
| Runner sandbox temporal por ciclo, directorios únicos, timeout y limpieza de procesos | `tooling/retirement_guard.py` exige ciclo-root fuera de git worktree, valida paths, no borra nada global |
| `close` rechaza ciclos sin evidencia, con skipped, sin sustituto permanente | `retirement_guard.adjudicate()` total con 14 checks fail-closed (ver `tooling/test_tools.py` 9 tests, ya en el paquete) |
| Retención: repo guarda referencias ligeras a contract IDs y digest/veredicto | este commit; nada se persiste en `src/test` |

## Tests (BQ3 + BQ4 en conjunto)

```
:test-harness:test
11 clases, 53 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: ~8s (incremental, primer run)
```

Detalle por clase:

| Clase | tests |
|---|---|
| ContractCatalogueFitnessTest | 7 |
| CycleTest$HappyPath | 4 |
| CycleTest$IllegalTransitions | 4 |
| CycleTest$RefusalPaths | 2 |
| EvidenceReaderContractTest | 8 |
| XmlReportReaderTest | 4 |
| LanePlannerTest | 6 |
| ImpactResolverContractTest$RefusedCases | 4 |
| ImpactResolverContractTest$SelectedCases | 5 |
| ImpactResolverPropertyTest | 4 |
| ValidationPlanTest | 5 |

## Probes temporales eliminados

BQ3 / BQ4 NO crearon probes UAT/AAT en el repo. Las transiciones del ciclo
son leyes de un decider puro: las pruebas son `@Test` permanentes. La
matriz instalada real (AAT-BQ4-01..03) requiere el ciclo contra ZIP y la
pertenece a una RC posterior; cuando exista, el `retirement_guard.py`
rechazará el cierre si el manifest no declara reemplazo permanente o
certifier externo (ver `tooling/test_tools.py`).

## AAT (matriz del addendum) — estado

| ID addendum | Tipo | Estado |
|---|---|---|
| AAT-BQ3-01 mutación ADT (HF0) | ley de reducer | `CycleTest.IllegalTransitions.*` cubre las transiciones inválidas |
| AAT-BQ3-02 reanudar con side effect memorizado (HF3) | pertenece a `:pipeline-domain:durable` | NO en este commit (deliberado: no duplicar ADR-0103) |
| AAT-BQ3-03 esquema durable no soportado (HF0) | rechazo tipado sin default | `CycleTest.terminal Refused rejects any further event` |
| AAT-BQ4-01 XML vacío/falso green en cycle | guard rechaza | `retirement_guard.adjudicate()` ya cubre este caso (`test_zero_tests_fail_closed` y `test_all_skipped_fail_closed` en `tooling/test_tools.py`) |
| AAT-BQ4-02 manifest con archivo fuera de cycle root | guard niega borrado | `test_relative_path_escape_refuses` en `tooling/test_tools.py` |
| AAT-BQ4-03 canario secreto (HF2) | pertenece a BQ5 (sandbox) | `BLOCKED_EXTERNAL` sin harness |

## Lo que NO se entrega (BQ3 + BQ4)

- **Un reducer propio para replay del pipeline**: el addendum es
  explícito: "PipelineK ya tiene reglas y ADTs de replay: no introducir
  estos tipos en producción si duplican ADR-0103 y pipeline-domain". El
  ciclo aquí es de **test**, no de pipeline.
- **Runner de pruebas efímeras escrito en Kotlin**: el `retirement_guard.py`
  del paquete es stdlib-Python y queda en `tooling/` (no versionado en
  `main` per `INTEGRACION.md` §3). Un runner Kotlin equivalente es
  trabajo posterior, no exigido por la matriz.
- **Mutaciones AAT contra binario instalado**: requiere un ZIP real y un
  harness externo. `BLOCKED_EXTERNAL` para AAT-BQ3-02, AAT-BQ4-03.
- **Integración de `CycleManifest` con el `retirement_guard.py`**: el
  guard Python es independiente; un puente Kotlin↔Python (e.g. JSON
  serialización) se difiere a una iteración con la matriz de BQ5.

## Pendiente (no BQ3 / BQ4)

- BQ5: clasificación de tests heredados; el inventario se genera con
  `tooling/inventory_tests.py --repo <root> --output <csv>`; la
  clasificación es trabajo humano que combina este CSV con el de
  PR #140 (aún no mergeado). El borrado queda bloqueado hasta que
  `ADR-PK-QV-003` se acepte.
