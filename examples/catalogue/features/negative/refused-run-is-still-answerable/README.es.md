# Refused Run Is Still Answerable

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> 35-refused-run-is-still-answerable.pipeline.kts — a run refused before it starts can still be asked about.

## Las etapas y los pasos

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `Plugin admission refused for` |

## Que necesita

- plugin-jar
- run-state

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/refused-run-is-still-answerable/refused-run-is-still-answerable.pipeline.kts
pipelinek run      examples/catalogue/features/negative/refused-run-is-still-answerable/refused-run-is-still-answerable.pipeline.kts
```

## Su linaje

- **Nombre historico:** `35-refused-run-is-still-answerable.pipeline.kts`
- **Ruta de origen:** `examples/negative/35-refused-run-is-still-answerable.pipeline.kts`
- **Huella del cuerpo:** `sha256:7e46b1db853a247eeac9cb457df52faf0fc2cf35175ec21f1fed25debf2a4479`
- **Huella del origen:** `sha256:c1b92d0e4f1c23502fd955dc54f7366043b1e945c9ca87666a74c3837cfd1df9`
