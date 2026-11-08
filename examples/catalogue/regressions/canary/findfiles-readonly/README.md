# Findfiles Readonly

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `file_io`, `workspace`, `plugin`

**Expected outcome:** `success`

## What it exercises

Reading and writing files, and what is there to be read back

*The example says this about itself:*

> The subject is a READ-ONLY step: enumerating files must not change the
> tree it enumerated.

## The stages and the steps

### 1. `find`

- `sh` — `"mkdir -p tree/sub && printf 'one\\n' > tree/a.txt && printf 'two\\n' > tree/sub/b.txt...`
- `sh` — `"(cd tree && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) > snapshot...`
- `findFiles` — `base = "tree", glob = "**/*.txt"` — a step this plugin provides (`core-utils`)
- `findFiles` — `base = "tree", glob = "**/*.nope"` — a step this plugin provides (`core-utils`)
- `sh` — `"(cd tree && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) > snapshot...`
- `sh` — `"diff -u snapshot.before snapshot.after && test \"$(find tree -type f | wc -l)\" -eq 3"`
- `sh` — `mkdir -p /workspace/out && cp snapshot.after /workspace/out/canary-findfiles.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/canary-findfiles.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- el plugin de utilidades (utilities) viene en la imagen de la candidata

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/findfiles-readonly/findfiles-readonly.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/findfiles-readonly/findfiles-readonly.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `findfiles-readonly.pipeline.kts`
- **Origin path:** `scenarios/canary/findfiles-readonly.pipeline.kts`
- **Body digest:** `sha256:697540c9cb993215bd99ee725e9f71f9e26b5a79499aa84bcf2393059aca97b5`
