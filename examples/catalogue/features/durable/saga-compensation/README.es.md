# Saga Compensation

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `failure`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> A Saga CHAIN, compensated by the engine, in a real process with a real SQLite.

## Las etapas y los pasos

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", actions = listOf( SagaChainScript( actionId = "reserve", compensat...` — un paso que aporta este plugin (`example-saga-plugin`)
- `post` — el bloque de pasos que se ejecutan tras la etapa
  - `always` — el bloque de pasos que siempre se ejecutan
    - `sh` — `"test \"$(cat '$home/effects.log')\" = " + "\"$(printf 'reserve\\ncharge\\nrefund\\nrel...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `the action ship failed`, `saga [checkout]: SETTLED` |

## Que necesita

- durable-once

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/saga-compensation/saga-compensation.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-compensation/saga-compensation.pipeline.kts
```

## Su linaje

- **Nombre historico:** `33-saga-compensation.pipeline.kts`
- **Ruta de origen:** `examples/durable/33-saga-compensation.pipeline.kts`
- **Huella del cuerpo:** `sha256:be3347a4c002bcad25c1d49aca195c07708face5dcab946540b52337250c93d3`
