# Tasks: lfc2-r2-structured-dsl-runtime-return

## WU-LPR-086 — this slice (docs only)

- [x] Read blocker:
      `docs/v2/07-uat/S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md`
- [x] Inspect LFC-2R2 branches:
      `git branch -a | grep r2`
- [x] Read spike on
      `origin/cycle/lfc2-e1-r2-runtime-return` at `2227fa87` and `b7685b97`.
- [x] Verify slot conflict:
      `main` already owns ADR-0081 (BodyInvoker) and ADR-0082 (LPR priority);
      next free slot is **0093**.
- [x] Write `openspec/changes/lfc2-r2-structured-dsl-runtime-return/proposal.md`
- [x] Write `openspec/changes/lfc2-r2-structured-dsl-runtime-return/design.md`
- [x] Write this `tasks.md`.
- [ ] Extract spike text from `2227fa87` into
      `docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md`
      (renumber header from `ADR-0081` to `ADR-0093`; status from `PROPOSED`
      to `ACCEPTED`; carry forward §1-§10 unchanged).
- [ ] Update `docs/v2/04-adrs/README.md` index with the ADR-0093 entry, placed
      after ADR-0092 (next free slot).
- [ ] Update `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`:
      - add ADR-0093 reference to the `core.pwd` blocker evidence column;
      - add a short `LFC-2R2` row in any roadmap section that tracks the
        horizontal blocker explicitly (or a single inline mention).
- [ ] Update `docs/v2/05-roadmap/INITIATIVE_LPR_001.md` §Tier A.1 to reference
      ADR-0093 as the binding design.
- [ ] Write `docs/v2/07-uat/WU_LPR_086_LFC2_R2_SPIKE_BRANCH_MERGE.md` receipt.
- [ ] Commit: `WU-LPR-086: bring ADR-0093 (LFC-2R2 spike) to main — docs only`.
- [ ] Tag: `wu-lpr-086`. Push to `origin/main` + tag.
- [ ] Update handoff
      `docs/v2/07-uat/HANDOFF-2026-09-20-LPR-AUTO-RUN.md` with the next WU
      (WU-LPR-087, implementation).

## WU-LPR-087 — follow-up implementation (NOT in this PR)

This is the production-code slice. It implements the spike and closes
`core.pwd` G7+G8 + `core.pwdTmp` + `readFile` + `fileExists`. Bound by the
architecture in ADR-0093 §4.2 and the design risks in this slice's
`design.md` §5-§6.

- [ ] `stepValue` generic seam in `pipeline-step-sdk`
      (`StepScope.stepValue<I, O>(stepKey, encodedInput, outputCodec,
      callSite): O`).
- [ ] `RuntimeScriptedStepFacade.invokeTyped` (thin generalisation of the
      existing `isUnix` adaptation; LFC-2R R2 is the reference shape).
- [ ] Convert `pwd()` / `pwd(tmp)` / `readFile()` / `fileExists()` consumer
      façades from eager `steps.add(...)`/placeholder to suspend `stepValue`.
- [ ] Convert `sh(returnStdout = true)` consumer façade to suspend `stepValue`
      using `CoreShellStep`'s contract; `returnStdout` selects STDOUT
      projection in `capturedStdout` (certified contract).
- [ ] Pin the eager/suspend mixed-ordering rule (strict lexical order).
- [ ] Extend `Main.kt` form selector predicate with
      `sourceContainsRuntimeReturningCall(source)`. Frontend, never backend.
- [ ] Kill `runtimeConfig.userDir()` placeholder; remove eager `RuntimeConfig`
      injection for these sources.
- [ ] Add `CorePwdStepContractSuiteTest` rows for the suspend path (typed
      return, fresh + replay, divergence).
- [ ] Add `PwdTmpStepContractSuiteTest` rows (typed `String`, durable tmp dir,
      deterministic identity).
- [ ] Add `ReadFileStepContractSuiteTest` rows + `FileExistsStepContractSuiteTest`
      rows.
- [ ] Run `core.pwd` G7 canary: installed binary + real fixture +
      fresh EXIT 0 + replay EXIT 0 + `PwdResolved` events.
- [ ] Run `core.pwd` G8 final certification (5-layer strict validation set).
- [ ] Apply the same G6+G8 to `core.pwdTmp` (depends on `pwd(tmp)` consumer).
- [ ] Apply the same G6+G8 to `readFile` and `fileExists` (if registered as
      core Steps; otherwise Tier D).
- [ ] Receipt `docs/v2/07-uat/WU_LPR_087_LFC2_R2_IMPL.md` + per-Step receipts
      (`S2_A6_CORE_PWD_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` +
      `S2_A6_CORE_PWD_G8_FINAL_CERTIFICATION_RECEIPT.md`).
- [ ] L5 round gate (full `check`) — green before commit.
- [ ] Tag `wu-lpr-087` + push.

## WU-LPR-088..090 — post-R2 (Tier A continuation, after pwd/pwdTmp unblock)

- [ ] `core.pwdTmp` G6+G8 final certification (already covered above if the
      consumer is implemented as part of WU-LPR-087).
- [ ] Tier B (`junit.results`, `publishHTML`, `stash`, `unstash`, `lock`,
      `input`, `httpRequest`) burn-down — 7 Steps, queue WU-LPR-089..095.
- [ ] Tier C (`readTOML` / `writeTOML` / `tar` / `untar`) on demand.
