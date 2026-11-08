# Dir Nested

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `dir`, `nested`, `shell`, `cross_stage`

**Resultado esperado:** `success`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> dir nested + read/write/fileExists con hashes.
> Escribe en subdirectorios anidados via dir(), verifica lectura y existencia.

## Las etapas y los pasos

### 1. `dir-nested`

- `dir(a/b/c)` — un bloque de directorio
  - `sh` — `printf 'payload-har006\\n' > payload.txt`
- `dir(a/b/c)` — un bloque de directorio
  - `sh` — `"cat payload.txt | grep -q payload-har006 && test -f payload.txt && mkdir -p /workspace...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `a/b/c/payload.txt`, `out/marker.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/dir-nested/dir-nested.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/dir-nested/dir-nested.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `har006-dir-nested.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/har006-dir-nested.pipeline.kts`
- **Huella del cuerpo:** `sha256:72d077e66250903c402eb29ae44cc995a9bef359e44edd84d7c172e3d5e72ff7`
