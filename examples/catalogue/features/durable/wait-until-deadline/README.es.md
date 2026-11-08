# Wait Until Deadline

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `failure`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> 23-wait-until-deadline.pipeline.kts — a waitUntil whose condition never holds ENDS.

## Las etapas y los pasos

### 1. `wait-until-deadline`

- `waitUntil(initialRecurrencePeriod = 50L, maxBackoffMs = 200L)` — un bloque de espera
  - `sh` — `false`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `Pipeline finished with FAILURE`, `cause [TIMEOUT]: waitUntil deadline exceeded at poll 3 (200ms backoff ceiling)` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/wait-until-deadline/wait-until-deadline.pipeline.kts
pipelinek run      examples/catalogue/features/durable/wait-until-deadline/wait-until-deadline.pipeline.kts
```

## Su linaje

- **Nombre historico:** `23-wait-until-deadline.pipeline.kts`
- **Ruta de origen:** `examples/durable/23-wait-until-deadline.pipeline.kts`
- **Huella del cuerpo:** `sha256:e76d593baa47148f5cd6eb6d090694fcafa66b6cc9b10d1b9d4ab59628ec2416`
- **Huella del origen:** `sha256:887f73a035fa30f48cc4c62a587988d85f29bf277d62d9bddf67370b9253b014`
