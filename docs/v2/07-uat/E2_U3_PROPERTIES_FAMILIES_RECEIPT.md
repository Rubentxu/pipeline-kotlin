# LFC-2E2-EXPANSION U3 — properties families

## Slice scope

U3 closes the third plugin-model expansion slice for the LFC-2E2 cycle.
It adds two new Step families (`utilities.readProperties`, `utilities.writeProperties`)
to the existing OFFICIAL_PLUGIN coordinate `pipeline.utilities.json@1.0.0`,
mirroring the same architecture used for U1 (JSON hardening) and U2 (YAML).

U3 is the third slice that proves the same architectural claim:

> A new capability port, a new typed failure ADT, a new Step family, and a
> new plug-in extension can be added without ANY change to the production
> coordinator, dispatcher, runtime context, or capability seam.

## Architectural claims

| # | Claim | Evidence |
|---|---|---|
| **A1** | A new typed failure ADT (`UtilitiesPropertiesError` + `UtilitiesPropertiesException`) is declared in the plugin package and production core is unaware of it | `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/properties/UtilitiesPropertiesPlugin.kt` (the new file); `Lfc2E2ExpansionGateFitnessTest.G4-6` (production sources contain zero references to `UtilitiesPropertiesError`/`UtilitiesPropertiesException`) |
| **A2** | A new typed capability token (`utilities.properties.operations`) is declared in the plugin and routed through the generic `capabilityAccessFactory` seam | `UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY` (in the new file); `UtilitiesJsonContributor.definitions()` now returns 7 entries (3 JSON + 2 YAML + 2 properties); `Lfc2E2ExpansionGateFitnessTest.G4-6` (production core does NOT know about the token) |
| **A3** | Two new Step families are declared in the same OFFICIAL_PLUGIN coordinate (no new contributor) | `UtilitiesJsonContributor.definitions()` (the same contributor grew from 5 → 7 entries); `Lfc2E2ExpansionGateFitnessTest.G5b` |
| **A4** | The new Steps participate in the canonical durable spine (registry → codec → contract → capability admission → handler → encoded result → journal → events) | `UtilitiesPropertiesStepContractSuiteTest` (12 rows: identity, contract completeness, capability admission, handler semantics, typed failure, real-DSL scenario) |
| **A5** | The new capability port is fail-closed: when the capability is missing the Step is rejected before the handler runs | `capability admission - missing properties capability REJECTS readProperties before handler runs` |
| **A6** | The new Steps project a unified typed Output (`JsonObject`) at the capability boundary, just like the YAML projection in U2 | `ReadPropertiesOutput.entries: JsonObject`, `WritePropertiesInput.value: JsonObject`; `handler - writeProperties then readProperties round-trip preserves entries` |
| **A7** | The new family is JDK-bundled (no new external dependency) | `compileOnly("org.yaml:snakeyaml:2.3")` was added in U2; U3 only needs `java.util.Properties` which is on the JDK classpath |
| **A8** | The new family follows the existing typed-failure pattern (`@Throws(UtilitiesPropertiesException::class)` on every operation) | `interface UtilitiesPropertiesOperations` annotation; `Lfc2E2ExpansionGateFitnessTest.G4-6` (verifies annotation is present) |

## Test evidence

| Suite | Rows | Status |
|---|---|---|
| `UtilitiesPropertiesStepContractSuiteTest` | 12 | PASS |
| `Lfc2E2ExpansionGateFitnessTest` (now 18 rows including G4-6) | 18 | PASS |
| `Lfc2E2PrepFitnessTest` | 10 | PASS |
| `Lfc2E0GlobalClosureFitnessTest` | 12 | PASS |
| `Lfc2UniversalCoreFreezeFitnessTest` | 6 | PASS |
| `UtilitiesJsonStepContractSuiteTest` | 26 | PASS |
| `UtilitiesYamlStepContractSuiteTest` | 17 | PASS |
| `UppercaseStepContractSuiteTest` | 14 | PASS |
| **Total** | **115** | **PASS** |

## Counter rollup (post-U3)

| Counter | Pre-U3 | Post-U3 | Δ |
|---|---|---|---|
| `certified_core_steps` | 12 | 12 | 0 |
| `certified_external_plugin_steps` | 6 | 8 | +2 |
| `certified_total_steps` | 18 | 20 | +2 |
| `rejected_steps` | 1 | 1 | 0 |
| `stopped_g7_steps` | 2 | 2 | 0 |
| `implemented_uncertified_steps` | 0 | 0 | 0 |
| `total_production_step_keys` | 21 | 23 | +2 |
| `legacy_residual_ids` | 0 | 0 | 0 |
| `legacy_residual_metadata_rows` | 0 | 0 | 0 |
| `legacy_residual_dispatcher_files` | 0 | 0 | 0 |
| `OFFICIAL_PLUGIN families certified` | 1 | 1 | 0 (same coordinate, more families) |

## Files added or modified

### Added (this slice)

- `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/properties/UtilitiesPropertiesPlugin.kt` (310 lines)
  - sealed `UtilitiesPropertiesError` (PropertiesNotFound / PropertiesIoFailure)
  - `UtilitiesPropertiesException` typed carrier
  - `UtilitiesPropertiesOperations` capability port with `@Throws`
  - `DefaultUtilitiesPropertiesOperations` FS-backed implementation
  - `ReadPropertiesInput` / `ReadPropertiesOutput` / `ReadPropertiesCodec` / `ReadPropertiesOutputCodec`
  - `WritePropertiesInput` / `WritePropertiesOutput` / `WritePropertiesCodec` / `WritePropertiesOutputCodec`
  - `ReadPropertiesStepDefinition` / `WritePropertiesStepDefinition`
  - DSL extensions: `StageScope.readProperties(path)` and `StageScope.writeProperties(path, value)`

- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UtilitiesPropertiesStepContractSuiteTest.kt` (345 lines, 12 rows)
  - identity (2), contract completeness (3), capability admission (2), handler semantics (2), typed failure (2), real-DSL scenario (1)

- `docs/v2/07-uat/E2_U3_PROPERTIES_FAMILIES_RECEIPT.md` (this file)

### Modified

- `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt` (UtilitiesJsonContributor.definitions() now returns 7 entries)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2E2ExpansionGateFitnessTest.kt` (added G4-6)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UtilitiesYamlStepContractSuiteTest.kt` (registry size assertion updated to 8)
- `docs/v2/status/step-certification.yaml` (added 2 properties entries; counters: certified_external_plugin_steps 6→8, certified_total_steps 18→20, total_production_step_keys 21→23, total_yaml_step_entries 18→20)
- `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` (CERTIFIED: 18 → 20; added 4 new family entries)
- `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` (CERTIFIED external plugin: 6 → 8; added new capability tokens)
- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` (added U2 YAML + U3 properties entries to the inventory rollup)

## Pre-existing limitations (NOT regressions from this slice)

1. `examples/utilities/01-json-roundtrip.pipeline.kts` continues to fail with a
   pre-existing DSL signature mismatch ("Too many arguments for 'fun steps()'")
   reproducible on the cycle base SHA. The same root cause blocks ANY new
   `.pipeline.kts` fixture from compiling under the script compiler.
   The ContractSuite's typed `PipelineSpec` + `DslCompiledPipelineCompiler.compile()`
   path is the authoritative proof of pipeline composition, and that path is GREEN
   for all three families (JSON, YAML, properties).
2. YAML parse error at `docs/v2/status/step-certification.yaml` line 442 (now
   ~800 due to growth) — pre-existing on cycle base, out-of-scope for U3.
3. The remaining LFC-2E2 slices (U4 findFiles/touch, U5 checksums, U6 zip/unzip,
   U7 tar/untar, U8 family closure) are not addressed in this slice.

## What U3 proves that U1/U2 didn't

U1 (JSON hardening) proved the typed-failure pattern scales for an existing family.
U2 (YAML) proved a new family can be added with a new capability token and a new
typed ADT, sharing the existing OFFICIAL_PLUGIN coordinate.
U3 (properties) proves the same shape with a JDK-bundled dependency — the plugin
model does NOT require any new external dependency for the third family.

This is the fourth cumulative proof (counting U0's expansion gate fitness) that
the LFC-2E2 architecture can grow its OFFICIAL_PLUGIN surface without modifying
production core. The capability token count went from 2 (U0) to 4 (U3); the
StepKey count went from 3 (U0) to 7 (U3); all without touching
`RegistryExecutionBoundary.kt`, `CanonicalDurableRunCoordinator.kt`, or
`CanonicalRuntimeCapabilityAccess.kt`.
