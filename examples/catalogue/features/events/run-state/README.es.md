# Run State

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `events`

**Resultado esperado:** `success`

## Que ejercita

La via de eventos: que le cuenta una corrida a un consumidor, y en que orden

*El ejemplo dice de si mismo:*

> 07-run-state.pipeline.kts — what the product says about a run that is over.

## Las etapas y los pasos

### 1. `work`

- `sh` — `echo 'the-step-that-finished'`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- run-state

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/events/run-state/run-state.pipeline.kts
pipelinek run      examples/catalogue/features/events/run-state/run-state.pipeline.kts
```

## Su linaje

- **Nombre historico:** `07-run-state.pipeline.kts`
- **Ruta de origen:** `examples/events/07-run-state.pipeline.kts`
- **Huella del cuerpo:** `sha256:8b4a7ef4313fcbb2d07c9c32bad6f1a9d9d2559bb4be083b2f4b815133c9575d`
