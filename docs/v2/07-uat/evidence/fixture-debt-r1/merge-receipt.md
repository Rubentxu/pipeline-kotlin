# FIXTURE-DEBT R1 — MERGE RECEIPT

**Cycle:** `p-733fb505b5a6bd2d/fixture-debt-18-stale`
**Branch merged:** `prep/lfc2-fixture-debt`
**Merge commit (FF):** `0f4c110b5162a4895b436b5dd07a678b75ee95e9`
**Pre-merge HEAD:** `42ab7e0ee9f11cd827cb38c25621f00bfe6d92f9`
**Merge mode:** Fast-forward
**Date:** 2026-09-15T11:41 UTC
**Author:** orchestrator-2026-09-15 (executing on explicit user GO)
**Trunk SHA (post-merge):** `0f4c110b5162a4895b436b5dd07a678b75ee95e9` (== `origin/main`)

---

## 1. Integration attestation

| Verification | Result |
|---|---|
| Pre-merge `origin/main` SHA | `42ab7e0ee9f11cd827cb38c25621f00bfe6d92f9` |
| Post-merge `origin/main` SHA | `0f4c110b5162a4895b436b5dd07a678b75ee95e9` |
| `git push origin main` exit code | 0 |
| Local `main` == remote `origin/main` | YES |
| Fast-forward merge (no merge commit) | YES (clean linear history) |
| Number of commits added to trunk | 8 |
| Working tree clean post-merge | YES |

---

## 2. Trunk delta vs pre-merge

| Category | Count | Notes |
|---|---|---|
| Test files modified | 4 | All bind `stepRegistry` or `controlDirRoot` per LB-02 A5_3B |
| Documentation files added | 5 | FIXTURE_DEBT_READY_TO_APPLY + 4 evidence files |
| Production source files modified | 0 | All changes are test-only refixture |
| Firewall files modified | 0 | `CanonicalDurableRunCoordinator.kt`, `DslCompiledPipelineCompiler.kt`, `Main.kt` all untouched |
| Lines added (test side) | +103 | Across 4 test files |
| Lines added (doc side) | +1158 | Across 5 markdown/log files |

---

## 3. Pre-trunk-merge evidence (worktree HEAD `9fb4b0c8`)

Fresh JUnit XML canary (timestamps 2026-09-15T11:40Z):

| Suite | tests | fail | err | skip | timestamp |
|---|---|---|---|---|---|
| `CanonicalDurableRunCoordinatorTest` | 26 | 0 | 0 | 0 | 11:40:50.557Z |
| `DualExecutionSeamCharacterizationTest` | 5 | 0 | 0 | 0 | 11:40:53.778Z |
| `DurableProtocolInvocationCharacterizationTest` | 8 | 0 | 0 | 0 | 11:40:53.807Z |
| `ExecutionBoundaryFactoryTest` | 4 | 0 | 0 | 0 | 11:40:53.842Z |
| **TOTAL** | **43** | **0** | **0** | **0** | |

Run command: `./gradlew :pipeline-application:test --tests 4 classes --rerun-tasks`
Run exit code: 0 (BUILD SUCCESSFUL in 34s)

---

## 4. Release lineage

```
42ab7e0e (origin/main, pre-merge base)
   ↓ FF
0f4c110b (origin/main, post-merge)
   │
   ├── 2125aa5f  PREP classification
   ├── 55dd697a  fix: CanonicalDurableRunCoordinatorTest (10 bare constructions)
   ├── 12651bdc fix: DualExecutionSeamCharacterizationTest (seamed-router recorder)
   ├── 9caab8cc fix: DurableProtocolInvocationCharacterizationTest (default fixture)
   ├── 1e99ad82 fix: ExecutionBoundaryFactoryTest (controlDirRoot + workspace sentinel)
   ├── 9adbb682 docs: apply-report — 18/18 baseline reds green
   ├── 9fb4b0c8 docs: verification-report + verify rerun log
   └── 0f4c110b docs: release-receipt — THIS RECEIPT (committed before push)
```

8 commits, all documented, none touching production source or firewall files.

---

## 5. Authority chain

```
fixture-debt R1 worktree prep/lfc2-fixture-debt HEAD 9fb4b0c8
  → release-receipt.md committed at HEAD 0f4c110b
  → FF merge to local main (HEAD = 0f4c110b)
  → push origin main (origin/main = 0f4c110b)
  → merge-receipt.md (THIS FILE) persisted as durable integration evidence
```

---

## 6. SDDK ledger entry

```
cycle_id:           p-733fb505b5a6bd2d/fixture-debt-18-stale
phase:              release
status:             RELEASE_PENDING -> (after release.complete) CLOSED
path:               B-direct
sequence:           5 events (apply, verify, release, release.complete, archive)
gates:              tests-pass PASSED, policy-compliant PASSED,
                    no-pending-effects PASSED, release-uat-approved PASSED
artifacts:
  - implementation-receipt    docs/v2/07-uat/evidence/fixture-debt-r1/apply-report.md
  - verification-report       docs/v2/07-uat/evidence/fixture-debt-r1/verification-report.md
  - release-receipt           docs/v2/07-uat/evidence/fixture-debt-r1/release-receipt.md
  - merge-receipt             docs/v2/07-uat/evidence/fixture-debt-r1/merge-receipt.md (THIS)
trunk_SHA:          0f4c110b5162a4895b436b5dd07a678b75ee95e9
origin_main_SHA:    0f4c110b5162a4895b436b5dd07a678b75ee95e9
```

---

## 7. NEXT actions

1. Apply SDDK transition `release.complete` (gates: no-pending-effects + release-uat-approved; requirements: merge-receipt + release-receipt).
2. Apply SDDK transition `archive` to close the cycle in CLOSED state.
3. Re-anchor B12 worktree `pipeline-b12` onto new trunk `0f4c110b` (rebase). This will become the new `MAIN_BASELINE_POST_FIXTURE`.
4. After B12 rebase: rerun the 7-test causal matrix per user's directive to classify the 5 pre-existing reds (RESOLVED_BY_FIXTURE_DEBT | BASELINE_PREEXISTING | B12_REGRESSION).
5. After classification: implement minimal generic remediation for the 2 T2 reds (T2-HF3-3, T2-HF3-4) and the residual classification.

---

*End of merge-receipt.md. Trunk is now at `origin/main = 0f4c110b`.*
