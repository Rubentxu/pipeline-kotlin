# F5.2 Closure Receipt — junit.results OFFICIAL_PLUGIN

**Status**: `F5.2 = CLOSED_GREEN`
**Pipeline shape**: `scm-git.checkout → dir(hello-world) → sh ./make_junit_xml.sh passing → junit.results`

## Commit chain

| Commit | Subject | Purpose |
| --- | --- | --- |
| `fd8fecf9` | F5.2.partial — junit.results OFFICIAL_PLUGIN (composition smoke) | Implementation: module, parser, codecs, StepDefinition, contributor, DSL façade, ServiceLoader SPI, ServiceLoader wiring. Composition smoke = PASS. |
| `7e0e5953` | F5.2.fix — junit.results workspaceRoot fallback | Real E2E = PASS, defects closed. |
| `83e2d0f9` | F5.2.doc — close workspaceRoot defect with the real root cause | Defect doc re-anchored to the correct root cause (workspaceRoot resolution, not cwd propagation). |

## Module added

`v2/pipeline-step-sdk/junit` — OFFICIAL_PLUGIN providing `junit.results`.

- `JUnitReportParser.kt` — SAX parser, XXE-hardened (`disallow-doctype-decl`,
  external-general-entities=false, external-parameter-entities=false,
  nonvalidating/load-external-dtd=false, FEATURE_SECURE_PROCESSING,
  rejecting `EntityResolver` on the reader directly). Throws
  `JUnitReportParseException` for malformed/empty/non-`<testsuite>` XML.
- `JUnitReportCodec.kt` — `JUnitResultsInputCodec` + `JUnitReportSummaryCodec`,
  runtime kotlinx-serialization JSON API (no plugin), with `schema()` on
  the input codec.
- `JUnitReportSummary.kt` — typed `JUnitReportSummary(tests, failures,
  errors, skipped, durationSeconds, reportPath)` plus derived `successful`,
  `failed`, `isClean`.
- `JUnitResultsStepDefinition.kt` — `Effect.READ_ONLY`, empty capability
  set, fail-closed USER failures for missing file / directory-not-file /
  empty / oversized; `failOnFailure=true` aborts on failures+errors.
- `JUnitResultsKey.kt` — `JUnitResultsKey.VALUE = PluginStepId("junit.results")`.
- `JUnitStepDefinitionContributor.kt` — `class` with companion (ServiceLoader
  new-able), validator-cross-checked manifest, families `{TESTING,
  REPORTING}`.
- `JUnitDsl.kt` — `StageScope.junitResults(reportPath, workspaceRoot,
  failOnFailure, maxReportBytes)` lowers to `registryStep`.
- `src/main/resources/META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`
  — ServiceLoader descriptor with FQN of the contributor.

Wiring:
- `v2/settings.gradle.kts` — adds `:pipeline-step-sdk:junit`.
- `v2/pipeline-application/build.gradle.kts` — `implementation(...)` AND
  `testImplementation(...)` so the jar lands in the installed distribution
  AND is available to the application test classpath.

## Certified artefact

```
publisher          pipeline-kotlin
namespace          pipeline.junit
version            0.1.0
digest             sha256:5695dd53998fc88ad50869e1d7785982d32646e78888dd40652fdd1525adfc62
jar sha256         25ab62c7b01186057ba41b72f6aee31a736735f7abc8b77b9524a1409522aa71
installed at       v2/pipeline-application/build/install/pipelinek/lib/junit-0.1.0.jar
```

Both hashes were produced by `sha256sum` on the file at the path above
(this receipt).

## Test evidence (this run)

| Suite | tests | skipped | failures | errors |
| --- | --- | --- | --- | --- |
| `JUnitReportParserTest` | 8 | 0 | 0 | 0 |
| `F5_1_ScmGitNegativePathsTest` | 11 | 0 | 0 | 0 |
| `F5_1_ScmGitProviderProvenanceTest` | 4 | 0 | 0 | 0 |
| `F5_1_ScmGitStepContractTest` | 10 | 0 | 0 | 0 |
| `F5_2_JUnitStepContractTest` | 22 | 0 | 0 | 0 |
| **Total** | **55** | **0** | **0** | **0** |

XML canaries at:
- `v2/pipeline-step-sdk/junit/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitReportParserTest.xml`
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.F5_2_JUnitStepContractTest.xml`
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.F5_1_ScmGitNegativePathsTest.xml`
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.F5_1_ScmGitProviderProvenanceTest.xml`
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.F5_1_ScmGitStepContractTest.xml`

## End-to-end evidence (this run)

Command (run live, exit 0):
```
java -classpath $PIPELINEK_CP dev.rubentxu.pipeline.v2.application.MainKt run \
    --workspace /tmp/pk-uat-f5-2/ws_receipt \
    --control-root /tmp/pk-uat-f5-2/control_receipt \
    --db /tmp/pk-uat-f5-2/db_receipt \
    --plugin-jar /home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-step-sdk/scm-git/build/libs/scm-git-0.36.0.jar \
    --plugin-jar /home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-step-sdk/junit/build/libs/junit-0.1.0.jar \
    scenario_e2e.kts
```

Pipeline final line: `Pipeline finished with SUCCESS`.

Event sequence (19 events, abbreviated):
```
CompilationStarted → CompilationFinished → RunStarted
→ StageStarted[checkout] → StepStarted[scm-git] → StepFinished[scm-git] → StageFinished success
→ StageStarted[test] → DirEntered → StepStarted[sh] → EchoOutputCaptured → StepFinished[sh] → DirExited → StageFinished success
→ StageStarted[report] → StepStarted[junit] → StepFinished[junit] → StageFinished success
→ RunFinished success
```

The XML at `hello-world/build/test-results/test/TEST-com.example.SampleTest.xml`
is generated by the fixture script `make_junit_xml.sh passing` invoked from
inside the `dir("hello-world")` block. It was created during the same run;
no pre-existing developer-side file was read.

## Negative paths evidence (this run)

| Case | Setup | Expected | Observed |
| --- | --- | --- | --- |
| `neg1_missing` | `reportPath = "does/not/exist/..."` | `StepFailed` + `RunFinished failure` | ✅ exit=1, `report file not found at /tmp/pk-uat-f5-2/ws_neg1/does/not/...` |
| `neg2_malformed` | `make_junit_xml.sh malformed` (writes broken XML) | `StepFailed` + `RunFinished failure` | ✅ exit=1, `malformed XML at /tmp/pk-uat-f5-2/ws_neg2/hello-world/build/...` |
| `neg3_failing_strict` | `make_junit_xml.sh failing` + `failOnFailure=true` | `StepFailed` + `RunFinished failure` | ✅ exit=1, `1 failed test(s) (1 failures + 0 errors out of 3)` |
| `neg4_failing_lenient` | `make_junit_xml.sh failing` + `failOnFailure=false` | `RunFinished success` (typed summary reports the failure) | ✅ exit=0, `RunFinished outcome=success` |

Failure classification is `FailureKind.ENGINE` in all four (see follow-up
B below — this is a deliberate pre-existing boundary decision, not a
F5.2 regression).

## Fixture (reproducible)

`/tmp/pk-uat-f5-2-fixture/`

```
repo/
├── .tool-versions                    # java temurin-24.0.2+12
├── build.gradle.kts                  # Kotlin JVM (unused by the E2E; the
│                                       fixture script produces XML
│                                       directly without invoking gradle)
├── gradlew, gradlew.bat              # gradle wrapper (unused by the E2E)
├── gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
├── make_junit_xml.sh                 # writes a deterministic JUnit XML
│                                       at $PWD/build/test-results/test/
│                                       when invoked as `passing`,
│                                       `failing`, or `malformed`
├── settings.gradle.kts
└── src/                              # Kotlin test sources (unused by the E2E)
git/fixture.git/                      # `git clone --bare` mirror
```

The bare repo is fetched into the workspace at
`/tmp/pk-uat-f5-2-fixture/git/fixture.git` and is referenced by URL
`file:///tmp/pk-uat-f5-2-fixture/git/fixture.git`. The E2E does not
depend on any network repository or external credential store.

## Correction of the prior hypothesis

The earlier receipt (defect doc originally named
`DEFECT_CWD_NOT_PROPAGATED_TO_SH.md`) hypothesised that
`dir("hello-world") { sh(...) }` failed to propagate `cwd` to the nested
`core.sh` handler via `CanonicalDurableRunCoordinator`. The
characterisation in this cycle disproved that hypothesis:

- `sh("pwd")` inside `dir("hello-world") { sh("pwd") }` returns
  `/tmp/pk-uat-f5-2/wsN/hello-world`, NOT the control dir. The
  coordinator IS propagating the cwd through `ContextOverlay.Cwd` →
  `childShOptions.workingDirectory` → `ShExecution.invokeShell` →
  `DurableShellExecutor.launch` → `pb.directory`.
- The actual failure cause was the `junit.results` step receiving
  `workspaceRoot = "."` from the script author and resolving it against
  the process cwd (the binary's launcher cwd), which is the workspace
  only when `--workspace` happens to be the cwd. A missing
  `pipeline.workspace.root` lookup in the handler meant the relative
  report path was searched in the wrong directory.

`CanonicalDurableRunCoordinator` was NOT modified. The fix lives
entirely in `JUnitResultsStepDefinition.handler` (commit `7e0e5953`),
which is the right seam: a plugin handler is the only place where
input decoding, capability lookup and filesystem resolution are colocated
for that plugin's contract, and the runtime context already publishes
the workspace as a system property for that purpose.

The defect doc was renamed to `DEFECT_JUNIT_WORKSPACE_ROOT.md` (commit
`83e2d0f9`) to reflect the corrected cause.

## Deliberate out-of-scope (follow-ups filed)

### Follow-up A — Workspace contextual seam

`pipeline.workspace.root` (system property) is a UAT-pragmatic seam
introduced by `Main.kt:583`. The long-term goal is to derive the
workspace from the effective step context (immutable value passed
through the coordinator), without shared global state. Today's fix is
the minimum needed to keep the public DSL contract ergonomic; a
follow-up WU will:

- Audit every consumer of `pipeline.workspace.root`.
- Define a contextual `WorkspaceProvider` capability surfaced through
  `SHELL_OPERATIONS_CAPABILITY`-style admission.
- Add negative tests:
  - absolute `workspaceRoot` that does NOT exist → fallback
    documented and tested (already covered by `F5_2_JUnitStepContractTest`
    case `handler falls back to pipeline workspace when workspaceRoot
    points at a non-existent directory`).
  - Two parallel runs with different `--workspace` paths do NOT leak
    cwd between them (the property is per-process, but each pipelinek
    invocation has its own JVM; the test must prove two concurrent
    `pipelinek run --workspace /tmp/A ...` and `pipelinek run
    --workspace /tmp/B ...` invocations each resolve their relative
    paths against their own workspace).

No general refactor is in scope today.

### Follow-up B — Typed failure propagation

`RegistryExecutionBoundary` (commit `7e0e5953` is NOT in that file;
this is pre-existing infrastructure) converts every
`PluginStepException(kind=USER)` raised by a plugin handler into a
`StepOutcome.Failure(failureKind=ENGINE)`. The user-facing message is
preserved, but the `FailureKind` is dropped.

This is observable for both `core.sh` (LB-02 / certified reference)
and `junit.results` (F5.2). The classification decision influences
retry semantics, audit consumers, and the event envelope.

A dedicated WU will:
- Characterise the boundary's current transformation matrix
  (`kind_in → kind_out`, with `message`, `data`, `exception_type`
  fields).
- Identify which transformation corresponds to the existing contract
  and which are unintended losses.
- Add a characterisation suite that runs both `core.sh` and
  `junit.results` (and any other plugin handler that already raises
  `PluginStepException`) to lock the current behaviour before changing
  it.
- Implement the minimum generic change (if any is justified) so USER
  failures declared by a plugin survive into `StepOutcome` and the
  event envelope, reserving `ENGINE` for real engine/adapter faults.

`core.sh` was the reference effectful step; `junit.results` is the
second to surface this. The WU will not introduce JUnit-specific
exceptions and will not change all error semantics at once.

## Constraints honoured

- No modification to `CanonicalDurableRunCoordinator`.
- No modification to `core.sh`, `core.echo`, or any other previously
  certified Step.
- No change to the C10 backwards-compat contract of legacy contributors.
- SDKMAN remains `WAITING_EXTERNAL`.
- No `--rerun-tasks` invoked during the receipt's evidence capture;
  the existing Gradle build cache and `sdk-repo` are untouched.
