# S2-B10 / G5 LEGACY_REMOVED — core.archiveArtifacts Receipt

Cycle: LFC-2E1 · Slice: S2-B10 (archiveArtifacts burn-down)
Gate: **G5 — LEGACY_REMOVED** (physical destructive removal of every legacy form)
Base: `435f5f8b` (S2-B10 / G4 REGISTRY_PRIMARY flip, PR #41, FF-merged to trunk)
Branch: `cycle/lfc2-e1-archive-artifacts-g5`
Authority: ADR-0070..0074, STEP_CONSTITUTION, STEP_PLUGIN_CERTIFICATION, PIPELINE_TEST_HARNESS
Predecessors: `S2_B10_ARCHIVEARTIFACTS_G0_CLASSIFICATION_MEMO.md`,
`S2_B10_ARCHIVEARTIFACTS_G2_DIFFERENTIAL_CONTRACT_FREEZE.md`,
`S2_B10_ARCHIVEARTIFACTS_G3_READINESS_RECEIPT.md`,
`S2_B10_ARCHIVEARTIFACTS_G4_REGISTRY_PRIMARY_RECEIPT.md`

---

## 0. Gate law applied

```text
G4 = N / N / N      -> (N-1) / N / N        ids only (registry-primary flip)
G5 = (N-1) / N / N  -> (N-1) / (N-1) / (N-1) metadata + dispatcher PHYSICALLY removed
```

At G4 the three legacy authorities read `2 / 3 / 3`:

```text
LEGACY_PLUGIN_IDS      = { core.load, core.waitUntil }                 -> 2
CanonicalCoreStepMetadata rows = { load, waitUntil, archiveArtifacts } -> 3
Canonical*NodeDispatcher.kt    = { load, waitUntil, archiveArtifacts } -> 3
```

G5 removes the two remaining physical forms for `core.archiveArtifacts` and **restores
convergence** to `2 / 2 / 2`.

---

## 1. What G5 destroyed (LEGACY_REMOVED — physical destructive)

### 1.1 Production routing source-of-truth — `CanonicalCoreStepDecoder.kt`

| Form | Before (G4) | After (G5) |
| --- | --- | --- |
| `CanonicalCoreStepCommand.ArchiveArtifacts` sealed subtype | present (UNREACHABLE in production) | **deleted** (T-08 KDoc replaced by provenance comment) |
| `ARCHIVE_ARTIFACTS_PLUGIN_ID` constant | present | **deleted** |
| decoder branch (`when` arm) | present | **deleted** — the key now falls through to the `else` rejection |
| `LEGACY_PLUGIN_IDS` | `{ core.load, core.waitUntil }` | unchanged (`2`) — already at G4 |

The `else` rejection is the fail-closed path: an unregistered legacy key is no longer
decodable by ANY legacy form.

### 1.2 Production metadata authority — `CanonicalCoreStepMetadata.kt`

```diff
-        "core.archiveArtifacts" to StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED),
```

Replaced by a provenance comment. `CanonicalCoreStepMetadata.pluginIds` is now exactly
`{ core.load, core.waitUntil }`. `metadata("core.archiveArtifacts")` now **throws
`IllegalArgumentException`** (fail-fast preserved) — this is what makes
`RegistryStepMetadataResolver` the pre-decode metadata authority rather than a second
opinion: there is no legacy row left to fall back to.

### 1.3 Production dispatcher — `durable/`

- `CanonicalArchiveArtifactsNodeDispatcher.kt` — **file deleted** (`git rm`).
- `CanonicalNodeDispatcher.kt` — the `archiveArtifactsDispatcher` field, the `when` arm and
  `archiveArtifactsContext()` were **deleted**; the facade is now exhaustive over the
  surviving sealed subtypes (`load`, `waitUntil`) with no `else` hiding an unhandled case.

### 1.4 Differential contract test deletion (G2 artifact retired)

`CoreArchiveArtifactsDifferentialContractTest.kt` — **deleted**.

The differential suite existed to hold BOTH legs (legacy vs registry) in agreement during
the transition (`S2_B10_ARCHIVEARTIFACTS_G2_DIFFERENTIAL_CONTRACT_FREEZE.md`). With the
second leg physically gone there is nothing left to compare.

**G6 traceability pointer:** the freeze's substantive assertions are not lost — they are
carried forward by
`CoreArchiveArtifactsStepContractSuiteTest` row `G5 LEGACY_REMOVED invariant — core dot
archiveArtifacts physical forms destroyed and counters align`, which asserts the
byte-identical canonical envelope AND the removal (via `assertThrows` on the legacy
metadata lookup), plus the retained-keys control that proves the authority was not
disabled wholesale. G6 (17/17 coverage matrix) MUST record the deleted differential suite
as **N/A / superseded** and cite this row as its surviving carrier.

### 1.5 `LegacyResidualSnapshot` state machine

```kotlin
private val physicalResidual: Set<String> = setOf("core.load", "core.waitUntil")
private val registryPrimaryPendingRemoval: String? = null
```

`4/5/5 -> 4/4/4 -> 3/4/4 -> 3/3/3 -> 2/3/3 -> 2/2/2`. This single object is the only
place the residual set is declared; sibling fitness suites read it and MUST NOT declare
their own counters (the duplication defect that made the `core.pwd` G5 sync six files).

### 1.6 Superseded gate snapshots in sibling suites

Seven `*RegistryPrimaryFitnessTest` suites plus several Step unit/contract suites pinned
`N/N/N` counters from *earlier* lanes. Those rows were **already red at the G4 base**
(see §5) because G4 changed the counters without touching them. G5 applies the established
two-part pattern (cleanWs precedent):

```text
@Disabled("<why superseded + base-SHA evidence>")  on the historical row  (preserved verbatim)
+ a NEW row asserting the post-S2-B10 truth
```

Suites touched: `CoreEmitEvent`, `CoreError`, `CoreIsUnix`, `CorePwd`, `CoreWriteFile`,
`CoreSleep` (registry-shape row), `CoreDeleteDirStepUnitTest`, `CoreIsUnixStepUnitTest`,
`CoreCleanWsStepContractSuiteTest`, `EmitEventStepContractSuiteTest`,
`CanonicalCoreStepCommandRegistryTest`.

`CoreSleepRegistryPrimaryFitnessTest` additionally had a stale **registry-shape** row
(`post-S2-A10-G1`, 13 keys) that G3/G4 invalidated by registering
`CoreArchiveArtifactsStep` (13 -> 14 keys). Superseded and replaced by
`post-S2-B10-G3` (14 keys). Note the two truths this separates: the **registry key set** is
REGISTRATION truth (`core.archiveArtifacts` present) while the **residual** is LEGACY
truth (`core.archiveArtifacts` absent). Post-G5 both hold simultaneously.

---

## 2. RED -> GREEN closure proof (same file, same assertions)

`LegacyResidualConvergenceFitnessTest.kt` — **new**, written ONCE and not modified between
the two runs. File sha256 `b154af1d06f010fa882c7ed7e1f3f5ad0e489b27bd4a988706e97a406eb2679c`,
mtime `2026-09-13 14:31:34.258 +0200` (recorded before both runs).

The suite closes a real enforcement gap: every per-Step S3 suite calls
`assertCurrentState` (tolerant of an in-flight G4 flip by design), so **nothing** asserted
that the corpus is at a genuinely CONVERGED point rather than mid-transition. A receipt
sentence is not a gate; this suite is.

### 2.1 RED — G4 base `435f5f8b` + the new test, no production change

Re-captured on demand in a dedicated read-only worktree at `435f5f8b`
(`convergence-RED-g4base-recaptured.xml`, sha256
`91457d536157c01355e64ff93ee18497bdec871d297122127a6cf21af497c16e`).
The original RED XML was overwritten by later full-module runs, so this is an explicit
**re-capture** of the same argv at the same base SHA, not a substitute for a preserved
original; the original run's recorded result (3/3 failed, exact third-row message) and
this re-capture agree.

```text
: 3 tests, 0 skipped, 3 failures, 0 errors   (exit 1)

row 1  same exact key set in all three authorities
       AssertionFailedError: ids vs metadata rows disagree (not converged) ==> expected: <2> but was: <3>
row 2  non-vacuous while the burn-down is still open
       AssertionFailedError: every residual key must still have a dispatcher file while not converged
                             ==> expected: <2> but was: <3>
row 3  converged with no in-flight registry primary flip
       IllegalStateException: Convergence requires no in-flight REGISTRY_PRIMARY flip;
                              registryPrimaryPendingRemoval=core.archiveArtifacts (G5 not closed)
```

Base-state confirmation read from the same worktree: `registryPrimaryPendingRemoval: String?
= "core.archiveArtifacts"` and 3 metadata rows. The worktree was removed after capture.

### 2.2 GREEN — post-G5 HEAD

```text
:v2:pipeline-architecture-tests:test --tests 'LegacyResidualConvergenceFitnessTest'
: 3 tests, 0 skipped, 0 failures, 0 errors   (exit 0)
```

Persisted: `convergence-GREEN-recaptured.xml`, sha256
`f48217cea934564f285bacd904c750767cf211ed9feff2650e0df32ac4058617`.

Live == expected == **`2 / 2 / 2`**, both per-authority and cross-authority.

This is stronger than an `assertFailsWith`-style negative test: the SEMANTICS of the
assertion never changed ("the residual is converged"); the product changed and the guard
flipped. A negative test would have had to be rewritten at G5 — exactly the incidental
mutation this design removes.

---

## 3. Exit criteria (user-confirmed) — evidence map

| # | Criterion | Evidence | Result |
| --- | --- | --- | --- |
| 1 | `assertCurrentState(root)` holds | all 7 `S3*LegacyRemovedFitnessTest` suites + `Lfc2` in `:pipeline-architecture-tests:test` | **PASS** |
| 2 | `assertConverged(root)` holds | `LegacyResidualConvergenceFitnessTest` 3/0/0 | **PASS** |
| 3 | live ids / metadata / dispatchers = `{core.load, core.waitUntil}` | same 3 rows; live sets read from source | **PASS** `2/2/2` |
| 4 | `StructuralFamilyResolver`: `archiveArtifacts -> Registry`, `waitUntil -> LegacyCore` | `CoreArchiveArtifactsStepContractSuiteTest` row `G4 routing — StructuralFamilyResolver classifies core dot archiveArtifacts as Registry` (runtime seam, live resolver output, incl. no-registry negative control); suite 27/0/0 | **PASS** |
| 5 | `fixture10SmokeE2E PASS` | `CompatibilityCorpusTest` row `fixture10SmokeE2E()` green | **PASS** |
| 6 | `StepContractSuite` GREEN | `CoreArchiveArtifactsStepContractSuiteTest` 27/0/0 | **PASS** |
| 7 | S3 suites GREEN | 7/7 S3 suites 0 failures | **PASS** |
| 8 | `Lfc2` GREEN | `Lfc2RegistryFamilyFitnessTest` 3/0/0, `Lfc2DurableCoordinatorScopeFitnessTest` 4/0/0 | **PASS** |
| 9 | byte-equivalent payload preservation | §4 | **PASS** |
| 10 | zero regressions vs base | §5 | **PASS** |

---

## 4. Byte-equivalence of the observable archive payload (post-removal)

`docs/v2/07-uat/evidence/s2-b10-g5/fixture10-g5-byte-equivalence.json`

Method: same installed-distribution command and fixture as the G4 A/B, twice at HEAD with
a fresh `--db` per run, through the **registry-only** spine (no legacy fallback exists).

```text
v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application \
    run v2/compatibility/10-smoke-e2e.pipeline.kts --db <tmp>/db
```

| Capture | exit | relPath | sha256 | size | stageName |
| --- | --- | --- | --- | --- | --- |
| G4 `B_head` (registry-primary) | 0 | `build/libs/smoke.jar` | `fb8ce055…cb2b9` | 4 | `build` |
| G5 run-1 | 0 | `build/libs/smoke.jar` | `fb8ce055…cb2b9` | 4 | `build` |
| G5 run-2 | 0 | `build/libs/smoke.jar` | `fb8ce055…cb2b9` | 4 | `build` |

```text
g5_run1_matches_g4            = true
g5_run2_matches_g4            = true
g5_run1_equals_g5_run2        = true
byte_identical_payload_preserved = true
```

`stdout_sha256` intentionally differs across runs (`runId`/`eventId`/`occurredAt` are
run-scoped identifiers), so equivalence is asserted on the ARTIFACT PAYLOAD — the
observable domain output — not on the event envelope.

---

## 5. Base-vs-head: the full application module (fresh, both SHAs)

Both runs use the identical argv in a worktree at the cycle base and in the G5 worktree.
Result truth is the JUnit XML set, not console or exit code.

```text
: 435f5f8b  ./v2/gradlew -p v2 :pipeline-application:test   -> 162 classes, 1393 tests,  95 skipped, 54 failures  (18m32s)
: HEAD      ./v2/gradlew -p v2 :pipeline-application:test   -> 161 classes, 1392 tests, 111 skipped, 36 failures  (18m28s)
```

Failure sets compared by `(class, testName)`:

```text
FIXED by G5 (18)      -> every counter class G4 had silently broken (see §1.6)
NEW REGRESSIONS (0)   -> none
RESIDUAL (36)         -> identical (class, name) AND message-identical modulo
                         /tmp/junit-<digits> temp paths and run-scoped ids
```

Artifacts: `base-435f5f8b-app-module-failures.json`, `head-app-module-failures.json`.

This is the material finding of the round: **G4's targeted verification never ran the full
application module**, so ~18 counter rows across 11 classes were left red at `435f5f8b`.
They are collateral of this lane, and G5's sweep clears all 18. The 36 residual failures
are pre-existing from earlier lanes and are **not widened or re-baselined** here.

Residual failure classes (pre-existing, unchanged): `CanonicalDurableRunCoordinatorTest` 11,
`UatLocal009TopStepsTest` 4, `ExecutionBoundaryFactoryTest` 4,
`DualExecutionSeamCharacterizationTest` 3, `CompatibilityCorpusTest` 2,
`UatCompat001CorpusSmokeRunTest` 2, `UatLocal007SandboxProfileTest` 2,
`UatLocal008CredentialsTest` 2, `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` 1,
`CoreLegacyStepMetadataResolverTest` 1, `RegistryStepMetadataResolverTest` 1,
`UatLocal005CheckoutGitTest` 1, `UatLocal005CorpusUntouchedTest` 1,
`DurableProtocolInvocationCharacterizationTest` 1.

### 5.1 Architecture module (full run)

```text
./v2/gradlew -p v2 :pipeline-architecture-tests:test   -> 241 tests, 1 failure
```

The single failure is `Lfc0GlobalStateFitnessTest > production code does not access the
controller user directory property`, reproduced fresh at base `435f5f8b`:

```text
Forbidden production global-state access:
  Finding(file=.../pipeline-application/src/main/kotlin/.../application/Capabilities.kt,
          line=76, token=System.getProperty("user.dir"))
```

Unrelated to `archiveArtifacts` (`Capabilities.kt`, not touched by this lane). Pre-existing.

All 7 S3 suites green, `Lfc2RegistryFamilyFitnessTest` 3/0/0,
`LegacyResidualConvergenceFitnessTest` 3/0/0, all `FArch*`/`Lfc0*`/`Lfc1*` green except the
one row above.

---

## 6. Counters and certification status

```text
Certified Steps:             ?
Legacy executable Steps:     2      (core.load, core.waitUntil)
Registry-primary Steps:      ?
```

This lane's counters:

| Authority | Before G4 | At G4 | At G5 |
| --- | --- | --- | --- |
| `LEGACY_PLUGIN_IDS` | 3 | 2 | **2** |
| `CanonicalCoreStepMetadata` rows | 3 | 3 | **2** |
| `Canonical*NodeDispatcher.kt` | 3 | 3 | **2** |
| converged? | yes | no | **yes** |

**`core.archiveArtifacts` state: `IMPLEMENTED_UNCERTIFIED` -> not yet `CERTIFIED`.** G5
closes LEGACY_REMOVED, which is a *prerequisite* of CERTIFIED (ADR-0074), not a substitute.
Per the burn-down template, G6 (contract coverage matrix) and the certification gate remain
open. Nothing in this receipt records `DONE`/`PASS` for an uncertified Step.

---

## 7. Files changed (21 paths)

Production (4):

```text
 M pipeline-application/.../application/CanonicalCoreStepDecoder.kt
 M pipeline-application/.../application/CanonicalCoreStepMetadata.kt
 M pipeline-application/.../application/durable/CanonicalNodeDispatcher.kt
 D pipeline-application/.../application/durable/CanonicalArchiveArtifactsNodeDispatcher.kt
```

Tests (16):

```text
 D CoreArchiveArtifactsDifferentialContractTest.kt
 M CoreArchiveArtifactsStepContractSuiteTest.kt
 M CoreArchiveArtifactsStepUnitTest.kt
 M CanonicalCoreStepCommandRegistryTest.kt
 M CoreCleanWsStepContractSuiteTest.kt
 M CoreDeleteDirStepUnitTest.kt
 M CoreEmitEventRegistryPrimaryFitnessTest.kt
 M CoreErrorRegistryPrimaryFitnessTest.kt
 M CoreIsUnixRegistryPrimaryFitnessTest.kt
 M CoreIsUnixStepUnitTest.kt
 M CorePwdRegistryPrimaryFitnessTest.kt
 M CoreSleepRegistryPrimaryFitnessTest.kt
 M CoreWriteFileRegistryPrimaryFitnessTest.kt
 M EmitEventStepContractSuiteTest.kt
 M architecture/LegacyResidualSnapshot.kt
 A architecture/LegacyResidualConvergenceFitnessTest.kt
```

Evidence (5): `docs/v2/07-uat/evidence/s2-b10-g5/`.

---

## 8. Gate progression

```text
G0 classification      DONE
G1 registry seam       DONE   (prior lane)
G2 differential freeze DONE
G3 readiness + suite   DONE
G4 REGISTRY_PRIMARY    DONE   (435f5f8b, PR #41, merged)
G5 LEGACY_REMOVED      THIS RECEIPT
G6 contract matrix     17/17 coverage (16 required + 1 N/A + 1 DELEGATED) — STOP before G6
G7 installed acceptance
G8 CERTIFIED
```

---

## 9. References

- `docs/v2/07-uat/evidence/s2-b10-g5/` — all G5 evidence
- `docs/v2/07-uat/S2_B10_ARCHIVEARTIFACTS_G4_REGISTRY_PRIMARY_RECEIPT.md`
- `docs/v2/07-uat/S2_A10_CORE_CLEANWS_G5_LEGACY_REMOVED_RECEIPT.md` — the G5 precedent
- `v2/pipeline-architecture-tests/src/test/kotlin/.../LegacyResidualSnapshot.kt`
- `v2/pipeline-architecture-tests/src/test/kotlin/.../LegacyResidualConvergenceFitnessTest.kt`
- `docs/v2/03-adr/ADR-0074-*.md` (Step certification states)
