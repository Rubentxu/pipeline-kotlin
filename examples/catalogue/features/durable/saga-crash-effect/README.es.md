# Saga Crash Effect

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `failure`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> A Saga whose forward effect HAPPENED and whose answer was LOST.

## Las etapas y los pasos

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", actions = listOf( SagaChainScript( actionId = "reserve", compensat...` — un paso que aporta este plugin (`example-saga-plugin`)
- `post` — el bloque de pasos que se ejecutan tras la etapa
  - `always` — el bloque de pasos que siempre se ejecutan
    - `sh` — `"test \"$(cat '$home/effects.log')\" = \"reserve\" " + "|| { echo 'SAGA-REPEATED-OR-MAD...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `the write-ahead for reserve was refused`, `saga [checkout]: NEEDS_INTERVENTION (UNKNOWN_EFFECT)` |

## Que necesita

- resume

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/saga-crash-effect/saga-crash-effect.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-crash-effect/saga-crash-effect.pipeline.kts
```

## Su linaje

- **Nombre historico:** `35-saga-crash-effect.pipeline.kts`
- **Ruta de origen:** `examples/durable/35-saga-crash-effect.pipeline.kts`
- **Huella del cuerpo:** `sha256:fea0fce3935f2318739e675d30b7a29178c052ba0f8a6328a631ed87620986e0`
