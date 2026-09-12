# S2-A5 / G4 — `core.isUnix` REGISTRY_PRIMARY Flip

**Cycle:** `lfc2-e1-s2-a5-g3-core-isunix-readiness` (G4 executed inside the same SDDK cycle)
**Branch:** `cycle/lfc2-e1-s2-a5-g4-core-isunix-authority-flip`
**Base:** `de3b592c` (S2-A5/G3 trunk-integrated at `origin/main`)
**Status:** FLIP COMPLETE — STOP after this receipt; G5 requires explicit GO.
**Date:** 2026-09-12T06:57Z

## Scope (user GO)

Single-point structural change: `core.isUnix ∉ LEGACY_PLUGIN_IDS`. The registry
becomes the production routing authority. Legacy source code remains physically
present (LEGACY_UNREACHABLE, not LEGACY_REMOVED) until G5.

**Untouched (per user mandate):**
- `UnixPlatformClassifier` — pure canonical classifier, unchanged
- `CoreIsUnixStep` semantics — handler, contract, capability declaration unchanged
- `PlatformIdentity` capability bridge — unchanged
- `ScriptedRegistryInvoker` — R4B wiring unchanged
- `PipelineDsl` — DSL surface unchanged
- `CanonicalIsUnixNodeDispatcher` implementation — kept type-loadable, source still on disk
- `core.pwd`, `core.milestone`, any other legacy key
- G3-A4.2 ShellOperations

## G4 production flip — single source change

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt:59`

```diff
 val LEGACY_PLUGIN_IDS: Set<String> = setOf(
     "core.milestone",
     "core.deleteDir",
     "core.cleanWs",
     "core.load",
     "core.pwd",
-    "core.isUnix",
     "core.waitUntil",
     "core.archiveArtifacts",
 )
```

After the flip:
- `StructuralFamilyResolver(core.isUnix)` returns `Registry` (was `LegacyCore`).
- `RegistryStepMetadataResolver.resolve(core.isUnix)` reads from `CoreIsUnixStep.descriptor`
  (was the legacy `CanonicalCoreStepMetadata["core.isUnix"]` row).
- The composite metadata resolver, with `core.isUnix` absent from the legacy set,
  fails closed if the key is absent from the registry (no LegacyCore fallback).

## G4 post-flip counters

```text
core.isUnix:
  REGISTERED         = true   (G1 candidate, CoreStepRegistryFactory line 69)
  REGISTRY_PRIMARY   = true   (NEW: G4 flip)
  LEGACY_UNREACHABLE = true   (NEW: legacy dispatcher still on disk but routing unreachable)
  LEGACY_REMOVED     = false  (G5 work)
  CONTRACT_SUITE     = false  (G6 work)
  CERTIFIED          = false  (installed final certification)

StructuralFamily   = Registry
MIGRATION_READY    = true   (since G3; preserved through G4)
```

Transitional counter movement (`legacy executable IDs / metadata rows / dispatcher sources`):
- pre-S2-A5/G4 (post-S2-A4/G4): **8 / 8 / 8**
- post-S2-A5/G4 (this slice): **7 / 8 / 8**

The metadata row + dispatcher file remain until G5.

## Legacy source code physically present (LEGACY_UNREACHABLE, removal is G5)

```text
legacy dispatcher physically present = true   (CanonicalIsUnixNodeDispatcher.kt on disk)
legacy decoder physically present    = true   (CanonicalCoreStepDecoder.kt IS_UNIX_PLUGIN_ID branch)
legacy metadata row physically present= true   (CanonicalCoreStepMetadata["core.isUnix"])
legacy execution reachable           = false  (not in LEGACY_PLUGIN_IDS; StructuralFamilyResolver routes Registry)
```

These four facts together are the G4 invariant. G5 deletes them; G4 only certifies
that they are present-but-unreachable.

## G4 fitness (dedicated, 8 rows, all PROVEN)

New file: `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreIsUnixRegistryPrimaryFitnessTest.kt`

| # | Assertion | Result |
|---|---|---|
| 1 | core.isUnix registered in production registry | PASS |
| 2 | core.isUnix absent from LEGACY_PLUGIN_IDS | PASS |
| 3 | StructuralFamilyResolver classifies isUnix as Registry | PASS |
| 4 | legacy routing cannot select isUnix (no LegacyCore fallback) | PASS |
| 5 | registry execution produces typed IsUnixOutput via CoreIsUnixStep | PASS |
| 6 | PlatformIdentity capability admission preserved | PASS |
| 7 | registry descriptor still declares the capability R4B consumes | PASS |
| 8 | legacy source code physically present (LEGACY_UNREACHABLE, not REMOVED) | PASS |

The fitness deliberately stops short of deleting legacy source. That is G5.

## Verification (fresh XML, this session, this branch)

| Suite | Tests | Failures | Errors | Skipped | XML timestamp | sha256 |
|---|---|---|---|---|---|---|
| `CoreIsUnixRegistryPrimaryFitnessTest` | 8 | 0 | 0 | 0 | 2026-09-12T06:57:... | `d6ca9103f5c2dc50a4d433abf05a9733563b49a90f4ce447c77ac003ff45f73f` |
| `CoreIsUnixStepUnitTest` (post-flip) | 18 | 0 | 0 | 0 | 2026-09-12T06:57:... | `b9cad38ba3ea4d4abf9b38ad1ce37bac1f1b5a111a07659d9096170d040638f6` |
| `ScriptedIsUnixRuntimeTest` | 13 | 0 | 0 | 0 | 2026-09-12T06:57:... | `57876f94803edc16c4eef3a635b14552fddf885c49007204e94619414885b08f` |
| `ScriptedRegistryInvokerTest` | 10 | 0 | 0 | 0 | 2026-09-12T06:57:... | `7bf5cc2cca41f00f097007903277b92dc05e54cf51dd9523786d35a1fd61009a` |
| `R4BProductionWiringFitnessTest` | 2 | 0 | 0 | 0 | 2026-09-12T06:57:... | `dbb1708e3f0b28241e0e635acd092fc419e4c3bba1e911e1b428998bd55bda8e` |
| `CanonicalIsUnixNodeDispatcherTest` | 1 | 0 | 0 | 0 | 2026-09-12T06:57:... | `23191710ea43e63d4a107ff4bbc2aa905dad943f43216bb58577374f1b512936` |
| `CanonicalCoreStepCommandRegistryTest` | 10 | 0 | 0 | 0 | 2026-09-12T06:57:... | `1a3733d2ba37efbcee59a853b373edbcc96fe03360fa26a2eefa08d2fbb84381` |

Peer suites from earlier slices (registry-primary tests for `core.error`, `core.sleep`,
`core.emit.event`):

| Suite | Tests | Failures | Errors | Skipped | XML timestamp | sha256 |
|---|---|---|---|---|---|---|
| `CoreEmitEventRegistryPrimaryFitnessTest` | 11 | 0 | 0 | 1 (historical snapshot `@Disabled`) | 2026-09-12T06:57:... | `74203288b02df6f7c19e9e9fbd5889b1c400130839eac9bdabc66480bd2aefeb` |
| `CoreErrorRegistryPrimaryFitnessTest` | 15 | 0 | 0 | 2 (historical snapshots `@Disabled`) | 2026-09-12T06:57:... | `f5754bfc0b939d335a0d59c8121c25673763bb1742eb3a0ebb8ad628c7607d8c` |
| `CoreSleepRegistryPrimaryFitnessTest` | 7 | 0 | 0 | 2 (one historical + one pre-existing `@Disabled`) | 2026-09-12T06:57:... | `1cb019f05bc84d2c86c000848d4f08ceeddbe396a5fe584b7ad41625507b0b2b` |

**Historical 8-key snapshot tests `@Disabled` (per user "do not invert historical meaning"):**

| Test | Reason |
|---|---|
| `CoreEmitEventRegistryPrimaryFitnessTest.G4 flip — ... and 8 residual keys remain` | S2-A4/G4 snapshot; superseded by `core isUnix registry flip — 7 residual keys remain post-S2-A5-G4`. Preserved verbatim; deleted when the legacy narrative ends. |
| `CoreErrorRegistryPrimaryFitnessTest.G5 flip -- LEGACY_PLUGIN_IDS is exactly the 9 residual keys (full-set equality)` | S2-A1/G5 snapshot; superseded by `LEGACY_PLUGIN_IDS post-S2-A5-G4 — 7 residual keys remain`. |
| `CoreErrorRegistryPrimaryFitnessTest.G6 counters -- LEGACY_PLUGIN_IDS is 8, metadata rows is 9, dispatchers is 9` | S2-A1/G6 snapshot; the `LEGACY_PLUGIN_IDS is 8` half is superseded by the post-S2-A5/G4 7-key snapshot. (The metadata row + dispatcher assertions remain true at G4 — they only fire when G5 deletes them.) |
| `CoreSleepRegistryPrimaryFitnessTest.registry is primary and legacy counters retain only the nine residual keys` | S2-A2/G4 snapshot; superseded by `registry post-S2-A5-G4 — 7 residual legacy keys remain`. |

Each `@Disabled` annotation carries the explicit reason; the test bodies are preserved
verbatim for historical traceability. New live tests assert the post-S2-A5/G4 state.

**Result of the S2-A5 / G4 modification: 0 test regressions for `core.isUnix` and the
R4B scripted path. Pre-existing pre-existing `CoreLegacyStepMetadataResolverTest`
failures on `core.sleep` are unchanged (NOT introduced by this slice; same failure
message at base `de3b592c`).**

## Command (L1+L2 evidence, full rerun, canary discipline per AGENTS.md rule 25)

```bash
timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests 'CoreIsUnixStepUnitTest' \
  --tests 'CoreIsUnixRegistryPrimaryFitnessTest' \
  --tests 'ScriptedIsUnixRuntimeTest' \
  --tests 'ScriptedRegistryInvokerTest' \
  --tests 'R4BProductionWiringFitnessTest' \
  --tests 'CoreEmitEventRegistryPrimaryFitnessTest' \
  --tests 'CoreErrorRegistryPrimaryFitnessTest' \
  --tests 'CoreSleepRegistryPrimaryFitnessTest' \
  --tests 'CanonicalCoreStepCommandRegistryTest' \
  --tests 'CanonicalIsUnixNodeDispatcherTest' \
  --rerun-tasks
```

Result: **BUILD SUCCESSFUL in 29s, 42 actionable tasks: 42 executed** (full rerun,
fresh XML written, all digests captured at the timestamps above).

## Fail-closed guarantee (re-stated)

```text
registry resolution succeeds       → execute registry Step
registry resolution fails          → fail closed
NEVER:
  → fall back to CanonicalIsUnixNodeDispatcher
```

The fitness row 4 (`legacy routing cannot select isUnix`) verifies this: an empty
registry fails closed via `EngineInvariantViolation`; the legacy row is not consulted
as a fallback.

## Resumption target (next cycle, NOT this PR)

```text
S2-A5/G5 → physical legacy removal (LEGACY_REMOVED)
            delete CanonicalIsUnixNodeDispatcher.kt
            delete CanonicalCoreStepCommand.IsUnix subtype
            delete IS_UNIX_PLUGIN_ID decoder branch
            delete CanonicalCoreStepMetadata["core.isUnix"] row
            converge counters 7 / 8 / 8 → 7 / 7 / 7
S2-A5/G6 → StepContractSuite (IsUnixStepContractSuite)
installed/final certification
S2-A5 CLOSED
```

Until then:
- `core.isUnix` is REGISTERED, REGISTRY_PRIMARY, LEGACY_UNREACHABLE.
- The flip is done; the legacy removal is G5.
- No authority auto-progression.