# Branch Failure Reported

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `process`, `shell`, `cross_stage`

**Resultado esperado:** `failure`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> The subject is what a FAILED branch leaves behind: the side effect it
> produced before failing stays, and the failure is named.

## Las etapas y los pasos

### 1. `branches`

- `parallel` — un bloque paralelo
  - `branch(left)` — una rama de un bloque paralelo
    - `sh` — `mkdir -p /workspace/out && printf 'left\\n' > /workspace/out/canary-branch-left.txt`
  - `branch(right)` — una rama de un bloque paralelo
    - `sh` — `"mkdir -p /workspace/out && printf 'right\\n' > /workspace/out/canary-branch-right.txt...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| ficheros | `out/canary-branch-right.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/branch-failure-reported/branch-failure-reported.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/branch-failure-reported/branch-failure-reported.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `branch-failure-reported.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/branch-failure-reported.pipeline.kts`
- **Huella del cuerpo:** `sha256:302da316e6c6fb4a33bb2d86910279d64357d62cd0f988782a13c0426ba2739f`
