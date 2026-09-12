# S2-A6 / G4 — `core.pwd` REGISTRY_PRIMARY Flip

**Cycle:** `lfc2-e1-s2-a6-core-pwd-authority` (G4 executed inside the same SDDK cycle)
**Branch:** `cycle/lfc2-e1-s2-a6-g4-core-pwd-authority-flip`
**Base:** `ee87acdb` (S2-A6/G3R trunk-integrated at `origin/main`)
**Status:** FLIP COMPLETE — STOP after this receipt; G5 requires explicit GO.
**Date:** 2026-09-12T12:35Z

## Scope (user GO)

Single-point structural change: `core.pwd ∉ LEGACY_PLUGIN_IDS`. The registry becomes
the production routing authority. Legacy source code remains physically present
(LEGACY_UNREACHABLE, not LEGACY_REMOVED) until G5.

**Untouched (per user mandate):**
- `CorePwdStep` semantics — handler, contract, capability declaration unchanged
- `WorkspaceIdentity` capability bridge — unchanged
- `PipelineDsl.pwd()` / `pwd(tmp=false)` / `pwd(tmp=true)` — DSL surface unchanged
  (the G3R slice already redirects `pwd(tmp=true)` through the registry by lowering
  to `StepSpec.RegistryStepSpec`)
- `CanonicalPwdNodeDispatcher` implementation — kept type-loadable, source still on disk
- `core.milestone`, `core.isUnix`, any other legacy key
- `CanonicalCoreStepMetadata["core.pwd"]` row — kept until G5 (LEGACY_UNREACHABLE)

## G4 production flip — single source change

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt:97`

```diff
 val LEGACY_PLUGIN_IDS: Set<String> = setOf(
     "core.milestone",
     "core.deleteDir",
     "core.cleanWs",
     "core.load",
-    "core.pwd",
     "core.waitUntil",
     "core.archiveArtifacts",
 )
```

After the flip:
- `StructuralFamilyResolver(core.pwd)` returns `Registry` (was `LegacyCore`).
- `RegistryStepMetadataResolver.resolve(core.pwd)` reads from `CorePwdStep.descriptor`
  (was the legacy `CanonicalCoreStepMetadata["core.pwd"]` row).
- The composite metadata resolver, with `core.pwd` absent from the legacy set, fails
  closed if the key is absent from the registry (no LegacyCore fallback).
- `CorePwdStep.definition.contract.descriptor.effects == {READ_ONLY}` and
  `replayPolicy == MEMOIZED` byte-match the legacy row metadata (proof in row 8 of
  the new fitness test).

## G4 post-flip counters

```text
core.pwd:
  REGISTERED         = true   (G1 candidate, CoreStepRegistryFactory since S2-A6/G1)
  REGISTRY_PRIMARY   = true   (NEW: G4 flip)
  LEGACY_UNREACHABLE = true   (NEW: legacy dispatcher still on disk but routing unreachable)
  LEGACY_REMOVED     = false  (G5 work)
  CONTRACT_SUITE     = false  (G6 work)
  CERTIFIED          = false  (installed final certification)

StructuralFamily   = Registry
MIGRATION_READY    = true   (since G3; preserved through G4)
```

Transitional counter movement (`legacy executable IDs / metadata rows / dispatcher sources`):
- pre-S2-A6/G4 (post-S2-A5/G4): **7 / 8 / 8**
- post-S2-A6/G4 (this slice): **6 / 8 / 8** (note: pwd row in CanonicalCoreStepMetadata is preserved until G5)

The metadata row + dispatcher file remain until G5.

## Legacy source code physically present (LEGACY_UNREACHABLE, removal is G5)

```text
legacy dispatcher physically present = true   (CanonicalPwdNodeDispatcher.kt on disk)
legacy decoder physically present    = true   (CanonicalCoreStepDecoder.kt PWD_PLUGIN_ID branch)
legacy metadata row physically present= true   (CanonicalCoreStepMetadata["core.pwd"])
legacy execution reachable           = false  (not in LEGACY_PLUGIN_IDS; StructuralFamilyResolver routes Registry)
```

These four facts together are the G4 invariant. G5 deletes them; G4 only certifies
that they are present-but-unreachable.

## G4 fitness (dedicated, 8 rows, all PROVEN)

New file: `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdRegistryPrimaryFitnessTest.kt`

| # | Assertion | Result |
|---|---|---|
| 1 | core.pwd registered in production registry | PASS |
| 2 | core.pwd absent from LEGACY_PLUGIN_IDS | PASS |
| 3 | StructuralFamilyResolver classifies pwd as Registry | PASS |
| 4 | core.pwd.tmp continues to resolve as Registry (G3T invariant preserved) | PASS |
| 5 | legacy routing cannot select pwd (no LegacyCore fallback) | PASS |
| 6 | registry execution produces typed PwdOutput via CorePwdStep | PASS |
| 7 | WorkspaceIdentity + EventSink capability admission preserved | PASS |
| 8 | registry descriptor metadata matches legacy row (effects + replayPolicy) | PASS |

The fitness deliberately stops short of deleting legacy source. That is G5.

## Verification (fresh XML, this session, this branch)

### core.pwd family (the canonical SUT)

| Suite | Tests | Failures | Errors | Skipped | XML sha256 |
|---|---|---|---|---|---|
| `CorePwdRegistryPrimaryFitnessTest` | 8 | 0 | 0 | 0 | `6f3c721cb582a794bac20a286dcdc9f0ba03816b95e296f6257cf35efeed0d79` |
| `CorePwdStepUnitTest` (post-flip) | 24 | 0 | 0 | 2 (historical snapshot `@Disabled`) | `ab98bb45070930a7...` |
| `CorePwdTmpStepUnitTest` (post-flip) | 24 | 0 | 0 | 2 (historical snapshot `@Disabled`) | `fa5d1db48dd4ee08...` |
| `CanonicalCoreStepCommandRegistryTest` | 9 | 0 | 0 | 0 | `7fd2eca29a305f66...` |
| `PipelineDslPwdLoweringTest` | 5 | 0 | 0 | 0 | `cedebb465da09a06...` |

### Peer suites from earlier slices (registry-primary tests for `core.isUnix`, `core.sleep`, `core.file.writeFile`, `core.emit.event`, `core.error`)

| Suite | Tests | Failures | Errors | Skipped | XML sha256 |
|---|---|---|---|---|---|
| `CoreIsUnixRegistryPrimaryFitnessTest` | 9 | 0 | 0 | 2 (one S2-A5/G4 + one S2-A6/G4 historical `@Disabled`) | `fce3935a1e54583d...` |
| `CoreIsUnixStepContractSuiteTest` | 22 | 0 | 0 | 0 | `10909d3a26dc2008...` |
| `CoreIsUnixStepUnitTest` | 19 | 0 | 0 | 1 (historical S2-A5/G5 counter `@Disabled`) | `0392964a976aae8b...` |
| `CoreSleepRegistryPrimaryFitnessTest` | 9 | 0 | 0 | 4 (historical snapshots `@Disabled`) | `9066e41210b03f25...` |
| `CoreWriteFileRegistryPrimaryFitnessTest` | 6 | 0 | 0 | 1 (S2-A5/G4 historical `@Disabled`) | `da0e3c14f1700e54...` |
| `CoreEmitEventRegistryPrimaryFitnessTest` | 12 | 0 | 0 | 2 (S2-A4 + S2-A5 historical `@Disabled`) | `59fd3337bd72491e...` |
| `CoreErrorRegistryPrimaryFitnessTest` | 16 | 0 | 0 | 3 (historical snapshots `@Disabled`) | `dbde3e531b695e21...` |
| `EmitEventStepContractSuiteTest` | 29 | 0 | 0 | 1 (S2-A5/G5 historical `@Disabled`) | `43c904bb42528ede...` |

**Aggregate: 192 tests / 0 failures / 0 errors / 18 historical `@Disabled` snapshots.**

### Historical 7-key snapshot tests `@Disabled` (per user "do not invert historical meaning")

| Test | Reason |
|---|---|
| `CorePwdStepUnitTest.structural family - core pwd stays LegacyCore while in LEGACY_PLUGIN_IDS` | S2-A6/G1 snapshot; superseded by `core pwd registry flip — structural family resolves to Registry post-flip`. Preserved verbatim; deleted when the legacy narrative ends. |
| `CorePwdStepUnitTest.counters - 7 7 7 unchanged by S2-A6 G1 registration only` | S2-A6/G1 snapshot; superseded by `core pwd registry flip — 6 residual keys remain post-S2-A6-G4`. |
| `CorePwdTmpStepUnitTest.structural family - core pwd stays LegacyCore (post-G1 invariant unchanged)` | S2-A6/G3T snapshot; superseded by the post-flip counter test in `CorePwdStepUnitTest`. |
| `CorePwdTmpStepUnitTest.counters - legacy 7 7 7 unchanged, core pwd tmp is a NEW registry entry, NOT a legacy entry` | S2-A6/G3T snapshot; superseded by the post-flip counter test in `CorePwdStepUnitTest`. |
| `CoreSleepRegistryPrimaryFitnessTest.registry post-S2-A5-G4 — 7 residual legacy keys remain` | S2-A5/G4 snapshot; superseded by `registry post-S2-A6-G4 — 6 residual legacy keys remain`. |
| `CoreSleepRegistryPrimaryFitnessTest.production registry contains exactly the registered core steps (... + isUnix S2-A5 G1 candidate)` | S2-A5/G4 snapshot; superseded by the post-S2-A6/G4 9-key registry shape test. |
| `CoreEmitEventRegistryPrimaryFitnessTest.core isUnix registry flip — 7 residual legacy keys remain post-S2-A5-G4` | S2-A5/G4 snapshot; superseded by `core pwd registry flip — 6 residual keys remain post-S2-A6-G4`. |
| `CoreErrorRegistryPrimaryFitnessTest.LEGACY_PLUGIN_IDS post-S2-A5-G4 — 7 residual legacy keys remain` | S2-A5/G4 snapshot; superseded by `LEGACY_PLUGIN_IDS post-S2-A6-G4 — 6 residual legacy keys remain`. |
| `CoreIsUnixRegistryPrimaryFitnessTest.G4 flip — core dot isUnix is absent from LEGACY_PLUGIN_IDS` | S2-A5/G4 snapshot (asserted 7-key); superseded by `G4 flip - core dot isUnix stays absent from LEGACY_PLUGIN_IDS post-S2-A6-G4` (asserts 6-key). |
| `CoreIsUnixStepUnitTest.counters drop to 7-7-7 and legacy dispatcher source is physically removed` | S2-A5/G5 snapshot (asserts 7-key); superseded by `counters drop to 6-6-7 post-S2-A6-G4`. |
| `CoreWriteFileRegistryPrimaryFitnessTest.G4 flip — core dot file dot writeFile is NOT in LEGACY_PLUGIN_IDS` | S2-A5/G4 snapshot (asserts 7-key); superseded by `G4 flip post-S2-A6-G4 - 6 residual legacy keys remain`. |
| `EmitEventStepContractSuiteTest.no legacy resurrection — irreversible G5 state holds from inside the contract suite` | S2-A5/G5 snapshot (asserts 7-key); superseded by `no legacy resurrection — irreversible G5 state holds post-S2-A6-G4` (asserts 6-key). |

Each `@Disabled` annotation carries the explicit reason; the test bodies are preserved
verbatim for historical traceability. New live tests assert the post-S2-A6/G4 state.

**Result of the S2-A6 / G4 modification: 0 test regressions for `core.pwd`. Pre-existing
failures on `core.sleep` / `core.sh` / `core.file.writeFile` / `CompatibilityCorpusTest`
fixture14 / `UatLocal005CheckoutGitTest` SC-007 / `UatCompat001CorpusSmokeRunTest` /
`UatLocal007SandboxProfileTest` SB-S-010 remain unchanged (NOT introduced by this
slice; same failure message at base `ee87acdb` per worktree method).**

## Command (L1+L2 evidence, full rerun, canary discipline per AGENTS.md rule 25)

```bash
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CorePwdRegistryPrimaryFitnessTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CorePwdStepUnitTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CorePwdTmpStepUnitTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CanonicalCoreStepCommandRegistryTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CoreIsUnixRegistryPrimaryFitnessTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CoreIsUnixStepContractSuiteTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CoreIsUnixStepUnitTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CoreSleepRegistryPrimaryFitnessTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CoreWriteFileRegistryPrimaryFitnessTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CoreEmitEventRegistryPrimaryFitnessTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'CoreErrorRegistryPrimaryFitnessTest'
timeout 60 ./gradlew -p v2 :pipeline-application:test --tests 'EmitEventStepContractSuiteTest'
timeout 60 ./gradlew -p v2 :pipeline-scripting-api:test --tests 'PipelineDslPwdLoweringTest'
```

Result: **all 13 suites green; 192 tests / 0 failures / 0 errors / 18 historical
`@Disabled` snapshots; fresh XML written for every suite; all sha256 digests captured
at the timestamps above (per AGENTS.md rule 25).**

## Fail-closed guarantee (re-stated)

```text
registry resolution succeeds       → execute registry Step
registry resolution fails          → fail closed
NEVER:
  → fall back to CanonicalPwdNodeDispatcher
```

The fitness row 5 (`legacy routing cannot select pwd`) verifies this: an empty
registry fails closed via `EngineInvariantViolation`; the legacy row is not consulted
as a fallback.

## Counter movement summary (project dashboard / roadmap)

```text
S2-A4/G4 (post-core.emit.event flip)        → legacy executable IDs = 8
S2-A5/G4 (post-core.isUnix flip)             → legacy executable IDs = 7
S2-A6/G4 (post-core.pwd flip, this slice)    → legacy executable IDs = 6

legacy metadata rows (preserved until G5):
  S2-A4/G4 = 8
  S2-A5/G4 = 8  (isUnix metadata row removed at S2-A5/G5)
  S2-A6/G4 = 8  (pwd metadata row preserved at G4, deletion at G5)

legacy dispatchers (preserved until G5):
  S2-A4/G4 = 8
  S2-A5/G4 = 8  (isUnix dispatcher removed at S2-A5/G5)
  S2-A6/G4 = 8  (pwd dispatcher preserved at G4, deletion at G5)
```

`M → 0` convergence: 8 → 0 over the LFC-2E1-S2 cycle. At S2-A6/G4 we are at M=6.

## Diff summary

```text
 11 files changed, 208 insertions(+), 5 deletions(-)
 + 1 new file (CorePwdRegistryPrimaryFitnessTest.kt)
```

**Production change: 1 line removed from LEGACY_PLUGIN_IDS** + comment header
recording the G4 flip with full provenance (subtype, decoder branch, dispatcher file,
metadata row all still present until G5).

**Test changes: 11 files updated** (10 modified + 1 new). All modifications follow the
established pattern: `@Disabled` historical pre-flip snapshot tests with explicit
reasons + new post-flip tests asserting the 6-key state. No historical meaning is
inverted; bodies preserved verbatim for traceability.

## Resumption target (next cycle, NOT this PR)

```text
S2-A6/G5 → physical legacy removal (LEGACY_REMOVED)
            delete CanonicalPwdNodeDispatcher.kt
            delete CanonicalCoreStepCommand.Pwd subtype
            delete PWD_PLUGIN_ID decoder branch
            delete CanonicalCoreStepMetadata["core.pwd"] row
            converge counters 6 / 8 / 8 → 6 / 6 / 6
S2-A6/G6 → StepContractSuite (PwdStepContractSuite)
            installed/final certification
S2-A6 CLOSED
```

Until then:
- `core.pwd` is REGISTERED, REGISTRY_PRIMARY, LEGACY_UNREACHABLE.
- The flip is done; the legacy removal is G5.
- No authority auto-progression.

## STOP

G5 (physical legacy removal) requires explicit user GO. This slice MUST NOT proceed
past this receipt without explicit authorization.
