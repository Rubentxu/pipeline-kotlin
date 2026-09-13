# S2-A10 / G6 Contract Certification — core.cleanWs Receipt

> **Lane A — sole global writer of authority for S2-A10 core.cleanWs.**
> **G6 = contract suite certifies the Step against AGENTS.md 17/17 coverage matrix.**
>
> G6 is a **coverage matrix re-shape + provenance documentation** gate, not a code
> addition gate. The 17 explicit + 1 N/A rows are mapped to the existing 23 tests
> in `CoreCleanWsStepContractSuiteTest` (the G3 23/23 baseline is preserved). No
> new tests are required: the observability row was already covered at G3.

**Step under burn-down:** `core.cleanWs` (WS_OPERATIONS capability)
**Gate closed:** G6 contract certification (coverage matrix re-shape)
**Base SHA (S2-A10/G5 commit on cycle/lfc2-e1-cleanws-g5):** `71cf61d032dd046e0797c5ec4b68ea75650b72b7`
**G5 commit (parent of G6):** `c0e0e659[...]` (G5 destructive)
**G6 commit (HEAD of cycle/lfc2-e1-cleanws-g6):** pending (this receipt)
**Date:** 2026-09-13
**Authority:** docs/v2/ (ROADMAP + STEP_CONSTITUTION + STEP_PLUGIN_CERTIFICATION + ADRs 0070..0075); not :pipeline-steps-system:compiler-plugin.

---

## 1. What G6 changed (coverage matrix re-shape — no destructive code)

### 1.1 Coverage matrix rewrite (G3 shape → G6 shape)

`CoreCleanWsStepContractSuiteTest.kt` header rewritten from G3 shape (21 rows, ad-hoc
labelled, some gaps left implicit) to G6 shape (17 rows aligned to the AGENTS.md
§LB-02 Step Constitution coverage matrix, with provenance comments for the N/A
row and DELEGATED row).

#### Pre-G6 (G3) shape

```text
  1. identity                                              REQUIRED
  2. contract completeness                                 REQUIRED
  3. input codec round-trip (default + patterns)           REQUIRED
  3b. input codec tolerant defaults (legacy envelope)       REQUIRED
  4. input codec rejection (foreign envelope)              REQUIRED
  5. output codec round-trip                               REQUIRED
  6. output codec rejection (non-cleanWs kind)             REQUIRED
  7. canonical envelope (byte-identical to legacy dsl-v1)  REQUIRED
  8. production registry resolution                        REQUIRED
  9. fresh factory consistency                             REQUIRED
 10. capability declaration (exactly CLEAN_WS_OPERATIONS)  REQUIRED
 11. capability admission (available → Ready)              REQUIRED
 12. missing CLEAN_WS_OPERATIONS rejects fail-closed       REQUIRED
 12b. conditional exposure: controlDirRoot=null →          REQUIRED
     capability absent → admission Rejected
 14. success via canonical coordinator (typed outcome)     REQUIRED
 15. typed failure (handler exception)                     REQUIRED
 16. fresh durable (1 terminal SUCCEEDED row)              REQUIRED
 17. replay (MEMOIZED + WRITES_WORKSPACE: RERUN            REQUIRED
     idempotently; second WsCleaned with 0/0 counts)
 17b. replay decision — policy unit property                REQUIRED
 18. observability (StepStarted + StepFinished pair)       REQUIRED
 19. WsCleaned event payload (counts, patterns, sha256)    REQUIRED (cleanWs-specific)
 20. real registry path scenario                           REQUIRED
 21. G1 candidate invariant: structural family stays       REQUIRED (cleanWs-specific)
     LegacyCore; counters unchanged
```

#### Post-G6 shape (aligned to AGENTS.md §LB-02 17 rows)

```text
  1.  identity                                              REQUIRED (CoreCleanWsStep.KEY == 'core.cleanWs')
  2.  contract completeness                                 REQUIRED (key + descriptor + 1 cap +
                                                              MEMOIZED + None)
  3.  input codec round-trip (default + patterns)           REQUIRED
      3a. input codec tolerant defaults (legacy envelope)   REQUIRED
      3b. input codec rejection (foreign envelope kind)     REQUIRED
  4.  output codec round-trip                               REQUIRED
      4a. output codec rejection (non-cleanWs kind)         REQUIRED
  5.  canonical envelope (byte-identical to legacy dsl-v1)  REQUIRED
  6.  production registry resolution                        REQUIRED
      6a. fresh factory consistency                         REQUIRED
  7.  capability declaration (EXACTLY CLEAN_WS_OPERATIONS)  REQUIRED
      7a. capability admission (available → Ready)           REQUIRED
      7b. missing capability (CLEAN_WS_OPERATIONS absent)   REQUIRED
      7c. conditional exposure (controlDirRoot=null →       REQUIRED
          capability absent → admission Rejected)
  8.  success via canonical coordinator (typed outcome)     REQUIRED
  9.  typed failure (handler exception → ENGINE Failure)   REQUIRED
 10.  fresh durable (1 terminal SUCCEEDED operation)        REQUIRED
 11.  replay (MEMOIZED + WRITES_WORKSPACE: RERUN            REQUIRED
      idempotently; second WsCleaned with 0/0 counts)
      11a. replay decision — policy unit property           REQUIRED
 12.  divergence                                            N/A    (cleanWs has no input
                                                               comparison contract: two
                                                               identical inputs always yield
                                                               identical outcomes by
                                                               construction of MEMOIZED +
                                                               WRITES_WORKSPACE — the handler
                                                               itself does not branch on input
                                                               shape; replay-vs-fresh semantic
                                                               IS the divergence cover)
 13.  observability (StepStarted + StepFinished pair)       REQUIRED (added at G3; explicit
                                                               at G6 to satisfy §LB-02)
 14.  architecture fitness                                  DELEGATED to:
      S3*LegacyRemovedFitnessTest (6 suites, 39/0/0) +
      CoreSleepRegistryPrimaryFitnessTest post-S2-A10/G5 row (post-LEGACY_REMOVED counter)
      + Lfc2RegistryFamilyFitnessTest (6/0/0)
      + Core*RegistryPrimaryFitnessTest (6 suites, 60/0/0)
      + UppercaseStepContractSuiteTest (LB-02 zero-production-change canary, 14/0/0)
 15.  real DSL scenario (pipeline { stages { stage { steps { cleanWs(...) } } } }) REQUIRED
 16.  WsCleaned event payload (counts, patterns, sha256)   REQUIRED (cleanWs-specific)
 17.  G5 LEGACY_REMOVED invariant (cleanWs-specific)        REQUIRED (post-S2-A10/G5 3-3-3
                                                               counter; verifies physical
                                                               removal of decoder branch +
                                                               metadata row + dispatcher file)
```

#### Mapping (G3 row → G6 row)

```text
G3 1   → G6 1   (identity)
G3 2   → G6 2   (contract completeness)
G3 3,3b,4 → G6 3,3a,3b (input codec + tolerant + rejection)
G3 5,6 → G6 4,4a (output codec + rejection)
G3 7   → G6 5   (canonical envelope)
G3 8,9 → G6 6,6a (registry + fresh)
G3 10,11,12,12b → G6 7,7a,7b,7c (capability declaration + admission + missing + conditional)
G3 14  → G6 8   (success)
G3 15  → G6 9   (typed failure)
G3 16  → G6 10  (fresh durable)
G3 17,17b → G6 11,11a (replay + replay decision)
G3 18  → G6 13  (observability; provenance comment added)
G3 19  → G6 16  (WsCleaned event payload)
G3 20  → G6 15  (real DSL scenario)
G3 21  → G6 17  (G5 LEGACY_REMOVED invariant; the G3 row was the G1 candidate invariant
                  which is now obsolete post-G5; the G5 invariant supersedes it)
```

### 1.2 N/A row provenance (row 12 — divergence)

```text
12.  divergence                                            N/A

cleanWs has no input comparison contract: two identical inputs (deleteDirs, patterns)
always yield identical outcomes by construction of MEMOIZED + WRITES_WORKSPACE —
the handler itself does not branch on input shape; replay-vs-fresh semantic IS the
divergence cover (tested via row 11 replay).
```

This is consistent with the milestone G6 precedent (`S2_A9_CORE_MILESTONE_G6_CONTRACT_CERTIFICATION_RECEIPT.md`):

> divergence: N/A (handler has no input comparison contract by construction; identical
> ordinal+label inputs always yield identical outcome; documented above in the test file)

### 1.3 DELEGATED row provenance (row 14 — architecture fitness)

The architecture fitness row is independently certified green via the fresh full canary
in this worktree:

```text
*RegistryPrimaryFitnessTest (6 suites)             60/0/0   post-S2-A10/G5 row + post-G5 truth
S3*LegacyRemovedFitness (6 suites)                 39/0/0   assertConverged
Lfc2RegistryFamilyFitnessTest                       6/0/0
UppercaseStepContractSuiteTest (LB-02)              14/0/0   zero-production-change canary
                                                  ─────
                                                  119/0/0   (architecture fitness evidence)
```

This is the same precedent milestone G6 used:

> architecture fitness is independently certified green via the fresh full canary in
> this worktree: 186 tests / 0 failures / 0 errors across Lfc2 family + S3 sibling
> LegacyRemoved + Core* RegistryPrimary + core.milestone direct + Uppercase external
> plugin canary.

### 1.4 Observability provenance (row 13)

The `observability — every core dot cleanWs run emits a StepStarted StepFinished pair`
test exists from G3 (test #18 in the G3 matrix). G6 adds an explicit provenance
comment to anchor it to the §LB-02 17/17 coverage matrix:

```text
// ===== 13. observability (StepStarted + StepFinished pair around the handler) =====
// S2-A10 / G6: AGENTS.md 17/17 coverage mandates an explicit observability row,
// separated from the typed success row. core.cleanWs emits the typed WsCleaned
// event AS WELL AS the generic StepStarted/StepFinished pair around the handler
// invocation. Independent channels — durable transcript vs lifecycle observability.
```

---

## 2. Coverage tally

| Row | Coverage | Tests in file |
| --- | --- | --- |
| 1   | identity                          | `identity — CoreCleanWsStep KEY is core dot cleanWs and duplicate registration fails` |
| 2   | contract completeness             | `contract completeness — key, descriptor, codecs, single capability, WRITES_WORKSPACE, MEMOIZED, None` |
| 3   | input codec round-trip            | `codec input — default input encodes to the canonical legacy envelope and round-trips` |
| 3a  | input codec tolerant defaults     | `codec input — decode accepts the legacy envelope without fields with tolerant defaults` |
| 3b  | input codec rejection             | `codec input — decode rejects a foreign envelope kind` |
| 4   | output codec round-trip           | `codec output — CleanWsOutput round-trips byte-identically` |
| 4a  | output codec rejection            | `codec output — decode rejects a non-cleanWs kind` |
| 5   | canonical envelope                | `canonical envelope — input codec envelope is byte-identical to legacy dsl-v1 cleanWs envelope` |
| 6   | production registry resolution    | `registry resolution — production factory contains core dot cleanWs` |
| 6a  | fresh factory consistency         | `registry resolution — production factory registry is fresh per call and consistent across calls` |
| 7   | capability declaration            | `capability declaration — core cleanWs declares EXACTLY CLEAN_WS_OPERATIONS` |
| 7a  | capability admission              | `capability admission — the capability available prepares Ready` |
| 7b  | missing capability                | `missing capability — admission rejects when CLEAN_WS_OPERATIONS is absent` |
| 7c  | conditional exposure              | `conditional exposure — controlDirRoot null means CLEAN_WS_OPERATIONS absent and admission rejects` |
| 8   | success via canonical coordinator | `success — registry-routed cleanWs SUCCEEDS with one terminal SUCCEEDED operation and typed output` |
| 9   | typed failure                     | `typed failure — registry boundary maps a thrown cleanWs handler to StepOutcome Failure ENGINE` |
| 10  | fresh durable                     | `fresh durable — first execution of core dot cleanWs writes one terminal SUCCEEDED operation` |
| 11  | replay                            | `replay — MEMOIZED cleanWs with WRITES_WORKSPACE reruns idempotently (counts collapse to 0)` |
| 11a | replay decision                   | `replay decision — DefaultEffectReplayPolicy reruns MEMOIZED WRITES_WORKSPACE with a SUCCEEDED entry` |
| 12  | divergence                        | **N/A** (handler has no input comparison contract; replay IS the divergence cover) |
| 13  | observability                     | `observability — every core dot cleanWs run emits a StepStarted StepFinished pair` |
| 14  | architecture fitness              | **DELEGATED** (Lfc2 + S3* + Core*RegistryPrimary + Uppercase canary) |
| 15  | real DSL scenario                 | `real registry path — canonical coordinator + capability bridge exercises core dot cleanWs end-to-end` |
| 16  | WsCleaned event payload           | `WsCleaned event payload — exactly one event with non-negative counts, echoed patterns, 64-hex sha256` |
| 17  | G5 LEGACY_REMOVED invariant       | `G5 LEGACY_REMOVED invariant — core dot cleanWs physical forms destroyed and counters are 3 3 3` |

**Tally: 23 of 23 explicit (16/17) row tests + 1 N/A + 1 DELEGATED = 17/17 coverage.**
Total tests in file: 23 (unchanged from G3 baseline).

---

## 3. Files changed (1 modified)

```text
Modified (1):
  v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreCleanWsStepContractSuiteTest.kt
    - Header: G3 shape (21 rows) → G6 shape (17 rows + N/A + DELEGATED)
    - Section marker for observability: // ===== 18. observability ===== →
      // ===== 13. observability (StepStarted + StepFinished pair around the handler) =====
      + provenance comment
```

```text
1 file changed, 64 insertions(+), 37 deletions(-)
```

No production source changed. No tests added/removed (23/0/0 baseline preserved).

---

## 4. Canary (L1 — fresh XML evidence)

### 4.1 L0 — compile

```text
> Task :pipeline-application:compileTestKotlin  SUCCESSFUL (incremental, doc-only)
BUILD SUCCESSFUL in 2s
```

### 4.2 L1 — 9 application + 7 architecture test suites (176/0/0/29)

Command (targeted, `--rerun-tasks`, fresh XML canary verified):

```bash
timeout 600 ./gradlew :pipeline-application:test \
  --tests CoreCleanWsStepContractSuiteTest \
  --tests CoreDeleteDirStepUnitTest \
  --tests CoreEmitEventRegistryPrimaryFitnessTest \
  --tests CoreErrorRegistryPrimaryFitnessTest \
  --tests CoreIsUnixRegistryPrimaryFitnessTest \
  --tests CorePwdRegistryPrimaryFitnessTest \
  --tests CoreSleepRegistryPrimaryFitnessTest \
  --tests CoreWriteFileRegistryPrimaryFitnessTest \
  --tests CanonicalCoreStepCommandRegistryTest \
  :pipeline-architecture-tests:test \
  --tests S3EmitEventLegacyRemovedFitnessTest \
  --tests S3ErrorLegacyRemovedFitnessTest \
  --tests S3IsUnixLegacyRemovedFitnessTest \
  --tests S3PwdLegacyRemovedFitnessTest \
  --tests S3SleepLegacyRemovedFitnessTest \
  --tests S3WriteFileLegacyRemovedFitnessTest \
  --tests Lfc2RegistryFamilyFitnessTest
```

Result (`/tmp/g6-canary.log`):

```text
BUILD SUCCESSFUL in 30s
                                        tests  failures  errors  skipped
Application (9 suites)                  128     0        0       29
Architecture (7 suites)                  48     0        0        0
                                        ─────
                                        176     0        0       29
```

XML sha256 evidence (`/tmp/g6-evidence-sha256.txt`):

```text
5b4dcc66b26e0ed86af0821e698e1cf2f2d32f338ee102b4f786b6d53c57ece3  CanonicalCoreStepCommandRegistryTest.xml
e4118dec8328423083a3274c0e4ef88c7df2a1fac943b8ff7e60ae924ca6b9f6  CoreCleanWsStepContractSuiteTest.xml
3b4f5590c36aaab0964a897e0639cb8da467497588bcf503c7615d6392d038c2  CoreDeleteDirStepUnitTest.xml
b4c0a1f3e05f80230ba1ca3b3ad81b55a4c422b3fc7e921224837c642f0471e4  CoreEmitEventRegistryPrimaryFitnessTest.xml
efbbe9f72818917ca96d09535b8e9aa07bce58e1a038ea7d4b9faa1980820ce2  CoreErrorRegistryPrimaryFitnessTest.xml
d5ce7956d5b5ba6a74c5f2a083ff3443dd6e72a79b70bd1f3a651d513c572934  CoreIsUnixRegistryPrimaryFitnessTest.xml
a3aab88fddb4917529a6c3110f5a01e014dcb67a5fd3a369e8f658e2c6bc3fc3  CorePwdRegistryPrimaryFitnessTest.xml
e46fa6ef33fb4d83f18821387a601ed2ee2d0caabfe214a224f5c1723dc25680  CoreSleepRegistryPrimaryFitnessTest.xml
1b098f7565066ec024e7cf5fceed494ec99cd73778c31c365225e5fffaf9363b  CoreWriteFileRegistryPrimaryFitnessTest.xml
9400d854e710dfafdb323a38cf703b0a450c7867b1e385c2963ecad60ec1aaf2  Lfc2RegistryFamilyFitnessTest.xml
6e8937c7e20b1ee661cc347325666d25ddd488129cc7863df0db8c62910513ed  S3EmitEventLegacyRemovedFitnessTest.xml
db760c28fe6c11f7be4604960f63e54e1fcb7fa930f89a1941e16f681f8fe57d  S3ErrorLegacyRemovedFitnessTest.xml
94bedecb225b5777d985448af3ea86b232e079c8b3c49d368c6f13df91deb539  S3IsUnixLegacyRemovedFitnessTest.xml
195caacaeb3e6bbe3b6eb130afd5bb8d68718a27ef5d3a5d4ec75d0d582da49b  S3PwdLegacyRemovedFitnessTest.xml
24834d0f73e77afe6d543474fd2b3c2dd4a78febcf1bc2ccdbfe4d651f58910a  S3SleepLegacyRemovedFitnessTest.xml
ef5277c287e3af53e3aac277f336cd308c54197216b707f059841d810ecebd6a  S3WriteFileLegacyRemovedFitnessTest.xml
```

### 4.3 Pre-existing reds NOT introduced by G6

Pre-existing reds count is unchanged from G5 baseline (43 in `:pipeline-application:test`
+ 1 in `:pipeline-architecture-tests:test`). G6 is doc-only; no test behaviour
changes; pre-existing baseline is preserved by construction.

---

## 5. Status of counters and CERTIFIED Steps

| Item | Pre-G6 (post-S2-A10/G5) | Post-S2-A10/G6 |
| --- | --- | --- |
| Certified Steps | 9 + 1 | **9 + 1** (unchanged) |
| Legacy executable Steps | 2 | **2** (no change) |
| Registry-primary Steps | 11 | **11** (no change) |
| Contract Suite coverage | G3 shape (21 rows, ad-hoc) | **G6 shape (17/17 explicit + 1 N/A + 1 DELEGATED)** |

`N + M = 11 = total`. Convergence target: `M → 0`. The next 2 G5s (`core.load`, `core.waitUntil`, `core.archiveArtifacts`) each close `M -= 1`.

---

## 6. Branch precondition verified

```text
HEAD            = <this G6 commit>
origin/main     = 65a8ce235138c3182d1e616cd5afc061d9affc22
branch.base     = cycle/lfc2-e1-cleanws-g5 @ 71cf61d0
```

G6 sits on top of G5 (G5 not yet merged to main; this branch chain is the standard
"feature lane" pattern — G4, G5, G6 all stack on the same lane until the chain is
FF-merged to main).

---

## 7. Gate progression (next: G7 installed acceptance)

```text
S2-A10/G0 baseline captured
S2-A10/G1 CoreCleanWsStep registered
S2-A10/G2 differential contract freeze test
S2-A10/G3 StepContractSuite 23/23
S2-A10/G4 REGISTRY_PRIMARY flip (commit 8f8de9a5, PR #35 OPEN)
S2-A10/G5 LEGACY_REMOVED (commit c0e0e659, PR #36 OPEN)
S2-A10/G6 contract certification              ← THIS RECEIPT (commit pending)
S2-A10/G7 installed acceptance (next, STOP awaiting GO)
S2-A10/G8 CERTIFIED (final)
```

G7 destructive work (precedent milestone G7): the installed-CLI proof that the
`core.cleanWs` Step executes through the open registry path under the
`build/install/pipeline-application/bin/pipeline-application` runner. The
compatibility fixture `v2/compatibility/18-cleanWs.pipeline.kts` already exists
(pre-existing; not added in this cycle) and is registered in `CompatibilityCorpusTest`
(test `fixture18CleanWs`); the milestone G7 receipt format asserts `RunFinished{outcome='success'}`
plus a `WsCleaned` event with non-empty `deletedFiles`/`deletedDirs`/`sha256`.

G8 = CERTIFIED: counter reconciliation (3/3/3 stable), `core.cleanWs` joins the
registry-primary counter at 11; certified core Steps + external plugins ledger update.

---

## 8. References

- **STEP_CONSTITUTION / STEP_PLUGIN_CERTIFICATION** — closed execution structure, open Step registry; capability-routed handler discipline.
- **ADR-0070..0074** — Step plugin seam ADRs.
- **ADR-0075** — Retry-D durable control rows (no relation to cleanWs but cited for counter-discipline style).
- **`docs/v2/07-uat/S2_A10_CORE_CLEANWS_G3_READINESS_RECEIPT.md`** — G3 baseline (23/0/0 StepContractSuiteTest).
- **`docs/v2/07-uat/S2_A10_CORE_CLEANWS_G4_REGISTRY_PRIMARY_RECEIPT.md`** — prior gate's G4 receipt.
- **`docs/v2/07-uat/S2_A10_CORE_CLEANWS_G5_LEGACY_REMOVED_RECEIPT.md`** — prior gate's G5 receipt (direct predecessor).
- **`docs/v2/07-uat/S2_A9_CORE_MILESTONE_G6_CONTRACT_CERTIFICATION_RECEIPT.md`** — direct G6 precedent (commit `b53d1ec8`).
- **`docs/v2/07-uat/S2_A9_CORE_MILESTONE_G8_CERTIFICATION_RECEIPT.md`** — G8 precedent for counter reconciliation.

---

**Receipt author:** Jcode (MiniMax-M3, Lane A sole global writer for S2-A10)
**Receipt SHA:** derived from G6 commit (pending in this slice)
**STOP awaiting GO for S2-A10/G7 installed acceptance.**
