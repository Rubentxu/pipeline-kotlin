# S2-A5 / G5 — LEGACY_REMOVED: `core.isUnix` legacy execution authority physically deleted

**Cycle**: `lfc2-e1-s2-a5-g5-core-isunix-legacy-removed`
**Branch**: `cycle/lfc2-e1-s2-a5-g5-core-isunix-legacy-removed`
**Base**: `main` @ `182d7b86` (S2-A5/G4 REGISTRY_PRIMARY flip)
**Working SHA**: uncommitted at receipt writing (slice not yet pushed; STOP before push + FF to main)
**Date**: 2026-09-12

## Counter convergence

```
                S2-A4/G5   S2-A5/G4   S2-A5/G5  (this slice)
legacy IDs          8         7          7
metadata rows       8         8          7
dispatcher files    8         8          7
```

LEGACY_PLUGIN_IDS converged 8 → 7 at S2-A5/G4 (the G4 REGISTRY_PRIMARY flip removed
`core.isUnix` from the closed legacy authority). At S2-A5/G5, the physical legacy forms
(subtype, decoder branch, dispatcher file, metadata row) are physically deleted; the
metadata and dispatcher counters drop 8 → 7 to match.

## Production deletions (4 forms)

1. **Subtype `CanonicalCoreStepCommand.IsUnix`** — deleted from the sealed hierarchy
   (lines 168-176 of `CanonicalCoreStepDecoder.kt`).
2. **Decoder branch `IS_UNIX_PLUGIN_ID -> IsUnix(...)`** — deleted from
   `CanonicalCoreStepDecoder.decode`. The constant `IS_UNIX_PLUGIN_ID` is removed too.
3. **`CanonicalIsUnixNodeDispatcher.kt`** — file deleted
   (`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/`).
4. **`CanonicalCoreStepMetadata["core.isUnix"]` row** — deleted from the legacy metadata
   table.

## Facade surgery (CanonicalNodeDispatcher)

- `private val isUnixDispatcher = CanonicalIsUnixNodeDispatcher()` — removed.
- `is CanonicalCoreStepCommand.IsUnix -> isUnixDispatcher.dispatch(...)` — removed.
- `private fun CanonicalRuntimeContext.isUnixContext() = ...` — removed.

## Test-side surgery

- `CanonicalIsUnixNodeDispatcherTest.kt` → archived as `.kt.txt` (historical G3-G4 evidence).
- `CanonicalCoreStepCommandRegistryTest.IsUnix` test removed (subtype no longer exists).
- `CoreIsUnixStepUnitTest`: G4 "counters drop to 7-8-8 and legacy dispatcher remains physically
  present" → inverted to G5 "counters drop to 7-7-7 and legacy dispatcher source is physically
  removed"; new `assertThrows(IllegalArgumentException::class.java)` proves the legacy metadata
  authority no longer answers for `core.isUnix`.
- `CoreIsUnixRegistryPrimaryFitnessTest`: row 8 (the G4 "legacy source code is physically present"
  assertion) preserved **verbatim** as G4 REGISTRY_PRIMARY evidence, archived in
  `CoreIsUnixRegistryPrimaryFitnessTestRow8Fixture.kt.txt`. The active file carries the
  `@Disabled` test stub (compiles to an empty body) with explicit reason: G4 body references
  symbols physically removed at G5 (`CanonicalCoreStepCommand.IsUnix`, `CanonicalIsUnixNodeDispatcher`)
  and would not compile. Source-level absence proof lives in the new
  `S3IsUnixLegacyRemovedFitnessTest`. Final class result: 7 active tests pass + 1 disabled
  historical fixture.
- `Main.kt:136` — `core.isUnix` removed from the `NON_CANONICAL_CANONICAL_BRIDGE_ERROR`
  legacy listing.

## Sibling fitness pin updates (8 → 7)

The four sibling S3*LegacyRemovedFitnessTest classes pin the residual legacy keys / metadata
rows / dispatcher files. At S2-A4/G5 they pinned 8/8/8; at S2-A5/G5 they pin 7/7/7.

- `S3EmitEventLegacyRemovedFitnessTest` — `residualIds` and `three residual legacy authorities
  converge to exact eight step snapshots` → seven. Dispatcher set dropped
  `CanonicalIsUnixNodeDispatcher.kt`.
- `S3ErrorLegacyRemovedFitnessTest` — header doc, three counter snapshots (LEGACY_PLUGIN_IDS,
  metadata rows, dispatcher files) updated from 9/9/8 → 7/7/7. Dropped
  `CanonicalIsUnixNodeDispatcher.kt`.
- `S3SleepLegacyRemovedFitnessTest` — `expectedIds`, transitional snapshot test, counters
  8 → 7. Dropped `CanonicalIsUnixNodeDispatcher.kt`.
- `S3WriteFileLegacyRemovedFitnessTest` — same pattern.

## Pin updates (test-side counters)

- `CanonicalCoreStepCommandRegistryTest.sealedSubclasses has exactly 9 entries` → 8 entries.
  Expected set updated to 7 (drop `core.isUnix`); doc comment updated G4 → G5.
- `CoreWriteFileRegistryPrimaryFitnessTest`: counter pin 8 → 7.
- `EmitEventStepContractSuiteTest.no legacy resurrection`: counter pin 8 → 7.

## Anti-over-removal (the G5 law)

The new `S3IsUnixLegacyRemovedFitnessTest` enforces the anti-over-removal half:

- **`CoreIsUnixStep handler emits the UnixDetected event`** — the durable observation of the
  platform identity read MUST remain in the registry Step handler, not in a legacy dispatcher.
- **`PlatformIdentity capability remains the single os-name read authority`** —
  `CanonicalRuntimeCapabilityAccess` keeps `PLATFORM_IDENTITY_CAPABILITY` as the structural
  authority for the `System.getProperty("os.name")` read. Removing it would be over-removal.
- **`CoreIsUnixStep keeps the platform-identity capability declaration`** — the Step's
  `requiredCapabilities` still includes `runtime.platform-identity`, so capability admission
  remains the structural authority.
- **`production metadata resolver serves core.isUnix from the descriptor`** — proves the
  composite resolver (`RegistryStepMetadataResolver.composite(registry)`) still resolves
  `core.isUnix` to the registry descriptor.

## Test evidence (L1)

| Class | File | Tests | Failures | SHA256 |
| --- | --- | --- | --- | --- |
| S3IsUnixLegacyRemovedFitnessTest | architecture-tests | 9 | 0 | bee62574d36d41dd2e054b373ac83a4f96816b4780da3c329732b47d35e0b439 |
| CoreIsUnixRegistryPrimaryFitnessTest | application | 8 (1 @Disabled) | 0 | 40c6076062b04300d2310ec5b7f9e397e41edc9b6e884aee8195f36085b9cc56 |
| CoreIsUnixStepUnitTest | application | 18 | 0 | 17285313d7fbc0722c129b7839e208c1a91f7de922fa40e0e0b5d7bc2ce6248e |
| CanonicalCoreStepCommandRegistryTest | application | 9 | 0 | d9bac2628f3fffa91ab6977ffd64621ef4afa122faf909b599ff9dcf26f5b1c2 |
| ScriptedIsUnixCompilerMappingTest | application scripted | 10 | 0 | 6d571873db0136f83d41a637a19d574dc6f5bd74b7b67a5b4160d5245a9ac93d |
| ScriptedIsUnixRuntimeTest | application scripted | 13 | 0 | f901c27ac0bb61a9432538a57b21210d511d6a2d4b0673b886dd2b7564e019bb |

## Test evidence (L2 — sibling fitness convergence)

| Class | Tests | Failures | SHA256 |
| --- | --- | --- | --- |
| S3EmitEventLegacyRemovedFitnessTest | 8 | 0 | ed7011b68bc525490020bdf01b3761ef581359bdcda3e2e30df216d13ab14209 |
| S3ErrorLegacyRemovedFitnessTest | 12 | 0 | 16bf48a4522e901e00b1260625c6cbb8cdf038d2710e85f5db98016b44172220 |
| S3SleepLegacyRemovedFitnessTest | 4 | 0 | 49d052b454aa61fd45b09e215c80c409830b9624860195b3170fe4bcc1572438 |
| S3WriteFileLegacyRemovedFitnessTest | 4 | 0 | 452066e6de3811e01ae33946d2cde8a3e7b745647f8cf7bc3598e38be90a32f6 |

Total: **95 tests pass across the slice surface (L1 + L2: 9+8+18+9+10+13 + 8+12+4+4 = 95).**

## Rule 16 (base-baseline identity) — verified

L3 full `:pipeline-application:test` run: **36 failures**, all byte-identical to the frozen
base-SHA baseline (`0f53e487`, pre-S2-A4/G5). The 12 `CanonicalDurableRunCoordinatorTest`
failures in this run match the same 12 documented as pre-existing in
`S2_A4_CORE_EMITEVENT_G5_LEGACY_REMOVED_RECEIPT.md` (Rule 16).

Failure-by-class breakdown (all pre-existing, not regressions of this slice):

| Class | Failures | Origin |
| --- | --- | --- |
| `CanonicalDurableRunCoordinatorTest` | 12 | pre-S2-A2 (legacy decode path coupling) |
| `UatLocal009TopStepsTest` | 7 | pre-existing (writeFile/archiveArtifacts) |
| `DualExecutionSeamCharacterizationTest` | 3 | pre-S2-A4 (characterization) |
| `UatLocal008CredentialsTest` | 2 | pre-existing (credentials corpus drift) |
| `UatLocal007SandboxProfileTest` | 2 | pre-existing (sandbox profile) |
| `CoreLegacyStepMetadataResolverTest` | 2 | pre-S2-A2/G5 (sleep removed) |
| `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` | 1 | pre-S2-A5 (sh A4 flip) |
| `CompatibilityCorpusTest` | 1 | pre-existing |
| `UatCompat001CorpusSmokeRunTest` | 1 | pre-existing (corpus smoke) |
| `UatLocal005CheckoutGitTest` | 1 | pre-existing (git poll) |
| `RegistryStepMetadataResolverTest` | 1 | pre-S2-A5 |
| `DurableProtocolInvocationCharacterizationTest` | 1 | pre-S2-A4 (characterization) |
| `CanonicalRuntimeCapabilityAccessTest` | 1 | pre-LFC-2R (PLATFORM_IDENTITY added at R2) |

**Total: 36 (Rule 16 baseline held). No regressions introduced by this slice.**

## Forbidden in G5 (held)

- No `core.pwd` work — separate slice (`pwd` is still in LEGACY_PLUGIN_IDS).
- No `core.milestone` work — separate slice.
- No G3-A4.2 / ShellOperations work — separate slice.
- No G6/G7/G8 auto-progression — STOP between slices per user preference.
- No commit to `main` without explicit user GO — push + FF await approval.

## Files changed (15)

```
 v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt   (-15, +5)
 v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt  (-1)
 v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt                       (-1)
 v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalIsUnixNodeDispatcher.kt  (DELETED, 43 lines)
 v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt       (+4, -7)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepCommandRegistryTest.kt   (+9, -16)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreIsUnixRegistryPrimaryFitnessTest.kt  (+12, -14)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreIsUnixStepUnitTest.kt               (+10, -12)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreWriteFileRegistryPrimaryFitnessTest.kt (+2, -2)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/EmitEventStepContractSuiteTest.kt       (+2, -2)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalIsUnixNodeDispatcherTest.kt (ARCHIVED -> .kt.txt, 47 lines)
 v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreIsUnixRegistryPrimaryFitnessTestRow8Fixture.kt.txt (NEW, 35 lines)
 v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/S3EmitEventLegacyRemovedFitnessTest.kt   (+5, -9)
 v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/S3ErrorLegacyRemovedFitnessTest.kt       (+12, -22)
 v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/S3SleepLegacyRemovedFitnessTest.kt       (+4, -8)
 v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/S3WriteFileLegacyRemovedFitnessTest.kt  (+8, -8)
 v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/S3IsUnixLegacyRemovedFitnessTest.kt    (NEW, 171 lines)
```

## State at slice close (S2-A5 / G5)

```text
core.isUnix:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true
  LEGACY_REMOVED     = true   (this slice)
  CONTRACT_SUITE     = false  (superseded by S2-A5/G6 — see
                              docs/v2/07-uat/S2_A5_CORE_ISUNIX_G6_CONTRACT_CERTIFICATION_RECEIPT.md)
  CERTIFIED          = false  (reserved for G8 with installed-distribution/UAT evidence)

StructuralFamily = Registry
legacy counters  = 7 / 7 / 7
```

## Next step

Await user GO before:
1. `git add -A`
2. `git commit -m "S2-A5/G5 — LEGACY_REMOVED: core.isUnix legacy execution authority physically deleted"`
3. `git push -u origin cycle/lfc2-e1-s2-a5-g5-core-isunix-legacy-removed`
4. Stop (do not FF to main without explicit user approval).
