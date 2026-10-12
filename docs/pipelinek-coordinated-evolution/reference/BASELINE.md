# Baseline verificable y gaps que gobiernan el primer bloque

**Fecha:** 2026-10-10. **Revisado por lectura de código** (`main` en SHAs recogidos en README), no por `DONE` en un documento. No se han ejecutado suites de los repos ni contrastado ramas remotas no `main` para este paquete. Al comenzar M0, repetir en HEAD del agente e indicar CHANGED/UNCHANGED para cada hallazgo.

## PipelineK — elementos ya existentes, no reimplementar

- `v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputReadPort.kt`: `committedExtent`, `read`, `readRange`.
- `v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputTailState.kt`: `Open`, `Sealed`, `OutputTailPort`.
- `v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentOutputStore.kt`: reserva, commit, seal, recover, prune, lectura por rango. No publicar layout interno como ABI.
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/OutputPlaneProvider.kt`: lectores/escritores de Output Plane; revisar aislamiento multi-JVM y recovery.
- `v2/pipeline-output` — `OutputFrameIndex`: indexación del orden observado de frames, distinto del orden global entre eventos y bytes.
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/EventStore.kt`: store de eventos con cursor.
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/observation/ObservationQuery.kt`: filtros locales AND entre dimensiones; combinaciones stage + output actualmente requieren asociación de contexto, no un `event?.stage`.
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/observation/ObservationOutputFollower.kt`: wakeup seam; comprobar conexión real productor→notificación.
- `v2/pipeline-step-sdk/runtime/.../DurableShellExecutor.kt`: pumps concurrentes stdout/stderr y redacción para transcript; no bloquear un pipe esperando el otro. `returnStdout` es canal de valor y no se convierte en log exportable automáticamente.
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RunOutputRetention.kt`: retención al terminal; verificar conflicto con pinning de réplicas.

**Autoridades documentales relevantes:** `docs/v2/05-roadmap/ROADMAP.md`; `docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` (parcial/no certificada en la versión revisada); ADR-0085, ADR-0086, ADR-0088; `docs/v2/02-architecture/OBSERVATION_STREAM_ARCHITECTURE.md`. Son especificaciones/progreso propuesto, no evidencia sustitutiva de pruebas.

## Fabric — elementos ya existentes, no reimplementar

- `contracts/worker-session/.../WorkerHandshake.kt`: negociación de versiones/features.
- `contracts/worker-session/.../WorkerLease.kt`: lease y verdict de fencing.
- `control-plane/scheduler/.../FencingWorkerScheduler.kt`: ocupación worker/run, lease; un run por worker en modelo actual visible.
- `control-plane/application/.../LocalRunControl.kt`: admisión y lifecycle local.
- `contracts/transport/.../StreamFraming.kt` y `docs/decisions/ADR-0022-the-carrier-is-a-persistent-stream.md`: carrier persistente adoptado, no reemplazar sin evidencia.
- `worker/spool/.../FileSpool.kt`: persistencia/ACK de entradas y `pending`; requiere corrección si se encuentran entradas corruptas invisibles.
- `worker/agent/.../SpoolDrain.kt`: conserva mensajes no reconocidos, ACK solo para coincidencia de clave; reutilizar lógica, endurecer para alto volumen.
- `control-plane/application/.../FactNoticeReceiver.kt`: **OutputSpooled → Retained(NotDurable)** actualmente. Fact pages detectan duplicación mediante identidad del executor, no por prueba de intervalo ya aplicado: la deduplicación debe verificarse/corregirse antes de stream incremental.
- `worker/pipelinek-runtime-adapter/.../local/LocalPipelineKExecutionStrategy.kt`: drena stdout en `StringBuilder` y `facts()` espera el proceso con `waitFor` antes de traducir; ruta remota no verdaderamente live.
- `worker/pipelinek-runtime-adapter/.../local/PipelineKConsoleSource.kt`: lectura lanza un proceso CLI por página; reemplazar con port/bridge de larga duración, no tratar un subprocess por página como transporte final.
- `worker/pipelinek-runtime-adapter/.../PipelineKConsoleSourceAdapter.kt`: ya adapta `OutputReadPort` a ConsoleSource; extender, no duplicar.
- `adapters/control-api/proto/src/main/proto/fabric_control.proto`: `ObserveRun` y `ReadConsole` son unary RPC; añadir streaming de forma compatible.
- `adapters/control-api/grpc-server/.../NorthboundConcurrencyInterceptor.kt`: límite general in-flight; requiere separación para streams largos y control.
- `adapters/jenkins/plugin/.../FabricLogStorage.java`: read-side Jenkins paginada, Stage View proyección existente. No reemplazar por motor Groovy.
- `projection/kernel/.../ProjectionReducer.kt`: autoridad de interpretación de hechos. No derivar terminales desde console.

**Autoridades documentales:** `docs/decisions/ADR-0022-the-carrier-is-a-persistent-stream.md` **ADOPTED**; ADR-0024 fencing **ADOPTED**; ADR-0012 de límites y semántica de observación **ADOPTED** (incluye corrección sobre silencio: no concluir que un run muere por no generar datos); `docs/pipelinek-fabric-proposal-2026-10-03/01-specifications/JENKINS_SEAMS_AND_RED_LINES.md`; `docs/known-limitations.md`.

## Gaps iniciales (recomprobar en HEAD, no asumir que siguen abiertos)

| ID | Hallazgo observable | Bloqueador |
|---|---|---|
| G0-01 | `FactNoticeReceiver`: dedupe de facts no verifica rango ya aplicado | M0/M3 |
| G0-02 | `FactNoticeReceiver`: no admite `OutputSpooled` durable | M0/M3 |
| G0-03 | `FileSpool.pending` `mapNotNull` sobre lectura fallida puede ocultar corrupción | M0 |
| G0-04 | Fuente local Fabric no emite facts vivos, espera `waitFor` | M1 |
| G0-05 | Reader CLI por página, coste de proceso y pipe | M1/M4 |
| G0-06 | Faltan seguimientos northbound reales y cursores independientes | M4 |
| G0-07 | Pinning de retención ante ACK remoto aún no certificado | M3/M5 |
| G0-08 | Riesgos de escaneo O(n), UTF-8 parcial y wakeups descritos en OBS-PC | M1/M6, medir |
| G0-09 | No existe seam estable para archivo externo canónico | M5 |
| G0-10 | Necesidad de fairness por run/tenant/consumer y P0 cancel | M6 |

## Baseline mínimo obligatorio antes de diseñar cambios

`git status --short`, `git branch -vv`, `git rev-parse HEAD`, `git tag --list | tail`, `git remote -v`, dependencias resolubles, suite focal actual, baseline latencia local, directorios de estado, tests existentes equivalentes. No editar tests para fabricar éxito ni atribuir porcentajes de cobertura a una revisión solo documental.
