# EVT-3 Exploration Report (grounding sobre trunk 1b950074)

## Q1 — Cómo obtener hoy la historia completa (EVT-2)
- `EventHistoryReader(sink: EventSink)` implementa `EventHistory.history(run: ResourceRef, query)` y
  `EventTail.readAfter(run, cursor, limit)`. Devuelve `Sequence<PipelineEventEnvelope>` en orden de
  sequence asignado por el store. No se necesita ejecutar: cualquier proceso nuevo puede abrir el
  `EventSink` SQLite (o InMemory) y leer. La CLI `pipeline events` ya demuestra lectura post-run.
- Envelope: `{version, eventRef{source,id}, kind, occurredAt, sequence, subject, causation?, correlation?}`.

## Q2 — ResourceRef/source/subject reales para 07–10
Subject derivation ya total (EVT-2 `subjectOf`):
- `CatchErrorTriggered` → RUN subject (`v1:run:pipeline/run/<runId>`). Payload: `buildResult: String?`, `message: String?`.
- `RetryAttemptStarted/Finished` → STEP subject (`v1:step:.../stage/<i>/step/<j>`). Finished payload: `attemptNumber, maxAttempts, stepName, stepType, stageIndex, stepIndex, outcome: String` ("failed"/"succeeded").
- `TimeoutScheduled` → STEP subject si stage+step presentes, si no RUN. Payload: `timeoutSeconds, timeoutAction, stepName?, stepType?, stageIndex?, stepIndex?`.
- `ParallelBranchStarted/Finished` → STAGE subject (`v1:stage:.../stage/<parentStageIndex>`). Payload: `branchIndex, branchName, parentStageIndex` (+ `outcome` en Finished).
- `StepFailed` → RUN subject (ley congelada EVT-1: stepIndex sin stage). Payload incluye `message`.
- `RunStarted`/`RunFinished` → RUN subject. `RunFinished.outcome: String` ("success"/"unstable"/"failure").
- ⚠️ Hallazgo clave: los contratos 07–10 filtran por **campos de payload** (buildResult, outcome,
  attempt, message) que NO están en el envelope. El harness necesita un **payload accessor tipado**
  (decodificar DomainEvent por kind via `JsonEventLog`), o bien selectors con campos tipados
  (WhereClause cerrada por campo conocido: `catchBuildResult`, `retryOutcome`, `attemptNumber`,
  `branchIndex`, `messageContains`). No `Map<String,Any>`. Decisión de diseño: `EventSelector`
  lleva `where: List<TypedFieldMatch>` con `TypedFieldMatch` ADT cerrado (los 5 campos anteriores).

## Q3 — Assertions exactas de examples/run.sh (07–10)
- 07 (`check_07`, sobre f1): exactamente 1 `CatchErrorTriggered` buildResult=FAILURE; exactamente 1
  buildResult=UNSTABLE; orden FAILURE antes de UNSTABLE; existe `EchoOutputCaptured` cuyo content
  contiene "continues after nested catch". Outcome terminal esperado: unstable.
- 08 (`check_08`, f2 vs f1): en los eventos NUEVOS de la 2ª ejecución (hoy delimitados por
  **timestamp** — deuda que EVT-3 elimina usando cursor/sequence): 0 `ParallelBranchStarted`,
  0 `StepStarted`. Outcome 2º run: success.
- 09 (`check_09`, sobre f2): exactamente 2 `RetryAttemptFinished`; `[0].outcome=="failed"`,
  `[1].outcome=="succeeded"` (orden). Outcome: success.
- 10 (`check_10`, sobre f1): ≥1 `TimeoutScheduled` Y existe `StepFailed` con message conteniendo
  "timed out". Outcome: failure.

## Q4 — Sidecars examples/contracts/*.events.yaml
Existen 4 sidecars (07,08,09,10) + README. Son **propuestas de schema**, no parser: README lo dice
explícitamente. Formas usadas: `exactly {event, where, count}`, `exactlyPer {event, key, count}`,
`before {first, second, same}`, `never {event}`, `expect.runOutcome`. Campos `where` propuestos:
`buildResult`, `text`, `attempt`. Discrepancias a resolver en diseño: `exactlyPer` (08) no está en
el ADT mínimo del brief; 10 no cubre el StepFailed timed-out; "text: continues" es substring, no
equality. El diseño de EVT-3 define el schema v1 real y actualiza los sidecars a la forma decodificable.

## Q5 — Owner más limpio del harness
- `pipeline-events` ya contiene identity/ (ports, envelopes, projector) y es donde vive la lectura.
  Pero el harness es un **verificador de contratos**, no un proveedor de eventos.
- `pipeline-protocol` existe pero es protobuf worker-protocol (transporte) — NO usar (EVT-3 no toca
  transporte).
- `pipeline-testkit` es test-only — el harness debe correr también en CLI post-run (production
  surface mínima), así que no encaja como owner.
- **Decisión: módulo nuevo `pipeline-event-harness`** justificado por:
  (a) dirección de dependencias: depende de pipeline-domain (ResourceRef) + pipeline-events (ports,
  envelope) y NO debe ser dependencia de domain/application runtime; meterlo en pipeline-events
  obligaría a application a tomar dependencia transitiva de la lógica de verificación;
  (b) reutilización real: lo consumen la CLI (subcomando `events verify`), examples/run.sh
  (differential parity) y tests de contrato; en pipeline-events contaminaría el store/ports con
  semántica de verificación.
  Adapter YAML: snakeyaml (nueva dep, solo en el módulo harness; domain nunca la ve).

## Riesgos / decisiones preliminares
1. 08 necesita distinguir eventos de la 1ª vs 2ª ejecución SIN timestamps → usar
   `EventCursor` de EVT-2: cursor tras la 1ª ejecución, los eventos "nuevos" son sequence > cursor.
   El contract 08.ver2 se define sobre una SUB-historia delimitada por cursor. Nuevo elemento ADT:
   `scope` del contract (whole-run vs since-cursor) — o simplemente el verifier recibe la sub-lista.
2. Partial order para parallel: `Before(first, second, scope=SameBranch(branchIndex))` cubre 08;
   leyes universales (Started antes que Finished por recurso) se verifican con agrupación por clave
   tipada derivada del envelope+payload (branchKey = parentStageIndex:branchIndex).
3. `After` = inverso de `Before`: NO duplicar autoridad; `Before(a,b)` basta (09 y 07 lo usan así).
4. Mutation tests: transformaciones puras test-only sobre historias reales capturadas (fixtures
   generados de ejecuciones reales, committed como JSON de envelopes).
