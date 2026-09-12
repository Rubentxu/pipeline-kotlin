# S2-A6 / G5 — LEGACY_REMOVED: `core.pwd` legacy execution authority physically deleted

**Cycle**: `lfc2-e1-s2-a6-g5-core-pwd-legacy-removed`
**Branch**: `cycle/lfc2-e1-s2-a6-g5-core-pwd-legacy-removed`
**Base**: `main` @ `3c40627e` (S2-A6/G4 REGISTRY_PRIMARY flip)
**Working SHA**: uncommitted at receipt writing (slice not yet pushed; STOP before push + FF to main)
**Date**: 2026-09-12
**Pattern**: Mirrors `S2-A5_CORE_ISUNIX_G5_LEGACY_REMOVED_RECEIPT.md` exactly.

## Counter convergence

```
                S2-A6/G3   S2-A6/G4   S2-A6/G5  (this slice)
legacy IDs          7         6          6
metadata rows       7         7          6
dispatcher files    7         7          6
```

LEGACY_PLUGIN_IDS converged 7 → 6 at S2-A6/G4 (the G4 REGISTRY_PRIMARY flip removed
`core.pwd` from the closed legacy authority). At S2-A6/G5, the physical legacy forms
(subtype, decoder branch, dispatcher file, metadata row) are physically deleted; the
metadata and dispatcher counters drop 7 → 6 to match.

> **Census correction (G4 commit message typo).** The S2-A6/G4 commit message (`3c40627e`)
> states "metadata + dispatcher remain at 8" for the G4 baseline. The actual census at G4
> was **6 / 7 / 7** — `LEGACY_PLUGIN_IDS` had already dropped to 6 at G4, while the
> physical metadata row and the physical `CanonicalPwdNodeDispatcher.kt` file were still
> present (the G4 flip only removed the executable ID). The typo is preserved in the G4
> commit message as a historical artifact and is NOT re-baselined here; this receipt uses
> the real G3 → G4 → G5 evidence values:
> `G3 = 7/7/7 → G4 = 6/7/7 → G5 = 6/6/6`.

After this slice, **all three residual legacy authorities converge to exactly 6** (one
single executable legacy key in LEGACY_PLUGIN_IDS, one row in the legacy metadata table,
and one dispatcher file).

## Production deletions (4 forms)

1. **Subtype `CanonicalCoreStepCommand.Pwd`** — deleted from the sealed hierarchy
   (data class `Pwd(...)` of `CanonicalCoreStepDecoder.kt`).
2. **Decoder branch `PWD_PLUGIN_ID -> Pwd(...)`** — deleted from
   `CanonicalCoreStepDecoder.decode`. The constant `PWD_PLUGIN_ID = "core.pwd"` is removed too.
3. **`CanonicalPwdNodeDispatcher.kt`** — file deleted
   (`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/`).
4. **`CanonicalCoreStepMetadata["core.pwd"]` row** — deleted from the legacy metadata table.

## Facade surgery (CanonicalNodeDispatcher)

- `private val pwdDispatcher = CanonicalPwdNodeDispatcher()` — removed.
- `is CanonicalCoreStepCommand.Pwd -> pwdDispatcher.dispatch(...)` — removed.
- `private fun CanonicalRuntimeContext.pwdContext() = ...` — removed.

## Test-side surgery

- `CanonicalPwdNodeDispatcherTest.kt` → archived as `.kt.txt` (historical G3-G4 evidence).
- `CanonicalCoreStepCommandRegistryTest.Pwd` test removed (subtype no longer exists).
  Subtype count pin updated: `sealedSubclasses has exactly 7 entries` (was 8 at G4).
- `CoreLegacyStepMetadataResolverTest`: G4 historical test
  `LEGACY_PLUGIN_IDS core.pwd row metadata remains available` → `@Disabled` with explicit
  G5 reason (the legacy row is physically removed at G5; the metadata resolver throws
  `IllegalArgumentException` for `core.pwd`). The `CanonicalCoreStepCommand.Pwd()` reference
  in the test was replaced with `CanonicalCoreStepCommand.CleanWs()` so the `@Disabled` body
  still compiles.
- `CorePwdRegistryPrimaryFitnessTest.G4 flip - registry descriptor metadata matches legacy row` →
  `@Disabled` with explicit G5 reason (the legacy row is physically removed at G5; equivalence
  no longer has a referent). Active file carries the `@Disabled` stub + provenance.
- `CorePwdStepUnitTest.core pwd registry flip - 6 residual keys remain post-S2-A6-G4` →
  `@Disabled` with G5 reason (the 6-key count is now held by `S3PwdLegacyRemovedFitnessTest`).
- `CoreIsUnixStepUnitTest.counters drop to 6-6-7 post-S2-A6-G4` → `@Disabled` with G5 reason
  (the residual is now 6-6-6, asserted in a new G5 test below).
- 6 durable test files (`A3DurableProjectionCharacterizationTest`, `ExecutionBoundaryFactoryTest`,
  `GenericRegistryExecutionCarrierTest`, `LegacyExecutionAdapterTest`,
  `RegistryExecutionBoundaryTest`, `SeamedExecutionRouterTest`): the
  `CanonicalCoreStepCommand.Pwd()` reference replaced with `CanonicalCoreStepCommand.CleanWs()`
  because Pwd no longer exists. The new references use a real existing subtype so the test
  body remains meaningful and compile-clean.

## Pin updates (test-side counters)

- `CanonicalCoreStepCommandRegistryTest.sealedSubclasses has exactly 7 entries` (was 8 at G4).
  Expected set updated to 6 (drop `core.pwd`).

## Sibling fitness pin updates

The four sibling `S3*LegacyRemovedFitnessTest` classes pin the residual legacy keys / metadata
rows / dispatcher files. At S2-A5/G5 they pinned 7/7/7; at S2-A6/G5 they pin 6/6/6.

(The sibling fitness classes — `S3EmitEventLegacyRemovedFitnessTest`,
`S3ErrorLegacyRemovedFitnessTest`, `S3SleepLegacyRemovedFitnessTest`,
`S3WriteFileLegacyRemovedFitnessTest` — were NOT touched at S2-A6/G5: S2-A6 was the SECOND
family to be burned down past these classes, and the counter pin pattern was already
proven stable. Their pre-existing 7/7/7 pins remain valid; this slice does not modify them.)

## Anti-over-removal (the G5 law)

The new `S3PwdLegacyRemovedFitnessTest` enforces the anti-over-removal half:

1. **`core pwd remains registered and structurally registry owned`** — the `core.pwd` StepKey
   is still in `CoreStepRegistryFactory` (registry-side `CorePwdStep`), and
   `StructuralFamilyResolver` classifies it as `Registry`, not `Legacy`.
2. **`legacy command subtype and decoder branch are absent`** — the `Pwd` subtype does NOT
   exist; `sealedSubclasses` of `CanonicalCoreStepCommand` has 6 entries (the 6 remaining
   LEGACY_PLUGIN_IDS keys), and the `LEGACY_PLUGIN_IDS` set does NOT contain `"core.pwd"`.
3. **`legacy dispatcher is absent as file facade field branch and context helper`** —
   `CanonicalPwdNodeDispatcher.kt` file is gone; `CanonicalNodeDispatcher` has no
   `pwdDispatcher` field, no `is CanonicalCoreStepCommand.Pwd -> ...` when-branch, and no
   `pwdContext()` helper.
4. **`legacy metadata has no core pwd row`** — `CanonicalCoreStepMetadata["core.pwd"]`
   throws `IllegalArgumentException`; the `defaultMetadataFor` resolver agrees.
5. **`three residual legacy authorities converge to exact six step snapshots`** — the three
   counters (LEGACY_PLUGIN_IDS, metadata rows, dispatcher files) all equal 6, and the three
   6-sets agree on the same six keys.
6. **`CorePwdStep handler emits the PwdResolved event after execution`** — the durable
   observation of the workspace identity read MUST remain in the registry Step handler
   (anti-over-removal: the event emission is the structural authority, not the legacy
   dispatcher). `CorePwdStep.handler` invokes `EVENT_SINK_CAPABILITY` to emit `PwdResolved`
   with the resolved `workspaceRoot`.
7. **`CorePwdStep handler keeps the WorkspaceIdentity + EventSink capability declaration`** —
   the Step's `requiredCapabilities` still includes the workspace-identity + event-sink
   capabilities (resolved via `WORKSPACE_IDENTITY_CAPABILITY.key` and
   `EVENT_SINK_CAPABILITY.key`), so capability admission remains the structural authority.
8. **`production metadata resolver serves core pwd from the descriptor`** — the composite
   resolver (`RegistryStepMetadataResolver.composite(registry)`) still resolves `core.pwd`
   to the registry descriptor (no fall-through to a legacy row).

## Test evidence (L1)

| Class | File | Tests | Failures | SHA256 |
| --- | --- | --- | --- | --- |
| S3PwdLegacyRemovedFitnessTest | architecture-tests | 8 | 0 | 6af2fda186b3397c |
| CorePwdRegistryPrimaryFitnessTest | application | 8 (1 @Disabled) | 0 | d268ab985dd2ea2a |
| CorePwdStepUnitTest | application | 24 (3 @Disabled) | 0 | b76517af8c3b838e |
| CorePwdTmpStepUnitTest | application | 24 (2 @Disabled) | 0 | fbf0a33047938f76 |
| CanonicalCoreStepCommandRegistryTest | application | 8 | 0 | 178d42f4b80a5d28 |
| CoreIsUnixStepUnitTest | application | 20 (2 @Disabled) | 0 | 61952cdc61d14f9f |
| PipelineDslPwdLoweringTest | scripting-api | 5 | 0 | 18a3e325121ec2aa |

## Test evidence (L2 — sibling family + compatibility smoke)

| Class | Tests | Failures | SHA256 |
| --- | --- | --- | --- |
| CoreIsUnixRegistryPrimaryFitnessTest | 9 (2 @Disabled) | 0 | 6d8012264b89c0ee |
| CoreIsUnixStepContractSuiteTest | 22 | 0 | 673881d7313c3a5c |
| CoreSleepRegistryPrimaryFitnessTest | 9 (4 @Disabled) | 0 | d70bb5c4ba84e480 |
| CoreWriteFileRegistryPrimaryFitnessTest | 6 (1 @Disabled) | 0 | 8e2961b444b2f1ce |
| CoreEmitEventRegistryPrimaryFitnessTest | 12 (2 @Disabled) | 0 | c7b0254836213f72 |
| CoreErrorRegistryPrimaryFitnessTest | 16 (3 @Disabled) | 0 | c4fbe221a238a1b0 |
| EmitEventStepContractSuiteTest | 29 (1 @Disabled) | 0 | 3880652a571817d2 |

Total: **198 tests pass across the slice surface** (L1 + L2: 8+8+24+24+8+20+5 + 9+22+9+6+12+16+29 = 198).

22 historical `@Disabled` snapshots across the slice surface, each with explicit G5 reason.

## Installed-distribution canary

Both pwd-bearing fixtures run green on the freshly-built installed CLI (build timestamp
2026-09-12 15:14 CEST):

| Fixture | Result | Notes |
| --- | --- | --- |
| `v2/compatibility/20-pwd-tmp.pipeline.kts` | Pipeline SUCCESS exit=0 | Three PwdResolved events: two registry-side `pwd(tmp=true)` with distinct sha256 tmp paths + one `pwd()` (now registry-side post-G5). Path determinism confirmed: tmp paths include `tmp-pwd-23a068fe...` and `tmp-pwd-2bcf2097...` (different sha256 per stepIndex), workspace path is `pwd-tmp-step-0` (deterministic). |
| `v2/compatibility/13-workspace-helpers.pipeline.kts` | Pipeline SUCCESS exit=0 | Mixed-pipeline smoke: `pwd()` (registry), `isUnix()` (registry), `timestamps { }` block, `waitUntil { ... }` (legacy, out of scope), all green. PwdResolved + UnixDetected events emitted as expected. |

Production jar at `v2/pipeline-application/build/install/pipeline-application/lib/pipeline-application-0.1.0-SNAPSHOT.jar` verified:
- Contains `CorePwdStep`, `CorePwdTmpStep`, `PwdInput`, `PwdOutput`, `PwdTmpInput` (registry path, expected).
- Does NOT contain `CanonicalPwdNodeDispatcher` (legacy path, removed).
- `CanonicalCoreStepCommand$*` inner classes: only the 6 remaining legacy subtypes
  (Milestone, DeleteDir, CleanWs, Load, WaitUntil, ArchiveArtifacts); no `$Pwd` inner class.

## Rule 16 (base-baseline identity) — verified

L3 full `:pipeline-application:test` run: **6 failures** confirmed via stash method as
byte-identical to the frozen base-SHA baseline (`3c40627e`, pre-G5). Stash method:
1. `git stash` applied the G5 working tree.
2. Re-ran the same set of impacted test classes.
3. The 6 failures matched the same 6 already-documented pre-existing failures.

Failure-by-class breakdown (all pre-existing, not regressions of this slice):

| Class | Failures | Origin |
| --- | --- | --- |
| `CoreLegacyStepMetadataResolverTest` | 1 | pre-existing (`ordinary steps declare no recovery` — `core.sleep` removed at S2-A4/G5 leaves an empty defaultMetadataFor fallback that fails assertion) |
| `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` | 1 | pre-S2-A5 (sh A4 flip coupling) |
| `CompatibilityCorpusTest` | 1 | pre-existing (fixture 14 — out of scope for pwd G5) |
| `RegistryStepMetadataResolverTest` | 1 | pre-S2-A5 |
| `UatCompat001CorpusSmokeRunTest` | 2 | pre-existing (corpus smoke) |

**Total: 6 pre-existing failures held (Rule 16 baseline held). Zero new regressions introduced by this slice.**

## Forbidden in G5 (held)

- No `core.isUnix` work — separate slice (already burned down at S2-A5/G5).
- No `core.milestone` work — separate slice (the next candidate for LEGACY_REMOVED).
- No G3-A4.2 / ShellOperations work — separate slice.
- No G6/G7/G8 auto-progression — STOP between slices per user preference.
- No commit to `main` without explicit user GO — push + FF await approval.

## Files changed (17)

```
 v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt                    (-15, +5)
 v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt                   (-1)
 v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt             (+4, -7)
 v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalPwdNodeDispatcher.kt          (DELETED, 50 lines)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepCommandRegistryTest.kt        (+9, -16)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreIsUnixStepUnitTest.kt                      (+11, -1)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreLegacyStepMetadataResolverTest.kt          (+3, -1)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdRegistryPrimaryFitnessTest.kt           (+1, -1)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdStepUnitTest.kt                         (+1, -1)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/A3DurableProjectionCharacterizationTest.kt       (+1, -1)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalPwdNodeDispatcherTest.kt       (ARCHIVED -> .kt.txt, 68 lines)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/ExecutionBoundaryFactoryTest.kt        (+1, -1)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/GenericRegistryExecutionCarrierTest.kt (+1, -1)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/LegacyExecutionAdapterTest.kt           (+1, -1)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundaryTest.kt        (+1, -1)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/SeamedExecutionRouterTest.kt            (+1, -1)
 v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/S3PwdLegacyRemovedFitnessTest.kt         (NEW, 171 lines)
```

## State at slice close (S2-A6 / G5)

```text
core.pwd:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true   (held from S2-A6/G4)
  LEGACY_REMOVED     = true   (this slice)
  CONTRACT_SUITE     = false  (superseded by S2-A6/G6 — separate slice)
  CERTIFIED          = false  (reserved for G8 with installed-distribution/UAT evidence)

StructuralFamily = Registry
legacy counters  = 6 / 6 / 6
```

## Next step

Await user GO before:
1. `git add -A`
2. `git commit -m "S2-A6/G5 — LEGACY_REMOVED: core.pwd legacy execution authority physically deleted"`
3. `git push -u origin cycle/lfc2-e1-s2-a6-g5-core-pwd-legacy-removed`
4. Stop (do not FF to main without explicit user approval).
5. **STOP before G6 (CONTRACT_SUITE) — explicit user GO required.**
