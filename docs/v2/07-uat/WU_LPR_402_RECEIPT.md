# WU-LPR-402 — pwd()/isUnix() honest runtime semantics (closure receipt)

**Status:** `CLOSED WITH KNOWN-FOLLOWUP`.

**Result map (per slice objective):**

| Slice                                                | Status                                         |
|------------------------------------------------------|------------------------------------------------|
| Reuse-first inventory of authorities                 | **GREEN** — 5 registry authorities, 1 scripted façade, 1 runtime façade all already exist (S2-A5 G8, S2-A6 G5, S2-A6 G3T, LFC-2R R2) |
| `ScriptedStepFacade.pwd` declared + routed           | **GREEN** — typed `String`, mirrors `isUnix` in shape |
| `ScriptedCallKind.Pwd(tmp)` + `pwdCallSite()`        | **GREEN** — distinct identity at the same source position |
| `PipelineDsl.pwd(tmp=false)` → registry path         | **GREEN** — lowers to `RegistryStepSpec("core.pwd", …)` |
| `PipelineDsl.pwd(tmp=true)` → registry path          | **GREEN** — lowers to `RegistryStepSpec("core.pwd.tmp", …)` (was already; preserved) |
| `PipelineDsl.isUnix()` → registry path               | **GREEN** — lowers to `RegistryStepSpec("core.isUnix", …)` |
| Synchronous return is honest placeholder             | **GREEN** — `RUNTIME_VALUE_PLACEHOLDER` and `ISUNIX_PLACEHOLDER` constants; do NOT depend on `RuntimeConfig` |
| Fitness properties (7 properties, 0 filenames)       | **GREEN** — `WULpr402RuntimeHonestDslFitnessTest` 7/7 |
| Structured DSL runtime-return seam (`pipeline { ... }`) | **DEFERRED to LFC-2R2 / WU-LPR-402P** — `STRUCTURED_DSL_RUNTIME_RETURN_GAP` blocker remains |

The WU closes at the seam of what the GENERATOR form (the scripted
`.pipeline.kts` lowered to a `CompiledScriptedEntryPoint`) can already
deliver end-to-end with the existing registry authorities. The
STRUCTURED form remains a separate WU because it requires a compiler
lowering path that this WU did not introduce.

---

## 1. Reuse-first inventory (the work was alignment, not authoring)

The architectural foundation was already present from prior slices:

```text
CoreIsUnixStep       (S2-A5 / G8 CERTIFIED)
                     typed IsUnixInput / IsUnixOutput
                     capability-routed (PLATFORM_IDENTITY, EVENT_SINK)
                     ReplayPolicy.MEMOIZED + Effect.READ_ONLY

CorePwdStep          (S2-A6 / G5 LEGACY_REMOVED + G6 CONTRACT_SUITE)
                     typed PwdInput(tmp) / PwdOutput(path)
                     capability-routed (WORKSPACE_IDENTITY, EVENT_SINK)
                     ReplayPolicy.MEMOIZED + Effect.READ_ONLY
                     tmp=true rejected at decode (PWD_TMP_TRUE_DISPOSITION)

CorePwdTmpStep       (S2-A6 / G3T deterministic tmp)
                     typed PwdTmpInput / PwdTmpOutput (typealias PwdOutput)
                     capability-routed (TEMPORARY_WORKSPACE_OPERATIONS,
                                        EVENT_SINK)
                     deterministic tmp-pwd-<sha256(opId)>
                     REUSE does NOT recreate the directory

ScriptedStepFacade.isUnix(callSite): Boolean
                     (LFC-2R / R2, R2 proven end-to-end)
ScriptedRegistryInvoker
                     (LFC-2R / R2; durable decision + capability bridge)
RuntimeScriptedStepFacade.isUnix
                     thin adaptation; no parallel decoder
UnixPlatformClassifier.classifyUnix(osName)
                     single canonical pure classifier
```

The WU's contribution was:
1. Add `pwd` to the scripted façade (mirror `isUnix`).
2. Rewire the eager DSL funs to the registry path (no more legacy
   `StepSpec.Pwd` / `StepSpec.IsUnix` lowering).
3. Make the synchronous return value a documented placeholder constant
   instead of a host-read return.

No new Step implementation, no new codec, no new capability, no new
classifier.

---

## 2. Delta — DSL fun honesty

### Before

```kotlin
fun pwd(tmp: Boolean = false): String {
    if (tmp) {
        steps.add(StepSpec.RegistryStepSpec(
            stepKey = PluginStepId("core.pwd.tmp"),
            schemaVersion = "dsl-v1",
            encodedInput = EncodedStepValue("{}"),
        ))
    } else {
        steps.add(StepSpec.Pwd(tmp = false))    // LEGACY path
    }
    return runtimeConfig.userDir().ifEmpty { "<workspace>" }    // dishonest host read
}

fun isUnix(): Boolean {
    steps.add(StepSpec.IsUnix())    // LEGACY path
    val osName = runtimeConfig.osName().lowercase()    // dishonest host read
    if (osName.isEmpty()) return true
    return osName in listOf("linux", "macos", "darwin", ...)
}
```

### After

```kotlin
fun pwd(tmp: Boolean = false): String {
    if (tmp) {
        steps.add(StepSpec.RegistryStepSpec(
            stepKey = PluginStepId("core.pwd.tmp"),
            schemaVersion = "dsl-v1",
            encodedInput = EncodedStepValue("{}"),
        ))
    } else {
        // WU-LPR-402 — registry path; the legacy StepSpec.Pwd form is gone.
        steps.add(StepSpec.RegistryStepSpec(
            stepKey = PluginStepId("core.pwd"),
            schemaVersion = "dsl-v1",
            encodedInput = EncodedStepValue("""{"kind":"pwd","tmp":false}"""),
        ))
    }
    return RUNTIME_VALUE_PLACEHOLDER    // honest placeholder constant
}

fun isUnix(): Boolean {
    steps.add(StepSpec.RegistryStepSpec(
        stepKey = PluginStepId("core.isUnix"),
        schemaVersion = "dsl-v1",
        encodedInput = EncodedStepValue("{}"),
    ))
    return ISUNIX_PLACEHOLDER    // honest placeholder constant
}
```

The KDoc on each builder explicitly states:

> **WU-LPR-402 — runtime-returning DSL fun.** This builder lowers to a
> registry Step that produces the value as a typed runtime value at
> execution time. It does NOT return the real value synchronously from
> this DSL call — that would be a fake runtime value. Reading this
> return value as the real runtime value is a WU-LPR-402 contract
> violation.

---

## 3. Honest distinction (matrix)

```text
                construction (eager)         execution (typed)
                ──────────────────────       ──────────────────────
DSL fun call    pwd() → placeholders        runtime invocation
                isUnix() → placeholders     through ScriptedStepFacade
                                            returns typed value

Real value      NEVER reaches here           ALWAYS reaches here
                (no host reads)             (registry → codec → typed)

Why            DSL is IR construction.      The Step is the canonical
                The host environment is     runtime value producer;
                irrelevant to the IR.        the output codec is the
                                            typed projection authority.
```

The previous `runtimeConfig.userDir()` / `runtimeConfig.osName()` reads
in the DSL fun were the dishonest form: a host observation that
appeared to be a runtime value but was actually a build-time fabrication
that never reached the journal. The placeholder constants make the
dishonesty visible and the contract explicit.

---

## 4. Replay / durability (mirrors `core.isUnix` precedent)

The registry authority is `ReplayPolicy.MEMOIZED` on both `CorePwdStep`
and `CoreIsUnixStep`. This means:

```text
fresh execution (no durable history)
    → FRESH: CorePwdStep handler observes WorkspaceIdentity.workspaceRoot
            and emits PwdResolved event
    → REPLAY: persisted PwdOutput.path is reproduced without re-observing

platform change between fresh and replay
    → REPLAY: persisted path is reproduced; the new workspace is NOT observed
    → this is correct durable semantics (a stale observation after a
       workspace change is intentional, not a bug)

first execution emits PwdResolved (UnixDetected for isUnix)
replay emits NO new PwdResolved event
```

`core.pwd.tmp` follows the same pattern, but the `Files.createDirectories`
effect is gated by REUSE: on resume, the persisted path is reused
without recreating the directory (G7-04 evidence in
`S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md`).

---

## 5. Test evidence (36/36 GREEN in the touched surface)

### Scripting-api suite (PipelineDslPwdLoweringTest rewritten)

```text
PipelineDslPwdLoweringTest (WU-LPR-402)              7/7 GREEN
CheckoutDslTest                                      4/4 GREEN
StepSpec sealed hierarchy tests                      1/1 GREEN
PipelineDsl top-steps builders                      12/12 GREEN
PipelineDsl withCredentials tests                   16/16 GREEN
ScriptedSourceLocationTest                           1/1 GREEN
```

The rewritten `PipelineDslPwdLoweringTest` adds:

- `pwd tmp=false lowers to registry StepSpec with core pwd key`
- `pwd tmp=true lowers to StepSpec RegistryStepSpec with core pwd tmp key`
- `pwd default (no arg) lowers to registry StepSpec with core pwd key and tmp=false`
- `pwd returns the honest placeholder sentinel (RUNTIME_VALUE_PLACEHOLDER)`
- `pwd multiple invocations produce independent registry StepSpecs in step order`
- `isUnix lowers to registry StepSpec with core isUnix key`
- `isUnix returns the honest placeholder sentinel (ISUNIX_PLACEHOLDER)`

### Application scripted-runtime suite (NEW ScriptedPwdRuntimeTest)

```text
ScriptedPwdRuntimeTest (NEW, mirrors ScriptedIsUnixRuntimeTest)   6/6 GREEN
  - fresh execution target value (synthetic workspaceRoot)
  - REUSE with empty registry + changed workspace returns persisted path
  - pwd call-site identity distinct from shell/unix
  - fail closed: missing step on fresh throws, no fabricated String
  - fail closed: facade without invoker throws
  - persisted PwdOutput decodes through the declared outputCodec

ScriptedIsUnixRuntimeTest                                          13/13 GREEN
ScriptedIsUnixCompilerMappingTest                                  10/10 GREEN
```

### Architecture fitness

```text
WULpr402RuntimeHonestDslFitnessTest (NEW, 7 properties)            7/7 GREEN
  1. DSL funs do NOT read host environment to fabricate a runtime value
  2. DSL funs lower to RegistryStepSpec with canonical StepKey
  3. ScriptedStepFacade declares runtime-returning pwd/isUnix methods
  4. RuntimeScriptedStepFacade pwd routes only through registry invoker
  5. Codec contract: decode(encode(x)) == x for IsUnixOutput and PwdOutput
  6. No second authority for runtime-returning values
  7. Sanity check on the brace-matched body extractor
```

---

## 6. Pre-existing arch failures (UNCHANGED by this WU)

The WU-LPR-402 fitness and its dependencies are all green. The
following architecture tests were red on the WU-LPR-401 baseline
(verified by `git stash` + rerun) and remain red after this WU:

```text
FArchL7BlockStepNestingInvariantTest             10/11 (pre-existing)
FArchL7JenkinsVerbatimSignatureReflectionTest     2/6 (pre-existing)
FArchLfc1CanonicalCoverageTest                   1/3 (pre-existing)
LegacyResidualConvergenceFitnessTest             2/3 (pre-existing)
Lfc0GlobalStateFitnessTest                       1/2 (pre-existing)
Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest 1/7 (pre-existing)
Lfc2DurableAggregateIdentityFitnessTest          2/5 (pre-existing)
Lpr101L4SweepCharacterizationTest                 1/7 (pre-existing)
S3EmitEventLegacyRemovedFitnessTest              1/8 (pre-existing)
S3ErrorLegacyRemovedFitnessTest                  4/12 (pre-existing)
S3IsUnixLegacyRemovedFitnessTest                 1/? (pre-existing)
S3PwdLegacyRemovedFitnessTest                    1/? (pre-existing)
S3SleepLegacyRemovedFitnessTest                  1/? (pre-existing)
S3WriteFileLegacyRemovedFitnessTest              1/? (pre-existing)
```

Per the established WU discipline: pre-existing failures are NOT
re-baselined by this WU; they remain on the existing follow-up
ledger (see WU-LPR-302 receipt §4 for the original ledger, plus the
LFC-2R follow-ups list).

---

## 7. Deferred work (explicit follow-up WU)

### WU-LPR-402P — Structured DSL Runtime-Return Seam

The structured DSL form `pipeline { stages { stage("...") { val unix =
isUnix(); ... } } }` continues to use the eager `PipelineSpec` frontend
in Main.kt (R4B FORM selection, see Main.kt lines 542-580). The
generator form is the canonical runtime-return path today; the
structured form remains blocked by `STRUCTURED_DSL_RUNTIME_RETURN_GAP`
(frozen in `S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md`).

The follow-up must:
1. Lower `pipeline { ... }` forms with runtime-returning calls to a
   `CompiledScriptedEntryPoint` (currently only generator-level sources
   are lowered).
2. Route the lowered entry point through `RuntimeScriptedStepFacade` so
   the typed value reaches the Kotlin frame.
3. Update Main.kt R4B FORM selection to include the structured form
   when runtime-returning calls are detected.

### WU-LPR-403 — Declarative Builder Purity / Runtime Escape Audit

Re-scoped by the human's WU-LPR-402 instruction: NOT a compiler-level
`body: () -> List<StepSpec>` rewrite. NOT a build-time lint rule.
The question becomes: which builder lambdas actually observe runtime
state or produce runtime effects that should belong to pipeline
execution? Until WU-LPR-402 establishes what belongs to runtime,
WU-LPR-403 cannot pick a trajectory. Once WU-LPR-402P closes, WU-LPR-403
may close as `NO PRODUCTION CHANGE REQUIRED` if no runtime escapes are
identified.

---

## 8. Total accounting

```text
Commits:    1 (91435dfb — Phases 1+2+3 batched)
Files:      6 changed (3 production + 3 test)
  production:
    v2/pipeline-scripting-api/.../ScriptedExecutionApi.kt    +12
    v2/pipeline-scripting-api/.../dsl/PipelineDsl.kt          ~50 (honest rewire)
    v2/pipeline-application/.../scripted/CompiledScriptedEntryPoint.kt  +82 (pwd override + helper)
  tests:
    v2/pipeline-scripting-api/.../PipelineDslPwdLoweringTest.kt  rewritten (5 → 7 tests)
    v2/pipeline-application/.../ScriptedPwdRuntimeTest.kt  NEW (6 tests)
    v2/pipeline-architecture-tests/.../WULpr402RuntimeHonestDslFitnessTest.kt  NEW (7 properties)

Tests:      36/36 GREEN in the touched surface (scripting-api + scripted-runtime)
Fitness:    7/7 GREEN (WULpr402RuntimeHonestDslFitnessTest)
Pre-existing arch failures: 14 files (UNCHANGED, verified by stash+rerun)
Risk:       LOW — zero behavioural change for scripts that DO use the scripted runtime path;
            honest placeholder return for scripts that read the synchronous value
Follow-up:  WU-LPR-402P (structured DSL runtime-return seam) + WU-LPR-403 (builder purity audit)
```

---

## 9. Why this closes, not defers

The WU brief was: make `pwd()` and `isUnix()` honest runtime-returning
DSL funs. That is now true for the supported path (the scripted runtime
context). The unsupported path (the eager `PipelineSpec` frontend with
synchronous reads) is replaced by honest placeholder constants that
make the contract violation visible.

The structured form `pipeline { val unix = isUnix(); ... }` is a
genuine follow-up because the lowering path from `pipeline { ... }` to
`CompiledScriptedEntryPoint` does not yet exist — the R4B FORM
selection explicitly excludes it. Filing WU-LPR-402P with a clear
scope is the responsible close; pretending the structured form is
supported would re-introduce the dishonesty this WU removed.
