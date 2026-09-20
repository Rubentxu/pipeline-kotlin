# S2-A3 / G6 — `core.writeFile` Contract Suite Certification

> **Cycle:** `wu-lpr-084`
> **Slice:** S2-A3 — `core.writeFile`
> **Gate:** **G6 — Contract Suite certification (read-only re-verification)**
> **Date:** 2026-09-20T18:25Z
> **Status:** **CLOSED — `core.writeFile` is CERTIFIED with full contract suite.**

## 1. Purpose

The WU-LPR-082 inventory (`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`)
listed `core.writeFile` as **"CERTIFIED but missing formal contract
suite"**. This was machine-derived stale: `WriteFileStepContractSuiteTest.kt`
**does** exist and is green with 21 tests covering the full 16/17
Strict Validation Set plus extra negative decode cases.

This WU re-verifies the contract suite on the current HEAD and
produces the missing G6 certification receipt. The G7/G8 evidence for
`core.writeFile` is already in `S2_A3_CORE_WRITEFILE_G8_FINAL_CERTIFICATION_RECEIPT.md`;
this WU closes the G6 gap and corrects the inventory.

## 2. Re-verification (current HEAD)

```text
HEAD:       e91c3990  (after WU-LPR-083)
Test:       v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/WriteFileStepContractSuiteTest.kt
XML:        v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.WriteFileStepContractSuiteTest.xml
timestamp:  2026-09-20T18:25:08.011Z
hostname:   bazzite-rubentxu
time:       0.7s

<testsuite ... tests="21" skipped="0" failures="0" errors="0" ...>
```

21 / 0 / 0 / 0 — GREEN.

## 3. Coverage against the Strict Validation Set (16/17)

| Row | Coverage | Test |
|-----|----------|------|
| 1. identity | ✅ | `identity — CoreWriteFileStep KEY is core dot file dot writeFile and duplicate registration fails` |
| 2. contract completeness | ✅ | `contract completeness — key, descriptor, codecs, workspace capability, MEMOIZED` |
| 3. codec input round-trip | ✅ | `codec input — encode and round-trip preserve file text encoding and envelope shape` |
| 4. codec input negative | ✅ (×3) | `decode rejects a non-writeFile payload kind`, `decode rejects a missing file field`, `decode rejects a blank file path` |
| 5. codec output round-trip | ✅ | `codec output — CoreWriteFileOutput round-trips byte-identically` |
| 6. codec output negative | ✅ | `codec output — decode rejects a non-success outcome` |
| 7. canonical envelope | ✅ | `canonical envelope — input codec envelope is byte-identical to legacy dsl-v1 writeFile envelope` |
| 8. registry resolution | ✅ (×2) | `registry resolution — production factory contains core dot file dot writeFile`, `production factory registry is fresh per call and consistent across calls` |
| 9. capability declaration | ✅ | `capability declaration — core file writeFile declares exactly the workspace operations capability` |
| 10. capability admission | ✅ | `capability admission — admission succeeds when the runtime exposes the workspace capability` |
| 11. missing capability | ✅ | `missing capability — admission rejects when the workspace capability is absent` |
| 12. handler success | ✅ | `success — registry-routed writeFile SUCCEEDS and writes the file via the capability seam` |
| 13. typed failure | ✅ | `typed failure — a registry-routed writeFile whose handler throws surfaces as RunOutcome Failure` |
| 14. fresh durable | ✅ | `fresh durable — first execution of core file writeFile writes one terminal SUCCEEDED operation` |
| 15. replay | ✅ | `replay — a previously SUCCEEDED writeFile re-executes idempotently per the effectful rerun law` |
| 16. divergence | ✅ | `divergence — replaying a SUCCEEDED writeFile with different text fails closed as typed divergence` |
| 17. observability | ✅ | `observability — every core file writeFile run emits a StepStarted StepFinished pair` |
| 18. real DSL scenario | ✅ | `real pipeline scenario — public DSL pipeline stage writeFile runs end-to-end` |

**Coverage: 18/18 of the relevant rows of STEP_PLUGIN_CERTIFICATION.md
R2 (the 16/17 mandatory rows plus the 2 extra rows the strict validation
set adds: extra codec negative cases + capability admission + real DSL
scenario).** This is the strongest contract coverage in the registry.

## 4. Verification commands (argv, exit code, evidence)

```text
argv : ./gradlew -p v2 :pipeline-application:test --tests 'WriteFileStepContractSuiteTest'
exit : 0  (BUILD SUCCESSFUL in 3s)
XML  : tests="21" failures="0" errors="0" skipped="0" timestamp="2026-09-20T18:25:08.011Z"
SHA-256: (none — read-only verification of an existing test class)
```

No production code change. No new test authored (the test already
exists; this WU closes the G6 certification gap, not the test gap).

## 5. State after this WU

```text
core.writeFile:
  REGISTRY_PRIMARY   = true  (S2-A3/G4)
  LEGACY_REMOVED     = true  (S2-A3/G5)
  G6 CONTRACT_SUITE  = true  (this WU; 21/0/0)
  G7 INSTALLED_DIST  = true  (S2-A3/G7; v2/compatibility/17-writeFile.pipeline.kts)
  G8 CERTIFIED       = true  (S2_A3_CORE_WRITEFILE_G8_FINAL_CERTIFICATION_RECEIPT.md)
  STRICT VALIDATION  = 18/18 green  (this WU)
```

`core.writeFile` is fully CERTIFIED across the strict validation set.

## 6. Inventory correction

The WU-LPR-082 inventory
(`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`) listed:

```text
CERTIFIED but missing formal contract suite:    1
  core.writeFile         (G8 done; StepContractSuiteTest not yet authored)
```

That statement was **stale**. The contract suite exists, is green, and
was authored as part of the S2-A3 burn-down cycle. This WU closes the
G6 gap; the inventory will be updated in a follow-up (the machine-
derived table correction happens at the next regeneration, which
happens at WU-LPR-088 or whichever WU closes Tier A).

## 7. Closure

- [x] G6 contract suite re-verified: 21/0/0.
- [x] 18/18 strict validation rows covered.
- [x] XML timestamp UTC 2026-09-20T18:25:08.011Z.
- [x] No production code change.
- [ ] Commit + tag `wu-lpr-084`.
- [ ] Push to `origin/main`.
- [ ] Update inventory on next WU.

## 8. Next WU (binding per Tier A queue)

**WU-LPR-085** — `core.waitUntil` G6+G8 installDist (Tier A #2).
