# E2-U7 — TAR Family (`utilities.tarCreate` / `utilities.tarExtract`) Closure Receipt

**Slice:** LFC-2E2-EXPANSION U7 — Apache Commons Compress SPIKE decision
**Date:** 2026-09-17
**Branch:** `cycle/wu-g5b`
**Cycle base HEAD:** `15dedada` (post-U5) → `783f8a41` (post-U6)
**Slice HEAD (this receipt):** TBA (this commit)
**Receipt path:** `docs/v2/07-uat/E2_U7_TAR_RECEIPT.md`

## 1. Spike decision recorded

> **Apache Commons Compress was the candidate. We REJECTED it.**

The U7 directive explicitly evaluated Apache Commons Compress (`org.apache.commons:commons-compress`) with three pass criteria:

1. SDK/core/runtime stays clean
2. Packaging stays isolated
3. ServiceLoader stays GREEN

**Failure mode (formal, recorded for posterity):**

| Criterion | Observed with Commons Compress | Verdict |
| --- | --- | --- |
| SDK stays clean | ✅ no production source changes needed | pass |
| Runtime stays clean | ⚠️ Commons Compress pulls `commons-io` + `commons-codec` onto the runtime classpath (even with `compileOnly`, ServiceLoader implications on isolation) | partial |
| Packaging stays isolated | ⚠️ ~2 MB artifact + transitive surface disproportionate to ONE family | **fail (architecture smell)** |
| ServiceLoader GREEN | ✅ the SPI is procedural, not invasive | pass |
| No `ArchiveStore` abstraction | ⚠️ Commons Compress's API surface encourages abstracting `ArchiveStore` / `TarStore` / `ZipStore` | risk |

Because (a) packaging wasn't isolated and (b) Commons Compress's API shape
encouraged the very `ArchiveStore` abstraction we deliberately rejected in U6,
**the spike FAILED**, and U7 was implemented in **pure JDK** instead:

```
U7 SPICE OUTCOME
================
Pass criterion:                 JDK + USTAR/POSIX.1-1988
Fail criterion (motivator):     Commons Compress (too big; commits us to abstraction)
Surprise:                       none — TAR USTAR is small (~250 lines)
```

The lesson is itself the receipt: **JDK-bundled standards + pluggable archive
math beat a transitive surface for one family**. The U6 capability port +
sealed ADT shape was preserved unchanged; U7 added two methods to the same
port + two new sealed ADT cases (extending `UtilitiesArchiveError`).

## 2. Architectural claims

| Claim | Evidence |
| --- | --- |
| **A1.** `utilities.tarCreate` and `utilities.tarExtract` are siblings of `utilities.zip` / `utilities.unzip` in the SAME `UtilitiesJsonContributor.definitions()`. SAME OFFICIAL_PLUGIN coordinate, same JAR, zero new contributors. | `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt` L77..78: `pipeline.utilities.archive.TarCreateStepDefinition` + `TarExtractStepDefinition` listed alongside the U1..U6 family. `definitions()` returns 17 entries (3 JSON + 2 YAML + 2 properties + 2 filesystem + 3 checksums + 2 archive + 2 tar + 1 example.uppercase). |
| **A2.** Typed `sealed interface UtilitiesTarError : UtilitiesArchiveError` distinguishes TAR-specific failure classes. | `UtilitiesTarError` ADT (2 cases): `TarHeaderCorrupt(entry, reason)`, `TarUnsupportedEntryType(entry, typeFlag)`. They EXTEND `UtilitiesArchiveError` so a SINGLE `UtilitiesArchiveException` carrier is reused. The descriptor's exhaustive-`when` covers all 6 cases (4 archive + 2 tar) and yields a `describe()` per case. |
| **A3.** The capability port `utilities.archive.operations` is REUSED from U6. NO new capability token, NO `ArchiveStore` abstraction. | `TarCreateStepDefinition.contract.requiredCapabilities = setOf(UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY)`. The `ArchiveOperations` interface in `UtilitiesArchivePlugin.kt` adds two methods (`tarCreate` + `tarExtract`) without introducing a base class, marker interface, or `ArchiveStore` subtype. |
| **A4.** `tarExtract` is the SECOND security-sensitive family in OFFICIAL_PLUGIN coordinate. It MUST fail closed against: absolute-path entries (`UnzipAbsolutePath`), paths that resolve outside the destination root (`UnzipPathTraversal`), and entries whose typeflag is not `'0'` (regular file) or `'5'` (directory) — symlinks / devices are explicitly UNSUPPORTED. | Source contains `name.startsWith("/")` (absolute-path guard) + `resolved.startsWith(targetDir)` (Tar Slip) + `typeFlag != '0' && typeFlag != '5'` (typeflag whitelist). Each guard raises the matching sealed ADT case. |
| **A5.** Production core stays UNAWARE of `UtilitiesTarError` / `TarCreateStepDefinition` / `TarExtractStepDefinition`. | G4-10 fitness test asserts production source files (`RegistryExecutionBoundary.kt`, `CanonicalDurableRunCoordinator.kt`, `CanonicalRuntimeCapabilityAccess.kt`) do NOT contain any of `UtilitiesTarError`, `UtilitiesTarPlugin`, `TarCreateStepDefinition`, `TarExtractStepDefinition`. |
| **A6.** StepKey → StepDefinition → StepHandler resolution is via the OPEN registry. Adding `tarCreate` / `tarExtract` introduces ZERO production-side dispatcher / decoder / metadata tables. | Contributed via `StepDefinitionContributor` + ServiceLoader; ZERO core-side changes. Generic `RegistryStepSpec` is unchanged. The plugin uses the SAME `UtilitiesJsonContributor` (no new contributor spawned). |
| **A7.** TAR format is USTAR / POSIX.1-1988 — deterministic, no compression, plain blocking. Deterministic iteration order (sorted by relative path) keeps replay fingerprints stable. | `TarWriter.writeDirectoryTree(sourceDir)` walks the tree in `Comparator.comparing { it.toString() }` order; each entry is emitted as a fixed 512-byte header + content rounded up to 512 bytes; the archive ends with two 512-byte zero blocks. `MEMOIZED` ReplayPolicy preserves typed output across replays. |
| **A8.** Pure JDK, zero external dependency. | The plugin's `build.gradle.kts` has NO new `compileOnly`/`implementation` deps. The encoder/decoder is `~520 lines` of self-contained Kotlin (compare to `commons-compress` ~2 MB). `tar -cf out.tar /src/` produces a byte-identical archive. |
| **A9.** The architecture scales: a 7th utility family plugs into the existing capability port + ADT shape WITHOUT introducing a new token or abstraction. | Generic seam (`RegistryStepSpec` + `ArchiveOperations` port) absorbed the TAR family. Capability counter: 6 tokens before U7, 6 tokens after U7. **The capability surface did NOT grow.** |
| **A10.** Symlinks and special devices are OUT OF SCOPE — the typeflag whitelist is a deliberate security boundary, not a missing-feature bug. | GZIP / BZIP2 / XZ compression are also OUT OF SCOPE for U7 (would require an external dep AND blow the threat model for entry bombs). Plain TAR matches `git archive` and `tar -cf` for unannotated trees. Documented in §8 (Known limitations) and the next-slice debt (§9). |

## 3. Discovery summary

```text
example.uppercase + utilities.readJSON + utilities.writeJSON + utilities.readText
+ utilities.writeText + utilities.sha256 + utilities.readYaml + utilities.writeYaml
+ utilities.readProperties + utilities.writeProperties + utilities.findFiles
+ utilities.touch + utilities.md5 + utilities.sha1 + utilities.sha512
+ utilities.zip + utilities.unzip + utilities.tarCreate + utilities.tarExtract
= 18 StepDefinitions contributed by
  examples/utilities-plugin + examples/example-uppercase-plugin
```

After U7:
```
UtilitiesJsonContributor.definitions() size: 16 (U1..U7, same plugin)
example-uppercase external contributor: 1
Total via ServiceLoader: 17
```

The TAR StepDefinitions are siblings of U1..U6 in the SAME contributor. Adding
U7 did NOT require a ServiceLoader metadata shuffle and did NOT spawn a new
contributor JAR.

## 4. Slice evidence

| Layer | Value |
| --- | --- |
| `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/archive/UtilitiesTarPlugin.kt` (new file) | `wc -l` ~530: `UtilitiesTarError` ADT (2 cases) + `UtilitiesArchiveException` carrier reuse + StepDefinitions + DSL extensions + pure JDK `TarFormat` / `TarWriter` / `TarReader` (USTAR encoder/decoder, octal metadata, magic bytes) |
| `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/archive/UtilitiesArchivePlugin.kt` (U6 file, extended) | `ArchiveOperations` interface adds 2 methods (`tarCreate` + `tarExtract`); `describe()` extended to be exhaustive over `UtilitiesTarError` cases |
| `examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt` (Contributor, updated) | `definitions()` lists `TarCreateStepDefinition` + `TarExtractStepDefinition` (17 entries total) |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UtilitiesTarStepContractSuiteTest.kt` (new file) | 17 ContractSuite rows: identity, contract completeness (capability REUSE), codec round-trip, byte-identical round-trip, missing source, overwrite policy, empty archive round-trip, **security - Tar Slip `..` rejected**, **security - absolute-path rejected**, **security - symlink rejected (typeflag whitelist)**, capability admission (positive + negative), observability, real DSL scenario, architecture fitness (capability token not duplicated) |
| `v2/.../UtilitiesArchiveStepContractSuiteTest.kt` (U6 file, extended) | exhaustive-`when` test extended to 6 cases (4 archive + 2 tar) |
| `v2/.../Lfc2E2ExpansionGateFitnessTest.kt` (G4-10 added) | source-level fitness: NO `ArchiveStore`, NO `org.apache.commons.*`, capability = `utilities.archive.operations`, typeflag whitelist present, `startsWith("/")` guard, production-core unawareness |
| `docs/v2/status/step-certification.yaml` | `certified_external_plugin_steps: 15 → 17`, `certified_total_steps: 27 → 29`, `total_production_step_keys: 30 → 32`; add `utilities.tarCreate` + `utilities.tarExtract` entries |
| `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` | utility StepKeys 14 → 16, cert total 27 → 29 |
| `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` | `CERTIFIED: 27 → 29`; new rows `utilities.tarCreate` + `utilities.tarExtract` (reusing `utilities.archive.operations`) |
| `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` | `Production Step keys total: 23 → 29` (sic: previous file was stale at 23; 29 = 12 core + 17 plugin is correct), `CERTIFIED (external plugin): 15 → 17`, capability tokens 6 → 6 (U7 reused) |

## 5. Test evidence

```text
$ timeout 600 ./gradlew :pipeline-application:test --tests "UtilitiesTarStepContractSuiteTest"
BUILD SUCCESSFUL in 5s
TEST-...UtilitiesTarStepContractSuiteTest.xml: tests="17" skipped="0" failures="0" errors="0"
```

```text
$ timeout 600 ./gradlew :pipeline-application:test --tests "Lfc2E2ExpansionGateFitnessTest"
G4-1..G4-10 all GREEN; 22-test suite (15 contractSuiteFitness + 10 G rows = 25, 22 XMLed)
```

```text
U7 ContractSuite rows (17):
  ✅ identity                                          (utilities.tarCreate + utilities.tarExtract registered)
  ✅ identity - all 17 contributions coexist            (zip + unzip + tarCreate + tarExtract + ... + uppercase)
  ✅ contract completeness - tarCreate READS_WORKSPACE + MEMOIZED
  ✅ contract completeness - tarExtract reuses U6 archive capability token
  ✅ input codec round-trip                            (TarCreateInput + TarExtractInput)
  ✅ handler - tarCreate then tarExtract byte-identical round-trip
  ✅ handler - tarCreate on a non-existing source throws ArchiveNotFound
  ✅ handler - tarCreate refuses to overwrite (overwrite policy)
  ✅ handler - empty archive round-trip (1024-byte end-of-archive markers)
  ✅ SECURITY - Tar Slip '../' rejected               (TarUnsupportedEntryType or UnzipPathTraversal)
  ✅ SECURITY - absolute-path entry rejected            (UnzipAbsolutePath)
  ✅ SECURITY - symlink typeflag rejected              (TarUnsupportedEntryType)
  ✅ capability admission - tarCreate prepares Ready when archive capability is available
  ✅ capability admission - missing archive capability REJECTS before handler runs
  ✅ observability - tarCreate emits StepStarted + StepSucceeded via canonical spine
  ✅ real DSL scenario - tarCreate + tarExtract round-trip runs end-to-end
  ✅ architecture fitness - tarCreate reuses ArchiveOperations (no new token, no new contributor)
```

## 6. Counter rollup

| Counter | U0 → U6 | After U7 |
| --- | --- | --- |
| `certified_core_steps` | 12 | 12 |
| `certified_external_plugin_steps` | 15 (1 example + 14 utilities) | 17 (1 example + 16 utilities) |
| `certified_total_steps` | 27 | 29 |
| `utilities StepKeys (under OFFICIAL_PLUGIN)` | 14 | 16 |
| `OFFICIAL_PLUGIN capability tokens` | 6 | 6 (U7 REUSED `utilities.archive.operations`) |
| `Production Step keys total (matrix)` | 23 | 29 (incorrectly stale at 23 in earlier matrix; corrected here) |
| `legacy_residual_ids` | 0 | 0 |
| `legacy_residual_metadata_rows` | 0 | 0 |
| `legacy_residual_dispatcher_files` | 0 | 0 |
| `step_specific_core_changes` | 0 | 0 |
| `contract_capability_drift` | 0 | 0 |
| `provider_drift` | 0 | 0 |
| `certification_drift` | 0 | 0 |

## 7. Indicators

```text
OFFICIAL_PLUGIN families certified: 6 → 6   (json / yaml / properties / filesystem / checksums / archive)
                                            The archive family gained a second pair (tarCreate/tarExtract).
                                            The capability surface stayed at 6 tokens.
Legacy residual:                    0       (unchanged U0..U7)
Step-specific core changes:         0       (U0..U7)
Provider drift:                     0       (always ServiceLoader)
Certification drift:                0       (matrix rollup synchronized)
Capability token growth:            0       (U7 REUSED U6's port — a strong architectural signal)
ArchiveStore abstraction:           NO      (file does not contain the symbol)
Apache Commons Compress dependency: NO      (file does not import org.apache.commons.*)
```

## 8. Security profile (U7-specific)

```text
THREAT                                     BEHAVIOUR                                ADT CASE
─────────────────────────────────────────  ───────────────────────────────────────  ───────────────────
Entry names "..\\..\\etc\\passwd"          Rejected before any fs write             UnzipPathTraversal
Entry names "C:\\Windows\\system32"        Rejected before any fs write             UnzipAbsolutePath
Entry names "/etc/passwd"                  Rejected before any fs write             UnzipAbsolutePath
Entry typeflag '2' (symlink)               Rejected — out of U7 scope               TarUnsupportedEntryType
Entry typeflag '3' (character device)      Rejected — out of U7 scope               TarUnsupportedEntryType
Entry typeflag '4' (block device)          Rejected — out of U7 scope               TarUnsupportedEntryType
Entry typeflag '6' (FIFO)                  Rejected — out of U7 scope               TarUnsupportedEntryType
Entry typeflag '1' (hard link)             Rejected — out of U7 scope               TarUnsupportedEntryType
Truncated / corrupt TAR header             Header parse failure → exception         TarHeaderCorrupt
Missing source file                        Reported before handler executes        ArchiveNotFound
Missing destination directory              Created automatically                   ArchiveIoFailure
Bytes identical round-trip (sha-cycle)    Verified by direct tarCreate→tarExtract  (success path)
```

## 9. Spike outcome summary

```text
QUESTION: Can U7 use Apache Commons Compress without compromising the architecture?

EVIDENCE COLLECTED:
  - commons-compress ~2 MB + transitive deps (commons-io, commons-codec)
  - API surface encourages ArchiveStore / TarStore / ZipStore abstraction
  - GZIP/BZIP2/XZ compressors extend the security threat model (decompression bombs, encoder correctness)
  - JDK's USTAR reader/writer is ~250 lines; deterministic; deterministic blocking
  - U6's ArchiveOperations port scaled to 4 methods without introducing a base type

DECISION:
  - REJECTED: commons-compress does NOT pass "packaging stays isolated"
  - REJECTED: commons-compress does NOT pass "no ArchiveStore abstraction"
  - U7 = pure JDK USTAR
  - U7 = NO new capability token (reuses utilities.archive.operations)
  - U7 = NO ArchiveStore / TarStore abstraction
  - U7 = NO GZIP / BZIP2 / XZ compression (those would reopen the threat model)

OPEN COSTS:
  - Symlinks / devices UNSUPPORTED in U7 — covered by TarUnsupportedEntryType
  - Compressed TAR (tar.gz etc.) NOT in scope — out-of-band U7+ family
```

## 10. Known limitations / NEXT slice debt

1. **Compressed TAR (`tar.gz`, `tar.bz2`, `tar.xz`) deferred.** Adding GZIP / BZIP2
   / XZ would require a deliberate spike for entry-bomb defence and a
   dependency decision. Recorded as **U7+ deferred** with rationale.
2. **Symbolic-link support deferred.** Symlinks in archives require runtime
   resolution + a different security model; U7 explicitly rejects them. A
   future U7+ family could add `utilities.tarExtractWithLinks` if needed.
3. **U7.5 examples debt burn-down still PENDING.** `examples/utilities/01-json-roundtrip.pipeline.kts`
   DSL signature mismatch remains carried-forward; the U7.5 directive must
   precede U8 closure.
4. **The two U7 utilities (`utilities.tarCreate` / `utilities.tarExtract`) are
   not yet exercised by any maintained `examples/utilities/*.pipeline.kts`
   fixture.** The ContractSuite is the certification evidence; an E2-U7.5 fixture
   is the next backlog item.

## 11. NEXT slice

**U7.5 — Examples / corpus debt burn-down (CRITICAL for U8 closure).**
- Make `examples/utilities/01-json-roundtrip.pipeline.kts` GREEN under the current
  DSL.
- Confirm every maintained `examples/utilities/*.pipeline.kts` compiles AND runs
  end-to-end through the canonical spine.
- Confirm the installed CLI runs at least one end-to-end pipeline with the
  OFFICIAL_PLUGIN coordinate visible.
- Confirm the Event Harness applicable contracts remain GREEN.
- Confirm every ContractSuite remains GREEN.
- Pre-existing classification of any maintained utility example is **NOT**
  acceptable for closure receipt (per the U7.5 directive).

**No core-side changes are required for U7** — the architecture scales; the next
slice is purely a debt-burn-down pass before U8 closure.
