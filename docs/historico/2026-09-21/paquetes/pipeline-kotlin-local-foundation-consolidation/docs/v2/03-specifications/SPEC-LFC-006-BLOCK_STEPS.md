# SPEC-LFC-006 — Block steps and scoped context

**Status:** proposed

## Motivation

`withEnv`, `withCredentials`, `dir`, `timeout`, `timestamps`, `ansiColor`, `retry`, `catchError` and similar constructs currently risk bespoke nested-walker logic. Model them through a common block-step substrate.

## IR

```kotlin
data class PluginBlockStep(
    override val id: StepId,
    override val pluginStepId: PluginStepId,
    val input: SerializedCommand,
    val body: List<StepNode>,
) : StepNode
```

## Scoped transforms

Contextual wrappers implement an enter/execute/exit contract, with guaranteed cleanup.

Examples:

- `withEnv` -> environment overlay;
- `withCredentials` -> credential lease + environment/file projection + cleanup;
- `dir` -> workspace cwd view;
- `timestamps` -> output decorator;
- `timeout` -> deadline/cancellation scope.

Control-flow wrappers (`retry`, `catchError`) use the same body representation but add policy around body execution.

## Invariant

The child body still executes through the same `StepDispatcher`; wrappers cannot directly interpret arbitrary nested step types.
