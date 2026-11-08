# Failure

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `process`, `shell`

**Resultado esperado:** `failure`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> Negative twin: expected shell failure, PipelineK should exit 1 with SCRIPT failure.

## Las etapas y los pasos

### 1. `intentional-failure`

- `sh` — `exit 7`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/failure/failure.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/failure/failure.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `failure.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/failure.pipeline.kts`
- **Huella del cuerpo:** `sha256:5d306a88010684f97843379478b2ce09142212a3560299588b1cd902e8d7e317`
