# Jenkins-Compatible `sh` Contract

## 1. Scope

This specification defines the observable compatibility target for Pipeline Kotlin
`sh`.

Reference baseline:

- Jenkins Pipeline Nodes and Processes step reference;
- `workflow-durable-task-step`;
- `durable-task` BourneShellScript behavior.

The compatibility target is behavioral, not binary.

## 2. Jenkins surface

Canonical Jenkins parameters:

```text
script: String                    required
encoding: String?                 optional
label: String?                    optional
returnStatus: Boolean             optional
returnStdout: Boolean             optional
```

No canonical `timeoutMs`, `env` or `isScriptBlock` parameter exists.

Multiple-line scripts are valid. A shebang may select an interpreter. Without a
shebang, the configured/default Bourne shell behavior is used with `-xe`.

## 3. Observable behavior matrix

| Mode | exit 0 | exit non-zero | stdout | stderr |
|---|---|---|---|---|
| default | success / no value | throw step failure | log | log |
| `returnStdout=true` | return stdout String | throw step failure | returned, not normal log stream | log |
| `returnStatus=true` | return `0` | return exit code | normal log | normal log |

`returnStatus=true` changes the contract: exit `42` is a successful step invocation
whose return value is `42`.

Therefore the lifecycle layer MUST NOT emit `StepFailed` merely because the child
process exit code is non-zero when `returnStatus=true`.

## 4. Mutually exclusive return modes

The canonical internal command uses:

```kotlin
enum class ShellReturnMode {
    NONE,
    STDOUT,
    STATUS,
}
```

The public DSL must reject a request that semantically enables both stdout and status
return modes.

## 5. Kotlin façade

Kotlin should preserve Jenkins named-argument style while avoiding `Any?`.

A feasible surface is generated overloads, for example:

```kotlin
suspend fun sh(
    script: String,
    encoding: String? = null,
    label: String? = null,
): Unit
```

```kotlin
suspend fun sh(
    script: String,
    returnStdout: Boolean,
    encoding: String? = null,
    label: String? = null,
): String
```

```kotlin
suspend fun sh(
    script: String,
    encoding: String? = null,
    label: String? = null,
    returnStatus: Boolean,
): Int
```

The exact JVM overload layout may be code-generated. The normative requirements are:

- call sites use Jenkins parameter names;
- result types are statically useful;
- unsupported combinations fail before launching the process;
- no `Any` leaks into ordinary user code.

If overload resolution proves too fragile, a generated typed option marker is
acceptable, but a generic `ShellResult` must not silently replace Jenkins behavior
under the name `sh`.

## 6. Pipeline Kotlin extension API

A richer result is useful but is a separate API:

```kotlin
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val duration: Duration,
)
```

Possible names:

- `exec`
- `shResult`
- `process`

It must be documented as a Pipeline Kotlin extension, not Jenkins `sh`.

## 7. Internal command

```kotlin
data class ShellCommand(
    val script: String,
    val encoding: CharsetName?,
    val label: String?,
    val returnMode: ShellReturnMode,
)
```

No timeout and no environment map.

Effective environment and deadline/cancellation come from `StepInvocationContext`.

## 8. Durable task API

The lower-level shell executor returns only terminal information:

```kotlin
sealed interface DurableTaskTerminal {
    data class Exited(
        val exitCode: Int,
        val output: OutputRef,
    ) : DurableTaskTerminal

    data class LaunchFailed(
        val failure: FailureRecord,
    ) : DurableTaskTerminal

    data class Lost(
        val failure: FailureRecord,
    ) : DurableTaskTerminal

    data class Cancelled(
        val interruption: InterruptionRecord,
    ) : DurableTaskTerminal
}
```

`Launching` and `Running` are snapshots, not values accepted by the shell result
classifier.

## 9. Shell semantic classifier

Pure classifier:

```text
Exited(0), NONE       → Unit
Exited(0), STDOUT     → captured stdout
Exited(0), STATUS     → 0

Exited(n!=0), STATUS  → n
Exited(n!=0), NONE    → ShellExitException(n)
Exited(n!=0), STDOUT  → ShellExitException(n)

LaunchFailed          → InfrastructureStepException
Lost                  → InfrastructureStepException
Cancelled             → PipelineInterruptedException
```

There is no `RUNNING → SCHEMA` branch.

## 10. Failure provenance

A launch/lost failure must carry a durable `FailureRecord` containing at least:

```text
code
message
failure kind
origin
retryability
operation id
control/task identity
worker identity when available
diagnostic details
```

If the failure was caught in the same process, `Throwable?` may be attached to the
runtime exception. It is not the persisted contract.

## 11. Output contract

When `returnStdout=false`:

- stdout → build/run output service;
- stderr → build/run output service.

When `returnStdout=true`:

- stdout → durable output capture for the returned String;
- stderr → build/run output service;
- stdout may still be stored internally for audit/replay, but must not be duplicated
  to the normal user log merely because storage is unified.

Encoding applies consistently to returned stdout and log transcoding according to the
step contract.

## 12. Script-file execution

Preserve ADR-0046 mechanics:

- write script verbatim to `script.sh`;
- respect shebang;
- otherwise invoke the selected/default shell with expected flags;
- protect against text-file-busy where relevant;
- wrapper contains paths/control mechanics, never user script interpolation;
- output/result written to files;
- result published atomically;
- durable process detached from runtime process;
- cookie/heartbeat preserved.

## 13. Timeout

Canonical use:

```kotlin
timeout(time = 5, unit = TimeUnit.MINUTES) {
    sh("./build.sh")
}
```

The shell receives cancellation through the invocation context.

A timeout is not modeled by a `timeoutMs` field on `ShellCommand`.

Legacy `timeoutMs` is deprecated only after the block implementation reaches parity.

## 14. Environment

Canonical use:

```kotlin
withEnv(listOf("FOO=bar", "PATH+TOOLS=/opt/tools/bin")) {
    sh("tool")
}
```

Environment composition is contextual.

No canonical `env: Map<String,String>` parameter exists on `sh`.

## 15. Label

`label` is display metadata for the step. It must not change operation semantics or
task identity except insofar as source command payload compatibility includes the
configured parameter.

## 16. Required UAT

- default exit 0;
- default non-zero throws;
- `returnStatus` 0;
- `returnStatus` non-zero returns and step stays successful;
- `returnStdout` exact stdout with trailing newline;
- stderr still visible under `returnStdout`;
- multiline script;
- shebang;
- encoding;
- label metadata;
- runtime restart while shell continues;
- launch failure;
- definitive lost task;
- timeout interruption;
- user cancellation;
- nested retry/catchError behavior;
- no secret/script injection via outer shell.
