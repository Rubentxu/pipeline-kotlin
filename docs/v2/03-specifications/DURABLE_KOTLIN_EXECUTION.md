# Durable Kotlin Execution Specification

## 1. Purpose

Define the execution architecture that allows Pipeline Kotlin to provide:

- normal Kotlin control flow;
- runtime values from steps;
- Jenkins-familiar behavior;
- local durability and restart recovery;
- no CPS/continuation serialization;
- deterministic, inspectable operation history;
- future-compatible worker boundaries without designing a remote controller today.

Normative decision: ADR-0065.

## 2. Two execution surfaces

### 2.1 Declarative surface

The Declarative surface is a description.

It may be inspected before effects occur and may produce a serializable manifest/IR.

Examples:

```kotlin
pipeline {
    agent { label("linux") }

    environment {
        env("CI", "true")
    }

    stages {
        stage("Build") {
            options {
                timeout(time = 30, unit = TimeUnit.MINUTES)
            }

            steps {
                sh("./gradlew build")
            }
        }
    }
}
```

The implementation may statically compile obvious atomic steps, but the manifest is
not allowed to pretend that dynamic Kotlin results are known ahead of execution.

### 2.2 Scripted surface

A scripted body is executable Kotlin:

```kotlin
script {
    val branch = sh(
        script = "git branch --show-current",
        returnStdout = true,
    ).trim()

    if (branch == "main") {
        deploy()
    }
}
```

The runtime executes the block with a `ScriptedScope`/capability context.

## 3. Runtime call model

Conceptual stack:

```text
Kotlin façade
    ↓
StepInvoker
    ↓
StepExecutionBoundary
    ↓
ReplayLookup
    ├─ completed → materialize recorded result
    ├─ running durable → reconcile/reattach
    └─ new → StepHandler
                  ↓
              capability
                  ↓
             DurableTask
```

A step façade contains no orchestration logic beyond:

- validate caller-level mutually-exclusive options;
- build typed command;
- invoke the runtime;
- decode typed return value.

## 4. Core contracts

Illustrative contracts; names may be refined but responsibilities are normative.

```kotlin
interface StepInvoker {
    suspend fun <C : StepCommand, R : Any?> invoke(
        descriptor: StepDescriptor<C, R>,
        command: C,
        callSite: CallSite,
    ): R
}
```

```kotlin
interface StepHandler<C : StepCommand, R : Any?> {
    suspend fun execute(
        context: StepInvocationContext,
        command: C,
    ): R
}
```

```kotlin
data class StepInvocationContext(
    val run: RunIdentity,
    val operation: OperationIdentity,
    val workspace: WorkspaceRef?,
    val environment: EnvironmentView,
    val cancellation: CancellationScope,
    val output: OutputSink,
    val clock: PipelineClock,
    val capabilities: CapabilitySet,
)
```

The context is not a universal service locator. Handlers receive or request only
declared capabilities.

## 5. Operation identity

### 5.1 Requirements

An operation key must be:

- deterministic for the same compatible execution history;
- distinct across dynamic loop iterations;
- distinct across parallel branches;
- distinct across retry attempts;
- stable after process restart;
- scoped by source/plugin/runtime compatibility information;
- computable before executing the effect.

### 5.2 Proposed identity

```text
OperationKey =
    definitionDigest
  + executableEntryPointId
  + staticCallSiteId
  + dynamicScopePath
  + invocationOrdinalWithinScope
  + attemptId
```

`staticCallSiteId` should prefer compiler/source mapping generated from a stable
source range or explicit user key rather than raw runtime stack traces.

`dynamicScopePath` examples:

```text
stage:build
script:42
loop:45[2]
retry:deploy/attempt:1
parallel:test/branch:linux
```

### 5.3 Input digest

The journal stores an input digest separately:

```text
stepId
pluginId
pluginVersion
command schema version
normalized command payload
effective context values that affect semantics
```

If an existing operation key is found with a mismatched input digest, recovery fails
closed with `ReplayCompatibilityException`.

## 6. Journal state machine

```text
ABSENT
  ↓ schedule (persist)
SCHEDULED
  ↓ start
RUNNING
  ├─ completed → SUCCEEDED(result)
  ├─ failed → FAILED(FailureRecord)
  ├─ interrupted → INTERRUPTED(InterruptionRecord)
  └─ process survives runtime → RECONCILING
```

No effect may begin before its scheduled record is durable enough to prevent
untracked execution.

## 7. Replay algorithm

Pseudo-code:

```kotlin
suspend fun <C, R> invoke(command: C): R {
    val identity = identityFactory.forCall(command, callSite, dynamicScope)

    return when (val previous = journal.lookup(identity.key)) {
        null -> executeFresh(identity, command)

        is Succeeded -> {
            verifyCompatible(previous, command)
            codec.decode(previous.result)
        }

        is Failed -> {
            verifyCompatible(previous, command)
            throw failureMaterializer.toException(previous.failure)
        }

        is Interrupted -> {
            verifyCompatible(previous, command)
            throw interruptionMaterializer.toException(previous.interruption)
        }

        is Running, is Reconciling -> {
            verifyCompatible(previous, command)
            reconcile(identity, previous, command)
        }
    }
}
```

A replayed failure must fail at the same logical call site without relaunching the
effect.

## 8. Artifact identity

Recovery must execute the original compatible compiled artifact.

Minimum identity:

- source digest;
- DSL API version;
- compiler adapter version;
- runtime compatibility version;
- plugin lock digest;
- generated step façade schema digest.

If the requested recovery artifact is not available, fail explicitly. Do not compile
new source and pretend it is the same run.

## 9. Determinism contract for scripted Kotlin

Allowed normal Kotlin:

- local variables;
- if/when;
- loops;
- collections;
- pure helper functions;
- data classes;
- deterministic string/number operations.

External/nondeterministic values that can affect durable control flow must enter
through recorded Pipeline APIs.

Examples:

```text
GOOD                          NOT DURABLE BY DEFAULT
----                          ----------------------
sh(...)                       ProcessBuilder(...)
readFile(...)                 Files.readString(...)
pipelineClock.now()           System.currentTimeMillis()
pipelineRandom.next...        Random.Default...
httpRequest(...)              raw Socket/HttpClient
credentials(...)              direct secret file access
```

The scripting/compiler layer should progressively lint or deny known unsafe APIs in
durable scripted scopes.

## 10. Parallel

Each parallel branch has its own deterministic scope path and invocation cursor.

Completion ordering must not affect branch identity.

Join result is explicit and journaled.

Cancellation must propagate to branch scopes according to the parent policy.

## 11. Retry

Each attempt receives a distinct attempt identity while the body call sites remain
stable beneath it.

Example:

```text
retry@L20
  attempt:1/sh@L21
  attempt:2/sh@L21
```

Recorded successes from a failed previous attempt are reused only if the retry/body
step policy explicitly allows it. Default policy should match intended Jenkins
observable behavior, not maximize cache reuse.

## 12. Source changes during recovery

If source/plugin/runtime compatibility changes:

- do not transparently continue;
- require the original artifact, or
- create an explicit fork/migration workflow.

A recovery error is preferable to replaying side effects under changed code.

## 13. Output

Output is a cross-cutting service keyed by run/operation/stream/offset.

A durable process writes stdout/stderr to durable files or an equivalent local sink.
The output service exposes:

- append;
- read page;
- tail/stream;
- byte/line offsets;
- redaction;
- timestamps/decorators;
- future transport adapter.

`returnStdout` is a step return-mode policy, not the storage model.

## 14. Security

- user shell text is written verbatim to a script file;
- user script text never becomes part of an outer shell command string;
- credentials do not enter argv;
- persisted commands/events must redact or reference secrets;
- replay records store credential identity/version metadata, not plaintext;
- call-site/source mapping must not expose secret values.

## 15. Required tests

At minimum:

1. completed step replays without relaunch;
2. recorded failure rethrows without relaunch;
3. runtime dies while durable shell continues and later reattaches;
4. source digest mismatch fails closed;
5. loop operation keys remain stable;
6. parallel branch completion order does not change keys;
7. retry attempts are distinct;
8. nested block scopes restore context after success/failure/interruption;
9. output offsets survive restart;
10. secret values do not appear in argv/journal/events.
