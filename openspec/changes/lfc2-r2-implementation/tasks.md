# Tasks: lfc2-r2-implementation

## WU-LPR-087 — sub-tasks (one open PR; multiple commits if needed)

### Phase A — Foundation (no DSL change yet)

- [ ] Extend `ScriptedCallKind` (ScriptedExecutionApi.kt) with
      `ReadFile`, `FileExists`, `ShellReturnStdout(script)` variants.
- [ ] Extend `ScriptedSourceLocation` with `readFileCallSite`,
      `fileExistsCallSite`, `shReturnStdoutCallSite` factories.
- [ ] Extend `ScriptedStepFacade` (ScriptedExecutionApi.kt) with
      `readFile(callSite, file): String`,
      `fileExists(callSite, file): Boolean`,
      `shReturnStdout(callSite, script, encoding): String`.
- [ ] L0: `:pipeline-scripting-api:compileKotlin` (10 s budget).
- [ ] L1: existing `ScriptedStepFacade` tests must continue passing
      (additive change, no breakage).

### Phase B — Concrete façade + Step registrations

- [ ] Implement `RuntimeScriptedStepFacade.readFile`,
      `fileExists`, `shReturnStdout` (CompiledScriptedEntryPoint.kt).
      Delegate to `ScriptedRegistryInvoker.invoke` + Step output codec.
- [ ] Decide `core.readFile` / `core.fileExists` placement:
      - If core: create `CoreReadFileStep.kt` + `CoreFileExistsStep.kt`
        following the `CorePwdStep` pattern. Register via
        `CoreStepRegistryFactory`. Add new capability
        `READ_FILE_CAPABILITY` if needed (else reuse
        `WORKSPACE_IDENTITY_CAPABILITY`).
      - If Tier-D REJECTED: record rejection in
        `STEP_ECOSYSTEM_MATRIX.md` with reason.
- [ ] Wire `core.sh` already has `returnStdout` semantics in
      `CoreShellStep`; verify the contract's `inputCodec` accepts
      `returnStdout = true` and the `outputCodec` decodes a `String`.
      If yes, no new Step; reuse `core.sh` for the new consumer.
- [ ] L0: full `:pipeline-application:compileKotlin`.
- [ ] L1: new `RuntimeScriptedStepFacadeTest` covering all 4 consumers
      (FRESH + REUSE + fail-closed), modelled on `ScriptedPwdRuntimeTest`.
- [ ] L1: `CoreReadFileStepContractSuiteTest` + `CoreFileExistsStepContractSuiteTest`
      (if core; Tier-D path skips these).

### Phase C — Mapping + Lowering

- [ ] Extend `KotlinScriptedSourceMapper` (KotlinScriptedSourceMapper.kt)
      with PSI recognition for `pwd(...)`, `readFile(...)`, `fileExists(...)`,
      and `sh(..., returnStdout = true)`.
- [ ] Extend `ScriptedSourceLowering` with `rewritePwdCalls`,
      `rewriteReadFileCalls`, `rewriteFileExistsCalls`,
      `rewriteShReturnStdoutCalls`. Reverse-walk discipline.
- [ ] Update `facadeSchemaVersion` to `facade-r4-pwd-readFile-fileExists-shReturnStdout-v1`.
- [ ] L0: `:pipeline-scripting-kotlin24:compileKotlin`.
- [ ] L1: `KotlinScriptedSourceMapperTest` cases for the four new forms.
- [ ] L1: `ScriptedSourceLoweringTest` cases for the four rewriters.

### Phase D — Main.kt form selector + structured body wiring

- [ ] Extend `Main.kt` form selector predicate to
      `hasAnyRuntimeReturningCall` (closed over `ScriptedCallKind`).
- [ ] Activate scripted frontend for `pipeline { }` sources that have
      runtime-returning calls.
- [ ] Wire `RuntimeScriptedStepFacade` + `ScriptedRegistryInvoker` into
      the structured-form run path.
- [ ] Remove `RUNTIME_VALUE_PLACEHOLDER` + `DslRuntimeConfigScope` references
      in `Main.kt` and the eager `PipelineDsl` builders.
- [ ] Change `PipelineDsl.steps { block }` signature to
      `block: suspend StepsScope.() -> Unit`.
- [ ] Remove the eager `pwd(tmp)` / `readFile(file)` / `fileExists(file)`
      builder methods from `StageScope` / `StepsScope` (they no longer
      make sense; the suspend path goes through the scripted frontend).
- [ ] Add `suspend fun sh(..., returnStdout = true): String` overload
      to the structured DSL (uses `RuntimeScriptedStepFacade.shReturnStdout`).
- [ ] L0: full `:pipeline-application:compileKotlin` + `:pipeline-scripting-api:compileKotlin`.
- [ ] L3: `:pipeline-application:test` (full — covers contract suites +
      UAT corpus + scripted harness + compatibility fixtures).
- [ ] Investigate any corpus fixture breakage; fix or REJECT per the
      strict certification law.

### Phase E — G7 canary + G8 certification

- [ ] Create real `.pipeline.kts` fixtures:
      `v2/compatibility/23-pwd-structured.pipeline.kts` (uses
      `val p = pwd(); sh("echo $p")`),
      `v2/compatibility/24-pwd-tmp-structured.pipeline.kts`,
      `v2/compatibility/25-readFile-structured.pipeline.kts`,
      `v2/compatibility/26-fileExists-structured.pipeline.kts`.
- [ ] Install binary; run each fixture fresh + replay with same
      `--db`/`--control-root`; assert EXIT 0 + expected typed events.
- [ ] Capture SHA-256 fingerprints for binary + fixtures.
- [ ] Write `S2_A6_CORE_PWD_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` +
      `S2_A6_CORE_PWD_G8_FINAL_CERTIFICATION_RECEIPT.md`.
- [ ] Write `S2_A6_CORE_PWDTMP_G6_CONTRACT_CERTIFICATION_RECEIPT.md` +
      `S2_A6_CORE_PWDTMP_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` +
      `S2_A6_CORE_PWDTMP_G8_FINAL_CERTIFICATION_RECEIPT.md`.
- [ ] If `readFile` / `fileExists` are core: write
      `S2_A6_CORE_READFILE_G8_CERTIFICATION_RECEIPT.md` +
      `S2_A6_CORE_FILEEXISTS_G8_CERTIFICATION_RECEIPT.md`.
- [ ] Write `docs/v2/07-uat/WU_LPR_087_LFC2_R2_IMPL.md` (the WU receipt).
- [ ] Update `STEP_INVENTORY_LFC2E0.md` rows.
- [ ] Update `INITIATIVE_LPR_001.md` Tier A.1 → CLOSED.

### Phase F — Validation + close-out

- [ ] L4: `./gradlew -p v2 check` (incremental; budget = last green × 1.3).
- [ ] L5: full L5 gate (final apply/verify).
- [ ] Update `HANDOFF-2026-09-20-LPR-AUTO-RUN.md`: close WU-LPR-087 + WU-LPR-088,
      advance queue to WU-LPR-089 (`junit.results`).
- [ ] Commit + tag `wu-lpr-087` + `wu-lpr-088` (the latter if it has its own work).
- [ ] Push `origin/main` + tags.
- [ ] Update handoff with counter deltas.

## WU-LPR-088 — `core.pwdTmp` G6+G8 final cert (if not bundled in 087)

If `core.pwdTmp` G6+G8 cannot finish inside WU-LPR-087, it becomes
WU-LPR-088 (same scope, same receipt format, separate commit).

## WU-LPR-089..095 — Tier B queue (post-R2)

- [ ] WU-LPR-089: `junit.results` full burn-down.
- [ ] WU-LPR-090: `stash` burn-down.
- [ ] WU-LPR-091: `unstash` burn-down.
- [ ] WU-LPR-092: `publishHTML` burn-down.
- [ ] WU-LPR-093: `lock` burn-down.
- [ ] WU-LPR-094: `input` burn-down.
- [ ] WU-LPR-095: `httpRequest` burn-down.

## Out-of-scope (deferred)

- SPIKE-016 re-run (its evidence is in-tree and binding).
- New `StepSpec` subtype (forbidden by AGENTS.md Step Constitution).
- `core.pwd.tmp` separate consumer façade (it shares the suspend `pwd(tmp)`
  façade with `core.pwd`; only the StepKey differs inside the registry).
- Plugin marketplace, hot reload, dependency resolution, plugin signing
  (out of scope per AGENTS.md Step Plugin Authoring Guide).
