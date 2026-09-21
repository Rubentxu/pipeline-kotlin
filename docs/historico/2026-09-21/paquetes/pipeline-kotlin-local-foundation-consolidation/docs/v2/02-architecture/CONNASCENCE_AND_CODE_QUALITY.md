# Connascence and code-quality remediation

## Replace positional/data-clump connascence

Avoid repeated `(runId, stageIndex, stepIndex, attempt)` arguments. Introduce typed value objects such as `StepExecutionId`, `OperationId` and `StepExecutionContext`.

## Replace name/string connascence

No application/runtime switches on `"success"`, `"failure"`, string effects, replay policies or execution locations. Use sealed/enums/value types.

## Replace algorithm connascence

Environment precedence, process execution, credential projection and output redaction each have one implementation owner. Callers depend on the owner contract rather than duplicating the sequence.

## Replace temporal connascence

Plugin registration, credential cleanup and output commits should use lifecycle/context abstractions so callers cannot forget required ordering.

## God-file remediation

Large orchestrator files are split by semantic ownership, not arbitrary line count:

- coordinator/planner;
- dispatcher;
- individual handlers;
- block policy/transform implementations;
- adapters.

No "util" module should become the next god-object dumping ground.
