# S2-A1 G4 Receipt — `core.error` Migration Readiness Fitness

**Cycle branch:** `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
**Cycle commit:** see `git log` on the branch
**Date:** 2026-09-11T10:25Z
**Status:** GREEN — Safe-to-flip criteria met for G5.

## Law (per user directive)

```text
REGISTRY_IMPLEMENTATION_READY
+ SEMANTIC_PARITY_PROVEN (G3)
+ LEGACY_AUTHORITY_STILL_INTACT
= SAFE_TO_FLIP
```

## What G4 proves (machine-derived, structural, no mobile assertions)

```text
1. REGISTRY_IMPLEMENTATION_READY
   - CoreErrorStep.KEY == PluginStepId("core.error")
   - CoreErrorStep.definition exists and is non-null
   - production CoreStepRegistryFactory.registry() resolves core.error to exactly
     CoreErrorStep.definition (no parallel definition)
   - production registry contains {core.echo, core.sh, core.error}

2. NO STEP-SPECIFIC BRANCHES ADDED
   - RegistryExecutionBoundary uses the generic TypedStepOutput projection
     (no concrete core.* literal)
   - per-Step dispatcher directory contains exactly 12 files (no new dispatcher added)
   - CanonicalNodeDispatcher facade still routes is CanonicalCoreStepCommand.Error
     through legacy (the variant still exists; deletion is G6)
   - CanonicalCoreStepDecoder has exactly 12 pluginId branches (no parallel branch added)

3. LEGACY_AUTHORITY_STILL_INTACT (precondition, not debt)
   - core.error IS in LEGACY_PLUGIN_IDS
   - StructuralFamilyResolver(core.error) == LegacyCore
   - CanonicalErrorNodeDispatcher file is present on disk
   - legacy metadata row for core.error exists
   - legacy decoder declares ERROR_PLUGIN_ID and its branch
   - legacy dispatcher still produces StepOutcome.Failure end-to-end

4. LEGACY COUNTERS INVARIANT
   - LEGACY_PLUGIN_IDS has exactly 12 entries
   - CanonicalCoreStepMetadata has exactly 12 rows
```

## Gate (machine-derived, fresh run on this branch)

```text
CoreErrorMigrationReadinessFitnessTest (G4)               16/16 GREEN
CoreErrorLegacyRegistryParityTest (G3)                    16/16 GREEN
CoreErrorStepUnitTest (G1)                               20/20 GREEN
CoreErrorStepG2RegistryAdmissionTest (G2)                 6/6  GREEN
CanonicalErrorNodeDispatcherTest                           1/1  GREEN
ErrorHandlingTest                                          7/7  GREEN
UatStep003ErrorAbortTest                                   1/1  GREEN
UatLocal012ErrorHandlingTest                               8/8  GREEN
UatComp002ErrorSourceMappedTest (scripting-kotlin24)       3/3  GREEN
CliCompileErrorExitsOneTest                                3/3  GREEN
LegacyEchoUnreachableProofTest                             4/4  GREEN
CoreLegacyStepMetadataResolverTest                         4/4  GREEN
CanonicalCoreStepCommandRegistryTest                      14/14 GREEN
RegistryStepMetadataResolverTest                           5/5  GREEN
A4_1DescriptorRecoveryCharacterizationTest                 6/6  GREEN
S3EchoLegacyRemovedFitnessTest (architecture-tests)        7/7  GREEN
─────────────────────────────────────────────────────────────────────
Total                                                   121/121 GREEN
```

### Counter invariant at G4 (unchanged)

```text
LEGACY_PLUGIN_IDS  : 12  sha256 b0d33e63c911e012be66c220d9b00f1a8824780fa67cfaa64db041b30b1de2c2
metadata rows       : 12
dispatcher classes  : 12

StructuralFamilyResolver(core.error) == LegacyCore
CanonicalErrorNodeDispatcher.kt present on disk
Legacy decoder branch for core.error present
```

## Test-class design — three distinct classes for three distinct states

Per the user's directive: do not reuse `S3ErrorLegacyRemovedFitnessTest` for the
transient G4 state, and do not write mobile assertions that get inverted later.

```text
G4:  CoreErrorMigrationReadinessFitnessTest (this commit)
      — asserts the G4 transient state: registry ready + legacy intact
      — does NOT assert "core.error NOT in LEGACY_PLUGIN_IDS" (mobile)
      — does NOT assert "CanonicalErrorNodeDispatcher absent" (mobile)
      — asserts STRUCTURAL FACTS about the current state (counters == 12,
        file exists, resolver returns LegacyCore)

G5:  CoreErrorRegistryPrimaryFitnessTest (next cycle, after GO)
      — asserts the G5 state: production path is registry, legacy is unreachable
      — asserts "core.error NOT in LEGACY_PLUGIN_IDS" (the flip)
      — asserts StructuralFamilyResolver(core.error) == Registry (the receipt)
      — legacy dispatcher/dispatcher MAY still physically exist
      — DOES NOT assert file absence (that is G6)

G6:  S3ErrorLegacyRemovedFitnessTest (final, irreversible)
      — asserts source-level absence of all legacy forms
      — file CanonicalErrorNodeDispatcher.kt MUST NOT exist
      — sealed variant CanonicalCoreStepCommand.Error MUST be removed
      — decoder branch MUST be removed
      — metadata row MUST be removed
      — and the structural assertions from G5 still hold
```

This separation ensures:
- G4's assertions never have to be inverted.
- The `LegacyRemoved` test class is reserved for the IRREVERSIBLE state and cannot be
  confused with the transient G4 readiness state.
- The `LegacyCore → Registry` flip is one of the most important receipts of S2-A1 and
  lives in a dedicated G5 class.

## What G4 does NOT prove (deferred to G5/G6)

- That legacy execution becomes unreachable (G5).
- That legacy production code is deleted (G6).
- Real-distribution end-to-end through the registry path (G8).
- StepContractSuite certification row coverage (G7).

## Awaiting GO for G5

G5 will:

1. Edit `CanonicalCoreStepDecoder.LEGACY_PLUGIN_IDS` to remove `"core.error"`. Single
   line edit.
2. Verify with `CoreErrorRegistryPrimaryFitnessTest` (new file, asserts the flip
   structurally):
   - `core.error NOT in LEGACY_PLUGIN_IDS`
   - `StructuralFamilyResolver(core.error) == Registry`
   - `CanonicalErrorNodeDispatcher` MAY still exist; it is unreachable, not deleted.
   - Legacy counters: LEGACY_PLUGIN_IDS = 11; metadata rows = 12; dispatchers = 12
     (the user explicitly said: "los otros dos contadores seguirán temporalmente en 12
     hasta G6 si dispatcher/metadata siguen físicamente presentes").
3. Re-run the full gate to prove the flip is observable AND legacy production
   behaviour is unchanged at the canonical-event level (same `StepFailed{USER,
   "test error message"}` projection).

After G5 GREEN, the next GO is G6 (source-level deletion) which produces the
irreversible `S3ErrorLegacyRemovedFitnessTest` and `12 → 11` across all three counters.
