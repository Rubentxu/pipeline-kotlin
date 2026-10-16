# Ejemplo de arquitectura funcional (ilustrativo, no copiar como API pública)

El ejemplo siguiente es intencionalmente pequeño: muestra ADTs + function total + interpreter separado. **Antes de programarlo** se busca la autoridad ya existente en `pipeline-domain`/`pipeline-runtime-api`, por ejemplo ADR-0103 para replay.

```kotlin
sealed interface Input {
    data class OutputChunk(val bytes: ByteArray, val stream: StreamKind) : Input
    data object WorkerLost : Input
}
sealed interface StepDecision {
    data class Append(val chunk: Input.OutputChunk) : StepDecision
    data class Reject(val reason: FailureReason) : StepDecision
}
enum class StreamKind { Stdout, Stderr }
enum class FailureReason { AlreadyTerminal, Unobservable }

// Pure (assuming State is an immutable value):
fun decide(state: State, input: Input): StepDecision = when (input) {
    is Input.OutputChunk ->
        if (state.terminal) StepDecision.Reject(FailureReason.AlreadyTerminal)
        else StepDecision.Append(input)
    Input.WorkerLost -> StepDecision.Reject(FailureReason.Unobservable)
}

// Impure edge is owned by an adapter. It must not re-decide the outcome.
interface OutputPort { suspend fun append(stream: StreamKind, bytes: ByteArray) }
suspend fun interpret(decision: StepDecision, output: OutputPort) = when (decision) {
    is StepDecision.Append -> output.append(decision.chunk.stream, decision.chunk.bytes)
    is StepDecision.Reject -> Unit
}
```

La persistencia real, canalización de secretos, `EffectReplayPolicy` y reconciliación durable son más complejas: no se reimplementan con este ejemplo. Lo que se toma es la forma `decide` pura ↔ `interpret` con efectos y la división entre `StepDecision` y `Observation`.
