# Workspace Dir

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `dir`, `nested`, `shell`

**Resultado esperado:** `success`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> dir() subdirectory semantics + effective cwd coherence.
> Enters a subdir via dir(), writes through sh, verifies fileExists there.

## Las etapas y los pasos

### 1. `dir-contract`

- `dir(nested/deep)` — un bloque de directorio
  - `sh` — `mkdir -p out && pwd > out/pwd.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `nested/deep/out/pwd.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/workspace-dir/workspace-dir.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/workspace-dir/workspace-dir.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `workspace-dir.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/workspace-dir.pipeline.kts`
- **Huella del cuerpo:** `sha256:962026f808eb752ba8f416fff4c239238cf46925a3bad4ad5a36ad3e5913438c`
