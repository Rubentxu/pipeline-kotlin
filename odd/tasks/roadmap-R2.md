# PipelineK — R2 OBS-2 supervivencia real (operador 2026-10-13)

**Baseline:** `origin/main@1cc37932` (R1.5 ABI delta doc)
**SDDK WorkItem:** `93158a45-2997-49ff-9a85-7906c56344c6`
**Foco:** cerrar la OBS-2 Nivel B (ADR-OBS-003 §4) en producción, no en
prototipo.

## Principio

- Mover el prototipo ObsPc2IngestAgent (ADR-OBS-003) a producción
  supervisada, exactamente como el §2 del ADR describe y el §4
  verifica.
- El inverso del spike es la prueba: la fila 0/50 tiene que
  pasar a 50/50 con `DONE` después de matar la JVM del runtime.
- Reusar DurableShellExecutor y DurableShellLaunching sin
  bifurcarlos: añadir una opción "pumped transcript via agent" en
  lugar de crear un segundo runtime.
- Sin secretos en disco (raw_secret_on_disk == 0 sigue siendo la
  invariante).

## R2.1 — Cerrar §4 del ADR-OBS-003

**Problema:** ADR-OBS-003 §4 lista la verificación que la
implementación debe ganar, pero el §4 deja abierto el "shape"
concreto de la implementación: ¿cómo lanza el runtime el agente,
cómo lo supervisa, cómo lo mata al cerrar el run, y cómo se
recompone tras un restart?

**Decisión (R2.1):**

- **Granularidad:** un agente por run, no por step. ADR §5
  recomienda amortizar el coste del proceso entre los steps del
  run; seguimos esa recomendación.
- **Rendezvous:** FIFO en `controlRoot/<runId>/fifos/` (no en
  tmpfs efímero: el agente debe poder re-attach tras un restart
  del runtime).
- **Lanzamiento:** el runtime lanza el agente como un proceso
  separado con `ProcessBuilder` + `nohup`-equivalente
  (setsid+disown). El agente se desconecta de la JVM del runtime
  pero no de su control root.
- **Supervisión:** el runtime mantiene un mapa `runId -> agent
  Process`. Al cerrar el run (terminal normal o shutdown), el
  runtime envía SIGTERM al agente, espera 5s, y luego SIGKILL.
- **Re-attach:** tras un restart, el runtime escanea
  `controlRoot/<runId>/fifos/` por FIFOs huérfanos y arranca
  agentes que aún no estén corriendo. Esto se hace en
  `DurableRunCoordinator` arranque.
- **Detección de terminación:** el agente no usa SIGPIPE para
  detectar que el child terminó. El child termina cuando
  `RunFinished` aparece en la journal. El agente observa el
  event store (read-only) y termina cuando ve `RunFinished` o
  recibe SIGTERM del runtime.

## R2.2 — Mover ObsPc2IngestAgent a src/main

- Crear `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/observation/ingest/ObsPc2IngestAgent.kt`
  con la misma lógica que el prototipo pero:
  - Paquete de producción (no test).
  - KDoc explicando que esta es la versión de producción, no el
    prototipo.
  - Cross-references a ADR-OBS-003 §2.1, §2.2, §4.
- El prototipo en `src/test/` se queda como referencia y como
  fixture de tests adversariales.
- L0: `compileKotlin` verde.
- L1: `ObsPc2IngestAgentPrototypeUatTest` sigue pasando (la
  fuente no cambia; el prototipo se mantiene).

## R2.3 — Lanzar y supervisar el agente desde DurableShellExecutor

- Modificar `DurableShellLaunching.buildWrapper` para incluir
  `trap '' PIPE` cuando `config.pumpedViaAgent == true` (ADR §2.3).
- Modificar `DurableShellLaunching.launch` para:
  - Crear los FIFOs en `controlRoot/<runId>/fifos/`.
  - Lanzar el child con `Redirect.to(stdoutFifo)` /
    `Redirect.to(stderrFifo)` en lugar de `Redirect.PIPE`.
  - Lanzar el agente como un proceso separado
    (`ProcessBuilder` + setsid) y guardar su handle en el mapa
    de supervisión.
- Modificar `DurableShellExecutor` para:
  - Pedir el par "agent+launcher" cuando la transcript es
    pumped (no cuando es returnStdout).
  - Al cierre del run, enviar SIGTERM al agente y luego SIGKILL.
- HF3: SIGKILL real, desenchufando la JVM del runtime mientras
  el child sigue vivo. Medir que el agente drena los bytes que
  el child escribe DESPUÉS de la muerte del runtime.
- §4 Inversion:
  - `ObsPc2ProducerSurvivalSpikeTest.after_lines_written` debe
    pasar de 0/50 a 50/50.
  - `bytes_after_release > bytes_after_kill` debe cumplirse.
  - `DONE` debe aparecer tras el kill (step completa post-mortem).
- HF5: una mutación que haga al agente reenviar bytes crudos
  debe RED `raw_secret_on_disk == 0`. Una mutación que pare al
  runtime de lanzar al agente debe RED `DONE`.

## R2.4 — ReleaseGate verde

- Suite focal verde: `ObsPc2IngestAgentPrototypeUatTest`,
  `ObsPc2ProducerSurvivalSpikeTest`, `DurableShellExecutor*Test`.
- Suite de aplicación verde.
- ABI documentada: la nueva opción `pumpedViaAgent` se publica
  en la API del config; el delta 0.58 → 0.59 es aditivo (no
  rompe 0.58).
- ZIP + SDK + SBOM + SHA256SUMS.
- PR, merge, tag `v0.59.0-rc1`.

## No abrir

- Wakeups de commit: el polling del agente es a 25 ms; el
  CommitWakeupPort sigue aplazado (R3 decidirá).
- Reinicio sin control root: el agente requiere control root
  persistente, no memoria volátil. Sin state in-memory.
- Recolector de bytes crudos en memoria: la redacción ocurre
  ANTES del sink; el redactor mantiene un buffer pequeño
  (`MIN_SECRET_WINDOW`); nunca se persiste un chunk no
  redactado.

## Estado

- 🔲 R2.1 — cerrar §4 (esta nota)
- 🔲 R2.2 — mover agente a src/main
- 🔲 R2.3 — wiring + SIGKILL + §4 inversion
- 🔲 R2.4 — ReleaseGate + v0.59.0-rc1

## Refs

- `docs/v2/04-adrs/ADR-OBS-003-child-output-descriptor-ownership.md` §2, §4, §5
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/ObsPc2IngestAgent.kt` (prototipo)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/ObsPc2ProducerSurvivalSpikeTest.kt` (spike, prueba de la inversión)
- `docs/v2/07-uat/OBS2_LEVEL_B_PRODUCER_SURVIVAL_SPIKE.md`
- `docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` (OBS-2)
- `docs/pipelinek-coordinated-evolution/specifications/SPEC-01-PIPELINEK-LIVE-PLANES.md` (PK-L5/L6)
