# Repeat Block

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `plugin`, `process`, `shell`

**Resultado esperado:** `success`

## Que ejercita

Pasos que aporta un plugin, y las capacidades que declara

*El ejemplo dice de si mismo:*

> The subject is a COUNT: the body of a repeated block runs exactly as
> many times as the block was asked for.

## Las etapas y los pasos

### 1. `repeat`

- `repeatBlock(2)` — un bloque repetido (`example-block-plugin`)
  - `sh` — `echo iteration | tee -a iterations.txt`
- `sh` — `"test \"$(wc -l < iterations.txt)\" -eq 2"`
- `sh` — `mkdir -p /workspace/out && cp iterations.txt /workspace/out/canary-iterations.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/canary-iterations.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- el plugin de bloque repetible (example.repeat) viene en la imagen de la candidata

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/repeat-block/repeat-block.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/repeat-block/repeat-block.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `repeat-block.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/repeat-block.pipeline.kts`
- **Huella del cuerpo:** `sha256:20d70e707f2d1fa529428df8cc14a28f90f3106157b597aa2ed84523393b7edc`
