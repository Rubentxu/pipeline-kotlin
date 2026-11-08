# Workspace Coherence

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `steps`

**Expected outcome:** `success`

## What it exercises

The steps of the DSL, one by one, and what each of them guarantees

*The example says this about itself:*

> The four workspace-aware Steps must agree on ONE base: the effective working
> directory of the 'dir(...)' scope they run in.

## The stages and the steps

### 1. `coherence`

- `writeFile` — `file = "at-root.txt", text = "ROOT"`
- `sh` — `mkdir -p victim && echo KEEP-ROOT > victim/keep.txt`
- `dir(inner)` — a directory block
  - `sh` — `echo INNER > anchor.txt`
  - `sh` — `mkdir -p victim && echo KEEP-SCOPE > victim/keep.txt`
  - `writeFile` — `file = "written.txt", text = "WRITTEN"`
  - `sh` — `test -f written.txt && echo WROTE_HERE || echo LEG_write_scope`
  - `stash` — `name = "coherence", includes = "*.txt"`
  - `sh` — `rm -f anchor.txt`
  - `unstash` — `name = "coherence"`
  - `sh` — `test -f anchor.txt && echo RESTORED || echo LEG_restore_scope`
  - `sh` — `test ! -e at-root.txt && echo SCOPE_CLEAN || echo LEG_root_leaked`
  - `deleteDir` — `victim`
  - `sh` — `test ! -f victim/keep.txt && echo VICTIM_GONE || echo LEG_delete_scope`
- `sh` — `test -f at-root.txt && echo ROOT_SURVIVED || echo LEG_root_lost`
- `sh` — `test -f victim/keep.txt && echo ROOT_VICTIM_SURVIVED || echo LEG_root_victim_lost`
- `sh` — `test ! -f anchor.txt && echo ROOT_NOT_POLLUTED || echo LEG_restore_escaped`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `WROTE_HERE`, `RESTORED`, `SCOPE_CLEAN`, `VICTIM_GONE`, `ROOT_SURVIVED`, `ROOT_VICTIM_SURVIVED`, `ROOT_NOT_POLLUTED` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/steps/workspace-coherence/workspace-coherence.pipeline.kts
pipelinek run      examples/catalogue/features/steps/workspace-coherence/workspace-coherence.pipeline.kts
```

## Its lineage

- **Historical name:** `34-workspace-coherence.pipeline.kts`
- **Origin path:** `examples/steps/34-workspace-coherence.pipeline.kts`
- **Body digest:** `sha256:5a27cee42ed5cf642702093f642827361cdf0f9f3890a1ac24e8b7e472bed872`
- **Origin digest:** `sha256:e18505f1fbed6493977e76a4883b46baceb2ceafef036922b64715ff26cb9824`
