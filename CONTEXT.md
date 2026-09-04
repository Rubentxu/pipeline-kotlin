# Pipeline Kotlin

The local-first pipeline context separates a compiled definition from one runtime invocation, so the same inspectable model can be validated, executed, and graphed.

## Language

**Compiled Pipeline**:
The immutable, inspectable definition produced from source before any run begins.
_Avoid_: pipeline spec, runtime pipeline

**Definition Identity**:
The deterministic identity of a compiled pipeline definition.
_Avoid_: run ID, operation ID

**Stage Body**:
The single structural form of a stage: steps, nested stages, parallel branches, or a matrix.
_Avoid_: untyped stage contents

**Step Node**:
A definition-local step with a stable identity, plugin step identity, and versioned opaque payload.
_Avoid_: runtime operation, DSL step object

**Runtime Operation**:
One attempted execution of a step during a pipeline run.
_Avoid_: step ID

**Effect**:
The domain-owned classification of an operation's externally observable side effect.
_Avoid_: SDK effect enum

**Replay Policy**:
The domain-owned rule determining whether an operation may be reused, rerun, or must not replay.
_Avoid_: SDK replay enum

**Step Descriptor**:
The domain-owned static metadata contract for a plugin step kind; it is neither a step payload nor a runtime invocation.
_Avoid_: SDK descriptor, runtime step object

**Execution Location**:
The static declared location for a step contract, not a runtime scheduler decision.
_Avoid_: worker assignment

**DSL Compilation**:
The deterministic transformation of a declarative pipeline specification into an inspectable compiled definition before validation or execution.
_Avoid_: runtime evaluation, execution planning
