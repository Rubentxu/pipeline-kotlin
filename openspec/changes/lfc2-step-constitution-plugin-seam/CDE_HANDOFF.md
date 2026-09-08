# CDE — Structural pre-decode routing seam (B1.2c2-cde merged)

Status: produced 2026-09-08 after B1.2c2-a2 closed (`2a5e32f9`, tree clean). The original
`c -> d -> e` decomposition is withdrawn: it encoded a circular dependency (registry adapter
`↔` structural pre-decode routing) that the implementation surfaced. c/d/e are one architectural
unit and one gate. **No ceremonial commit for `c`.**

**CDE.1 + CDE.2 DONE (2026-09-08).** Commits `a05682ec` (CDE.1/grounding) then CDE.2-a `d93f6b23`,
b1 `2ac3ce0e`, b2+b3 `32d83315`, b4 `0bd247e6`, c0 `014f4859`, c `eba47525`, d `11736960`, e
`f595503a`. The durable coordinator is now structural-only: `CanonicalCoreStepCommand`/
`CanonicalCoreStepDecoder` are confined behind `LegacyExecutionBoundary`; eligibility/labels come
from `CanonicalCoreStepMetadata`; the overlay is a structural ADT; recovery is decided by
`metadata.recoveryPolicy`, never a Step name; typed decode runs only on Execute. Fitness
`Lfc2DurableCoordinatorScopeFitnessTest` (F1/F2/F3) + 81 relevant + architecture module 165/0.
Next: **CDE.3** — a `RegistryExecutionAdapter` must occupy the same frontier as
`LegacyExecutionBoundary` WITHOUT modifying journal/replay/fingerprint/cursor/lifecycle.

## 0. Why c/d/e merged (evidence, not preference)

The durable envelope extracted in a2 calls its execution callback with an already-decoded
`CanonicalCoreStepCommand` (`Execute -> stepExecutor.invoke(typedCommand, ...)`). That type is the
closed, decoded legacy/core world. A registry strategy cannot cleanly consume it: reconstructing an
`EncodedStepValue` from `Echo.command.text` / `Sh.command.script` would reintroduce concrete semantics
into the adapter and merely relocate the hardcoded dispatcher. Therefore the **registry-vs-legacy
decision must happen before the concrete Step decode**, on the raw encoded node. The replay-invariant
(reuse decides before any handler runs) is only naturally expressible on that pre-decode form.

## 1. CDE.1 grounding — data-flow map (real types)

```
source DSL/IR
  -> StepNode : OpaqueStepNode | BlockStepNode        (domain/CompiledPipeline.kt)
       - id: StepId
       - pluginStepId: PluginStepId                    ("core.echo")
       - payload: VersionedStepPayload(schemaVersion, encoded)
  -> coordinator.dispatch (CanonicalDurableRunCoordinator.kt)
       - prepareInvocation (a2.1): CanonicalCoreStepDecoder.decode(node)
           require(node.payload.schemaVersion == "dsl-v1")   [SCHEMA_VERSION]
           Json.parseToJsonElement(node.payload.encoded).jsonObject
           when (pluginStepId.value) { "core.echo" -> Echo(requiredString("text")) ... }
       -> CanonicalCoreStepCommand (sealed, closed legacy world)
  -> durable protocol (a2): prepare -> reconcile(InvocationReconciliation) ->
       Execute { StepExecutionBoundary { stepExecutor.invoke(typedCommand, CanonicalRuntimeContext) } }
       -> journal.append(RerunOperation(status=outcome.toOperationStatus())) -> cursor advance
  -> stepExecutor default = CanonicalNodeDispatcher.dispatch -> concrete `when(command)`
       -> Echo case -> CanonicalEchoNodeDispatcher / SDK echo() -> EchoOutputCaptured
```

### 1.1 The two representations (do NOT conflate)

| Concern | Real holder | Value for echo |
|---|---|---|
| **Durable identity / replay / fingerprint / journal** | `node.payload.encoded` (`VersionedStepPayload`), wrapped by coordinator as `OperationInput(params={"payload": JsonPrimitive(encoded)})` then `Fingerprint.compute` | `{"kind":"echo","text":"hi"}` (dsl-v1 JSON object; compiler `DslCompiledPipelineCompiler.encodePayload`) |
| **Plugin input representation** (`StepCodec<I>`) | `EncodedStepValue` decoded by `StepDefinition.inputCodec` | Today, `CoreEchoStep.inputCodec.decode` returns `EchoInput(encoded.value)` = **plain text** |

`EncodedStepValue` is a bare non-empty string; `payload.encoded` is a JSON-object string. They are
structurally both `String` but **semantically different contracts**.

### 1.2 Where decoder selection happens today

`CanonicalCoreStepDecoder.decode` (application/CanonicalCoreStepDecoder.kt) is the single selection
point: `when (pluginStepId.value) { "core.echo" -> ... }` over the **name string** + the dsl-v1
`schemaVersion` gate. No reusable canonical/plugin invocation abstraction exists above it.

### 1.3 Who feeds CoreEchoStep today

Only `CoreEchoSeamTest` (B1.2b), via its own codec round-trip (`encode(EchoInput("hi")) ->
EncodedStepValue("hi")`). It is **never** fed a real dsl-v1 payload. So today the registry echo input
contract is an independent, invented plain-text form, unexercised by the engine.

## 2. Payload mismatch — A/B/C determination (evidence)

The mismatch:
```
envelope durable payload   : {"kind":"echo","text":"hi"}   (dsl-v1 object)
CoreEchoStep.inputCodec    : EncodedStepValue == plain "hi" -> EchoInput("hi")
```

- **A (redundant generic wrapper)? NO.** Unwrapping `{"kind":"echo", ...}` down to the *text value* is
  echo-semantic (it extracts the `text` field), not a generic envelope strip. A step-agnostic
  normalization cannot produce echo's bare text.
- **B (CoreEchoStep codec over the wrong type)? Predominantly YES.** The registry contract must decode
  the durable canonical payload the engine actually holds, otherwise routing forces the forbidden
  `decode -> encode -> decode`. `CoreEchoStep`'s plain-text codec encodes a contract that does not match
  `node.payload.encoded`; it sits over the wrong representation. Its correction (decode the dsl-v1 echo
  object it will receive) belongs to **B1.2c3 (echo migration)**, not CDE.
- **C (distinct contracts, needs new canonical encoded invocation contract)? PARTIAL / deferred.** For
  core steps we own both sides, so B keeps durable identity == plugin input. Whether *external* plugin
  nodes carry the dsl-v1 `{"kind":...}` envelope or their own clean schema is an open, explicit
  architecture/versioning question that CDE's generic fixture must not silently decide. If the generic
  seam requires a common pre-decode form that diverges from `VersionedStepPayload`, that is a durable/
  versioning decision needing an ADR/amendment (stop criterion).

### CDE.1 conclusion (design principle)

Introduce a pre-decode **structural invocation** that carries the durable canonical payload **unchanged**
(`pluginStepId` + `schemaVersion` + `encoded`), so fingerprint/journal/operation identity are untouched.
The registry strategy feeds that same encoded string to `StepDefinition.inputCodec`; the durable
authority stays a single protocol. Reuse/divergence/abort are decided before any handler runs. A
step-agnostic, lossless adapter (`persisted canonical payload -> EncodedStepValue`) is valid; a
`when(key){ Echo->text ... }` re-encode bridge is forbidden.

## 3. CDE decomposition (revised, sub-slices, single gate)

- **CDE.1 — Ground + structural invocation model.** Identify the pre-decode seam; introduce/adapt the
  minimal structural representation (`CanonicalInvocation`: stepKey + encoded input + schemaVersion, or
  an equivalent structural ADT over the REAL payload types). Behaviour-preserving; legacy remains the
  only productive execution. Gate complete.
- **CDE.2 — Legacy adapter behind the new seam.** Express the existing path as `pre-decode invocation ->
  legacy adapter -> existing decoder/CanonicalCoreStepCommand -> existing execution`, registry absent.
  Proves the new frontier changes nothing. Gate: 42 characterization + durable/replay + CLI.
  **Grounding (2026-09-08):** `effects`/`replayPolicy` that feed fingerprint/reconcile are CONSTANT per
  `pluginId` (`CanonicalCoreStepCommand` subtypes hardcode `defaultMetadata`: Echo→(READ_ONLY,MEMOIZED),
  Shell→(EXECUTES_SUBPROCESS,RERUN), Error→(ABORTS_PIPELINE,NEVER), ...) and pluginId is already on the
  node (`node.pluginStepId`, 1:1 with the sealed subtype). So metadata is resolvable by `stepKey`
  WITHOUT decode. The only decode-dependent pre-execution concern is the EmitEvent context-overlay
  (`core.emit.event` CatchErrorEntered/Triggered), which stays a coordinator concern on the legacy path.
  CDE.2 therefore does NOT change durable inputs.
  **STOP-CRITERION FINDING (2026-09-08, from real code + frozen a1 laws):** "decode fully behind the
  Execute executor seam" is NOT behaviour-preserving against the frozen characterization. C3 asserts
  `decode-fail -> stepExecutor.calls == 0`, and `RecordingInvocationExecutor` counts calls to the
  `CanonicalInvocationExecutor` invoked in the Execute branch. Today decode fails in prepareInvocation
  BEFORE that seam, so a malformed fresh step never reaches it. If decode moved inside a
  `LegacyExecutionAdapter` invoked by stepExecutor within Execute, C3 flips to 1. Also decode currently
  runs before reconcile, so a malformed payload with a diverged/valid journal row classifies SCHEMA today
  but would classify INFRASTRUCTURE "diverged" (divergence runs first) under the new order; and the
  EmitEvent/CatchError context-overlay is applied in prepare (pre-reconcile) on decode, so moving decode
  changes overlay timing for a reused EmitEvent. Each is an observable failure-classification / frozen-law
  change. **Reachable satisfiable form:** move decode into the Execute branch but as a PRE-step before the
  effective-executor seam (decode-fail returns the terminal SCHEMA outcome and FAILED journal write
  without invoking stepExecutor -> C3 stays 0; metadata stays stepKey-resolved). Confirm the malformed+
  journaled classification and EmitEvent-overlay timing against Uat/durable suite before committing.

### 3.1 CDE.2 pending characterizations — C5 result (frozen 18920b9a)

**C5 (malformed payload + matching journaled SUCCESS), observed from current code (not assumed):** the
durable protocol is **DECODE-FIRST**. A malformed payload `{not-valid-json` is rejected as `SCHEMA`
Failure with `executor=0` even when a pre-seeded SUCCEEDED journal row has a fingerprint matching the
malformed payload (reuse-eligible). Schema validation therefore **precedes** replay/reuse; a reuse must
not skip it.

**Exact current state machine (non-block canonical step), from code + C3/C4/C5/S1/C1:**
```
decode (prepareInvocation): parse dsl-v1 envelope + typed field extraction
   FAIL -> journal FAILED + return SCHEMA Failure   (executor=0)   [C3, C5]
   OK   -> typedCommand
      -> reconcile: divergence (INFRA fail) -> shell recover -> effectReplayPolicy
            Diverged       -> INFRA fail (executor=0)              [C4]
            RecoverRunning -> recovered outcome (executor=0)       [S1]
            ReuseCompleted -> Success (executor=0)                 [C2]
            RejectedAbort  -> INFRA fail (executor=0)
            Execute        -> stepExecutor.invoke(decodedCommand) (executor=1)  [C1]
```

**Implication for CDE.2 (the validation-vs-decode refinement):** typed semantic decode cannot move behind
Execute while preserving C3/C5 unless a **schema/envelope validation phase precedes durable resolution**.
The current codec couples (a) generic envelope validation (JSON parse, `kind`) and (b) typed semantic
field extraction (`EchoInput`). C5/C3 only prove (a) must precede reuse. Whether (b) a field-invalid but
envelope-valid payload must also precede reuse is NOT frozen by any test yet: moving (b) into Execute
would let it be REUSED (Success) where today it rejects SCHEMA. **Open question for the next pass:** does
any Uat/durable test freeze field-invalid + reuse-eligible as SCHEMA? If yes, full validation stays before
reuse (decode-first is structural). If no, envelope-validation-before-reconcile + typed-decode-in-Execute
is behaviour-preserving for the frozen suite. **Characterization #2 (EmitEvent/CatchError timing under
reuse) remains pending.**

**C6 result (frozen 503c5556):** a reused `CatchErrorEntered(UNSTABLE)` (journaled SUCCESS) still pushes
the context overlay pre-reconcile, so a fresh failing `sh "exit 1"` inside it downgrades to `Unstable`
(executor: reused emit 0, fresh sh 1). EmitEvent/CatchError overlay resolution therefore happens before
durable reconciliation even under reuse (consistent with C5 decode-first) and must live in
StructuralPreparation or a neighbouring explicit pre-durable phase, NOT in TypedInputDecode.
**Both pending characterizations (#1 C5, #2 C6) are now complete.**

### 3.2 CDE.2 open question — ANSWERED (no test freezes field-invalid + reuse as SCHEMA)

Scanned the durable/UAT/coordinator/dispatcher tests: every SCHEMA case is structural or fresh — C3/C5
use malformed JSON (parse failure); the coordinator schema test uses `schemaVersion "dsl-v0"` (version
gate) on a fresh journal; the EmitEvent dispatcher test uses an unknown `kind`. Reuse tests (C2,
UatEvt, CLI reuse) all carry complete valid payloads. **No test freezes an envelope-valid but
field-missing payload under a reuse-eligible journal as SCHEMA.** Therefore only structural/envelope
validation (JSON parse + dsl-v1 schemaVersion + `kind`/pluginId) must precede durable resolution; typed
semantic field extraction (`EchoInput.text` etc.) may move into Execute without violating the frozen
suite.

**CDE.2 implementation shape (now fully determined):**
1. **StructuralPreparation (pre-reconcile):** validate schemaVersion, parse the dsl-v1 envelope, verify
   `kind`/pluginId, resolve durable metadata by `stepKey` (constant per pluginId) -> `Ready(structural)`
   or `Rejected(SCHEMA)`. Rejected journals FAILED + returns (executor=0) — preserves C3/C5/schema gate.
   This is step-agnostic (only the `kind` value differs per plugin).
2. **Durable resolution** over the structural invocation (no `CanonicalCoreStepCommand` needed):
   Reuse / Diverged / Recover / Execute.
3. **Execute -> TypedInputDecode:** typed field extraction -> `Ready(input)` (-> stepExecutor, executor=1)
   or `Rejected(SCHEMA)` (terminal, executor=0, journal FAILED) — fresh field-invalid stays SCHEMA;
   field-invalid under reuse is not frozen (reuse may win), behaviour-preserving for the suite.

This satisfies the CDE.2 gate: reuse/divergence decide with structural validation only; typed decode and
executor stay 0 on reuse/divergence; fresh valid = decode 1 + executor 1; fresh malformed = SCHEMA
executor 0. The typed decode can now move behind Execute as a PRE-step before the executor seam (so C3's
executor=0 for a fresh field-invalid holds).

- **CDE.3 — RegistryExecutionAdapter.** `generic invocation -> StepRegistry -> RegistryStepInvoker ->
  codec.decode(raw input) -> typed handler`, no Step-name cases. Proven in isolation AND under the
  durable protocol with a **generic fixture** (not echo). Missing key / missing capability / decode
  failure fail closed before the handler.
- **CDE.4 — Registry injection / composition.** Inject `StepRegistry` at composition roots
  (`Main.kt`, `PipelineRule.kt`) as the minimal compatible dependency. No general DI cleanup.
- **CDE.5 — Structural routing.** Select generic-vs-legacy by node form/schema/type, never a
  `when(stepName)`.

## 4. CDE architectural gate

| Scenario | Required |
|---|---|
| Fresh generic invocation | registry handler executions == 1 |
| Replay-reuse generic invocation | registry handler executions == 0 |
| Divergence | registry handler executions == 0 |
| Decode rejection | typed handler executions == 0; current durable failure semantics preserved |
| Legacy steps (not yet migrated) | still function via compatibility path |
| Durable protocol | single authority for journal/replay/fingerprint/divergence/cursor/lifecycle; no parallel durable path for plugins |

## 5. Echo deferred on purpose

No Echo-specific semantics in CDE; the seam must be provable with a generic definition/fixture. After
CDE, **B1.2c3** (register/route `core.echo` via the generic structural invocation) is the definitive
test that the seam was not shaped for Echo.

## 6. Stop criterion (unchanged)

If grounding shows a common pre-decode representation forces a change to journal schema / persisted
payload / fingerprint semantics / operation identity / replay compatibility, stop with evidence (it is a
new durable/versioning decision needing an ADR/amendment). If it is expressible as a behaviour-preserving
generic view/adaptation over the existing representation, continue.

## 7. First action, next pass

> CDE.1 — with the map above: introduce/adapt the minimal structural (pre-decode) invocation model
> over the REAL payload types (`VersionedStepPayload` / `EncodedStepValue`), behaviour-preserving,
> legacy still sole productive execution. Compile -> focused coordinator -> 42 characterization ->
> durable/replay -> full `:pipeline-application:test` -> fresh XML -> atomic commit.

---

## 8. CDE.3 grounding — PreparedExecution / common execution seam (2026-09-08)

Grounding of CDE.3, per the pre-authorized next-pass scope: contrast the real legacy and registry
signatures to find the minimal common abstraction that preserves `prepare/decode -> Rejected | Ready ->
único executor -> effects`, keeping replay/reuse/divergence outside both decode and handler.

### 8.1 Real-signature table (contrasted from source)

| Phase | Legacy (today, productive) | Registry (`domain/step`, isolated) | Common frontier |
|---|---|---|---|
| Structural identity | `CanonicalStructuralPreparation.prepare(node)` → `Ready(CanonicalInvocation(stepKey,schemaVersion,encodedInput)+envelope)` | same structural invocation (`CanonicalInvocation.fromNode`) | yes — CDE.1 |
| Durable metadata | `stepMetadataResolver.resolve(stepKey)` (legacy table default) | `StepContract.descriptor.effects/replayPolicy` (registry bridge not yet wired) | resolvable by stepKey (CDE.2-b2/b3) |
| Typed decode | `LegacyExecutionBoundary.decode(step)` → `Rejected(reason)` \| `Ready(command: CanonicalCoreStepCommand)` | `codec.decode(EncodedStepValue)` (via `RegistryStepInvoker`) — no handler yet | prepare (no side effects) |
| Prepared execution | `CanonicalCoreStepCommand` (closed sealed, 14 subtypes) | nothing yet; would be erased decoded input + handler ref | NEEDS abstraction |
| Effects | `CanonicalNodeDispatcher.dispatch(command, CanonicalRuntimeContext)` → legacy per-step `*NodeDispatcher` (`suspend`) | `StepHandler.execute(input, StepHandlerContext)` (`fun`, non-suspend) → `O` | common executor seam |
| Output | `StepOutcome` (Success/Unstable/Failure) directly | `O: Any` then `outputCodec` — **no O→StepOutcome mapping exists** | must converge to `StepOutcome` |

### 8.2 Stop-criterion finding (evidence, from real code)

`CanonicalInvocationExecutor` (the frozen a1 seam, `CanonicalInvocationExecutor.kt:19`) is a
`fun interface suspend invoke(command: CanonicalCoreStepCommand, context: CanonicalRuntimeContext):
StepOutcome`. It is intrinsically coupled to BOTH the closed command world AND the rich
`CanonicalRuntimeContext` (opId/runId/stage/shOptions/controlDirRoot/eventSink). Its construction sites
are all `{ command, ctx -> CanonicalNodeDispatcher().dispatch(command, ctx) }`:
the coordinator production default (`CanonicalDurableRunCoordinator.kt:292`) and 7 lambdas in
`DurableProtocolInvocationCharacterizationTest.kt` (lines 100/156/208/253/333/408/495). `CanonicalRuntimeContext`
is built at only 3 sites.

**Conclusion: the user's stop criterion fires.** `CanonicalInvocationExecutor` cannot accept a common
`PreparedExecution` without either (a) the coordinator still naming `CanonicalCoreStepCommand` (breaks
F1) or (b) rewriting the ~8 executor construction sites AND the runtime-context shape (big-bang). Do NOT
mutate it in place.

Deeper structural fact: legacy and registry have **different effect models** and **different result
types**. Legacy dispatchers are `suspend` and each take a narrow per-step `*DispatchContext` derived from
the rich `CanonicalRuntimeContext`, returning `StepOutcome` directly. Registry handlers are non-`suspend`
`fun`, take `StepHandlerContext` + `StepCapabilityAccess`, and return a typed `O` that is NOT `StepOutcome`.
Forcing both behind one `PreparedExecution.execute(): StepOutcome` would require an O→StepOutcome
normalization that does not exist yet (that is CDE.3-e) and an async handler surface that registry does
not yet have (a B1.3 finding, must not be decided here).

### 8.3 Grounded decision

Follow the user's stop-criterion remediation: a **parallel/temporary common seam**, legacy adapted behind
it, no big-bang. Shape (the "opaque data" option, not the execute-lambda option — keeps capabilities out
of the prepared object and out of the durable protocol):

```
StructuralInvocation
   -> strategy.prepare(...)                    // NO side effects
        -> ExecutionPreparation { Rejected(StepFailure) | Ready(prepared: PreparedExecution) }
   -> common executor  (the single a1 "effect" seam)
        -> when(strategyKind)                  // sealed over 2 strategy kinds, never N plugins
             legacy    -> existing legacy dispatcher path (still needs its rich runtime)
             registry  -> capability admission + typed handler + O->StepOutcome normalization
        -> StepOutcome
```

- `PreparedExecution` is an **opaque runtime-ephemeral marker**: it carries already-admitted, decoded data
  (or a strategy ref); it is never constructed with side effects, never inspected semantically by the
  durable protocol, never persisted/replayed (replay re-prepares only when Durable Resolution says
  Execute).
- The **prepare side already exists for legacy** (`LegacyExecutionBoundary.decode` == legacy prepare:
  `Rejected|Ready`). Registry prepare = resolve + capability admission + `codec.decode` (handler still not
  run). So the gap is entirely on the execute side.
- The single executor must internally route to **exactly two** strategy kinds (legacy/registry), never a
  `when(stepName)`. Capabilities live in the executor's runtime, not inside `PreparedExecution`.
- `O` must never escape to the durable protocol as `Any`; the strategy normalizes it to `StepOutcome`
  behind the seam (CDE.3-e). A generic fixture whose handler returns only a success marker proves only the
  success subset until CDE.3-e lands; that is accepted and must be documented, not silently generalised.

### 8.4 Laws the common seam must preserve (frozen authority)

```
reuse                        prepare = 0, executor = 0
divergence                   prepare = 0, executor = 0
recover                      prepare = 0, executor = 0 (legacy shell recovery intact)
fresh structural-invalid     prepare = 0, executor = 0
fresh typed-invalid          prepare = 1, executor = 0
fresh valid legacy           prepare = 1, executor = 1
fresh valid registry         prepare = 1, executor = 1
replay reusable registry     codec = 0, handler = 0   (Durable Resolution cuts before prepare)
```

### 8.5 CDE.3-a outcome and the next real code slice

CDE.3-a (this grounding) is design only, per the plan; no production change yet. The next real code slice
is **CDE.3-b: adapt the legacy path behind the new common seam** (legacy produces the prepared
representation and still executes through the single executor), with the full suite staying identical.
CDE.3-b requires its own characterization cycle (the 42 frozen + durable/replay), so it opens a dedicated
implementation round rather than being rushed here.

---

## 9. CDE.3-b1 — Characterization of the current executor contract (2026-09-08)

Scoped to the exact frontier that CDE.3-b will rewire. Sources read in full:
`CanonicalInvocationExecutor.kt`, `LegacyExecutionBoundary.kt`,
`CanonicalDurableRunCoordinator.kt`, `CanonicalNodeDispatcher.kt`,
`CanonicalCoreStepDecoder.kt`, and every `RecordingInvocationExecutor` site in
`DurableProtocolInvocationCharacterizationTest.kt`.

### 9.1 What actually enters the executor

The durable coordinator reaches the executor ONLY under
`InvocationReconciliation.Execute` (`CanonicalDurableRunCoordinator.kt:572`). Nothing else can reach it.
Inside that branch, in frozen order:

1. **Typed decode** (`LegacyExecutionBoundary.decode(step)`, line 577): a `Rejected` returns
   `rejectSchema(...)` (SCHEMA Failure journaled, executor NEVER invoked); a `Ready(command)` unwraps a
   concrete `CanonicalCoreStepCommand`.
2. **`journal.beginOperation`** if the journal row is fresh (line 586).
3. **Executor call** `stepExecutor.invoke(typedCommand, CanonicalRuntimeContext(...))` (line 591),
   wrapped by `StepExecutionBoundary(eventSink).execute(lifecycleContext) { ... }`.
4. **Terminal journal append** of the resulting `RerunOperation` (status from `outcome.toOperationStatus()`).
5. **Cursor advance** only when the outcome is not a `Failure` (line 615).

The executor's real arguments:
- `command: CanonicalCoreStepCommand` — a closed sealed of 14 subtypes, produced by
  `CanonicalCoreStepDecoder.decode`, which is the ONLY producer. `LegacyExecutionBoundary` catches
  `IllegalArgumentException` from every decode `require(...)` / `throw` and turns it into `Rejected`;
  there is no other Rejected producer today.
- `context: CanonicalRuntimeContext` — a data class the coordinator builds inline at the single call
  site (line 593): `opId, runId, stageName, stageIndex, stepIndex, shOptions, controlDirRoot, eventSink`.
  Built at exactly 3 sites total (this one is the only production Execute site).

### 9.2 Ownership

- `CanonicalCoreStepCommand` is owned by the closed legacy world. `CanonicalNodeDispatcher.dispatch`
  is the sole consumer and is a `when(command)` over the 14 closed subtypes routing to the narrow
  per-step `*NodeDispatcher`. That `when` is LEGACY-INTERNAL and stays put; the new common seam must not
  add a second one.
- **The coordinator names the concrete command transitively only through the executor seam's signature.**
  Its own class signature imports `CanonicalCoreStepCommand`? It does not (checked the import list), but
  the local `typedCommand` is that concrete type and is passed to `stepExecutor.invoke`, so the Execute
  branch is coupled to the concrete command through `CanonicalInvocationExecutor`. This is exactly what
  CDE.3-b5 (gate item 4) must remove: the coordinator will hand an opaque `PreparedExecution` to the new
  common executor and never name `CanonicalCoreStepCommand`.

### 9.3 Context / capabilities used

`CanonicalRuntimeContext` is the durable runtime the coordinator owns. Per-step dispatchers derive a
narrow `*DispatchContext` from it (e.g. `echoContext()` uses `eventSink`; `shellContext()` uses
`opId/runId/shOptions/controlDirRoot/eventSink/...`). No step receives the whole coordinator, journal,
cursor, lifecycle, credential port or context stack. Capabilities are already narrow per step via the
`*DispatchContext` derivation; they are NOT inside the executor seam's command payload.

### 9.4 Result & failure contract

- Output is always `StepOutcome` (closed Success/Unstable/Failure). The executor seam returns
  `StepOutcome`; there is no `Any`/`Result<*>` anywhere on the durable path.
- A returned `Failure` is journaled and the cursor is NOT advanced; the coordinator's
  `decideContinuation` walks the catchError chain. Rejection (`rejectSchema`) is a distinct terminal:
  SCHEMA Failure journaled under a RERUN fingerprint, executor never called.

### 9.5 Existing characterization adequacy (what protects the frontier)

The `RecordingInvocationExecutor` freezes the "executor == effective side-effect execution" signal across
the whole durable protocol: C1 fresh=1; C2 reuse=0; C4 divergence=0; C3 decode-failure=0; C5
decode-first precedence over a matching journaled success=0; S1 running-shell recovery=0; C6 reused
CatchErrorEntered overlay still pushed (failing sh fresh=1, outcome Unstable); a1-4 lifecycle
`StepStarted < semantic < StepFinished` ordering.

**Gap for CDE.3-b, and only that gap:** the recorder counts the executor seam alone; it CANNOT yet
distinguish `prepare` from `execute`. The law `fresh typed-invalid legacy -> prepare=1, Ready=0,
executor=0` therefore has no current oracle. A prepare-vs-executor split measurement is impossible until
the b2/b3 prepare seam exists, so that recorder is introduced WITH b3 (not in b1). No executor-counting
or ordering property is missing today; none of the existing tests need to be weakened.

### 9.6 What must be invariant across b2-b5

The one non-negotiable semantic (user law + a1): **"Rejected during preparation -> executor = 0;
Ready -> executor = 1".** The executor seam must keep meaning effective side-effect execution; b3 must
not move the rejection count onto the executor to make the abstraction fit. The durable lifecycle,
journal, replay, cursor and failure persistence stay owned by the spine (`StepExecutionBoundary` +
coordinator), never duplicated into the new seam.

---

## 10. CDE.3-b DONE — legacy behind the common seam (2026-09-08)

Executed b1–b6. `CommonExecutionBoundary` is now the authority the a1 law observes; the old
command-typed `CanonicalInvocationExecutor` is a deprecated legacy compatibility detail behind
`LegacyExecutionAdapter`.

### 10.1 What landed (per slice)

| Slice | Commit | Result |
|---|---|---|
| b1 characterize | `22a5c7d0` | executor frontier documented (§9); no test needed (prepare counter belongs to b3) |
| b2 additive seam | `ea47e52a` | `PreparedExecution` (opaque open marker), `CommonExecutionBoundary` (fun interface `execute(prepared, CanonicalRuntimeContext): StepOutcome`), `PreparedLegacyExecution`, `LegacyExecutionAdapter`; production untouched; adapter test |
| b3 rewire + dual | `9eec3f90` | `LegacyExecutionBoundary.decode`→`prepare: ExecutionPreparation{Rejected|Ready}`; coordinator Execute prepares opaque `PreparedExecution` then calls the common seam; trailing `commonExecutionBoundary` param defaults to the adapter over the injected old executor (existing callers unchanged); `DualExecutionSeamCharacterizationTest` proves `commonCalls==legacyCalls` |
| b4 migrate law | `3450df59` | frozen `DurableProtocolInvocationCharacterizationTest` observes `CommonExecutionBoundary`; `RecordingBoundary` replaces `RecordingInvocationExecutor`; assertion values unchanged |
| b5 retire | `fdc21da3` | old seam `@Deprecated` as compatibility detail; explicit retire-with-legacy-dispatcher task |
| b6 proof | gate runs | below |

### 10.2 CDE.3-b gate evidence

- Coordinator reaches the effective seam ONLY under Execute, and now names neither
  `CanonicalCoreStepCommand` nor the retired `LegacyExecution` ADT (gate items 4, 5, 6 verified).
- `DurableProtocolInvocationCharacterizationTest` 8/0/0 (fresh XML) — now on `CommonExecutionBoundary`:
  fresh=1, reuse=0, divergence=0, decode/schema=0, shell recovery=0, C5 decode-first=0, C6 overlay
  reuse executor=1, a1-4 lifecycle order.
- `DualExecutionSeamCharacterizationTest` 5/0/0 — equivalence law holds across all legacy resolution
  families (1==1 and 0==0).
- `LegacyExecutionAdapterTest` 2/0/0; full durable package 114/0/0.
- `:pipeline-architecture-tests --rerun-tasks` 165/0/0 fresh XML.
- Full `:pipeline-application:test` + UatLocal NOT run (scoped verification per change-scoped rule;
  pre-existing UatLocal failures fail at base `a05682ec`, not this change). Full verification = NO.

### 10.3 Laws now stated on the common seam (frozen authority)

```
structural-invalid        prepare = 0, commonExecution = 0
reuse                     prepare = 0, commonExecution = 0
divergence                prepare = 0, commonExecution = 0
recover                   fresh prepare = 0, commonExecution = 0 (legacy shell recovery intact)
fresh typed-invalid legacy prepare = 1, Ready = 0, commonExecution = 0  (SCHEMA)
fresh valid legacy        prepare = 1, Ready = 1, commonExecution = 1  (same StepOutcome)
```
Note: `prepare` counters (distinct from `commonExecution`) are introduced WITH the registry prepare
seam (CDE.3-c), which is where the dual-seam equivalence becomes a common-vs-registry frontier.

### 10.4 Result shape

```
Durable Protocol
      |   (opaque PreparedExecution)
      v
CommonExecutionBoundary            <- new authority (a1 law, both legacy & future registry)
      |
      +---- LegacyExecutionAdapter -------> CanonicalInvocationExecutor (@Deprecated compat)
      |                                           |
      |                                           v
      |                                   legacy dispatcher
      `---- (future) registry strategy -> typed handler -> output normalization
```

### 10.5 Next pass — CDE.3-c

Registry prepare: `StepRegistry -> ErasedStepAdapter -> codec -> Rejected | Ready(PreparedRegistryExecution)`,
reusing the SAME `ExecutionPreparation`/`CommonExecutionBoundary` contract. No change to the durable
coordinator frontier is expected (it already routes by opaque `PreparedExecution`); CDE.3-c wires the
registry strategy selection by structural step key and proves `fresh typed-invalid registry
prepare=1 Ready=0 commonExecution=0` and `fresh valid registry prepare=1 Ready=1 commonExecution=1`
without touching legacy.

---

## 11. CDE.3-c grounding — registry prepare seam (2026-09-08)

Grounding of the second implementation of the contract demonstrated in CDE.3-b. Sources read in
full: `domain/step/StepRegistry.kt`, `application/CoreEchoStep.kt`, `CoreEchoSeamTest.kt`.

### 11.1 Registry real signature table (contrasted)

| Concept | Registry (domain/step) | Common frontier (CDE.3-b) |
|---|---|---|
| Identity | `PluginStepId` key | coordinator routes by structural step key; legacy metadata already keyed by `PluginStepId` |
| Registration | `StepRegistry.register(StepDefinition<I,O>)`, deterministic duplicate rejection | open, no privileged path |
| Typed contract | `StepContract<I,O>` (descriptor + inputCodec + outputCodec + requiredCapabilities) | — |
| Typed decode | `contract.inputCodec.decode(EncodedStepValue)` | == prepare (no side effects) |
| Capability admission | `StepInvocationOutcome.MissingCapability` (before handler) | == prepare rejection (executor 0) |
| Handler | `StepHandler.execute(input, StepHandlerContext)` `fun`, returns `O` | == execute (effects) |
| Runtime for handler | `StepHandlerContext(runId, stepIndex, StepCapabilityAccess)` — NARROW | must be derived from `CanonicalRuntimeContext` (NOT the whole coordinator) |
| Erased adapter | `RegistryStepInvoker` (domain/step) | per-bridge erased seam |
| Output | `O: Any` via `outputCodec` | **no O→StepOutcome mapping exists (CDE.3-e)** |

### 11.2 Key finding: RegistryStepInvoker FUSES decode and handler

`RegistryStepInvoker.invoke` (StepRegistry.kt:178-205) does capability admission + decode + handler in
one call, returning `StepInvocationOutcome<O>`. There is NO codec-only prepare path today: a
`DecodeFailure` or `MissingCapability` aborts before the handler, but the caller cannot observe
"decoded-and-admitted-but-not-run" as a distinct state. CDE.3-c therefore needs a registry PREPARE
boundary that returns `Rejected | Ready(PreparedRegistryExecution)` and never calls `handler.execute`.
This mirrors exactly the legacy split in CDE.3-b3 (decode→prepare vs execute→boundary).

### 11.3 Design constraints for the registry prepare seam

- A registry `prepare` must produce an opaque `PreparedRegistryExecution : PreparedExecution` that
  carries the admitted contract + decoded input, is runtime-ephemeral, never persisted / fingerprinted
  / replayed, and exposes no durable state. Only the registry execution path reads it.
- `StepRegistry` is in `pipeline-domain` (inward). `CommonExecutionBoundary`/`PreparedExecution` are in
  `pipeline-application` durable. `pipeline-application` already depends on `pipeline-domain`, so an
  application-own registry prepare adapter may reference `domain.step` types; dependency direction stays
  inward (domain.step never names application.durable).
- `PreparedRegistryExecution` cannot be a subtype of a sealed-in-application `PreparedExecution` if
  registry lives in another module, but here both the adapter and PreparedExecution live in
  `pipeline-application` (the registry SELECTION and PREPARE are engine-side; only the contract/codec
  are domain). `PreparedExecution` is an open interface precisely so an application-side
  `PreparedRegistryExecution` can be added without editing legacy.

### 11.4 Coordinator strategy selection (the CDE.3-c integration point)

The durable coordinator's Execute branch currently calls `LegacyExecutionBoundary.prepare(step)`
unconditionally. To route a registry step through the SAME `ExecutionPreparation`/boundary contract, the
PREPARE must be strategy-dispatched by structural step key (registry-owned key -> registry prepare;
otherwise -> legacy prepare). The EXECUTION authority seam is untouched: it already receives an opaque
`PreparedExecution` and the coordinator never names a strategy payload. CDE.3-c introduces the prepare
selector; the registry EXECUTE path (capability admission from `CanonicalRuntimeContext` ->
`StepCapabilityAccess`, handler.run, O->StepOutcome) is CDE.3-d/e.

### 11.5 Capability bridge (deferred to CDE.3-d/e, must not be decided here)

Legacy derives narrow per-step `*DispatchContext` from `CanonicalRuntimeContext`. A registry handler
needs `StepHandlerContext(runId, stepIndex, StepCapabilityAccess)` where capabilities come from the
engine runtime. The CDE.3-b executor receives `CanonicalRuntimeContext`; registry execute must derive
`StepCapabilityAccess` from it (eventSink today) WITHOUT handing the whole coordinator. This is a
distinct design (CDE.3-d/e), not part of prepare.

### 11.6 Next real code slice for CDE.3-c

Add the registry PREPARE seam + `PreparedRegistryExecution`, exercised by a focused unit test that
proves decode-only admission (handler NOT run) for fresh valid vs fresh typed-invalid, reusing
`ExecutionPreparation`. Then wire the coordinator prepare selector by structural step key and prove the
registry laws. Requires its own compile -> focused -> characterization cycle in a dedicated round; not
rushed here.

### 11.7 CDE.3-c slice 1 LANDED — registry prepare seam (2026-09-08)

`RegistryExecutionPreparation.prepare(registry, key, encodedInput, availableCapabilities)` returns
`ExecutionPreparation { Rejected | Ready(PreparedRegistryExecution) }`, additive (no coordinator/legacy
change), commit `58f63a37`. Frozen by `RegistryExecutionPreparationTest` 5/0/0: Ready-on-valid handler=0;
typed-invalid Reject handler=0; unknown-key Reject; missing-capability Reject BEFORE decode handler=0;
supplied-capability Ready. `PreparedRegistryExecution` carries the admitted `StepDefinition<*,*>` and the
erased decoded input for the CDE.3-d execute path.

**Dependency boundary (why the coordinator registry law proof is NOT slice 1):** proving
`fresh valid registry prepare=1 Ready=1 commonExecution=1` and the selector by structural step key
requires a registry EXECUTE path on [CommonExecutionBoundary]. A registry-prepared execution routed to
the boundary today has no executor (the legacy adapter rejects non-legacy prepared with
`EngineInvariantViolation`). So the coordinator selector + the registry `commonExecution` laws are
inherently coupled to CDE.3-d/e (registry execute: capability bridge `CanonicalRuntimeContext` ->
`StepCapabilityAccess`, `handler.execute`, O->StepOutcome). They are therefore opened TOGETHER with the
CDE.3-d round; wiring the selector now would leave an unrunnable path. CDE.3-c slice 1 stands as the
independently verifiable decode-only registry prepare contract.

---

## 12. CDE.3-d ACCEPTED — registry execute through the common seam (plan for the next pass)

Accepted checkpoint (frontier clean): CDE.3-b DONE + CDE.3-c slice 1 DONE. CDE.3-d is the next real
slice, to start directly from §11.7 — do NOT re-plan B1/CDE.1/CDE.2/CDE.3-a/b/c.

### 12.1 Goal

A `PreparedRegistryExecution` must traverse the SAME authoritative [CommonExecutionBoundary] as legacy
and land on a `StepOutcome`, preserving: prepare-rejected -> commonExecution = 0; prepare-ready ->
commonExecution = 1; replay reuse -> registry prepare = 0, commonExecution = 0, handler = 0;
divergence -> registry prepare = 0, commonExecution = 0, handler = 0.

### 12.2 Decomposition (d1–d5, one behavior per commit)

**d1 — Capability bridge.** Only `CanonicalRuntimeContext -> StepCapabilityAccess`, explicit and small.
Expose ONLY known+declared capabilities (`EVENT_SINK_CAPABILITY` today). Never hand the full
`CanonicalRuntimeContext` to a handler; never rebuild a PipelineContext. Direction:
`StepContract.requiredCapabilities -> admission -> StepCapabilityAccess -> handler`, never the reverse.
Gate: capability lookup focused tests; missing capability fail-closed; existing legacy behaviour intact;
durable + architecture green. Atomic commit.

**d2 — CommonExecutionBoundary strategy routing.** Route at least two PreparedExecution classes
(`PreparedLegacyExecution`, `PreparedRegistryExecution`) by strategy kind / representation, NOT by
stepKey and NOT by a sealed hierarchy over concrete plugins. Acceptable structural categories:
Legacy-compatible form | Registry form. Not acceptable: `PreparedExecution.Echo/.Sh/.Uppercase`. Gate:
legacy common execution stays 1:1; a registry prepared fixture crosses the boundary; no concrete handler
needs special-casing. Commit.

**d3 — Registry handler execution.** `PreparedRegistryExecution -> capability admission -> erased
adapter -> typed handler.execute(input)`. Prepare/codec already happened BEFORE the boundary; handler
must NOT re-decode. Demonstrate fresh valid registry prepare=1 commonExecution=1 handler=1;
typed-invalid registry prepare=1 commonExecution=0 handler=0; missing capability handler=0 with
admission semantics clearly characterized (decide with evidence whether admission is in prepare or
immediately before the handler inside the boundary, but a handler must never start with missing
capabilities). Commit green.

**d4 — O -> StepOutcome normalization.** Do not let `Any?` escape to the durable coordinator. Path:
`handler: I -> O -> output codec/adapter -> canonical encoded result -> StepOutcome`. If `StepOutcome`
cannot represent generic output correctly, ground the gap first; do NOT deform it with casts; do NOT
silently change the journal schema. Gate: typed output, void/unit output, handler failure, output-encode
failure (typed), durable coordinator sees only StepOutcome. Commit.

**d5 — Registry durable proof (the real CDE.3-d gate).** Run registry through the real spine:
fresh registry prepare=1 commonExecution=1 handler=1; replay reuse registry all 0; divergence registry
all 0; typed-invalid registry prepare=1 commonExecution=0 handler=0; missing capability handler=0
fail-closed.

### 12.3 Mandatory invariants (d1–d5)

- one single durable spine;
- `CommonExecutionBoundary` remains the authority for effective execution;
- no `when(stepName)`; no `core.echo` special-case;
- no handler before capability admission;
- no omnipotent `CanonicalRuntimeContext` handed to the plugin;
- no repeated decode inside the handler path;
- no `Any` as a durable contract;
- no journal / fingerprint / cursor changes;
- legacy stays green.

### 12.4 EVENT_SINK_CAPABILITY is a real capability, not global access

`echo` requiring an output/event sink must stay DECLARED in its `StepContract` and SUPPLIED through
`StepCapabilityAccess`. Do not resolve it by turning events into global access. This is the proof the
capability model is real, not documentary.

### 12.5 Final CDE.3-d gate

DONE when registry traverses StructuralInvocation -> Registry prepare -> Ready(PreparedRegistryExecution)
-> CommonExecutionBoundary -> capability admission -> typed handler -> output normalization ->
StepOutcome, protected by frozen characterization, durable/replay suite, architecture fitness, registry
focused tests, fresh XML and a clean tree. Then open CDE.3-e only if residual fitness/output/capability
contract work remains.

### 12.6 Progress record (committed) — frontier clean

- d1 `132f4e68` — capability bridge. `EVENT_SINK_CAPABILITY` relocated to neutral `application/Capabilities.kt`;
  `CanonicalRuntimeCapabilityAccess` (durable) exposes ONLY EVENT_SINK from `CanonicalRuntimeContext.eventSink`,
  fail-closed get. Gate: CanonicalRuntimeCapabilityAccessTest 4/0/0, CoreEchoSeamTest 6/0/0, seam 20/0/0,
  architecture 165/0/0.
- d2 `69bc1ff0` — `PreparedExecution` sealed over the two TEMPORAL structural strategy families
  (PreparedLegacyExecution | PreparedRegistryExecution), NOT over plugins; `SeamedExecutionRouter.route`
  selects by strategy family (exhaustive `when`, no stepKey, no concrete-step special-case).
  LegacyExecutionAdapterTest non-legacy case now uses a real PreparedRegistryExecution. Gate: router 2,
  adapter 2, dual 5, characterization 8, registry prepare 5, arch 165/0/0.
- d3 `507c76c1` — `RegistryExecutionBoundary.adapt()` executes a PreparedRegistryExecution's typed handler
  WITHOUT re-decoding, after a hard capability re-check against the runtime access; handler gets a narrow
  StepHandlerContext (runtime identity + declared capabilities), never a CanonicalRuntimeContext. Gate:
  RegistryExecutionBoundaryTest 6/0/0, seam 38/0/0, arch 165/0/0.
- d4 `d4bd984c` — grounded output normalization: StepOutcome carries no generic output slot and the journal
  schema is unchanged, so the handler's typed O is never stuffed into StepOutcome and never crosses to the
  coordinator (no Any as durable contract); durable-observable output is an effect emitted via declared
  capabilities (echo). Gate: RegistryExecutionOutcomeTest 4/0/0, arch 165/0/0.

All seam tests green (characterization 8, dual 5, adapter 2, router 2, registry prepare 5, registry execute
6, outcome 4, capability bridge 4, echo seam 6) and architecture fitness 165/0/0 fresh. CDE.3-b/c laws
unchanged.

### 12.7 d5 starting point (next slice — CDE.3-d gate) + d5a grounding finding

**d5a grounding (evidence-based, coordinator read):** `CanonicalDurableRunCoordinator.dispatch` is
hardwired to the canonical-core world. It resolves `stepMetadataResolver.resolve(step.pluginStepId)`
(default `CoreLegacyStepMetadataResolver` = `CanonicalCoreStepMetadata.metadata(key)`, which fails fast
on a non-core key), fingerprints via `StepMetadata.replayPolicy`, and prepares through
`LegacyExecutionBoundary.prepare(step)` (`CanonicalCoreStepDecoder`). `StepMetadataResolver.kt` doc
states explicitly that the registry/definition metadata composite (migrated definitions + legacy table)
"belongs to CDE.3/CDE.5". So a registry step today cannot even reach the coordinator Execute branch
without: (a) a registry/definition `StepMetadataResolver` composite, (b) a registry step present as a
`StepNode` in the compiled pipeline (`StepNode.payload.encoded` + `pluginStepId`), and (c) the
prepare-selector by structural step key. The d1–d4 registry EXECUTE seams (capability bridge, sealed
families, router, registry prepare/execute, output normalization) are complete and ready; the durable
spine proof is gated by the metadata composite + registry-node representation, which the roadmap places
nearer core.echo-by-Registry.

Remaining spine work when the composite is available: wire `CanonicalDurableRunCoordinator` to (a) the
CDE.3-c slice-2 prepare selector by structural step key (registry when the key resolves in a
`StepRegistry`, else legacy), feeding `RegistryExecutionPreparation.prepare` the runtime's available
capabilities, and (b) route Ready prepared executions through
`SeamedExecutionRouter.route(legacyAdapter, RegistryExecutionBoundary.adapt())`; then demonstrate through
the REAL spine: fresh registry prepare=1 common=1 handler=1; replay reuse registry all 0; divergence
registry all 0; typed-invalid registry prepare=1 common=0 handler=0; missing capability handler=0
fail-closed. Cross-cutting coordinator change: run L4 module suites + architecture fitness + durable/
replay as the gate. Open CDE.3-e only if residual fitness/output/capability contract work remains.

**d5b progress (committed):** `RegistryStepMetadataResolver.composite(registry)` (`d7d2972e`) resolves
durable StepMetadata from a registered definition's descriptor for non-core keys, delegates core keys to
the legacy core authority unchanged, and fails hard on a key neither core nor registered. Test 3/0/0,
architecture 165/0/0.

**d5c feasibility finding (evidence, CanonicalInvocation.kt read):** `CanonicalStructuralPreparation.prepare`
does NOT restrict to core keys; it accepts any `StepNode` whose payload schemaVersion is `dsl-v1` and
whose encoded payload parses as a JSON object. So an `OpaqueStepNode(pluginStepId=<registry key>,
payload=dsl-v1)` passes the structural gate and can reach the Execute branch once the coordinator is
wired to (a) the composite metadata resolver, (b) a registry-aware prepare selector, and (c) the
SeamedExecutionRouter. No structural-preparation change is needed. Remaining is the coordinator wiring +
durable characterization, which is the next slice.
