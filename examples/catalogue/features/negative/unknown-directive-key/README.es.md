# Unknown Directive Key

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> A directive key that no contributor registers must fail CLOSED and name the
> key, never be skipped.
> This is the other half of plugins/lock-directive.pipeline.kts. That scenario
> would stay green if admission turned fail-OPEN and simply ignored the whole
> directives block; this one goes red in the same circumstance, and it also
> pins the specific refusal rather than accepting any nonzero exit, so a
> compiler error or an unrelated crash cannot stand in for the real guarantee.

## Las etapas y los pasos

### 1. `Unknown directive`

- `directives` — un bloque
  - `directive` — `"acme.no.such.directive", """{}"""`
- `sh` — `echo should never run`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `unresolved directive 'acme.no.such.directive'` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/unknown-directive-key/unknown-directive-key.pipeline.kts
pipelinek run      examples/catalogue/features/negative/unknown-directive-key/unknown-directive-key.pipeline.kts
```

## Su linaje

- **Nombre historico:** `unknown-directive-key.pipeline.kts`
- **Ruta de origen:** `examples/negative/unknown-directive-key.pipeline.kts`
- **Huella del cuerpo:** `sha256:d92c777ee392d34ab1e9c32759413cfe0a15a3a0253fa6c759b83cbe17930c6d`
