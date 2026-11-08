# Milestone

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `success`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> The subject is a milestone step reached through the open registry rather than
> by a hard-wired call. Each milestone writes a durable trace entry, and the
> two ordinals here must both land before the pipeline reaches the final echo:
> that the run finishes is what tells us neither of them threw.

## Las etapas y los pasos

### 1. `milestones`

- `echo` — `pre-1`
- `milestone` — `ordinal = 1, label = "post-error"`
- `echo` — `between`
- `milestone` — `ordinal = 2, label = "post-build"`
- `echo` — `post-2`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `post-2` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/milestone/milestone.pipeline.kts
pipelinek run      examples/catalogue/features/durable/milestone/milestone.pipeline.kts
```

## Su linaje

- **Nombre historico:** `21-milestone.pipeline.kts`
- **Ruta de origen:** `examples/durable/21-milestone.pipeline.kts`
- **Huella del cuerpo:** `sha256:01d9bc425c54a76f65f741e861ad18aab35354a716828614c7ba961012384de4`
