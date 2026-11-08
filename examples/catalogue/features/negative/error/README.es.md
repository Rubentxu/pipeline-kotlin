# Error

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> The 'error' Step is the subject: it must fail the run. An exit-0
> expectation here would let an engine that swallowed the typed USER
> failure pass. The substrings pin the report, not just the outcome: the
> outcome line says what happened, and the cause line says WHY and of which
> kind, which is the difference between a report a person can act on and one
> they have to guess about. Both execution paths print the same report, so
> both of them are asserted by the same scenario.

## Las etapas y los pasos

### 1. `error-step`

- `error` — `"test error message", failureKind = FailureKind.USER`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `Pipeline finished with FAILURE`, `cause [USER]: test error message` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/error/error.pipeline.kts
pipelinek run      examples/catalogue/features/negative/error/error.pipeline.kts
```

## Su linaje

- **Nombre historico:** `15-error.pipeline.kts`
- **Ruta de origen:** `examples/negative/15-error.pipeline.kts`
- **Huella del cuerpo:** `sha256:fdab90aa92ad06426ee78579bbb3bda104dffc4f3f336452fc947228119be304`
- **Huella del origen:** `sha256:b2aac33f9c7aa4540e532931d1a9ea17c26b709bd01038082d940cff12d5ea33`
