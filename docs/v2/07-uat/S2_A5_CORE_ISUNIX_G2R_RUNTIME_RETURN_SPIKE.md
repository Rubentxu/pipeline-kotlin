# S2-A5 / G2R — RUNTIME-RETURN SPIKE: `DSL_RUNTIME_RETURN_GAP`

**Gate:** G2R — research/scoping only. **Production semantic changes = 0** (test-harness probes and this receipt only). **STOP after this gate.**
**Base:** `3192303a` (G2).

## The two existing paths, traced

### PATH EAGER (the only production `.pipeline.kts` path today)

```text
.pipeline.kts
→ Kotlin24ScriptingHost.compile (Main.kt:355 extracts get$$result)
→ script evaluates NOW: PipelineDsl.isUnix() adds StepSpec.IsUnix AND
  returns runtimeConfig.osName() synchronously (PipelineDsl.kt:1590)
→ PipelineSpec (construction-time Boolean already captured in the Kotlin frame)
→ DslCompiledPipelineCompiler.compile → StepNode graph
→ CanonicalDurableRunCoordinator (dispatches core.isUnix later, at execution)
```

The Kotlin `val unix = isUnix()` value is decided during PipelineSpec CONSTRUCTION.
The durable step dispatched later has no channel back into that frame — by design of
eager evaluation. No cache/thread-local/lastOutput can repair this without breaking
typed-output architecture.

### PATH SCRIPTED (fully built, durable, suspend-based — exists in main)

```text
compiled Kotlin entry point (CompiledScriptedEntryPoint)
→ ScriptedArtifactRuntime.execute (runId, entryPoint)          [application/scripted]
→ ScriptedRuntime.run → ScriptedScope (per-scope ordinals map)
→ RuntimeScriptedStepFacade (ScriptedStepFacade)               [suspend; values materialize
  before control returns to Kotlin — documented on ScriptedScope]
→ ScriptedOperation (callSiteId, dynamicScopePath, invocationOrdinal)
→ ScriptedOperationRuntime = JournaledScriptedOperationRuntime
   → OperationInput(stepId="scripted.core.sh", params incl. callSiteId/scope/ordinal)
   → Fingerprint.compute(input, SCRIPTED_SHELL_STEP_ID, MEMOIZED, attempt)
   → journal.get(operationId): existing SUCCEEDED → decode persisted wire result,
     handler NOT called; RUNNING → reconciler; else execute + append SUCCEEDED w/ output
→ typed result returned to the suspended Kotlin continuation (no CPS, no continuation serialization)
```

Evidence both halves are green: `ScriptedScopeTest` 13/0,
`CompiledScriptedEntryPointHostTest` 1/0 (real kts host, fresh XML).

**Who picks which path:** the scripting host's evaluation output decides. A script whose
result value is a `PipelineSpec` → eager declarative path (Main). A script returning a
`CompiledScriptedEntryPoint` (object : CompiledScriptedEntryPoint { ... }) → scripted
runtime path. Same host, same `.pipeline.kts` file, two execution models. The scripted
model is NOT yet wired into `Main.kt`'s production run loop — it is feature-complete
infrastructure exercised by tests.

## The seam: both halves already exist

```text
REGISTRY SIDE (done, S2-A5 G1/G2):
  CoreIsUnixStep → IsUnixOutput → outputCodec.encode → CommonExecutionResult.encodedOutput
  → persisted as OperationOutput (CanonicalDurableRunCoordinator.kt:808, fresh + rerun)

SCRIPTED SIDE (done, pre-existing):
  suspend facade → journaled execute-or-reuse → typed decode → Kotlin value
  (JournaledScriptedOperationRuntime.toReplayResult decodes the persisted wire result
  WITHOUT calling the effect — exactly D2's resume law)
```

The gap is a **narrow adapter**, not a new mechanism: the scripted runtime's operation
model is shell-specific (`ScriptedOperation.command: ShellCommand`, wire = ShellInvocationResult,
`stepId = "scripted.core.sh"`), while the registry side speaks `StepKey + EncodedStepValue`.

## Options evaluated (no decision pre-made; blast radius compared)

**Option A — generalize ScriptedOperation to carry any registry Step.**
Make `command` a sealed `ScriptedPayload` (Shell | RegistryStep(key, encodedInput, resultType)) and
generalize the wire/result decode. Blast radius: ScriptedOperation, JournaledScriptedOperationRuntime
(wire codec), ScriptedScope/RuntimeScriptedStepFacade (new methods), ScriptedStepFacade API (+facadeSchemaDigest bump).
Cons: touches the shell-typed spine that `sh` depends on; a generalized wire format is a
durable-compatibility migration for existing journals. Cons: the facade is invoked generically by the
compiled entrypoint, so its own suspend pattern is NOT shell-specific.
**Chosen shape: SEAMED GENERALIZATION** — introduce a narrow new port
`ScriptedRegistryStepInvoker { suspend suspend fun invoke(stepKey, callSiteId, scopePath, ordinal,
encodedInput): EncodedStepValue-or-typed }` implemented in application by (1) composing durable identity
exactly like ScriptedOperation (callSiteId + dynamicScopePath + invocationOrdinal + runId),
(2) reusing `RegistryExecutionPreparation` + `CommonExecutionBoundary` for the effect, and (3)
journalling through the SAME `OperationJournal` with `stepId = "scripted." + stepKey` and the
encodedOutput as OperationOutput.result. `sh` stays untouched on its existing path.

**Option B — extend the declarative PipelineSpec model with runtime returns.**
Rejected: requires the eager PipelineDsl frame to receive future values. There is no mechanism short
of lazy/deferred types (a DSL-breaking change) or CPS (rejected). The Kotlin24 host already
demonstrates the better model: ordinary suspend Kotlin.

**Option C — brand-new runtime-return mechanism (caches, deferred handles, second evaluation).**
Rejected: all listed red flags (global result cache, thread-local, lastOutput, second script
evaluation, reflection, runBlocking bridge, controller-side RuntimeConfig). Violates typed-output
architecture and CTX-P explicit-context law.

## Call-site identity (reusable today)

`ScriptedCallSiteId` (source:line:col:step) + `ScriptedDynamicScopeId` stack +
per-scope `invocationOrdinal` (ScriptedScope.invokeAt) give collision-free durable
identity for `repeat(3) { if (isUnix()) ... }` and distinct `val a`/`val b` calls. The
identity feeds `OperationInput.params`/operationId — NOT the `StepNode` fingerprint —
so `os.name` still does not enter the fingerprint; divergence is detected by the
journal's own fingerprint-over-input comparison, consistent with the scripted `sh`
behavior today. A `shellCallSite()`-style `sourceLocation.stepCallSite("isUnix")` on
`ScriptedSourceLocation` extends the generator cleanly.

## D2 compatibility (fresh / rerun / resume) — holds by construction

```text
FRESH:  scripted registry invoker → prepare/admit → CoreIsUnixStep handler
        → PlatformIdentity observed → IsUnixOutput → encodedOutput journalled
        → decode → Boolean to Kotlin
RERUN:  new execution → re-observe → new Boolean
RESUME: journal SUCCEEDED hit → decode persisted encodedOutput → return to Kotlin;
        handler = 0, PlatformIdentity NOT touched
```

Resume value == decoded persisted IsUnixOutput, never a fresh observation — the same
law `JournaledScriptedOperationRuntime` already enforces for `sh`. No facade-side
`PlatformIdentity` fallback exists or may be added.

## Horizontal feature verdict

This is NOT an `isUnix` fix. It is the missing seam for **runtime-returning Steps** as
a horizontal LFC-2 capability, covering exactly the family the roadmap expects:
`sh(returnStdout)` (already shipped via the scripted path), `isUnix()`, `pwd()`,
`readFile()`, `fileExists()`. Each such Step needs only: typed Output + codec
(registry side, already the pattern) + one facade method + one call-site form.

## Rejected approaches (falsification attempts on H1)

- Making the eager `PipelineDsl.isUnix()` return a deferred/handle: DSL-breaking.
- Routing the declarative pipeline's `StepSpec.IsUnix` node result back into the Kotlin
  frame: no channel exists; construction already returned.
- Duplicating isUnix semantics inside the scripted facade (computing from
  runtimeConfig instead of executing `core.isUnix`): would create a THIRD authority
  and violate D1/one-classifier. The facade MUST go through `core.isUnix`.

## Decision

```text
DSL_RUNTIME_RETURN_GAP:
  RESOLUTION_PATH       = A-SEAMED: generic scripted-registry invoker reusing
                          ScriptedCallSite identity + RegistryExecutionPreparation/
                          CommonExecutionBoundary + OperationJournal; ScriptedStepFacade
                          gains suspend isUnix(): Boolean (and siblings later)
  IMPLEMENTATION_SCOPE  = known (one new port + application adapter + facade method +
                          generator call-site form + kts host tests; `sh` path untouched)
  G3_BLOCKED            = false   (G3 registry-primary flip is orthogonal and safe;
                          the DSL reconnection is a horizontal feature gate, not a
                          core.isUnix burn-down gate)
```

Recommended sequencing: land G3..G5 for `core.isUnix` on the existing burn-down rails;
implement the scripted-registry invoker as a separate horizontal slice (LFC-2 feature,
with its own StepContractSuite-style proof: fresh/rerun/resume value identity, no
re-observation on resume, call-site collision tests), then reconnect `PipelineDsl.isUnix`
compatibility surface to it (D3 closure) and only then record S2-A5 CERTIFIED with the
D3 contradiction resolved-or-bounded.

## Invariants (unchanged, probed not modified)

counters 8/8/8; family LegacyCore; PipelineDsl.isUnix eager behavior byte-identical;
CoreIsUnixStep/UnixPlatformClassifier/PlatformIdentity untouched.

**G2R COMPLETE. STOP.**
