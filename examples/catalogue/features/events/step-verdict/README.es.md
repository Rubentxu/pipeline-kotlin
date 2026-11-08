# Step Verdict

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `events`

**Resultado esperado:** `failure`

## Que ejercita

La via de eventos: que le cuenta una corrida a un consumidor, y en que orden

*El ejemplo dice de si mismo:*

> THE GUARANTEE UNDER TRIAL

## Las etapas y los pasos

### 1. `verdict`

- `sh` — `echo 'this one works'`
- `sh` — `echo 'this one refuses' && exit 3`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |

## Que necesita

- step-verdict

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/events/step-verdict/step-verdict.pipeline.kts
pipelinek run      examples/catalogue/features/events/step-verdict/step-verdict.pipeline.kts
```

## Su linaje

- **Nombre historico:** `02-step-verdict.pipeline.kts`
- **Ruta de origen:** `examples/events/02-step-verdict.pipeline.kts`
- **Huella del cuerpo:** `sha256:c7bdd4a59e300fa6f3480660ed38df46cf374403be597ab705c19fe25af53e2a`
