# Workspace Contract

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `dir`, `process`, `shell`, `cross_stage`

**Resultado esperado:** `success`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> Regression canary for the workspace contract (the fix shipped in 0.39.1-rc1).
> Verifies that sh() runs with cwd == /workspace (the workspace contract).

## Las etapas y los pasos

### 1. `workspace-contract`

- `sh` — `"mkdir -p out && (pwd > out/pwd.txt; grep -qx /workspace out/pwd.txt && printf 'cwd-ok\...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/pwd.txt`, `out/cwd-marker.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/workspace-contract/workspace-contract.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/workspace-contract/workspace-contract.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `workspace-contract.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/workspace-contract.pipeline.kts`
- **Huella del cuerpo:** `sha256:df522da9447e8adc72569d58f22c2d250fe923831c8176cb7ec5e3f7eeed054f`
