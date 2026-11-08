# Dir Nested Deep

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `dir`, `nested`, `cross_stage`

**Resultado esperado:** `success`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> The subject is the DEPTH of the working-directory contract: five levels
> of 'dir()', the file written at the deepest one, and the working
> directory RESTORED afterwards.

## Las etapas y los pasos

### 1. `nested`

- `dir(a/b/c/d/e)` — un bloque de directorio
  - `sh` — `printf 'CANARY-DEEP\\n' > payload.txt`
- `dir(a/b/c/d/e)` — un bloque de directorio
  - `sh` — `cat payload.txt | grep -qx CANARY-DEEP`
- `sh` — `"pwd | grep -qx /workspace && mkdir -p /workspace/out && printf 'restored\\n' > /worksp...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `a/b/c/d/e/payload.txt`, `out/canary-dir-restore.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/dir-nested-deep/dir-nested-deep.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/dir-nested-deep/dir-nested-deep.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `dir-nested-deep.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/dir-nested-deep.pipeline.kts`
- **Huella del cuerpo:** `sha256:ddfaf2fb19ed3334de4c60f927e88e7f3bcf20a2aec96934ddad5292c4fb49a5`
