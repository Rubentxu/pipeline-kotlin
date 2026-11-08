# Workspace Clean

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `file_io`

**Resultado esperado:** `success`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> The subject is an ABSENCE: 'cleanWs' removes the paths it matches, and
> what it does NOT match has to survive.

## Las etapas y los pasos

### 1. `clean`

- `sh` — `"mkdir -p scratch && printf 'gone\\n' > scratch/gone.txt && printf 'kept\\n' > scratch/...`
- `cleanWs` — `patterns = listOf("scratch/gone.txt")`
- `sh` — `test ! -e scratch/gone.txt && test -e scratch/kept.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `scratch/kept.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/workspace-clean/workspace-clean.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/workspace-clean/workspace-clean.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `workspace-clean.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/workspace-clean.pipeline.kts`
- **Huella del cuerpo:** `sha256:ebb70060552de015fe0c314faad21cdf2d1d91c13b1966d0deeed46e0a6ad364`
