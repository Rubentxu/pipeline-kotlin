# SPEC-LFC-016 — Step Constitution

**Status:** proposed

## Objective

Fijar una única norma para Steps DSL y plugins.

## Canonical route

```text
typed façade
 -> typed Step input
 -> canonical ExecutionNode.Invoke
 -> StepRegistry.resolve
 -> decode + contract validation
 -> capability admission
 -> runtime adapter
 -> typed StepHandler
 -> durable services / BodyInvoker
 -> StepResult
 -> events/output/journal
```

No existe una segunda ruta.

## DSL façade

La façade declarativa:

- construye data;
- valida invariantes puros;
- emite una invocación;
- no realiza I/O;
- no lee host cwd/env/OS;
- no inventa resultados runtime.

Runtime values pertenecen a `script {}` durable.

## StepDefinition

```kotlin
interface StepDefinition<I : Any, O : Any> {
    val key: StepKey
    val contractVersion: ContractVersion
    val contract: StepContract
    val inputCodec: StepCodec<I>
    val outputCodec: StepCodec<O>
    val handler: StepHandler<I, O>
}
```

## StepContract mínimo

```kotlin
data class StepContract(
    val executionLocation: ExecutionLocation,
    val effects: Set<Effect>,
    val replay: ReplayPolicy,
    val capabilities: Set<CapabilityId>,
    val body: BodyContract,
    val failure: FailureContract,
    val observability: ObservabilityContract,
    val compatibility: ContractCompatibility,
)
```

Todo valor finito usa enum/sealed ADT/value class.

## Capabilities

Handlers expresan dependencias mínimas, preferentemente con context parameters:

```kotlin
context(
    process: ProcessCapability,
    output: OutputCapability,
    workspace: WorkspaceCapability,
)
suspend fun executeSh(input: ShInput): StepResult<ShResult>
```

No `PipelineContext` omnipotente.

## Payload completeness

Toda semántica runtime debe sobrevivir compilación.

Ejemplo:

```kotlin
data class RetryInput(
    val maxAttempts: PositiveInt,
    val delay: RetryDelay,
)

data class TimeoutInput(
    val duration: PositiveDuration,
    val activity: Boolean,
)
```

Perder retry count o timeout config en `{}` es un defecto de compilación.

## Smart constructors

Constructores de configuración son puros.

Correcto:

```kotlin
fun scmGit(...): ScmSpec = ScmSpec(...)
fun git(...): Unit = checkout(scmGit(...))
```

Prohibido construir y emitir a la vez.

También está prohibido `retry` como mutación del último Step.

## Runtime-valued operations

Declarative:

```kotlin
steps {
    sh("./gradlew build")
}
```

Scripted:

```kotlin
script {
    val branch = shStdout("git branch --show-current").trim()
    if (branch == "main") {
        sh("./publish.sh")
    }
}
```

El resultado scripted es durable, serializable y replay-aware.

## Conditions

Declarative conditions son data serializable:

```kotlin
sealed interface StageCondition {
    data class Branch(val pattern: BranchPattern) : StageCondition
    data class Environment(val name: EnvName, val value: String) : StageCondition
    data class All(val children: NonEmptyList<StageCondition>) : StageCondition
    data class Any(val children: NonEmptyList<StageCondition>) : StageCondition
    data class Not(val child: StageCondition) : StageCondition
}
```

Lambda Kotlin arbitraria con valores runtime pertenece a scripted.

## KSP

Puede generar:

- descriptor;
- codecs/schema;
- façade;
- registry provider;
- manifest;
- IDE/LSP;
- docs;
- TestKit glue.

No puede contener semántica seleccionada por nombre de Step.

## Fail closed

Falla antes de side effects:

- unknown Step;
- schema mismatch;
- body shape inválido;
- capability ausente;
- plugin incompatible;
- unsupported semantics.

Nunca no-op, comment, placeholder o fake success.

## Fitness mandatory

CI debe detectar:

- runtime import de DSL StepSpec;
- central concrete Step switch;
- KSP known-name table;
- manual global plugin Step list;
- declarative fake result;
- global cwd/env/process access;
- capability utilizada pero no declarada.
