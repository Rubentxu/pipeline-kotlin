# R2 RECEIPT — OutputProtocol y CLI TestKit

**SHA evaluado:** `d443215e` (HEAD local al cierre de R2.4 / WURp023)
**Fecha:** 2026-10-12T21:15Z
**Ciclo:** pk-release-0-57-consolidation
**Entorno:** host load 6-9 (umbral seguro < 5). El gate completo de R3
queda bajo `BLOCKED_ENVIRONMENT`; los commits de R2 se cierran por
inspección de código y tests focales ejecutados contra fixtures, no
por `check` integral.

## Sub-bloques y su estado

| Sub | Entrega | Estado | Evidencia |
|---|---|---|---|
| **R2.1** | `OutputProtocol` enum + `--output-protocol=<id>` + dispatch en `Main.kt` | **IMPLEMENTADO** | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/OutputProtocol.kt` (enum `Legacy` / `SeparatedV2`); `Main.kt:1303-1320` (canal stderr para SeparatedV2) |
| **R2.2** | `CliContractTestKit` + `Subprocess` con drenaje concurrente, cierre de árbol de procesos y límites de memoria | **IMPLEMENTADO** | `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/support/Subprocess.kt` — `DRAIN_CHUNK_BYTES=8KB`, `CAPTURE_LIMIT_BYTES=8MB`, `Thread({ drain() }, name).isDaemon=true` por stream, `descendants().toList()` + reap recursivo. `SubprocessHarnessTest` (10 tests) cubre el contrato: drain concurrente, kill de árbol, timeouts con diagnóstico, refusals tipados, exit code preservation |
| **R2.3** | `ProcessOutputDecoder` con enum `Protocol { Published, SeparatedV2, Unknown }` + dispatch por protocolo + matriz de 8 tests | **IMPLEMENTADO** | `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/support/ProcessOutputDecoder.kt` — enum `Protocol`; `ProcessOutputDecoderTest` (matriz 8 casos) |
| **R2.4** | Migración de UAT históricas conservando oráculos | **PARCIAL — 2 de 1 site corregidos** | `UatDsl005TimeoutGrammarTest` migrado en 55561dda; `WURp023ObservationModesUatTest` migrado en d443215e. `ObserveReplayEncoderEquivalenceTest::ENCODER-5` con `startsWith("[")`/`endsWith("]")` es **legítimo**: testea la vista `--view events --format json` (array JSON de envelopes, sin recibo). El audit solo aplica al canal de `run`, no a la vista de replay |
| **R2.5** | Resolver formalmente el contrato durable/no durable de `LaunchReceipt` | **CERRADO** | `docs/adr/ADR-PK1-LAUNCHRECEIPT-MODES.md` (c9df67f1); KDoc de `LaunchReceipt.kt` declara "**Launched is a guarantee of LAUNCH, not of DURABILITY**". Migración de tests de métricas a 0.58 documentada en el ADR |
| **R2.6** | Consumidor externo real | **PARCIAL** | `examples/fabric-contract-consumer/.../FabricProjection.kt` enumera los 7 nuevos `OutputRefusal` de SDK 0.57 (commit bc2410e7). El build e2e del consumer contra el SDK 0.57.0 publicado es responsabilidad del bloque R4 |

## Auditoría del testkit (R2.2)

El testkit de procesos (`Subprocess` + `CliRun` + `OwnedSubprocess`)
cumple las propiedades del brief:

- **Drenaje concurrente de stdout y stderr** — `Thread({ drain() }, name)`
  por stream con `isDaemon = true`. Ambos streams son PIPE
  simultáneamente desde el instante que el hijo arranca.
- **Cierre del árbol de procesos** — `descendants().toList()` +
  `descendants.reversed().forEach { destroyForcibly() }` con
  `descendantsTerminated` como diagnóstico. Una llamada a
  `destroyForcibly()` solo sobre el CLI padre dejaría nietos vivos
  y es explícitamente rechazada.
- **Límite de memoria** — `CAPTURE_LIMIT_BYTES = 8 * 1024 * 1024`
  (8 MB) por stream. El drain continúa leyendo del pipe para evitar
  el pipe-buffer hazard, pero deja de copiar al sink cuando se
  alcanza el límite. Un hijo que escribe 100 MB no produce OOM
  en el harness.
- **Errores tipados** — `sealed interface CliRun` con
  `Completed(exitCode, stdout, stderr, ...)` / `TimedOut(diagnostics)`
  / `LaunchFailed(cause)`. Los `accessor refuses the other outcomes`
  tests de `SubprocessHarnessTest` pinan que un `Completed` no
  expone campos de `TimedOut` y viceversa.
- **Exit code preservation** — el código se preserva en
  `Completed.exitCode`; los tests `killing a process that already
  finished says so and keeps its exit code` y
  `killing a live process reports a kill and invents no exit code`
  pinan ambas ramas.

## Conservación del contrato `pipelinek.launch/1`

El brief R2 fue explícito: "No modificaría `LaunchReceipt.SCHEMA`
por comodidad. Debe permanecer `pipelinek.launch/1` salvo decisión
explícita de evolución del contrato." Confirmado:

- `LaunchReceipt.SCHEMA = "pipelinek.launch/1"` (sin cambios).
- El ADR-PK1-LAUNCHRECEIPT-MODES NO añade un campo `mode` al
  schema; la distinción durable/no-durable se infiere por la
  invocación (`--db <path>`) o por `OutputRefusal.recoveryGate`.
- El KDoc declara explícitamente que la distinción NO se hace
  por el schema.

## Lo que R2 NO hace

- R2.6 (consumer e2e) depende de la publicación del SDK 0.57.0
  en un repositorio. La publicación es bloque R4.
- Las métricas de migración del ADR-PK1-LAUNCHRECEIPT-MODES
  (test in-memory no escribe journal; test ambos modos emiten
  mismo schema) están diferidas a 0.58 según el propio ADR.
- R3 (re-medir WURp023, UatS2R0, smoke-e2e, workflow-control)
  queda bajo `BLOCKED_ENVIRONMENT` con load 1-min > 5.

## Próximo paso (R3)

Cargar `load < 5` y correr la matriz de 4 UAT pendientes:

1. `WURp023ObservationModesUatTest` (ya migrado a la contrato
   R0.3; pendiente de ejecución real contra binario instalado).
2. `UatS2R0RunOwnershipCliTest` (lease cross-process; el audit
   de la rama rc6 lo clasificó como "fork-storm + --max-workers
   wedge", no regresión de código).
3. `smoke-e2e` (FAIL en rc5): clasificar como producto / fixture /
   protocolo / entorno.
4. `workflow-control` (FAIL en rc5, `deleteDir` en workspace
   ajeno): clasificar de la misma forma.

Después, `check` integral tres veces consecutivas, con
`--max-workers=1 --no-daemon --rerun-tasks` y `--console=plain`,
midiendo load promedio.
