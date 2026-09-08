# Design: LFC-2 reconstitution — step constitution, plugin seam and certification

Phase: design. No code. Anchored in canonical ground truth at HEAD (`5af901c7` area) and the
reference package (input only). This design is reconciled to canonical authority (see
RECONCILIATION_MATRIX.md + ADR_DISPOSITION.md).

## Target architecture (criterion 6/7)

Closed world for execution structure, open world for Steps/plugins. Core and external plugins run
the exact same path (no privileged core, no separate generic plugin).

```text
typed DSL façade
  -> canonical Invoke  (ExecutionNode: sealed, closed)
  -> StepRegistry      (open: StepKey -> StepDefinition -> StepHandler)
  -> erased typed adapter
  -> StepHandler<Input,Output>
  -> declared capabilities / context bridge (capability admission)
  -> durable engine (journal / events / replay / cancellation)
  -> typed StepResult + typed domain events
```

Conceptual shapes (names may vary; invariants do not):

```kotlin
@JvmInline value class StepKey(val value: String)

sealed interface ExecutionNode {           // CLOSED structure the interpreter exhaustively matches
  data class Invoke(id: StepId, step: StepKey, input: EncodedInput,
                    bodies: StepBodies, source: SourceLocation?) : ExecutionNode
}
sealed interface StepBodies {              // CLOSED
  data object None; data class Single(nodes); data class Named(branches)
}

interface StepDefinition<I:Any,O:Any> {    // OPEN registry payload
  val key: StepKey; val contract: StepContract
  val inputCodec: StepCodec<I>; val outputCodec: StepCodec<O>
  val handler: StepHandler<I,O>
}
```

Invariants:
- The interpreter exhaustively matches a finite set of structural shapes, never plugin Step classes.
- Unknown/incompatible `StepKey`, schema mismatch and body-shape mismatch fail closed before effects.
- A Step's declared capabilities are exactly the capabilities it may use (no global/omnipotent context).
- No central per-Step switch; no KSP `when(stepName)`; no `Map<String,Any?>` public Step contract.

Why reconcile with ADR-0069: the accepted fail-closed invariant is preserved; its enforcement
becomes registry-driven admission rather than a closed concrete registry.

## Certification states (criterion 13)

A Step is only done when **CERTIFIED**. States:
`DESIGNED → IMPLEMENTED_UNCERTIFIED → CERTIFIED` with orthogonal `QUARANTINED` / `RETIRED`.
`DONE/PASS` is never recorded for an uncertified Step. Common certification dimensions (core and
external plugins identical): contract, input/output codec, positive DSL, negative DSL, canonical IR,
registry, capability admission, handler success, typed failure, observability, cancellation, replay,
body contract, credentials/security, real distribution, Jenkins compatibility, executable scenario.

## Harness fidelity HF0..HF6 (criterion 3/12)

Renames the package `T0..T6` to **HF** (Harness Fidelity) to avoid colliding with the canonical LFC-2
item list `T0..T4` (which is NOT renamed).

| HF | Name | Boundary | Canonical anchor | Typical purpose |
|----|------|----------|------------------|-----------------|
| HF0 | Pure Contract | no process | — | ADTs, codecs, validation |
| HF1 | In-Process | in-process | `openspec/specs/pipeline-test-rule` | DSL/IR/handler integration |
| HF2 | Forked Real Distribution | forked distribution | CLI/distribution | CLI, classpath, plugin loading |
| HF3 | Restart/Resume | kill/restart | durable journal/resume (ADR-0040/0029) | journal, replay, resume |
| HF4 | Rootless Sandbox | Podman/hardened | ADR-0048 | isolation / security |
| HF5 | Service Sandbox | HF4 + services | ADR-0048/0053 | Git/HTTP/DB/artifacts isolated |
| HF6 | Online Smoke | OSS / network | ADR-0053 | ecosystem compatibility |

Rule: use the minimum faithful level that proves the property. Deterministic substitution (clock,
IDs, randomness, failure injection) allowed at HF0/HF1 when real time is not the object. Isolation
and teardown hygiene: each run gets unique workspace/root/stores/credential store/plugin dir/run ID/
process group; teardown kills descendants and preserves diagnostics before cleanup.

`pipeline-testkit` gains: `PipelineExtension` (HF1), `RealPipelineExtension` (HF2),
`PipelineSessionExtension` (HF3), `SandboxPipelineExtension` (HF4/HF5), plus `StepContractSuite` /
`PluginContractSuite` and workspace/process/git/credentials/plugin fixtures and failure injection.

## Executable scenarios (criterion 10/11)

The documented/shown `.pipeline.kts` IS the file the harness runs. Four inventories share
ScenarioRunner but keep distinct responsibilities:
- `examples/` — pedagogical / executable product specs (positive + negative).
- `v2/compatibility/` — Jenkins/DSL corpus (positive + negative grammar).
- `test-fixtures/` — internal adversarial scenarios.
- `plugin-fixtures/` — real external plugin builds.

Corpus additions: positive, negative DSL, step combinations, real retry/timeout/parallel,
credentials, filesystem/workspace, durability/restart, external plugins, security/adversarial.

## BodyInvoker / BranchInvoker (criterion 15)

A block Step never executes child handlers directly and there is no per-block dispatcher collection
(`dispatchRetryBlock`, `dispatchTimeoutBlock`, ...). The engine injects capabilities; each child
re-enters the same Invoke → Registry → capability admission → handler → journal/events path.

```kotlin
interface BodyInvoker {
  suspend fun invoke(body: BodyRef, patch: ExecutionContextPatch = None): BodyOutcome
}
interface BranchInvoker {
  suspend fun invokeAll(branches: List<NamedBodyRef>, policy: JoinPolicy,
                        patch: ExecutionContextPatch = None): ParallelOutcome
}
```

Mapping: `dir`→workspace patch; `withEnv`→environment patch; `withCredentials`→credential lease
patch; `retry`→N invocations with AttemptId; `timeout`→deadline/cancellation scope; `catchError`→
interpret typed `BodyOutcome`; `parallel`→Named Bodies + `BranchInvoker`.

This design **revises E-EM-11 D1/D2**, which currently propose one-off `dispatchRetryBlock` /
`dispatchTimeoutBlock` in the coordinator: those become routes into the shared body machinery, not a
new exception collection. D3 (parallel fork) is resolved by criterion 16: composable Named Bodies
reusing the durable branch machinery, not a permanent stage-terminal special case.

## Composable parallel (criterion 16)

`parallel` is a composable Step expressed as Named Bodies among siblings, reusing the existing
durable infrastructure (branch-indexed rows on `OpId`, parallel-frame executor where applicable)
rather than a whole-stage-only terminal. Whether it may appear with serial siblings before/after
depends on the grammar; the canonical shape is `StepBodies.Named` + `BranchInvoker`, composable.

## Execution order and dependency graph (criterion 9)

```text
Step Constitution  (ADR-0070 core: sealed ExecutionNode + open StepRegistry + StepDefinition/StepContract)
      ↓
ScenarioRunner (executable scenario runner for examples/compatibility/fixtures; ADR-0071)
      ↓
PipelineExtension (HF1 in-process; openspec pipeline-test-rule becomes HF1 + StepContractSuite)
      ↓
generic Step seam (canonical Invoke -> Registry -> typed adapter -> handler)
      ↓
migrate echo + sh onto the seam (delete concrete dispatcher cases)
      ↓
RealPipelineExtension (HF2 forked real distribution)
      ↓
external reference plugin proof  <-- extensibility gate: no core edit
      ↓
Step certification (CERTIFIED via StepContractSuite; ADR-0074)
      ↓
strict DSL (DslMarker/scopes/smart constructors/fake-return closure/source fidelity)
      ↓
BodyInvoker / BranchInvoker (ADR-0073)
      ↓
dir / withEnv / timestamps  (context-only blocks first)
      ↓
retry / timeout  (over BodyInvoker; real semantics steered toward E-EM-11 D1/D2 revision)
      ↓
composable parallel  (Named Bodies + BranchInvoker; E-EM-11 D3 = composable)
      ↓
durable scripted runtime values  (real pwd/isUnix/when/post; no fake returns)
      ↓
typed when/post conditions
      ↓
formal @KotlinScript + source fidelity
      ↓
LFC-2 GATE  = lfc-2-honest-dsl-closure green (no disabled obligations) AND this extensibility
             proof CERTIFIED
```

Dependency note: `echo`+`sh` form the minimum vertical slice; the external plugin proof must come
immediately after, BEFORE migrating many Steps, or extensibility can be faked again.

## Re-analysis triggers (escalate to spike/ADR, do not silently proceed)

1. external plugin needs a core edit; 2. a handler needs a global service not declared as a
capability; 3. a block handler needs a direct child handler; 4. IR needs a Step-specific structural
node; 5. KSP needs known-name semantics; 6. a golden test passes only by hiding semantic fields;
7. retry/timeout/parallel needs to bypass journal/events; 8. the TestKit needs production internals
that are not public.
