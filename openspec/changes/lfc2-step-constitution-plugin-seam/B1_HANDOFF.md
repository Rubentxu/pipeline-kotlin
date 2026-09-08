# B1 — Technical handoff for B1.2c2 (durable execution envelope)

Status: handoff produced 2026-09-08 after an approved production stop at B1.2c1. Next pass is
**pre-authorized** to start directly at B1.2c2-a. Tree clean at `864ee511`.

Approved/verified to date: B1.1 `3a924bd4`, B1.2a `2cc3bc63`, B1.2b `864ee511` (all green, XML
canary), B1.2c1 characterization complete. Nothing further marked done.

## 1. Exact current architecture of `core.echo`

```
DSL/IR  ->  CanonicalDurableRunCoordinator (durable)  ->  CanonicalNodeDispatcher (concrete when)
```

Concrete classes/functions (all under `v2/pipeline-application/...` unless noted):
- `application/CanonicalCoreStepDecoder.kt` — sealed `CanonicalCoreStepCommand` (`Echo`, `Shell`, ...)
  + `decode(node)`. Echo carries `text`.
- `application/durable/CanonicalDurableRunCoordinator.kt`
  - `dispatch(...)` ~L444-592 (see §2 line map): orchestrates journal/replay/events AND concrete decode/dispatch.
  - fields: `journal`, `cursorStore`, `clock`, `dispatcher: CanonicalNodeDispatcher`, `eventSink`,
    `controlDirRoot`, `credentialScopePort`, `divergenceDetector`.
  - helper `recoverRunningShell` L594-639 (shell-specific recovery, must NOT move to envelope).
  - `dispatchBody` L650+ (block steps; distinct substrate, not part of B1.2c2).
- `application/durable/CanonicalNodeDispatcher.kt` — the concrete `when(command)` over
  `CanonicalCoreStepCommand` (Echo case → `echoDispatcher`). This is where concrete `Echo`
  knowledge lives.
- `application/durable/CanonicalEchoNodeDispatcher.kt` + SDK `sdk/runtime/echo(...)` → emits
  `EchoOutputCaptured`, returns `StepOutcome.Success`.
- Composition roots that build `CanonicalDurableRunCoordinator` + `CanonicalNodeDispatcher`:
  `application/Main.kt` (production) and `application/support/PipelineRule.kt` (test). Both must be
  updated when the registry is injected (B1.2c2-d).
- Domain seam (already landed): `v2/pipeline-domain/.../domain/step/StepRegistry.kt` (B1.1) +
  `StepHandlerContext` (B1.2a). Echo adapter: `application/CoreEchoStep.kt` (B1.2b).

## 2. Exact extraction target — `CanonicalDurableRunCoordinator.dispatch` L444-592

Line map (what moves to the durable envelope vs what does NOT):

| L | Concern | Move to envelope? |
|---|---|---|
| L444-471 | Decode `CanonicalCoreStepDecoder.decode`; SCHEMA failure → journal FAILED + `StepOutcome.Failure`. | **Decode stays in coordinator** (strategy-specific). Its SCHEMA failure path journals a FAILED row → this "journal a failure without execution" behaviour belongs in the envelope as "abort/persist without execute". |
| L474-498 | EmitEvent/CatchError context-overlay (contextStack push/pop). | Coordinator/orchestration concern (contextStack lives in coordinator). NOT envelope. |
| L500-502 | `effects` + `replayPolicy` from command metadata. | Coordinator derives them; passes into envelope decision. |
| L501-509 | `OpId` + `operationId` format; `OperationInput`; `Fingerprint.compute`. | **operation identity + fingerprint** → envelope. |
| L510-516 | `StepLifecycleContext`. | **generic lifecycle context** → envelope. |
| L517-525 | `journaled = journal.get`; `currentOperation`; PENDING row. | **replay/reconcile lookup** → envelope. |
| L526-530 | `divergenceDetector.check(...).isFailure` → INFRASTRUCTURE fail. | **fingerprint/divergence** → envelope. |
| L531-548 | `recoverRunningShell(...)` recovery branch. | Shell-specific recovery — coordinator/strategy concern. Envelope must allow a strategy hook or receive an already-recovered outcome; do NOT fold shell reconciler into the envelope. |
| L549-560 | `effectReplayPolicy.decide(...)` → SKIP/ABORT/RERUN. | **replay decision** → envelope. On SKIP the execution callback is NOT invoked (see replay test). |
| L561-563 | `journal.beginOperation(...)`. | **journal begin** → envelope. |
| L565-579 | `StepExecutionBoundary(eventSink).execute(lifecycleContext) { dispatcher.dispatch(...) }`. | **execution boundary + callback**. The callback body is the ONLY strategy-specific part → becomes the `StepExecutionStrategy`. |
| L580-589 | `journal.append(RerunOperation(...status=outcome.toOperationStatus()))`. | **result persistence** → envelope. |
| L590 | `if (outcome !is Failure) cursorStore.advance(...)`. | **cursor advancement** → envelope. |
| L591-592 | `return outcome`. | envelope returns typed outcome. |

Do NOT move: contextStack orchestration (L474-498), `recoverRunningShell` (L594-639),
`dispatchBody` (L650+), shell/echo per-step runtime authority.

## 3. Target architecture

```
CanonicalDurableRunCoordinator
        |
        v
DurableExecutionEnvelope          (new; extracted L444-592 minus strategy/decode/context)
   ├── operation identity / fingerprint
   ├── journal begin / replay / reconcile
   ├── divergence check
   ├── generic lifecycle (StepExecutionBoundary)
   ├── result persistence
   └── cursor advance
        |
        v
StepExecutionStrategy            (callback seam)
   ├── LegacyExecutionAdapter    [temporary; current CanonicalNodeDispatcher.dispatch]
   └── RegistryExecutionAdapter  [generic: Invoke -> StepRegistry -> ErasedStepAdapter -> handler]
          |
          v StepRegistry  ->  ErasedStepAdapter  ->  Typed StepHandler
```

Single durable protocol. Journal/replay/cursor are never duplicated by a strategy/handler.

## 4. B1.2c2 decomposition (run in order; each atomically committed, green)

- **B1.2c2-a** — behaviour-preserving durable envelope extraction (Extract Function/Component).
  NO registry, NO echo migration, NO legacy deletion, NO payload/ID/replay/event-order change.
- **B1.2c2-b** — legacy `CanonicalNodeDispatcher.dispatch` becomes the callback/strategy of the
  envelope. Behaviour-preserving; proves journal/replay are decoupled from concrete dispatch.
- **B1.2c2-c** — add the generic registry execution strategy (Invoke → StepRegistry →
  ErasedStepAdapter → typed handler), tested against an invocation fixture. Envelope unaware of the
  strategy. Missing StepKey fails closed; handler side effect not executed on replay reuse.
- **B1.2c2-d** — inject `StepRegistry` at the composition roots (`Main.kt`, `PipelineRule.kt`) as an
  explicit constructor dependency. No singleton/global/service-locator. Tests supply a deterministic
  registry. Temporary compatibility constructor only if explicitly marked for removal in B1.
- **B1.2c2-e** — structural selection generic-vs-legacy based on the canonical node FORM/version,
  never a Step-name `when`. Both paths coexist; unknown generic Step fails closed; legacy Steps still
  function; durable semantics shared.

After B1.2c2: B1.2c3 (route `core.echo`), B1.2c4 (remove reachable Echo dispatch + fitness),
B1.2e (regression proof / close Echo), then B1.3 `core.sh`.

## 5. Invariants (MUST NOT)

- No duplicated journal/replay for generic Steps.
- No replay moved into a StepHandler.
- No Step-name routing (`when(stepKey){ "core.echo" -> ... }`).
- No silent generic→legacy fallback.
- No omnipotent `PipelineContext`.
- No global/static registry (constructor dependency).
- No duplicated generic lifecycle event emission per StepHandler (lifecycle stays in the
  envelope/spine: `StepStarted → handler → StepSucceeded/StepFailed`).
- No change to fingerprints, operation IDs, or cursor semantics during the extract.
- The durable envelope owns operation identity, journal, replay/reconcile, fingerprint/divergence,
  lifecycle, result persistence and cursor — a single durable protocol.

## 6. Exact test gate for B1.2c2-a (concrete names; no placeholders)

Gradle invocation (targeted first, full module at batch end):
`timeout 600 ./gradlew -p v2 :pipeline-application:test --tests '<Class>.method*'` then the classes:

- Coordinator focused: `application.durable.CanonicalDurableRunCoordinatorTest`,
  `application.durable.CanonicalCoordinatorScopeStackTest`,
  `application.durable.CanonicalNodeDispatcherTest`, `application.durable.CanonicalEchoNodeDispatcherTest`.
- Operation journal / durable / replay / divergence: `UatDurable001ReplaySurvivesRestartTest`,
  `UatDurable002DivergenceFailsClosedTest`, `UatDurable007DivergenceMismatchTest`,
  `Spike016DurableScriptedReplayTest`, `UatEvt001ReplayTest`, `UatEvt002MultiStepReplayTest`.
- Event ordering/lifecycle: `StepExecutionBoundaryTest`, `UatStep002EchoCaptureTest`.
- CLI path through this coordinator: `CanonicalInMemoryCliTest`, `CliNonCanonicalInMemoryExitsTwoTest`,
  `MainCliParsingTest`, `UatDurableDefaultReuseCliTest`, `CliCompileErrorExitsOneTest`.
- Replay reuse proof (B1.2c2-c): add/confirm a recording-strategy test that when the envelope decides
  `REUSE_RESULT`/SKIP the execution callback is NOT invoked.
- Architecture fitness: the `pipeline-architecture-tests` module suite (`FArch*`) for the no-concrete
  echo-dispatch rule (activated in B1.2c4 / B1.4).
- Fresh JUnit XML under `v2/pipeline-application/build/test-results/test/` (`tests/failures/errors`)
  as the result oracle; delete the canary XML first when a run MUST have executed.

## 7. Characterization findings (B1.2c1) that condition the extract

- Concrete `Echo` knowledge lives ONLY in `CanonicalNodeDispatcher.when(command)` (L38-56); the
  coordinator journals against `pluginStepId` + `step.payload.encoded` regardless of command type.
- The seam input is the coordinator's `step.payload.encoded` (as `EncodedStepValue`), available
  BEFORE `CanonicalCoreStepDecoder.decode`. So generic routing keys on `registry.contains(pluginId)`,
  not on the decoded `Echo` type.
- Journal/replay/fingerprint operate on the encoded payload + effects/replayPolicy; they are
  orthogonal to which strategy executes. This is why the envelope extraction is feasible without a
  second durable spine.
- Decode (L444) and context-stack overlay (L474-498) are strategy/orchestration concerns that do not
  belong in the envelope. The SCHEMA-failure-journal path couples decode to journal; the envelope
  exposes an "abort/fail without execute" path to keep that behaviour single.
- `recoverRunningShell` is shell-specific and must remain reachable from the coordinator/strategy
  seam (envelope must accept either a strategy hook or an already-recovered outcome).
- Tests protecting each behaviour: coordinator/durable/replay/event/CLI classes listed in §6.
- Composition roots to update when injecting the registry: `Main.kt`, `PipelineRule.kt`.

## 8. First action, next pass

> B1.2c2-a — extract the durable execution envelope from `CanonicalDurableRunCoordinator.dispatch`
> L444-592 in a behaviour-preserving way: no StepRegistry, no Echo migration, no legacy deletion,
> no payload/ID/replay/event-order/fingerprint change. Compile → focused coordinator tests → durable/
> replay/event tests → full `:pipeline-application:test` → fresh XML → atomic commit.
