# FIXTURE-DEBT R1 — RELEASE RECEIPT

**Cycle:** `p-733fb505b5a6bd2d/fixture-debt-18-stale`
**Path:** B-direct (apply → verify → release)
**Worktree HEAD:** `9fb4b0c8a2ef0a54a5f888b02116e5777baabf8b`
**Branch:** `prep/lfc2-fixture-debt`
**Author:** orchestrator-2026-09-15
**Date:** 2026-09-15

---

## 1. Release artifact scope

This release integrates the 18 stale-fixture deterministic reds from the B12 G0
baseline whose root cause was bare / no-registry legacy coordinator fixtures
and unbound `controlDirRoot` reaching `CanonicalLoadNodeDispatcher.kt:47`.

The release artifact is the **worktree branch `prep/lfc2-fixture-debt` at HEAD
`9fb4b0c8`**, which contains 6 commits over `origin/main@42ab7e0e`:

| SHA | Message |
|---|---|
| `2125aa5f` | docs(lfc2-fixture-debt): PREP classification — READY_TO_APPLY for the 18 stale-fixture deterministic failures |
| `55dd697a` | fix(lfc2-fixture-debt): CanonicalDurableRunCoordinatorTest — bind stepRegistry on the 10 bare constructions |
| `12651bdc` | fix(lfc2-fixture-debt): DualExecutionSeamCharacterizationTest — seamed-router dual recorder |
| `9caab8cc` | fix(lfc2-fixture-debt): DurableProtocolInvocationCharacterizationTest a1-4 — default fixture for lifecycle spine ordering |
| `1e99ad82` | fix(lfc2-fixture-debt): ExecutionBoundaryFactoryTest — bind controlDirRoot and seed workspace sentinel |
| `9adbb682` | docs(lfc2-fixture-debt): apply-report — 18/18 baseline reds green, PASS |
| `9fb4b0c8` | docs(lfc2-fixture-debt): verification-report + verify rerun log — PASS, 43/43 green, 0 prod source diff, firewall clean |

(Commit list includes the verification-report commit.)

---

## 2. Files in the release

Total: **6 files** (4 test files + 2 docs)

| Path | Category |
|---|---|
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinatorTest.kt` | TEST |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/DualExecutionSeamCharacterizationTest.kt` | TEST |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/DurableProtocolInvocationCharacterizationTest.kt` | TEST |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/ExecutionBoundaryFactoryTest.kt` | TEST |
| `docs/v2/07-uat/FIXTURE_DEBT_READY_TO_APPLY.md` | DOC |
| `docs/v2/07-uat/evidence/fixture-debt-r1/apply-report.md` | DOC |
| `docs/v2/07-uat/evidence/fixture-debt-r1/verification-report.md` | DOC |
| `docs/v2/07-uat/evidence/fixture-debt-r1/verify-rerun.log` | DOC |

**Zero production source modifications.** **Zero firewall file modifications.**

---

## 3. Mutation classification

| Category | Count |
|---|---|
| `FIXTURE_DRIFT` (production semantics correct, fixture stale) | 14 |
| `CHARACTERIZATION_REWRITE` (test characterized a seam that migrated) | 4 |
| `SEMANTIC_REGRESSION` (production semantics wrong) | 0 |
| `GLOBAL_STATE_LEFTOVER` (state bleed across tests) | 0 |

---

## 4. Test evidence

### Apply phase

| Suite | tests | pass | fail | skipped |
|---|---|---|---|---|
| `CanonicalDurableRunCoordinatorTest` | 26 | 26 | 0 | 0 |
| `DualExecutionSeamCharacterizationTest` | 5 | 5 | 0 | 0 |
| `DurableProtocolInvocationCharacterizationTest` | 8 | 8 | 0 | 0 |
| `ExecutionBoundaryFactoryTest` | 4 | 4 | 0 | 0 |
| **TOTAL** | **43** | **43** | **0** | **0** |

### Collateral B17 fixes

10 of the 47 B17-attributable reds (in `CanonicalDurableRunCoordinatorTest` and
`DualExecutionSeamCharacterizationTest`) also turned green as a side effect of
binding `stepRegistry` in the bare fixture constructions.

### Firewall audit

| Rule | Result |
|---|---|
| Production source diff | 0 ✓ |
| `CanonicalDurableRunCoordinator.kt` | untouched ✓ |
| `DslCompiledPipelineCompiler.kt` | untouched ✓ |
| `Main.kt` | untouched ✓ |
| `if(stepKey)` / `when(stepKey)` / `dispatchTimeoutBlock` introduced | 0 ✓ |

---

## 5. Gate receipts

| Receipt ID | Outcome | Date |
|---|---|---|
| `gate-tests-pass-aa765bd43a09e594-1` | passed | 2026-09-15T10:08Z |
| `gate-policy-compliant-aa765bd43a09e594-1` | passed | 2026-09-15T10:09Z |

---

## 6. Receipt digests

- `apply-report.md` (commit `9adbb682`): sha256 to be computed on `git show` reference.
- `verification-report.md` (commit `9fb4b0c8`): same.
- JUnit XML canary for `CanonicalDurableRunCoordinatorTest` at
  `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest.xml`.

---

## 7. Trunk integration protocol

This is a **local release**: cycle advanced to `phase=release, status=RELEASE_PENDING`
locally. **Trunk integration is GATED on explicit user GO.** No `origin/main` push
is performed automatically. The `merge-receipt` must reference the real trunk SHA
once the user issues the GO and the orchestrator executes the merge.

When the user issues the GO, the orchestrator will:

1. `git checkout main && git pull origin main` (verify HEAD == `42ab7e0e`).
2. `git merge --no-ff prep/lfc2-fixture-debt` (or fast-forward depending on policy).
3. `git push origin main`.
4. Tag as `v0.39.0` (or next available).
5. Capture the **post-merge trunk SHA** and store it in `merge-receipt.md`.
6. Open `p-733fb505b5a6bd2d/fixture-debt-18-stale` `release.complete` transition
   with `merge-receipt` and `release-receipt` artifacts.
7. Advance to `Archive`.

---

## 8. SDDK ledger entry

```
cycle_id: p-733fb505b5a6bd2d/fixture-debt-18-stale
phase:    release
status:   RELEASE_PENDING
path:     B-direct
sequence: 4 events
gates:
  - tests-pass           PASSED
  - policy-compliant     PASSED
artifacts:
  - implementation-receipt       (apply-report.md)
  - verification-report          (verification-report.md)
  - release-receipt              (this document, pending)
  - merge-receipt                (pending user GO)
```

---

*End of release-receipt.md. Awaiting user GO for trunk integration.*
