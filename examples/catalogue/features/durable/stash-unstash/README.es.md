# Stash Unstash

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `success`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> 'core.stash' / 'core.unstash' installed-CLI canary (Tier B #1).
> Exercises the registry-path 'core.stash' and 'core.unstash' Steps through
> the DSL surface. Stage "build" creates files in the workspace and stashes
> them under the logical name "lpr104-files"; stage "use" un-stashes them
> back and verifies byte-equivalent recovery via sh(returnStdout=true).
> Failures here mean one of:
> - registry resolution broke (StepKey "core.stash"/"core.unstash" not found)
> - the registry handler regressed (no StashCreated/StashRestored, or wrong path shape)
> - the DSL lowering reverted to legacy StepSpec.Stash
> Storage layout: <controlRoot>/stashes/<runId>/<lpr104-files>. The stash
> directory is a SIBLING of <controlRoot>/workspace/ so cleanup of the
> build stage workspace does NOT destroy the stash.

## Las etapas y los pasos

### 1. `build`

- `sh` — `mkdir -p src`
- `writeFile` — `file = "src/lpr104.txt", text = "lpr104-data"`
- `sh` — `mkdir -p docs`
- `writeFile` — `file = "docs/readme.md", text = "lpr104-readme"`
- `stash` — `name = "lpr104-files", includes = "src/**,docs/**"`

### 2. `use`

- `sh` — `rm -rf src docs`
- `unstash` — `name = "lpr104-files"`
- `sh` — `test -f src/lpr104.txt && echo SRC_OK`
- `sh` — `test -f docs/readme.md && echo DOCS_OK`
- `sh` — `cat src/lpr104.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/stash-unstash/stash-unstash.pipeline.kts
pipelinek run      examples/catalogue/features/durable/stash-unstash/stash-unstash.pipeline.kts
```

## Su linaje

- **Nombre historico:** `31-stash-unstash.pipeline.kts`
- **Ruta de origen:** `examples/durable/31-stash-unstash.pipeline.kts`
- **Huella del cuerpo:** `sha256:5a22dffbad90937503a1f628b6f48585301b71dcd141ed8bb79a91a43b28d10f`
- **Huella del origen:** `sha256:5a22dffbad90937503a1f628b6f48585301b71dcd141ed8bb79a91a43b28d10f`
