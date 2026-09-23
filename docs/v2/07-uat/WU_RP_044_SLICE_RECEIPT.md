# WU-RP-044 SLICE RECEIPT — Streaming de transcript end-to-end (M5 RSS debt)

**SHA base:** 888f4b60 (CI verde previa, run 35785380826 SUCCESS).
**WIP SHA:** cambios staged sobre 888f4b60 (9 archivos modificados, 1 nuevo test).
**Branch:** main. **CI verification:** pendiente push (CI del NUEVO SHA requerido por AGENTS.md §RECETA-1).
**Charter:** ROADMAP §6 WU-RP-022 y §0 §5 — eliminar el RSS del orden de magnitud del transcript (M5 maxRss ~10 GB) sin perder observabilidad (canal separado typed ≠ console transcript, ADR-0001 / WU-LPR-011R2 at-rest retention).

## 1. Diagnóstico (OBSERVED)

M5 (soak 1 GiB) en WU-RP-022 medía `maxRss ~10 GB` para un comando `sh` cuyo transcript es ~1 GiB. Causa estructural, en orden de impacto:

1. `DurableShellExecutor.executeTerminal` materializaba el transcript completo en `consoleTranscript: String` (línea 1148 pre-fix) ANTES de invocar `terminal.fromReconciliation` → ya había ~1 GB vivo en el terminal antes de que la capa superior lo leyera.
2. `ShExecution` (legacy path) volvía a materializar: `redactTranscript(consoleTranscript ?: …)` → segundo ~1 GB en la JVM antes de pasarlo a `emitTranscriptChunked`.
3. `Main.events-jsonl` ejecutaba `eventStore.eventsFor(runId).toList()` → la lista completa de eventos en memoria (cada `EchoOutputCaptured` con ~64 MiB payload) ANTES de `JsonEventLog.encode(events)` → tercer pico: lista + JSON-encoded String de ~1 GB.
4. `SqliteEventStore.eventsFor` materializaba los eventos decodificados en un `mutableListOf<DomainEvent>` antes de devolver el `Sequence`.

Con `-Xmx1g`, el heap crasheaba en OOM durante el soak (probado en sesión anterior: `soak1`, killed por SIGKILL con RSS ~9.5 GB en `jcmd`).

## 2. Decisión (DERIVED desde diagnóstico)

Streaming coherente end-to-end sin alterar el contrato observable:

- **`ShExecution.emitTranscriptStreaming`** (nuevo, twin de `emitTranscriptChunked`): lee el `InputStream` en ventanas de 64 MiB (mismo `MAX_TRANSCRIPT_CHUNK_CHARS`), envuelve con `StreamingRedactor` (preexistente) para no romper redacción, y emite `EchoOutputCaptured` por ventana. Observable contract idéntico: chunks contiguos, ordenados, lossless, bounded.
- **`DurableShellExecutor.executeTerminal`**: en `JENKINS_LOG` projection NO materializa `consoleTranscript`; devuelve `null`. En `CAPTURE_FILE` projection sigue leyendo (es el canal typed VALUE, no la observabilidad).
- **`DurableShellExecutor.cleanup(controlDir, exitCode, keepTranscriptLog=false)`** (nuevo overload): en JENKINS_LOG + exit 0 borra todo el control dir **excepto `console.log`**. El consumidor `ShExecution` lee `console.log` desde disco vía `DurableShellFiles.resolveConsoleLog(...)` (lazy), lo streama, y borra el archivo retenido SOLO si exit==0. La retención condicional preserva LPR-011R2 (transcript retenido en fallo/timeout para post-mortem).
- **`Main.events-jsonl`**: usa `JsonEventLog.encodeTo(events, writer)` (nuevo), trackea `lastEvent` mientras streama, no materializa lista ni JSON monolítico.
- **`SqliteEventStore.eventsFor`**: ahora `sequence { … yield(it) }` lazy, una sola iteración (el caller siempre iteraba completamente). Patrón `constrainOnce()` explícito.
- **`EventHistoryReader.nextPage`**: usa el stream lazy, evita `sortedBy` (no necesario: la SQL ya ordena por rowid ASC) y `take(limit+1)` peeked para `hasMore` en pasada única (sin materializar todo el historial).
- **`JsonEventLog.encodeTo(events, out)`**: streaming twin de `encode(events)` byte-idéntico.

`DurableTaskTerminalAdapter.toLegacyShellResult` se adapta al nuevo contrato: si `capturedStdout` es null y existe `console.log` (legacy projection), lo lee lazy. No es la ruta caliente (legacy compatibility shim).

## 3. Decisiones sutiles (importantes para evitar regresiones)

- **`keepTranscriptLog` solo cuando `exitCode==0 && !timeoutTriggered`**. Timeout y fallo conservan todo el control dir para post-mortem (LPR-011R2 Gate-1 at-rest retention). El default `false` preserva la interfaz `DurableShellLaunching` original.
- **`StreamingRedactor.wrap(InputStream)`** ya existía en credentials-api (introducido en WU-RP-022 P1); se reutiliza tal cual.
- **`Main.events-jsonl`** preserva el orden byte-idéntico del output (verificado con diff en soak; pipelinek validate/events siguen produciendo el mismo JSON enumerable, solo cambia que se streama en lugar de allocarse de golpe).
- **`EventHistoryReader`** pierde el `sortedBy { it.sequence }` (la SQL ya ordena por rowid ASC = sequence ASC en este esquema); preserva `lastSequence` y `hasMore` correctos.
- **Sin regresión de redaction**: `StreamingRedactor` opera chunk-boundary-safe por construcción (ring buffer + match incremental, heredado de P1 de WU-RP-022). LPR-011r2 verde al 100%.

## 4. Cambios

- `v2/pipeline-application/src/main/kotlin/.../Main.kt` (+12/-9): events-jsonl streaming con `JsonEventLog.encodeTo`.
- `v2/pipeline-application/src/main/kotlin/.../durable/ShExecution.kt` (+88/-9): `emitTranscriptStreaming` + uso en finally de la operación con borrado condicional del control dir.
- `v2/pipeline-events/src/main/kotlin/.../events/JsonEventLog.kt` (+15/-0): `encodeTo(events, writer)` streaming twin.
- `v2/pipeline-events/src/main/kotlin/.../events/SqliteEventStore.kt` (+24/-15): `eventsFor` lazy `sequence {}`.
- `v2/pipeline-events/src/main/kotlin/.../events/identity/EventHistoryReader.kt` (+18/-12): pasada única con peeked hasMore.
- `v2/pipeline-step-sdk/runtime/src/main/kotlin/.../durable/DurableShellExecutor.kt` (+49/-4): overload `keepTranscriptLog` + cleanup condicional + `consoleTranscript=null` en JENKINS_LOG.
- `v2/pipeline-step-sdk/runtime/src/main/kotlin/.../durable/DurableTaskTerminalAdapter.kt` (+21/-8): legacy projection recupera `console.log` lazy.

**Nuevo test:**

- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/TranscriptStreamingEmissionTest.kt` (76 líneas, 4 tests):
  1. `small transcript emits exactly one event identical to content` (idéntico a `TranscriptChunkingTest` para transcripts cortos).
  2. `empty stream emits nothing`.
  3. `missing source emits nothing`.
  4. `oversized stream chunks are lossless ordered and bounded` (asserts 2 chunks, bounded, concatenación = payload exacto).

## 5. Verificación OBSERVED

### L1 (nuevo test)
- `cd v2 && ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.durable.TranscriptStreamingEmissionTest' --no-daemon` → BUILD SUCCESSFUL 26 s.
- Canary XML: `TEST-dev.rubentxu.pipeline.v2.application.durable.TranscriptStreamingEmissionTest.xml` (timestamp 2026-09-23T06:36:55.726Z), `tests=4 skipped=0 failures=0 errors=0 time=0.551s`. 4 `<testcase>` sin `<failure>`/`<error>`.

### L2 (vecinos críticos de regression)
- `cd v2 && ./gradlew :pipeline-application:test --tests 'Lpr011r2SecretRedactionAtRestUatTest' --tests 'Lpr011SecretRedactionTranscriptUatTest' --tests 'TranscriptStreamingEmissionTest' --no-daemon` → BUILD SUCCESSFUL 29 s.
  - `Lpr011r2*`: 11 tests / 0 failures / 0 errors / 0 skipped / 14.508 s (transcript retention post-mortem OK).
  - `Lpr011*`: 6 tests / 0 / 0 / 0 / 0.498 s (transcript redaction OK).
  - `TranscriptStreamingEmissionTest`: 4 tests / 0 / 0 / 0 / 0.490 s.

### L3 (eventos full module, --rerun-tasks)
- `cd v2 && ./gradlew :pipeline-events:test --rerun-tasks --no-daemon` → BUILD SUCCESSFUL 57 s. **TOTAL events tests=188 skipped=0 failures=0 errors=0** (canary XML sum, 36 archivos TEST-*.xml regenerados con timestamp actual).

### L4 (application + sdk-runtime, --rerun-tasks)
- `cd v2 && ./gradlew :pipeline-application:test :pipeline-step-sdk:runtime:test --rerun-tasks --no-daemon` → BUILD SUCCESSFUL 15m 37s. 63 tasks ejecutadas (no cache hit). **TOTAL application tests=1735 skipped=115 failures=0 errors=0; sdk-runtime tests=190 skipped=0 failures=0 errors=0**.

### L5 (round gate `check` incremental sobre el WIP)
- `cd v2 && ./gradlew check` → BUILD SUCCESSFUL 13s. 11 ejecutadas, 124 up-to-date (regresión de cache post-L4-rerun es up-to-date — esperado: sin cambios desde el rerun). `pipeline-events:check` ejecutó `koverVerify` correctamente. **No FAILED, no ERROR**.

### Bug encontrado y corregido durante el L4 (en worktree, ya en el diff)
- Primera versión del `finally` de `ShExecution` borraba `console.log` SIEMPRE → rompió 2 tests LPR-011r2 (at-rest retention en fallo/timeout). Causa: el cleanup ya borra control dir pero el `finally` adicional borraba el log retenido.
- Fix: `deleteRetainedLog = (terminal as? DurableTaskTerminal.Exited)?.exitCode == 0`. Conserva el log retenido en fallo/timeout (Gate-1 at-rest). Verificado: LPR-011r2 11/11 verde, LPR-011 6/6 verde.

### Soak end-to-end con `-Xmx1g` (registro de la sesión anterior, evidencia preservada)
- Dist recién instalada (`v2/pipeline-application/build/install/pipelinek`) ejecutó pipeline con `sh` cuyo transcript = 1 GiB (1073741824 bytes exactos).
- `soak5` (primera versión) **emitió 8 eventos sin `EchoOutputCaptured`** → pérdida silenciosa por cleanup race. Corregido.
- `soak6` (post-fix): **EXIT=0**, 129 s (vs baseline 137 s), **maxRss ~1,4 GB** (vs ~10 GB baseline), **lossless 1.073.741.824 chars exactos en 16 chunks de 64 MiB**, 24 eventos, control dir limpio al final (queda solo `last-run/<hash>` puntero preexistente). Heap live-set diminuto confirmado.
- Comando: `PIPELINEK_OPTS="-Xmx1g" ./pipelinek run --db /tmp/soak6.db --control-root /tmp/soak6-ctrl v2/compatibility/31-soak-1gip.pipeline.kts`.

## 6. Estado del receipt tras cierre local (WIP — push pendiente)

- **NO_COMMIT**: working tree contiene los 9 archivos modificados + el test nuevo; `git status` confirma.
- **L1..L5 locales verdes** sobre este WIP.
- **CI del NUEVO SHA**: REQUIRED antes de declarar PASS_GREEN_CI. Bootstrap procedure documentada (DELETE protection → push → RE-APPLY → trigger CI).
- **Próximo paso**: commit (mensaje: `perf(events/sh): stream transcript + eventsFor + jsonl end-to-end (WU-RP-044 M5 RSS debt)`), bootstrap push, CI run, actualizar SESSION_POINTER + WORK_JOURNAL.

## 7. Cierre de M5 RSS debt

M5 RSS debt (maxRss ~10 GB en soak 1 GiB) queda **cerrada** por construcción:

- Heap live-set ya NO escala con el tamaño del transcript (ningún `String` ni `List` materializa el contenido completo).
- Reducción observada: ~10 GB → ~1,4 GB (~7x) con `-Xmx1g`.
- Sin cambio de contrato observable (mismos eventos, mismo orden, mismo contenido redactionado).

## 8. Residual y notas operativas

- **Procesos stale `sqlite-event-writer`** parked en `queue.take()` si `close()` no corre (2 matados manualmente en esta sesión, RSS cayó 10GB→450MB tras GC). Vigilar `pgrep -f MainKt` tras soaks futuros.
- **`PIPELINEK_OPTS="-Xmx1g"`** sigue siendo técnica válida para medir RSS live-set en soaks (falsa el techo de G1 y revela el live-set real).
- **L5 incremental fue up-to-date** porque el L4 rerun tocó todo lo que el L5 incremental habría ejecutado. Sin cambio de código desde el L4 rerun → esperado.
- **`EventHistoryReader.nextPage`** ya no usa `sortedBy { it.sequence }`. Eliminado por redundancia (la SQL ordena por rowid ASC = sequence ASC en este esquema). Documentado en el commit.
- **Patrón de tests**: `TranscriptStreamingEmissionTest` sigue el contrato de `TranscriptChunkingTest` (mismo observable contract por ventana, lossless, bounded, ordenado). Suite duplicada porque el path es diferente (stream vs in-memory).

## 9. Referencia a la sesión anterior (contexto)

- WORK_JOURNAL 2026-09-22/23 — WU-RP-044 (sesión 2): streaming de transcript completo, gate verde, SIN commitear. Esta WU es el cierre formal de esa sesión.

## 10. Próxima WU candidata (NO EJECUTAR hasta CI verde)

Tras CI verde y merge, opciones ordenadas por impacto técnico (todavía en RP-4):

1. **UAT-RP-018 sandbox 'os'** (ADR-0016, RunnerTrustProfile): cierra el último UAT PARTIAL de RP-2.
2. **WU-RP-045 dogfooding N2 ampliado** (ROADMAP §6 WU-RP-043): más scripts per-shard, refinar el job `dogfood`.
3. **SLO M5 RSS formal** (≤ 2 GB): con la evidencia nueva (live-set ~1,4 GB), cabe un SLO estricto.

Decisión inteligente del orquestador (auto-run preautorizado): priorizar (1) por cerrar la última deuda UAT de RP-2 antes de RP-5 Gate.

---

**Estado actual:** WIP L1..L5 verdes sobre 888f4b60. CI verification pendiente push.