# LFC-2E2-EXPANSION U4 — filesystem families (first non-codec-shaped)

## Slice scope

U4 closes the fourth plugin-model expansion slice. It adds two new Step
families (`utilities.findFiles`, `utilities.touch`) to the existing
OFFICIAL_PLUGIN coordinate `pipeline.utilities.json@1.0.0`.

**U4 is the FIRST slice that adds non-codec-shaped Steps to the plugin family:**

- `utilities.findFiles` returns a **LIST-shaped** Output (`List<String>` of
  matching paths), not a single typed scalar.
- `utilities.touch` accepts a **timestamp-typed** argument (`Long?`) and
  applies a side-effect (Files.setLastModifiedTime).

Together they prove the architecture is **not biased toward file-decode
codecs**: a different shape (LIST / timestamp) lives under the same generic
`capabilityAccessFactory` seam.

## Architectural claims

| # | Claim | Evidence |
|---|---|---|
| **A1** | A new typed failure ADT (`UtilitiesFilesystemError` + `UtilitiesFilesystemException`) is declared in the plugin package; production core is unaware | `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/filesystem/UtilitiesFilesystemPlugin.kt`; `Lfc2E2ExpansionGateFitnessTest.G4-7` (production sources contain zero references to `UtilitiesFilesystemError`/`Exception`) |
| **A2** | A new typed capability token (`utilities.filesystem.operations`) is declared in the plugin and routed through the generic `capabilityAccessFactory` seam | `UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY`; `Lfc2E2ExpansionGateFitnessTest.G4-7` (production core does NOT know about the token) |
| **A3** | Two new Step families are declared in the same OFFICIAL_PLUGIN coordinate (no new contributor) | `UtilitiesJsonContributor.definitions()` (the same contributor grew from 7 → 9 entries) |
| **A4** | The new Steps participate in the canonical durable spine (registry → codec → contract → capability admission → handler → encoded result → journal → events) | `UtilitiesFilesystemStepContractSuiteTest` (12 rows) |
| **A5** | The new capability port is fail-closed: missing capability rejects the Step before the handler runs | `capability admission - missing filesystem capability REJECTS findFiles before handler runs` |
| **A6** | The new LIST-shaped Output is portable (encoded as a JSON array of strings), so no nio.Path leak into the public contract | `FindFilesOutputCodec` (wraps `List<String>` into a JsonObject before encoding) |
| **A7** | The new timestamp-typed Input is also portable (`Long?`), so no FileTime leak into the public contract | `TouchInput.lastModifiedMillis: Long?` |
| **A8** | The architecture handles **non-codec shapes** without changing the production core | `findFiles` returns `List<String>`, `touch` mutates a timestamp — neither is a "read file and decode" codec; production coordinator still does the same dispatch |

## Test evidence

| Suite | Rows | Status |
|---|---|---|
| `UtilitiesFilesystemStepContractSuiteTest` | 12 | PASS |
| `Lfc2E2ExpansionGateFitnessTest` (now 19 rows including G4-7) | 19 | PASS |
| `Lfc2E2PrepFitnessTest` | 10 | PASS |
| `Lfc2E0GlobalClosureFitnessTest` | 12 | PASS |
| `Lfc2UniversalCoreFreezeFitnessTest` | 6 | PASS |
| `UtilitiesJsonStepContractSuiteTest` | 26 | PASS |
| `UtilitiesYamlStepContractSuiteTest` | 17 | PASS |
| `UtilitiesPropertiesStepContractSuiteTest` | 12 | PASS |
| `UppercaseStepContractSuiteTest` | 14 | PASS |
| **Total** | **128** | **PASS** |

## Counter rollup (post-U4)

| Counter | Pre-U4 | Post-U4 | Δ |
|---|---|---|---|
| `certified_core_steps` | 12 | 12 | 0 |
| `certified_external_plugin_steps` | 8 | 10 | +2 |
| `certified_total_steps` | 20 | 22 | +2 |
| `rejected_steps` | 1 | 1 | 0 |
| `stopped_g7_steps` | 2 | 2 | 0 |
| `implemented_uncertified_steps` | 0 | 0 | 0 |
| `total_production_step_keys` | 23 | 25 | +2 |
| `legacy_residual_ids` | 0 | 0 | 0 |
| `legacy_residual_metadata_rows` | 0 | 0 | 0 |
| `legacy_residual_dispatcher_files` | 0 | 0 | 0 |
| `OFFICIAL_PLUGIN families certified` | 1 | 1 | 0 (same coordinate) |

## Files added or modified

### Added (this slice)

- `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/filesystem/UtilitiesFilesystemPlugin.kt` (370 lines)
  - sealed `UtilitiesFilesystemError` (FilesystemNotFound / FilesystemInvalidGlob / FilesystemIoFailure)
  - `UtilitiesFilesystemException` typed carrier
  - `UtilitiesFilesystemOperations` capability port with `@Throws`
  - `DefaultUtilitiesFilesystemOperations` FS-backed implementation
  - `FindFilesInput` / `FindFilesOutput` (LIST-shaped) / `FindFilesCodec` / `FindFilesOutputCodec`
  - `TouchInput` (timestamp-shaped) / `TouchOutput` / `TouchCodec` / `TouchOutputCodec`
  - `FindFilesStepDefinition` / `TouchStepDefinition`
  - DSL extensions: `StageScope.findFiles(root, glob, maxDepth)` and `StageScope.touch(path, ts, createDirs)`
  - Notable implementation choice: synthesises a `leafMatcher` from the user-friendly
    `**/*.kt` glob (Java NIO PathMatcher doesn't match root-level files with `**`
    alone). Plugin layer absorbs the JDK semantic gap.

- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UtilitiesFilesystemStepContractSuiteTest.kt` (343 lines, 12 rows)

- `docs/v2/07-uat/E2_U4_FILESYSTEM_FAMILIES_RECEIPT.md` (this file)

### Modified

- `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt` (UtilitiesJsonContributor.definitions() now returns 9 entries)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2E2ExpansionGateFitnessTest.kt` (added G4-7)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UtilitiesPropertiesStepContractSuiteTest.kt` (registry size assertion updated to 10)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UtilitiesYamlStepContractSuiteTest.kt` (registry size assertion updated to 10)
- `docs/v2/status/step-certification.yaml` (added 2 filesystem entries; counters: certified_external_plugin_steps 8→10, certified_total_steps 20→22, total_production_step_keys 23→25, total_yaml_step_entries 20→22)
- `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` (CERTIFIED: 20 → 22; added 2 new family entries)
- `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` (CERTIFIED external plugin: 8 → 10; added filesystem capability token)
- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` (added U4 entries to the inventory rollup)

## Pre-existing limitations (NOT regressions from this slice)

1. `examples/utilities/01-json-roundtrip.pipeline.kts` continues to fail with a
   pre-existing DSL signature mismatch ("Too many arguments for 'fun steps()'")
   reproducible on the cycle base SHA. ContractSuite's typed `PipelineSpec` +
   `DslCompiledPipelineCompiler.compile()` path is the authoritative proof.
2. YAML parse error at `docs/v2/status/step-certification.yaml` line 442 (now
   ~1000) — pre-existing on cycle base, out-of-scope for U4.
3. The remaining LFC-2E2 slices (U5 checksums, U6 zip/unzip, U7 tar/untar,
   U8 family closure) are not addressed in this slice.

## What U4 proves that U1/U2/U3 didn't

| Slice | Shape | Output type | Capability tokens |
|---|---|---|---|
| U1 (JSON hardening) | Codec | Scalar (typed JsonElement / String / Long) | 2 |
| U2 (YAML) | Codec (projection) | Scalar (typed JsonElement via projection) | 3 |
| U3 (properties) | Codec (projection) | Scalar (typed JsonObject) | 4 |
| **U4 (filesystem)** | **Non-codec** | **LIST (`List<String>`) + timestamp (`Long?`)** | **5** |

U4 is the first slice where the Output type is NOT a single typed scalar:
`findFiles` returns a list, `touch` takes a timestamp. This is what proves the
architecture handles shape diversity — the production coordinator did not need
to know that findFiles walks a directory tree or that touch updates mtime.

## Cumulative evidence (U1..U4)

The capability token count went from 2 (U0) to 5 (U4); the StepKey count went
from 3 (U0) to 9 (U4) within the OFFICIAL_PLUGIN coordinate. Production core
files (`RegistryExecutionBoundary.kt`, `CanonicalDurableRunCoordinator.kt`,
`CanonicalRuntimeCapabilityAccess.kt`) have zero references to ANY of the
plugin-specific types:

- `UtilitiesJsonError` / `UtilitiesJsonException`
- `UtilitiesYamlError` / `UtilitiesYamlException`
- `UtilitiesPropertiesError` / `UtilitiesPropertiesException`
- `UtilitiesFilesystemError` / `UtilitiesFilesystemException`
- `utilities.json.operations`
- `utilities.yaml.operations`
- `utilities.properties.operations`
- `utilities.filesystem.operations`

The generic `capabilityAccessFactory` seam is the SINGLE place where plugin
specifics cross into runtime, and even that seam is **typed by StepCapability**
keys — the runtime never names a specific StepKey.

This is the cumulative architectural claim that the LFC-2E2 cycle is built on.
