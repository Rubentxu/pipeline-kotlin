# ADR-0082 — Structured DSL Runtime Return: suspend structured DSL (design spike LFC-2R2)

Status: PROPOSED (design spike LFC-2R2 — resolves blocker `STRUCTURED_DSL_RUNTIME_RETURN_GAP`)
Date: 2026-09-12
Context:
- `docs/v2/07-uat/S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md` (blocker frozen)
- ADR-0006 (durable replay INSTEAD OF CPS — CPS was already rejected once, 2024)
- ADR-0065 (D1: declarative discovery vs scripted execution; D2: durability is deterministic replay, NOT Kotlin continuation persistence)
- SPIKE-016 (passed: suspend scripted bodies replay durably without serializing continuations)
- `docs/v2/07-uat/LFC2R_R2_ISUNIX_SCRIPTED_RUNTIME_CONSUMER.md` (generator-level suspend runtime-return proven end-to-end)

## 1. Problem

A Step executed by the durable runtime cannot return its typed Output `O` into the Kotlin
frame that built a `PipelineSpec` through the **eager structured frontend**
(`pipeline { stages { stage { steps { ... } } } }`). Today:

- `PipelineDsl.pwd()` returns the synchronous placeholder `runtimeConfig.userDir()`
  (S2-A6 G3R comment), not a durable runtime value.
- `PipelineDsl.sh(returnStdout = true)` adds `StepSpec.Shell` to a list and returns `Unit`;
  the caller cannot observe stdout.
- `readFile` / `fileExists` are documented as "no return value" in the DSL.

The runtime-return seam shipped in LFC-2R (R1–R4B) covers only **generator-level scripted
sources** (`ScriptedStepFacade.isUnix(callSite)`, suspend, journal-reused, codec-decoded)
and deliberately excludes `pipeline { ... }` bodies (R4B GO note in `Main.kt`).

**The law this design must satisfy:** ONE generic mechanism that returns the typed `O` of
any `StepDefinition<I, O>` to a Kotlin frame that keeps executing. NO Step-specific
compiler hacks, no per-Step frontend cases, no change to the durable spine's authority
model (fingerprint, journal, replay remain spine-level).

Consumers of proof: `pwd(): String`, `fileExists(): Boolean`, `readFile(): String`,
`sh(returnStdout = true): String`.

## 2. Evaluation dimensions (applied identically to all three designs)

| Dimension | Question |
|---|---|
| Durable spine | Does fingerprint / journal / replay semantics stay spine-level and Step-agnostic? |
| Scripting host | Can the Kotlin scripting host compile the body form without a compiler plugin? |
| Type safety | Is `O` delivered with its declared type, zero `Any?` / stringly leakage? |
| No-hack law | Is the mechanism generic over `StepDefinition<I, O>` (open-world)? |
| Migration cost | How much DSL / compiler / coordinator surface changes? |
| Authoring model | Does it preserve Jenkins-familiar step semantics (AGENTS.md STEP SEMANTICS)? |

## 3. Design A — CPS / continuation transformation of the script body

### 3.1 Shape

A frontend source-to-source transform rewrites the body of `pipeline { }` into
continuation-passing style: every runtime-returning call receives a continuation
callback; the transform threads the rest of the body through it.

```kotlin
// USER WRITES (.pipeline.kts)                          // LOWERED (conceptual)
pipeline {                                              cpsPipeline {
    stages {                                                stages {
        stage("deploy") {                                       stage("deploy") {
            steps {                                                 steps {
                val dir = pwd()                     =>                  pwd(k = { dir ->         // injected continuation
                val out = sh(                                               val out = sh(
                    "echo $dir",                                                "echo ${'$'}dir",
                    returnStdout = true,                                        returnStdout = true,
                )                                                               k = { out ->
                if (out.trim() == dir) {                                            if (out.trim() == dir) {
                    echo("ok")                                                          echo("ok", k = END)
                }                                                                   }
            }                                                                   }
        }                                                                   }
    }                                                                   }
}                                                                       }
```

### 3.2 Analysis

**Scripting host.** A full-fidelity CPS transform of Kotlin source is a compiler-plugin
job (IR lowerings like `suspend`'s own state machine). Doing it by regex/`KotlinScriptedSourceMapper`
cannot handle what real bodies contain: lambdas capturing locals (`val branch = ...; if (branch == ...)`
is the AGENTS.md-sanctioned pattern), `try/catch/finally`, `when` over ADTs, loops with
`break/continue`, non-local returns. Partial regex CPS produces silently wrong programs —
the transform's failure mode is a wrong pipeline, not a compile error.

**No-hack law.** Violated in practice even if the transform itself is generic: AGENTS.md
KSP law ("KSP has no semantic `when(stepName)`", no Step semantics in compilers) forces
the transform to be generic, but a generic CPS rewrite IS a new execution model bolted in
front of the spine — every one of its bugs is a durable-semantics bug. It also collides
with ADR-0006, which explicitly chose durable replay over CPS: Kotlin continuation
re-materialization across restarts is exactly what SPIKE-016 was run to disprove as
necessary.

**Durable spine.** CPS closures are re-created from re-compiled source at replay, so
replay still works (same as today's source-digest gate, S16-E4). But call-site identity
degrades: after transform, source lines no longer correspond to executing frames, so
`ScriptedSourceLocation`-derived `ScriptedCallSiteId`s must be computed pre-transform and
carried through the CPS plumbing — extra state with no spine authority, a divergence risk
surface.

**Type safety.** Fine in principle (`k = { out -> ... }` types `out`), but lambda-shaped
errors and stack traces become unreadable; the typed value arrives buried in generated
nested closures.

**Migration cost.** Highest of the three: a source transformer that must be correct for all
of Kotlin, plus every diagnostic, debugger and stack-trace path. Verdict: **REJECT** —
already superseded by ADR-0006/ADR-0065; resurrecting it for the structured frontend would
undo a recorded decision with no new evidence.

## 4. Design B — Suspend structured DSL (RECOMMENDED)

### 4.1 Shape

`pipeline { }` bodies that consume runtime values are `suspend` lambdas. Runtime-returning
step functions become `suspend fun` returning the Step's declared `O` directly. Execution
follows ADR-0065 D1: declarative structure still lowers through the canonical compiler to
the same durable spine; the body interleaves with the spine as a **scripted consumer** —
the exact model SPIKE-016 proved and LFC-2R R2 shipped at generator level.

```kotlin
// USER CODE (.pipeline.kts) — the only change is suspend + real returns
pipeline {
    stages {
        stage("deploy") {
            steps {
                val dir = pwd()                       // suspend: String, durable
                val out = sh(                          // suspend: String
                    script = "echo ${'$'}dir",
                    returnStdout = true,
                )
                if (out.trim() == dir) {               // real Kotlin control flow on real values
                    echo("ok")
                }
            }
        }
    }
}
```

What happens under `pwd()` (generic for ANY `StepDefinition<I, O>`):

```kotlin
// pipeline-scripting-api — one generic seam, zero per-Step cases
suspend fun <O : Any> StepScope.stepValue(
    stepKey: PluginStepId,
    encodedInput: EncodedStepValue,
    outputCodec: StepCodec<O>,          // static Step-owned contract, not a runtime capability
    callSite: ScriptedCallSiteId,
): O {
    val facade = currentScriptedFacade()             // identity + nextOrdinal(callSite)
    return facade.invokeTyped(stepKey, encodedInput, outputCodec, callSite)
}

// Plugin- or core-owned ergonomic façade — the DSL is typed construction; runtime
// values come from the handler through the codec. pwd(tmp=false):
suspend fun StepScope.pwd(tmp: Boolean = false): String =
    stepValue(
        stepKey = if (tmp) PwdTmpStepDefinition.KEY else PwdStepDefinition.KEY,
        encodedInput = PwdCodec.encode(PwdInput(tmp)),
        outputCodec = PwdStepDefinition.contract.outputCodec,
        callSite = location.pwdCallSite(),            // "src:line:col:pwd", distinct per shape
    )
```

`sh(returnStdout = true)` is the identical pattern with `CoreShellStep`'s contract;
`returnStdout` selects the STDOUT projection in `capturedStdout` (the `core.sh` certified
contract) and the codec returns it as `String`.

### 4.2 Analysis

**Scripting host.** Plain Kotlin. `suspend` lambdas and `suspend` top-level functions
compile in the existing Kotlin scripting host (`KotlinJvmScriptCompiler`); no plugin, no
IR, no FIR. The host call site wraps the body in a coroutine (`runBlocking` in the CLI,
the coordinator's scope in-process). SPIKE-016's hypothesis — "normal suspend Kotlin
scripted code with runtime-returning steps … replaying from the same compiled artifact …
without serializing Kotlin continuations" — PASSED, including loops (S16-E5) and nested
blocks (S16-E6). This is proven infrastructure, not a bet.

**Durable spine.** ZERO semantic change. The single-writer journal, fingerprint, replay
policy, `ReplayDecision` authority, retry control rows: untouched. The scripted consumer
path already implements the full discipline: `ScriptedCallSiteId` derived from
`ScriptedSourceLocation` (deterministic, distinct per call shape), `nextOrdinal(callSite)`
loop-safety identity (R2 receipt §ScriptedRuntime), journal lookup BEFORE registry /
capability resolution (replay never invokes the capability factory), reuse decodes the
persisted payload with the Step's OWN declared `outputCodec` so fresh and replay agree on
one output contract, and a source-digest mismatch fails closed (S16-E4). `CancellationException`
stays an execution mechanism, never durable truth (CTX-P / PAR-D laws preserved).

**Type safety.** `O` arrives typed from `outputCodec.decode`. No `Any?`, no placeholder
`String`, no boolean-coupled sentinels — strict-typed functional design rule 3 satisfied
by construction. Typed failures surface as the Step's contractual failure kind (E-EM-11
test law), not infrastructure errors.

**No-hack law.** The seam is ONE generic function over `StepKey + encodedInput + codec +
callSite`. Concrete Step knowledge lives where it already lives: plugin/core codecs and
DSL façades (the `example.uppercase` golden path, steps 10–11: façade lowers to the
generic primitive — here `stepValue` instead of eager `registryStep`). The compiler learns
nothing new; the coordinator learns nothing new. `registryStep` remains the eager
construction primitive; `stepValue` is its suspend sibling sharing the SAME lowering target.

**Migration cost.** Moderate and bounded:
1. Add `stepValue` (generic) + `RuntimeScriptedStepFacade.invokeTyped` (thin generalization
   of the existing `isUnix` adaptation; R2 receipt shows the shape).
2. Convert the four consumer façades (`pwd`, `pwd(tmp)`, `readFile`, `fileExists`,
   `sh(returnStdout)`) from eager `steps.add(...)`/placeholder to suspend `stepValue`.
3. Make the structured `steps { }` scope suspend-capable ONLY where a value is consumed;
   eager `StepSpec` construction is unchanged for value-less steps — the two forms coexist
   (same coexistence the AGENTS.md DSL-vs-runtime section already codifies for `script { }`).
4. Frontend FORM selection in `Main.kt` (R4B) extends its existing predicate: sources with
   runtime-returning calls inside `pipeline { }` route to the suspend frontend compiled
   against the same durable coordinator. No authority switch — the same form-selection law
   ("Main may choose the frontend, never the backend") already governs this code path.
5. Kill the `runtimeConfig.userDir()` placeholder (the S2-A6 diagnosis names it as root
   cause) and the eager RuntimeConfig injection for these sources (R4B already refuses it
   for the generator form).

## 5. Design C — Two-phase declarative reference/value binding

### 5.1 Shape

The eager graph is untouched. Runtime-returning builders return typed **handles**
(`StepValue<T>`); the runtime resolves every handle against journaled outputs before or
during execution of dependents.

```kotlin
// USER CODE (.pipeline.kts)
pipeline {
    stages {
        stage("deploy") {
            steps {
                val dir = pwd()                          // StepValue<String>, NO suspension
                sh("echo ${'$'}{dir}")                    // handle interpolated into input
                // Handles resolve at execution; interpolation is declarative binding:
                echo(message = "dir=${'$'}{pwdRef}")
            }
        }
    }
}
```

```kotlin
// runtime
@JvmInline value class StepValue<T : Any> internal constructor(
    val ref: ResolvedValueRef,      // (runId, callSite, ordinal) → journal row
) { /* no value accessor: T is NEVER observable at construction time */ }
```

### 5.2 Analysis

**Scripting host.** Trivially compatible — nothing suspends, the host is unchanged.

**Durable spine.** Clean in principle: the handle IS a typed journal reference, so binding
and reuse share one authority. But input contracts change shape: encoded inputs now embed
references that the engine must resolve pre-decode or mid-decode, so fingerprint must be
defined over RESOLVED values while the stored envelope contains refs — a second identity
layer to define, version, and gate (ADR-0067 monotonic schema implications).

**Type safety.** Good at the boundary (`StepValue<String>` is typed), but the payload of
the design is stringly interpolation `${dir}` into other steps' inputs — the typed `T`
exists mostly to be formatted. True typed use (`out.trim() == dir`, ADT branching) is
IMPOSSIBLE on an unresolved handle by design; the moment a body needs control flow on a
runtime value, it must fall back to `script { }` anyway. The gap would persist for exactly
the patterns the blocker receipt lists (`readFile` + `if (fileExists(...))` consumers are
branching consumers).

**No-hack law.** Generic mechanism, but it requires every Step input codec to understand
the reference form (codec-level change across all plugins) or a core-side ref-resolution
pass before decode — either way a new cross-cutting contract, not a local seam.

**Migration cost.** Lowest for wiring, but it institutionalizes a TWO-MODEL authoring
surface: declarative binding for data-flow, `script { }` for any decision. Jenkins users
writing `if (fileExists('x'))` — the AGENTS.md STEP SEMANTICS list — do not get a truthful
API in the structured form; the S2-A6 blocker text ("frontend/continuation boundary for
the whole runtime-returning family") is only half-solved. Verdict: **REJECT as the primary
design; its reference-typing insight (`(runId, callSite, ordinal) → journal row`) is
already implicit in Design B's reuse path and needs no new public handle type.**

## 6. Comparison summary

| Dimension | A. CPS transform | B. Suspend DSL | C. Handle binding |
|---|---|---|---|
| Host: compiles without plugin | NO (full-fidelity CPS = compiler plugin) | YES (plain suspend, SPIKE-016 proven) | YES |
| Spine semantic change | Call-site identity degraded | NONE (existing scripted consumer discipline) | Fingerprint over resolved values + envelope-with-refs (new identity layer) |
| Typed control flow on `O` | Yes, unreadable | YES (`if (out.trim() == dir)`) | NO (interpolation only) |
| `Any?` leakage | None | None | None, but typed `T` is inert |
| No-hack law | Fragile-generic, contradicts ADR-0006 | One generic seam over codec + callSite | New cross-cutting codec contract |
| Jenkins-familiar authoring | Preserved shape, broken diagnostics | PRESERVED (step names/params/semantics) | Split-brain (branching → `script{}`) |
| Precedent evidence | ADR-0006 rejected CPS | SPIKE-016 PASSED; LFC-2R R2 shipped the same seam at generator level | None in-tree |
| Migration cost | Highest | Moderate, bounded (§4.2) | Low wiring / high contract spread |

## 7. Decision

**Adopt Design B (suspend structured DSL).** Rationale:

1. It is the only design that fully closes the blocker for the whole runtime-returning
   family with real typed control flow, while changing zero durable-spine semantics.
2. Its two load-bearing assumptions are already proven in-tree: suspend bodies replay
   durably (SPIKE-016, passed) and the typed codec-decoded reuse path works end-to-end
   (LFC-2R R2, `ScriptedIsUnixRuntimeTest` 13/0, reuse with empty registry).
3. It is one generic mechanism (`stepValue`) over the Step's own contract — the same
   open-world discipline as `registryStep`, satisfying the no-Step-specific-hacks law.
4. A and C are respectively superseded (ADR-0006) and insufficient (no typed branching).

## 8. Machinery reused (nothing invented)

| Existing machine | Role in Design B |
|---|---|
| `ScriptedStepFacade` + `ScriptedScope.identity` / `nextOrdinal(callSite)` (R2) | loop-safe durable identity per call site |
| `ScriptedRegistryInvoker` → registry → `StepHandler` → declared capabilities | execution path, unchanged |
| `CommonExecutionResult` encoded output + Step's declared `outputCodec.decode` | typed `O` materialization, one contract both directions |
| `ReplayPolicy` / journal-lookup-before-everything | reuse/rerun authority, unchanged (OUTPUT_EXISTS ≠ REPLAY_REUSE) |
| `ScriptedSourceLocation` call-site ids (`shellCallSite`, `unixCallSite` pattern) | extend with per-shape ids (`pwdCallSite`, `readFileCallSite`, ...) |
| `registryStep` lowering discipline (plugin façade → generic primitive) | mirrored by `stepValue` |
| R4B frontend FORM selection in `Main.kt` | extended predicate, same "frontend, never backend" law |
| `script { }` block precedent | establishes suspend-style bodies as an accepted DSL form |

## 9. Hardest technical risk

**The eager/suspend duality inside `steps { }`.** Today `steps { }` is pure eager data
construction (AGENTS.md: construction must not perform I/O). Design B makes
runtime-returning calls suspend *inside the same scope*, so `StageScope`/`StepScope` must
become suspend-capable while value-less builders stay eager `StepSpec` adds. The compiler
will not stop a user from mixing both arbitrarily, and the durable frontend must then
execute one stage body that is part declarative list, part interleaved scripted consumer —
the mixed ordering (eager list flushed before? after? interleaved with suspend calls?) is
a semantics decision that must be pinned and fingerprint-stable, or call-site ordinals
diverge between fresh and edited scripts in ways the source-digest gate cannot catch.
This is the first thing the LFC-2R2 implementation slice must specify (candidate rule:
strict lexical order — eager adds and suspend calls share one ordered sequence).

## 10. What this ADR does NOT decide

- No authority flip for `core.pwd` / any burn-down state (S2-A6 counters stay 6/6/6).
- No `pipeline {}`-body rewrite in this spike; the R4B form-selection predicate change is
  implementation work under a follow-up slice with G7/UAT exit criteria.
- No new `StepSpec` subtype: the suspend frontend lowers to the same canonical
  representations (AGENTS.md STEP CONSTITUTION §4-5; closed IR unchanged).
