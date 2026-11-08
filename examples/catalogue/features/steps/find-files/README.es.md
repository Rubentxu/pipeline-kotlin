# Find Files

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `steps`

**Resultado esperado:** `success`

## Que ejercita

Los pasos del DSL, uno a uno, y que garantiza cada uno

*El ejemplo dice de si mismo:*

> The subject is the SIDE-EFFECT contract of 'core-utils.findFiles'. The step is
> declared 'Effect.READ_ONLY' and returns a typed value the DSL does not
> expose, so the guarantee the harness can observe is "what files survived the
> step", not "what files did the step enumerate". A finder that enumerated
> nothing passes the latter; a finder that touched anything -- created a
> sidecar, deleted a file, rewrote a byte -- fails this one, because the
> snapshot taken afterwards has a hash the snapshot taken before did not.

## Las etapas y los pasos

### 1. `find-files`

- `sh` — `""" mkdir -p build/utils/scan/sub echo 'one' > build/utils/scan/a.txt echo 'two' > buil...`
- `sh` — `""" (cd build/utils/scan && find . -type f -print0 \ | LC_ALL=C sort -z \ | xargs -0 sh...`
- `findFiles` — `base = "build/utils/scan", glob = "*.txt"` — un paso que aporta este plugin (`core-utils`)
- `findFiles` — `base = "build/utils/scan", glob = "**/*.txt"` — un paso que aporta este plugin (`core-utils`)
- `findFiles` — `base = "build/utils/scan", glob = "**/*.nope"` — un paso que aporta este plugin (`core-utils`)
- `sh` — `""" (cd build/utils/scan && find . -type f -print0 \ | LC_ALL=C sort -z \ | xargs -0 sh...`
- `sh` — `diff -u snapshot.before snapshot.after`
- `sh` — `"test \"$(find build/utils/scan -type f | wc -l)\" -eq 4"`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/steps/find-files/find-files.pipeline.kts
pipelinek run      examples/catalogue/features/steps/find-files/find-files.pipeline.kts
```

## Su linaje

- **Nombre historico:** `26-find-files.pipeline.kts`
- **Ruta de origen:** `examples/steps/26-find-files.pipeline.kts`
- **Huella del cuerpo:** `sha256:76731a2bf22887892ba38441f120ae38815f8ed391c4633629d9e2bfbd873333`
