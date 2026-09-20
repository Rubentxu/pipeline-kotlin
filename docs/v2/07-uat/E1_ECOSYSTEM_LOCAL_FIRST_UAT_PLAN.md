# E1.ecosystem-local-first — UAT Plan

Cycle: `cycle/e1-ecosystem-local-first`
Companion: `proposal.md`, `spec.md`, `design.md`, `tasks.md`

Self-contained UAT plan. Each scenario maps 1:1 to a `spec.md`
scenario. The UAT dashboard renders this file via the project's
`sddk uat` tool; the `id` is the dashboard scenario anchor.

## Suite: e1-ecosystem-local-first

### S1 — happy_junit_results

```yaml
id: e1.S1.happy_junit_results
title: "junit.results (OFFICIAL_PLUGIN, F5.2) reads canonical JUnit XML into typed JUnitReportSummary"
given:
  - "A pipeline.kts with a stage that calls junitResults() on a real Gradle-produced JUnit XML"
  - "The XML conforms to the Ant/Maven schema (testsuite/testcase/failure/error/skipped)"
when:
  - "The pipeline runs end-to-end against a clean install"
then:
  - "junit.results returns a typed JUnitReportSummary with tests/failures/errors/skipped/durationSeconds/reportPath"
  - "tests > 0 AND failures == 0 AND errors == 0"
  - "isClean == true"
  - "CommonExecutionResult.success == true"
  - "Events emitted per F5.2 contract test"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S1-happy.txt
uath_under_test: "v2/pipeline-step-sdk/junit (F5.2 CERTIFIED)"
uath_owner: "F5.2 closure — NOT modified by this cycle"
```

### S2 — junit_results_test_failures

```yaml
id: e1.S2.junit_results_test_failures
title: "junit.results surfaces failing cases; with failOnFailure=false the pipeline does not abort"
given:
  - "A pipeline.kts reading a JUnit XML with at least one <failure> or <error>"
  - "failOnFailure=false (informational mode)"
when:
  - "The pipeline runs"
then:
  - "JUnitReportSummary.failures + errors > 0"
  - "JUnitResultsOutput.outcome carries the typed failure classification"
  - "CommonExecutionResult.success == true (failOnFailure=false)"
  - "The pipeline does not abort"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S2-fail.txt
uath_under_test: "v2/pipeline-step-sdk/junit (F5.2 CERTIFIED)"
uath_owner: "F5.2 closure — NOT modified by this cycle"
```

### S3 — junit_results_missing_file

```yaml
id: e1.S3.junit_results_missing_file
title: "junit.results fails closed with USER kind on missing file"
given:
  - "A report path that does not exist on disk"
when:
  - "junitResults() runs"
then:
  - "CommonExecutionResult.success == false"
  - "failureKind == USER (typed, not INFRASTRUCTURE)"
  - "message contains 'not found' or 'missing'"
  - "No partial summary is constructed"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S3-missing.txt
uath_under_test: "v2/pipeline-step-sdk/junit (F5.2 CERTIFIED)"
uath_owner: "F5.2 closure — NOT modified by this cycle"
```

### S4 — junit_results_malformed_xml

```yaml
id: e1.S4.junit_results_malformed_xml
title: "junit.results fails closed with USER kind on malformed XML"
given:
  - "A file at the report path that is not well-formed XML OR has an unexpected root element"
when:
  - "junitResults() runs"
then:
  - "CommonExecutionResult.success == false"
  - "failureKind == USER"
  - "message indicates malformed / parse failure"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S4-malformed.txt
uath_under_test: "v2/pipeline-step-sdk/junit (F5.2 CERTIFIED)"
uath_owner: "F5.2 closure — NOT modified by this cycle"
```

### S5 — junit_results_hardened_parser

```yaml
id: e1.S5.junit_results_hardened_parser
title: "junit.results parser is hardened (XXE, streaming, byte cap)"
given:
  - "A maliciously-crafted XML with external entities / DOCTYPE / oversized body"
when:
  - "junitResults() runs"
then:
  - "External entities are NOT resolved (no XXE)"
  - "Files exceeding maxReportBytes (default 10 MiB) fail closed with USER"
  - "Parser uses SAX streaming (no DOM tree built)"
evidence_artifact: docs/v2/07-uat/evidence/e1-ecosystem-local-first/S5-hardened.txt
uath_under_test: "v2/pipeline-step-sdk/junit (F5.2 CERTIFIED)"
uath_owner: "F5.2 closure — NOT modified by this cycle"
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
