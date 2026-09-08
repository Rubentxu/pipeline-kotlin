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
