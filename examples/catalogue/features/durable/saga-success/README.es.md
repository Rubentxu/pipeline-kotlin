# Saga Success

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `success`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> A Saga CHAIN that SUCCEEDS, in a real process — and in BOTH storage worlds.

## Las etapas y los pasos

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", actions = listOf( SagaChainScript( actionId = "reserve", compensat...` — un paso que aporta este plugin (`example-saga-plugin`)
- `post` — el bloque de pasos que se ejecutan tras la etapa
  - `success` — un bloque
    - `sh` — `"test \"$(cat '$home/effects.log')\" = " + "\"$(printf 'reserve\\ncharge')\" " + "|| {...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stderr contiene | `saga [checkout]: SETTLED` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/saga-success/saga-success.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-success/saga-success.pipeline.kts
```

## Su linaje

- **Nombre historico:** `38-saga-success.pipeline.kts`
- **Ruta de origen:** `examples/durable/38-saga-success.pipeline.kts`
- **Huella del cuerpo:** `sha256:8cd7c8ea2b6654d889fd27533baca458cc47231be66e73b3d24b1bac52d31a3c`
