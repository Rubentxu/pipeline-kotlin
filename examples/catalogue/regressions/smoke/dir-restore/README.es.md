# Dir Restore

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `dir`, `process`, `shell`

**Resultado esperado:** `failure`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> dir restaura cwd tras error. El primer dir() falla (exit 1),
> el segundo dir() debe partir del workspace raiz (no del subdirectorio
> fallido) y verificarlo escribiendo pwd.

## Las etapas y los pasos

### 1. `dir-restore`

- `dir(errdir)` — un bloque de directorio
  - `sh` — `exit 1`
- `dir(chk)` — un bloque de directorio
  - `sh` — `pwd | grep -qx /workspace && printf 'cwd-restored\\n' > /workspace/out/marker.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/dir-restore/dir-restore.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/dir-restore/dir-restore.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `har007-dir-restore.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/har007-dir-restore.pipeline.kts`
- **Huella del cuerpo:** `sha256:9ce90d610d6727110d687b5d6049a72dddf0b0e56696092ee4139b5938ef501c`
