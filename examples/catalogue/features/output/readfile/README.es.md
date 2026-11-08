# Readfile

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `output`

**Resultado esperado:** `success`

## Que ejercita

El plano de salida: que produjo un paso, y como se vuelve a leer

*El ejemplo dice de si mismo:*

> Core.readFile live fixture. Writes a file with the certified
> writeFile substrate, reads it back through core.readFile (FileRead event,
> content never enters the event channel), then checks existence via
> core.fileExists (missing file is a legitimate false, not a failure).

## Las etapas y los pasos

### 1. `readfile-roundtrip`

- `writeFile` — `"lpr104-readme.txt", "hello-lpr-104"`
- `readFile` — `lpr104-readme.txt`
- `fileExists` — `lpr104-readme.txt`
- `fileExists` — `lpr104-missing.txt`
- `echo` — `ROUNDTRIP-OK`
- `sh` — `test -f lpr104-readme.txt && grep -q hello-lpr-104 lpr104-readme.txt`
- `sh` — `test ! -e lpr104-missing.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/output/readfile/readfile.pipeline.kts
pipelinek run      examples/catalogue/features/output/readfile/readfile.pipeline.kts
```

## Su linaje

- **Nombre historico:** `23-readfile.pipeline.kts`
- **Ruta de origen:** `examples/output/23-readfile.pipeline.kts`
- **Huella del cuerpo:** `sha256:de2224e8eecdedd76480c3e2d811b078081efb9fcfdcf821fa02eb225b541db3`
