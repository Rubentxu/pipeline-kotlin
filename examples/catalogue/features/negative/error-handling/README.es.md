# Error Handling

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `unstable`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

## Las etapas y los pasos

### 1. `error-and-milestone`

- `warnError(intentionally flaky section)` — un bloque de aviso
  - `catchError` — un bloque de pasos de recuperacion
    - `sh` — `exit 1`
- `milestone` — `ordinal = 1, label = "post-error"`

### 2. `post-milestone`

- `echo` — `milestone reached`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `unstable` |
| stdout contiene | `milestone reached` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/error-handling/error-handling.pipeline.kts
pipelinek run      examples/catalogue/features/negative/error-handling/error-handling.pipeline.kts
```

## Su linaje

- **Nombre historico:** `12-error-handling.pipeline.kts`
- **Ruta de origen:** `examples/negative/12-error-handling.pipeline.kts`
- **Huella del cuerpo:** `sha256:0a5ba7e4bd985d779dd9f33c1ad758d9c896923aac134c8fc87269ad770acf65`
