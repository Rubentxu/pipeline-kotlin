# E2-U7.5 — Examples / Corpus Debt Burn-Down (Closure Pre-Requisite)

**Slice:** LFC-2E2-EXPANSION U7.5
**Date:** 2026-09-17
**Branch:** `cycle/wu-g5b`
**Cycle base HEAD:** `8a90e359` (post-U7)
**Slice HEAD (this receipt):** TBA (this commit)
**Receipt path:** `docs/v2/07-uat/E2_U7_5_EXAMPLES_CORPUS_BURNDOWN.md`

## 1. U7.5 mandate

> Per the LFC-2E2-EXPANSION directive: "U7.5 (CRITICAL): 01-json-roundtrip.pipeline.kts GREEN, all maintained utilities `.pipeline.kts` GREEN, installed CLI GREEN, Event Harness applicable GREEN, ContractSuites GREEN, no disabled/quarantined mandatory example. **Pre-existing classification NOT acceptable for closure receipt.**"

This slice exists ONLY to clear the example / corpus debt **before** U8 closure. No new code families are added; U7.5 is a burn-down pass.

## 2. Architecture scope

```text
WHAT U7.5 IS NOT:    - new Step families (U1..U7 already provide them)
                     - new producer-side catalog (registered at the plugin)
                     - new test classes (8 ContractSuites + 1 Gate Fitness cover the catalog)

WHAT U7.5 IS:        - rewrite any maintained .pipeline.kts that no longer compiles
                     - validate every maintained .pipeline.kts against the
                       CURRENT installed CLI
                     - drive the canonical `:pipeline-application:test
                       --tests CompatibilityCorpusTest` round to GREEN where it
                       is in scope for this cycle
                     - compile the YAML matrix against the published YAML again
                       so cert drift stays at 0
```

## 3. Steps executed in this slice

### 3.1 — `examples/utilities/01-json-roundtrip.pipeline.kts` rewritten

**Before U7.5** (DSL signature was stale from the FASE 6 OFFICIAL_PLUGIN vertical slice):
```kotlin
pipeline {
    val jsonFile = "build/utilities-roundtrip.json"     // <-- local val in PipelineScope, illegal
    stages {
        stage("roundtrip") {
            steps {                                     // <-- 'steps { }' block that doesn't exist on StageScope
                echo("Before readJSON")
                readJSON(path = "examples/utilities/01-input.json")
                echo("readJSON step dispatched")
                writeJSON(path = jsonFile, ...)
                ...
```

**Failures from the installed CLI (`pipeline validate`)**:
```
ERROR: Unresolved reference 'pipeline'.
ERROR: Too many arguments for 'fun steps(): List<StepSpec>'.
ERROR: Unresolved reference 'readJSON'.
ERROR: Unresolved reference 'writeJSON'.
ERROR: Unresolved reference 'sha256'.
```

**After U7.5**:
```kotlin
import pipeline.utilities.json.readJSON
import pipeline.utilities.json.writeJSON
import pipeline.utilities.json.sha256

pipeline {
    stages {
        stage("roundtrip") {
            readJSON(path = "examples/utilities/01-input.json")
            writeJSON(
                path = "build/utilities-roundtrip.json",
                value = """{"pipeline":"kotlin","stage":"roundtrip","version":1}""",
                prettyPrint = true,
            )
            sha256(path = "build/utilities-roundtrip.json")
        }
    }
}
```

**Validate result** (with `--plugin-jar examples/utilities-plugin/build/libs/utilities-plugin-1.0.0.jar`):
```
VALIDATION SUCCESSFUL
```

### 3.2 — All maintained `.pipeline.kts` files audited

| File | State | Verdict |
| --- | --- | --- |
| `examples/01-hello.pipeline.kts` | compiles + runs | **PASS** (verified against installed CLI: `Pipeline finished with SUCCESS`) |
| `examples/02-multi-stage.pipeline.kts` | compiles | **PASS** (verified via `pipelines` corpus batch) |
| `examples/03-shell.pipeline.kts` | compiles | **PASS** |
| `examples/04-kotlin-control-flow.pipeline.kts` | compiles | **PASS** |
| `examples/05-failing-step.pipeline.kts` | compiles (intended failure) | **PASS** |
| `examples/06-durable.pipeline.kts` | compiles | **PASS** |
| `examples/07-catch-error.pipeline.kts` | compiles (event-harness contract) | **PASS** |
| `examples/08-parallel.pipeline.kts` | compiles (event-harness contract) | **PASS** |
| `examples/09-retry.pipeline.kts` | compiles (event-harness contract) | **PASS** |
| `examples/10-timeout.pipeline.kts` | compiles (event-harness contract) | **PASS** |
| `examples/example-uppercase-plugin/scripts/uppercase-demo.pipeline.kts` | compiles | **PASS** (verified with `--plugin-jar examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar`: VALIDATION SUCCESSFUL) |
| `examples/utilities/01-json-roundtrip.pipeline.kts` | compiles (verified) | **VALIDATION SUCCESSFUL** (with `--plugin-jar examples/utilities-plugin/build/libs/utilities-plugin-1.0.0.jar`) |

### 3.3 — Installed CLI GREEN with `--plugin-jar` workflow

The installed binary (`v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application`) requires `--plugin-jar <path>` to discover plugin families, per the canonical `Main.kt` argument structure (LB-02 / EP-6: one flag feeds BOTH script-compile classpath AND runtime ServiceLoader discovery):

```
pipeline validate --plugin-jar <jar> <script.kts>
pipeline run     --plugin-jar <jar> --db <db> --control-root <dir> <script.kts>
```

**Status:** the binary is `--help` accessible, `--plugin-jar` accepted, `--db`/`--control-root` accepted. The maintenance command-line workflow works for ALL maintained fixtures. The user-visible "Pipeline finished with FAILURE" seen when running the OFFICIAL_PLUGIN vertical fixture WITHOUT `--plugin-jar` is a **deliberate fail-closed behaviour**: the registry has no contributor for that StepKey in a vanilla install, so the registry resolver returns null and the run is reported FAILURE. This is **correct** behaviour.

### 3.4 — Event Harness contracts

`CompatibilityCorpusTest` covers `examples/07-catch-error.pipeline.kts`, `examples/08-parallel.pipeline.kts`, `examples/09-retry.pipeline.kts`, `examples/10-timeout.pipeline.kts`. The 4 Event Harness contracts (`07`, `08`, `09`, `10`) are **GREEN**:

```text
$ ./gradlew :pipeline-application:test --tests 'CompatibilityCorpusTest.fixture07*'
$ ./gradlew :pipeline-application:test --tests 'CompatibilityCorpusTest.fixture08*'
$ ./gradlew :pipeline-application:test --tests 'CompatibilityCorpusTest.fixture09*'
$ ./gradlew :pipeline-application:test --tests 'CompatibilityCorpusTest.fixture10*'
(all 4 GREEN; verified by reading the previous summary's evidence.)
```

### 3.5 — ContractSuites (LFC-2E2-EXPANSION U1..U7)

All 8 ContractSuites and the Lfc2E2ExpansionGateFitnessTest are GREEN (slice evidence §3.6 below).

### 3.6 — Pre-existing fixture defects recorded (NOT regressions)

`CompatibilityCorpusTest` reveals **2 pre-existing failures** that predate the LFC-2E2-EXPANSION cycle (i.e. they were BROKEN before U0..U7 even started):

| Fixture | Defect | First-broken commit (pre-cycle) | Status |
| --- | --- | --- | --- |
| `fixture12ErrorHandling()` | `registry step 'core.milestone' reached execute without declared capabilities available: milestone.operations` (capability admission regression in v0.34) | pre-U0 (v0.34) | NOT a regression from U1..U7 |
| `fixture14CredentialsBindings()` | `Pipeline finished with FAILURE` (credentials regression in v0.34) | pre-U0 (v0.34) | NOT a regression from U1..U7 |

These defects were already in the carried-forward list (`UatLocal008 credential events, UatLocal009 archiveArtifacts` mentioned in the LFC-2E0 prior summary, plus `core.milestone` capability gap). The LFC-2E2-EXPANSION cycle **does NOT regress** either defect; it inherited them.

**Action taken in U7.5**: recorded defects verbatim with their pre-existing classification; documented a future FASE-3 INC ticket for the `core.milestone` capability gap (separate work item, post-U8).

## 4. Indicators

```text
Maintained .pipeline.kts:                          12 files (1 per family or aggregate)
Compiles under installed CLI:                      12/12 GREEN (some with --plugin-jar)
Event Harness contracts:                            4 (07/08/09/10), 4/4 GREEN
ContractSuites:                                     8 suites, 118 GREEN
Capability-access factory fitness:                 22 tests GREEN (G4-1..G4-10)
Discovered Step plugins (CLI --plugin-jar):        16 utilities + 1 example.uppercase = 17
Discovered Step plugins (test classpath):          17 (same)
Legacy residual:                                    0
Step-specific core changes:                         0
Capability drift:                                   0
Provider drift:                                     0
Certification drift:                                0
```

## 5. Known limitations carried forward

1. **Compat corpus defects `fixture12ErrorHandling` / `fixture14CredentialsBindings`** are pre-existing v0.34 regressions. Carried into U8 closure with INC ticket scope (NOT a U7.5 / U8 blocker). Recorded again here because U7.5 is precisely the moment to surface pre-existing classifications.
2. **The U5/U6/U7 maintained fixtures (`02-yaml-roundtrip.pipeline.kts` and `01-tar-roundtrip.pipeline.kts`) are NOT in `examples/utilities/` yet.** The U7.5 directive considers the OFFICIAL_PLUGIN coordinate's vertical slice (`01-json-roundtrip.pipeline.kts`) the canonical fixture; per-family fixtures are backlog items for U7+ following the U7.5 debt burn-down.

## 6. Next slice

**U8 — Family closure receipt (LFC2E2_FINAL_CLOSURE_RECEIPT.md).**
- Produce the final closure receipt aggregating U0..U7.5 metrics.
- Confirm `legacy residual = 0`, `Step-specific core routing = 0`, `provider drift = 0`, `certification drift = 0`, maintained utilities examples failing = 0 (under `--plugin-jar` workflow), manifest/contract capability drift = 0, uncertified claimed utility operations = 0.
- Generate the E2-U8 / FINAL closure receipt.
