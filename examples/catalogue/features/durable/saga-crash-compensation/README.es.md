# Saga Crash Compensation

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `failure`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> A Saga whose COMPENSATION happened and whose answer was LOST.

## Las etapas y los pasos

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", actions = listOf( SagaChainScript( actionId = "reserve", compensat...` — un paso que aporta este plugin (`example-saga-plugin`)
- `post` — el bloque de pasos que se ejecutan tras la etapa
  - `always` — el bloque de pasos que siempre se ejecutan
    - `sh` — `"test \"$(cat '$home/effects.log')\" = " + "\"$(printf 'reserve\\ncharge\\nrefund')\" "...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `the write-ahead for reserve was refused`, `saga [checkout]: NEEDS_INTERVENTION (COMPENSATION_UNCERTAIN)` |

## Que necesita

- resume

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/saga-crash-compensation/saga-crash-compensation.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-crash-compensation/saga-crash-compensation.pipeline.kts
```

## Su linaje

- **Nombre historico:** `36-saga-crash-compensation.pipeline.kts`
- **Ruta de origen:** `examples/durable/36-saga-crash-compensation.pipeline.kts`
- **Huella del cuerpo:** `sha256:b3da7fbf41a4be0918a91b1c295225e5c2247cf7b692ce16e1cff14d28410e79`
