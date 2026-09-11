# LFC-2R / R4A — Production Wiring Architecture (scoping & decision)

Status: **COMPLETE (investigation + design; production semantic changes = 0)**
Scope: trace the real CLI path, falsify the three wiring models, answer the
10 questions, define the identity law. **No `Main` activation, no authority
flip, no legacy changes** (explicit user scope).

`SECOND_RUNNER = REJECTED`
`PRODUCTION_WIRING_MODEL = SCRIPTED_FRONTEND_CANONICAL_BACKEND`
(with coordinator-owned structural nodes for block Steps — see §Model falsification)

---

## 1. Trace of `Main.kt` today (durable mode; in-memory mode is identical modulo stores)

```text
pipeline run [--db] <script>
  Main.kt (~L495 durable / ~L355 in-memory)
  1. scriptContent = scriptPath.readText()
  2. definitionId  = DeterministicIdGenerator.definitionId(path, content)
  3. runId         = selectDurableRun(policy, RunIdDirectory, definitionId)
  4. host          = Kotlin24ScriptingHost(eventStore, runId)
  5. DslRuntimeConfigScope.set(SystemRuntimeConfig())   // ← eager runtime injection
  6. result        = host.compile(ScriptDefinition.file(...))
  7. pipelineSpec  = result.scriptInstance["get$$result"] as? PipelineSpec   // reflection
  8. compiledPipeline = DslCompiledPipelineCompiler.compile(spec, sourcePath, sourceContent, Digest("builtin"))
  9. registry composition (CoreStepRegistryFactory + ExternalStepPluginDiscovery)
 10. GATE: supportsCanonicalDurableExecution(compiledRegistry) — fail-closed exit 2 otherwise
 11. runCanonicalPipeline → CanonicalDurableRunCoordinator(
        dispatcher = CanonicalNodeDispatcher(), journal = SqliteOperationJournalImpl,
        cursorStore, eventSink, stepRegistry, retryControlJournal, controlDirRoot).run(pipeline, runId)
```

**Critical observation (the G2R gap, now with exact mechanism).** The real
production DSL (`PipelineDsl.kt`) is **eager**: `pipeline { stages { stage { … } } }`
runs the user's blocks synchronously at *construction* time, accumulating
`StepSpec` rows in mutable lists (`PipelineScope`/`StagesScope`/`StageScope`).
Inside that construction:

```kotlin
fun isUnix(): Boolean {
    steps.add(StepSpec.IsUnix())
    val osName = runtimeConfig.osName().lowercase()   // ← observed at BUILD time
    ...
}
```

So in the REAL `.pipeline.kts` today, `if (isUnix()) { sh("make") }` branches
**at script-construction time**: the resulting `PipelineSpec` topology is a
function of the host platform. On resume from a different host, Main re-runs
`host.compile` (step 6), the eager branch re-evaluates against the NEW
platform, and a **different graph** is compiled — precisely the hazard the
user flagged. This is the strongest single argument for the R4A model.

The scripted path (`ScriptedArtifactRuntime`, R1–R3) is NOT reachable from
Main today: it is exercised only by tests; `ScriptEvaluationOutput.CompiledEntryPoint`
is never produced by the production script recipe.

## 2. Model falsification

### C — SECOND RUNNER: **REJECTED** (evidence, not taste)

- `Main` already enforces single-spine: LF-0208 comment ("storage choice must
  not select a different execution algorithm") and the LEG-1 burn-down deleted
  the legacy `PipelineOrchestrator`; the non-canonical gate fails closed with
  exit 2. A `choose runner` branch in Main would (a) re-open the deleted seam,
  (b) create two journals for the same run id, (c) make `--resume` semantics
  divergent per branch. No evidence anywhere in docs/v2 supports revisiting
  that law. REJECT stands.

### A — HYBRID COMPOSITION (scripted runtime resolves expressions only): **insufficient alone**

Falsified as a *complete* answer: if the scripted runtime only resolves
*expressions* but the graph is still built eagerly by `PipelineScope`, then the
Boolean that steers Kotlin control flow is still evaluated during construction
— the topology problem of §1 is unsolved. Hybrid composition only works for
expressions that do NOT influence structure. It survives only as a subset of B
(see below).

### B — SCRIPTED FRONTEND / CANONICAL BACKEND: **SELECTED**

The compiled scripted entry point becomes the *execution frontend*: normal
Kotlin (coroutine) control flow runs inside `ScriptedRuntime`, and every
runtime-returned atomic Step invocation terminates in the SAME durable
authority the canonical path uses:

```text
ScriptedStepFacade.isUnix(callSite)          [already built, R2]
  → ScriptedRegistryInvoker.invoke           [journal.get → prepare → boundary → journal.append]
  → RegistryExecutionPreparation             [capability admission, fail-closed]
  → CommonExecutionBoundary → registry Step  [same registry instance]
  → OperationJournal                         [same journal port/table family]
```

Evidence this is composition, not a second engine:

- `ScriptedRegistryInvoker` already consumes the `OperationJournal` interface
  (`ScriptedRegistryInvoker.kt:93`) — the same port `Main` instantiates as
  `SqliteOperationJournalImpl` for the canonical path. Same authority
  abstraction, same SQLite backend family, same replay/cursor law.
- Capability admission and handler execution already flow through
  `RegistryExecutionPreparation` / `CommonExecutionBoundary` — the canonical
  spine's own seams, not parallel machinery.
- The façade is a thin adapter: identity + encoded input + declared codec
  (R2 receipt). It holds no replay logic and no durable state.

**Structural nodes stay coordinator-owned.** `stage`, `retry`, `timeout`,
`parallel`, `catchError` are Block Steps re-entering the engine via
`BodyInvoker`/`BranchInvoker` (ADR-0073, AGENTS.md §8). R4A does NOT move them
into the scripted frontend. The selected model is therefore precisely:

> the scripted entry point is the frontend for the *body* of a structural node;
> the structure itself (stages, blocks, retry control rows) remains
> `CanonicalDurableRunCoordinator` territory. One engine; the scripted layer is
> a body-execution mode of that engine.

This subsumes A (hybrid) as the structural-level statement and B as the
body-level statement — they are the same model at two granularities.

## 3. The 10 questions

**Q1. Where is PipelineSpec produced today?**
In `Main` steps 6–8: `host.compile` eagerly executes the DSL construction
blocks; `get$$result` reflection extracts the finished `PipelineSpec`;
`DslCompiledPipelineCompiler.compile` lowers it to `CompiledPipeline`.
Construction-time evaluation is the root property to change for
runtime-returned control flow.

**Q2. Can a runtime-returning call occur before PipelineSpec must be complete?**
Not in the eager path (construction IS evaluation). Under model B the question
dissolves: there is no "complete PipelineSpec before the Boolean" — the entry
point body IS the execution, and PipelineSpec's role (declarative structure)
is played by the coordinator's structural nodes. Where a script mixes both,
the lowering (R3) is the insertion point: it already rewrites `isUnix()`
call sites at compile time without needing the value.

**Q3. Can the scripted frontend emit/invoke Steps incrementally?**
Yes — that is its existing behavior. `ScriptedScope.invokeAt`/`isUnix` invoke
one operation at a time, each independently journaled (RUNNING → terminal) with
its own `operationId`. The journal already stores per-operation rows, not a
pre-declared plan; the durable spine supports incremental operation emission.
What R4B must add is a *structural replay law* for the coordinator (Q7/Q-identity),
not a new journal mechanism.

**Q4. Who assigns Step identity when control flow is dynamic?**
Today: `stableScriptedKey(runId, entryPointId, callSiteId, dynamicScopePath,
invocationOrdinal)` (`ScriptedRuntime.kt:38`, `ScriptedRegistryInvoker.kt:59`),
with the ordinal owned by `ScriptedScope.nextOrdinal`. Deterministic because:
callSiteId is source-derived (R3 lowering: `<src>:<line>:<col>:isUnix`), the
scope path is lexically derived, and the ordinal is the Nth dynamic invocation
at that site. On resume, the persisted value short-circuits the observation and
the SAME branch is re-taken (R2's reuse-with-changed-platform proof), so the
ordinal sequence replays identically regardless of host platform. Dynamic
control flow does NOT destabilize identity: the identity follows the *executed*
path, and the executed path is made deterministic by the durable value.

**Q5. Relation of scripted identity to canonical StepId?**
Canonical identity today: `pipeline / stageIndex / stepIndex / operation`
(`$controlDirRoot/$runId-$stageIndex-$stepIndex/` in Main). Scripted identity:
`runId / entryPointId / callSite / scopePath / ordinal`. These are two
derivations over the same run. The law R4A fixes:

```text
IDENTITY LAW (R4A-L1):
One run, one operation-identity namespace, one journal key space.
A runtime-returned Step executed through the scripted frontend is the SAME
durable operation whether reached eagerly or via the frontend. Its journal key
MUST be derived by ONE authority — the scripted deterministic derivation
(source + callSite + scope + ordinal) promoted INTO the canonical namespace as
the operation key for frontend-executed ops. No dual keys for one effect.
```

Concretely for R4B: `CanonicalDurableRunCoordinator` gains a body-execution
mode in which stage bodies run as compiled entry points whose operations use
the invoker's `operationId` scheme namespaced by the canonical run; the
coordinator's structural nodes keep `stage/step` addressing. Two addressing
schemes, ONE journal, no key collision (frontend keys are `entryPoint`-rooted,
structural keys are `stageIndex`-rooted) and no duplication of the same effect
under both schemes (an operation is executed by exactly one scheme — the one
that owns its body).

**Q6. Same journal?**
Same `OperationJournal` port, same production adapter family
(`SqliteOperationJournalImpl`). Today the scripted tests use
in-memory/file variants of the same interface; R4B must wire the invoker to
the SAME SQLite journal instance the coordinator receives from Main — a
composition-root change, not an engine change. Proven single authority once
wired: one `journal.get(operationId)` decides reuse for both worlds.

**Q7. stage()/retry()/timeout()/parallel()/catchError()?**
They remain coordinator-owned Block Steps (ADR-0073). Under model B, a stage
body that contains scripted control flow is executed as a frontend body;
`retry`/`timeout` wrapping a body is a durable control row / structural node
around that body (RETRY-D already proves durable control rows for child
bodies). `parallel` branches are Named Bodies — each branch body can be a
scripted entry-point fragment with its own scope path (lexical derivation
gives branch-distinct `dynamicScopePath`, so identities never collide). No
new block machinery is invented.

**Q8. Coordinator as authority vs scripted model as another form of it?**
The scripted layer becomes **another body-execution mode of the same
coordinator**, not a peer. The decisive precedent is RETRY-D: child bodies are
executed by the dispatch loop against durable control rows; a scripted body is
a child body whose atomic ops route through registry+journal. The coordinator
never `when`s on the body's flavor; the body is invoked, its ops are durable,
its completion is a typed result folded by the coordinator.

**Q9. Minimal Main surface?**
Bounded, mechanical (R4B):
1. At script evaluation, select `ScriptEvaluationOutput.CompiledEntryPoint`
   (already a closed ADT case) instead of / in addition to `get$$result`.
2. Pass the SAME journal/cursorStore/eventSink/registry already composed into
   `runCanonicalPipeline` to a `ScriptedRegistryInvoker` construction.
3. Coordinator runs structural nodes; runtime-returned stage bodies run via
   `ScriptedArtifactRuntime` with that invoker.
No new flags, no runner choice, no second `System.exit` path, no new stores.
Estimated diff surface: composition-root wiring only (~tens of lines), zero
new execution algorithms.

**Q10. Can real (unsimplified) fixture-shaped scripts compile conceptually?**
Yes, with one explicit prerequisite: the lowering (R3) must also apply inside
`stage { }` blocks of a `pipeline { }` script — i.e. `isUnix()` in a real
`.pipeline.kts` is rewritten to `steps.isUnix(ScriptedCallSiteId(...))` whose
`steps` receiver is the suspend `ScriptedStepFacade` of the enclosing body,
not the eager `StageScope`. The counterexample scripts then behave:

```kotlin
stage("build") {
    if (isUnix()) sh("make") else sh("build.cmd")
}
repeat(3) { if (isUnix()) echo("unix-$it") }
```

- fresh SunOS: call site A (1st) observes → true → `sh("make")` branch; call
  site B inside `repeat` gets ordinals 0,1,2 (`B@0,B@1,B@2`) → three `echo`
  ops with distinct identities.
- resume on Windows host: A is SUCCEEDED in journal → persisted `true` reused
  (0 platform reads) → Kotlin branch re-takes the unix arm → SAME `sh("make")`
  operation identity revisited → replay/reuse, never a `build.cmd` op. The
  Windows branch is NOT reconstructed. This is exactly the property the user
  named as R4's most important one:
  `durable runtime value → deterministic replay of Kotlin control flow → same
  subsequent operation identities`.

## 4. What R4B must still prove (gap list, not built here)

1. Lowering inside `pipeline { stages { stage { … } } }` (receiver plumbing:
   facade in scope of stage body) — the R3 lowering currently covers
   entry-point bodies; same PSI mechanism, new scope.
2. Coordinator body-mode: stage bodies dispatched as compiled entry points
   (composition seam in the dispatcher, no concrete-Step branching).
3. Same-journal wiring (Q6) + an integration test proving ONE journal row set
   for a mixed run.
4. Mixed-script handling: a script producing BOTH a PipelineSpec and entry
   point semantics — decision recorded here: the entry-point form is the
   future authority; eager `get$$result` remains untouched until the flip
   (G4), so R4B adds a selection seam, not a replacement.
5. Installed-distribution execution of real fixture13 (FRESH / --rerun /
   --resume).

## 5. D3 state after R4A

```text
D3:
  runtime solution            = true   (R1/R2)
  scripted consumer           = true   (R2)
  compiler mapping            = true   (R3)
  real source control flow    = true   (R3 + Q10 design)
  production wiring design    = true   (THIS receipt: model B selected, law R4A-L1)
  installed wiring            = false  (R4B)
```

## 6. Verification

No production code changed in R4A (`git diff` empty against `a2de545f` except
this document). Evidence basis: source reads of `Main.kt` (in-memory ~L355,
durable ~L495, `runCanonicalPipeline` ~L746), `PipelineDsl.kt`
(`stages`/`stage` eager scopes ~L946–984, eager `isUnix` ~L1589),
`CompiledScriptedEntryPoint.kt` (`RuntimeScriptedStepFacade`,
`ScriptedArtifactRuntime`), `ScriptedRuntime.kt` (`stableScriptedKey` ~L38),
`ScriptedRegistryInvoker.kt` (journal port, operationId ~L59, journal.get
~L132). No test runs required; no evidence invalidated from R1–R3.
