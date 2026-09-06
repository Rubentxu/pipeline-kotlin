# Block Step Execution Specification

## 1. Purpose

Jenkins Pipeline steps may execute a body/closure zero, one or multiple times while
providing contextual behavior.

Pipeline Kotlin must model this directly.

## 2. First-class block contract

Conceptual descriptor:

```kotlin
data class StepDescriptor<C, R>(
    val id: StepId,
    val takesBody: Boolean,
    ...
)
```

Runtime:

```kotlin
fun interface PipelineBody<R> {
    suspend fun invoke(): R
}
```

```kotlin
interface BlockStepHandler<C, R> {
    suspend fun execute(
        context: StepInvocationContext,
        command: C,
        body: PipelineBody<R>,
    ): R
}
```

A body handler may:

- invoke once;
- invoke repeatedly (`retry`, `waitUntil`);
- not invoke at all (conditional/milestone-like behavior);
- alter contextual capabilities;
- catch/rethrow exceptions;
- cancel nested work.

## 3. Canonical IR

Static/declarative representation needs real nesting:

```kotlin
sealed interface ExecutionNode

data class AtomicStepNode(
    val id: StepId,
    val pluginStepId: PluginStepId,
    val payload: VersionedStepPayload,
) : ExecutionNode

data class BlockStepNode(
    val id: StepId,
    val pluginStepId: PluginStepId,
    val payload: VersionedStepPayload,
    val body: List<ExecutionNode>,
) : ExecutionNode

data class ParallelNode(
    val id: StepId,
    val branches: List<ParallelBranchNode>,
) : ExecutionNode

data class ScriptEntryNode(
    val id: StepId,
    val entryPoint: ScriptEntryPointId,
) : ExecutionNode
```

Do not linearize body semantics into marker events + shell scripts.

## 4. Context stack

Block steps compose immutable/scoped context overlays:

```text
Run
 └─ Stage
     └─ node
         └─ withEnv
             └─ withCredentials
                 └─ dir
                     └─ timeout
                         └─ retry attempt
                             └─ sh
```

Each scope pushes a typed overlay and guarantees restoration in `finally`.

No global mutable environment/workspace stack should be required for ordinary nested
execution.

## 5. `timeout`

Canonical surface:

```kotlin
timeout(
    time = 10,
    unit = TimeUnit.MINUTES,
    activity = false,
) {
    ...
}
```

`activity` is Boolean.

### Absolute timeout

Persist:

```text
scope id
startedAt
deadline
clock domain
```

On recovery, recompute remaining duration from persisted deadline.

### Activity timeout

Activity is driven by output/event activity within the body scope. Persist the last
accepted activity point or enough information to reconstruct it.

### Expiry

Expiry:

1. records timeout interruption;
2. cancels child scopes/tasks;
3. durable shell receives cancellation and terminates the process tree;
4. body throws `PipelineInterruptedException`;
5. parent wrappers decide whether to catch/rethrow.

## 6. `retry`

```kotlin
retry(count = 3) {
    ...
}
```

Vanilla behavior: repeat body on exceptions, except user abort.

Typed conditions can be modeled with dedicated condition types rather than strings.

```kotlin
retry(
    count = 3,
    conditions = listOf(agent(), nonresumable()),
) {
    ...
}
```

Each attempt has an explicit durable attempt id and lifecycle events.

On final failure, rethrow the last exception preserving its failure record.

## 7. `catchError`

```kotlin
catchError(
    buildResult = "FAILURE",
    stageResult = null,
    message = null,
    catchInterruptions = true,
) {
    ...
}
```

Normative behavior:

- execute body normally;
- catch ordinary body exceptions;
- by default, catch recognized flow interruptions;
- when `catchInterruptions=false`, rethrow flow interruptions;
- apply build/stage result policy;
- log/associate message if configured;
- continue after the block.

Do not translate nested steps into shell.

## 8. `warnError`

Equivalent policy:

```text
catchError(
  message = message,
  buildResult = UNSTABLE,
  stageResult = UNSTABLE,
  catchInterruptions = configured/default
)
```

It remains a real block, not a compiler marker rewrite.

## 9. `withEnv`

```kotlin
withEnv(listOf(
    "FOO=bar",
    "REMOVE_ME=",
    "PATH+TOOLS=/opt/tools/bin",
)) {
    ...
}
```

The body receives an environment overlay.

`PATH+NAME=x` prepends `x` to PATH according to compatibility rules.

The overlay is visible to all nested external processes and restored after body exit.

## 10. `withCredentials`

Credentials are resolved at execution time into a temporary scoped binding.

Requirements:

- no plaintext in IR/journal;
- temp secret files have restricted permissions;
- output redaction applies within the body;
- bindings are removed/restored in `finally`;
- failure/interruption does not leak secret material;
- replay references credential identity/version policy rather than storing secret.

## 11. `dir`

Produces a workspace/cwd overlay. Nested file/process steps resolve relative paths
against the overlay.

Restoration must work after:

- success;
- ordinary failure;
- timeout;
- cancellation.

## 12. Output decorators

`timestamps` and `ansiColor` should decorate the output pipeline/context rather than
rewrite stored final strings after the fact.

The raw/redacted/auditable storage policy must be defined so decoration is deterministic.

## 13. `node`

Local-first `node` may initially select local capability/workspace context rather than
perform remote scheduling.

However, it is still a block scope so a future scheduler can supply a worker context
without changing user DSL semantics.

## 14. Current rewrite removal

Remove the following once replacement UAT passes:

- `rewriteWorkflowControl`;
- shell `set +e` composition for catchError;
- fake marker-stack semantics used solely to simulate block scope;
- `unstable → emit event + sh(exit 0)` when a direct result API exists;
- `retry()` mutation of the previous `StepSpec`.

Historical readers/fixtures may keep deserialization adapters.

## 15. Block identity and replay

A block scope has its own operation identity and dynamic path.

Nested step identities include that scope.

A block that catches a recorded child failure must catch it again during replay and
reach the same following control flow.

## 16. Plugin SDK

Plugin metadata must declare whether a step:

- takes a body;
- may invoke body multiple times;
- introduces context;
- may catch interruptions;
- has cancellation behavior;
- has replay policy constraints.

This allows generated documentation, validators and the LSP to understand block
semantics without plugin-specific hardcoding.
