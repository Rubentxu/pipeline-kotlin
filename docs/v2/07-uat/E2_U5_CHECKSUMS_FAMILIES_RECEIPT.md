# LFC-2E2-EXPANSION U5 — checksums families (md5, sha1, sha512)

## Slice scope

U5 closes the fifth plugin-model expansion slice. It adds three new Step
families (`utilities.md5`, `utilities.sha1`, `utilities.sha512`) to the
existing OFFICIAL_PLUGIN coordinate `pipeline.utilities.json@1.0.0`,
complementing the existing `utilities.sha256`.

**U5 introduces the typed-closed-algorithm ADT pattern:**

The three new families are NOT one generic `utilities.checksum(algo="md5")`
Step that takes the algorithm as a runtime string. Each algorithm is a
distinct `StepKey` with its own `StepContract`, `StepCodec`, and DSL
extension. The shared digest logic lives in a single typed closed
`HashAlgorithm` enum inside the plugin — the enum is exhaustive, so the
handler cannot be passed an unknown algorithm at runtime.

Rationale:
- Keeps StepKey stability (callers target `utilities.md5`, not
  `utilities.checksum` with a side-channel algorithm parameter).
- Algorithm names are typed, not stringly typed. The compiler enforces
  exhaustiveness when `when`-ing on `HashAlgorithm`.
- Production core stays unaware of MD5/SHA-1/SHA-512 entirely.

## Architectural claims

| # | Claim | Evidence |
|---|---|---|
| **A1** | A new typed failure ADT (`UtilitiesChecksumError` + `UtilitiesChecksumException`) is declared in the plugin package; production core is unaware | `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/checksums/UtilitiesChecksumsPlugin.kt`; `Lfc2E2ExpansionGateFitnessTest.G4-8` (production sources contain zero references to `UtilitiesChecksumError`/`Exception`/`HashAlgorithm`) |
| **A2** | A new typed capability token (`utilities.checksums.operations`) is declared in the plugin and routed through the generic `capabilityAccessFactory` seam | `UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY` (distinct from sha256's `utilities.sha.operations`); `Lfc2E2ExpansionGateFitnessTest.G4-8` |
| **A3** | Three new Step families are declared in the same OFFICIAL_PLUGIN coordinate (no new contributor) | `UtilitiesJsonContributor.definitions()` (the same contributor grew from 9 → 12 entries) |
| **A4** | Each algorithm is its own StepKey — NOT a generic Step-by-name with a string algorithm parameter | `PluginStepId("utilities.md5")`, `PluginStepId("utilities.sha1")`, `PluginStepId("utilities.sha512")`; `Lfc2E2ExpansionGateFitnessTest.G4-8` |
| **A5** | The algorithm name is a typed closed `HashAlgorithm` enum, not a runtime string | `enum class HashAlgorithm(...)` with 3 variants; `Lfc2E2ExpansionGateFitnessTest.G4-8` |
| **A6** | The digest backend is JDK-only (`MessageDigest.getInstance`); no new external dependency | `Lfc2E2ExpansionGateFitnessTest.G4-8` |
| **A7** | The new capability port is fail-closed: missing capability rejects the Step before the handler runs | `capability admission - missing checksums capability REJECTS md5 before handler runs` |
| **A8** | Digests are deterministic and reproducible against JDK's MessageDigest directly | `handler - md5 matches the JDK MessageDigest value`, `sha1`, `sha512` |

## Test evidence

| Suite | Rows | Status |
|---|---|---|
| `UtilitiesChecksumsStepContractSuiteTest` | 15 | PASS |
| `Lfc2E2ExpansionGateFitnessTest` (now 20 rows including G4-8) | 20 | PASS |
| `Lfc2E2PrepFitnessTest` | 10 | PASS |
| `Lfc2E0GlobalClosureFitnessTest` | 12 | PASS |
| `Lfc2UniversalCoreFreezeFitnessTest` | 6 | PASS |
| `UtilitiesJsonStepContractSuiteTest` | 26 | PASS |
| `UtilitiesYamlStepContractSuiteTest` | 17 | PASS |
| `UtilitiesPropertiesStepContractSuiteTest` | 12 | PASS |
| `UtilitiesFilesystemStepContractSuiteTest` | 12 | PASS |
| `UppercaseStepContractSuiteTest` | 14 | PASS |
| **Total** | **144** | **PASS** |

## Counter rollup (post-U5)

| Counter | Pre-U5 | Post-U5 | Δ |
|---|---|---|---|
| `certified_core_steps` | 12 | 12 | 0 |
| `certified_external_plugin_steps` | 10 | 13 | +3 |
| `certified_total_steps` | 22 | 25 | +3 |
| `rejected_steps` | 1 | 1 | 0 |
| `stopped_g7_steps` | 2 | 2 | 0 |
| `implemented_uncertified_steps` | 0 | 0 | 0 |
| `total_production_step_keys` | 25 | 28 | +3 |
| `legacy_residual_ids` | 0 | 0 | 0 |
| `legacy_residual_metadata_rows` | 0 | 0 | 0 |
| `legacy_residual_dispatcher_files` | 0 | 0 | 0 |
| `OFFICIAL_PLUGIN families certified` | 1 | 1 | 0 (same coordinate) |

## Files added or modified

### Added (this slice)

- `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/checksums/UtilitiesChecksumsPlugin.kt` (332 lines)
  - `enum class HashAlgorithm` (MD5 / SHA1 / SHA512, typed closed)
  - sealed `UtilitiesChecksumError` (ChecksumNotFound / ChecksumIoFailure)
  - `UtilitiesChecksumException` typed carrier
  - `ChecksumOperations` capability port with `@Throws`
  - `DefaultChecksumOperations` JDK-MessageDigest-backed implementation
  - `Md5Input` / `Sha1Input` / `Sha512Input` + shared `ChecksumOutput` codec
  - `Md5StepDefinition` / `Sha1StepDefinition` / `Sha512StepDefinition`
  - DSL extensions: `StageScope.md5(path)`, `StageScope.sha1(path)`, `StageScope.sha512(path)`
  - Each algorithm is its own StepKey — NOT a generic Step-by-name

- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UtilitiesChecksumsStepContractSuiteTest.kt` (397 lines, 15 rows)

- `docs/v2/07-uat/E2_U5_CHECKSUMS_FAMILIES_RECEIPT.md` (this file)

### Modified

- `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt` (UtilitiesJsonContributor.definitions() now returns 12 entries)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2E2ExpansionGateFitnessTest.kt` (added G4-8)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UtilitiesPropertiesStepContractSuiteTest.kt` (registry size assertion updated to 13)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UtilitiesYamlStepContractSuiteTest.kt` (registry size assertion updated to 13)
- `docs/v2/status/step-certification.yaml` (added 3 checksums entries; counters: certified_external_plugin_steps 10→13, certified_total_steps 22→25, total_production_step_keys 25→28, total_yaml_step_entries 22→25)
- `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` (CERTIFIED: 22 → 25; added 3 new family entries)
- `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` (CERTIFIED external plugin: 10 → 13; added checksums capability token)
- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` (added U5 entries to the inventory rollup)

## Pre-existing limitations (NOT regressions from this slice)

1. `examples/utilities/01-json-roundtrip.pipeline.kts` continues to fail with a
   pre-existing DSL signature mismatch ("Too many arguments for 'fun steps()'")
   reproducible on the cycle base SHA. ContractSuite's typed `PipelineSpec` +
   `DslCompiledPipelineCompiler.compile()` path is the authoritative proof.
2. YAML parse error at `docs/v2/status/step-certification.yaml` line 442 (now
   ~1200) — pre-existing on cycle base, out-of-scope for U5.
3. The remaining LFC-2E2 slices (U6 zip/unzip, U7 tar/untar or DEFER,
   U7.5 examples debt burn-down, U8 family closure) are not addressed in
   this slice.

## What U5 proves that U1..U4 didn't

The checksums family is the first that has a **closed-typed-algorithm**
pattern: the algorithm name is a value of an enum, not a runtime string.
The compiler can prove that the handler is exhaustive over the enum cases,
which means there is no opportunity to silently drop a new algorithm on the
floor (the Kotlin compiler would flag the missing `when` branch as an error).

Combined with U4 (LIST/timestamp shapes), the architecture now hosts:
- codec-shaped scalar outputs (U1 JSON, U3 properties, U5 checksums)
- codec-projection outputs (U2 YAML)
- LIST-shaped output + timestamp-typed input (U4)
- typed-closed-algorithm dispatch (U5)

across 12 Step families in a single OFFICIAL_PLUGIN coordinate, with **zero
production core changes** since U0.
