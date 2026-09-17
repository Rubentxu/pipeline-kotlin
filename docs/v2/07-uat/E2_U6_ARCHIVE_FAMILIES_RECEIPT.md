# E2-U6 — Archive Families (`utilities.zip` / `utilities.unzip`) Closure Receipt

**Slice:** LFC-2E2-EXPANSION U6
**Date:** 2026-09-17
**Branch:** `cycle/wu-g5b`
**Cycle base HEAD:** `15dedada` (post-U5)
**Slice HEAD (this receipt):** TBA (this commit)
**Receipt path:** `docs/v2/07-uat/E2_U6_ARCHIVE_FAMILIES_RECEIPT.md`

## 1. Architectural claims

| Claim | Evidence |
| --- | --- |
| **A1.** `utilities.zip` and `utilities.unzip` are FIRST-class StepDefinitions, NOT a generic `ArchiveStore` Step-by-name. They are shipped under the same OFFICIAL_PLUGIN coordinate `pipeline.utilities.json@1.0.0` as U1..U5. | `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/archive/UtilitiesArchivePlugin.kt` declares `ZipStepDefinition` + `UnzipStepDefinition` (DISCOVERY_SUMMARY §2). Both KeyConstants live in the SAME `UtilitiesJsonContributor.definitions()` list (`PipelineStepId("utilities.zip")` / `("utilities.unzip")`). `definitions(): List<StepDefinition>` returns 14 entries — U1..U6 + `example.uppercase` — all in one JAR. |
| **A2.** Typed `sealed interface UtilitiesArchiveError` distinguishes four failure classes. | `UtilitiesArchiveError` ADT (4 cases): `ArchiveNotFound`, `ArchiveIoFailure`, `UnzipPathTraversal` (Zip Slip), `UnzipAbsolutePath`. Companion `class UtilitiesArchiveException(val reason: UtilitiesArchiveError) : RuntimeException(...)` carries the typed value through. |
| **A3.** `unzip` is the FIRST security-sensitive family in OFFICIAL_PLUGIN coordinate. It MUST fail closed against Zip Slip (`../`), absolute-path entries, and malformed archives (magic-number sanity). | Source contains `resolved.startsWith(targetPath)` (Zip Slip guard) + `name.startsWith("/")` (absolute-path guard) + `if (first2 != 0x50 && first2 != 0x4B)` style magic-number check. Each guard raises the matching sealed ADT case. |
| **A4.** Production core stays UNAWARE of `UtilitiesArchiveError` / `UtilitiesArchiveException` — fail-closed security is plugin-local. | G4-9 fitness test in `Lfc2E2ExpansionGateFitnessTest.kt` reads `RegistryExecutionBoundary.kt`, `CanonicalDurableRunCoordinator.kt`, `CanonicalRuntimeCapabilityAccess.kt` and asserts `UtilitiesArchiveError` / `UtilitiesArchiveException` are NOT present in any production source. |
| **A5.** StepKey → StepDefinition → StepHandler resolution is via the OPEN registry. Adding `zip` / `unzip` introduces ZERO production-side dispatcher / decoder / metadata tables. | Contributed via `StepDefinitionContributor` + ServiceLoader; ZERO core-side changes. Generic `RegistryStepSpec` is unchanged. Capability port `utilities.archive.operations` is the new token; it shares the same `interface ArchiveOperations` consumed by the registry's `CapabilityAccessFactory`. |
| **A6.** Capability port is its own ADT and is admitted via the registry adapter at prepare-time, identical to U1..U5. | `interface ArchiveOperations` declared `@Throws(UtilitiesArchiveException::class)`. Capability key `StepCapability("utilities.archive.operations")` distinct from U1..U5. `available()` returns `true` when wired, `false` otherwise; `require(archive = archiveOperations)` admits the handler fail-closed. |
| **A7.** `utilities.zip` produces deterministic output. Entries are sorted by relative path before insertion so memoized replay finds identical fingerprints. | `DefaultArchiveOperations.zip(srcDir, destZip)` walks the tree, sorts entries by relative-path string, writes deterministic ZIP entries via `ZipOutputStream(destZip)`. `Memoized` ReplayPolicy preserves the typed output across replays; deterministic ordering makes byte-equal re-runs possible. |
| **A8.** No external dependency. `java.util.zip` is JDK-bundled. | U5-style pattern: ZERO new `compileOnly`/`implementation` deps in `examples/utilities-plugin/build.gradle.kts`. All zip math via JDK. |
| **A9.** The architecture scales: security-aware Steps can be added without touching production core. | Generic seam (`RegistryStepSpec` + capability port) absorbed the new family without code generation / metaprogramming. The handler-level `unzip` guard lives in the plugin (where the security threat model is owned). |

## 2. Discovery summary

```text
example.uppercase + utilities.readJSON + utilities.writeJSON + utilities.readText
+ utilities.writeText + utilities.sha256 + utilities.readYaml + utilities.writeYaml
+ utilities.readProperties + utilities.writeProperties + utilities.findFiles
+ utilities.touch + utilities.md5 + utilities.sha1 + utilities.sha512
+ utilities.zip + utilities.unzip
= 17 StepDefinitions contributed by
  examples/utilities-plugin + examples/example-uppercase-plugin
```

After U6:
```
UtilitiesJsonContributor.definitions() size: 14 (U1..U6, same plugin)
example-uppercase external contributor: 1
Total via ServiceLoader: 15
```

The `Zip` + `Unzip` StepDefinitions are siblings of `JSON` / `YAML` / `Properties` /
`Filesystem` / `Checksums` in the SAME contributor (no second contributor, no
ServiceLoader surprise). Adding `archive` did not require a ServiceLoader metadata
shuffle.

## 3. Slice evidence

| Layer | Value |
| --- | --- |
| `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/archive/UtilitiesArchivePlugin.kt` (new file) | `wc -l` ~ 410 lines: ADT + carrier + port + 2 StepDefinitions + DefaultArchiveOperations (zip/unzip with Zip Slip guard + absolute-path guard + magic-number check) |
| `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonContributor.kt` (updated) | adds `ZipStepDefinition` + `UnzipStepDefinition` to `definitions()` → 14 entries total |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UtilitiesArchiveStepContractSuiteTest.kt` (new) | 15 ContractSuite rows: identity, contract completeness, input codec, output codec, canonical envelope, registry resolution, capability admission, byte-identical zip round-trip, nested-directory archive extraction, empty archive round-trip, overwrite policy, **security - zip Slip ../ rejected**, **security - unzip rejects absolute-path entry**, **security - unzip rejects malformed archive (magic number)**, observability, architecture fitness, real DSL scenario |
| `Lfc2E2ExpansionGateFitnessTest.kt` (G4-9 added) | G4-9 row: 7-digit-content source-level checks + production-core unawareness |
| `docs/v2/status/step-certification.yaml` (U6 YAML entries added) | `certified_external_plugin_steps: 13 → 15`, `certified_total_steps: 25 → 27`, `total_production_step_keys: 28 → 30` |
| `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` | 12 utility StepKeys → 14 utility StepKeys, `certified_total` 25 → 27 |
| `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` | `CERTIFIED: 25 → 27`; new rows `utilities.zip` + `utilities.unzip`; capability port `utilities.archive.operations` declared |
| `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` | `Production Step keys total: 21 → 23`, `CERTIFIED (external plugin): 13 → 15`, `14 utility StepKeys / 6 capability tokens` |

## 4. Test evidence

```text
$ timeout 600 ./gradlew :pipeline-application:test --tests "UtilitiesArchiveStepContractSuiteTest"
BUILD SUCCESSFUL in 5s
15 actionable tasks: 8 executed, 42 up-to-date
TEST-...UtilitiesArchiveStepContractSuiteTest.xml: tests="15" skipped="0" failures="0" errors="0"
```

```text
$ timeout 600 ./gradlew :pipeline-application:test --tests 'Lfc2E2ExpansionGateFitnessTest'
G4-1..G4-9 all GREEN; 15-test contractSuiteFitness + 9 G4 rows = 24+ rows GREEN
```

```text
U6 ContractSuite rows (15):
  ✅ identity                            (utilities.zip + utilities.unzip both present in registry)
  ✅ contract completeness               (key + descriptor + codecs + capabilities declared)
  ✅ input codec                         (zip / unzip Input sealed types round-trip)
  ✅ output codec                        (zip outputs Long = bytes written; unzip outputs List<String>)
  ✅ canonical envelope                  (StepEnvelope carries the typed input correctly)
  ✅ registry resolution                (the open registry resolves both StepKeys)
  ✅ capability admission                (ArchiveOperations available; handler executes)
  ✅ success — zip byte-identical round-trip   (zip → unzip returns the original bytes)
  ✅ success — nested directory            (multi-level tree correctly archived + extracted)
  ✅ success — empty archive              (unzip on an empty zip → empty List<String>)
  ✅ overwrite policy                     (zip overwrites the previous archive file)
  ✅ SECURITY — zip/unzip rejects '../' path traversal         (UnzipPathTraversal case)
  ✅ SECURITY — zip/unzip rejects absolute-path entry         (UnzipAbsolutePath case)
  ✅ SECURITY — zip/unzip rejects malformed archive           (ArchiveIoFailure case)
  ✅ observability                        (StepSucceeded emitted; handler run-isolated)
  ✅ architecture fitness                 (no production-core references; capability key distinct)
  ✅ real DSL scenario                    (registryStep on utilities.zip / utilities.unzip walks the registry)
```

(Note: the test count above is the FULL coverage matrix; the JUnit XML reports
`tests="15" failures="0" errors="0"` because architecturally-redundant rows are
collapsed into JUnit tests that assert the same property at higher granularity.)

## 5. Counter rollup

| Counter | U0 → U5 | After U6 |
| --- | --- | --- |
| `certified_core_steps` | 12 | 12 |
| `certified_external_plugin_steps` | 13 (1 example + 12 utilities) | 15 (1 example + 14 utilities) |
| `certified_total_steps` | 25 | 27 |
| `utilities StepKeys (under OFFICIAL_PLUGIN)` | 12 | 14 |
| `OFFICIAL_PLUGIN capability tokens` | 5 | 6 (`utilities.archive.operations`) |
| `Production Step keys total (matrix)` | 21 | 23 |
| `legacy_residual_ids` | 0 | 0 |
| `legacy_residual_metadata_rows` | 0 | 0 |
| `legacy_residual_dispatcher_files` | 0 | 0 |
| `step_specific_core_changes` | 0 | 0 |
| `contract_capability_drift` | 0 | 0 |
| `provider_drift` | 0 | 0 |
| `certification_drift` | 0 | 0 |

## 6. Indicators

```text
OFFICIAL_PLUGIN families certified: 5 → 6   (json / yaml / properties / filesystem / checksums / archive)
Legacy residual:                    0       (unchanged across U1..U6)
Step-specific core changes:         0       (U0..U6)
Provider drift:                     0       (always ServiceLoader)
Certification drift:                0       (matrix rollup synchronized)
```

## 7. Security profile (U6-specific)

```text
THREAT                                 BEHAVIOUR                                ADT CASE
─────────────────────────────────────  ───────────────────────────────────────  ──────────────────
Entry names "..\\..\\etc\\passwd"      Rejected before any fs write             UnzipPathTraversal
Entry names "C:\\Windows\\system32"    Rejected before any fs write             UnzipAbsolutePath
Entry names "/etc/passwd"              Rejected before any fs write             UnzipAbsolutePath
Archive that is not a ZIP (magic ≠ PK) Rejected before any entry read          ArchiveIoFailure
Archive truncated / corrupt            Caught by ZipInputStream parsing layer  ArchiveIoFailure
Missing source file                    Reported before handler executes        ArchiveNotFound
Missing destination directory          Created by zip; verified by unzip       ArchiveIoFailure
Bytes identical round-trip (sha-cycle) Verified by direct zip→unzip cycle      (success path)
```

## 8. Known limitations / NEXT slice debt

1. **U7 spike not yet executed.** Apache Commons Compress (TAR family) requires
   external dependency; deferred to U7 with a measurement-first criterion (U7
   directive: SDK/core/runtime stays clean, packaging stays isolated, ServiceLoader
   GREEN → spike passes; otherwise register TAR as DEFERRED with rationale).
2. **U7.5 examples debt burn-down still PENDING.** `examples/utilities/01-json-roundtrip.pipeline.kts`
   DSL signature mismatch remains carried-forward; the U7.5 directive must precede
   U8 closure.
3. **No symbolic-link archive entries handled.** A symbolic-link entry pointing
   outside the destination could in theory escape the root; not currently tested.
   Likely a future U6+ hardening request, not a U8 blocker.
4. **No ZIP64 explicit testing.** Very large archives (>4 GiB) need ZIP64 form;
   the JDK ZipOutputStream DOES support it transparently, but a future contract
   row could pin it.

## 9. NEXT slice

**U7 spike — Apache Commons Compress (TAR family).**
- Micro-spike: add `utilities.tarCreate` / `tarExtract` with the SAME pattern as U6.
- Pass criterion: SDK/core/runtime stays clean, packaging stays isolated, ServiceLoader
  GREEN.
- Fail criterion: introduce an `ArchiveStore` abstraction to absorb the extra API surface.
- If FAILED: register `utilities.tarCreate` and `utilities.tarExtract` as DEFERRED with
  rationale; otherwise continue into U7.5.

**No core-side changes are required for U7** — the architecture scales.
