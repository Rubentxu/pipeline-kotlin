# S1-R0 — RUN-CONCURRENCY-1 characterisation receipt

**Fecha:** 2026-09-29T20:17Z
**Ciclo SDDK:** `p-733fb505b5a6bd2d/s1-r0-concurrency-characterisation` (base `fbe10b12`)
**Backlog item ejecutado:** `bl-bl-01M3PVS4W1000387DQHFYH2M40` (promoted a este ciclo)
**Defecto confirmado capturado:** `bl-bl-01M3QD197Q000387ET2D4MFKR0` (P1)
**SHA auditado:** `fbe10b12e562d648338f19df792c011db0bf35ca` (main, == origin/main)
**Entorno:** bazzite, kernel 6.x fc44, 64 cores, 94 GiB RAM, JDK 24.0.2 (Temurin), Gradle 8.14.5,
btrfs sobre `/dev/nvme0n1p3` (1,9 T, 70 % usado, 558 G libres)
**Clase de evidencia:** OBSERVED (procesos CLI reales, base de datos durable real)

---

## 1. Pregunta del gate (operator-bound, `s1-directive-kernel-v1`)

> Los runs durables no tienen ownership entre procesos. DbLock es un
> `ConcurrentHashMap` por JVM; `sequenceCounters` son `AtomicLong` en memoria seedados
> al arrancar; la tabla `events` no tiene `UNIQUE(run_id, sequence)`. Dos JVMs que
> reanudan el mismo `runId` pueden ejecutar efectos y anexar ambas la secuencia N.
> **Ley: durabilidad sin ownership está incompleta bajo concurrencia.**
> Experimento de caracterización obligatorio ANTES de cualquier policy de directiva
> con efectos concurrentes o ownership distribuido.

Superficie verificada en el código antes de experimentar (STRUCTURAL):

| Elemento | Ubicación | Hecho observado |
|---|---|---|
| `DbLock` | `v2/pipeline-events/.../events/durable/DbLock.kt:29-44` | `object` con `ConcurrentHashMap<String, ReentrantLock>` → serializa **solo dentro de la JVM** |
| Contador de secuencia | `SqliteEventStore.kt:34` (`sequenceCounters`), `:134-151` (`seedSequenceCounters`), `:216` | `AtomicLong` por `runId`, seedado con `MAX(sequence)` **en la construcción del store** |
| Esquema `events` | `SqliteEventStore.kt:88-97` | `PRIMARY KEY` ausente; **no** existe `UNIQUE(run_id, sequence)` |
| Selección de runId | `MainDurableRunSupport.kt:19-33` | `ReusePriorRun` reutiliza el último id; `ResumePriorRun` **reutiliza el mismo id**; `StartFreshRun` genera uno nuevo |
| Policies CLI | `CliParser.kt:147-160` | `--resume` → `ResumePriorRun`; `--rerun` → `StartFreshRun` |

**Consecuencia de diseño (OBSERVED en el primer intento del experimento):** la única vía por
la que dos procesos resuelven el **mismo** `runId` es `--resume`. Un intento inicial con dos
`--rerun` concurrentes produjo **dos** `runId` distintos (`--rerun` arranca un run fresco por
definición), es decir, no produce dos dueños. El experimento se rediseñó sobre esa base: la
condición de dos dueños requiere un run **incompleto** al que dos procesos `--resume`.

## 2. Experimento

`UatRunConcurrencyCharacterisationTest`
(`v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatRunConcurrencyCharacterisationTest.kt`)

1. Un proceso (**dueño #1**) ejecuta un `sh("echo started >> marker; sleep 30; echo done >> marker")`
   con `--db`/`--control-root` nuevos. Se le mata por fuerza cuando el efecto está en vuelo
   (el marker contiene `started`), dejando el run durable **incompleto**.
2. Se toman las líneas de `events` del `runId` dominante (5 filas) como baseline.
3. Se lanzan dos procesos (**dueños #2 y #3**) que ejecutan `run --db … --control-root … --resume`
   sobre el **mismo** estado, liberados juntos por un `CyclicBarrier` para solapar sus ventanas.
4. Se observan, sobre la base de datos durable real: el marker de efecto, el número de filas
   de `events` del `runId`, y `COUNT(DISTINCT sequence)` frente a `MAX(sequence)`.

**Reproducción del efecto de carrera:** el dueño #1 se mata con `destroyForcibly()` (proceso
hijo incluido, vía `@AfterEach` `descendants().destroyForcibly()`), lo que replica el
escenario operativo de "proceso muerto a mitad de un efecto".

## 3. Resultado OBSERVED (ejecución que pasó)

```
RUN-CONCURRENCY-1 CHARACTERISATION
run_id=79e8f42b-3d0e-429e-b6bf-c219765e9564
event_rows_before_resume=5
event_rows_after_resume=21
distinct_sequences=13
max_sequence=13
marker_lines=2 (started=1)
owner2_exit=0 owner3_exit=0
journal_operation_rows=1
```

| Propiedad | Resultado | Lectura |
|---|---|---|
| Convergencia de `runId` | los dos dueños resolvieron el **mismo** `runId` | el experimento sí produjo dos dueños activos |
| **Efecto duplicado** | **NO** (`started=1`, `done=1`, `marker_lines=2`) | la memoización del operation journal deja que **sólo un** dueño re-ejecute el `sh` incompleto |
| Filas de `events` bajo el mismo `run_id` | 5 → 21 | ambos dueños publican en el mismo run |
| **Secuencias duplicadas** | **SÍ** (`rows=21`, `distinct=13`, `max=13` → 8 filas colisionan) | **defecto confirmado** |
| Filas de `operation_journal` | 1 | una sola identidad de operación; el resto son eventos de los dos dueños |
| Exit code de ambos dueños | 0 / 0 | ninguno detecta al otro como conflicto; **no hay fail-closed** |

**Conclusión (OBSERVED):**

1. La hipótesis del backlog "dos dueños activos ejecutan ambos los efectos" **NO se reproduce**
   en la frontera de efectos: la memoización del operation journal serializa de facto la
   re-ejecución. Esto **degrada la severidad** del riesgo hypothesized.
2. La hipótesis "dos dueños anexan ambos la secuencia N" **SÍ se reproduce y se confirma**:
   la secuencia por run **no es un invariante durable** mientras dos dueños se solapan. Cada
   JVM avanza su propio `AtomicLong` desde su propio seed (`MAX(sequence)` en su arranque), y
   no existe `UNIQUE(run_id, sequence)` que lo impida.
3. Ningún dueño detecta al otro: ambos terminan en `exit 0` sin conflicto ni fail-closed.

## 4. Veredicto sobre el gate

**RUN-CONCURRENCY-1: characterised. La caracterización es obligatoria y está hecha; el
experimento se puede cerrar como EJECUTADO.** El gate deja de bloquear el trabajo puro y de
interpretación, **pero no se resuelve con este slice**: la evidencia sostiene que la policy
correcta NO es un lock de exclusión de efectos (el journal ya lo cubre) sino **ownership de
secuencia/publicación**:

- `RunExecutionLease` + `fencing token` en la autoridad de secuencia, o
- asignación de secuencia **dentro de la transacción del writer** (la fila del contador se
  vuelve derivable de la propia tabla), o
- `UNIQUE(run_id, sequence)` como invariante de fail-closed que hace visible la violación.

Eso **no** se implementa en este slice: es un cambio de contrato durable y requiere su propio
ciclo. Se registra como `bl-bl-01M3QD197Q000387ET2D4MFKR0` (P1).

**Decisión operativa (dentro de la ley operator-bound):** la policy de directiva con efectos
concurrentes sigue siendo el trabajo de un ciclo posterior; este slice entrega la
caracterización que la ley exigía, sin abrir interpretación.

## 5. Evidencia reproducible

| Evidencia | Detalle |
|---|---|
| Test | `UatRunConcurrencyCharacterisationTest` (1 test, `@Timeout(300)`, sin cambios de producción) |
| L0 | `:pipeline-application:compileTestKotlin` → BUILD SUCCESSFUL |
| L1 (GREEN) | `v2/gradlew -p v2 :pipeline-application:test --tests 'UatRunConcurrencyCharacterisationTest*'` → exit 0 |
| XML canary | `TEST-…UatRunConcurrencyCharacterisationTest.xml` — `tests=1 skipped=0 failures=0 errors=0`; sha256 `94c99b5ec819b5cef3eb2a2c5591b5d5a04413e294b3d5a2bf743269fb1eb968` |
| Log L1 | sha256 `7ac607e49a3bbf471e4d6c2a56e20a59080d1ecdd123d05f5c6fb13f17aa9d1d` |
| Run L5 (WU-A, este mismo ciclo) | `v2/gradlew -p v2 check --rerun-tasks` → BUILD SUCCESSFUL 20m13s; log sha256 `394d81e61fffe72c47d59fd6af8dec046e45f49ee37d6ec4abcfaac48b656ca8` |
| Cobertura L5 | 559 XML de test frescos en 22 módulos; `failures=0 errors=0` en los 4 módulos citados por el P1 (application 1827, sdk/runtime 201, scripting-kotlin24 56, domain 588) |

## 6. WU-A — P1 `bl-bl-01M3HYC2FB0003873WVH8V2AG0` (JUnit XML write-failure): NO REPRODUCIBLE

El P1 ("Could not write XML test results" para 10 clases en 4 módulos bajo `check --rerun-tasks`)
se investigó con sondas progresivas y **no se reproduce en HEAD**:

| Sonda | Invocación | Resultado |
|---|---|---|
| 1b | `:pipeline-step-sdk:runtime:test --tests 'ShOptionsTypedEnvTest*' --rerun-tasks` | exit 0, sin error XML |
| 2 | `:pipeline-step-sdk:runtime:test --rerun-tasks` (módulo completo, 3 clases del hallazgo) | exit 0, sin error XML |
| 3 | `:pipeline-scripting-kotlin24:test --rerun-tasks` (2 clases del hallazgo) | exit 0, sin error XML |
| 4 | `:pipeline-application:test --tests 'UatStep003*' --rerun-tasks` | exit 0, sin error XML |
| 5 | dos módulos en una invocación (paralelismo multi-módulo) | exit 0, sin error XML |
| 6 | **`v2 check --rerun-tasks` completo** (266 tareas, 20m13s) | **BUILD SUCCESSFUL, 0 ocurrencias del error** |

Descartadas por observación: disco (558 G libres), permisos (btrfs, `/var/home` escribible),
longitud de ruta, y configuración de test (los `build.gradle.kts` de los 4 módulos sólo
cambiaron por *version bumps* desde la captura del 2026-09-27; `maxParallelForks=2` /
`forkEvery=40` son de `d7be6300`, del 2026-09-21, anteriores a la captura). No hay cambio de
código en las rutas involucradas entre la captura y HEAD.

**Lectura:** alerta sin verificar reproducible → por la regla de la skill autonomo
("deuda sin verificar si sus criterios siguen vigentes no es deuda real") **no es deuda real
en HEAD**. No se implementa ningún fix. El item **permanece Triaged en el ledger** (no se
descarta): el conjunto de razones de `sddk backlog discard` es cerrado
(`superseded|wontfix|duplicate`) y "no reproducible en HEAD" no pertenece a él; forzar una de
esas razones sería una falsificación del ledger. Queda como item triado sin acción, con esta
evidencia como referencia.

## 7. Cierre de trabajo (checklist AGENTS.md)

```text
Reference implementation consulted: n/a — internal durability characterisation;
  no external reference applies to cross-process run ownership at the CLI seam
Behaviour adopted:        two-owner experiment over the real CLI + real SQLite state
Intentional deviations:   the test PINS the observed defect (duplicate sequences) instead
                          of asserting the hypothesised one (duplicate effects), because the
                          hypothesised behaviour is not reproduced
Security implications:    n/a for this WU — no trust boundary changed; the finding is a
                          data-integrity one, not a sandbox/credential one
Tests demonstrating:      UatRunConcurrencyCharacterisationTest (1/1, XML canary above)
```

## 8. Estado y siguiente paso

- S1-R0 (RUN-CONCURRENCY-1) **caracterizado y ejecutado**; deja de ser un bloqueo sin
  evidencia y pasa a ser deuda con veredicto (P1 `…97Q000387ET2D4MFKR0`).
- P1 XML write-failure: **no reproducible**, sin acción de código.
- Siguiente trabajo de valor (elegido por el orquestador): el defecto de secuencia es el
  candidato natural, pero **no** es un fix pequeño: cambia el contrato durable. El fix P4
  `DEBT-CLI-SCRIPT-NOT-FOUND` (stacktrace crudo ante script inexistente, `Main.kt:401`) sí es
  un fix pequeño y de entrega inmediata de calidad CLI.
