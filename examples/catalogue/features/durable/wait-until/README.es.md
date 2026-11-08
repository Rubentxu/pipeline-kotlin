# Wait Until

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `success`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> 22-wait-until.pipeline.kts — installed-CLI canary
> Real .pipeline.kts using the structural waitUntil block through the canonical
> dispatch path (BlockStepNode + BodyExecutionPolicy.RepeatUntil + dispatchRepeatUntilBody).
> Validates that after the registry removal and the durable journal, the DSL
> 'waitUntil(initialRecurrencePeriod) { body }' produces a correct durable trace with
> WaitUntilPolled (≥1) + WaitUntilCompleted(origin=canonical).
> '//@ durable' makes the launcher run this pipeline twice against one control
> root. That is the property this scenario can actually speak for and no other
> can: a body that already reached WaitUntilCompleted must not be polled again
> on the replay, and must not fail for having done nothing new. A single run
> cannot observe it, which is why the replay branch of the launcher existed
> without ever being reached.
> The marker used to be the absolute path /tmp/marker-wu-g5-restore. The
> comment above it claimed the path was "unique to this corpus to avoid
> collision with other test runs", which was the opposite of what the code did:
> a fixed name in a shared directory is precisely what two concurrent runs
> collide on. It is now relative, so it resolves inside this stage's own
> workspace and the claim is true.

## Las etapas y los pasos

### 1. `wait-until`

- `sh` — `touch marker-wu-g5-restore`
- `waitUntil(initialRecurrencePeriod = 100L)` — un bloque de espera
  - `sh` — `test -f marker-wu-g5-restore && echo READY`
- `sh` — `rm marker-wu-g5-restore`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- durable

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/wait-until/wait-until.pipeline.kts
pipelinek run      examples/catalogue/features/durable/wait-until/wait-until.pipeline.kts
```

## Su linaje

- **Nombre historico:** `22-wait-until.pipeline.kts`
- **Ruta de origen:** `examples/durable/22-wait-until.pipeline.kts`
- **Huella del cuerpo:** `sha256:72fe1a49b698d95ad3b0aa647ae0b2eb7cc7f072969ed3c3afb4767a34a4b783`
