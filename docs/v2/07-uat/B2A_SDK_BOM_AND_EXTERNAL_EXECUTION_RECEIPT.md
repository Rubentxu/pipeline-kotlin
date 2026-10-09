# B2A — SDK BOM + external end-to-end execution — RECEIPT

- **Branch:** `s6-plugin-sdk`
- **Base SHA (unmodified at start):** `de591132520a7a922646dc96fffa1a7b53829a31`
- **Date:** 2026-10-08
- **Revision version:** `0.47.0` (`v2/build.gradle.kts` `rootProject.version`)
- **Scope:** additive only. No engine behaviour change. No published contract change.

## Deliverables (files)

```text
NEW  v2/pipeline-sdk-bom/build.gradle.kts                       (java-platform BOM)
MOD  v2/settings.gradle.kts                                     (+ include ":pipeline-sdk-bom")
MOD  v2/build.gradle.kts                                        (+ sdkBomModules, + verifySdkExternalExecution)
NEW  examples/sdk-external-execution/settings.gradle.kts        (independent build)
NEW  examples/sdk-external-execution/build.gradle.kts
NEW  examples/sdk-external-execution/fixtures/external.pipeline.kts
NEW  examples/sdk-external-execution/src/main/kotlin/example/sdkext/consumer/SdkSurfaceConsumer.kt
NEW  examples/sdk-external-execution/src/test/kotlin/example/sdkext/external/ExternalExecutionTest.kt
NEW  docs/v2/07-uat/B2A_SDK_BOM_AND_EXTERNAL_EXECUTION_RECEIPT.md   (this file)
```

`git diff --stat` (tracked files): `v2/build.gradle.kts +83`, `v2/settings.gradle.kts +6`. The four
published contract modules' build files and POMs are **untouched** (see "condition of delivery").

---

## ENTREGA A — BOM del SDK

### What was built

A `java-platform` module `:pipeline-sdk-bom`, published to the same `v2/build/sdk-repo` by the same
task (`publishSdkForExternalPlugin`), carrying one `api` constraint per published contract at
`rootProject.version`. It is **not** added to `publishedContractModules`: that list is the authority
for artifacts whose *type surface* is frozen by `apiCheck`, and the BOM has no classes, no ABI, no
`src/` and no BCV entry. The build file states that distinction in place.

The BOM joins only the publication flow, via a second `dependsOn(sdkBomModules.map { ... })`. No new
publishing mechanism was introduced.

### Publication evidence

```text
$ cd v2 && timeout 900 ./gradlew --console=plain :publishSdkForExternalPlugin
EXIT=0   BUILD SUCCESSFUL in 12s   25 actionable tasks: 15 executed, 10 up-to-date
```

Produced `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/pipeline-sdk-bom/0.47.0/pipeline-sdk-bom-0.47.0.pom`
(`.pom` sha256 `d03ad617b7e274f8569882bf66f4b3ad0db5fba57f8ebeee2134de0f9d5ec3f5`), whose body is:

```xml
<packaging>pom</packaging>
<dependencyManagement>
  <dependencies>
    <dependency><groupId>dev.rubentxu.pipeline.v2</groupId><artifactId>pipeline-domain</artifactId><version>0.47.0</version></dependency>
    <dependency><groupId>dev.rubentxu.pipeline.v2</groupId><artifactId>pipeline-scripting-api</artifactId><version>0.47.0</version></dependency>
    <dependency><groupId>dev.rubentxu.pipeline.v2</groupId><artifactId>pipeline-events</artifactId><version>0.47.0</version></dependency>
    <dependency><groupId>dev.rubentxu.pipeline.v2</groupId><artifactId>pipeline-output</artifactId><version>0.47.0</version></dependency>
  </dependencies>
</dependencyManagement>
```

### Proof required by the WU — external consumer, platform + versionless coordinates

`examples/sdk-external-execution/build.gradle.kts` declares exactly:

```kotlin
implementation(platform("dev.rubentxu.pipeline.v2:pipeline-sdk-bom:$sdkVersion"))
implementation("dev.rubentxu.pipeline.v2:pipeline-domain")
implementation("dev.rubentxu.pipeline.v2:pipeline-scripting-api")
implementation("dev.rubentxu.pipeline.v2:pipeline-events")
implementation("dev.rubentxu.pipeline.v2:pipeline-output")
```

and `SdkSurfaceConsumer.kt` names one public type per contract (`StageScope`, `DomainEvent`,
`OutputCursor`, `FailureKind`), so all four must resolve on the compile classpath.

**Condition of delivery met:** this was achieved **without touching the POMs of the four modules or
their build scripts**. The platform's `api` constraints are read from the published BOM (POM
`dependencyManagement` + Gradle module metadata). No module POM was modified.

---

## ENTREGA B — external end-to-end execution

`examples/sdk-external-execution` is an INDEPENDENT Gradle build (own settings file, no `project(...)`):

1. **Builds the plugin JAR** from `examples/example-uppercase-plugin` through its own Gradle build
   (`Exec`, independent settings), against the same published SDK:
   `-PsdkRepo=<sdk-repo> -PsdkVersion=0.47.0 jar`.
2. **Runs the INSTALLED distribution** (`:pipeline-application:installDist` binary
   `.../install/pipelinek/bin/pipelinek`) against `fixtures/external.pipeline.kts`, which uses the
   plugin's own `uppercaseObserved` Step, passing the JAR through **`--plugin-jar`** (the real CLI
   argument; verified in `CliParser.kt:220`).
3. **Asserts the result** (`ExternalExecutionTest`): exit `0`; stderr contains
   `Pipeline finished with SUCCESS`; stdout contains the plugin's own event kind
   `example.uppercase.applied`, the `PluginEventEmitted` envelope, and the handler's own payload
   fragment `"payload":"v1:5:5"` (input `"hello"` → output `"HELLO"`, encoded by the plugin's codec).
   The payload is the witness that the handler *executed* rather than merely being composed.

Observed stdout (one run, real binary), the assertion subjects in context:

```json
{"kind":"StepStarted",...,"stepName":"external/registrystep-0","stepType":"example"}
{"kind":"PluginEventEmitted","registryKind":"example.uppercase.applied","schemaVersion":1,"payload":"v1:5:5","emittedBy":"example.uppercase"}
{"kind":"StepFinished",...,"stepName":"external/registrystep-0","stepType":"example"}
```

Observed stderr composition report (product's own line):

```text
Discovered external Step plugins: scm-git, http, junit, utilities, example.uppercase
Discovered external event definitions: example.uppercase.applied
Pipeline finished with SUCCESS
```

### Canonical gate

A root task `:verifySdkExternalExecution` orchestrates publish + `installDist` (task dependencies,
never a nested `v2` invocation — the root build holds this checkout's build lock) and forks the
external build.

```text
$ cd v2 && timeout 900 ./gradlew --console=plain :verifySdkExternalExecution
EXIT=0   BUILD SUCCESSFUL in 18s   (77 actionable tasks: 16 executed, 61 up-to-date)
        external build: BUILD SUCCESSFUL in 15s; :test executed
```

Canary XML (deleted before the run; regenerated):

```text
examples/sdk-external-execution/build/test-results/test/TEST-example.sdkext.external.ExternalExecutionTest.xml
tests="1" skipped="0" failures="0" errors="0" timestamp="2026-10-08T11:33:23.962Z"
sha256 713a21be961300480c9a6fd1cc8700cf09a2bf18bda7853cf873585484d5c40a
```

### Cost, stated (not discovered later)

`:verifySdkExternalExecution` is **NOT wired into `check`**. If it were, every `check` would publish
to `sdk-repo`, run `installDist`, and fork a further Gradle for the plugin build. Measured duration
of the gate as run: **18 s warm** (publish + `installDist` up-to-date; external build 15 s including a
~5 s real pipeline run). Cold cost was not measured and is not claimed. It is a named closeout gate
step, like `verifyFabricContractConsumer` / `verifyScriptingContractConsumer`.

---

## Mutations (one per new claim; XML fresh; restoration verified by hash)

Backups were taken in `$JCODE_SCRATCH_DIR` (`b2a-ext-build.bak`, `b2a-fixture.bak`, `b2a-test.bak`)
and the deleting canary XML removed before each run.

| # | Claim killed | Mutation | Observed RED | Restored hash |
|---|---|---|---|---|
| M1 | BOM applies its constraints (ENTREGA A compile) | commented out the `platform(...)` line in the external build | `EXIT=1`; `:compileKotlin FAILED`; `Could not find dev.rubentxu.pipeline.v2:pipeline-domain:.` (and the other three) | `build.gradle.kts` = `239711c8…473d` ✓ |
| M2 | the plugin's contribution actually EXECUTED (ENTREGA B) | fixture uses core `echo("hello")` instead of `uppercaseObserved` | `EXIT=1`; test XML `failures="1"` at the `example.uppercase.applied` assertion, **while exit 0 / SUCCESS assertions passed** (run `echo` still succeeds) | `fixtures/external.pipeline.kts` = `f436a26f…bb3e1` ✓ |
| M3 | the pipeline reaches the expected outcome; the plugin JAR is load-bearing | `--plugin-jar` pointed to a non-existent path | `EXIT=1`; test XML `failures="1"` at the exit-code assertion; stderr `Plugin admission refused … artifact does not exist` | `ExternalExecutionTest.kt` = `844586a5…a6cecf` ✓ |

Restoration was re-verified by running the external build green afterwards:
`EXIT=0`; canary XML `tests="1" failures="0" errors="0"`.

Every claim above has a mutation. No claim in this WU is mutation-free.

---

## Verification executed (fresh)

| Check | Command | Result |
|---|---|---|
| BOM publication | `cd v2 && timeout 900 ./gradlew :publishSdkForExternalPlugin` | EXIT 0 |
| End-to-end gate | `cd v2 && timeout 900 ./gradlew :verifySdkExternalExecution` | EXIT 0; canary XML `1/0/0` fresh |
| External build (restored) | `./v2/gradlew -p examples/sdk-external-execution … check --rerun-tasks` | EXIT 0; XML `1/0/0` |
| Publication/settings fitness | `:pipeline-architecture-tests:test --tests '*PublishedContractBoundaryFitnessTest*' '*Lfc0*' '*P3EPublishedContractMaturity*'` | EXIT 0 (6+2+5 green) |
| External-namespace fitness | `:pipeline-architecture-tests:test --tests '*CoreKnowsNoExternalPluginNamespaceFitnessTest*'` | EXIT 0 (3 green) |
| L4 architecture module | `:pipeline-architecture-tests:test` (full) | EXIT 0; 535 tests, 10 skipped, 0 failures, 0 errors; `BUILD SUCCESSFUL in 3m23s` |

All commands ran with `cd v2 && timeout 900 ./gradlew …` (external commands via `./v2/gradlew -p
examples/sdk-external-execution`), logs under `$JCODE_SCRATCH_DIR`, hermetic (local `sdk-repo`, no
public network needed for SDK resolution).

`PublishedContractBoundaryFitnessTest` remains green: `publishedContractModules` still lists exactly
the four contracts, and the BOM is deliberately not on it.

---

## NO_MEDIDO (and why)

- **Full repository `check` (L5).** Not run. The change adds a BOM module, one root build task, and a
  standalone example build; it touches no engine, no published contract and no existing test wiring.
  The relevant widest run performed is the full `:pipeline-architecture-tests` module (L4), which is
  the surface that reads `publishedContractModules` and `settings.gradle.kts`.
- **Cold cost of `:verifySdkExternalExecution`.** Only the warm run (18 s) was measured; the cold
  figure (fresh publish + `installDist`) is not claimed.
- **Maven (non-Gradle) consumption of the BOM.** Only Gradle `platform(...)` was exercised. The POM
  carries `dependencyManagement`, but Maven's interpretation was not run.
- **Gradle module metadata vs POM as the applied source.** The build resolves; which of the two the
  platform constraints came from was not isolated. M1 proves the constraints are load-bearing.
- **Durable store re-read from the external build.** `ExternalExecutionTest` reads the run's stdout;
  it does not open `db.sqlite` (no SQLite dependency in the external build). Persisted-record
  survival across process exit is covered inside `v2` by `P3DPluginEventInstalledDistributionUatTest`.
- **External harness / PetClinic certification.** Out of scope; belongs to `pipelinek-release-harness`.

---

## What this does NOT prove

- It does **not** prove the plugin or the BOM behaves under the external release harness; it proves
  behaviour on this revision's installed distribution only.
- It does **not** remove the requirement to declare the four coordinates in a consumer. A Gradle
  platform only *pins versions*; it cannot inject a dependency a consumer did not ask for. The BOM is
  fewer repeated versions, not fewer coordinates.
- It does **not** prove byte-for-byte reproducibility of the BOM POM or of `installDist`.
- It does **not** prove that `pipeline-sdk-bom` is the only resolution path, nor that a consumer
  without the BOM cannot resolve the four coordinates (it can, by naming versions directly).
- It does **not** certify a new Step. The subject's Step (`example.uppercase`) is unchanged.

## Reference implementation consulted

```text
Reference implementation consulted: Jenkins `withEnv`/`readYaml` family + pipeline-utility-steps (design only) — none applicable
Behaviour adopted:                  additive build/release surface (SDK BOM + external execution gate); no product behaviour changed
Intentional deviations:             none
Security implications reviewed:     the external test only adds a JAR the admission gate already refuses on a bad manifest (M3)
Tests demonstrating the contract:    examples/sdk-external-execution/src/test/kotlin/.../ExternalExecutionTest.kt
```
