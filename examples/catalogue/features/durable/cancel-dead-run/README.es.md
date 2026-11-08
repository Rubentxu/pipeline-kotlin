# Cancel Dead Run

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `success`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> 39-cancel-dead-run.pipeline.kts — closing a run whose writer was killed.

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
| stdout contiene | `cancelled run` |

## Que necesita

- durable-once
- cancel

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/cancel-dead-run/cancel-dead-run.pipeline.kts
pipelinek run      examples/catalogue/features/durable/cancel-dead-run/cancel-dead-run.pipeline.kts
```

## Su linaje

- **Nombre historico:** `39-cancel-dead-run.pipeline.kts`
- **Ruta de origen:** `examples/durable/39-cancel-dead-run.pipeline.kts`
- **Huella del cuerpo:** `sha256:1786c3cd8e205b5a060d35589fd5bdda041fa4d3720cf28cee4d4396d3598484`
