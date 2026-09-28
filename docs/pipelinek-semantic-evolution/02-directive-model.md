# Declarative Directive Model

## 1. Why directives need their own model

Jenkins separates Declarative directives (`when`, `post`, `agent`, `environment`, `options`) from Pipeline Steps. PipelineK should do the same while preserving its own typed/durable architecture.

Directives alter stage orchestration and must not masquerade as Steps.

## 2. Closed directive execution shapes / open directive registry

Use the same principle as Steps:

```text
closed execution structure + open registry
```

### 2.1 Invocation

```kotlin
data class DirectiveInvocation(
    val key: DirectiveKey,
    val schemaVersion: DirectiveSchemaVersion,
    val encodedInput: EncodedDirectiveValue,
)
```

### 2.2 Definition

```kotlin
data class DirectiveDefinition<I : Any, O : Any>(
    val key: DirectiveKey,
    val contract: DirectiveContract<I, O>,
    val policy: DirectiveExecutionPolicy,
    val handler: DirectiveHandler<I, O>,
)
```

### 2.3 Closed execution policy

```kotlin
sealed interface DirectiveExecutionPolicy {
    data class Guard(val phase: GuardPhase) : DirectiveExecutionPolicy
    data class Context(val phase: ContextPhase) : DirectiveExecutionPolicy
    data class Resource(val phase: ResourcePhase) : DirectiveExecutionPolicy
    data class Finalizer(val condition: FinalizerCondition) : DirectiveExecutionPolicy
    data class StagePolicy(val phase: PolicyPhase) : DirectiveExecutionPolicy
}
```

The engine switches over this **closed shape**, never over `DirectiveKey`.

External libraries can register a directive only by selecting a supported shape. A new orchestration shape requires a core architectural version, preventing plugins from silently inventing a second coordinator.

## 3. Directive phases

```kotlin
enum class GuardPhase {
    BEFORE_OPTIONS,
    BEFORE_INPUT,
    BEFORE_AGENT,
    AFTER_AGENT,
    BEFORE_BODY,
}

enum class ContextPhase {
    BEFORE_BODY,
}

enum class ResourcePhase {
    BEFORE_BODY,
}

enum class PolicyPhase {
    BEFORE_BODY,
    AFTER_BODY,
}
```

Deterministic stage lifecycle:

```text
StageDeclared
   -> guards BEFORE_OPTIONS
   -> options/policies
   -> guards BEFORE_INPUT
   -> input/approval (future)
   -> guards BEFORE_AGENT
   -> resource/agent resolution
   -> guards AFTER_AGENT
   -> context directives
   -> guards BEFORE_BODY
   -> stage body
   -> finalizers/post
   -> StageFinished
```

Every transition emits events.

## 4. `when`

Do not evaluate arbitrary expression strings in the declarative model.

Use a typed predicate ADT:

```kotlin
sealed interface StagePredicate {
    data class Equals(val left: ValueExpr, val right: ValueExpr) : StagePredicate
    data class Not(val child: StagePredicate) : StagePredicate
    data class All(val children: NonEmptyList<StagePredicate>) : StagePredicate
    data class Any(val children: NonEmptyList<StagePredicate>) : StagePredicate
    data class Branch(val pattern: String, val comparator: StringComparator) : StagePredicate
    data class Environment(val name: EnvName, val expected: String) : StagePredicate
}

sealed interface ValueExpr {
    data class Literal(val value: String) : ValueExpr
    data class Env(val name: EnvName) : ValueExpr
    data class Parameter(val name: ParameterName) : ValueExpr
}
```

Evaluation is pure:

```text
Predicate + StageAdmissionContext -> PredicateResult
```

```kotlin
sealed interface PredicateResult {
    data object Match : PredicateResult
    data class NoMatch(val reason: PredicateReason) : PredicateResult
    data class Rejected(val error: PredicateError) : PredicateResult
}
```

No side effects during evaluation.

Legacy `whenCondition(String)` remains fail-closed until a typed migration exists.

## 5. `post`

Represent finalization as data, not body flattening:

```kotlin
enum class PostCondition {
    ALWAYS,
    SUCCESS,
    FAILURE,
    UNSTABLE,
    ABORTED,
    UNSUCCESSFUL,
    CLEANUP,
}

data class PostPlan(
    val bodies: Map<PostCondition, BodyRef>,
)
```

Pure decision:

```text
StageOutcome + PostPlan -> ordered List<BodyRef>
```

Interpret through `BodyInvoker`.

Order is versioned and deterministic. `cleanup` always runs last.

## 6. `agent`

Do not fake Jenkins remote scheduling.

Define a typed requirement:

```kotlin
sealed interface ExecutionTargetRequirement {
    data object LocalAny : ExecutionTargetRequirement
    data class LocalLabels(val labels: Set<AgentLabel>) : ExecutionTargetRequirement
    data class CapabilitySet(val required: Set<ExecutionCapability>) : ExecutionTargetRequirement
    data class Remote(val selector: RemoteSelector) : ExecutionTargetRequirement
}
```

For the current local profile:

- `LocalAny`, local labels/capabilities: supported;
- `Remote`: rejected fail-closed unless a remote allocator capability is installed.

Port:

```kotlin
fun interface ExecutionTargetResolver {
    suspend fun acquire(requirement: ExecutionTargetRequirement): TargetLeaseResult
}
```

No global Jenkins-like executor objects.

## 7. `environment`

Stage declarative environment is a `Context` directive that produces an immutable environment patch.

```text
parent ExecutionContext + EnvironmentPatch -> child ExecutionContext
```

No JVM-global environment mutation.

`withEnv {}` remains a Block Step for lexical/nested scopes.

## 8. `options`

Do not keep a bag of optional fields.

```kotlin
sealed interface StageOption {
    data class Timeout(val duration: Duration) : StageOption
    data class Retry(val attempts: PositiveInt) : StageOption
    data object SkipDefaultCheckout : StageOption
    data object Timestamps : StageOption
}
```

Every option must map to a real policy/interpreter or be rejected.

## 9. External directives

Add:

```kotlin
interface DirectiveDefinitionContributor {
    fun contribute(registry: MutableDirectiveRegistry)
}
```

An external directive library may:

- define codecs;
- select a supported `DirectiveExecutionPolicy` shape;
- declare narrow capabilities;
- emit declared events.

It may not:

- modify coordinator source;
- add a new lifecycle phase;
- bypass admission;
- access journal/event store directly;
- branch on other directive keys;
- mutate global process state.
