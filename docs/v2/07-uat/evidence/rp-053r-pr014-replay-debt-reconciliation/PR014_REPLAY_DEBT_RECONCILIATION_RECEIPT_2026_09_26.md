# PR-014 replay mutation debt reconciliation receipt

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-pr014-replay-debt-reconciliation`
**Work item:** PR-014 stale replay-mutation debt reconciliation
**Date:** 2026-09-26
**Base SHA:** `013c50bc08525547888b8184c9e9e617ebb8d71d`
**Head SHA:** `013c50bc08525547888b8184c9e9e617ebb8d71d`
**Branch:** `wu/rp-053r-red-fixtures`
**Status:** `RESOLVED_NO_CODE_CHANGE`

## Question audited

The reconciled debt table marked the ten category-C replay-policy survivors as an open P2 follow-up. The authoritative R8 triage receipt also contains a later closure section for the same category, but its summary table still says `Gap real, P2`. This receipt resolves that contradiction against the current Git tree and fresh test results.

## Evidence

### Git ancestry

- `bc7c05d4` (`fix(runtime,adr-r8c): MEMOIZED SKIP requires purely-READ_ONLY effect set`) is an ancestor of `HEAD`.
- `27a6cd9e` (`docs(uat,adr-r8c): close WU-RP-040 R8 category C (10 replay mutants)`) is also in the current history.
- The implementation therefore exists in the candidate source tree. No new production patch is justified.

### Current implementation contract

`EffectReplayPolicy.kt` now requires the MEMOIZED `SKIP` branch to have a non-empty effect set in which every effect is `READ_ONLY`. Mixed sets containing `WRITES_WORKSPACE` or `EXECUTES_SUBPROCESS` return `RERUN`. Non-`SUCCEEDED` journal outcomes return `RERUN`. `ABORTS_PIPELINE` takes precedence and returns `ABORT`.

### Fresh focused verification

Command:

```bash
cd v2 && timeout 600 ./gradlew :pipeline-step-sdk:runtime:test \
  --tests 'dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicyTest' \
  --tests 'dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicyContractTest' \
  --console=plain
```

- Exit code: `0`
- Gradle log: `/home/rubentxu/.jcode/scratch/pr014-replay-tests.log`
- Gradle log SHA-256: `7c265e570b12f8cb85593dbe0bca0e9f7c80228de39c03f57db8fff26fd268ed`
- `EffectReplayPolicyTest`: 23 tests, 0 failures, 0 errors, 0 skipped
- XML: `v2/pipeline-step-sdk/runtime/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicyTest.xml`
- XML SHA-256: `7a4f6113dad9a53747e36902b9703156145af9d4b176818030af54788cf4a37a`
- `EffectReplayPolicyContractTest`: 9 tests, 0 failures, 0 errors, 0 skipped
- XML: `v2/pipeline-step-sdk/runtime/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicyContractTest.xml`
- XML SHA-256: `83c66ab5383180362f04d3046a8cb61a4ac43c279e6c1793c0afa121022d2c95`
- Total fresh focused evidence: 32 tests, 0 failures, 0 errors, 0 skipped

## Disposition

- PR-014 category-C replay-policy survivor debt: **RESOLVED**.
- Production code changes: **NONE**.
- New tests: **NONE**. Existing 23 + 9 rows already cover the previously surviving combinations.
- The historical R8 receipt remains immutable. Its closure subsection and this receipt are the current evidence; the stale summary wording must not be used to reopen the debt.
- Category A, B, and D survivor classifications remain unchanged and are not silently promoted to resolved by this receipt.

## Scope boundaries

The external release harness was not run because it belongs to `pipelinek-release-harness`. This receipt concerns only the local replay-policy mutation debt and does not claim RP-5, stable-release, or full-repository certification.

Reference implementation consulted: none applicable. This is a test-evidence reconciliation of an existing replay policy.

Behaviour adopted: retain the already-implemented fail-closed replay matrix and record its current proof without duplicating tests or changing runtime code.

Intentional deviations: none.

Security implications reviewed: replay admission remains fail-closed for journaled `NEVER` operations, and mixed effect sets do not incorrectly reuse successful history.

Tests demonstrating the contract: `EffectReplayPolicyTest`, `EffectReplayPolicyContractTest`, and the two fresh XML results listed above.
