# Workspace Contract Matrix

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `dir`, `nested`, `cross_stage`, `shell`

**Resultado esperado:** `success`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> Regresion externa del defecto "workspace contract" en PipelineK.
> Sintomas reportados antes de la rc1:
> sh() cwd no coincide con dir() block, o dir() no persiste cwd entre etapas.

## Las etapas y los pasos

### 1. `dir-nested-3`

- `dir(a/b/c)` — un bloque de directorio
  - `sh` — `"mkdir -p /workspace/out && pwd > /workspace/out/wu-rp-053-cwd-a-b-c.txt && grep -q '/w...`

### 2. `read-cross-stage`

- `sh` — `"cat /workspace/a/b/c/file.txt > /workspace/out/wu-rp-053-readback.txt && grep -qx one...`

### 3. `parallel-branches`

- `parallel` — un bloque paralelo
  - `branch(left)` — una rama de un bloque paralelo
    - `sh` — `"mkdir -p /workspace/out && printf 'left-branch-payload\\n' > /workspace/out/wu-rp-053-...`
  - `branch(right)` — una rama de un bloque paralelo
    - `sh` — `"mkdir -p /workspace/out && printf 'right-branch-payload\\n' > /workspace/out/wu-rp-053...`

### 4. `dir-reattach`

- `dir(x/y)` — un bloque de directorio
  - `sh` — `"pwd > /workspace/out/wu-rp-053-cwd-x-y.txt && grep -q '/workspace/x/y' /workspace/out/...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/wu-rp-053-cwd-a-b-c.txt`, `out/wu-rp-053-lines.txt`, `out/wu-rp-053-readback.txt`, `out/wu-rp-053-branch-left.txt`, `out/wu-rp-053-branch-right.txt`, `out/wu-rp-053-cwd-x-y.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/workspace-contract-matrix/workspace-contract-matrix.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/workspace-contract-matrix/workspace-contract-matrix.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

**Cubre:** `HAR-004`

## Su linaje

- **Nombre historico:** `wu-rp-053-workspace-contract.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/wu-rp-053-workspace-contract.pipeline.kts`
- **Huella del cuerpo:** `sha256:9de75c6ee5931cc3e8f940c49fbb8c17cef733fe506f54535263bfb59fd89541`
