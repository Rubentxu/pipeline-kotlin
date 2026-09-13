# S2-B10 / G6 Contract Certification — core.archiveArtifacts Receipt

> **Lane A — sole global writer of authority for S2-B10 `core.archiveArtifacts`.**
> **G6 = contract suite certifies the Step against the AGENTS.md 17/17 coverage matrix.**
>
> G6 is a **coverage-matrix re-shape + provenance documentation** gate, not a code gate.
> No production source changed. No test added or removed. The 27 tests of the G3 baseline
> are preserved verbatim; what changes is the traceability of the matrix they implement.

**Step under burn-down:** `core.archiveArtifacts` (ARTIFACT_ARCHIVE_OPERATIONS capability)
**Gate closed:** G6 contract certification (coverage matrix re-shape + divergence traceability)
**Base SHA (`origin/main`, G5 merged):** `00416000bb4836333b6b1e413bbf0ed1dafe1daf`
**Branch:** `cycle/lfc2-e1-archive-artifacts-g6`
**G6 commit (this receipt):** pending
**Date:** 2026-09-13
**Authority:** docs/v2/ (ROADMAP + STEP_CONSTITUTION + STEP_PLUGIN_CERTIFICATION + ADRs 0070..0075); not `:pipeline-steps-system:compiler-plugin`.

---

## 1. What G6 changed (coverage matrix re-shape — no production, no test semantics)

One file modified, comment-only:

```text
 v2/pipeline-application/src/test/kotlin/.../CoreArchiveArtifactsStepContractSuiteTest.kt
```

Three documentation defects were closed. Each was a real traceability gap introduced by
the natural progression of the lane (G3 wrote the matrix; G4 and G5 invalidated parts of it).

### 1.1 Row 12 (divergence) pointed at a DELETED suite

The G3 header read:

```text
 12.  divergence   COVERED BY G2 — see `CoreArchiveArtifactsDifferentialContractTest`
                   (10 differential rows; the deliberate divergences D1..D5 are frozen there …)
```

`CoreArchiveArtifactsDifferentialContractTest` was **deleted at G5** (the legacy leg was
physically removed; there was nothing left to compare). The matrix therefore pointed at a
file that no longer exists — a broken traceability edge, and precisely the "receipt sentence
is not a gate" failure mode this lane has been correcting.

**G6 does NOT reintroduce the differential suite.** It replaces the dangling pointer with a
**live authority map**: each frozen delta now names the test that currently proves it.

### 1.2 Row 17 pinned the superseded G4 counter state

The G3 header read `G4 counters invariant (2/3/3) + routing (17b/17c)`. Post-G5 the state is
`2/2/2` (converged). Re-shaped to the G5 invariant.

### 1.3 Row 14 (architecture fitness) under-declared the evidence

Listed six `S3*` suites (there are seven) and recorded its evidence in the G3 readiness
receipt rather than naming the current live suites. Re-shaped to a `DELEGATED` row with the
actual suite set.

---

## 2. Coverage matrix — post-G6 shape (17/17)

```text
 1.  identity                                              REQUIRED
 2.  contract completeness                                 REQUIRED
 3.  input codec round-trip                                REQUIRED
     3a. input codec tolerant defaults (legacy envelope)   REQUIRED
     3b. input codec rejection (foreign envelope kind)     REQUIRED
 4.  output codec round-trip (success)                     REQUIRED
     4a. output codec round-trip (failure variant)         REQUIRED (archiveArtifacts-specific)
     4b. output codec rejection (foreign kind)             REQUIRED
 5.  canonical envelope (byte-identical to compiler        REQUIRED
     lowering) + legacy-decoder rejection lock
 6.  production registry resolution                        REQUIRED
     6a. fresh factory consistency                         REQUIRED
 7.  capability declaration (EXACTLY one)                  REQUIRED
     7a. capability admission (available → Ready)          REQUIRED
     7b. missing capability → Rejected (fail closed)       REQUIRED
     7c. conditional exposure (controlDirRoot=null)        REQUIRED
 8.  success (typed outcome + one ArtifactArchived)        REQUIRED
 9.  typed failure (step's OWN SCRIPT failure, not         REQUIRED
     ENGINE; null encodedOutput)
     9a. thrown-handler negative control → ENGINE          REQUIRED (test law)
10.  fresh durable (1 terminal SUCCEEDED operation)        REQUIRED
11.  replay (MEMOIZED + WRITES_WORKSPACE rerun            REQUIRED
     idempotent)
     11a. replay decision — policy unit property           REQUIRED
12.  divergence                                           REQUIRED — §2.1 live map
13.  observability (StepStarted + StepFinished pair)      REQUIRED
14.  architecture fitness                                 DELEGATED — §2.2
15.  real DSL scenario                                    REQUIRED
16.  ArtifactArchived payload (relPath/sha256/size)       REQUIRED (archiveArtifacts-specific)
17.  G5 LEGACY_REMOVED invariant (2/2/2 converged)        REQUIRED (archiveArtifacts-specific)
     17b. routing — StructuralFamilyResolver → Registry    REQUIRED (runtime seam)
     17c. routing end-to-end — a non-empty archive        REQUIRED (behavioural proof)
         succeeds where the legacy authority failed
```

### 2.1 Row 12 provenance — divergence, live D1..D5 authority map

Unlike `core.cleanWs` (whose G6 precedent declared divergence **N/A** because the handler
has no input-comparison contract), `core.archiveArtifacts` **does** have a frozen
divergence record (G2) with five substantive deltas. Every one retains a live authority:

| Delta | Divergence | Historical authority | **Live authority (post-G5)** |
| --- | --- | --- | --- |
| **D1** | Hand-rolled absolute-anchored glob (structurally non-matching) → certified `AntStyleGlob` | G2 §3 row 1 | **row 17c** (non-empty archive succeeds through production wiring where legacy failed) + `CompatibilityCorpusTest.fixture10SmokeE2E` + `FArchL7AntStyleGlobShapeTest` |
| **D2** | `excludes` silently ignored → applied | G2 §3 row 7 | `CoreArchiveArtifactsStepUnitTest` `handler applies excludes patterns` + `AntStyleGlobTest` (`user excludes filter out specified patterns`, `default excludes verbatim`, `DEFAULT_EXCLUDES has exactly 13 entries`, `defaultExcludes false includes normally-excluded files`) |
| **D3** | Effect `{READ_ONLY}` → `{WRITES_WORKSPACE}` | G2 §3 row 8 | **row 2** (contract completeness pins `WRITES_WORKSPACE`, and the assertion message cites delta D3) + **row 11a** (replay decision on MEMOIZED + WRITES_WORKSPACE) |
| **D4** | Retention spelling `artifacts/` → `artefacts/` | G2 §3 row 9 | **row 8** (archived copy lands in `<controlDirRoot>/artefacts/<runId>/<stage>/…`) + **row 11** (same path on rerun) |
| **D5** | `Files.copy` without `REPLACE_EXISTING` → `REPLACE_EXISTING` | G2 §3 row 10 | **row 11** (rerun is idempotent, byte-identical entries) + **row 11a** |

Historical authority (frozen, not deleted as documentation):
`docs/v2/07-uat/S2_B10_ARCHIVEARTIFACTS_G2_DIFFERENTIAL_CONTRACT_FREEZE.md` §3 frozen matrix
and §4 divergence register. The G2 §2 finding ("the legacy step is structurally
non-functional") remains the reason D1 is a **fix**, not a behaviour change.

In-file provenance comment added at the section marker that carries D1:

```kotlin
// ===== 17c. routing, end-to-end (the behavioural proof; G6 matrix row 17c / D1) =====
//
// S2-B10 / G6 traceability: this row is the LIVE authority for frozen delta D1. The
// suite that owned D1 historically, `CoreArchiveArtifactsDifferentialContractTest`, was
// deleted at G5 when the legacy leg was physically removed (nothing left to compare).
```

### 2.2 Row 14 provenance — architecture fitness, DELEGATED

Independently certified green in the G6 canary (§4). Delegated evidence:

```text
S3*LegacyRemovedFitnessTest (7 suites)                    52/0/0    static source absence
LegacyResidualConvergenceFitnessTest                       3/0/0    assertConverged, 2/2/2
Lfc2RegistryFamilyFitnessTest                              3/0/0    closed structural family
Lfc2DurableCoordinatorScopeFitnessTest                     4/0/0    coordinator scope
FArchL7AntStyleGlobShapeTest                               4/0/0    glob engine shape (D1/D2)
Core*RegistryPrimaryFitnessTest (6 suites)                87/0/39   per-Step counters
AntStyleGlobTest (pipeline-artefacts-local)               14/0/0    excludes semantics (D2)
                                                        ────────
                                                         167/0/39
```

---

## 3. Coverage tally

| Row | Coverage | Test(s) in file | Tests |
| --- | --- | --- | --- |
| 1 | identity | `identity — KEY is core dot archiveArtifacts and duplicate registration fails` | 1 |
| 2 | contract completeness | `contract completeness — key, descriptor, codecs, single capability, WRITES_WORKSPACE, MEMOIZED, None` | 1 |
| 3 | input codec round-trip | `codec input — default input encodes to the canonical envelope and round-trips` | 1 |
| 3a | input codec tolerant defaults | `codec input — decode accepts the legacy envelope without optional fields with tolerant defaults` | 1 |
| 3b | input codec rejection | `codec input — decode rejects a foreign envelope kind` | 1 |
| 4 | output codec round-trip (success) | `codec output — ArchiveArtifactsOutput round-trips byte-identically and survives durable string form` | 1 |
| 4a | output codec (failure variant) | `codec output — ArchiveArtifactsFailureOutput round-trips with its SCRIPT kind and message` | 1 |
| 4b | output codec rejection | `codec output — decode rejects a non-archiveArtifacts kind` | 1 |
| 5 | canonical envelope + decoder rejection lock | `canonical envelope — input codec is byte-identical to the legacy compiler lowering for non-default fields` (carries the G5 `assertThrows` legacy-decoder rejection proof) | 1 |
| 6 | production registry resolution | `registry resolution — production factory contains core dot archiveArtifacts` | 1 |
| 6a | fresh factory consistency | `registry resolution — production factory registry is fresh per call and consistent across calls` | 1 |
| 7 | capability declaration | `capability declaration — core dot archiveArtifacts declares EXACTLY ARTIFACT_ARCHIVE_OPERATIONS` | 1 |
| 7a | capability admission | `capability admission — the capability available prepares Ready` | 1 |
| 7b | missing capability | `missing capability — admission rejects and names the missing capability` | 1 |
| 7c | conditional exposure | `conditional exposure — controlDirRoot null means the capability is absent and admission rejects` | 1 |
| 8 | success | `success — boundary coexecute archives matched files into retention and returns typed success` | 1 |
| 9 | typed failure | `typed failure — an empty match without allowEmptyArchive surfaces as the step's own SCRIPT failure` | 1 |
| 9a | thrown-handler negative control | `typed failure negative control — a thrown handler is re-classified ENGINE by the boundary` | 1 |
| 10 | fresh durable | `fresh durable — first execution of core dot archiveArtifacts writes one terminal SUCCEEDED operation` | 1 |
| 11 | replay | `replay — MEMOIZED archiveArtifacts with WRITES_WORKSPACE reruns idempotently with identical entries` | 1 |
| 11a | replay decision | `replay decision — DefaultEffectReplayPolicy reruns MEMOIZED WRITES_WORKSPACE with a SUCCEEDED entry` | 1 |
| 12 | divergence | **DELEGATED — §2.1 live D1..D5 map** (no in-file row; by construction, exceeding it would reintroduce the deleted differential leg) | 0 |
| 13 | observability | `observability — every core dot archiveArtifacts run emits a StepStarted StepFinished pair` | 1 |
| 14 | architecture fitness | **DELEGATED — §2.2** | 0 |
| 15 | real DSL scenario | `real DSL scenario — pipeline DSL lowers archiveArtifacts and the run succeeds through the canonical spine` | 1 |
| 16 | ArtifactArchived payload | `ArtifactArchived payload — relPath sha256 size and archivedAt are present and self-consistent` | 1 |
| 17 | G5 LEGACY_REMOVED invariant | `G5 LEGACY_REMOVED invariant — core dot archiveArtifacts physical forms destroyed and counters are 2 2 2` | 1 |
| 17b | routing (runtime seam) | `G4 routing — StructuralFamilyResolver classifies core dot archiveArtifacts as Registry` | 1 |
| 17c | routing end-to-end | `G4 routing end-to-end — a non-empty archive succeeds through production wiring where legacy failed` | 1 |

**Tally: 15 REQUIRED matrix rows test-backed in-file (15 tests) + 12 sub-rows (12 tests)
= 27 tests; 2 DELEGATED rows (12, 14) with independent live authority. 17/17 coverage.**

```text
matrix rows          17
  test-backed        15  (rows 1..11, 13, 15..17)
  DELEGATED           2  (rows 12 divergence, 14 architecture fitness)
  N/A                 0  (contrast: cleanWs G6 had a genuine N/A divergence row)
in-file tests        27  = 15 rows + 12 sub-rows (3a,3b,4a,4b,6a,7a,7b,7c,9a,11a,17b,17c)
```

The test count is **unchanged from the G3 baseline (27)** — G6 adds no test and removes none.

---

## 4. Canary (L1 — fresh XML evidence at `00416000` + G6 doc edit)

### 4.1 L0 — compile

```text
timeout 600 ./v2/gradlew -p v2 :pipeline-application:compileTestKotlin
BUILD SUCCESSFUL in 3s   (37 actionable tasks: 1 executed, 36 up-to-date)
```

### 4.2 L1 — 26 test classes across 3 modules (318/0/0/51)

XML canary: every `TEST-*.xml` in the three modules was **deleted before the run** and all
26 regenerated (timestamps `2026-09-13T14:25:30Z`..`14:25:36Z`).

```bash
timeout 900 ./v2/gradlew -p v2 \
  :pipeline-application:test \
    --tests CoreArchiveArtifactsStepContractSuiteTest \
    --tests CoreArchiveArtifactsStepUnitTest \
    --tests CoreCleanWsStepContractSuiteTest \
    --tests EmitEventStepContractSuiteTest \
    --tests CoreDeleteDirStepUnitTest \
    --tests CoreIsUnixStepUnitTest \
    --tests CanonicalCoreStepCommandRegistryTest \
    --tests CoreEmitEventRegistryPrimaryFitnessTest \
    --tests CoreErrorRegistryPrimaryFitnessTest \
    --tests CoreIsUnixRegistryPrimaryFitnessTest \
    --tests CorePwdRegistryPrimaryFitnessTest \
    --tests CoreSleepRegistryPrimaryFitnessTest \
    --tests CoreWriteFileRegistryPrimaryFitnessTest \
    --tests 'CompatibilityCorpusTest.fixture10SmokeE2E' \
  :pipeline-architecture-tests:test \
    --tests 'S3*LegacyRemovedFitnessTest' \
    --tests Lfc2RegistryFamilyFitnessTest \
    --tests Lfc2DurableCoordinatorScopeFitnessTest \
    --tests LegacyResidualConvergenceFitnessTest \
    --tests FArchL7AntStyleGlobShapeTest \
  :pipeline-artefacts-local:test \
    --tests AntStyleGlobTest
```

```text
BUILD SUCCESSFUL in 8s   (59 actionable tasks: 3 executed, 56 up-to-date)

module      class                                            tests skip fail err
app         CanonicalCoreStepCommandRegistryTest                  4    0    0   0
app         CompatibilityCorpusTest (fixture10SmokeE2E only)      1    0    0   0
app         CoreArchiveArtifactsStepContractSuiteTest            27    0    0   0
app         CoreArchiveArtifactsStepUnitTest                     23    2    0   0
app         CoreCleanWsStepContractSuiteTest                     24    1    0   0
app         CoreDeleteDirStepUnitTest                            21    3    0   0
app         CoreEmitEventRegistryPrimaryFitnessTest              16    6    0   0
app         CoreErrorRegistryPrimaryFitnessTest                  20    7    0   0
app         CoreIsUnixRegistryPrimaryFitnessTest                 13    6    0   0
app         CoreIsUnixStepUnitTest                               21    3    0   0
app         CorePwdRegistryPrimaryFitnessTest                    12    5    0   0
app         CoreSleepRegistryPrimaryFitnessTest                  16   11    0   0
app         CoreWriteFileRegistryPrimaryFitnessTest              10    5    0   0
app         EmitEventStepContractSuiteTest                       30    2    0   0
arch        FArchL7AntStyleGlobShapeTest                          4    0    0   0
arch        LegacyResidualConvergenceFitnessTest                  3    0    0   0
arch        Lfc2DurableCoordinatorScopeFitnessTest               4    0    0   0
arch        Lfc2RegistryFamilyFitnessTest                        3    0    0   0
arch        S3EchoLegacyRemovedFitnessTest                        7    0    0   0
arch        S3EmitEventLegacyRemovedFitnessTest                  8    0    0   0
arch        S3ErrorLegacyRemovedFitnessTest                     12    0    0   0
arch        S3IsUnixLegacyRemovedFitnessTest                     9    0    0   0
arch        S3PwdLegacyRemovedFitnessTest                        8    0    0   0
arch        S3SleepLegacyRemovedFitnessTest                      4    0    0   0
arch        S3WriteFileLegacyRemovedFitnessTest                 4    0    0   0
artefacts   AntStyleGlobTest                                    14    0    0   0
                                                                ───  ──   ──  ──
                                                    26 classes  318  51    0   0
```

XML sha256 (prefix 16) — full set persisted in
`docs/v2/07-uat/evidence/s2-b10-g6/g6-canary-xml-sha256.txt`:

```text
7beb6ca9ef3b40cd  CanonicalCoreStepCommandRegistryTest.xml
ad49369c0486eb7f  CompatibilityCorpusTest.xml
cd260db7161ca32d  CoreArchiveArtifactsStepContractSuiteTest.xml
a75bffdb4e029f7e  CoreArchiveArtifactsStepUnitTest.xml
f17762681ff5381d  CoreCleanWsStepContractSuiteTest.xml
7fa2f6436e80f1f1  CoreDeleteDirStepUnitTest.xml
fce94de8a850a686  CoreEmitEventRegistryPrimaryFitnessTest.xml
6c68ba4192cd7bca  CoreErrorRegistryPrimaryFitnessTest.xml
1627cfcbe6cf98f5  CoreIsUnixRegistryPrimaryFitnessTest.xml
de6a81d09b7cddcc  CoreIsUnixStepUnitTest.xml
de9bf3ba1beb7f58  CorePwdRegistryPrimaryFitnessTest.xml
3e49c4bafbac0572  CoreSleepRegistryPrimaryFitnessTest.xml
b9c13ef06e23f746  CoreWriteFileRegistryPrimaryFitnessTest.xml
4c39bc0b80853a43  EmitEventStepContractSuiteTest.xml
56112e281c334384  FArchL7AntStyleGlobShapeTest.xml
d7f6d31431c3e2c2  LegacyResidualConvergenceFitnessTest.xml
d143ee79a0bef83f  Lfc2DurableCoordinatorScopeFitnessTest.xml
d136b97891d8b464  Lfc2RegistryFamilyFitnessTest.xml
7b0e7ab2299176d0  S3EchoLegacyRemovedFitnessTest.xml
8db1e778601d17d2  S3EmitEventLegacyRemovedFitnessTest.xml
1702e331520aa8eb  S3ErrorLegacyRemovedFitnessTest.xml
0d8924e2ccee814c  S3IsUnixLegacyRemovedFitnessTest.xml
849ea9d83065c210  S3PwdLegacyRemovedFitnessTest.xml
9714192b2aae0b12  S3SleepLegacyRemovedFitnessTest.xml
e1a85c05983ce551  S3WriteFileLegacyRemovedFitnessTest.xml
a8b194975740cfcb  AntStyleGlobTest.xml
```

`CoreArchiveArtifactsStepContractSuiteTest` is **27/0/0**, identical to the G3 baseline:
the G6 edit is comment-only and changed no test semantics.

### 4.3 A note on the 51 skips

The skips are the G5 sweep's `@Disabled` historical gate snapshots, preserved verbatim by
design (`CoreSleepRegistryPrimaryFitnessTest` 11, `CoreErrorRegistryPrimaryFitnessTest` 7,
`CoreEmitEvent`/`CoreIsUnix` 6 each, `CorePwd`/`CoreWriteFile` 5 each,
`CoreDeleteDir`/`CoreIsUnixStepUnitTest` 3 each, `CoreArchiveArtifactsStepUnitTest` 2,
`CoreCleanWsStepContractSuiteTest`/`EmitEventStepContractSuiteTest` 1 each). Each carries a
`@Disabled` reason naming the superseding row and the base-SHA evidence. They are historical
record, not coverage.

### 4.4 Pre-existing reds NOT introduced by G6

Unchanged from the G5 baseline: **36** in `:pipeline-application:test` and **1** in
`:pipeline-architecture-tests:test` (`Lfc0GlobalStateFitnessTest`, `Capabilities.kt:76`).
G6 is comment-only; no test behaviour changed, so the pre-existing baseline is preserved by
construction. Neither module was re-run in full for G6 — the G5 full-module receipts at
`00416000` remain the authority for those numbers and are not stale (no production or
non-comment test change occurred).

---

## 5. Scope compliance

| Rule | Status |
| --- | --- |
| No production change | ✅ one test file, comment-only |
| Do NOT reintroduce the deleted differential suite | ✅ deleted file stays deleted; replaced by a live authority map |
| No new tests | ✅ 27 tests before and after |
| Traceability to G2 as historical divergence evidence | ✅ §2.1 + in-file provenance comment at marker 17c |
| Current ContractSuite as living authority | ✅ §2.1 and §3 map every row to a live test name |
| Zero fabrication | ✅ all counts from fresh XML at `00416000` + G6 edit; canary verified |

---

## 6. Counters and certification status

| Item | Post-G5 | Post-G6 |
| --- | --- | --- |
| Certified Steps | unchanged | **unchanged** |
| Legacy executable Steps | 2 (`core.load`, `core.waitUntil`) | **2** (no change) |
| Residual authority convergence | `2 / 2 / 2` | **`2 / 2 / 2`** (no change) |
| Contract Suite coverage shape | G3/G4 ad-hoc (dangling row 12, stale row 17) | **G6: 17/17, 15 test-backed + 2 DELEGATED** |
| Contract suite tests | 27 | **27** (unchanged) |

**`core.archiveArtifacts` state: `IMPLEMENTED_UNCERTIFIED`.** G6 closes the *contract
certification* gate; per ADR-0074 the Step is not `CERTIFIED` until G7 (installed
acceptance) and G8 (certification) complete. This receipt deliberately does **not** record
`DONE`/`CERTIFIED`.

---

## 7. Branch precondition verified

```text
branch          = cycle/lfc2-e1-archive-artifacts-g6
branch.base     = 00416000bb4836333b6b1e413bbf0ed1dafe1daf
origin/main     = 00416000bb4836333b6b1e413bbf0ed1dafe1daf   (G5 merged via PR #42, FF)
```

Unlike the cleanWs lane (where G4/G5/G6 stacked unmerged on one branch), this lane's G5 is
already trunk truth, so G6 branches directly from `origin/main`. The gate sequence therefore
reads strictly one-gate-per-merge.

---

## 8. Gate progression

```text
S2-B10/G0 classification        DONE
S2-B10/G1 registry seam         DONE  (prior lane)
S2-B10/G2 differential freeze   DONE
S2-B10/G3 readiness + suite     DONE
S2-B10/G4 REGISTRY_PRIMARY      DONE  (435f5f8b, PR #41, FF-merged)
S2-B10/G5 LEGACY_REMOVED        DONE  (00416000, PR #42, FF-merged)
S2-B10/G6 contract certification THIS RECEIPT
S2-B10/G7 installed acceptance   next — STOP awaiting GO
S2-B10/G8 CERTIFIED              final
```

G7 scope (precedent `core.cleanWs` G7): the installed-CLI proof that `core.archiveArtifacts`
executes through the open registry path under
`v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application`, for
both a non-empty match (`ArtifactArchived` with a byte-identical payload hash to the G4/G5
captures, already demonstrated in the G5 receipt) and the empty-match failure path.

---

## 9. References

- `docs/v2/07-uat/evidence/s2-b10-g6/` — G6 canary XML sha256 set
- `docs/v2/07-uat/S2_B10_ARCHIVEARTIFACTS_G2_DIFFERENTIAL_CONTRACT_FREEZE.md` — historical divergence authority (D1..D5, §2 non-functional-legacy finding)
- `docs/v2/07-uat/S2_B10_ARCHIVEARTIFACTS_G5_LEGACY_REMOVED_RECEIPT.md` — direct predecessor
- `docs/v2/07-uat/S2_B10_ARCHIVEARTIFACTS_G4_REGISTRY_PRIMARY_RECEIPT.md`
- `docs/v2/07-uat/S2_A10_CORE_CLEANWS_G6_CONTRACT_CERTIFICATION_RECEIPT.md` — G6 precedent (and the cleanWs N/A divergence contrast)
- `v2/pipeline-application/src/test/kotlin/.../CoreArchiveArtifactsStepContractSuiteTest.kt`
- **STEP_CONSTITUTION / STEP_PLUGIN_CERTIFICATION**; **ADR-0070..0075**

---

**Receipt author:** Jcode (Lane A sole global writer for S2-B10)
**STOP awaiting GO for S2-B10/G7 installed acceptance.**
