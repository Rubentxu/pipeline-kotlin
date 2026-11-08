# Toolchain Inventory

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `process`, `shell`

**Resultado esperado:** `success`

## Que ejercita

La vida de una corrida: como arranca, como acaba y que concluye

*El ejemplo dice de si mismo:*

> The subject is the TOOLCHAIN the image provides: it has to be there,
> and the version it reports has to be recorded.

## Las etapas y los pasos

### 1. `tools`

- `sh` — `"mkdir -p /workspace/out && python3 --version > /workspace/out/canary-python3.txt 2>&1...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/canary-python3.txt`, `out/canary-cargo.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- la imagen trae python3 y cargo (el canario falla si falta alguna)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/toolchain-inventory/toolchain-inventory.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/toolchain-inventory/toolchain-inventory.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `toolchain-inventory.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/toolchain-inventory.pipeline.kts`
- **Huella del cuerpo:** `sha256:6d8c1e7c4108e12a19eba2912993ad5df8686f12a083d6173e2149ea80dd91b8`
