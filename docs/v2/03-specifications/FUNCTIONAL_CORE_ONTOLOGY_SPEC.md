# SPEC-PK-QV-03 — Ontología y arquitectura funcional (estilo Haskell sobre Kotlin)

**Estado:** PROPOSED. Complementa `STEP_CONSTITUTION.md`, `DSL_EXECUTION_HARDENING.md`, `RECOVERY_DURABILITY.md`, `ADR-0103-one-replay-authority.md`.

## Lenguaje ubicuo canónico

| Concepto | Definición | Propietario |
|---|---|---|
| `Capability` | comportamiento que el producto promete al usuario | dominio/contrato de producto existente |
| `ExecutionNode` | estructura cerrada que describe el plan, no un Step concreto | `pipeline-domain` (ya existe) |
| `StepDefinition` | extensión abierta descubierta por registry; no clase especial del motor | SDK/domain existentes |
| `EffectIntent` | descripción inerte de efecto a realizar, no su ejecución | dominio de la capacidad, solo si hace falta |
| `Decision` | resultado total, puro, al aplicar una regla a hechos | modelo de dominio |
| `Reconciliation` | única autoridad de recuperación para estado durable | según ADR-0103, no duplicar |
| `RunOutcome` | término del modelo de ejecución ya existente | `pipeline-domain`, no crear clon |
| `Observation` | datos captados desde mundo externo sin decidir outcome | adapter/puerto de observación |
| `Scenario` | testigo concreto de un contrato | testkit/corpus |
| `Evidence` | resultado medido, asociado a commit y artefacto | harness de tests |
| `ReleaseCandidate` | bytes y manifiesto de artefacto, no estado de certificación | pipeline-release |

## Forma arquitectónica

```
Input externo ──parse/validate──> ADT de hechos ──[decide: puro]──> Decision/EffectIntent
                                                               │
                                                        [interpret: I/O]
                                                               │
                                                            Observation
                                                               │
                                                          reducer puro
```

Tipos: valores inmutables; preferir `sealed interface` y clases de valor con validación; `when` sobre ADTs cerrado y exhaustivo; explícito `None`/`Missing`/`Unknown` cuando semánticas distintas. Fallos esperables son ramas de un ADT, no excepciones con mensaje regex. Excepciones solo en borde no recuperable.

## Reglas de pureza y duplicación

- Prohibido `Instant.now()`, random, Git, DB, println, `ProcessBuilder` o filesystem en la función de decisión. Pasar `Clock`/`Seed`/snapshot como valor de entrada en adapter.
- Product side effects solo en interpreter, una vez según política de durable replay. Recuperación no repite efectos cuya observación ya posee una autoridad durable.
- La DSL estructural es un ADT cerrado; registry Step abierto. No ampliar `when(stepKey)` ni switch sobre clases de plugin. No encajar bodies dentro de shell si el lenguaje no puede expresar semántica del cuerpo.
- No serializar tipos UI/renderer como estado durable; mantener wire versionado y usar conversiones explícitas.
- No añadir segundo criterio de `RunOutcome`, segunda comprobación de secret redaction ni dos validadores de `ExecutionLocation`; extraer función pura propietaria y llamarla desde CLI, coordinator, SDK.
- No reescribir clases grandes por estética: extraer **una función de decisión probada** por ciclo y medir reducción de ramas duplicadas, efectos en código de dominio y ficheros responsables.

## Ejemplo de forma de transición

```kotlin
sealed interface RecoveryObservation {
    data object NoCheckpoint : RecoveryObservation
    data class Stored(val fingerprint: String) : RecoveryObservation
    data class UnsupportedSchema(val version: Int) : RecoveryObservation
}
sealed interface RecoveryDecision {
    data object FirstRun : RecoveryDecision
    data object Resume : RecoveryDecision
    data class Refuse(val reason: String) : RecoveryDecision
}
fun decideRecovery(observed: RecoveryObservation, expectedFingerprint: String): RecoveryDecision =
    when (observed) {
        RecoveryObservation.NoCheckpoint -> RecoveryDecision.FirstRun
        is RecoveryObservation.Stored ->
            if (observed.fingerprint == expectedFingerprint) RecoveryDecision.Resume
            else RecoveryDecision.Refuse("fingerprint-divergence")
        is RecoveryObservation.UnsupportedSchema -> RecoveryDecision.Refuse("unsupported-schema")
    }
```

**Advertencia:** pseudoejemplo de forma y naming. PipelineK **ya tiene** reglas y ADTs de replay: no introducir estos tipos en producción si duplican `ADR-0103` y `pipeline-domain`. El objetivo es aplicar la forma a la autoridad existente.

## Leyes mínimas

Determinismo por input; `decode(encode(x)) == x` para campos soportados; unknown durable → reject; ausencia de `EffectIntent` ante rechazo; replay de checkpoint terminal no crea nuevos side effects; cancelación es distinta de timeout; identidad y resultado no se convierten de strings por convención. Cada ley tiene una mutación que la prueba detecta en nivel más bajo fiel.
