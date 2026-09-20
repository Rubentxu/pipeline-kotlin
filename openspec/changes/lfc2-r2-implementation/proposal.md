# Change: lfc2-r2-implementation (LFC-2R2 Structured DSL Runtime Return — implementation slice)

## Why

`ADR-0093 — Structured DSL Runtime Return` (ACCEPTED on `main` by WU-LPR-086,
2026-09-20) commits `pipeline-kotlin` to the **suspend structured DSL** design
(Design B of the spike) for closing the horizontal `STRUCTURED_DSL_RUNTIME_RETURN_GAP`
that currently blocks `core.pwd` G7, `core.pwdTmp` G6+G8, `readFile`, and
`fileExists` (see `S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md`).

The infrastructure the spike needs is **already in tree** (R1–R4B + S2-A5/G3R
work landed in `main` over the 2026-09-12 → 2026-09-18 cycle):

- `ScriptedStepFacade.pwd(callSite, tmp): String` (public, suspend)
  — `pipeline-scripting-api/.../ScriptedExecutionApi.kt` L262
- `RuntimeScriptedStepFacade.pwd(...)` (concrete, wired to
  `ScriptedRegistryInvoker.invoke` + `CorePwdStep.definition.contract.outputCodec.decode`)
  — `pipeline-application/.../CompiledScriptedEntryPoint.kt` L137–175
- `ScriptedRegistryInvoker.invoke(call)` (step-agnostic generic seam,
  fresh+reuse+fail-closed)
  — `pipeline-application/.../ScriptedRegistryInvoker.kt`
- `ScriptedCallKind.Pwd(tmp)` + `pwdCallSite(tmp)` (call-site identity)
  — `pipeline-scripting-api/.../ScriptedExecutionApi.kt` L72, L121
- `ScriptedFrontendRunner.run(...)` (R4B closure wiring)
  — `pipeline-application/.../ScriptedFrontendRunner.kt`
- `ScriptedPwdRuntimeTest` (FRESH + REUSE + fail-closed + call-site identity +
  persistence coherence, all green on HEAD) proves the scripted runtime-return
  end-to-end for `pwd` at the unit level.

What is **missing** for the structured `pipeline { … }` frontend to deliver
runtime-returning values is the **mapping + lowering + façade wiring** that
takes a user-authored `pipeline { ... val p = pwd(); ... }` and routes it to
the working scripted runtime-return path. Specifically:

1. `KotlinScriptedSourceMapper` only emits `ScriptedCallKind.IsUnix` today.
   It must also emit `Pwd(tmp)`, `ReadFile`, `FileExists`, and the
   `sh(..., returnStdout = true)` form.
2. `ScriptedSourceLowering` only rewrites `isUnix()` to `steps.isUnix(...)`.
   It must also rewrite `pwd()`/`pwd(true)`/`readFile()`/`fileExists()`/`sh(..., returnStdout = true)`
   to `steps.<method>(ScriptedCallSiteId(...))` calls.
3. `Main.kt` frontend FORM selection (line 622–658) only activates the
   scripted frontend for **generator-level** sources (no `pipeline { }`).
   It must activate the scripted frontend for **`pipeline { }` sources that
   contain runtime-returning calls** (the structured form), routing the body
   to `ScriptedFrontendRunner` instead of the eager `PipelineSpec` path.
4. `PipelineDsl.pwd/tmp/readFile/fileExists` are eager `fun …: Unit`
   (or eagerly add `StepSpec.RegistryStepSpec` to a list and return a
   placeholder). They must become `suspend fun …: T` calling
   `RuntimeScriptedStepFacade.<method>(callSite)`. `StageScope.steps { block }`
   must become `block: suspend StepsScope.() -> Unit`.
5. `sh(..., returnStdout = true)` must gain a `suspend` overload that returns
   `String`, calling the existing `RuntimeScriptedStepFacade.sh(..., returnStdout)`
   overload.
6. `RUNTIME_VALUE_PLACEHOLDER` and the eager `DslRuntimeConfigScope` injection
   path (ADR-0093 §4.2 step #5) MUST be killed: any future structured
   pipeline body that reaches `pwd()` synchronously is a bug, not a
   placeholder-returning compat path.

This change implements all six items. It is the **production-code slice** the
spike (WU-LPR-086) authorised. No architectural re-decision is required;
every decision is bound by ADR-0093.

## Outcomes

1. `KotlinScriptedSourceMapper` recognises and emits
   `ScriptedCallKind.Pwd(tmp)`, `ReadFile`, `FileExists`,
   `ScriptedCallKind.Shell(returnStdout = true)` (a sealed ADT extension
   or sub-class of `Shell`).
2. `ScriptedSourceLowering` rewrites all four to `steps.<method>(ScriptedCallSiteId(...))`
   calls in REVERSE offset order, preserving the existing reverse-walk
   discipline (`rewriteIsUnixCalls` is the reference shape).
3. `Main.kt` form selector predicates activate the scripted frontend for
   `pipeline { … }` sources that contain any of the four runtime-returning
   kinds, in addition to the existing generator-level predicate. The
   `isGeneratorLevelSource` check becomes a coarser predicate
   (`hasAnyRuntimeReturningCall`) with `hasStructuredBody` as a guard.
4. `PipelineDsl.pwd(tmp)`, `readFile(file)`, `fileExists(file)` become
   `suspend fun`. `sh(..., returnStdout = true)` gains a `suspend` overload
   that returns `String`. `StageScope.steps { block }` changes the block
   type to `suspend StepsScope.() -> Unit`. `RUNTIME_VALUE_PLACEHOLDER`
   and `DslRuntimeConfigScope` are removed (with all references migrated to
   the scripted runtime-return path).
5. The structured `pipeline { }` frontend wires through the SAME
   `ScriptedFrontendRunner` path the generator-level source already uses,
   with `RuntimeScriptedStepFacade` carrying the `ScriptedRegistryInvoker`.
6. `core.pwd` G7 canary (installed-CLI + real `.pipeline.kts` fixture +
   fresh EXIT 0 + replay EXIT 0) PASSES.
7. `core.pwd` G8 final certification receipt + 5-layer Strict Validation Set
   PASSES; `core.pwd` row moves from `BLOCKED (STRUCTURED_DSL_RUNTIME_RETURN_GAP)`
   to `CERTIFIED`.
8. `core.pwdTmp` G6 contract suite + G8 final certification receipt + G7 canary
   PASSES; `core.pwdTmp` row moves from `no G6/G8 yet` to `CERTIFIED`.
9. `readFile` and `fileExists` get `core.readFile` / `core.fileExists` Steps
   registered + their contract suites + G7+G8; they move from "not registered"
   to `CERTIFIED`. If the project decides they should NOT be core Steps
   (e.g. they belong in `pipeline-step-sdk/files` as a plugin), this change
   records that decision and they become Tier-D REJECTED.

## Non-goals

- No new `StepSpec` subtype. The structured DSL body still lowers to the
  SAME canonical compiled scripted entry point the generator form already
  uses; the lowerer's text-generation is what changes, not the IR.
- No change to the durable spine (single-writer journal, fingerprint, replay
  policy, `ReplayDecision`, retry control rows). ADR-0065 + ADR-0084 are
  preserved verbatim.
- No new capability surface. `core.pwd` already declares
  `WORKSPACE_IDENTITY_CAPABILITY + EVENT_SINK_CAPABILITY`; `core.readFile`
  declares `WORKSPACE_IDENTITY_CAPABILITY`; `core.fileExists` declares
  `WORKSPACE_IDENTITY_CAPABILITY`. No new capability kinds.
- No change to `LEGACY_PLUGIN_IDS` (still `{}`).
- No change to the strict certification law.

## Reference evidence (in-tree, not re-derived)

- `docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md` (binding
  design)
- `docs/v2/07-uat/S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md` (blocker)
- `docs/v2/07-uat/S2_A5_CORE_ISUNIX_G8_FINAL_CERTIFICATION_RECEIPT.md`
  (generator-form precedent for `core.isUnix`)
- `pipeline-scripting-api/.../ScriptedExecutionApi.kt` (public façade
  contracts already in place)
- `pipeline-application/.../CompiledScriptedEntryPoint.kt` (concrete façade
  implementations already in place)
- `pipeline-application/.../ScriptedRegistryInvoker.kt` (generic seam
  already in place)
- `pipeline-application/.../scripted/ScriptedFrontendRunner.kt` (runner
  already in place for generator form)
- `pipeline-scripting-kotlin24/.../KotlinScriptedSourceMapper.kt` (PSI
  mapping, needs extension)
- `pipeline-scripting-kotlin24/.../ScriptedSourceLowering.kt` (text
  rewriter, needs extension)
- SPIKE-016 (passed): suspend scripted bodies replay durably without
  serializing continuations
- LFC-2R R2 (`LFC2R_R2_ISUNIX_SCRIPTED_RUNTIME_CONSUMER.md`):
  generator-level suspend runtime-return proven end-to-end

## Strict Certification Law

This change closes the LFC-2R2 blocker and certifies `core.pwd`,
`core.pwdTmp`, `core.readFile`, `core.fileExists` (or REJECTS them in
Tier D if a non-core decision is made). No `DONE/PASS` / `IMPLEMENTED_UNCERTIFIED`
/ `WIP` / `TBD` / `partial` is accepted as a final state for any of them.
Every Step touched by this change ends the slice in one of:

- `CERTIFIED` (full G0..G8 + 5-layer Strict Validation Set + receipts), or
- `REJECTED` (with explicit reason in Tier D of `STEP_ECOSYSTEM_MATRIX.md`).

The decision for `readFile` / `fileExists` (core vs Tier D) is documented
in the WU-LPR-087 receipt and ratified by L5 gate evidence before any
code lands.
