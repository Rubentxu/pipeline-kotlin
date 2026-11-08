# Writefile Step

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `file_io`, `cross_stage`

**Resultado esperado:** `success`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> The subject is the WRITE step of the DSL, and its guarantee is that what
> it writes is there to be read BACK, in a DIFFERENT stage.

## Las etapas y los pasos

### 1. `write`

- `writeFile` — `file = "out/canary-writefile.txt", text = "CANARY-WRITEFILE"`

### 2. `read-back`

- `sh` — `grep -qx CANARY-WRITEFILE out/canary-writefile.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/canary-writefile.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/writefile-step/writefile-step.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/writefile-step/writefile-step.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `writefile-step.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/writefile-step.pipeline.kts`
- **Huella del cuerpo:** `sha256:acfc614dd8182d1ae4bd334697f734c7a3ab8ca2db45fddee68a2a14ad65d514`
