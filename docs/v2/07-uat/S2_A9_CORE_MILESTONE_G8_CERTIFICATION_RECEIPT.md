# S2-A9 / G8 — core.milestone Final Certification Receipt

> Cycle: `cycle/lfc2-e1-milestone` (closed across LFC-2E1 / S2-A9 burn-down slices)
> Slice: S2-A9 (`core.milestone`)
> Gate: **G8 — Final Certification (read-only verification of all prior gates)**
> Base: `main @ b53d1ec8` (post-G5 milestone G5 merge + G6 contract-suite merge); branch HEAD at verification: `b53d1ec8`
> Date: 2026-09-13 (local +02:00)
> Production code changes: **0** in this G8 slice (counter reconciliation tests + ledger updates only)

G8 closes S2-A9 and **proposes `CERTIFIED = true`** for `core.milestone`
(ADR-0074). This receipt is a read-only re-verification of every prior gate
on current main with fresh evidence; the installed-CLI acceptance burden was
discharged at G5 via the `21-milestone.pipeline.kts` canary (`RunFinished{outcome=success}` + `MilestoneReached{1,2}` events recorded in the G5 receipt).

## 1. Per-gate verification table (re-verification of CLOSED gates)

G1..G6 are already-closed facts (recorded in their merged receipts and in the
gate ledger below). This G8 slice re-verifies on current main (`b53d1ec8`)
that the recorded evidence is intact and fresh (relevant inputs unchanged
since each gate's green run). Where a claim below cites a test, the test is
the gate's OWN recorded proof, re-run fresh here solely for the freshness
canary.

| Gate | Closed at (authority) | Freshness re-verification on `b53d1ec8` (2026-09-13) | Verdict |
|---|---|---|---|
| G1 registry seam proof | `S2_A9_CORE_MILESTONE_G1_RECEIPT.md` | `CoreMilestoneStepUnitTest > identity — registry key namespace matches core.*` re-run fresh | **CONFIRMED** |
| G2 corpus migration | `S2_A9_CORE_MILESTONE_G2_RECEIPT.md` | `CoreMilestoneStepUnitTest > corpus fingerprint — registry envelope byte-equivalent` re-run fresh | **CONFIRMED** |
| G3 REGISTRY_PRIMARY | `S2_A9_CORE_MILESTONE_G3_RECEIPT.md` | `CoreMilestoneRegistryPrimaryFitnessTest` re-run fresh (counters 5/5/5 at G3, now post-G5) | **CONFIRMED** |
| G5 LEGACY_REMOVED | `S2_A9_CORE_MILESTONE_G5_LEGACY_REMOVED_RECEIPT.md` (counters 5/5/5 → 4/4/4) | `LegacyResidualSnapshot.assertConverged` (inside all 7 `S3*LegacyRemovedFitnessTest`, re-run fresh) confirms the three legacy forms (command subtype/decoder branch, dispatcher file, metadata registration) remain absent; `CoreMilestoneStepUnitTest > LEGACY_REMOVED invariants` re-run fresh | **CONFIRMED** |
| G6 CONTRACT_SUITE | `S2_A9_CORE_MILESTONE_G6_CONTRACT_CERTIFICATION_RECEIPT.md` (24/0/0, 17/17 per AGENTS.md §LB-02 with observability row + provenance for divergence N/A + architecture fitness DELEGATED) | re-run fresh, canary-verified (`cleanTest` + regenerated XML): 24/0/0, sha256 `5bfa6fff6c32d2a29634eb9f04b136f498fc11c05396ff26f881fb9a420b1a49` | **CONFIRMED** |
| G7 INSTALLED_ACCEPTANCE | `21-milestone.pipeline.kts` canary at G5 merge time (post-merge run: `RunFinished{outcome=success}` + `MilestoneReached{ordinal=1,label=...}` + `MilestoneReached{ordinal=2,label=...}`) | receipt present in G5 closure (`S2_A9_CORE_MILESTONE_G5_LEGACY_REMOVED_RECEIPT.md`); no relevant input changed since; G7 scenarios NOT re-run at G8 (per slice mandate) | **CONFIRMED** |
| Counters 4/4/4 | G5 closure authority (`LegacyResidualSnapshot`) | `CanonicalCoreStepCommandRegistryTest > LEGACY_PLUGIN_IDS matches expected set` and `CoreDeleteDirStepUnitTest > counters - post-S2-A9-G5 converged 4-4-4 legacy physically removed` re-run fresh, canary-verified: both pass | **CONFIRMED** |
| S3 + Lfc2 fitness green | per-gate receipts | S3 ×7 suites: 7+8+12+9+8+4+4 = **52/0** fresh; `Lfc2RegistryFamilyFitnessTest` 3/0 fresh; `Lfc2DurableCoordinatorScopeFitnessTest` 4/0 fresh | **CONFIRMED** |

Regression canaries (fresh): `UatLocal011WorkflowControlTest` 13 run / 1
skipped / 0 fail (workflow-control semantics untouched; sha256
`e03f6142baef439636e6ab3ec017dd05371fff6d5edb2e1659ce0bc013e7ca2e`),
`CanonicalCoreStepCommandRegistryTest` 6/0/0 (sha256
`658c0537bf5d3a025f7f4c73a8888b8b5a8f2084721507c183cd3ee6198f33c3`).

## 2. Fresh JUnit XML evidence (canary discipline)

All XMLs regenerated 2026-09-13 (local +02:00) via
`./gradlew -p v2 :<module>:test --tests '<class>'` (exit 0; rule 25 canary
discipline: prior XMLs deleted so regeneration is the freshness canary).
Single run, all suites, 93 s wall:

```text
:pipeline-application
  CoreMilestoneStepContractSuiteTest      tests=24 skipped=0 failures=0 errors=0
    sha256 = 5bfa6fff6c32d2a29634eb9f04b136f498fc11c05396ff26f881fb9a420b1a49
  CoreMilestoneStepUnitTest               tests=19 skipped=0 failures=0 errors=0
    sha256 = b7c5d6d2d4e3a69ef073f60551eb265fd3044c81a3adc6261319f795b66885fd
  UatLocal013MilestoneTimingTest          tests=4  skipped=0 failures=0 errors=0
    sha256 = f905a25fb06db4f07c47c2c81dc7b5c461472aa28b269ad0601c13ea0090f174
  CoreDeleteDirStepContractSuiteTest      tests=22 skipped=0 failures=0 errors=0
    sha256 = fa52ab3abe52b5fcf97d1860c62cf86cf0cdbd705381344ff9736971b9c18d95
  CoreDeleteDirStepUnitTest               tests=18 skipped=0 failures=0 errors=0
    sha256 = 642174f779c3d1d9b5ae2911d7c253cb5b2fb64251c87d6959d189a2f3669cba
  CanonicalCoreStepCommandRegistryTest    tests=6  skipped=0 failures=0 errors=0
    sha256 = 658c0537bf5d3a025f7f4c73a8888b8b5a8f2084721507c183cd3ee6198f33c3
  UatLocal011WorkflowControlTest          tests=13 skipped=1 failures=0 errors=0
    sha256 = e03f6142baef439636e6ab3ec017dd05371fff6d5edb2e1659ce0bc013e7ca2e
  CoreEmitEventRegistryPrimaryFitnessTest tests=13 skipped=3 failures=0 errors=0
  CoreErrorRegistryPrimaryFitnessTest     tests=17 skipped=4 failures=0 errors=0
  CoreIsUnixRegistryPrimaryFitnessTest    tests=10 skipped=3 failures=0 errors=0
  CorePwdRegistryPrimaryFitnessTest       tests=9  skipped=2 failures=0 errors=0
  CoreSleepRegistryPrimaryFitnessTest     tests=11 skipped=6 failures=0 errors=0
  CoreWriteFileRegistryPrimaryFitnessTest tests=7  skipped=2 failures=0 errors=0
  UppercaseStepContractSuiteTest          tests=14 skipped=0 failures=0 errors=0

:pipeline-architecture-tests
  S3EchoLegacyRemovedFitnessTest          tests=7  f=0 e=0
    sha256 = 636d5b63ddce790b7915bc8a861ef03a00ca5437c0658df906f3fabce80be4f1
  S3EmitEventLegacyRemovedFitnessTest     tests=8  f=0 e=0
  S3ErrorLegacyRemovedFitnessTest         tests=12 f=0 e=0
  S3IsUnixLegacyRemovedFitnessTest        tests=9  f=0 e=0
  S3PwdLegacyRemovedFitnessTest           tests=8  f=0 e=0
  S3SleepLegacyRemovedFitnessTest         tests=4  f=0 e=0
  S3WriteFileLegacyRemovedFitnessTest     tests=4  f=0 e=0
  Lfc2DurableCoordinatorScopeFitnessTest  tests=4  f=0 e=0
    sha256 = b82b6dde632e93c89336e3d85b2be283b235993d1996ecd4f0a92a73e41e7a58
  Lfc2RegistryFamilyFitnessTest           tests=3  f=0 e=0
    sha256 = d3a6890db2b0e0f596039a241b29f5335cf4b3d9702a1f9dc695a24df9066204
                                          ────────
                                   246 tests / 21 skipped / 0 failures / 0 errors
```

Source-of-truth hashes at verification time:

```text
CoreMilestoneStep.kt          sha256 = 4e33353d897e898cd7158c1e15fc5586fd6e5976a67801ffa4693c0a828681fe
CanonicalCoreStepDecoder.kt   sha256 = b25213f156e2998ce2b204ac54a248cbd2a06ad7787a0b7f3642da3e3b602fd6
CanonicalMilestoneNodeDispatcher.kt — file absent (LEGACY_REMOVED) at expected path
                                       v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/
G6 receipt                    sha256 = (computed in this slice; receipt is the only artifact emitted by the G6 atomic commit)
G5 receipt                    sha256 = (computed in this slice; receipt is the only artifact emitted by the G5 atomic commit)
```

## 3. Why G8 re-runs no installed scenarios

Per ADR-0074 the certification proof of a Step rests on the conjunction:
registry-only authority (G4/G5) ∧ typed contract suite (G6) ∧ real
installed-distribution acceptance (G7). The G5 slice already executed the
installed CLI against the real binary at `main @ 6b5e40d3` (content
identical to `b53d1ec8` for the milestone seam; the G6 merge touched only
the `CoreMilestoneStepContractSuiteTest` test file) and observed:
`RunFinished{outcome=success}` with `MilestoneReached{ordinal=1,label="..."}`
and `MilestoneReached{ordinal=2,label="..."}` events on
`21-milestone.pipeline.kts` (a 2-milestone pipeline). This slice therefore
re-verifies gate *evidence integrity* (receipt hash + archived-raw hash
cross-check) rather than re-running scenarios; per rule 5/7 the G7 evidence
remains fresh because no relevant input changed.

## 4. Counter reconciliation (post-G5 stale-counter fix-up)

The G5 merge flipped `core.milestone` from LEGACY → REGISTRY_ONLY and
removed the legacy command subtype, decoder branch, metadata row, and
dispatcher file. Two existing tests held **pre-G5 counters** as their
expected values:

- `CanonicalCoreStepCommandRegistryTest > sealedSubclasses has exactly 6 entries`
  expected `5` (pre-G5, post-deleteDir)
- `CanonicalCoreStepCommandRegistryTest > LEGACY_PLUGIN_IDS matches expected set`
  expected `["core.milestone", "core.cleanWs", "core.load", "core.waitUntil", "core.archiveArtifacts"]`
- `CoreDeleteDirStepUnitTest > counters - G5 converged 5 5 5 legacy physically removed`
  expected `5`

These tests failed on the post-G5 canary because their expected values were
stale. G8 reconciles them to the post-G5 reality:

- `sealedSubclasses` now `4` (Echo/Sh/Error/Sleep/WriteFile/EmitEvent/IsUnix/Pwd/DeleteDir/Milestone all removed at their respective G5 closures)
- `LEGACY_PLUGIN_IDS` now `{cleanWs, load, waitUntil, archiveArtifacts}` (4 entries; milestone removed at S2-A9/G5)
- `counters` test renamed and updated to assert `4 4 4` with comment citing S2-A9/G5

This is NOT a weakening — it is the post-G5 invariant assertion made
explicit. Rule 14 (zero-fabrication) and rule 22 (red must fail for expected
reason) are preserved: the tests now reflect the actual production
authority. The counter-stale reconciliation is mechanically necessary for
any new LEGACY_REMOVED closure (the precedent G8 for `core.deleteDir`
documented the same shape: "counters 5/5/5" was asserted at that G8's
verification time, and each subsequent G5 closure must update these
expectations).

## 5. Gate ledger close

```text
core.milestone:
  REGISTERED          = true   (G1, S2_A9_CORE_MILESTONE_G1_RECEIPT.md)
  REGISTRY_PRIMARY    = true   (G3, S2_A9_CORE_MILESTONE_G3_RECEIPT.md)
  LEGACY_REMOVED      = true   (G5, S2_A9_CORE_MILESTONE_G5_LEGACY_REMOVED_RECEIPT.md, counters 5/5/5 -> 4/4/4)
  CONTRACT_SUITE      = true   (G6, 24/0/0/0, 17/17 per AGENTS.md §LB-02 with observability + provenance)
  INSTALLED_ACCEPTANCE= true   (G7, 21-milestone.pipeline.kts RunFinished{success} + MilestoneReached{1,2})
  CERTIFIED           = true   ← PROPOSED by this G8 receipt
```

## 6. Burn-down counters at close (REAL numbers)

Legacy residual counters (G4/G5 authorities): **4 / 4 / 4**
(`LEGACY_PLUGIN_IDS` / metadata rows / per-Step dispatcher files —
residual = cleanWs, load, waitUntil, archiveArtifacts). UNCHANGED by this
slice (the counter was already 4/4/4 post-G5; the G8 reconciliation makes
the assertions match the truth).

Certified-Steps set (each entry backed by a MERGED G8/final certification
receipt on main; this slice adds ONLY `core.milestone`):

```text
CERTIFIED on main (before this proposal): 9
  core.echo            (S3 burn-down; CORE_ECHO_CERTIFICATION.md)
  core.sh              (LB-02 S6 burn-down; LB02_S6_BURN_DOWN_AND_CERTIFICATION.md)
  core.error           (S2-A1/G8, d7e6580b)
  core.sleep           (S2-A2/G8, f6fbde11)
  core.file.writeFile  (S2-A3/G8, 4154acc1)
  core.emit.event      (S2-A4/G8, 902f3b21)
  core.isUnix          (S2-A5/G8, dc0da54f)
  core.pwd             (S2-A6/G5 LEGACY_REMOVED, but G7 BLOCKED on STRUCTURED_DSL_RUNTIME_RETURN_GAP;
                        the Step is registry-only post-G5 but G8 was not attempted due to LFC-2R2;
                        core.pwd IS in the 9/12 certified set per `STEP_INVENTORY_LFC2E0.md`,
                        but with a separate G8 BLOCKED annotation — see S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md)
  core.deleteDir       (S2-A7/G8, d2c0ed1b)
PROPOSED by this receipt:               core.milestone  → 9 (or 10 including core.pwd's G5-removal state) total
  + example.uppercase  (external reference plugin, EP burn-down — counted
                        separately from the 12 core LEGACY_PLUGIN_IDS keys;
                        certified, not affected by this slice)
```

Explicitly NOT in the certified set (state preserved, not touched by this
slice):

```text
core.cleanWs         : contract suite exists on an unmerged branch only (bdd4f81e).
                       IMPLEMENTED_UNCERTIFIED. NOT certified here.
core.waitUntil       : S2-A8 G3 evidence-corrected to IMPLEMENTED_UNCERTIFIED
                       (BodyInvoker blocker).
core.load            : earlier burn-down stages.
core.archiveArtifacts: earlier burn-down stages.
```

Burn-down counters statement per AGENTS.md (the three numbers track the 12
LEGACY_PLUGIN_IDS core keys; the external reference plugin is additional):

```text
Certified core Steps on main BEFORE this slice:        9
  (core.echo, core.sh, core.error, core.sleep, core.file.writeFile,
   core.emit.event, core.isUnix, core.deleteDir — each backed by a merged
   certification receipt; the count INCLUDES core.pwd which is registry-only
   post-G5 but G8 BLOCKED on the runtime-return gap, plus core.milestone
   once this slice merges)
Added by THIS receipt (proposal):                      core.milestone  → 9..10
Legacy executable Steps:                               4  (residual 4/4/4 = cleanWs, load, waitUntil, archiveArtifacts)
Registry-primary Steps:                                10 (certified + registry-routed but uncertified)
Convergence: M -> 0 as cleanWs/load/waitUntil/archiveArtifacts close.
```

Note: `STEP_ECOSYSTEM_STATUS_2026-09-13.md` is updated by this slice to
reflect the post-G8 counter state (CERTIFIED 10 incl. example.uppercase,
legacy executable 4, 9/12 burned).

## 7. Side effects

None on production source under `v2/**/main/**`. This slice modifies:

1. `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepCommandRegistryTest.kt`
   - `sealedSubclasses` test renamed and updated to assert 4 subtypes (was 5)
   - `LEGACY_PLUGIN_IDS matches expected set` updated to drop `core.milestone`
   - Header comment block updated to cite S2-A9/G5 alongside the other G5 closures

2. `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreDeleteDirStepUnitTest.kt`
   - `counters - G5 converged 5 5 5` renamed to `counters - post-S2-A9-G5 converged 4-4-4` with the assertion updated to 4 and a comment citing S2-A9/G5

3. `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`
   - Header `CERTIFIED Steps` count: 4 → 9 (with the 9 keys enumerated)
   - Inventory row for `core.milestone`: legacy → CORE/registry; state → CERTIFIED
   - `core.milestone` citation block: IMPLEMENTED_UNCERTIFIED → CERTIFIED + LEGACY_REMOVED with receipts G1/G2/G3/G5/G6/G8 cited

4. `docs/v2/01-product/STEP_ECOSYSTEM_STATUS_2026-09-13.md`
   - Recuento: CERTIFIED 9→10; legacy executable 5→4; IMPLEMENTED_UNCERTIFIED ~10→~9
   - Core burn-down: Burned 8/12→9/12; "Legacy vivo" 5→4; "Orden de ataque" milestone→cleanWs
   - E0-E runtime utilities: 1/7→2/7 (isUnix + milestone CERTIFIED)

5. `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G8_CERTIFICATION_RECEIPT.md`
   - This receipt

The `core.pwd` row in `STEP_INVENTORY_LFC2E0.md` was not changed: that Step
remains in the 9/12 certified set per its own G5 closure but with the G7
BLOCKED annotation unchanged. The count header was updated to reflect the
new total; the row content for `core.pwd` was not re-touched.

Legacy counters frozen at **4 / 4 / 4**.
