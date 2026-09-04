# SPEC-LFC-005 — Single execution spine

**Status:** proposed

## Contracts

```kotlin
interface RunCoordinator {
    suspend fun run(request: RunRequest): RunOutcome
}

interface StepDispatcher {
    suspend fun dispatch(node: StepNode, context: StepExecutionContext): StepOutcome
}

interface StepHandler<C : StepCommand> {
    suspend fun execute(command: C, context: StepExecutionContext): StepOutcome
}
```

`StepExecutionContext` carries typed IDs and views/capabilities, not concrete adapter implementations.

## Invariants

- every atomic/block/plugin step goes through exactly one dispatcher;
- retry/resume/parallel execution does not bypass dispatch;
- no `runBlocking` inside application/runtime orchestration boundaries; blocking is confined to CLI/main/test bridges if needed;
- process work routes through `DurableTaskRuntime`/`ProcessService`;
- timeout/cancellation terminates the whole process tree;
- outcomes are typed, never string literals like `"success"`/`"failure"` in the application/runtime core.

## Outcome sketch

```kotlin
sealed interface StepOutcome {
    data class Success(val result: StepResultRef?) : StepOutcome
    data class Failure(val failure: Failure) : StepOutcome
    data class Cancelled(val reason: CancellationReason) : StepOutcome
    data class Skipped(val reason: SkipReason) : StepOutcome
}
```
