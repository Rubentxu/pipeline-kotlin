# Catcherror Handled

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `process`, `shell`, `cross_stage`

**Resultado esperado:** `unstable`

## Que ejercita

La vida de una corrida: como arranca, como acaba y que concluye

*El ejemplo dice de si mismo:*

> The subject is a CAUGHT failure: the run continues, and the run's
> outcome says so.

## Las etapas y los pasos

### 1. `handled`

- `catchError` — un bloque de pasos de recuperacion
  - `sh` — `exit 1`
- `sh` — `mkdir -p /workspace/out && printf 'caught\\n' > /workspace/out/canary-caught.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `unstable` |
| ficheros | `out/canary-caught.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/catcherror-handled/catcherror-handled.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/catcherror-handled/catcherror-handled.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `catcherror-handled.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/catcherror-handled.pipeline.kts`
- **Huella del cuerpo:** `sha256:473b5721efd7e67905ea7b0fb2d536fdcbe5ab611c7293fff5ac38437be58864`
