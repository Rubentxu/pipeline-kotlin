# Scripted Control Flow

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `control-flow`

**Resultado esperado:** `success`

## Que ejercita

Que rama de una bifurcacion se ejecuta, y cuantas veces corre el cuerpo de un bucle

*El ejemplo dice de si mismo:*

> Two scripted control flows in one scenario: a branch that takes one arm, and
> a loop whose body must run a known number of times.
> The branch is observable in what the pipeline PRODUCED: the other arm would
> print a different sentence, so a branch that took the wrong way is visible
> rather than merely a script that ran.
> The loop count is observed by counting the lines the body wrote. A bare
> echo per iteration would stay green for a body that ran once, or not at
> all, and a loop that ran three times when it should have run five is
> exactly the regression the count is there to catch.

## Las etapas y los pasos

### 1. `scripted-control-flow`

- `script` — un bloque de Kotlin
  - `echo` — `x is positive`
  - `echo` — `x is not positive`
- `sh` — `"""for i in 1 2 3; do echo "iteration ${'$'}i"; done > iterations.txt""", isScriptBlock...`
- `sh` — `"""test "${'$'}(wc -l < iterations.txt)" -eq 3"""`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `x is positive` |
| la transcripcion contiene | `x is positive` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/control-flow/scripted-control-flow/scripted-control-flow.pipeline.kts
pipelinek run      examples/catalogue/features/control-flow/scripted-control-flow/scripted-control-flow.pipeline.kts
```

## Su linaje

- **Nombre historico:** `05-scripted-control-flow.pipeline.kts`
- **Ruta de origen:** `examples/control-flow/05-scripted-control-flow.pipeline.kts`
- **Huella del cuerpo:** `sha256:e8e81b23af0eb4b38f0458c4dfb04715116e7d39498e7e3d7a4b82a2097df15c`
