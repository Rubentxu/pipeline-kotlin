---
type: spike
id: SPIKE-018
title: "Architecture spike for core.load — script compilation + child-pipeline boundaries"
status: passed
date: 2026-09-12
worktree: pipeline-load-spike (branch cycle/lfc2-e1-load-spike @ 53b8fca0)
related:
  - ADR-0070 (closed structure, open step registry)
  - ADR-0073 (block steps re-enter through BodyInvoker)
  - ADR-0075 (retry control durable reconciliation)
  - ADR-0081 (BodyInvoker continuation model, cycle/lfc2-e1-bodyinvoker)
  - docs/v2/08-spikes/WAITUNTIL_BODY_INVOKER_SPIKE_FOLLOWUP.md
---

# SPIKE-018 — LOAD architecture spike

**Question.** What does a typed, certified `core.load` need beyond the standard
G0..G8 burn-down, and does it discover a NEW horizontal boundary that should be
built generically first?

**Method.** Read-only investigation at worktree `pipeline-load-spike`, base
`53b8fca0`. Every claim below cites a real path/line at that SHA (or a sibling
branch where explicitly marked).

---

## 1. Current legacy load surface (evidence)

`core.load` is in `LEGACY_PLUGIN_IDS`
(`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt:110`)
and classified `LEGACY_IMPLEMENTED_UNCERTIFIED` in
`docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md:86`.

### 1.1 DSL form

- `StepSpec.Load(path: String)`
  `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:629-634`.
  The KDoc (lines 614-627) claims "compiled via Kotlin24ScriptingHost and its
  steps are appended to the current execution scope" — **the code does not do
  this** (see 1.4).
- DSL function `load(path)` appends the spec
  (`PipelineDsl.kt:1636-1638`).
- Block step flattener treats Load as terminal, no nested steps
  (`v2/pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/api/BlockStepFlattener.kt:193-197`).

### 1.2 Compiler lowering — LATENT CONTRACT DEFECT

`DslCompiledPipelineCompiler.stepNode` has **no `StepSpec.Load` case**
(`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt:140-237`),
so Load falls into the generic `else` (lines 230-236):
`PluginStepId("core.load")` + `encodePayload(step)`.

`encodePayload` (lines 629-675) also has **no Load case**, so it hits
`else -> put("declarativeValue", step.toString())` (line 671). The emitted
payload is `{"kind":"load","declarativeValue":"Load(path=...)"}` — **the `path`
field is never encoded**.

The decoder, however, *requires* `path`:
`CanonicalCoreStepDecoder.kt:259-265`
(`payload.requiredString("path")`, throwing helper at lines 302-305).
**Consequence:** any `.pipeline.kts` containing `load(...)` compiled through
the canonical path produces a node that fails closed at decode with
`IllegalArgumentException: dsl-v1 payload requires string 'path'`. The load
family has no green production execution path today.

### 1.3 Metadata row

`"core.load" to StepMetadata(setOf(Effect.EXECUTES_SUBPROCESS), ReplayPolicy.MEMOIZED)`
(`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt:25`).

Flag: `Effect.EXECUTES_SUBPROCESS` (`v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/Effect.kt:15`,
"spawns an external process") is the wrong classification for an in-process
script evaluation; the descriptor-level fix belongs to the typed Step, not the
row.

### 1.4 Legacy dispatcher — a stub, not an executor

`CanonicalLoadNodeDispatcher.dispatch`
(`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalLoadNodeDispatcher.kt:44-117`):

1. Resolves the stage workspace via `WorkspaceResolver` (line 50-51) and
   normalizes the path, rejecting workspace escape (lines 54-64).
2. Reads the file bytes and computes SHA-256 (lines 67-78).
3. Re-entrancy check against a `loadedFingerprints` set of `"$path:$sha256"`
   (lines 81-99) — **but the set is constructed fresh per dispatch**:
   `CanonicalNodeDispatcher.loadContext()` passes
   `loadedFingerprints = mutableSetOf(), // Per-run fingerprint cache`
   (`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt:70-78`).
   The comment says per-run; the code is per-invocation. The documented
   Jenkins re-entrancy (`PipelineDsl.kt:624-625`) therefore never fires across
   steps.
4. Emits `WorkflowLoaded` with **`stepCount = 0`** (lines 84-96, 104-114) and
   returns `StepOutcome.Success` (line 116).

The comment at lines 38-40 admits: "The actual compilation and step execution
is deferred to the coordinator level" — but the coordinator has **no load
handling at all** (grep for load/Load in
`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt`
returns only unrelated matches; no `CanonicalLoadDispatchContext` constructor
exists outside `CanonicalNodeDispatcher.kt`, verified by grep).

**Net: the canonical durable path for load is a silent no-op.** The file is
read, hashed, an event with `stepCount = 0` is emitted, and nothing from the
loaded file executes. This violates the fail-closed coverage law (AGENTS.md
STEP SEMANTICS §3: a step without real support must be rejected, never
silently converted to a no-op).

### 1.5 No in-process evaluation anywhere

The KDoc's "compiled via Kotlin24ScriptingHost" claim is not implemented on
any path: `Kotlin24ScriptingHost` is constructed only in
`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt`
and its own module (`v2/pipeline-scripting-kotlin24/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/Kotlin24ScriptingHost.kt`);
no production code compiles a `load`-referenced file. The V1 `core/` module
has no load step executor either (grep for `fun load(`, `"load"` step keys in
`core/src/main/kotlin` yields only plugin-security and library-loading
matches).

---

## 2. Capabilities a typed load Step needs

### 2.1 Script source loading (workspace read)

The handler must resolve `path` against the current stage workspace and read
bytes. Today's legacy code does raw `java.io.File` I/O inside the dispatcher
(`CanonicalLoadNodeDispatcher.kt:68`), which a handler must not do. The
existing `WORKSPACE_OPERATIONS_CAPABILITY`
(`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Capabilities.kt:28`)
already covers stage-workspace file operations and `WorkspaceIdentity`
(Capabilities.kt:85-89) covers path resolution — script source loading can
reuse this seam rather than inventing a second one. Workspace-containment
policy (reject path escape) stays a Step-side typed decision fed by the
capability's resolved root, mirroring the S2-A6 pwd precedent
(Capabilities.kt comment block at lines 66-89).

### 2.2 Script compilation — a genuinely NEW horizontal boundary

There is today **no capability** exposing script compilation to handlers. The
port exists (`ScriptingHost.compile(ScriptDefinition): ScriptCompilationResult`,
`v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptingHost.kt:7-15`;
sealed `ScriptCompilationResult` Success/Failure at
`ScriptCompilationResult.kt:10-39`) and is hexagonally correct — a port in
`pipeline-scripting-api`, adapter `pipeline-scripting-kotlin24` — but nothing
bridges it into the capability admission system
(`CanonicalRuntimeCapabilityAccess` supplies eventSink/workspace/stage/
platform/temporary-workspace/delete-dir/milestone today; see
Capabilities.kt:15-227).

Proposed generic boundary:

```text
SCRIPT_COMPILATION_CAPABILITY: StepCapability("scriptCompilation")
  value: narrow port, NOT ScriptingHost itself — e.g.

  interface ScriptCompilationOperations {
      fun compile(request: ScriptCompileRequest): ScriptCompilationOutcome
  }

  sealed interface ScriptCompilationOutcome {
      data class Compiled(val artifacts: CompiledScriptArtifacts) : ScriptCompilationOutcome
      data class Rejected(val diagnostics: List<ScriptingDiagnostic>) : ScriptCompilationOutcome
  }
```

Hexagonal placement: the interface lives **inner** (pipeline-domain or the
application capability layer alongside `Capabilities.kt`); the adapter wraps
the existing `ScriptingHost` (Kotlin24) and is wired by the capability bridge
(`CanonicalRuntimeCapabilityAccess.buildProvided`), exactly like
`SHELL_OPERATIONS_CAPABILITY`/`ShOperationsAdapter`. The handler declares the
capability in `StepContract.requiredCapabilities`; admission stays fail-closed
(AGENTS.md capability-routed handler discipline).

Why a new capability and not reuse: compilation is an effect class no existing
capability models (classloader/evaluation is security-sensitive — note V1's
DSL validator restricts `Class.forName`/`URLClassLoader`,
`core/src/main/kotlin/dev/rubentxu/pipeline/dsl/validation/DslValidator.kt:157-158`),
and an external plugin could never declare "reach the scripting host" without
it. This boundary is generic: `load` is its first consumer, but scripted
library steps and ADR-0081's scripted-entry-point flows
(`ScriptEvaluationOutput.CompiledEntryPoint`,
`ScriptCompilationResult.kt:52-55`) are future consumers.

### 2.3 Child execution — BodyRef re-entry, NOT a nested run

The loaded file is a `.pipeline.kts` whose steps must execute **in the same
run context** (Jenkins semantics: same build, same env, same durable run).
Three options compared:

| Option | Verdict | Reason |
| --- | --- | --- |
| (a) Nested child run (own journal/runId) | REJECT | Creates a second durability authority; contradicts one-execution-path law; child run would need its own workspace/retry/replay model with no cross-run join semantics. |
| (b) Compile-time inlining of the loaded file | REJECT | `path` is a runtime value (AGENTS.md DSL vs runtime: construction must not perform I/O); the file may not exist at compile time and may change between attempts — inlining breaks replay identity. |
| (c) BodyRef-style child execution through `BODY_INVOKER_CAPABILITY` | ACCEPT | The loaded file's compiled step sequence becomes a synthetic body re-entering the SAME dispatch spine via `BodyInvoker.invoke(BodyRef, BodyInvocationContext): BodyOutcome`, getting per-child journal rows, per-attempt control rows, and the closed `BodyOutcome` algebra. |

Option (c) is no longer hypothetical: ADR-0081 and the inner contract landed
on sibling branch `cycle/lfc2-e1-bodyinvoker` (commit `e67307e4`,
`v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyInvoker.kt`:
`BodyRef` value class with engine-owned deterministic `bodyPath` encoding,
`BodyRefs.childBody/branchBody/namedBody` factories lines 42-56,
`fun interface BodyInvoker { suspend fun invoke(body: BodyRef, context: BodyInvocationContext): BodyOutcome }`
lines 157-159, `BODY_INVOKER_CAPABILITY = StepCapability("bodyInvoker")`
line 171). The durable precedent for re-entry into the same spine already
exists at stage level: `runParallelStage` re-dispatches branches "by the SAME
dispatch machinery" with branch-indexed OpIds and independent durable rows
(`CanonicalDurableRunCoordinator.kt:1012-1109`), and `dispatchBody` is the
generic block-body path (`CanonicalDurableRunCoordinator.kt:1327`).

Load-specific shape under (c): the `core.load` handler (atomic at the
`OpaqueStepNode` level, like waitUntil per `CoreWaitUntilStep.kt:77-78`
precedent) compiles the loaded file through 2.2's capability, obtains a
deterministic child-body identity, and invokes it. `BodyRef` deliberately
carries identity only, "runtime content never serializes and never rides in a
ref" (BodyInvoker.kt KDoc lines 19-24) — the compiled child steps are
engine-owned content behind the capability, bound at dispatch time, which is
exactly the ADR-0081 D2 separation.

---

## 3. Security / replay implications

### 3.1 Loaded-file identity must be IN the payload

Today the sha256 is computed inside the dispatcher and used only for an
in-memory, per-dispatch cache (§1.4). It never reaches the durable payload.
For replay safety the **decoded Input must carry `(path, expectedSha256)`** so
the canonical fingerprint (`Fingerprint.compute` hashes `stepId + params +
runId + attempt + replayPolicy`,
`v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/Fingerprint.kt:48-105`)
captures file identity per attempt. Consequences:

- Two invocations of the same path+content within a run are fingerprint-equal
  → `ReplayPolicy.MEMOIZED` reuse gives the Jenkins re-entrancy guarantee as a
  **durable** property instead of a mutable set (fixing the §1.4 cache bug by
  construction; the same MEMOIZED+WRITES_WORKSPACE-RERUN idempotence debate
  was settled for deleteDir at commit `162d8cc5`).
- A restart-mid-load after the file changed on disk produces a different
  fingerprint → divergence. The engine already has the right answer:
  fail-closed divergence, as `ParallelDecision.RejectDivergence` does
  (`CanonicalDurableRunCoordinator.kt:1095-1102`). Silent re-execution of new
  content must never happen.
- `ReplayPolicy.NEVER` is NOT the right policy here (REPLAY-POLICY law,
  AGENTS.md: NEVER means "existing durable history → ABORT"); load wants
  MEMOIZED with content-identity in the fingerprint.

### 3.2 Workspace containment and source trust

The legacy containment check (normalize + `startsWith(stageWorkspace)`,
`CanonicalLoadNodeDispatcher.kt:54-64`) is correct policy and must be
preserved as a typed input validation (fail-closed `Rejected` outcome of the
load step itself, kind SCHEMA or a dedicated typed failure — not
INFRASTRUCTURE, per the test-law distinction in AGENTS.md REPLAY POLICY).

Compilation executes third-party script text with the engine's privileges.
Minimum typed contract: compile only from workspace-resolved paths (never
inline user text through this step), surface diagnostics as data
(`ScriptingDiagnostic` already exists), and inherit the V1 posture that
dynamic class loading is restricted. A fuller capability-sandbox decision is
out of scope for this spike; flag it as a security review item before
CERTIFIED.

### 3.3 Restart mid-load

Because child steps re-enter the same spine with per-child journal rows
(option c), restart-mid-load inherits exactly the retry/parallel recovery
model: completed children are reused from the journal, the in-flight child is
re-attempted under its control row (ADR-0075 pattern), and the load operation
row itself is the anchor that says "this body was entered." Without the
control-row anchor a second invocation would re-run the whole child sequence
— the precise RETRY-D failure mode (AGENTS.md RETRY-D section), so the load
handler MUST bind the retry/control journal path, not just emit events.

---

## 4. The boundary, stated minimally

```text
Inner ports (no adapter knowledge):
  ScriptCompilationOperations  (new; capability SCRIPT_COMPILATION_CAPABILITY)
  BodyInvoker                  (ADR-0081, already landed inner contract)

Adapters:
  Kotlin24 scripting host wrapped behind ScriptCompilationOperations
  capability bridge binds both at dispatch time (CanonicalRuntimeCapabilityAccess)

Handler (CoreLoadStep, registry path only):
  input  = LoadInput(path, expectedSha256)        — whole-payload codec
  descriptor effects = WRITES nothing; classify as READ_ONLY + WRITES_WORKSPACE-free;
                       in-process evaluation (fix the EXECUTES_SUBPROCESS row §1.3)
  replayPolicy = MEMOIZED
  behavior  = validate path/containment → read via workspace capability →
              sha256 → compile via script capability → invoke child body →
              typed LoadOutcome (Loaded(stepCount) / Reentrant / Rejected(diagnostics))
  events    = WorkflowLoaded with REAL stepCount (never the 0-stub)
```

Hexagonal check: both ports point inward; the Step knows neither the
scripting host nor the coordinator; the engine never learns what `load`
means — it reads the contract (AGENTS.md "polymorphic dispatcher" rule).

## 5. Verdict and sequencing

**Lift: LARGE — the largest of the remaining LEGACY_PLUGIN_IDS entries.**
Justification, in decreasing weight:

1. Two new horizontal boundaries (script compilation capability; child-body
   re-entry wiring in the capability bridge) — neither exists today in
   production wiring.
2. The current "legacy path" is a stub that silently succeeds while executing
   nothing (§1.4) plus a latent compiler/decoder contract defect (§1.2); G1 is
   not a transplant of characterized behavior (as in pwd/echo) but real
   behavior definition.
3. Durable child execution must integrate with retry control rows, fingerprint
   identity, and divergence — the full spine surface.

**Does it discover a generic boundary to build first? YES — two, and both
are already justified independently of load:**

| Boundary | First consumer | Status |
| --- | --- | --- |
| `BODY_INVOKER_CAPABILITY` inner contract (ADR-0081) | waitUntil (spike follow-up), then retry/timeout, then load | Landed on `cycle/lfc2-e1-bodyinvoker` (`e67307e4`); not yet in production wiring |
| `SCRIPT_COMPILATION_CAPABILITY` | load (first), scripted libraries/entry points (later) | This spike's proposal; nothing exists |

**Recommended sequencing:**

1. Finish waitUntil/retry through the BodyInvoker seam first (W1-W3 of the
   waitUntil follow-up). This productionizes `BODY_INVOKER_CAPABILITY` and
   validates child-body durability on a bounded, SMALL case — load should not
   be the first consumer of a brand-new execution seam.
2. Land `SCRIPT_COMPILATION_CAPABILITY` as its own G1-style slice (port +
   adapter + capability bridge + contract suite), independent of load, so it
   can be reviewed as infrastructure.
3. Only then run load's G0..G8. G0 must baseline the two latent defects found
   here (compiler `declarativeValue` payload missing `path`; per-dispatch
   re-entrancy cache; `stepCount = 0` silent no-op) so G1 fixes are
   traceable. Fixing the compile contract (§1.2) is legitimately part of
   load's G1, not a separate legacy repair.
4. load burn-down comes LAST among the remaining legacy entries — every other
   candidate (milestone, cleanWs, waitUntil, archiveArtifacts) is SMALL/MEDIUM
   against the pattern proven by echo/sh/pwd/deleteDir/isUnix.

## 6. Top risks

| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | Child execution of loaded steps creates a second durability model if done as nested-run or ad-hoc dispatch | Hard rule: re-enter ONLY via `BodyInvoker`/`BODY_INVOKER_CAPABILITY` (ADR-0081 D-law); fitness-scan for any load-specific dispatch path |
| 2 | Replay divergence on file mutation between attempts (restart-mid-load executes different content, or silently re-runs old rows) | `expectedSha256` in decoded Input → fingerprint; divergence → fail-closed `Rejected`-style terminal, mirroring `RejectDivergence` |
| 3 | The stub-and-defect status (§1.2/§1.4) means there is NO characterized behavior to migrate — G2's corpus is greenfield | G0 characterizes the DESIRED semantics from Jenkins reference baseline (`docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md`) + V1 behavior; declare the re-entrancy and containment policies explicitly in the contract suite |
