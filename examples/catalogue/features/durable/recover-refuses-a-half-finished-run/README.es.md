# Recover Refuses A Half Finished Run

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `success`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> 41-recover-refuses-a-half-finished-run.pipeline.kts — recovering a run that did not finish.

## Las etapas y los pasos

### 1. `work`

- `sh` — `echo 'the-step-that-finished'`
- `sh` — `kill -9 \$(cat '$pidFile')`
- `sh` — `echo 'never-reached'`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `nothing was recovered` |

## Que necesita

- durable-once
- recover

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/recover-refuses-a-half-finished-run/recover-refuses-a-half-finished-run.pipeline.kts
pipelinek run      examples/catalogue/features/durable/recover-refuses-a-half-finished-run/recover-refuses-a-half-finished-run.pipeline.kts
```

## Su linaje

- **Nombre historico:** `41-recover-refuses-a-half-finished-run.pipeline.kts`
- **Ruta de origen:** `examples/durable/41-recover-refuses-a-half-finished-run.pipeline.kts`
- **Huella del cuerpo:** `sha256:2a30b1ef9dff368d3e3184876aa3058daa740f9afc0e31dbe57c17ea84840e97`
- **Huella del origen:** `sha256:aca7412a0f64d53b82439e56ac31c73bac9036c732ca4d7f2dbf7251ce7515ba`
