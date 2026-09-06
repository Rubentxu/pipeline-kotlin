# Jenkins Reference Baseline for Execution Semantics

**Checked:** 2026-09-05

This file lists primary references that should back compatibility claims.

## 1. `sh`, `bat`, PowerShell, node/process steps

Official Pipeline step reference:

https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/

Contract used by this proposal:

- `sh.script` required;
- `encoding`, `label`, `returnStatus`, `returnStdout` optional;
- non-zero normally fails the step;
- `returnStatus=true` returns the status instead;
- `returnStdout=true` returns stdout as String while stderr remains logged;
- multiline shell scripts are accepted;
- shebang can select interpreter;
- otherwise shell uses normal `-xe` behavior.

## 2. Basic block/control steps

Official Pipeline step reference:

https://www.jenkins.io/doc/pipeline/steps/workflow-basic-steps/

Contract used:

- `timeout` throws `FlowInterruptedException` on expiry;
- `timeout.activity` is Boolean;
- timeout unit defaults to MINUTES;
- `retry` retries body on exception; user abort is excluded by default;
- retry conditions include infrastructure/nonresumable concepts;
- `catchError` continues after caught body failure;
- `catchError.catchInterruptions` defaults true;
- `warnError` is equivalent to catchError with build/stage UNSTABLE;
- `withEnv` supports `VAR=value`, `VAR=` and `PATH+NAME=/path`;
- `readFile`, `pwd`, `fileExists`, `isUnix` return real runtime values.

## 3. Pipeline Step API / body invocation model

Repository:

https://github.com/jenkinsci/workflow-step-api-plugin

Developer guide establishes:

- asynchronous StepExecution callback model;
- success/failure completion;
- `stop` for cancellation;
- `onResume` for restart;
- block-scoped steps declare implicit body argument;
- body invocation can add context and callback;
- FlowNode may be associated with step execution.

Pipeline Kotlin does not copy this API, but uses it as evidence that body-scoped steps
are an execution primitive, not shell/compiler rewrites.

## 4. Durable shell mechanics

BourneShellScript:

https://github.com/jenkinsci/durable-task-plugin/blob/master/src/main/java/org/jenkinsci/plugins/durabletask/BourneShellScript.java

FileMonitoringTask:

https://github.com/jenkinsci/durable-task-plugin/blob/master/src/main/java/org/jenkinsci/plugins/durabletask/FileMonitoringTask.java

Relevant concepts:

- script file;
- result file;
- log/output files;
- atomic result publication;
- output capture;
- heartbeat check;
- durable control directory;
- cookie;
- background launch;
- shebang handling;
- script copy for text-file-busy mitigation.

ADR-0046 already adopted the valuable local subset and remains the local mechanical
authority.

## 5. Compatibility discipline

The project should pin an executable Jenkins baseline rather than relying forever on
"latest" online docs.

Suggested lock fields:

```yaml
jenkinsCore: "<pinned LTS>"
plugins:
  workflow-durable-task-step: "<version>"
  durable-task: "<version>"
  workflow-basic-steps: "<version>"
  workflow-step-api: "<version>"
  workflow-api: "<version>"
checkedAt: "2026-09-05"
```

The exact versions belong in the future differential harness and should be updated
through an explicit compatibility review.
