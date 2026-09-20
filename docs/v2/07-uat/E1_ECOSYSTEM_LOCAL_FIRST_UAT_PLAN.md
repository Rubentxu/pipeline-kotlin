# E1.ecosystem-local-first — UAT Plan

Cycle: `cycle/e1-ecosystem-local-first`
Companion: `proposal.md`, `spec.md`, `design.md`, `tasks.md`

Self-contained UAT plan. Each scenario maps 1:1 to a `spec.md`
scenario. The UAT dashboard renders this file via the project's
`sddk uat` tool; the `id` is the dashboard scenario anchor.

## Suite: e1-ecosystem-local-first

### S1 — happy_junit_read

```yaml
id: e1.S1.happy_junit_read
title: "core.junit reads canonical JUnit XML into typed JunitReport"
given:
  - "A pipeline.kts with a stage that calls core.junit on a real Gradle-produced JUnit XML"
  - "The XML conforms to the Ant/Maven schema (testsuite/testcase/failure/error/skipped)"
when:
  - "The pipeline runs end-to-end against a clean install"
then:
  - "core.junit returns a typed JunitReport with non-null suites and totals"
  - "totals.tests > 0 AND totals.failures == 0 AND totals.errors == 0"
  - "outcome == Passed"
  - "Events emitted: JunitReadStarted, JunitSuiteRead (>= 1), JunitReadCompleted"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S1-happy.txt
```

### S2 — junit_test_failures_surface

```yaml
id: e1.S2.junit_test_failures_surface
title: "core.junit surfaces failing cases without aborting the pipeline"
given:
  - "A pipeline.kts reading a JUnit XML with at least one <failure> or <error>"
when:
  - "The pipeline runs"
then:
  - "outcome == Failed"
  - "failingCases is non-empty AND contains the failing case names + messages"
  - "CommonExecutionResult.success is TRUE for the Step itself (the typed FAIL is the side channel)"
  - "The pipeline does not abort unless the author explicitly checks the outcome"
  - "Events emitted: JunitReadStarted, JunitSuiteRead, JunitReadCompleted(FAIL)"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S2-fail.txt
```

### S3 — junit_missing_file

```yaml
id: e1.S3.junit_missing_file
title: "core.junit fails closed with INPUT_INVALID on missing file"
given:
  - "A glob that matches no file on disk"
when:
  - "core.junit runs"
then:
  - "CommonExecutionResult.success == false"
  - "failureKind == INPUT_INVALID"
  - "message contains 'JUnit XML not found'"
  - "Events emitted: JunitReadStarted, JunitReadFailed(MISSING_FILE)"
  - "No partial report is constructed"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S3-missing.txt
```

### S4 — junit_malformed_xml

```yaml
id: e1.S4.junit_malformed_xml
title: "core.junit fails closed with INPUT_INVALID on malformed XML"
given:
  - "A file at the glob path that is not well-formed XML OR has an unexpected root element"
when:
  - "core.junit runs"
then:
  - "CommonExecutionResult.success == false"
  - "failureKind == INPUT_INVALID"
  - "message contains 'malformed'"
  - "Events emitted: JunitReadStarted, JunitReadFailed(MALFORMED)"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S4-malformed.txt
```

### S5 — junit_unsupported_schema

```yaml
id: e1.S5.junit_unsupported_schema
title: "core.junit fails closed on non-canonical schema variants"
given:
  - "A well-formed XML with non-canonical extensions (e.g. JUnit 5 standalone schema elements)"
when:
  - "core.junit runs"
then:
  - "failureKind == INPUT_INVALID"
  - "message contains 'Unsupported JUnit schema variant'"
  - "Events emitted: JunitReadStarted, JunitReadFailed(UNSUPPORTED_SCHEMA)"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S5-unsupported.txt
```

### S6 — artifact_query_happy

```yaml
id: e1.S6.artifact_query_happy
title: "core.artifactQuery returns a typed ArtifactHandle for a published artifact"
given:
  - "A pipeline.kts that earlier ran core.archiveArtifacts(name='app', files=['build/libs/app.jar'])"
  - "The artifact was successfully archived"
when:
  - "core.artifactQuery(name='app') runs"
then:
  - "Returns ArtifactHandle { name='app', path=<absolute>, sizeBytes>0, sha256=<64 hex chars> }"
  - "Events emitted: ArtifactQueried(name='app', sha256)"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S6-query-happy.txt
```

### S7 — artifact_query_not_found

```yaml
id: e1.S7.artifact_query_not_found
title: "core.artifactQuery fails closed when no artifact with the given name exists"
given:
  - "No artifact with name='missing' was ever archived in this run"
when:
  - "core.artifactQuery(name='missing') runs"
then:
  - "failureKind == INPUT_INVALID"
  - "message contains 'not found in index'"
  - "Events emitted: ArtifactQueryFailed(NOT_FOUND)"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S7-query-missing.txt
```

### S8 — e2e_demo_happy

```yaml
id: e1.S8.e2e_demo_happy
title: "End-to-end demo: checkout → build → tests → junit read → archive → query"
given:
  - "examples/e1-ecosystem-demo is a small Gradle project producing build/libs/demo.jar and build/reports/tests/test/TEST-*.xml"
  - "examples/e1-ecosystem-demo/demo.pipeline.kts exercises the full chain"
  - "A freshly-built distribution of pipelinek"
when:
  - "The operator runs: pipelinek run examples/e1-ecosystem-demo/demo.pipeline.kts"
then:
  - "All 7 sub-steps complete in order"
  - "core.junit returns Passed outcome with totals.tests > 0"
  - "core.artifactQuery returns a handle whose sha256 matches the on-disk JAR sha256"
  - "Events emitted in the canonical order: JunitReadStarted -> JunitSuiteRead (>=1) -> JunitReadCompleted -> ArtifactQueried"
  - "All sha256s recorded in evidence file"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S8-e2e-happy.txt
```

### S9 — e2e_build_fail

```yaml
id: e1.S9.e2e_build_fail
title: "End-to-end demo with build failure: downstream steps short-circuit cleanly"
given:
  - "examples/e1-ecosystem-demo with a broken source file"
when:
  - "The demo pipeline runs"
then:
  - "The build step returns CommonExecutionResult with failureKind = BUILD_FAILED (typed, not INFRASTRUCTURE)"
  - "JunitReadStarted is NOT emitted"
  - "Previously-published artifacts (from a prior run) are still queryable via core.artifactQuery"
  - "No silent swallowing; the failure is surfaced with a clear message"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S9-build-fail.txt
```

### S10 — e2e_missing_report

```yaml
id: e1.S10.e2e_missing_report
title: "End-to-end demo with no JUnit report produced"
given:
  - "examples/e1-ecosystem-demo with tests disabled in build.gradle"
when:
  - "The demo pipeline runs"
then:
  - "core.junit receives the expected glob path; the file does not exist"
  - "S3 fires: INPUT_INVALID with 'JUnit XML not found'"
  - "Events: JunitReadStarted, JunitReadFailed(MISSING_FILE)"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S10-no-report.txt
```

### S11 — e2e_resume

```yaml
id: e1.S11.e2e_resume
title: "Resume after interruption: cached JunitReport is reused, no second parse"
given:
  - "A demo pipeline that was interrupted between the JUnit read and the archive step"
when:
  - "The operator reruns the same demo with the same --db and --control-root"
then:
  - "core.junit does NOT emit a second JunitReadStarted event"
  - "The cached JunitReport is replayed from the journal (ReplayPolicy.MEMOIZED)"
  - "The archive step runs from the cached report value"
  - "sha256 evidence: a single JunitReadStarted event in the merged event log"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S11-resume.txt
```

## Suite summary

```text
e1-ecosystem-local-first:
  scenarios: 11
  positive:  4 (S1, S6, S8, S11)
  failure:   7 (S2, S3, S4, S5, S7, S9, S10)
  coverage:  core.junit (S1-S5), core.artifact.query (S6-S7), E1.3 end-to-end (S8-S11)
```

## Operator review gate

The cycle closes only when ALL 11 scenarios have a green evidence
file under `docs/v2/07-uat/evidence/e1-ecosystem-local-first/`.
Any red on S8..S11 (E1.3 end-to-end) blocks the cycle close.
Red on S1..S7 (unit-level) is a defect, fix in the cycle, re-run.
