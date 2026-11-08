# Step Failure

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> A command that fails must fail the whole run, and the report must say WHICH
> failure it was. The typed 'error' step has a scenario of its own; this one
> covers the other kind, the one a shell brings: the command exits with a code,
> and the run must name that as a SCRIPT failure instead of collapsing it into
> a generic error. An exit-0 expectation here would let an engine that
> swallowed the command's failure pass, and expecting only "it failed" would
> accept any failure at all, which is the difference between a scenario that
> pins the behaviour and one that only pins that something broke.

## Las etapas y los pasos

### 1. `failing-command`

- `sh` — `echo 'the command refuses to succeed' && exit 1`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `Pipeline finished with FAILURE`, `cause [SCRIPT]: shell exited with code 1` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/step-failure/step-failure.pipeline.kts
pipelinek run      examples/catalogue/features/negative/step-failure/step-failure.pipeline.kts
```

## Su linaje

- **Nombre historico:** `30-step-failure.pipeline.kts`
- **Ruta de origen:** `examples/negative/30-step-failure.pipeline.kts`
- **Huella del cuerpo:** `sha256:c571e125e1ef4a7578ab5e8c0456c84d4ffd50b58afb9ce4facfd87dfe4ac842`
- **Huella del origen:** `sha256:6c9b9b156c2211828de29997863969144efe360506c2b301526526687375b6ab`
