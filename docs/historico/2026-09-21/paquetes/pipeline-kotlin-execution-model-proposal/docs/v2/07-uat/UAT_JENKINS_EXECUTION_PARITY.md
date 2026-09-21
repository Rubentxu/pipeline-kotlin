# UAT — Jenkins Execution Parity and Durable Kotlin Runtime

Prefix: `UAT-JEP-*`

These scenarios are product acceptance tests, not implementation-unit tests.

## Evidence rules

Every acceptance claim must include fresh:

- command argv;
- exit code;
- JUnit XML or structured result artifact;
- run logs/output digest;
- source/runtime/plugin versions;
- for differential tests: Jenkins baseline version/plugin lock.

No manual statement such as "pre-existing failure" is evidence without a fresh
base-vs-head comparison.

---

## A. Shell contract

### UAT-JEP-001 — default shell success

```kotlin
sh("printf 'ok\n'")
```

Expected:

- step success;
- stdout visible in normal output;
- no StepFailed.

### UAT-JEP-002 — default shell non-zero

```kotlin
sh("exit 7")
```

Expected:

- typed shell step exception;
- failure record kind SCRIPT;
- StepFailed exactly once;
- exit code retained as structured detail.

### UAT-JEP-003 — returnStatus non-zero is success

```kotlin
val rc = sh(
    script = "exit 7",
    returnStatus = true,
)
assertEquals(7, rc)
```

Expected:

- caller continues;
- StepFailed not emitted;
- process exit telemetry may record 7;
- pipeline can still succeed.

### UAT-JEP-004 — returnStdout

```kotlin
val out = sh(
    script = "printf 'abc\n'",
    returnStdout = true,
)
assertEquals("abc\n", out)
```

Expected:

- exact String including trailing newline;
- stdout not duplicated into normal user log.

### UAT-JEP-005 — stderr with returnStdout

Script emits stdout + stderr.

Expected:

- stdout returned;
- stderr remains visible in normal output;
- returned value excludes stderr.

### UAT-JEP-006 — multiline and shebang

Run:

- multiline Bourne script;
- executable script body beginning with explicit shebang.

Expected interpreter semantics match declared Jenkins compatibility.

### UAT-JEP-007 — encoding and label

Expected:

- returned/log output uses configured encoding;
- label changes display metadata, not execution result.

---

## B. Durable shell state

### UAT-JEP-008 — runtime dies while shell continues

Long-running shell starts and runtime process is terminated.

Expected:

- shell survives according to local durable-task contract;
- recovery sees running/reconciling task;
- no duplicate shell process;
- completion result is reused;
- output resumes from persisted offset.

### UAT-JEP-009 — launch failure

Force a deterministic OS/process launch failure.

Expected:

- InfrastructureStepException;
- durable FailureRecord;
- no fake SCRIPT exit;
- diagnostic cause attached in-process when available.

### UAT-JEP-010 — definitive lost task

Produce a task whose durable evidence becomes conclusively unrecoverable according to
the configured lost policy.

Expected:

- infrastructure failure;
- failure details include task/control identity;
- never assume success;
- no fabricated original Throwable after replay.

---

## C. Timeout/interruption

### UAT-JEP-011 — absolute timeout

```kotlin
timeout(time = 1, unit = TimeUnit.SECONDS) {
    sh("sleep 30")
}
```

Expected:

- timeout interruption;
- nested process tree terminated;
- timeout record persisted;
- not classified SCRIPT.

### UAT-JEP-012 — timeout recovery

Crash runtime while timeout body is active.

Expected:

- persisted deadline remains authoritative;
- restart does not reset timeout duration;
- body is cancelled when deadline is already expired.

### UAT-JEP-013 — activity timeout

`activity=true`.

Expected:

- log/output activity extends allowed lifetime according to baseline semantics;
- silence expires the block.

---

## D. Retry

### UAT-JEP-014 — retry ordinary failure

Body fails twice and succeeds third time.

Expected:

- exactly three attempt ids;
- third result returned;
- attempt events ordered;
- no fourth attempt.

### UAT-JEP-015 — user abort is not retried by vanilla retry

Expected immediate propagation of user-abort interruption.

### UAT-JEP-016 — infrastructure retry condition

A retry condition equivalent to Jenkins agent/nonresumable semantics is exercised.

Expected only matching failures are retried.

---

## E. catchError / warnError

### UAT-JEP-017 — catchError continues

Inner `sh` fails.

Expected:

- inner sh has StepFailed;
- catchError consumes exception;
- configured build/stage result applied;
- statement after catchError executes.

### UAT-JEP-018 — catchInterruptions false

Timeout inside catchError with `catchInterruptions=false`.

Expected timeout interruption propagates.

### UAT-JEP-019 — warnError

Expected build + stage become UNSTABLE and following statements execute.

### UAT-JEP-020 — arbitrary nested step types

Inside catchError execute nested:

- withEnv;
- withCredentials test provider;
- dir;
- write/read file;
- custom test plugin.

Expected no conversion to shell script and normal typed execution for every child.

---

## F. Context

### UAT-JEP-021 — withEnv nesting

Verify:

- ordinary override;
- unset;
- `PATH+NAME` prepend;
- nested restore.

### UAT-JEP-022 — dir restore on failure

Nested body throws.

Expected cwd restored in following step.

### UAT-JEP-023 — credential cleanup/redaction

Expected:

- credential available only in scope;
- secret absent from argv/journal/events;
- secret redacted from output;
- temp files removed after failure/interruption.

---

## G. Dynamic scripted Kotlin

### UAT-JEP-024 — runtime-value branch

```kotlin
script {
    val branch = sh(
        script = "printf 'main\n'",
        returnStdout = true,
    ).trim()

    if (branch == "main") {
        sh("printf deploy")
    } else {
        sh("printf skip")
    }
}
```

Expected deploy branch.

### UAT-JEP-025 — replayed runtime-value branch

Kill runtime after first sh completed but before branch shell completes.

Expected:

- first sh not relaunched;
- recorded `"main\n"` returned;
- same branch selected;
- operation ids identical.

### UAT-JEP-026 — loop identity

```kotlin
repeat(3) { i ->
    sh("printf '$i'")
}
```

Crash/recover at each possible boundary.

Expected each logical iteration has a stable distinct operation key.

### UAT-JEP-027 — source change fail closed

Modify pipeline source between run and recovery.

Expected explicit replay compatibility error unless original artifact is selected.

### UAT-JEP-028 — runtime-returning workspace APIs

Use `readFile`, `fileExists`, `pwd`, `isUnix` inside Kotlin expressions.

Expected real runtime values, no placeholders.

---

## H. Lifecycle/event ownership

### UAT-JEP-029 — exactly-once lifecycle terminal emission

For success, failure, interruption and returnStatus nonzero:

- StepStarted exactly once;
- terminal lifecycle event policy exactly once;
- no duplicate StepFailed from handler + coordinator.

### UAT-JEP-030 — engine invariant isolation

Inject an impossible internal transition.

Expected:

- ENGINE/INTERNAL classification;
- not SCHEMA;
- not swallowed by catchError as an ordinary script failure unless explicitly
  configured for engine failures (default: never).

---

# Differential Jenkins Harness

## Purpose

For F2/F3 compatibility, run equivalent scenarios against a pinned Jenkins baseline.

Layout suggestion:

```text
compatibility/jenkins/
  baseline.lock
  scenarios/
    sh-default/
      Jenkinsfile
      pipeline.pipeline.kts
      expected.yaml
```

## Normalized result

```yaml
scenario: sh-return-status-7
jenkins:
  return: 7
  buildResult: SUCCESS
  stageResult: SUCCESS
pipelineKotlin:
  return: 7
  buildResult: SUCCESS
  stageResult: SUCCESS
comparison:
  status: PASS
```

Normalize unstable implementation-specific data:

- timestamps;
- temp paths;
- UUIDs;
- PIDs;
- stack frames.

Do not normalize semantic differences.

## Compatibility report

Every release candidate generates:

```text
PASS
INTENTIONAL_DEVIATION
UNSUPPORTED
FAIL
```

per scenario, with links to ADRs for deviations.

"Jenkins familiar" is not accepted as F2/F3 without this report.
