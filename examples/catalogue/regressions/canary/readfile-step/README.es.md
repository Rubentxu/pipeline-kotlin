# Readfile Step

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `file_io`

**Resultado esperado:** `success`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> The subject is the READ side of the file steps: 'readFile' and
> 'fileExists', in BOTH polarities.

## Las etapas y los pasos

### 1. `roundtrip`

- `sh` — `mkdir -p out && printf 'CANARY-READFILE\\n' > out/canary-readfile.txt`
- `readFile` — `out/canary-readfile.txt`
- `fileExists` — `out/canary-readfile.txt`
- `fileExists` — `out/canary-absent.txt`
- `sh` — `grep -qx CANARY-READFILE out/canary-readfile.txt && test ! -e out/canary-absent.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/canary-readfile.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/readfile-step/readfile-step.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/readfile-step/readfile-step.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `readfile-step.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/readfile-step.pipeline.kts`
- **Huella del cuerpo:** `sha256:39aed92096215e2cf85719da18b75e7d13814ac1c6104278c260828c561fbf7d`
