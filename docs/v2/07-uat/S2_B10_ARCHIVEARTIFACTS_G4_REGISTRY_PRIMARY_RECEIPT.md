# S2-B10 / G4 — `core.archiveArtifacts` REGISTRY_PRIMARY flip

**Cycle:** `lfc2-e1-s2-b10-core-archiveartifacts`
**Branch:** `cycle/lfc2-e1-archive-artifacts-g4` (cut from `origin/main @ c0e27f21`, the G3 merge)
**Base:** `c0e27f211ef533943a502cee1faa95119023c6be`
**Date:** 2026-09-13 (local +02:00)
**Status:** REGISTRY_PRIMARY — the authority flip is landed and verified end-to-end.

## 1. The flip (exactly what changed)

G4 is the authority mutation. Three production edits plus three test-truth edits.

### 1.1 Production (3 files)

| File | Change |
|---|---|
| `CanonicalCoreStepDecoder.kt` | `"core.archiveArtifacts"` **removed** from `LEGACY_PLUGIN_IDS` (3 → 2 ids). Gate comment block added. |
| `CoreStepRegistryFactory.kt` | stale G1 comment replaced: the key is now registry-owned, not legacy-owned. |
| `LegacyResidualSnapshot.kt` | `registryPrimaryPendingRemoval = "core.archiveArtifacts"` — the **single declaration site** of the transitional `(N-1)/N/N` state. |

**NOT touched (the anti-over-removal half of the counter law).** All four legacy forms survive
G4 physically and are merely UNREACHABLE in production until G5:

```text
CanonicalCoreStepCommand.ArchiveArtifacts subtype            present
ARCHIVE_ARTIFACTS_PLUGIN_ID const + decoder branch           present
CanonicalCoreStepMetadata["core.archiveArtifacts"] row       present
durable/CanonicalArchiveArtifactsNodeDispatcher.kt           present
```

### 1.2 Test truth (6 files)

| File | Change |
|---|---|
| `CompatibilityCorpusTest.kt` | `fixture10SmokeE2E`: `runFixtureFail` → **`runFixturePass`** (the observable exit criterion) |
| `CoreArchiveArtifactsStepContractSuiteTest.kt` | row 17 → G4 invariants; **new row 17b** (`StructuralFamilyResolver` → Registry + 2 negative controls); **new row 17c** (end-to-end behavioural proof) |
| `CoreArchiveArtifactsStepUnitTest.kt` | 2 G1 gate snapshots archived `@Disabled`; 2 new G4 truth rows added |
| `CoreArchiveArtifactsDifferentialContractTest.kt` | 1 G2 gate snapshot archived `@Disabled` |
| 7 × `S3*LegacyRemovedFitnessTest.kt` | 9 `assertConverged` calls → `assertCurrentState` (see §4) |
| `LegacyResidualSnapshot.kt` | KDoc contradiction removed: per-Step suites call `assertCurrentState`, `assertConverged` is the G5-closure proof |

## 2. Exit criteria — all met

| # | Criterion | Status |
|---|---|---|
| 1 | `LEGACY_PLUGIN_IDS -= "core.archiveArtifacts"` | ✅ 3 → 2 |
| 2 | `StructuralFamilyResolver` → `Registry` | ✅ row 17b, 3 assertions |
| 3 | `fixture10SmokeE2E`: `runFixtureFail` → `runFixturePass` | ✅ and green |
| 4 | legacy metadata **untouched** | ✅ row 17 asserts the row survives |
| 5 | legacy dispatcher **untouched** | ✅ `LegacyResidualSnapshot` pins `CanonicalArchiveArtifactsNodeDispatcher.kt` |
| 6 | counters = **2 / 3 / 3** | ✅ declared in the single authority; positively re-derived from source by the S3 suites |
| 7 | end-to-end behavioural proof (beyond routing metadata) | ✅ §3 |

## 3. The observable proof (A/B on the same command at both SHAs)

A `StructuralFamilyResolver` assertion proves *routing*. It does **not** prove the behaviour
actually changed. The following is the A/B pair, run with the **installed distribution** at each
SHA, same fixture, fresh `--db`:

```text
command: <module>/build/install/pipeline-application/bin/pipeline-application \
           run v2/compatibility/10-smoke-e2e.pipeline.kts --db <tmp>/db
```

| | `A` base `c0e27f21` | `B` head (this commit) |
|---|---|---|
| authority | legacy dispatcher | registry `CoreArchiveArtifactsStep.definition` |
| **exit code** | **1** | **0** |
| domain event | `ArtifactArchiveFailed` | `ArtifactArchived` |
| reason / payload | `No files matched glob pattern 'build/libs/*.jar' and allowEmptyArchive is false` | `files: [{stageName: build, relPath: build/libs/smoke.jar, sha256: fb8ce055…, size: 4}]` |
| JUnit expectation | `runFixtureFail` → green (fixture *failing* is the expectation) | `runFixturePass` → green (fixture *succeeding* is the expectation) |
| stdout sha256 | `33336be6…` | see JSON |

Machine-readable: `docs/v2/07-uat/evidence/s2-b10-g4/fixture10-ab-resolution.json`.

**Read the table carefully: `fixture10SmokeE2E` is green on BOTH sides, for opposite reasons.**
A green JUnit row alone is therefore worthless as flip evidence. The evidence is the *pair*
(exit 1 → exit 0, `ArtifactArchiveFailed` → `ArtifactArchived`, `runFixtureFail` → `runFixturePass`).
Frozen delta **D1** (glob engine regex → certified `AntStyleGlob`) is the cause: the legacy
dispatcher anchored its regex against absolute paths and could never match.

## 4. Why 9 `assertConverged` calls had to change (and why it is not a weakening)

Landing G4 on its own turned all seven `S3*LegacyRemovedFitnessTest` suites red with:

```text
java.lang.IllegalStateException: Convergence requires no in-flight REGISTRY_PRIMARY flip;
registryPrimaryPendingRemoval=core.archiveArtifacts (G5 not closed)
```

`LegacyResidualSnapshot` contained a **self-contradiction**: its class KDoc said per-Step suites
"`MUST call assertConverged`", while `assertCurrentState`'s own KDoc said "`Call this from every
per-Step S3 fitness test`". The suites followed the wrong one.

`assertConverged` = `assertCurrentState` + "no flip in flight". During a G4 mutation the flip is
in flight **by design**, so a per-Step absence suite about an *unrelated* Step (`core.echo`,
`core.pwd`, …) went red for a reason that has nothing to do with that Step. The previous lanes
never surfaced this because their G4 and G5 shared one branch, so trunk only ever saw a converged
state.

Resolution: per-Step suites assert the **declared stage snapshot** (`assertCurrentState`); the
stricter `assertConverged` remains the G5-closure proof. Both functions assert the **same
substantive invariant** (`live == expected`); they differ only in whether an in-flight flip is
tolerated. Nothing was weakened, and the contradiction in the authority's own KDoc is now removed
with the reason recorded there.

The 7 suites still re-derive the residual **live from source** and now confirm `2 / 3 / 3`
independently of the declaration:

| Suite | Result | sha256 |
|---|---|---|
| `S3EchoLegacyRemovedFitnessTest` | 7/0/0 | `c9c121e752afd164e603acfaa3b2c1af9ac8e72aafb9affdea98706689906cf5` |
| `S3EmitEventLegacyRemovedFitnessTest` | 8/0/0 | `a331be3e7da9f98378c2d63191ddac30f2d2737d8c57c26946be65e6c8415189` |
| `S3ErrorLegacyRemovedFitnessTest` | 12/0/0 | `a7af345f5caf66f1cba0f13f335775baada162aadf33ff83b21ab3f7a45c0a87` |
| `S3IsUnixLegacyRemovedFitnessTest` | 9/0/0 | `7fe02de27eb0041e8737e0e93d42aff18219205410cc4a458e5a6261260b5590` |
| `S3PwdLegacyRemovedFitnessTest` | 8/0/0 | `09490679eaa0d5090d72724a285de0cf142662b76a8c7db8a89ec1a5311a379f` |
| `S3SleepLegacyRemovedFitnessTest` | 4/0/0 | `e23f8aee16beecf115601c0a3d379bd656fdca67c006eb53efb241d6de2f47fa` |
| `S3WriteFileLegacyRemovedFitnessTest` | 4/0/0 | `81882b06c328a39426a645d1d56d8c92edc2a65bd370ff43e5c6b5c161e3ab86` |
| `Lfc2RegistryFamilyFitnessTest` | 3/0/0 | `f6d5d74ed85c7118635fb7cb86446b2120a7c5b7d82cf86897a21824fdab33c8` |

Their stale copy-paste names (`converge to exact six step snapshots`, `the 6 residual keys`) were
corrected to number-free phrasing so the assertion name no longer claims something false.

## 5. New G4 rows in the contract suite

```text
17   G4 invariant       core dot archiveArtifacts is registry-primary with counters 2 3 3
17b  G4 routing         StructuralFamilyResolver classifies core dot archiveArtifacts as Registry
17c  G4 routing e2e     a non-empty archive succeeds through production wiring where legacy failed
```

Row 17 asserts **both** halves of the counter law: the id is gone **and** the metadata row
survives (`2 / 3 / 3`, not `2 / 2 / 2`).

Row 17b adds two negative controls, because the rule is membership-based, not name-based:

```text
core.waitUntil (still legacy)              -> LegacyCore   (legacy-membership-wins preserved)
core.archiveArtifacts with registry=null   -> LegacyCore   (legacy coordinator unchanged)
```

Row 17c is the behavioural proof at the unit level: a **non-empty** match
(`build/libs/*.jar` → 1 file) through the production coordinator. Before G4 this exact input
failed (D1), so green here can only mean the registry adapter executed. It complements §3: §3
proves the end-to-end exit code flip, 17c proves the flip is live in-process.

## 6. Verification (fresh XML + SHA-256, canary deleted-then-regenerated)

```text
argv: ./v2/gradlew -p v2 :pipeline-architecture-tests:test \
        :pipeline-application:test --tests '*ArchiveArtifacts*' --tests '*Compatibility*'
```

| Suite | tests | skipped | failures | sha256 |
|---|---|---|---|---|
| `CoreArchiveArtifactsStepContractSuiteTest` | 27 | 0 | **0** | `9701e8a262b849a21b5ece4e1839ee05917ef521f3fd32c3813eb63a950e280d` |
| `CoreArchiveArtifactsStepUnitTest` | 23 | 2 | **0** | `722cef640aac9a05ec53a8d4b7b12d25644eebcf95f9d8b7b53586926bc85d7e` |
| `CoreArchiveArtifactsDifferentialContractTest` | 11 | 1 | **0** | `8c63728bcb9d2894d69413aaf088da98064545cf99a38901d259f40c67527b66` |
| `CompatibilityCorpusTest` | 20 | 0 | 2 (both pre-existing, §7) | `637b707d19df9ebf7ba6a9a5bdd78ad6542bffb5f8a1241b032f8042b0f444b3` |
| `Lfc0GlobalStateFitnessTest` | 2 | 0 | 1 (pre-existing) | `bd542dae541675eb1855bd9ad54da23a2d787d3813b6ba58689c58b2c71e5095` |

`CoreArchiveArtifactsStepContractSuiteTest` was re-run after a final KDoc-only correction
(stale `G1`/`G3` wording in the class header and coverage matrix, no assertion touched); the
sha256 above is from that **final** run — `9701e8a2…`. `CompatibilityCorpusTest.fixture10SmokeE2E`
was re-verified in the same run: 1/0/0, sha256 `0456d67a…`.

All other suites in both modules: `failures="0" errors="0"`. `:pipeline-application:compileTestKotlin`
and `:pipeline-architecture-tests:compileTestKotlin` exit 0.

`fixture10SmokeE2E` specifically: 1/0/0 (`runFixturePass`).

## 7. Pre-existing reds — fresh base-vs-head evidence (rule 16)

Collected by running the same selections in the **trunk worktree checked out at the base SHA
`c0e27f21`** (not a mid-cycle commit), 34 tests, 3 failed:

| Test | Base `c0e27f21` | Head (G4) | Classification |
|---|---|---|---|
| `CompatibilityCorpusTest.allCorpusFixturesAreDiscoverable` | FAIL (expects 19, finds 20) | FAIL | **pre-existing**, unrelated to this slice (fixture inventory) |
| `CompatibilityCorpusTest.fixture14CredentialsBindings` | FAIL (exit 1) | FAIL | **pre-existing**, unrelated (credentials fixture) |
| `CoreArchiveArtifactsStepUnitTest.counters - G1 leaves legacy counters at 5 5 5` | FAIL (expects 5, live 3) | `@Disabled` | **pre-existing red**; already stale at base (the residual shrank in the S2-A9/S2-A10 G5 closures). Archived as a historical snapshot per precedent and superseded by the G4 counter row. |
| `CoreArchiveArtifactsStepUnitTest.structural family - …stays LegacyCore…` | PASS | updated to G4 truth | **G4-induced** (gate snapshot) |
| `CoreArchiveArtifactsDifferentialContractTest.counters — G2 leaves the legacy residual at 3 3 3` | PASS | updated to G4 truth | **G4-induced** (gate snapshot) |
| `Lfc0GlobalStateFitnessTest` | FAIL | FAIL | **pre-existing**, carried |

No pre-existing red was widened. One pre-existing red (`counters - G1 … 5 5 5`) was *narrowed*
by archiving a stale gate snapshot, disclosed here explicitly.

## 8. Counters (post-G4)

```text
core.archiveArtifacts:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true      <-- flipped in this gate
  LEGACY_UNREACHABLE = true      (legacy forms physically present, unreachable in production)
  LEGACY_REMOVED     = false     (G5)

global residual:
  legacy ids       = 3 -> 2   {core.load, core.waitUntil}
  metadata rows    = 3        (unchanged; G4 is ids-only)
  dispatcher files = 3        (unchanged; G4 is ids-only)
  certified steps  = 10       (unchanged; certification is G8)
  registry-primary = 11 -> 12
```

## 9. G5 exit criteria (registered now)

G5 (`LEGACY_REMOVED`) MUST:

1. Delete `CanonicalCoreStepCommand.ArchiveArtifacts` subtype + `ARCHIVE_ARTIFACTS_PLUGIN_ID`
   constant + decoder branch + `CanonicalCoreStepMetadata["core.archiveArtifacts"]` row.
2. Delete `durable/CanonicalArchiveArtifactsNodeDispatcher.kt` and its `CanonicalNodeDispatcher`
   seams (field, `when` branch, `archiveArtifactsContext()`).
3. Remove `"core.archiveArtifacts"` from `physicalResidual` and reset
   `registryPrimaryPendingRemoval = null` → converged `2 / 2 / 2`.
4. **Delete `CoreArchiveArtifactsDifferentialContractTest.kt`** — its second leg (the legacy
   authority) ceases to exist. Record the deletion + traceability in the G5 receipt, per the
   `CoreCleanWsDifferentialContractTest.kt` precedent.
5. Byte-equivalent payload preservation proof across the removal.
6. Re-run the full architecture fitness set with `assertConverged` (now legitimately strict).

## 10. Decision requested

G4 complete: all 7 exit criteria met, evidence fresh and canaried, only pre-existing reds remain.

**STOP** — awaiting explicit user **GO** for G5.
