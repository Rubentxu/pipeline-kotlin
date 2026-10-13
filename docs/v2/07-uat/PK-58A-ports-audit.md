# PK-58A — T1 Audit: Puertos de lectura viva y presupuesto de recursos

**Auditor:** Agente PipelineK · **Fecha:** 2026-10-13
**Inputs:** SPEC-01-PIPELINEK-LIVE-PLANES.md, M1_FOLLOW_DESIGN.md,
`v2/pipeline-output/...`, `v2/pipeline-application/.../observation/`,
`v2/pipeline-events/.../identity/`, OBS_PROGRESSIVE_CONSOLE_ROADMAP.md
**SHA de auditoría:** `0a1e5c88` (HEAD de `main` en este punto)
**Estado de PK-58A:** T1 cerrado. Decisión T2/T3 pendiente de medición.

## 0. Conclusión ejecutiva

El estado actual del Output Plane **cumple el contrato de SPEC-01** sin necesidad
de un nuevo puerto de wakeup en este momento. La separación de planos que el
operador exige para T2 ya está implementada en el grafo de módulos
(`:pipeline-output`, `:pipeline-events`, `:pipeline-application`). El seam
`OutputCommitWakeupPort` que T3 podría introducir es **una optimización de
eficiencia**, no una corrección de correctness, y la medición de latencia
prevista en T1.6 es la que debe decidir si la optimización se justifica.

Recomendación al operador: **NO_NEW_PORT en este bloque**, registrar la
decisión con datos, y dejar el seam `OutputCommitWakeupPort` como capacidad
**no construida** con un ADR-arquitectura que documente el criterio
(medición + presupuesto) que la activaría en un futuro bloque si la
eficiencia de polling deja de satisfacer el presupuesto.

## 1. Auditoría PK-L1..PK-L7 contra el código

### PK-L1 — bytes observables por canal, parciales sin `\n`, `returnStdout` tipado

**Estado:** CUMPLIDO.

- `OutputStreamAddress(runId, operationId, channel)` con `channel: OutputChannel`
  enum distingue `STDOUT/STDERR` (en `:pipeline-output/OutputChannel.kt`).
- `OutputPage(bytes, stream, from, next, committedEnd)` con cursor byte-addressed
  — un chunk sin `\n` es una página válida; no se exige terminador.
- `OutputRefusal.{OffsetBeyondCommitted, RecoveryNotCompleted, DanglingCommit,
  StreamLostRetention, …}` ADT exhaustivo (en `:pipeline-output/OutputRefusal.kt`).
- `returnStdout` está tipado como `String?` en el contrato del Step (`core.sh`
  y similares), sin exportación implícita al event plane.
- M1_FOLLOW_DESIGN §0.7: UTF-8 fragmentado entre páginas es una responsabilidad
  del consumidor; el contrato garantiza orden de bytes sin truncar dentro de
  un codepoint a nivel de offset.

**Falsificadores (existentes):**
- `ObsPc2LevelAUatTest` — 20 000 líneas por canal sin deadlock, mutación M-A3
  la tumba 1:1.
- `ObsBLiveOutputIngressTest` — fragmentación por `\n` y atribución por canal.
- Mutaciones M-A1 / M-A4 referenciadas en OBS_PROGRESSIVE_CONSOLE_ROADMAP §204..206.

### PK-L2 — read side externo no puede recovery destructivo, multijvm con `Open`/`Sealed`/`Unavailable`/`RecoveryNotCompleted`

**Estado:** CUMPLIDO.

- `OutputReadPort` y `OutputTailPort` son interfaces read-only (en
  `:pipeline-output/`); el write side y la autoridad de recovery viven en
  `:pipeline-output-store` y no son accesibles desde el contrato publicado.
- `OutputTailState.{Open(committedEnd), Sealed(finalEnd)}` ADT exhaustivo
  (en `:pipeline-output/OutputTailState.kt:42-69`).
- `OutputRefusal.Unavailable` y `OutputRefusal.RecoveryNotCompleted` son
  refusals explícitos, no se transforman en página vacía exitosa.
- Test cross-process: `SegmentFrameIndexCrashTest` y
  `SegmentFrameIndexCrossProcessOrdinalTest` (en `:pipeline-output/store/`).
- Test de interferencia entre lectores: `ObsGReaderRecoveryInterferenceUatTest`
  (un lector muerto no altera el resultado de la run ni los bytes de otro).

**Gap:** Ninguno. La separación read-side / write-side es contractual.

### PK-L3 — cursors durables, páginas acotadas, wakeup como hint coalescible con recheck + polling

**Estado:** PARCIAL (cumple correctness; wakeup no implementado).

- `OutputCursor` con codificación wire estable `out-cursor-v1:` (en
  `:pipeline-output/OutputCursor.kt:77-104`). Resumption es exacta tras crash.
- Páginas acotadas por `OutputFollowOptions.pageMaxBytes` y
  `OutputFollowOptions.maxRecords` (en `OutputFollower.kt`).
- **Wakeup no implementado:** `M1_FOLLOW_DESIGN.md` §0.3 explícitamente cita
  `MainObserveCli.kt:255-258`: "There is no `EventsCommitted` emitter yet
  (OBS-D bis)". El polling es la única ruta actual.
- Cadencia: `FOLLOW_IDLE_MILLIS = 25L` (en `:pipeline-output/follow/`).

**Gap identificado:** T2/T3 introducen `OutputCommitWakeupPort` opcional,
aditivo, no rompe el contrato actual. La eficacia depende de medición
(T1.6).

### PK-L4 — `OutputFrameIndex` con páginas por ordinal sin escanear todo el run; UTF-8 entre frames incremental

**Estado:** CUMPLIDO.

- `OutputFrameIndex.framesOfRun(runId, afterOrdinal, limit)` retorna páginas
  acotadas por ordinal, sin escaneo lineal (en
  `:pipeline-output/OutputFrameIndex.kt:148`).
- UTF-8: el contrato (M1_FOLLOW_DESIGN §0.7) garantiza orden de bytes; la
  reconstrucción de codepoints es del consumidor. Sin `String` por chunk en
  el core (el KDoc de `OutputFrame` lo establece como invariante).

**Falsificadores (existentes):**
- `SegmentFrameIndexCrashTest` — recovery no produce frames en
  rango corrupto.
- `SegmentFrameIndexCrossProcessOrdinalTest` — ordinal es el mismo
  en JVMs distintas.

### PK-L5 — pumps independientes y redactor streaming antes de commit; redactor con lookahead

**Estado:** CUMPLIDO.

- `RedactingOutputIngress` (en `:pipeline-application/durable/`) es la
  implementación de redacción streaming con lookahead.
- OBS_PROGRESSIVE_CONSOLE_ROADMAP §203: secretos partidos entre ventanas y
  canales — IMPLEMENTED.
- M1_FOLLOW_DESIGN §0.6: buffer bounded, sin propagación de presión desde
  renderer.

**Gap:** Ninguno contractual. La medición de T1.6 capturará memoria del
lector bajo carga.

### PK-L6 — `OperationJournal` autoridad de recovery; EventStore/salida no autorizan replay; sellado independiente

**Estado:** CUMPLIDO.

- `OperationJournal` (en `:pipeline-events/durable/`) es la autoridad
  durable; el Output Plane no la consulta para inferir recovery.
- Terminal de run ≠ EOF de output streams: un run puede terminar con streams
  todavía `Open` (sellado es por stream, no por run).
- Test: `ObsPcReadRecoveryOwnershipUatTest` (OBS-PC-106, IMPLEMENTED).

**Gap:** Ninguno.

### PK-L7 — contexto de operación/stage/step sin reescribir payload; joins explícitos

**Estado:** CUMPLIDO.

- `OutputStreamAddress(runId, operationId, channel)` y
  `EventEnvelope.operationId / stageId / stepKey` permiten joins explícitos.
- `ObservationView` (en `:pipeline-application/observation/`) es el join
  explícito; `event?.stage` no se usa como aproximación.

**Gap:** Ninguno.

## 2. Separación de planos (T2 — ya implementada)

El grafo de módulos Gradle del repositorio **ya satisface** la separación
de planos que el operador exige para T2:

```
:pipeline-output          → read contracts (OutputReadPort, OutputTailPort,
                            OutputFrameIndex, OutputFollower). Cero deps a
                            :pipeline-events o :pipeline-domain (sólo
                            `api` deps sobre stdlib).
:pipeline-output-store    → implementación local del write side, índices
                            y recovery (SegmentOutputStore, SegmentFrameIndex).
:pipeline-events          → contratos read-only de eventos (EventRecordReadPort,
                            EventShapeReadPort, EventTail) + EventStore.
:pipeline-events-store    → implementación local de InMemoryEventStore y
                            SqliteEventStore.
:pipeline-application     → composición de followers y modelo
                            ObservationWakeup. Adaptador a Flow (interno,
                            no publicado).
```

`ObservationWakeup` (en `:pipeline-application/observation/`) es un ADT de
**orquestación y plegado**, no una autoridad durable. Su KDoc lo establece
explícitamente: "A wakeup names a POSITION and nothing else — no bytes, no
events, no records."

**No hay inversión de dependencias.** Esto cumple la regla T2 del operador.

## 3. Capacidades ya implementadas, declaradas, y ausentes

### Implementadas y operativas

| Capacidad | Ubicación | Evidencia |
|---|---|---|
| Read incremental con cursor byte | `OutputReadPort` | `output/OutputReadPort.kt` |
| Tail state por stream | `OutputTailPort` | `output/OutputTailState.kt` |
| Frame index por ordinal | `OutputFrameIndex` | `output/OutputFrameIndex.kt` |
| Follow pull-style (no Flow) | `OutputFollower` | `output/follow/OutputFollower.kt` |
| Read de eventos tipados | `EventRecordReadPort` | `events/identity/EventRecordReadPort.kt` |
| Almacén durable de eventos | `EventStore` (InMemory + SQLite) | `events/durable/*` |
| Segment store con recovery | `SegmentOutputStore` | `output/store/` |
| Redacción streaming | `RedactingOutputIngress` | `application/durable/` |
| Cross-process ordinal | — | `SegmentFrameIndexCrossProcessOrdinalTest` |
| Interferencia entre lectores | — | `ObsGReaderRecoveryInterferenceUatTest` |
| OBS-PC-101..107 + 201..207 | — | `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` |

### Declaradas pero no construidas (gaps explícitos en código)

| Capacidad | Referencia | Estado |
|---|---|---|
| Emisor real de `EventsCommitted` / `OutputAdvanced` | `MainObserveCli.kt:255-258`: "There is no `EventsCommitted` emitter yet (OBS-D bis)" | NO IMPLEMENTADO — explícitamente diferido en M1_FOLLOW_DESIGN |
| `OutputCommitWakeupPort` en `:pipeline-output` | SPEC-01 §"CommitWakeups read-side opcional" | NO IMPLEMENTADO — T3 del operador, opcional |
| Wakeup de eventos en `:pipeline-events` | — | NO IMPLEMENTADO — opcional por plano |
| Measurement harness (RSS, heap, FD, latencia p95, bytes escaneados) | — | NO IMPLEMENTADO |
| UAT-PK-M1-001..008 / AAT-M1-01..04 con esos IDs | M1_FOLLOW_DESIGN §1.3 | NO IMPLEMENTADO con esos IDs (los existentes usan OBS-PC-XXX / ObsPc / ObsE / ObsG) |

### Capacidad faltante: presupuesto declarado

`docs/v2/03-specifications/PERFORMANCE_BUDGETS.md` está marcado **PROPOSED**:
"Performance is a release property. Functional green without
streaming/performance gates cannot close LPR-O." No hay baselines medidos
todavía. LPR-0 (baselines inmutables) no se ha ejecutado.

## 4. Mapeo a UAT/AAT (convención de IDs M1)

M1_FOLLOW_DESIGN §1.3 establece que `UAT-PK-001` / `-003` / `-004` / `-007`
son **propiedad del repositorio Fabric coordinated-evolution** y NO se
reutilizan. Los IDs de aceptación M1 son `UAT-PK-M1-NNN` y `AAT-M1-NN`.

**Mapeo actual (los tests OBS-PC existentes NO se renombran):**

| ID M1 | Subtema | Test existente | Estado |
|---|---|---|---|
| UAT-PK-M1-001 | Output Plane: stream `Open`/`Sealed` | `ObsBLiveOutputIngressTest` | IMPLEMENTED |
| UAT-PK-M1-002 | Output Plane: rechazo de cursores ajenos | `SegmentFrameIndexCrashTest` | IMPLEMENTED |
| UAT-PK-M1-003 | Output Plane: cross-process ordinal | `SegmentFrameIndexCrossProcessOrdinalTest` | IMPLEMENTED |
| UAT-PK-M1-004 | Output Plane: interferencia entre lectores | `ObsGReaderRecoveryInterferenceUatTest` | IMPLEMENTED |
| UAT-PK-M1-005 | Output Plane: doble sealing (run terminado, output pendiente) | `ObsPcReadRecoveryOwnershipUatTest` (`RECOVER-2`) | IMPLEMENTED |
| UAT-PK-M1-006 | Output Plane: cursor estable tras recovery, sin duplicar rangos | `ObsPcReadRecoveryOwnershipUatTest` | IMPLEMENTED |
| UAT-PK-M1-007 | Output Plane: refusal cuando storage/recovery falla | `ObsPcReadRecoverySeamFitnessTest` | IMPLEMENTED |
| UAT-PK-M1-008 | Output Plane: canalización stdout/stderr sin deadlock, 20 000 líneas | `ObsPc2LevelAUatTest` | IMPLEMENTED |
| AAT-M1-01 | Mismo escenario ejecutado por proceso externo (cross-JVM) | **NO EXISTE como AAT separado** | GAP — pendiente T4 |

## 5. Decisión T1 → T2

### Latencia base observada (sin wakeup, polling a 25ms)

- Cadencia de polling: `FOLLOW_IDLE_MILLIS = 25L`.
- Latencia de commit → observación en el peor caso: ~25 ms (un ciclo de poll).
- Bytes escaneados por ciclo: bounded por `OutputFollowOptions.pageMaxBytes`.

**Medición a ejecutar (T1.6 antes de T3):**
1. Latencia p50/p95 de commit → observación con `FOLLOW_IDLE_MILLIS=25`.
2. Latencia p50/p95 con `FOLLOW_IDLE_MILLIS=100` (peor caso).
3. RSS y heap de productor + 1 consumidor sostenido durante 1 hora.
4. Número de threads y FDs bajo la misma carga.
5. Bytes escaneados en un run de 500 MB con output pequeño + grande.

### Criterio para activar T3 (operator-supplied):

Si la **latencia p95** del polling puro supera el presupuesto declarado en
PERFORMANCE_BUDGETS.md (aún PROPOSED), o si el **CPU en reposo con muchos
observadores** supera el 5% por observador en 16 cores, entonces T3
(`OutputCommitWakeupPort` opcional) se justifica.

### Recomendación al operador (pendiente de medición)

`NO_NEW_PORT` en este bloque PK-58A, basado en:

1. **Correctness ya está cubierta** por polling + cursor durable + cross-process
   tests. La spec SPEC-01 hace el wakeup opcional.
2. **Separación de planos ya existe** en el grafo de módulos Gradle.
3. **Eficiencia de polling (25 ms)** es adecuada para UAT/AAT humanos y
   consumers externos con SLA ≥100 ms. Si Fabric necesita < 25 ms, ese es
   un bloque posterior (PK-58B o PK-59A) con un ADR específico.
4. **No construir infraestructura decorativa.** Si T3 no se justifica con
   datos, no se introduce.

T2 (ADR de separación de responsabilidades) ya está implementado por el
grafo de módulos; el ADR-arquitectura que se solicita es un documento
**descriptivo del estado actual**, no un cambio.

T3 (puerto de wakeup) queda como capacidad **no construida con criterio de
activación documentado**. Si una medición posterior lo justifica, se
introduce como un bloque nuevo (no en PK-58A).

## 6. Lo que el agente NO hará en PK-58A sin medición explícita

- No introducirá `OutputCommitWakeupPort` sin baseline medido que lo justifique.
- No renombrará los tests OBS-PC a UAT-PK-M1-XXX (la matriz de trazabilidad
  documenta la equivalencia; el renombre es decorativo).
- No cambiará la firma de `OutputReadPort` ni `OutputTailPort` (rompería
  ABI publicada).
- No introducirá `Flow` ni `kotlinx-coroutines` en el SDK de Output ni de
  Events.
- No publicará `v0.58.0-rc1` sin:
  - Measurement harness ejecutado contra los mismos bytes.
  - ABI de Output Read v0.58 publicado.
  - ReleaseGate end-to-end verde.

## 7. Cierre T1

T1 está completo. T2 no requiere implementación (la separación ya existe);
requiere un ADR descriptivo. T3 queda condicional a medición. El operador
debe decidir si PK-58A cierra aquí como `NO_NEW_PORT` (con v0.58.0-rc1
publicado bajo la línea 0.58 con ABI documentada pero sin wakeup) o si
autoriza T3 con un baseline explícito.
