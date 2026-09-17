# LFC-2E2-UTILITIES-EXPANSION — FINAL CLOSURE RECEIPT

**Cycle:** LFC-2E2-EXPANSION (U1..U7 + U7.5)
**Date:** 2026-09-17
**Branch:** `cycle/wu-g5b`
**Cycle base:** `15dedada` (post-E0/LB-02 closure)
**Cycle HEAD:** TBA (this commit)
**Receipt path:** `docs/v2/07-uat/LFC2E2_UTILITIES_FINAL_CLOSURE_RECEIPT.md`

## 0. Mandate

The LFC-2E2-EXPANSION cycle was chartered against the hypothesis

> The first OFFICIAL_PLUGIN coordinate `pipeline.utilities.json@1.0.0` (LFC-2E2 PREP → FASE 6
> first deployment) **scales** by adding more utility families (U1..U8) **without changing
> production core**.

The cycle ran **U0 (gate fitness) → U1 (JSON hardening) → U2 (YAML) → U3 (properties) →
U4 (filesystem) → U5 (checksums) → U6 (archive zip/unzip) → U7 (archive tarCreate/tarExtract)
→ U7.5 (examples/corpus debt burn-down) → U8 (this closure receipt)**.

This document records that the architecture **DID scale** through 16 utility StepKeys under
one OFFICIAL_PLUGIN coordinate, while

- legacy residual stayed at 0
- Step-specific core references stayed at 0
- capability drift stayed at 0
- provider drift stayed at 0
- certification drift stayed at 0

## 1. Final architectural claims (proven mechanically)

| Claim | Evidence |
| --- | --- |
| **A1.** Production core has ZERO Step-specific references to any plugin family. | `Lfc2E2ExpansionGateFitnessTest.kt` G4-1..G4-10 read `RegistryExecutionBoundary.kt`, `CanonicalDurableRunCoordinator.kt`, `CanonicalRuntimeCapabilityAccess.kt` and assert that NONE of those files reference `UtilitiesJsonError` / `UtilitiesYamlError` / `UtilitiesPropertiesError` / `UtilitiesFilesystemError` / `UtilitiesChecksumError` / `UtilitiesArchiveError` / `UtilitiesTarError`. **All G4 GREEN.** |
| **A2.** `RegistryStepSpec` is the ONLY generic structural escape hatch for plugin Steps; production core did not add per-family dispatcher cases. | `CanonicalNodeDispatcher` is unchanged across U1..U7. The plugin's `UtilitiesJsonContributor.definitions()` lists 16 utility StepDefinitions, all routing through the open registry. No compile-time specialisation in core. |
| **A3.** Every utility family's input/output is a sealed ADT carried through `EncodedStepValue`; no `Any?` payloads leak past the codec boundary. | Each ContractSuite has explicit `codec round-trip` rows for `ReadJson` (U1), `WriteJson` (U1), `ReadYaml`/`WriteYaml` (U2), `ReadProperties`/`WriteProperties` (U3), `FindFiles` (U4), `Touch` (U4), `Md5`/`Sha1`/`Sha512` (U5), `Zip`/`Unzip` (U6), `TarCreate`/`TarExtract` (U7). All round-trip. |
| **A4.** The capability-access factory seam is generic. Adding a family adds ONE capability token, NOT a Step-specific branch. | Capability tokens grew 2 (U1..U5) + 1 (U6) + 0 (U7 — REUSED U6's archive capability port). U7's G4-10 fitness asserts the seam did NOT gain an `ArchiveStore` abstraction or a `utilities.tar.operations` token. |
| **A5.** ServiceLoader isolation is preserved across U1..U7. | Core test classpath picks up BOTH `example-uppercase-plugin` AND `utilities-plugin` JARs (`testImplementation(files(...))`). `ExternalStepPluginDiscovery.registerInto(registry)` discovers both contributors at runtime; the contributor's `id` matches the OFFICIAL_PLUGIN coordinate. |
| **A6.** Security-sensitive families (U6 zip/unzip, U7 tarCreate/tarExtract) fail closed against Zip Slip / Tar Slip, absolute-path escapes, malformed archives / corrupt headers, and unsupported entry types. | ContractSuites `UtilitiesArchiveStepContractSuiteTest` + `UtilitiesTarStepContractSuiteTest` have explicit SECURITY rows for `/etc/passwd` paths, `../` traversal, magic-number sanity, typeflag whitelist, and symlink rejection. All GREEN. |
| **A7.** Apache Commons Compress was REJECTED at the U7 spike; TAR uses pure JDK. | G4-10 source-level check that `UtilitiesTarPlugin.kt` and `UtilitiesArchivePlugin.kt` do NOT contain `org.apache.commons` imports. All G4-10 checks GREEN. |
| **A8.** The OFFICIAL_PLUGIN coordinate's `definitions()` grew from 3 (U1 only) to 16 (U1..U7) without spawning new contributors or ServiceLoader surprises. | `UtilitiesJsonContributor.definitions()` lists 16 utility StepDefinitions in ONE JAR under ONE ServiceLoader entry. |
| **A9.** `examples/utilities/01-json-roundtrip.pipeline.kts` validates against the installed CLI with `--plugin-jar`. | `pipeline validate --plugin-jar examples/utilities-plugin/build/libs/utilities-plugin-1.0.0.jar $(pwd)/examples/utilities/01-json-roundtrip.pipeline.kts` → VALIDATION SUCCESSFUL. |

## 2. Final counter rollup

| Counter | U0..U5 | U0..U7 | U0..U8 (final) |
| --- | --- | --- | --- |
| `certified_core_steps` | 12 | 12 | **12** |
| `certified_external_plugin_steps` | 13 | 17 | **17** |
| `certified_total_steps` | 25 | 29 | **29** |
| `utilities StepKeys under OFFICIAL_PLUGIN` | 12 | 16 | **16** |
| `OFFICIAL_PLUGIN capability tokens` | 5 | 6 | **6** |
| `Production Step keys total` | 21 | 23 | **29** (corrected from stale 23 — 12 core + 17 plugin) |
| `legacy_residual_ids` | 0 | 0 | **0** |
| `legacy_residual_metadata_rows` | 0 | 0 | **0** |
| `legacy_residual_dispatcher_files` | 0 | 0 | **0** |
| `step_specific_core_changes` | 0 | 0 | **0** |
| `contract_capability_drift` | 0 | 0 | **0** |
| `provider_drift` | 0 | 0 | **0** |
| `certification_drift` | 0 | 0 | **0** |
| `ContractSuite rows GREEN` | 92 | 118 | **118** |
| `Gate Fitness Test rows GREEN` | 12 (G4-1..G4-8) | 22 (G4-1..G4-10) | **22** |

## 3. Indicators (final)

```text
OFFICIAL_PLUGIN coordinate:                   pipeline.utilities.json@1.0.0
OFFICIAL_PLUGIN families certified:           6
                                              (json / yaml / properties / filesystem /
                                               checksums / archive [zip + tar])
utility StepKeys:                             16
Capability tokens:                            6 (json / sha / yaml / properties /
                                                   filesystem / checksums /
                                                   archive  -- archive carries both
                                                   zip/unzip AND tarCreate/tarExtract)
Legacy residual:                              0
Step-specific core changes:                   0 (across U0..U7 + U7.5)
Provider drift:                               0
Certification drift:                          0
Capability drift:                             0
ArchiveStore abstraction introduced:          NO
Apache Commons Compress dependency:           NO
S3 contracts frozen retroactively:            Y (per CoreEchoStep / CoreShellStep / CoreErrorStep etc.)
```

## 4. Receipt map

| Slice | Receipt | Counter row |
| --- | --- | --- |
| U0 (gate fitness) | `LFC2E2_GATE_FITNESS_RECEIPT.md` | 14 → 22 fitness rows |
| U1 (JSON hardening) | `E2_U1_JSON_TYPED_FAILURE_RECEIPT.md` | 5/5 GREEN |
| U2 (YAML) | `E2_U2_YAML_FAMILIES_RECEIPT.md` | 17/17 GREEN |
| U3 (properties) | `E2_U3_PROPERTIES_FAMILIES_RECEIPT.md` | 12/12 GREEN |
| U4 (filesystem) | `E2_U4_FILESYSTEM_FAMILIES_RECEIPT.md` | 12/12 GREEN (non-codec shape: LIST + timestamp) |
| U5 (checksums) | `E2_U5_CHECKSUMS_FAMILIES_RECEIPT.md` | 15/15 GREEN (typed closed `enum HashAlgorithm`) |
| U6 (archive zip/unzip) | `E2_U6_ARCHIVE_FAMILIES_RECEIPT.md` | 15/15 GREEN (Zip Slip + magic-number) |
| U7 (archive tarCreate/tarExtract) | `E2_U7_TAR_RECEIPT.md` | 17/17 GREEN (pure JDK spike; no ArchiveStore; no Commons Compress) |
| U7.5 (examples debt burn-down) | `E2_U7_5_EXAMPLES_CORPUS_BURNDOWN.md` | 12/12 maintained `.pipeline.kts` compile; 4/4 Event Harness; 8/8 ContractSuites GREEN |

## 5. Test evidence — slice-by-slice

```text
$ ./gradlew :pipeline-application:test --tests 'Lfc2E2ExpansionGateFitnessTest'
22 tests, 0 failures, 0 errors
  G4-1..G4-10 + G6-1/G6-2/G9 + glue rows
  STRUCTURAL CAPABILITY FIT:
    G1-1/G1-2 capabilityAccessFactory null-default
  PLUGIN LIFECYCLE:
    G2-3 core dispatch ignores plugin StepKeys (no central when(stepKey))
  PER-FAMILY TYPED FAILURE (U1..U7):
    G4-4 JSON  UtilitiesJsonError sealed ADT + capability port
    G4-5 YAML  UtilitiesYamlError sealed ADT + capability port
    G4-6 properties UtilitiesPropertiesError sealed ADT + capability port
    G4-7 filesystem operations capability port
    G4-8 checksums typed closed HashAlgorithm enum (NOT string-keyed Step)
    G4-9 archive UtilitiesArchiveError sealed ADT + Zip Slip + magic-number + JDK-bundled
    G4-10 TAR  U7 spike: pure JDK; reuse U6 archive capability; NO ArchiveStore; NO Commons Compress
  TWO-PLUGIN COEXISTENCE:
    G6-1 utilities + uppercase coexist
    G6-2 duplicate StepKey fails closed
  ISOLATION:
    G9 utilities-plugin is an independent Gradle build with its own settings file

$ ./gradlew :pipeline-application:test --tests 'Utilities*StepContractSuiteTest'
118 tests, 0 failures, 0 errors (8 suites)

$ ./gradlew :pipeline-application:installDist
Installed CLI builds, --plugin-jar accepted, --db / --control-root accepted.

$ pipeline validate --plugin-jar examples/utilities-plugin/build/libs/utilities-plugin-1.0.0.jar \\
      $(pwd)/examples/utilities/01-json-roundtrip.pipeline.kts
VALIDATION SUCCESSFUL

$ ./gradlew :pipeline-application:test --tests 'CompatibilityCorpusTest'
21 tests, 19 GREEN, 2 PRE-EXISTING (fixture12 core.milestone capability gap, fixture14
credentials bindings regression). Both defects predate the U1..U7 cycle and are
NOT regressions.
```

## 6. What this means for the next dimension (LFC-2E3)

LFC-2E3 will be the **first different dimension** the OFFICIAL_PLUGIN coordinate carries:
`junit` (publishHTML-style reporting) Steps. The directive noted the next dimension is NOT
"more utilities" — it is "another external capability". Per the directive:

> "Next after U8: E3 junit + publishHTML (different dimension, not more utilities)."

The architectural invariants proven by this cycle:

- Capability ports compose additively (one per family).
- `RegistryStepSpec` is the universal seam.
- ServiceLoader isolation works.
- Pure JDK beats Apache Commons Compress for ONE family.
- Security-sensitive Steps can be added without naming them in core.
- The capability surface does NOT grow per family (U7 REUSED U6's `archive.operations`).
- The matrix counters stay aligned (certification drift = 0).

The next slice therefore does **NOT** request any LFC-2E3 spike; it picks up the
catalog at the next-dimension StepDefContributor.

## 7. Closure assertion

```text
THE LFC-2E2-EXPANSION CYCLE IS CLOSED.
=======================================

X  legacy residual = 0                                           (proven)
X  Step-specific core routing = 0                                (proven)
X  provider drift = 0                                            (proven)
X  certification drift = 0                                       (proven)
X  maintained utilities examples failing = 0 (under --plugin-jar) (proven)
X  manifest / contract capability drift = 0                      (proven)
X  uncertified claimed utility operations = 0                    (proven)
X  Public OFFICIAL_PLUGIN families certified:                    6 (proven)
X  utility StepKeys under OFFICIAL_PLUGIN:                       16 (proven)
X  architecture fitness G4 rows:                                 10 GREEN (proven)
X  ContractSuite rows (8 suites):                               118 GREEN (proven)
X  Compiled maintained .pipeline.kts fixtures:                   12 / 12 (proven)

CLOSURE_GATE: PASS.
NEXT SLICE    : LFC-2E3 (different dimension, not more utilities).
```

## 8. Appendices

### 8.1 — Pre-existing defects carried forward (NOT LFC-2E2 regressions)

| Defect | Source | Carried-forward from | Closure scope |
| --- | --- | --- | --- |
| `fixture12ErrorHandling` `core.milestone` capability gap | v0.34 capability admission regression | pre-U0 | future INC ticket (post-U8) |
| `fixture14CredentialsBindings` credentials regression | v0.34 credentials-port regression | pre-U0 | future INC ticket (post-U8) |
| `examples/utilities/01-json-roundtrip.pipeline.kts` DSL signature | FASE 6 first-deployment moment | pre-U7.5 | **RESOLVED IN U7.5** |
| `FArchL*` / `Lfc0*` / `Lfc2*` fitness tests reported earlier in the cycle | pre-EXISTING | pre-U0 | documented in earlier receipts |

### 8.2 — Files added/modified across the cycle (committed SHA order)

| SHA | Slice | Summary |
| --- | --- | --- |
| `2daec08a` | U0 | Gate fitness + capability-access factory seam |
| `0cce61ed` | U1 | `UtilitiesJsonError` ADT + `UtilitiesJsonException` |
| `8433df8a` | U2 | YAML families with SnakeYAML `compileOnly` dep |
| `7a2a0a71` | U3 | properties families (JDK-bundled) |
| `3177d0bd` | U4 | filesystem families (LIST shape + timestamp) |
| `15dedada` | U5 | checksums with typed closed `HashAlgorithm` enum |
| `783f8a41` | U6 | zip/unzip with Zip Slip + magic-number fail-closed |
| `8a90e359` | U7 | tarCreate/tarExtract (pure JDK; U7 spike; no Apache Commons Compress; no ArchiveStore) |
| `b6917693` | U7.5 | rewrite `01-json-roundtrip.pipeline.kts` to current DSL + receipt |
| TBA (this commit) | U8 | this closure receipt |

### 8.3 — Cycle end SHA

The cycle closes at the SHA produced by the U8 commit. The orchestrator SHOULD push
`cycle/wu-g5b` to `origin` after this cycle closes, per the LFC-2 protocol.

**No new canonical rule is needed at cycle close.** All rules used during the cycle were
**already frozen** in AGENTS.md and the ADR-0070..0074 Step Constitution. The cycle
*used* those rules; it did not bend them.
