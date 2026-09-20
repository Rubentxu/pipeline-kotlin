# E1.ecosystem-local-first — Spec

Cycle: `cycle/e1-ecosystem-local-first`
Companion to: `proposal.md`

## Capability

A pipeline author can read the canonical Ant/Maven JUnit XML report
into a **typed** Kotlin value (`JUnitReport`) inside a real
`.pipeline.kts`, and can query the artifacts produced by
`core.archiveArtifacts` by **name** to retrieve a stable handle (path
on the durable record) for further steps or external tooling.

This is **NOT** a remote-storage feature, a multi-node distribution
feature, or a new protocol. It is a **typed reader + a derived
index** that closes the local CI/CD loop.

## Scenarios (Given-When-Then, executable)

### S1. Happy-path read

```text
GIVEN a JUnit XML at build/reports/tests/test/TEST-*.xml conforming
      to the canonical Ant/Maven schema (testsuite/testcase/
      failure/error/skipped),
WHEN  the pipeline invokes core.junit("build/reports/tests/test/TEST-*.xml"),
THEN  the handler returns a typed JUnitReport value containing:
        - reportPath:   the resolved on-disk path
        - suites:       list of TestSuiteSummary
            (name, tests, failures, errors, skipped, time)
        - totals:       totals computed by the handler
            (tests, failures, errors, skipped, time)
        - outcome:      PASS  when failures == 0 AND errors == 0
                        FAIL  otherwise
      and emits the events:
        - JunitReadStarted
        - JunitSuiteRead(suiteName, tests, failures, errors, skipped, time)
        - JunitReadCompleted(outcome)
```

### S2. Test-failure read

```text
GIVEN a JUnit XML containing at least one <testcase> with
      <failure> or <error> child elements,
WHEN  core.junit reads the file,
THEN  outcome == FAIL,
      the typed JUnitReport carries the failing case names AND the
      raw message + type attributes from the XML,
      AND the pipeline-level typed failure surface is:
        - CommonExecutionResult.success = false
        - failureKind = StepContractFailure (not INFRASTRUCTURE)
      The handler does NOT raise the failure to abort the pipeline
      unless the pipeline author explicitly checked the outcome; the
      Step itself completes; the typed FAIL value is the side channel.
```

### S3. Missing-file failure

```text
GIVEN the path glob matches no file,
WHEN  core.junit reads,
THEN  the typed result is a rejection:
        - CommonExecutionResult.success = false
        - failureKind = INPUT_INVALID
        - message = "JUnit XML not found: <glob>"
      No partial report is constructed.
      Events emitted: JunitReadStarted, JunitReadFailed(MISSING_FILE).
```

### S4. Malformed XML failure

```text
GIVEN the file exists but does not parse as XML OR has a root
      element other than <testsuites> or <testsuite>,
WHEN  core.junit reads,
THEN  rejection:
        - failureKind = INPUT_INVALID
        - message = "JUnit XML malformed: <reason>"
      Events: JunitReadStarted, JunitReadFailed(MALFORMED).
```

### S5. Unsupported schema variant

```text
GIVEN the file parses but contains elements the cycle does NOT
      handle (e.g. non-canonical extensions, custom listeners),
WHEN  core.junit reads,
THEN  rejection:
        - failureKind = INPUT_INVALID
        - message = "Unsupported JUnit schema variant: <details>"
      Events: JunitReadStarted, JunitReadFailed(UNSUPPORTED_SCHEMA).
```

### S6. Artifact query (E1.2 bridge)

```text
GIVEN the pipeline earlier invoked core.archiveArtifacts(files=["build/libs/app.jar"], name="app"),
WHEN  the pipeline invokes core.artifactQuery(name="app"),
THEN  the handler returns a typed ArtifactHandle:
        - name
        - path         (absolute, durable on disk)
        - sizeBytes
        - sha256
      And emits ArtifactQueried(name, sha256).
      No filesystem scan; the handle is read from a derived index
      populated by core.archiveArtifacts at archive time.
```

### S7. Artifact not found (E1.2 negative)

```text
GIVEN no artifact with the requested name exists in the index,
WHEN  core.artifactQuery(name="missing"),
THEN  rejection: failureKind = INPUT_INVALID,
      message = "Artifact 'missing' not found in index".
      Events: ArtifactQueryFailed(NOT_FOUND).
```

### S8. End-to-end UAT (E1.3)

```text
GIVEN a clean install of the pipelinek distribution,
      a fixture project at examples/e1-ecosystem-demo with a
      Gradle build producing build/reports/tests/test/TEST-*.xml
      and build/libs/demo.jar,
WHEN  the user runs:
        pipelinek run examples/e1-ecosystem-demo/demo.pipeline.kts
THEN  the pipeline:
        1. checks out the demo project (git clone via core.sh)
        2. builds it (./gradlew build via core.sh)
        3. reads the JUnit report (core.junit)
        4. publishes the JAR as an artifact (core.archiveArtifacts)
        5. queries the artifact by name (core.artifactQuery)
        6. verifies the queried sha256 matches the on-disk file's
           sha256
        7. emits JunitReadCompleted(PASS) and ArtifactQueried
      All steps typed; all events emitted; all sha256s recorded.
```

### S9. Failure mode: build fails

```text
GIVEN the demo project has a broken source file,
WHEN  the pipeline runs end-to-end,
THEN  the build step returns failureKind = BUILD_FAILED (typed),
      JUnitReadStarted is NOT emitted (downstream steps are skipped
      or short-circuit per the user's script),
      the artifact query still works for previously-published
      artifacts (no regression).
```

### S10. Failure mode: missing JUnit report

```text
GIVEN the demo project's build/tests is disabled or no report
      is produced,
WHEN  core.junit runs on the expected path,
THEN  scenario S3 (MISSING_FILE) fires.
```

### S11. Resume interaction

```text
GIVEN the pipeline was interrupted between the JUnit read and the
      archive step,
WHEN  the user reruns the same .pipeline.kts with the same --db
      and --control-root,
THEN  the durable spine replays the cached JUnitReport value
      (because core.junit has ReplayPolicy.MEMOIZED), and the
      archive step runs from there.
      No second XML parse; no second JunitReadStarted event.
```

## Non-scenarios (out of scope)

- Reading Surefire reports with non-XML formats (e.g. binary,
  custom). Fail closed (S5).
- Reading JUnit 5 standalone reports (different schema). Fail closed (S5).
- Multi-glob result aggregation in one call (caller invokes N times).
- Streaming/chunked reading of million-line reports (memory-bound
  only; no streaming API).
- Remote artifact retrieval (out of cycle).

## Contract surface (single source of truth: `design.md`)

| Surface | Authority |
|---|---|
| StepKey | `core.junit` |
| Input | `JUnitInput(glob: String)` |
| Output | `JUnitReport` (typed data class) |
| Capabilities | `JUNIT_REPORT_CAPABILITY` |
| ReplayPolicy | `MEMOIZED` |
| RecoveryPolicy | `PIPELINE_DETERMINISTIC` (from descriptor) |
| Effects | `READS_WORKSPACE` |
| StepKey (E1.2) | `core.artifact.query` |
| Input (E1.2) | `ArtifactQueryInput(name: String)` |
| Output (E1.2) | `ArtifactHandle` |
| Capabilities (E1.2) | `ARTIFACT_INDEX_CAPABILITY` |
| ReplayPolicy (E1.2) | `MEMOIZED` |
| Effects (E1.2) | `READS_WORKSPACE` |
