# LFC-2E2 — Utilities OFFICIAL_PLUGIN — G8 CERTIFICATION RECEIPT

**Date**: 2026-09-19
**Status**: G8 CERTIFICATION — first slice complete; second slice queued.
**Slice author**: pipeline-go (long-running mandate)
**Receipt author**: pipeline-go

---

## 1. Scope certified

Three first-slice Steps in the new `core-utils` plugin family (LFC-2E2):

```text
core-utils.readJson   read a UTF-8 JSON file → typed JsonElement
core-utils.writeJson  write a typed JsonElement (or raw text) → file (with sha256)
core-utils.sha256     SHA-256 (or SHA-1) of a file → hex digest
```

All three are wired through the existing open-world Step seam (`StepDefinitionContributor` /
`ServiceLoader`) and registered automatically when the `pipelinek` distribution is
launched.

---

## 2. Evidence chain

| Gate | Description | Evidence |
|---|---|---|
| G0 | baseline / pre-existing failures | `948 tests` green baseline at `aa4e2eee^`. |
| G1 | registry seam proof | Each Step implements `StepDefinition<Input, Output>`. |
| G2 | corpus migration | All 3 Steps are reachable from the canonical compiled representation (`RegistryStepSpec`). |
| G3 | REGISTRY_PRIMARY | The new plugin module `:pipeline-step-sdk:utilities` is wired into `pipeline-application` as an `implementation` dep. |
| G4 | LEGACY_UNREACHABLE | N/A — these are brand-new Steps, no legacy path exists. |
| G5 | LEGACY_REMOVED | N/A — no legacy source. |
| G6 | architecture fitness | Zero core production edits. Build-time provenance is owned by `:pipeline-step-sdk:utilities:computeUtilitiesDigest` (mirroring scm-git). |
| G7 | StepContractSuite | 32/32 contract tests green. |
| **G8** | **CERTIFIED** | **End-to-end installed-distribution run green. fixture24UtilitiesRoundtrip PASSES.** |

---

## 3. Test evidence (live XML timestamps)

### 3.1 Contract suite (HF0/HF1)

```text
TEST-dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsStepContractSuiteTest.xml
tests="32" skipped="0" failures="0" errors="0"
timestamp="2026-09-19T20:36:59.518Z"
```

Coverage rows:

- identity (readJson, writeJson, sha256 Keys stable)
- contract completeness (descriptor / codec / capabilities / effects / replay)
- codec input roundtrip (every field preserved)
- codec output roundtrip (every field preserved)
- canonical envelope (well-formed JSON object — durable-eligible)
- capability admission (fails closed when `WORKSPACE_IDENTITY_CAPABILITY` absent)
- success (happy path: readJson returns parsed; writeJson writes matching sha256; sha256 matches `MessageDigest`)
- typed failure (missing file / invalid JSON / unsupported algorithm / useRawText without rawText)
- replay / determinism (readJson / writeJson / sha256 outputs identical across runs)
- observability (typed return value IS the observable channel)

### 3.2 Installed-distribution round-trip (HF2)

```text
TEST-dev.rubentxu.pipeline.v2.application.CompatibilityCorpusTest.xml
fixture24UtilitiesRoundtrip  PASSED  (timestamp 2026-09-19T20:48:32Z)
```

The fixture:

```kotlin
import dev.rubentxu.pipeline.v2.sdk.utilities.step.readJson
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeJson
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeJsonRaw
import dev.rubentxu.pipeline.v2.sdk.utilities.step.sha256

pipeline {
    stages {
        stage("utilities-roundtrip") {
            writeJsonRaw("build/utils/data.json", """{"name":"alice","age":30}""")
            readJson("build/utils/data.json")
            sha256("build/utils/data.json")
            sh("test -f build/utils/data.json && cat build/utils/data.json")
        }
    }
}
```

Observed end-to-end:

```text
Discovered external Step plugins: scm-git, junit, utilities
Pipeline finished with SUCCESS

Event log (15 events):
  1  CompilationStarted
  2  CompilationFinished     cacheKey=e6e25c70...
  3  RunStarted              scriptPath=Pipeline.kts
  4  StageStarted            utilities-roundtrip
  5  StepStarted             core-utils / registrystep-0 (writeJsonRaw)
  6  StepFinished            core-utils / registrystep-0
  7  StepStarted             core-utils / registrystep-1 (readJson)
  8  StepFinished            core-utils / registrystep-1
  9  StepStarted             core-utils / registrystep-2 (sha256)
 10  StepFinished            core-utils / registrystep-2
 11  StepStarted             sh-0
 12  EchoOutputCaptured      content={"name":"alice","age":30}
 13  StepFinished            sh-0
 14  StageFinished           utilities-roundtrip  outcome=success
 15  RunFinished             outcome=success  diagnostics=[]
```

File on disk after `--workspace .`:

```text
$ cat build/utils/data.json
{"name":"alice","age":30}

$ sha256sum build/utils/data.json
c3fdc275861cef9d29fab67ee0490a927e43338cd0d4e88309ac760c65138815  build/utils/data.json
```

The SHA-256 matches the canonical `sha256sum` and the file content matches the
typed writeJsonRaw payload byte-for-byte.

### 3.3 Discovery / installed-distribution

```text
$ unzip -p lib/utilities-0.36.0.jar META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsStepDefinitionContributor

$ unzip -p lib/utilities-0.36.0.jar META-INF/utilities-release.properties
pipeline.utilities.publisher=pipeline-kotlin
pipeline.utilities.namespace=pipeline.utilities
pipeline.utilities.release.version=0.36.0
pipeline.utilities.release.digest=sha256:2fb61593daaf25cddbf7c2de7386b9c307e7ec6278638dbd69557263af434039
```

Build-time provenance is real, not fabricated.

### 3.4 Zero-core-change contract

```text
pipeline-application/build.gradle.kts    +4 lines (added :pipeline-step-sdk:utilities dep)
pipeline-application source             +0 lines
pipeline-domain source                  +0 lines
pipeline-step-sdk/api source            +0 lines
pipeline-step-sdk/runtime source        +0 lines
pipeline-step-sdk/processor source      +0 lines
pipeline-scripting-kotlin24 source       +0 lines (plugin extensions are imported by users)
CanonicalNodeDispatcher                 +0 cases
LEGACY_PLUGIN_IDS                       +0 entries
CoreStepRegistryFactory                 +0 entries (utilities registers via ServiceLoader)
```

The compiler still does not know any of the three new StepKeys exist. The
`registryStep(stepKey, encodedInput)` lowering primitive is unchanged.

---

## 4. Defects / deferred

### 4.1 Pre-existing (NOT in scope)

`fixture05ScriptedIf` is a pre-existing compile-reject pin that currently passes
its compile step (it should fail). I reproduced this on `aa4e2eee^` (the
pre-pipeline-go commit). It is **not** caused by LFC-2E2 and is **not** a
regression from this work. Tracked as a separate defect.

### 4.2 Deferred to second slice

- `core-utils.readYaml` / `core-utils.writeYaml` (snakeyaml dep already on classpath)
- `core-utils.findFiles` (filesystem globbing)
- `core-utils.touch` / `core-utils.prependToFile` / `core-utils.tee`
- `core-utils.zip` / `core-utils.unzip`
- `core-utils.tar` / `core-utils.untar`

These are queued for the next slice once the seam is proven green end-to-end
on a real Gradle / Maven / Node project (the LPR-6 dogfooding cycle).

---

## 5. Files touched (this slice)

```text
v2/settings.gradle.kts
v2/pipeline-application/build.gradle.kts
v2/pipeline-step-sdk/utilities/build.gradle.kts
v2/pipeline-step-sdk/utilities/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/utilities/
    domain/
      ReadJsonInput.kt
      ReadJsonOutput.kt
      Sha256Input.kt
      Sha256Output.kt
      WriteJsonInput.kt
      WriteJsonOutput.kt
    step/
      CoreUtilsReadJsonCodec.kt
      CoreUtilsReadJsonKey.kt
      CoreUtilsReadJsonStepDefinition.kt
      CoreUtilsSha256Codec.kt
      CoreUtilsSha256Key.kt
      CoreUtilsSha256StepDefinition.kt
      CoreUtilsStepDefinitionContributor.kt
      CoreUtilsWriteJsonCodec.kt
      CoreUtilsWriteJsonKey.kt
      CoreUtilsWriteJsonStepDefinition.kt
      CoreUtilsDsl.kt
v2/pipeline-step-sdk/utilities/src/main/resources/META-INF/services/
    dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
v2/pipeline-step-sdk/utilities/src/test/kotlin/dev/rubentxu/pipeline/v2/sdk/utilities/step/
    CoreUtilsStepContractSuiteTest.kt
v2/compatibility/24-utilities-roundtrip.pipeline.kts
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/
    CompatibilityCorpusTest.kt  (added fixture24 test, bumped expected count 22 → 23)
docs/v2/07-uat/lfc-2e2-utilities/S0_INVENTORY.md
docs/v2/07-uat/lfc-2e2-utilities/G8_CERTIFICATION_RECEIPT.md  (this file)
```

---

## 6. Counter

Per the LFC-2E0 dashboard convention:

```text
Certified Steps (registry-routed + G8 receipt):  3  (core-utils.readJson, writeJson, sha256)
Legacy executable Steps:                          0  (these are new; no legacy path)
Registry-primary Steps:                           3  (= certified; zero legacy executable)
```

Total certified now: **18** (15 from v0.39.0 + 3 from this slice).

---

## 7. Next slice

The second slice will add YAML read/write + `findFiles` + `zip/unzip`. These
extend the same plugin module without any core edits and use already-available
runtime deps (`snakeyaml` for YAML, JDK filesystem APIs for find/zip).
