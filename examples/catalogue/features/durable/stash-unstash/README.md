# Stash Unstash

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `success`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

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

## The stages and the steps

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

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/stash-unstash/stash-unstash.pipeline.kts
pipelinek run      examples/catalogue/features/durable/stash-unstash/stash-unstash.pipeline.kts
```

## Its lineage

- **Historical name:** `31-stash-unstash.pipeline.kts`
- **Origin path:** `examples/durable/31-stash-unstash.pipeline.kts`
- **Body digest:** `sha256:5a22dffbad90937503a1f628b6f48585301b71dcd141ed8bb79a91a43b28d10f`
- **Origin digest:** `sha256:5a22dffbad90937503a1f628b6f48585301b71dcd141ed8bb79a91a43b28d10f`
