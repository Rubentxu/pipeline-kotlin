# Findfiles Readonly

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `file_io`, `workspace`, `plugin`

**Resultado esperado:** `success`

## Que ejercita

Leer y escribir ficheros, y lo que queda para releer

*El ejemplo dice de si mismo:*

> The subject is a READ-ONLY step: enumerating files must not change the
> tree it enumerated.

## Las etapas y los pasos

### 1. `find`

- `sh` — `"mkdir -p tree/sub && printf 'one\\n' > tree/a.txt && printf 'two\\n' > tree/sub/b.txt...`
- `sh` — `"(cd tree && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) > snapshot...`
- `findFiles` — `base = "tree", glob = "**/*.txt"` — un paso que aporta este plugin (`core-utils`)
- `findFiles` — `base = "tree", glob = "**/*.nope"` — un paso que aporta este plugin (`core-utils`)
- `sh` — `"(cd tree && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) > snapshot...`
- `sh` — `"diff -u snapshot.before snapshot.after && test \"$(find tree -type f | wc -l)\" -eq 3"`
- `sh` — `mkdir -p /workspace/out && cp snapshot.after /workspace/out/canary-findfiles.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/canary-findfiles.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- el plugin de utilidades (utilities) viene en la imagen de la candidata

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/findfiles-readonly/findfiles-readonly.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/findfiles-readonly/findfiles-readonly.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `findfiles-readonly.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/findfiles-readonly.pipeline.kts`
- **Huella del cuerpo:** `sha256:697540c9cb993215bd99ee725e9f71f9e26b5a79499aa84bcf2393059aca97b5`
