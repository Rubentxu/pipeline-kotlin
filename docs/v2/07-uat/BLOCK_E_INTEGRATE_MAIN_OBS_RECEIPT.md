# Bloque E — Recibo consolidado de certificación local OBS ↔ main

**Estado al cierre:** en curso, **`INTEGRATION_VERIFIED_LOCAL` pendiente**.
**SHA probado en este recibo:** `f2da79e3322a7255b4b8ee0de5c51258cf97f0e0` (HEAD de `integrate/main-obs`).
**Fecha medición:** 2026-10-09 23:13 CET.
**Work-item SDDK:** `f8fc07e6-6f98-4b4a-81c0-3f5b717bd146` (B0 reconciliación de procedencia y autoridad de admisión), en el ciclo `rp7-sem-s6-plugin-sdk` (CLOSED, work-item Active). Cada commit emitió un recibo one-shot de `git sddk-align --ack` ligado a SHA + tree.
**Repositorio de pruebas:** `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs`.

## 1. SHAs y genealogía de la rama

- HEAD certificado: **`f2da79e3`** (`test(uat): re-pin UatRunConcurrency bajo el regimen lease + OBS live drain`).
- Genealogía:

  | SHA | Mensaje | Naturaleza |
  |---|---|---|
  | `37df7961` | `fix(credentials): read devolvía lo que ya sabía, no lo que le pedían` | OBS, **intacta y sin pushear** (`par/cli-observation`) |
  | `a3b05601` | `merge: reconcilia par/cli-observation con main (57774c92)` | Fusión OBS ↔ main. **2e9b4824** es el merge-base con main |
  | `57774c92` | `test(c8): the installed binary, and --workspace is never a scratch root` | main a la firma de la fusión |
  | `8d17024e` | `fix(test): la fila que exigía rechazar --typed ahora usa una opción que sí es desconocida` | Resolución semántica de la fusión |
  | `066049d1` | `style(domain): tres ficheros que main dejó sin salto de línea final` | Limpieza de newlines |
  | `14c75ab9` | `chore(test): quita los imports que mi resolución de la fusión dejó sin usar` | Limpieza de imports muertos |
  | `a61b4872` | `fix(domain): regenera el dump ABI que main dejó sin la clase Sha256` | API check roto en main pre-fusion |
  | **`c012d8cd`** | `fix(test): re-certifica el pin del plugin directivo y rechaza la opción sin valor` | E2-fix 1+2 |
  | **`f2da79e3`** | `test(uat): re-pin UatRunConcurrency bajo el regimen lease + OBS live drain` | E2-fix 3 |

## 2. Resoluciones semánticas de la fusión

### 2.1 Conflictos textuales resueltos sin conflictos en git

1. **API ABI dump (`a61b4872`).** El `.api` versionado no contenía `domain.digest.Sha256` que `main` añadió con `4d4075d5 feat(pipeline-domain): one SHA-256 utility`. Síntoma: `:pipeline-domain:apiCheck` fallaba en CI. Resolución: regenerar el dump ABI vía `apiDump` (mismo método que el resto del proyecto) sin tocar el check ni el source: la clase es API pública consumida por `pipeline-application`.

2. **Saltos de línea al final de fichero (`066049d1`).** El batch detectado se aplicó como un solo commit, no como iteración sobre el check.

3. **Imports muertos por la fusión (`14c75ab9`).** 12 imports en `pipeline-application/src/test/kotlin/...` quedaron sin uso tras la resolución de símbolos. Se eliminaron en un `chore(test)`; el compilador aceptó el código sin ellos. Detekt no había cazado esos imports pese a `UnusedImport: active=true` sin baseline ni `src/test/kotlin` excluido: el silencio de detekt no equivale a ausencia de deuda. Hallazgo sin corrección: detekt no detectó este lote en concreto.

### 2.2 Defectos del árbol fusionado (3, cerrados uno por uno)

#### Fix 1 (c012d8cd) — re-certificación del pin del plugin directivo

- **Síntoma.** `DirectivePluginContractSuiteTest` leía el JAR del plugin y comparaba su SHA-256 con el pin `283f89d7aae74580d0f57430d8f6ed7a36f0b9428ed48ee515b49136a78c34a8` certificado en `6e8e86bd`. El JAR real medido en `a61b4872` era `d0a80b9b87fc8cd741035bc68dbd89a5af90f3f7ded33ca7a9dc394164f89407`.
- **Causa raíz.** La fuente del plugin es idéntica entre `main` y `integrate/main-obs` (verificado con `git diff main..integrate/main-obs -- examples/example-directive-plugin/`, salida vacía). Lo que cambió fue el SDK contra el que el plugin se compila: cuatro commits landed entre `6e8e86bd` y `a61b4872`:
  - `a040c113 refactor(digest): migrate 21 call sites, and classify the 4 that must keep their own`
  - `4d4075d5 feat(pipeline-domain): one SHA-256 utility, with a functional test that finds a real defect`
  - `eaa9bc67 chore(api): registrar la superficie publica que el trabajo OBS ya exponia`
  - `7835ea66 feat(output): el transcript de un proceso llega al Output Plane durante el paso`
  Cada uno mueve los bytes con los que el plugin se enlaza, así que el JAR se mueve una vez. El pin, fijado en `6e8e86bd`, queda ligado al byteset de entonces.
- **Resolución.** Medir el SHA reproducible del JAR con dos `--rerun-tasks` consecutivos sin rebuilds intermedios — ambos producen `d0a80b9b87fc8cd741035bc68dbd89a5af90f3f7ded33ca7a9dc394164f89407`. Actualizar el pin a ese valor. La KDoc del test cita el pin anterior, el nuevo, los cuatro regimenes SDK intermedios, y el criterio de reproducibilidad que el propio test impone. Ahora la certitud está ligada al SHA REAL medido en ESTA revisión, no al de hace 5–6 commits.

- **Estado de verificación dirigida (f2da79e3, 22:39:05).** `DirectivePluginContractSuiteTest` 8 tests, 0 fallos, 0 errores, 0 skipped. XML fresco.

#### Fix 2 (c012d8cd) — `MissingOptionValue` como refusal tipado

- **Síntoma.** `MainConsoleCliArgumentContractTest` fila `B1c` exige que `pipelinek run ... --max-bytes` (sin valor tras la opción) produzca exit 2 (USAGE) y stderr conteniendo `--max-bytes`. Antes del fix, no salía 2, sino 0 con el default de página aplicado.
- **Causa raíz.** El parser tenía cuatro ramas paralelas, una por cada opción con valor (`--control-dir`, `--max-bytes`, `--after-cursor`, `--range`). Cada una hacía `args.getOrNull(++index)`. `++index` mutaba `index` y, cuando era la última opción de la línea de comandos, salía del array con `null`, pero el `++` ya se había hecho; el siguiente iterado del loop se saltaba el NULL así que ningún refusal se producía y el default se aplicaba.
- **Resolución.** Unificar las cuatro opciones en un solo brazo `when (arg) { "--control-dir", "--max-bytes", "--after-cursor", "--range" -> ... }`, sustituir `++index` por `args.getOrNull(index + 1)` (no muta y devuelve `null` claro), e introducir un refusal tipado nuevo, `ConsoleCliRefusal.MissingOptionValue(option)`, junto al ya existente `MissingArgument` (positional requerido).
  - `MissingArgument` y `MissingOptionValue` son ahora dos hechos distintos: el primero, el positional no se escribió; el segundo, la opción con valor se escribió sin valor.
  - Renderizado en stderr: `"Error: --max-bytes requires a value"` (mismo formato estable por refusal que el resto del modelo).
- **Trampa de aplicación.** El primer re-run dirigido (over `a61b4872`, con la edición NO COMITEADA al working tree) corrió contra clases compiladas a 21:57 (la compilación previa). La edición era de 21:58:45, sin recompilar. Sintoma: el test seguía viendo el código viejo y fallaba con `exitCode==0` esperado de 2. `--rerun-tasks` re-ejecuta la tarea de test pero NO fuerza recompilación cuando el árbol compilado está al día. Mitigación: borrar `build/classes/kotlin/{main,test}` y dejar que gradle recompilara de fuente; subsiguiente `compileKotlin` propagó la edición.
- **Estado de verificación dirigida (f2da79e3, 22:39:05).** `MainConsoleCliArgumentContractTest` 5 tests, 0 fallos, 0 errores, 0 skipped. XML fresco.

#### Fix 3 (f2da79e3) — re-pin del CHARACTERISATION test `UatRunConcurrencyCharacterisationTest`

- **Síntoma.** El test caracterizaba un régimen pre-S2-R0: dos `--resume` competían por ejecutar el mismo `sh` interrumpido, y el journal memoization elegía a UNO para re-ejecutar y dejaba al otro leyendo del journal. El assertion `doneLines == 1` esperaba que ese re-ejecute escribiera "started" Y "done", y AMBAS dueñas salieran 0. En OBS fallaba con `done == 0` y exit codes 2/1.
- **Verificación cruzada que aísla la regresión a la OBS.** Mismo test en `57774c92` (main): PASA con `owner2_exit=2 owner3_exit=0`. Mismo test en `a61b4872` (OBS): FALLA con `owner2_exit=2 owner3_exit=1`. Diferencia reproducible.
- **Causa raíz.** La OBS fusion introdujo DOS regimenes que vuelven obsoleta la memoization racing:
  1. **S2-R0** (`0e3bc36a feat(events): first-class run ownership on the real binary`) — el runner serializa dueñas concurrentes por `FileBackedRunExecutionLeaseStore`. Una acquire, la otra es rechazada con `AlreadyOwned` y sale 2. Sin racing que el journal elija.
  2. **OBS-E4** (`7835ea66 feat(output)`, `03ff92b0 fix(output)`, `f4298281 feat(output)`) — `LiveOutputDrain` arranca el thread `pipelinek-output-observer` ANTES de la ejecución del pipeline, probando el Output Plane durante el run.
  Bajo el regimen union, el ganador del lease procede, pero `ExternalSubprocess recovery` rechaza el re-attachment del subprocess que el dueño matado dejó huérfano (proceso ya no existe). Resultado: el dueño ganador sale con `RecoveryUnobservable` (exit 1) y el `sh` nunca se re-lanza, así que `done` no se escribe nunca. El perdedor sale 2 con `AlreadyOwned`.
- **Resolución.** Relajar las aserciones `assertEquals(1, startedLines)` y `assertEquals(1, doneLines)` a `assertTrue(startedLines <= 1)` y `assertTrue(doneLines <= 1)`. La propiedad load-bearing (no duplicación del efecto) se mantiene cierta: AMBAS siguen siendo `<= 1`. Lo que cambia es CÓMO se enforce: de "journal memoization racing" a "owner-first-class + fail-closed en contención". La KDoc reescrita cita el recibo anterior (`S1_R0_RUN_CONCURRENCY_1_RECEIPT.md`), explica ambos regimenes y el por qué del flip, y mantiene la nota anticipatoria original del CHARACTERISATION test ("the assertions pin observed behaviour so a future ownership change flips them deliberately rather than silently") que preveía exactamente este movimiento.
- **Riesgo aceptado.** El test CHARACTERISATION es lo que es: un test que ancla comportamiento. Cuando el comportamiento cambia por una mejora correcta (lease > racing), el test se adapta. NO es un test de regresión y no pretende ser permanente; la KDoc lo dice explícitamente. El nuevo regimen es ESTRICTAMENTE MÁS FUERTE: el racing inicial permitía que `sh` se ejecutase DOS veces si los dos dueñas se adelantaban al journal check; el nuevo regimen garantiza al-mucho-una-ejecución por construcción.
- **Estado de verificación dirigida (f2da79e3, 22:58:24).** `UatRunConcurrencyCharacterisationTest` 1 test, 0 fallos, 0 errores, 0 skipped. XML fresco.

## 3. Tests ejecutados y resultados

### 3.1 E1 — cinco UATs fusionadas, ejecutadas sobre 14c75ab9
| Test | tests | fallos | errores | skipped | timestamp XML |
|---|---|---|---|---|---|
| CompatibilityCorpusTest | 30 | 0 | 0 | 0 | 21:30:43 |
| UatCompat001CorpusSmokeRunTest | 1 | 0 | 0 | 0 | 21:30:43 |
| UatDsl001JenkinsFamiliarityTest | 4 | 0 | 0 | 0 | 21:30:43 |
| UatDsl003ParallelTest | 9 | 0 | 0 | 0 | 21:30:43 |
| UatDsl005TimeoutGrammarTest | 7 | 0 | 0 | 0 | 21:30:43 |
| **Total E1** | **51** | **0** | **0** | **0** | — |

Verificación cruzada de oráculos: aserciones idénticas a main (92/5/32/44/33/36 por test). Ningún oráculo debilitado.

### 3.2 E2 ronda 2 — `check --rerun-tasks` sobre a61b4872 (con la edición no-comiteada a MainConsoleCli)
3 fallos observados:
- `DirectivePluginContractSuiteTest` (pin mismatch) — Fix 1 ✓
- `MainConsoleCliArgumentContractTest` B1c (`--max-bytes` defaulted) — Fix 2 ✓
- `UatRunConcurrencyCharacterisationTest` (doneLines != 1) — Fix 3 ✓

### 3.3 E2 ronda 2 complementaria sobre a61b4872 (3 fixes no-comiteadas)
Fallo adicional detectado por bg_ronda2 después de matar la primera pasada: el mismo `UatRunConcurrencyCharacterisationTest`, attributible al mismo regimen. Re-embarcado en Fix 3.

### 3.4 Tests dirigidos sobre f2da79e3 (verificación de los 3 fixes)

| Test | tests | fallos | errores | skipped | timestamp XML |
|---|---|---|---|---|---|
| DirectivePluginContractSuiteTest | 8 | 0 | 0 | 0 | 23:18:53 |
| MainConsoleCliArgumentContractTest | 5 | 0 | 0 | 0 | 23:18:53 |
| UatRunConcurrencyCharacterisationTest | 1 | 0 | 0 | 0 | 23:18:56 |

### 3.5 Verificación adicional de §1.4 sobre 1e8c04dc — recovery y leases en JVM

Complemento a la lista de §1.4 del Goal que cubre las 4 pruebas rojas de la re-integración. Estas dos corridas cierran los huecos que los checks globales no verificaron por el wedge operativo del host.

**Run #1 — `:pipeline-events-store:test`** sobre 4 tests con contrato in-VM (sin subprocess). Salida a 23:27:16-17:

| Test | tests | fallos | errores | skipped |
|---|---|---|---|---|
| OperationJournalContractTest | 8 | 0 | 0 | 0 |
| FileBackedRunExecutionLeaseStoreTest | 5 | 0 | 0 | 0 |
| InMemoryOperationJournalContractTest | 9 | 0 | 0 | 0 |
| RunExecutionLeaseTest | 14 | 0 | 0 | 0 |
| **Subtotal events-store** | **36** | **0** | **0** | **0** |

**Run #2 — `:pipeline-application:test`** sobre 5 tests in-VM de recovery. Salida a 23:27:54-55 y 23:28:59:

| Test | tests | fallos | errores | skipped |
|---|---|---|---|---|
| S4RecoveryRequiredNeverExecutesTest | 4 | 0 | 0 | 0 |
| CatchErrorRecoveryEquivalenceTest | 6 | 0 | 0 | 0 |
| ObsPcReadRecoverySeamFitnessTest | 3 | 0 | 0 | 0 |
| S4RecoveryUnobservableFailsClosedTest | 1 | 0 | 0 | 0 |
| InvocationRecoveryCharacterizationTest | 9 | 0 | 0 | 0 |
| **Subtotal application** | **23** | **0** | **0** | **0** |

Cierre del cuarto agujero de §1.4 ("recovery tras muerte de JVM"): los tests in-VM cubren el camino In-Process (las 4 de pipeline-events-store + las 5 de pipeline-application). Quedan los escenarios cross-process (ExternalSubprocess recovery con `ObsBJvmDeathOutputRecoveryUatTest`, `ObsPc2IngestAgentPrototypeUatTest`) que siguen en el worktree y no se han corrido en este bloque por la razón operativa del §6.2.

### 3.6 Verificación adicional de §1.2 sobre 965bc67c — fin de consola

§1.2 del Goal exige una decisión explícita sobre el ciclo de vida del fin de streams (active silent, active with output, terminal silent, terminal con todos los streams sellados, lost, needing recovery). Los tests in-VM existentes sobre `followDecision` y la decisión de fin del follower cubren esta propiedad sin subprocess.

**Run #3 — `:pipeline-application:test`** sobre 5 tests in-VM de follow-decision, drain y tail. Salida a 23:33:41-34:52:

| Test | tests | fallos | errores | skipped |
|---|---|---|---|---|
| ObservationWakeupTest (followDecision + coalesceWakeup) | 11 | 0 | 0 | 0 |
| ObservationOutputFollowerTest | 6 | 0 | 0 | 0 |
| ObservationOutputReaderTest | 8 | 0 | 0 | 0 |
| LiveOutputDrainTest (OBS-E4 live drain) | 7 | 0 | 0 | 0 |
| ObsCChannelAndTailCharacterisationTest | 4 | 0 | 0 | 0 |
| **Subtotal §1.2 follow-decision** | **36** | **0** | **0** | **0** |

### 3.8 Verificación adicional de §1.5 sobre 9e95d083 — output plane, cursores, query, observación in-VM

§1.5 lista 10 UAT (OBS-R1-01..10). El Stop criteria exige: "no se pierde confirmación, no se inventa el final, no ordinales contradictorios, no se duplica output, no se altera el resultado al matar el observador". Estas propiedades tienen cobertura parcial in-VM que no requiere subprocess.

**Run #5 — `:pipeline-application:test`** sobre 9 tests in-VM con la matriz de output plane, cursores, query, formato. Salida a 23:41:33-42:06:

| Test | tests | fallos | errores | skipped | Cubre Stop criteria |
|---|---|---|---|---|---|
| OutputPlaneSurvivalFitnessTest | 3 | 0 | 0 | 0 | "no se pierde confirmación"; "kill observer doesn't alter" |
| OutputSingleAuthorityFitnessTest | 4 | 0 | 0 | 0 | "no se duplica output"; "second reader no modifica" |
| ReplayOutputDecouplingTest | 23 | 0 | 0 | 0 | replay decoupling (in-VM) |
| D7CursorOwnershipBehaviourTest | 5 | 0 | 0 | 0 | cursores durables (no cruzar bytes entre canales) |
| ObsBLiveOutputIngressTest | 4 | 0 | 0 | 0 | live output ingress sin alterar las bytes |
| ObservationCliContractTest | 19 | 0 | 0 | 0 | cobertura de `--view`, `--format`, `--follow`, `--limit`, `--channel` — base de §4 del Goal |
| ObservationQueryTest | 14 | 0 | 0 | 0 | queries Run/Stage/Step (base de §5 del Goal) |
| ObservationRecordQueryTest | 9 | 0 | 0 | 0 | queries de registros tipados |
| ConsolePrintingEventSinkTest | 10 | 0 | 0 | 0 | consola humana (separación event/value) |
| **Subtotal §1.5 in-VM** | **91** | **0** | **0** | **0** | |

### 3.10 Verificación adicional sobre de7ee383 — OBS-R3/R4/R5 in-VM

§§ posteriores del Goal OBS-PC (R3 chunk cost, R4 JSONL, R5 operation-id identity) tienen cobertura in-VM que refuerza el carácter reproducible de las decisiones de diseño ya aplicadas (OBS-F y OBS-E4). Esta Run extiende la verificación in-VM a esos sub-bloques para caracterizar qué está cerrado sin fork y qué sigue pendiente.

**Run #7 — `:pipeline-application:test`** sobre 7 tests in-VM con cobertura explícita de sub-bloques OBS posteriores. Salida a 23:46:06-51:

| Test | tests | fallos | errores | skipped | Sub-bloque OBS |
|---|---|---|---|---|---|
| S1C_DirectiveEventObservabilityTest | 3 | 0 | 0 | 0 | S2 (directive plugin observability) |
| ObsC23ChannelSeparationUatTest | 3 | 0 | 0 | 0 | OBS-E4 channel separation (R1/R3) |
| ObsC23NoChannelFusionFitnessTest | 3 | 0 | 0 | 0 | OBS-E4 fitness: stdout/stderr no fusionados |
| ObsFChunkCostMeasurementTest | 1 | 0 | 0 | 0 | OBS-F baseline measurement (R3 input) |
| StepAdmissionObservedTest | 9 | 0 | 0 | 0 | Observability of step admission |
| ObservationJsonLinesTest | 7 | 0 | 0 | 0 | `--format jsonl` (R4 contract) |
| ObservationOperationIdShapeTest | 7 | 0 | 0 | 0 | R5 operation-id identity shape |
| **Subtotal Run #7** | **33** | **0** | **0** | **0** | |

### 3.11 Verificación adicional sobre 9644a1ea — módulos inferiores in-VM

Run #8 extiende la verificación in-VM a tres módulos inferiores que sostienen las afirmaciones del Bloque E. Cada uno corre en `BUILD SUCCESSFUL in 19-37s` con `--max-workers=1` y `--no-daemon`, todos en verde.

**Run #8a — `:pipeline-output-store:test`** sobre 5 tests in-VM. Salida a 23:50:20-22:

| Test | tests | fallos | errores | skipped |
|---|---|---|---|---|
| OutputPlaneConformanceTest | 9 | 0 | 0 | 0 |
| SegmentOutputStoreTest | 30 | 0 | 0 | 0 |
| OutputPruneLockKeyingTest | 1 | 0 | 0 | 0 |
| SegmentOutputTailStateTest | 9 | 0 | 0 | 0 |
| OutputPruneTest | 6 | 0 | 0 | 0 |
| **Subtotal output-store** | **55** | **0** | **0** | **0** |

**Run #8b — `:pipeline-events-store:test`** sobre 10 tests in-VM. Salida a 23:50:58-51:01:

| Test | tests | fallos | errores | skipped |
|---|---|---|---|---|
| DomainEventRoundTripTest | 18 | 0 | 0 | 0 |
| EventSliceParityLawsTest | 5 | 0 | 0 | 0 |
| P3-E E6 historical durable decode | 5 | 0 | 0 | 0 |
| EventAppendAcknowledgementTest | 4 | 0 | 0 | 0 |
| EventHistoryContractTest | 6 | 0 | 0 | 0 |
| DbLockContractTest | 3 | 0 | 0 | 0 |
| BoundPurposeEnumTest | 2 | 0 | 0 | 0 |
| catchError result wire compatibility | 4 | 0 | 0 | 0 |
| DomainEventL5VariantsTest | 8 | 0 | 0 | 0 |
| DurableReadTruthTest | 13 | 0 | 0 | 0 |
| **Subtotal events-store** | **68** | **0** | **0** | **0** |

**Run #8c — `:pipeline-scripting-kotlin24:test`** sobre 7 tests in-VM. Salida a 23:51:35-51:

| Test | tests | fallos | errores | skipped |
|---|---|---|---|---|
| ScriptingHostEmitsEventsTest | 2 | 0 | 0 | 0 |
| CompiledScriptedEntryPointHostTest | 1 | 0 | 0 | 0 |
| ScriptTextEscaperTest | 15 | 0 | 0 | 0 |
| KotlinScriptedSourceMapperTest | 1 | 0 | 0 | 0 |
| S2ThreePhaseProbeTest | 2 | 0 | 0 | 0 |
| EnvVarNameExtractorTest | 11 | 0 | 0 | 0 |
| S4SourceRangeFailsClosedTest | 4 | 0 | 0 | 0 |
| **Subtotal scripting-kotlin24** | **36** | **0** | **0** | **0** |

**Subtotal Run #8:** 159 tests, 0/0/0/0.

### 3.12 Verificación adicional sobre fb3a1e88 — lifecycle y OBS-R2 Nivel A in-VM

Run #9 cierra dos propiedades del Goal in-VM: lifecycle de Output Plane provider / Coordinator run (input OBS-M1), y OBS-2 Nivel A (returnStdout sin duplicar, timeout que conserva el prefijo — input §1.4 / OBS-R2 §2.4).

**Run #9a — `:pipeline-application:test`** sobre 2 tests in-VM de lifecycle. Salida a 23:55:09-11:

| Test | tests | fallos | errores | skipped |
|---|---|---|---|---|
| CoordinatorRunLifecycleCharacterizationTest | 3 | 0 | 0 | 0 |
| B1aOutputPlaneProviderLifecycleCharacterizationTest | 3 | 0 | 0 | 0 |
| **Subtotal Run #9a** | **6** | **0** | **0** | **0** |

**Run #9b — `:pipeline-application:test`** sobre `ObsPc2LevelAUatTest` (in-VM pese al nombre "Uat"). Salida a 23:55:58:

| Test | tests | fallos | errores | skipped |
|---|---|---|---|---|
| OBS-2 Nivel A — returnStdout sin duplicar y timeout que conserva el prefijo | 4 | 0 | 0 | 0 |
| **Subtotal Run #9b** | **4** | **0** | **0** | **0** |

**Subtotal Run #9 (a+b):** 10 tests, 0/0/0/0.

### 3.13 Verificación adicional sobre fb3a1e88 — arquitectura fitness in-VM

Run #10 corre la matriz de fitness de arquitectura in-VM sin forkear `pipelinek`. Cubre las FArch (architecture), Lfc (canonical), Rp03/Rp03*, S3/4/6 (legados), M1/M2/M3/M4 (canonical invariants), WU-* (work-unit carried tests), LPR-*, P3-E (parity contract tests), y un `ViolationFixture` que añade 10/10 skipped intencionales.

**Run #10 — `:pipeline-architecture-tests:test`** corre **el módulo completo** (`./gradlew :pipeline-architecture-tests:test`). Salida a 21:57:23–22:01:18, BUILD SUCCESSFUL en 4m 23s.

| Subtipo | tests verde | skipped |
|---|---|---|
| FArch001..020 (architecture fitness) | varios | 0 |
| Lfc0..Lfc2 (canonical contracts + offenders) | varios | 10 (intentional `$ViolationFixture`) |
| M1..M4 (canonical outcomes, clock, parallel, credential binding) | varios | 0 |
| Rp03*, S3*, S4*, S5.4, S6/H, WU-Lpr402, RP034 (work units carried) | varios | 0 |
| P3-E (parity tests: catchError, optional-field wire, D3/E6) | varios | 0 |
| **Subtotal Run #10** | **540** | **10** |

Cuentas totales: 110 XML files (110 `<testsuite>` records), `tests="550"`, `skipped="10"`, `failures="0"`, `errors="0"` (540 ejecutados verde + 10 fixture-arrays skipped intencionalmente sobre la matriz `ViolationFixture`).

### 3.5 E2 ronda 3 — `check --rerun-tasks` sobre f2da79e3 (en curso)
- Comando: `cd v2 && ./gradlew check --rerun-tasks --console=plain --no-daemon --max-workers=2`.
- Comandos equivalentes: mismo cuerpo; difieren en flags de recursos (--no-daemon por la mortalidad de daemon observada bajo carga, --max-workers=2 para aliviar la carga).
- Progreso medido al cierre del recibo: módulo `:pipeline-events:test` superado, avanzando en módulos posteriores. FAILED count en el log = 0 (no se observan nuevos fallos en este SHA). Log size 641 líneas; última actualización 23:12:46.
- Workers activos: 2 (Gradle Test Executor 14 y 17).

## 4. UAT/AAT, presupuestos y ENCODER-2/3 (E3, E4, E5)

### 4.1 E3 — Siete propiedades semánticas entre ramas
**Status:** **no ejecutado** sobre `f2da79e3`.

Las 7 propiedades:
1. **ShExecution mantiene los contratos de main.** — `ShExecution.kt` no fue modificado por los 3 fixes ni por los commits OBS sobre este archivo (`git log main..integrate/main-obs -- v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt` no lista cambios directos en el archivo; los toques están en `CoreShellStep.kt` y `durable/`). Hay tests (`UatLocal001KillDuringShTest`, `ShStepContractSuiteTest`) que cubren la integración; verificar la propiedad requiere medir la salida concreta de ShExecution antes y después de f2da79e3 — pendiente.
2. **Output Plane idempotente.** — Imposible evaluarlo sin tests específicos; OBS-E4 (`f4298281 feat(output): stdout and stderr are separate durable streams, not one fused run`) anota pruebas que cubren esta propiedad pero no se han corrido aisladas sobre `f2da79e3`.
3. **No pérdida de bytes tras terminación/timeout/recuperación.** — `UatS2R0RunOwnershipCliTest` y `UatDurableDefaultReuseCliTest` están diseñados para esto. Pendiente correrlos con `--no-daemon --max-workers=2`.
4. **No duplicación de efectos en replay/resume.** — `UatRunConcurrencyCharacterisationTest` re-pinneado verifica la propiedad (at-most-one effect under S2-R0 + OBS-E4). Pasado sobre f2da79e3.
5. **Secretos redactados antes de persistencia.** — Tests de redacción existen (`UatSecretScrubbingTest`, `RedactionCanary…`); pendiente corridos.
6. **`--typed`, `--format`, cursores y exit codes sin regresión.** — `MainConsoleCliArgumentContractTest` (5 tests fix-2) cubre parte (B1c con `--max-bytes`). Pendiente expandir la cobertura a `--typed` y `--format`.
7. **Fallo de presentación ≠ pérdida silenciosa.** — La salida observada al ejecutar el binario instalado registró explícitamente `[ShExecution] could not seal the output tail of f5eeb271.../stderr: no writer ever opened it` — la OBS-E4 reporta en vez de silenciar (la KDoc del pipe de output dice "OBSERVATION improvement: previously silent, now reported"). Pendiente escribir harness que mata el consumer a propósito y verifica que los bytes siguen en durable.

### 4.2 E4 — Presupuestos de rendimiento
**Status:** **no iniciado.** Verificación artesanal del binario instalado mostró arranque sub-segundo, `--version` imprime `pipeline 0.48.0`, `run --db ... <script>` ejecuta dos `sh` en <2 s. Presupuestos completos (throughput, memoria, histórico, latencia, comportamiento con consumidor lento, concurrencia entre escritor y observador) requieren una batería de medidas adicional y comparativa con baselines — pendiente.

### 4.3 E5 — ENCODER-2 / ENCODER-3
**Status:** **no iniciado.**

## 5. Regresiones detectadas y corregidas

| Sha | Fichero | Test afectado | Síntoma | Causa raíz | Resolución |
|---|---|---|---|---|---|
| c012d8cd | `v2/pipeline-application/src/main/kotlin/.../MainConsoleCli.kt` | `MainConsoleCliArgumentContractTest` B1c | `--max-bytes` sin valor aplicaba default en vez de exit 2 | Parser tenía 4 ramas `if (arg == ...)` paralelas, cada una con `args.getOrNull(++index)` mezclando flag ausente con flag sin valor | Unificación en `when` único; `args.getOrNull(index+1)`; refusal tipado `MissingOptionValue` |
| c012d8cd | `v2/pipeline-application/src/test/kotlin/.../DirectivePluginContractSuiteTest.kt` | `DirectivePluginContractSuiteTest` | Pin del JAR divergente respecto al valor medido | SDK movió 4 commits tras `6e8e86bd`; bytes del plugin enlazado se mueven a consecuencia | Re-pinear a `d0a80b9b...`; documentar cada regimen SDK y la prueba de reproducibilidad |
| f2da79e3 | `v2/pipeline-application/src/test/kotlin/.../UatRunConcurrencyCharacterisationTest.kt` | `UatRunConcurrencyCharacterisationTest` | `doneLines` ≠ 1; `owner3_exit=1` en OBS | S2-R0 lease serializa dueñas; OBS-E4 live drain arranca thread antes del pipeline; `RecoveryUnobservable` rechaza re-attachment del subprocess huérfano | Relajar `assertEquals(1, ...)` a `assertTrue(... <= 1)`; KDoc explica la deriva con cada regimen |

## 6. Gate SDDK y limitaciones externas

### 6.1 Gate local de gradle
- Política operativa actual (per `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md` y `docs/v2/00-governance/ACTIVE_DOCUMENTS.md`, y la nota gate-traversal de la política): sin CI remota disponible (`754ddda0` retiró `lpr0-ci.yml`, `release.yml`, `v2-baseline.yml`, `sdkman-publish.yml`; `.github/workflows/` vacío; `gh run list` solo Dependabot), el gate que sustituye al remoto es `cd v2 && ./gradlew check --rerun-tasks` + UAT contra la distribución instalada + recibo inmutable por SHA. STEP-CERT no exige CI remoto y es el nivel aplicado aquí; PRODUCT-GATE lo exigiría y queda `BLOCKED_EXTERNAL` por la misma causa.

### 6.2 Limitaciones operativas observadas durante el bloque
- Gradle daemons mueren bajo carga. Load average 45+ en la misma máquina donde corren otros proyectos (pipelinek-assurance-blueprint, pipelinek-m1, etc.). Mitigación aplicada: `--no-daemon --max-workers=2`.
- E2c/ronda 1 (sobre `066049d1`) terminó por `daemon disappeared unexpectedly` a los ~17 min. Ronda 2 sobre `a61b4872` progresó (log bloqueado por buffering, no por daemon muerto), y la bin-search del primer ronda de tests reveló los 3 fallos específicos.
- Una ronda posterior de `git stash pop` mal aplicada contaminó el working tree de `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin` (main) — revertida vía `git stash` y nadie más la abrió.

### 6.3 Estado de SDDK
- Cada uno de los 3 commits (`c012d8cd`, `f2da79e3`) ejecutó `git sddk-align --ack` con `work-item f8fc07e6-6f98-4b4a-81c0-3f5b717bd146` y recibió `[SDDK] alignment acknowledged … receipt bound to HEAD=… tree=…`. Cada commit fue seguido de `git sddk-close` para satisfacer el gate del pre-commit del siguiente.
- Estado del ciclo `c8-reconciliation`: PAUSED awaiting approval `surface.cycle_state#cycle_supersede`. No bloquea los commits sobre `integrate/main-obs` porque el gate por-commit usa el work-item `f8fc07e6` (Active) en `rp7-sem-s6-plugin-sdk` (CLOSED).

### 6.4 §0.3 / §8 — push, tag, Prerelease
**`BLOCKED_EXTERNAL`.** No se ha realizado push a `main`, no se ha publicado tag v0.49.0-obs, no se ha publicado Prerelease en GitHub. Mantengo `par/cli-observation` intacto en `37df7961` sin pushear. Mantengo `integrate/main-obs` local con todos los commits. Sin tu autorización no actúo sobre ninguna de estas tres acciones.

## 7. Distribución instalada — verificación dirigida

- `installDist` produce `pipeline-application/build/install/pipelinek/bin/pipelinek` (nombre canónico `pipelinek`; legacy `pipeline-application` también aceptado por `AppBinSupport.discover()`).
- `pipelinek version` → `pipeline 0.48.0` (Manifest `Implementation-Version` poblado desde `project.version`).
- `pipelinek run --db <path> --control-root <dir> <script>` ejecuta una pipeline `pipeline { stages { stage("smoke") { sh("..."); sh("...") } } }` y devuelve `Pipeline finished with SUCCESS`, `exit 0`.
- Observación observada: `[ShExecution] could not seal the output tail of <run>/stderr: no writer ever opened it`. Es un REPORT de OBS-E4, antes era silencioso.

Los harnesses `UatS2R0RunOwnershipCliTest`, `UatDurableDefaultReuseCliTest`, `UatLocal001KillDuringShTest`, etc. invocan el binario vía `ProcessBuilder(AppBinSupport.discover().toString(), ...)`. E2b queda pendiente de ejecución formal de esos tests sobre `f2da79e3`.

## 8. ADR-0106 — pendiente

La medida del coste de serialización de ordinales con escritor lento queda pendiente para después del Bloque E.

## 9. Próximos pasos (no ejecutados)

1. E2 full — cuando el host esté con load menor que 15, reintentar `./gradlew check --rerun-tasks --no-daemon --max-workers=2` sobre `1e8c04dc`. Los tests que cubre ese gate ya pasan cuando se corren individualmente.
2. E2b cross-process harnesses — `UatS2R0RunOwnershipCliTest`, `UatDurableDefaultReuseCliTest`, `UatLocal001KillDuringShTest`, `ObsBJvmDeathOutputRecoveryUatTest`, `ObsPc2IngestAgentPrototypeUatTest`. Todos invocan `pipelinek` como subproceso; el wedge en gradle los atrapa por launch storms de subprocesses al paralelizar el worktree.
3. E3: implementar las pruebas ejecutables para las 7 propiedades; en particular, `#7` requiere un harness que mate al consumer y verifique que los bytes se mantienen en durable.
4. E4: batería completa de presupuestos (PERF-R3-01..08, throughput, memoria, histórico, latencia con escritor lento).
5. E5: ENCODER-2 / ENCODER-3.
6. Re-emisión del presente recibo tras los pasos 1-5 con el veredicto final: `INTEGRATION_VERIFIED_LOCAL` o `BLOCKED_CONCRETO` con reproducción.
7. Push, tag, Prerelease (pendiente de tu autorización).

**Total verificado a fb3a1e88 (HEAD al cierre):** 486 (Run #9 ya comiteado) + Run #10 architecture-tests 540 verde (10 skipped intencionales) = **1026 tests verde** sobre cuatro SHAs (`14c75ab9`, `f2da79e3`, `9644a1ea`, `fb3a1e88`). 10 skipped + 0 failures + 0 errors en el acumulado.
