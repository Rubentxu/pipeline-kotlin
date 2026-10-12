# PK-SPEC-02 — Runtime observable e inspeccionable, sin dependencia Fabric

**Owner**: [PipelineK](https://github.com/Rubentxu/pipeline-kotlin). Consumidor: [Fabric](https://github.com/Rubentxu/pipelinek-fabric). Complementa `SPEC-01-PIPELINEK-LIVE-PLANES.md` y `coordination/INTERFACE_CONTRACT.md`.

## Resultado de producto

Un consumidor externo puede inspeccionar la ejecución de un run aún vivo, recuperar lectores después de SIGKILL, recibir hechos y bytes incrementales sin reejecutar operaciones, y conocer si la ejecución es recuperable, concluida o no observable.

## Interfaces estrictamente segregadas

- `RuntimeIntrospectionPort`: lectura pura de estado efectivo del runtime por run/attempt, sin mutar journal.
- `RuntimeControlPort`: cancelación/inicio/recovery por identidad con decisiones fail-closed.
- `EventReadPort`: replay de secuencia y terminalidad del stream.
- `OutputReadPort` y `OutputTailPort` existentes: offset, lectura por rango y sellado.
- `CommitWakeupPort`: hints no durables que despiertan lectores; pérdida de wakeup jamás afecta corrección.
- `RetentionPinPort` o equivalente solo si se demuestra necesario en M5; la política local no nombra S3 ni Fabric.

**Prohibido:** agregar un `ExecutionEverythingPort` o exponer `SegmentOutputStore`/`OperationJournal` privados como ABI pública solo para ahorrar un adaptador. Reutilizar interfaces existentes antes de crear nuevas.

## Modelo funcional (pseudocódigo Kotlin, no API impuesta)

```kotlin
sealed interface RuntimeObservation {
    data class Running(val attempt: AttemptId, val process: ProcessRef?) : RuntimeObservation
    data class RecoveredTerminal(val attempt: AttemptId, val terminal: TerminalObservation) : RuntimeObservation
    data class Lost(val attempt: AttemptId, val evidence: LossEvidence) : RuntimeObservation
    data class Unobservable(val reason: InspectionFailure) : RuntimeObservation
}
sealed interface RecoveryChoice {
    data class Reattach(val attempt: AttemptId) : RecoveryChoice
    data class ReuseTerminal(val receipt: TerminalReceipt) : RecoveryChoice
    data class FailClosed(val cause: RecoveryFailure) : RecoveryChoice
}
fun decideRecovery(observation: RuntimeObservation, journal: JournalProof): RecoveryChoice = TODO("pure total function")
```

La decisión se prueba por tabla exhaustiva. Los adapters effects interpretan `Reattach` o `ReuseTerminal` con journaling existente; nunca convertir un `Unobservable` en `start` automático. Nombres no congelados: confrontar con ADTs ya existentes antes de implementar.

## Aceptación / Perf

- SIGKILL supervisor ≠ SIGKILL child; un run de despliegue no ejecuta su side effect dos veces.
- Lector externo no llama `recover()` destructivamente durante writer vivo; streams `Open` vs `Sealed` distinguidos.
- No generar un thread nuevo por frame ni un proceso CLI por página del adapter; backpressure de red nunca en bombas de proceso.
- Cualquier coste de retención/cache se mide por bytes y `fds`, no por recuento de páginas solamente.
- ABI publicada compilable por Fabric real con variante legacy y nueva en tests de compat.
