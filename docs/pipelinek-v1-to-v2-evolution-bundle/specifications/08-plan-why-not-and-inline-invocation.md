# Specification — `plan`, `why-not` and inline invocation

## 1. Goal

Expose the runtime's real admission decision without executing Step effects, then reuse the same invocation path for ASX inline CLI.

## 2. Typed preparation decision

Refactor the current preparation logic so the decision itself is reusable:

```kotlin
sealed interface RegistryPreparationDecision {
    data class Ready(...) : RegistryPreparationDecision
    data class UnknownStep(...) : RegistryPreparationDecision
    data class MissingCapabilities(...) : RegistryPreparationDecision
    data class InvalidInput(...) : RegistryPreparationDecision
}
```

`RegistryExecutionPreparation.prepare(...)` becomes an adapter from this decision to current `ExecutionPreparation` during migration.

## 3. Law: decide without effects

Calling the decision function SHALL NOT:

- execute a handler;
- write events that imply Step execution;
- start processes;
- mutate workspace;
- resolve credentials into secret material;
- perform network I/O.

## 4. CLI

```text
pipelinek step <id> --input-json @request.json --plan
pipelinek why-not step <id> --input-json @request.json
```

`--plan` returns readiness and declared effects/capabilities; `why-not` focuses on blocking reasons and corrective affordances.

## 5. Inline execution

ASX `pipelinek step` without `--plan` SHALL lower to the same canonical registry/durable execution path as a Step in a compiled pipeline.

No inline-only handler.

## 6. Command/sh

Existing ASX design remains: `command` is initially a safe argv façade that lowers to the certified `core.sh` durable spine via canonical quoting; `sh` is explicit shell syntax. A new `core.exec` is out of scope until a separate process-runtime extraction proves value and parity.

## 7. Exit criteria

- plan reports the same missing capability the real execution would reject;
- plan proves zero handler invocation with counters/canaries;
- inline execution emits the same semantic Step lifecycle shape as pipeline execution;
- exit codes remain 0 success / 1 executed failure / 2 invocation-admission-config error.

## 8. Effect boundary under script compilation/evaluation

GR-008: pure typed Step planning is distinct from evaluating arbitrary Kotlin. `step --plan` never calls `evalWithTemplate` on user source to infer readiness. `pipelinek validate` preserves its separately characterized compile-and-DSL-construction contract; compilation phase separation does not silently change it into a sandbox or prove arbitrary top-level code effect-free.

Compiler cache hits never cache readiness/capabilities/trust or authorize an invocation. Fresh current preparation decisions still govern inline and pipeline execution.
