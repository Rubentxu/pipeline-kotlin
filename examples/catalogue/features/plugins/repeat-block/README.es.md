# Repeat Block

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `plugins`

**Resultado esperado:** `success`

## Que ejercita

Los pasos que aporta un plugin, y las capacidades que declara

*El ejemplo dice de si mismo:*

> The capability under trial is the REPEAT COUNT, and a count needs no store:
> how many times the body ran is decided by the block, and the file it wrote
> is the witness. The directive says so, and the launcher refuses this
> scenario if it ever needs the store to keep its promise.

## Las etapas y los pasos

### 1. `Repeat`

- `repeatBlock(2)` — un bloque repetido (`example-block-plugin`)
  - `sh` — `echo iteration | tee -a iterations.txt`
- `sh` — `"test \"$(wc -l < iterations.txt)\" -eq 2"`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `iteration` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/plugins/repeat-block/repeat-block.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/repeat-block/repeat-block.pipeline.kts
```

## Su linaje

- **Nombre historico:** `repeat-block.pipeline.kts`
- **Ruta de origen:** `examples/plugins/repeat-block.pipeline.kts`
- **Huella del cuerpo:** `sha256:e2842b12471e5b274ced9cfb17b5573e4b28920f086e6b2ce769072be16e4961`
- **Huella del origen:** `sha256:a95f79824f2b4fe5efe29bbbe1523afb2f85053a543f331c6c937928beda3a25`
