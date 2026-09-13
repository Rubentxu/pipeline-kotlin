# Lane R — CLEAN BUILD / EXTERNAL PLUGIN REPRODUCIBILITY

> code-under-test: `370a2350`
> branch: `cycle/build-example-plugin-reproducibility`
> base: `2b391e76` (= `origin/main` after the G8 certification PR #45 landed)
> evidence: PR #46 — this file is carried by the evidence commit, whose SHA the PR
>           body records; the two identities are deliberately not the same commit

## 1. Why this lane exists

G8 recorded, but deliberately did not fix, two build defects. Investigation for
this lane found that they are three forms of one defect:

```text
the build only worked because every existing worktree had inherited
state that git does not carry
```

| # | defect | consequence |
| --- | --- | --- |
| A | `v2/pipeline-application` pulled a pre-built jar from an independent Gradle build (`examples/example-uppercase-plugin`) with **no producing task anywhere in v2** | `:pipeline-application` test sources did not compile from a clean checkout |
| B | `examples/example-uppercase-plugin/libs/{pipeline-domain,pipeline-scripting-api}-0.1.0-SNAPSHOT.jar` were **committed SDK snapshots** | the plugin could drift silently from the live SDK; nothing would notice |
| C | **ten** test sites hardcoded `/var/home/<user>/Proyectos/kotlin/pipeline-kotlin` across five files in two modules | the suite was pinned to one machine and to one worktree |

Defect C is the widest: it fails on any other machine, and on this machine it made
tests read a *different checkout's* corpus rather than their own. The correct idiom
already existed inline in two tests (`A4_2`, `A4_8`); it had simply not been applied
here and was not shared.

### Reproduction of A (base checkout, before any change)

```text
./v2/gradlew -p v2 :pipeline-application:compileTestKotlin
e: .../UppercaseStepContractSuiteTest.kt:239:28 Unresolved reference 'UppercaseCodec'.
e: .../UppercaseStepContractSuiteTest.kt:250:19 Unresolved reference 'UppercaseStepDefinition'.
BUILD FAILED in 14s
```

## 2. Design

`example-uppercase-plugin` stays an **independent Gradle build** with its own
`settings.gradle.kts`. That independence is the property the plugin certifies — that
a third party can compile against the public SDK — so it is deliberately *not* made a
subproject of `v2`.

The SDK is now consumed as ordinary module coordinates resolved from a build-local
Maven repository that the v2 build produces **from this revision**:

```text
:pipeline-domain:jar  +  :pipeline-scripting-api:jar
        |
        v
:pipeline-domain:publishSdkPublicationToSdkRepository
:pipeline-scripting-api:publishSdkPublicationToSdkRepository
        |
        v
:publishSdkForExternalPlugin
        |
        v
:buildExamplePlugin            (the independent plugin build)
        |
        v
example-uppercase-plugin-0.1.0.jar
        |
        v
:pipeline-application test classpath  ->  compileTestKotlin / test
```

Note on Gradle semantics: `includeBuild(...) { dependencySubstitution { ... } }`
substitutes *externals* with projects of the **included** build, so it cannot make an
included plugin consume the *including* build's projects. A plain
`includeBuild` would have left the SDK unresolved. The published local repository is
the mechanism that keeps the two builds independent while giving the plugin an SDK
produced from the same source.

The plugin re-resolves changing modules with a zero cache TTL:

```kotlin
configurations.all { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }
```

Gradle's default SNAPSHOT TTL is 24h. Without this, the plugin could compile against
an SDK that no longer matches the repository — a silent stale-artifact reuse, which is
exactly what R7 exists to exclude.

Defect C is fixed by exposing the repository root to tests as the `pipeline.repoRoot`
system property, resolved by a per-module `TestProjectRoot` that prefers the property
and falls back to walking up from the working directory (so the suite also works from
an IDE). `pipeline-testkit` cannot host the helper: it depends on
`pipeline-application`, so `pipeline-application` cannot depend on it.

## 3. Acceptance criteria

### R1 — clean checkout compiles without manual steps

Clean-room worktree at `370a2350`, no `build/` anywhere, no pre-built jar, no `libs/`,
`--no-build-cache`:

```text
: pipeline-domain:publishSdkPublicationToSdkRepository
: publishSdkForExternalPlugin
: buildExamplePlugin
: pipeline-application:compileTestKotlin
BUILD SUCCESSFUL
R1_EXIT=0
```

### R2 — `:pipeline-application:test` builds the plugin automatically

Pristine worktree at `370a2350` (no `build/`, no SDK repo, no plugin jar), **one**
command:

```text
./v2/gradlew -p v2 --no-build-cache :pipeline-application:test \
    --tests 'UppercaseStepContractSuiteTest'

> Task :pipeline-domain:jar
> Task :pipeline-scripting-api:jar
> Task :publishSdkForExternalPlugin
> Task :buildExamplePlugin
> Task :pipeline-application:compileTestKotlin
> Task :pipeline-application:test
BUILD SUCCESSFUL in 32s
49 actionable tasks: 49 executed
```

`49 executed` with the build cache disabled is the evidence that this was a genuine
full build, not a cache replay.

```text
UppercaseStepContractSuiteTest  tests=14 failures=0 errors=0 skipped=0
XML sha256 a8b256685c8b1363006d577753bc49a820842e1537e155fa657943a498a430fe
```

### R3 — the plugin remains a real external frontier

```text
examples/example-uppercase-plugin/build.gradle.kts
  compileOnly("dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion")
  compileOnly("dev.rubentxu.pipeline.v2:pipeline-scripting-api:$sdkVersion")
  compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

No project(":...") dependency. No internal application/runtime import.
```

Its imports remain the public contract surface only: `StepDefinitionContributor`,
`StepContract`, `StepCodec`, `StepDefinition`, `StepHandler`, `StepDescriptor`,
`EncodedStepValue`, `Effect`, `ReplayPolicy`, `ExecutionLocation`, `PluginStepId`,
`StageScope`.

### R4 — the plugin compiles against SDK produced from the same SHA

The SDK arrives as module coordinates from a repository produced by this build. The
committed snapshots are gone:

```text
D  examples/example-uppercase-plugin/libs/pipeline-domain-0.1.0-SNAPSHOT.jar
D  examples/example-uppercase-plugin/libs/pipeline-scripting-api-0.1.0-SNAPSHOT.jar
```

### R5 — explicit dependency graph

Shown in §2 and observed in the R2 task list above.

### R6 — clean-room

Three separate worktrees were used, each created with `git worktree add --detach` at
`370a2350` and verified empty before the run:

| worktree | purpose | before | result |
| --- | --- | --- | --- |
| `pipeline-laneR-cleanroom` | R1 | no `v2/build`, no plugin `build/`, no `libs/` | PASS |
| `pipeline-laneR-r2` | R2, single command | no `build/`, no SDK repo, no plugin jar | 14/0/0, 49/49 executed |
| `pipeline-laneR-baseline` | base-vs-head | at `2b391e76` | see §4 |

### R7 — drift negative controls

**(a) SDK repository absent** — the plugin must fail closed, not fall back:

```text
./v2/gradlew -p examples/example-uppercase-plugin -PsdkRepo=<empty dir> jar
> Could not resolve all files for configuration ':compileClasspath'.
   > Could not find dev.rubentxu.pipeline.v2:pipeline-domain:0.1.0-SNAPSHOT.
   > Could not find dev.rubentxu.pipeline.v2:pipeline-scripting-api:0.1.0-SNAPSHOT.
BUILD FAILED   exit=1
```

There is no committed jar to fall back to, which is the point.

**(b) incompatible SDK, cache warm** — the decisive control. State was warmed with a
successful build, then the SDK API was broken (`PluginStepId` renamed to
`PluginStepIdX` across `pipeline-domain` only, 13 files) and the SDK republished.
The plugin build was re-run **without cleaning anything**:

```text
e: .../UppercaseStepDefinition.kt:4:40 Unresolved reference 'PluginStepId'.
e: .../UppercaseStepDefinition.kt:53:15 Unresolved reference 'PluginStepId'.
BUILD FAILED   exit=1
```

A stale cached jar would have let this succeed. Reverting the rename and republishing
restores a green build, and `pipeline-domain` source returns byte-identical to `HEAD`
(only its `build.gradle.kts` differs, which is this lane's change).

## 4. No new regressions

Base = `2b391e76`, head = `370a2350`, same argv, JUnit XML as the result truth:

```text
suite                            base        head
UatLocal005BannedImportsTest      2/0/0       2/0/0
UatLocal005ClassTimeout…Test      9/0/0       9/0/0
UatLocal005CorpusUntouchedTest    2/1/0       2/1/0
UatLocal008CredentialsTest       27/2/0      27/2/0
EventSchemaNoMapStringString…     2/0/0       2/0/0
UatComp003Credentials…Test        2/0/0       2/0/0
WithCredentialsCompile…Test       6/4/0       6/4/0
```

The two columns are identical. The failures are pre-existing and are **not** caused
by this lane. They are also identical to what defect C revealed: at base these tests
read the corpus of a *different* checkout, so the head column is the first time these
assertions have been evaluated against their own worktree.

`UatLocal005CorpusUntouchedTest` asserts 19 corpus fixtures while the corpus holds 20;
`UatLocal005CorpusUntouchedTest`'s own comment already names the addition
(`S2-A6/G3R added 20-pwd-tmp`) without updating the count.

### 4b. Whole-module evidence for the modules this lane touches

The seven classes above were chosen because this lane edits them. The whole-module runs
below were executed at `ed4a6d6b`, which is `370a2350` plus this receipt's first
docs-only commit, so the code under test is byte-identical to `370a2350`. This lane also
changes a **production** file (`ScriptDefinition` in `pipeline-scripting-api`) and two
build files, so the affected module suites were run whole rather than by class. Raw
head XML for all three modules is archived as a single tarball
(`raw/xml/module-suites/head-module-suites-xml.tar.gz`, 82 files); the verifier opens
it and re-derives these totals.

```text
module                        tests  skipped  failed  errors   failing class
pipeline-scripting-api           39        0       1       0   PipelineDslSealedHierarchyTest
pipeline-events                 123        0       0       0   (none)
pipeline-architecture-tests     241        0       1       0   Lfc0GlobalStateFitnessTest
```

Both failures are pre-existing. The two failing classes were executed at base as well,
and the archived base XML
(`raw/xml/module-suites/base-module-suites/`) carry the same counts:

```text
class                                    base       head
PipelineDslSealedHierarchyTest           1/1/0      1/1/0     (run whole: 39/1)
Lfc0GlobalStateFitnessTest               2/1/0      2/1/0     (run whole: 241/1)
```

The argument closes without a whole-suite base run: the head failure set of a module is
compared against base, and a module whose *only* failures are base-identical has no new
regression. A class that failed at base and passes at head would be an improvement, not
a regression, and is not claimed here either way.

Neither failure is reachable from this lane:

- `PipelineDslSealedHierarchyTest` asserts the `StepSpec` sealed hierarchy has exactly
  28 variants and finds 29, naming `ArchiveArtifacts`. That step landed in E1. The class
  is untouched by this lane and the message is byte-identical at base and head.
- `Lfc0GlobalStateFitnessTest` reports `Capabilities.kt:76
  System.getProperty("user.dir")`. `Capabilities.kt` is **not modified by this lane**
  (`git diff 2b391e76 -- …/application/Capabilities.kt` is empty), the token is at the
  same line in base, and line 76 is KDoc prose describing what handlers must *not* do.
  The fitness scanner matches the comment. That is a defect in the scanner, not in the
  code it reports, and it is out of scope here.

## 5. Out of scope

- The two pre-existing failures above are **not** fixed here. They are corpus
  accounting, unrelated to build reproducibility, and fixing them would change
  assertions in tests this lane did not otherwise touch.
- The non-deterministic `installDist` digest recorded by G8 (§4 of that receipt) is a
  **provenance / reproducible-builds** concern, not a clean-build concern. G7's
  evidence remains valid because it retains raw outputs.
- `UatComp003CredentialsDefaultImportTest` and `WithCredentialsCompileIntegrationTest`
  were changed only to stop hardcoding an absolute path. Their pre-existing failures
  (4 in the latter) are untouched.

## 6. Reproduce

```bash
# R1 / R2 from a clean room
git worktree add --detach /tmp/lr 370a2350 && cd /tmp/lr
./v2/gradlew -p v2 --no-build-cache :pipeline-application:compileTestKotlin
./v2/gradlew -p v2 --no-build-cache :pipeline-application:test \
    --tests 'UppercaseStepContractSuiteTest'

# R7a
mkdir -p /tmp/empty-sdk
./v2/gradlew -p examples/example-uppercase-plugin -PsdkRepo=/tmp/empty-sdk jar

# mechanical assertions and citation resolution
python3 docs/v2/07-uat/evidence/lane-r/verify-lane-r-receipt.py
```

## 7. What this lane does not claim

This lane does not claim the repository builds on a machine with a cold Gradle
dependency cache (that requires network access and is not what R6 tests). It claims
that no git-carried state — committed jars, inherited build outputs, or absolute
paths into a specific checkout — is required for `:pipeline-application` to compile
and test from a clean checkout.

## 8. Verifier controls

`verify-lane-r-receipt.py` asserts properties, not string presence, so it is only worth
something if mutating the thing it claims to protect makes it fail. Ten controls were
run; each one was expected to fail the verifier for a *specific* reason, the reason was
read, and the mutated artefact was then restored and re-hashed before the next control.
The final run is green and the restored files are byte-identical to their originals.

Against the repository assertions and the citation resolver:

```text
1   reintroducing an absolute path into a test source
2   restoring a committed SDK snapshot under the plugin's libs directory
3   relaxing the SNAPSHOT cache invalidation in the plugin build
4   dropping the producer edge from the consuming module's test compile
5   advancing one hex digit of a cited sha256
6   mutating an archived XML so it no longer matches its base counterpart
```

Against the module-suite section:

```text
7   corrupting a module total inside the archived tarball
8   editing a base XML so a failing class looks green
9   rewriting the assertion text of a base failure while keeping its count
10  removing a class from the archived tarball
```

Control 9 carries the most weight. Controls 7, 8 and 10 can all be caught by comparing
counts, so a verifier that only compares counts still looks healthy. Control 9 mutates
nothing but prose inside the failure message, keeps `tests/failures/errors` identical,
and still has to be caught: the receipt says the failure is *the same failure* at base,
not merely the same number of them. The comparison normalises the embedded checkout
path, otherwise the head and base messages would differ for a reason that has nothing
to do with the code.

Controls 7 and 10 were run a second time. The first attempt rebuilt the tarball with a
different member-name prefix, which made the verifier fail for the wrong reason — it
could no longer attribute members to a module. A control that discriminates for an
unintended reason proves nothing about the intended one, so both were re-run preserving
member names and only then accepted.
