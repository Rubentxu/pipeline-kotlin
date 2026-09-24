# RESPONSIBILITY MIGRATION ROADMAP — pipeline-kotlin → pipelinek-release-harness

> **Estado:** ACTIVO (2026-09-24).
> **Autoridad:** directiva del operador 2026-09-24T10:39Z ("empezar a completar el roadmap de migración de responsabilidades entre los dos repos y dejarlo bien definidos y con reglas en AGENTS.md de ambos repos").
> **Source of truth:** este documento + los AGENTS.md de cada repo. La decisión de mover una UAT concreta se cierra cuando su criterio de equivalencia (handover §6) pasa.

## 0. Resumen ejecutivo

PipelineK (este repositorio) **produce** candidatas inmutables con tests rápidos de contratos, arquitectura y regresiones. La certificación externa (binario instalado contra proyectos reales, sandbox contenedor, matrices de toolchains, benchmarks) vive en `Rubentxu/pipelinek-release-harness`. Este documento enumera, para cada categoría de test, dónde corre antes de la separación y dónde corre después, y los criterios formales de cierre.

## 1. Frontera operacional

| Aspecto | pipeline-kotlin (este) | pipelinek-release-harness (externo) |
|---|---|---|
| Modifica código Kotlin | **SÍ** (único) | **NO** (salvo parches al propio harness) |
| Compila binario | **SÍ** (único) | **NO** |
| Empaqueta ZIP reproducible | **SÍ** | **NO** |
| Emite manifiesto de candidata | **SÍ** | **NO** |
| Ejecuta binario instalado | **NO** (salvo canarios en este mismo repo, no como gate) | **SÍ** (único) |
| Ejecuta contra proyectos externos | **NO** | **SÍ** (único) |
| Publica checks / commit statuses | **NO** | **SÍ** |
| Promueve release tag | **NO** | **SÍ** |
| Cierra issues de defectos del producto | **NO** | **SÍ** (lo abre el harness; lo cierra el harness tras verificación externa) |
| Mantiene el ledger de certificaciones | **NO** | **SÍ** |

## 2. Catálogo de movimientos por UAT

La tabla cubre todas las UAT detectables en `v2/pipeline-application/src/test/` y unos pocos `pipeline-testkit`, `pipeline-scripting-kotlin24`. No incluye los `StepContractSuite`, fitness, ni los `Core*StepUnitTest` por su naturaleza interna.

| Test (Path) | Acción | Cuándo moverlo | Equivalencia |
|---|---|---|---|
| `UatLocal001KillDuringShTest.kt` | MIGRA al harness | Cuando exista WU-HARNESS-006 (sandbox) | 1-a-1 misma huella de kill |
| `UatLocal002ResumeAfterKillTest.kt` | MIGRA | Ídem | Replay con mismo `--db` debe dar el mismo resultado |
| `UatLocal003ReturnStdoutTest.kt` | MIGRA | Inicial (no necesita sandbox) | Captura de stdout idempotente |
| `UatLocal004TimeoutTest.kt` | MIGRA | Inicial | Mismas excepciones y runtime |
| `UatLocal005BannedImportsTest.kt` | **SE QUEDA** | — | Es test del codebase (imports prohibidos) |
| `UatLocal005CheckoutGitTest.kt` | MIGRA | Cuando exista WU-HARNESS-006 | Idéntica huella de Git clone externo |
| `UatLocal005ClassTimeoutAndTeardownTest.kt` | MIGRA | Inicial | Teardown determinista del JVM |
| `UatLocal005CorpusUntouchedTest.kt` | MIGRA | Inicial | Idem fingerprint por fixture |
| `UatLocal005EnvSpecialCharsTest.kt` | MIGRA | Inicial | Sin cambios de entorno |
| `UatLocal005GitAuthCanaryRoundGateTest.kt` | MIGRA | Cuando exista WU-HARNESS-006 | Mismo SHA de clone autenticado |
| `UatLocal005RegressionGateTest.kt` | MIGRA | Inicial | Fingerprint de regresión |
| `UatLocal005RequiresGitOnPathTest.kt` | MIGRA | Inicial | Entorno necesario del binario |
| `UatLocal006LostHeartbeatTest.kt` | MIGRA | Inicial | Heartbeat LOST classification |
| `UatLocal007SandboxProfileTest.kt` | MIGRA | **PRE-EXISTENTE ROJO** en pipeline-kotlin; pertenece al harness desde ahora | Sandbox profile LOCAL/none/os |
| `UatLocal008CredentialsTest.kt` | MIGRA | Inicial (binario instalado contra creds locales) | Misma huella de credentials resolution |
| `UatLocal008SshPrivateKeyRoundGateTest.kt` | MIGRA | Inicial | Mismo SSH key parsing |
| `UatLocal009TopStepsTest.kt` | MIGRA | Inicial | top steps ejecutados por binario |
| `UatLocal010SmokeE2ESandboxOfflineTest.kt` | MIGRA | Inicial (offline) | Smoke E2E |
| `UatLocal010SmokeE2ESandboxTest.kt` | MIGRA | Inicial | Smoke E2E con red |
| `UatLocal011WorkflowControlTest.kt` | MIGRA | Inicial | Workflow control del binario |
| `UatLocal012ErrorHandlingTest.kt` | MIGRA | Inicial | Idem |
| `UatLocal013MilestoneTimingTest.kt` | MIGRA | Inicial | Milestones + timing |
| `UatCompat001CorpusSmokeRunTest.kt` | MIGRA | WU-HARNESS-006 | Smoke run contra el corpus completo |
| `UatDsl001JenkinsFamiliarityTest.kt` | MIGRA | Inicial | DSL contra `sh`/`echo` |
| `UatDsl003ParallelTest.kt` | MIGRA | Inicial | DSL `parallel` |
| `UatDsl005TimeoutGrammarTest.kt` | MIGRA | Inicial | DSL `timeout` |
| `UatDsl006BodyExecutionTest.kt` | MIGRA | Inicial | DSL body execution |
| `UatDsl008StageOptionsFailClosedTest.kt` | MIGRA | Inicial | DSL stage options |
| `UatDurableDefaultReuseCliTest.kt` | MIGRA | Inicial | Default Reuse del CLI durable |
| `UatEvt001ReplayTest.kt` | MIGRA | Inicial | Replay de eventos |
| `UatEvt002MultiStepReplayTest.kt` | MIGRA | Inicial | Replay multi-step |
| `UatStep001ShExecutionTest.kt` | MIGRA | Inicial | Step `sh` empaquetado |
| `UatStep001ShFailureStepFinishedCountTest.kt` | MIGRA | Inicial | Step finished count |
| `UatStep002EchoCaptureTest.kt` | MIGRA | Inicial | Step `echo` con captura |
| `UatStep003ErrorAbortTest.kt` | MIGRA | Inicial | Step `error` con abort |
| `UatStep004SleepTimingTest.kt` | MIGRA | Inicial | Step `sleep` con timing |
| `UatTimeoutBlockDurableTest.kt` | MIGRA | Inicial | Block `timeout` durable |
| `UatRetryBlockDurableTest.kt` | MIGRA | Inicial | Block `retry` durable |
| `UatParallelBlockDurableTest.kt` | MIGRA | Inicial | Block `parallel` durable |
| `Lpr011r2SecretRedactionAtRestUatTest.kt` | MIGRA | WU-HARNESS-006 | Redacción at rest |
| `Lpr011SecretRedactionTranscriptUatTest.kt` | MIGRA | WU-HARNESS-006 | Redacción transcript |
| `PublishHtmlOperationsAdapterUatTest.kt` | MIGRA | WU-HARNESS-006 | Publish HTML adapter |
| `StashOperationsAdapterUatTest.kt` | MIGRA | WU-HARNESS-006 | Stash operations adapter |
| `v2/pipeline-testkit/UatM0001HelloPipelineTest.kt` | MIGRA | Inicial | Hello world del pipelinek instalado |
| `v2/pipeline-scripting-kotlin24/UatComp001ScriptCompilesTest.kt` | MIGRA | Inicial | Compilación de script |
| `v2/pipeline-scripting-kotlin24/UatComp002ErrorSourceMappedTest.kt` | MIGRA | Inicial | Source map de errores |
| `v2/pipeline-scripting-kotlin24/UatComp003CredentialsDefaultImportTest.kt` | MIGRA | Inicial | Credentials default import |

Total: **46 tests** detectados como UATs. Porcentaje de migración esperado: **45/46 = ~98%** migran. **1/46 queda** (`UatLocal005BannedImportsTest` que es test del codebase de pipeline-kotlin, no del binario).

## 3. Catálogo de lo que se queda

Tests de producto, no de certificación. Viven en pipeline-kotlin indefinidamente:

- `StepContractSuite*` (todas las variantes).
- `Core*StepUnitTest` y `Core*StepContractSuiteTest`.
- `*FitnessTest` (validación de arquitectura, READINESS, G3/G4/G5).
- `*Architectur*` (dependencias hexagonales, capas, imports prohibidos como `UatLocal005BannedImportsTest`).
- `CompatCorpusTest*` (test estructural del corpus interno — verificar que las 33 fixtures se cargan, no que el binario las ejecuta).
- Tests en `pipeline-domain`, `pipeline-events`, `pipeline-step-sdk`, etc. (suites de contratos internos).

## 4. Criterio de cierre de cada movimiento

Para que se considere **cerrado**, el siguiente criterio debe pasar (todo):

1. El equivalente del harness corre el mismo escenario sobre la misma candidata `262cc11e` y produce un resultado comparable byte-a-byte (definido en el handover §6: huella de defecto `input + síntoma + tipo + causa`).
2. La UAT original en pipeline-kotlin deja de correr en el L5 del repo (se desactiva con `@Disabled("migrated to harness; see pipelinek-release-harness#<issue>")`).
3. La issue del harness que abrió la migración recibe el commit de cierre con su recibo y digest.
4. No hay UAT migrada cuyo sustituto en el harness esté en estado "no verificado contra el mismo defecto".

Ningún movimiento puede marcarse como **cerrado** sólo porque la documentación haya viajado al harness.

## 5. Calendario propuesto por fases

| Fase | WUs | Tests afectados |
|---|---|---|
| **M0 — Bootstrap del harness** | WU-HARNESS-001 + 002 (esquema manifiesto) | Preparación, sin UATs aún. |
| **M1 — Migración inicial (~14 tests, sin sandbox)** | WU-HARNESS-003 + WU-HARNESS-004 r1 | `UatLocal003..004`, `UatDsl*`, `UatStep001..004`, `UatDurableDefaultReuse*`, `UatTimeout/Retry/Parallel*`, `UatEvt001..002*`, `UatLocal011..013`, `UatLocal005Corpus/Env/Regression/RequiresGit`, `UatCompat001` (parte que no necesita sandbox), `v2/pipeline-testkit/UatM0001*`, `v2/pipeline-scripting-kotlin24/UatComp*`. |
| **M2 — Sandbox (~17 tests, con Podman/Docker)** | WU-HARNESS-006 | `UatLocal001..002Kill/Resume`, `UatLocal005Checkout/AuthCanary`, `UatLocal006/007`, `UatLocal010*E2E`, `Lpr011*`, `PublishHtmlAdapter`, `StashOpsAdapter`, `UatCompat001` parte sandbox. |
| **M3 — Cierre formal** | WU-HARNESS-008 | Apertura del gate de promoción con todos los movimientos cerrados. |

Total estimado: 46 UATs migradas en ~3 fases.

## 6. Reglas firmes para `AGENTS.md` de pipeline-kotlin

El bloque añadido a `AGENTS.md` debe reflejar, sin ambigüedad:

1. Este repo **NO mantiene** tests que dependan del binario instalado, del corpus externo o de proyectos reales externos. Su rol termina cuando la candidata se entrega al harness.
2. Los `@Disabled("migrated to harness; see pipelinek-release-harness#<issue>")` son la marca correcta para una UAT migrada. **Cualquier intento de reactivar** una UAT migrada requiere reabrir la issue del harness primero.
3. Las UATs `UatLocal*`, `UatCompat*`, `UatDsl*`, `UatStep*`, `UatEvt*`, `UatDurable*`, `Lpr011*`, `PublishHtml*`, `StashOps*` que aún queden en pipeline-kotlin se declaran **legado**: pertenece al harness, y deben migrar en cuanto exista WU-HARNESS-006.
4. Los tests internos (contratos de Step, fitness, regresiones internas del codebase) **se mantienen**.
5. La candidata que se entrega al harness ya viene con **todos los UATs internos del producto en verde**. El harness verifica el resto.

## 7. Reglas firmes para `AGENTS.md` del harness

El nuevo repositorio `Rubentxu/pipelinek-release-harness` debe declarar en su `AGENTS.md`, sin ambigüedad:

1. El harness **NO modifica el código de PipelineK** ni abre PRs contra `pipeline-kotlin/src/main/**`.
2. El harness **recibe candidatas inmutables** (ZIP + SHA-256 + manifiesto) y emite veredictos estructurados (`verdict.json`, NDJSON, ledger.jsonl).
3. Toda prueba externa corre contra el **binario instalado** de la candidata, **NO** contra el código fuente.
4. El harness abre **una sola issue de coordinación por defecto del producto** con huella estable (`contrato + escenario + tipo + causa`); no por SHA de candidata.
5. El harness es responsable de **cerrar la issue** cuando la corrección se verifique en distribución instalada.
6. El harness publica estados en GitHub (check runs + commit statuses) pero el resultado+evidencia NO vive en comentarios sueltos de PR.
7. El harness **promueve los mismos bytes del ZIP** verificado a release estable; nunca reconstruye.
8. La autonomía de cada repo se respeta: este repo no es el del producto, y viceversa.

## 8. Procedimiento de cierre de ciclo

Cada movimiento de UAT debe cerrar con su **recibo** específico en el harness:

```
pipelinek-release-harness/docs/uat/UAT_LOC_007_MIGRATION_RECEIPT.md
```

El recibo contiene: SHA de la candidata sobre la que se hizo equivalencia, fingerprint del NDJSON, código de la prueba, lista de issues resueltas, y rollback plan (cómo reactivar el @Disabled en pipeline-kotlin si la equivalencia rompe).

## 9. Estado al 2026-09-24

- **0/46 UATs migradas**. Plan activo.
- Bloque `Release candidates` en `pipeline-kotlin/AGENTS.md` ya recoge parte de la separación.
- Pendiente: añadir el bloque espejo al AGENTS.md del harness (entregable a través del operador al crear el repo).
- El roadmap del harness mismo es responsabilidad del agente del harness; este documento NO es ese roadmap, es la **tabla de movimientos** desde pipeline-kotlin.

## 10. Anti-patrones explícitos

NO se hace:

- Mover una UAT al harness **sin antes** haber migrado un sustituto ejecutable que verifique el mismo defecto.
- **Borrar** una UAT de pipeline-kotlin antes de su migración al harness (debe quedar `@Disabled("migrated…")` con enlace a la issue).
- **Incluir** mensajes "test suite eliminado para ahorrar tiempo" en recibos de release.
- Copiar el cuerpo de una UAT a un gist o copy-paste en otra UAT; la equivalencia debe ser reproducible por huella (`input + síntoma + tipo + causa`).
- **Bloquear** desarrollo de pipeline-kotlin esperando migración: las dos líneas avanzan en paralelo; los `@Disabled("migrated…")` se mantienen hasta que la migración es verde.
