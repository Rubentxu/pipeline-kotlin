# Cleanws

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `steps`

**Resultado esperado:** `success`

## Que ejercita

Los pasos del DSL, uno a uno, y que garantiza cada uno

*El ejemplo dice de si mismo:*

> The effect of 'cleanWs' is an ABSENCE: the matched paths must be gone. A
> scenario that only ran the step and checked the exit code passed just as
> happily when the step deleted nothing, and leaving another run's files on
> disk is exactly the failure this step exists to prevent. So the assertion
> has to be about what is NOT there.
> 'test ! -e' rather than 'test ! -f': cleanWs is documented over patterns,
> not over regular files, and a pattern match that removed the directory
> itself would satisfy '-f' on a leftover path differently from '-e'. '-e'
> asks the only question that matters here -- did anything at all survive.

## Las etapas y los pasos

### 1. `cleanws-step`

- `sh` — `mkdir -p test && touch test/a.txt test/b.txt`
- `cleanWs` — `patterns = listOf("test/**")`
- `sh` — `test ! -e test/a.txt && test ! -e test/b.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/steps/cleanWs/cleanWs.pipeline.kts
pipelinek run      examples/catalogue/features/steps/cleanWs/cleanWs.pipeline.kts
```

## Su linaje

- **Nombre historico:** `18-cleanWs.pipeline.kts`
- **Ruta de origen:** `examples/steps/18-cleanWs.pipeline.kts`
- **Huella del cuerpo:** `sha256:254071f0e82d29d33a27740145da41ee526143ab8e1d759834aa2be58fb32207`
- **Huella del origen:** `sha256:254071f0e82d29d33a27740145da41ee526143ab8e1d759834aa2be58fb32207`
