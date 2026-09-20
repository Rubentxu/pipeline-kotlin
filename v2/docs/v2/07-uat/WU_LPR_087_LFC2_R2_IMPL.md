# WU-LPR-087 — LFC-2R2 implementation: STRUCTURED_DSL_RUNTIME_RETURN gap closure

**Date:** 2026-09-20
**Status:** CLOSED GREEN — LFC-2R2 implementation slice; PWD + FILE* Steps reach the registry-routed runtime-returning surface; the `STRUCTURED_DSL_RUNTIME_RETURN_GAP` is **partially** closed (Phases A, B, C, D-partial, E); Phase D.2 (PipelineDsl.steps { suspend }) and Phase D.3 (placeholder-seam removal) are deferred to a follow-up WU.
**Initiative:** LPR-001 (Local Production Ready + LFC-2E).
**Tags:** `wu-lpr-087-phase-a` (ee513aa6), `wu-lpr-087-phase-bcd` (654074e4), `wu-lpr-087-phase-e` (56bd0548).
**Architectural authority:** ADR-0093 (LFC-2R2 — Structured DSL Runtime Return), accepted on main in WU-LPR-086.

## Trigger / diagnosis

The LPR roadmap listed `core.pwd` as `BLOCKED` by the horizontal `STRUCTURED_DSL_RUNTIME_RETURN_GAP` (TESTING-STATE state-invariant 2026-09-20). The spike on branch `cycle/lfc2-e1-r2-runtime-return@2227fa87+b7685b97` codified the architectural cut-over into ADR-0093 (WU-LPR-086). This WU-LPR-087 implements the runtime-returning surface for the canonical filesystem primitives (`readFile` / `fileExists` / `sh(returnStdout = true)`) alongside the existing `pwd`/`isUnix`, all routed through the open registry seam.

## Change description (cumulative, 3 commits)

### Phase A (ee513aa6) — `feat(lfc2-r2): WU-LPR-087 Phase A — extend ScriptedExecutionApi ADT + fail-closed façade stubs`

- Extended `ScriptedCallKind` ADT with `ReadFile`, `FileExists`, `ShellReturnStdout(script)` data class variants.
- Extended `ScriptedSourceLocation` with `readFileCallSite()`, `fileExistsCallSite()`, `shReturnStdoutCallSite()` (collision-free per-kind).
- Declared `suspend fun readFile/fileExists/shReturnStdout` on `ScriptedStepFacade` interface.
- Implemented all three as **fail-closed stubs** in `RuntimeScriptedStepFacade` that throw `EngineInvariantViolation` with a clear "Phase B dependency" message — the L0 build remains green while the registry Steps are being built.

### Phase B + C + D-partial (654074e4) — `feat(lfc2-r2): Phase B+C+D-partial`

- **`CoreReadFileStep`**: `CoreReadFileOutput` upgraded from `data object` (Unit-only) to `data class(content: String?, exists: Boolean)`. Handler captures `WorkspaceOperations.readFile(file, encoding)` and projects the result. Codec envelope `{kind, content?, exists}`; back-compat decode accepts the legacy `{kind, outcome: SUCCESS}` shape (exists=true, content=null).
- **`CoreFileExistsStep`**: `CoreFileExistsOutput` upgraded from `data object` to `data class(exists: Boolean)`. Handler captures `WorkspaceOperations.fileExists(file).exists`. Codec `{kind, exists}`; back-compat decode accepts legacy envelope.
- **No new capabilities, no new adapters** — `WorkspaceOperations` already returned `FileReadResult(content, exists, sha256, size)` and `FileExistsResult(exists)`. The change was in the *output shape*, not in the IO contract.
- **`RuntimeScriptedStepFacade`**: 3 fail-closed stubs replaced with real implementations:
  - `readFile(callSite, file)` → `CoreReadFileStep` registry route → `String`.
  - `fileExists(callSite, file)` → `CoreFileExistsStep` registry route → `Boolean`.
  - `shReturnStdout(callSite, script, encoding)` → `CoreShellStep` with `ShellReturnMode.STDOUT` → `ShellInvocationResult.Stdout.value`.
- **`KotlinScriptedSourceMapper`**: PSI recognition extended for `pwd(tmp=true)`, `readFile(...)`, `fileExists(...)`, `sh(..., returnStdout = true)`. Each gets a closed `ScriptedCallKind` variant carrying the meaningful payload (no flag bags).
- **`ScriptedSourceLowering`**: rewriter unified into `rewriteRuntimeReturningCalls`; FACADE_SCHEMA_VERSION bumped from `facade-r3-isUnix-v1` to `facade-r4-pwd-readFile-fileExists-shReturnStdout-v1`.
- **`ScriptedCallKind.isRuntimeReturning()` extension**: closed ADT-typed predicate used by the Main form selector to activate the scripted frontend for any of the 5 runtime-returning kinds.

### Phase E (56bd0548) — `test(lpr): StepContractSuites for core.readFile / core.fileExists`

- `CoreReadFileStepContractSuiteTest` (14 tests, 0 failures): identity, contract completeness, input codec round-trip + rejection + validation, output codec encode/decode (new shape), back-compat decode, exists=false round-trip, TypedStepOutput, capability declaration == usage, open-registry seam, registry key uniqueness against all 16 other CoreSteps.
- `CoreFileExistsStepContractSuiteTest` (14 tests, 0 failures): mirror coverage.
- **Decoder defect fix** in `CoreReadFileStep`: `obj["content"]?.jsonPrimitive?.content` collapsed `JsonNull` to the literal string `"null"`. Fixed by branching on `JsonNull` explicitly so the new envelope `{"content": null, "exists": false}` round-trips to Kotlin `null` content.

## Verification

### L0 (compile, all modules)
```
./gradlew :pipeline-scripting-api:compileKotlin \
          :pipeline-scripting-kotlin24:compileKotlin \
          :pipeline-application:compileKotlin \
          :pipeline-application:compileTestKotlin
→ BUILD SUCCESSFUL (only pre-existing deprecation warnings).
```

### L1 (focal tests, post-change)
```
./gradlew :pipeline-application:test \
  --tests '*ScriptedIsUnix*' \
  --tests '*ScriptedPwd*' \
  --tests '*KotlinScriptedSourceMapper*' \
  --tests '*CorePwdStep*' \
  --tests '*CorePwdTmpStep*' \
  --tests '*CoreWriteFileStep*' \
  --tests '*CompatibilityCorpus*'
→ BUILD SUCCESSFUL in 2m 41s. 11 XMLs, 141 tests, 0 failures, 0 errors.
```

### L2 (Phase E suites, fresh)
```
./gradlew :pipeline-application:test \
  --tests '*CoreReadFileStepContractSuite*' \
  --tests '*CoreFileExistsStepContractSuite*'
→ BUILD SUCCESSFUL. 2 XMLs, 28 tests, 0 failures, 0 errors.
```

### Compatibility corpus
`CompatibilityCorpusTest` 30/0/0 — corpus intact including fixtures 13 (`workspace-helpers.pipeline.kts`: `pwd()` + `isUnix()`), 20 (`pwd-tmp.pipeline.kts`), 23 (`readFile.pipeline.kts`). Behavior unchanged on the legacy `pipeline { }` path.

### Audit (negative findings — NOT regressions)
1. `CoreReadFileOutput`/`CoreFileExistsOutput` had **zero** existing test references (`grep -rn` returned no matches before this WU) — this was a pre-existing test gap from WU-LPR-104, not introduced by Phase B. Closed by Phase E.
2. `StepCapability` is a `value class` with `key: String`. Test 11 originally used `assertSame` (reference), which fails across boxing boundaries; corrected to `assertEquals(key)` in Phase E.
3. `CoreReadFileStep` decoder JsonNull defect (caught by Phase E test #9) was latent since WU-LPR-104; the legacy envelope never carried `content: null` so it was invisible. LFC-2R2's typed envelope surfaces it. Fixed in Phase E.

## SHA-256 fingerprints

| Artifact | SHA-256 |
|----------|---------|
| Phase A commit | `ee513aa6` |
| Phase BCD commit | `654074e4` |
| Phase E commit | `56bd0548` |
| `CoreReadFileStepContractSuiteTest.xml` | see `pipeline-application/build/test-results/test/` (tests=14, failures=0, errors=0) |
| `CoreFileExistsStepContractSuiteTest.xml` | see `pipeline-application/build/test-results/test/` (tests=14, failures=0, errors=0) |

## End-of-work-unit closure

```text
Reference implementation consulted: PipelineK internal core; LFC-2R2 spike (merge → ADR-0093).
Behaviour adopted: typed readFile / fileExists / sh(returnStdout=true) via the open registry seam; no new StepKeys added (StepKeys unchanged from WU-LPR-104 catalog); codec envelope evolves from {kind, outcome} to {kind, content?, exists}/{kind, exists}/{kind, ..., returnMode=STDOUT}.
Intentional deviations: Phase D.2 (PipelineDsl.steps { suspend }) and Phase D.3 (RUNTIME_VALUE_PLACEHOLDER / DslRuntimeConfigScope removal) deferred to a follow-up WU. The runtime-returning path is end-to-end wired at the registry + façade layer; the DSL `pipeline { stages { stage { steps { } } } }` signature still uses the eager `fun Unit` and continues to fabricate placeholders. Closing those is a structural refactor, not a Step change.
Security implications reviewed: readFile / fileExists reach filesystem through the certified FileReadExecutor / FileExistsExecutor path safety (workspace-root guard + .v2 reserved-directory guard). sh(returnStdout=true) reuses CoreShellStep with the same capability-routed SHELL_OPERATIONS_CAPABILITY boundary as the eager sh(...) path. No new attack surface.
Tests demonstrating the contract:
  - pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreReadFileStepContractSuiteTest.kt
  - pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreFileExistsStepContractSuiteTest.kt
  - pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CompatibilityCorpusTest.kt
  - pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdStepContractSuiteTest.kt (pre-existing; core.pwd covered)
```

## Acceptance checklist (against AGENTS.md prime directives)

- [x] **Hexagonal architecture preserved**: handlers reach workspace file IO through `WorkspaceOperations` capability (existing port); no new infrastructure coupling.
- [x] **Strict typed functional design**: every runtime-returning call is modeled as a closed `ScriptedCallKind` ADT variant carrying meaningful payload (no flag bags); codec decode is total on documented envelopes (back-compat for legacy).
- [x] **Step semantics match Jenkins**: `pwd()` / `readFile()` / `fileExists()` / `sh(returnStdout=true)` follow Jenkins semantics — fileExists is a predicate (exists=false is success), readFile failure on missing is a typed Failure (not silent).
- [x] **Step constitution**: zero changes to `CanonicalRuntimeContext` access from handlers; capability declared == used (G3-A4.2 verified by test 11); no `when(stepKey)` cases added to any central dispatcher.
- [x] **Reference implementation research**: confirmed Jenkins semantics from the LFC-2R2 spike (WU-LPR-086) + ADR-0093.
- [x] **Replay policy execution vs re-execution**: `ReplayPolicy.MEMOIZED` reused; the typed output is persisted so REUSE reproduces the observation without re-IO.
- [x] **Coroutines — execution mechanism, never durable authority**: facade throws structured `PipelineStepException` for typed failures, never maps cancellation to durable failure.
- [x] **Explicit immutable execution context**: no ambient state mutation; `ScriptedScope.identity` is immutable data passed through `ScriptedRegistryInvoker.invoke`.

## Open / deferred

1. **Phase D.2**: `PipelineDsl.steps { block }` → `suspend` — required for the scripted DSL `runtime { }` block to call the new `readFile/fileExists/shReturnStdout` SUSPEND functions directly. Currently the façade methods are wired but unreachable from the `pipeline { }` body.
2. **Phase D.3**: `RUNTIME_VALUE_PLACEHOLDER` + `DslRuntimeConfigScope` removal — required to close the horizontal gap fully and stop fabricating placeholder values for `pwd`/`readFile` in eager DSL compilation. Deferred because the structural change requires re-architecting the eager `PipelineDsl` compiler path.
3. **G7 installed-CLI canary** for `core.readFile` / `core.fileExists`: requires a new `*.pipeline.kts` fixture exercising the canonical scripted frontend path; not added because Phase D.2 is a precondition.
4. **G8 inventory update** for `core.readFile` / `core.fileExists`: `STEP_INVENTORY_LFC2E0.md` does not exist in the repo (referenced in TESTING-STATE but uncreated); the inventory ledger must be added as part of the broader LFC-2E0 milestone, not as a per-WU item.

## Next WU

`WU-LPR-088` — Phase D.2+D.3 (PipelineDsl `suspend` refactor + placeholder-seam removal) is now the binding prerequisite to certify `core.pwd` and `core.pwdTmp` end-to-end via the installed-CLI G7 canary. Queue advancement per TESTING-STATE §Queue.
