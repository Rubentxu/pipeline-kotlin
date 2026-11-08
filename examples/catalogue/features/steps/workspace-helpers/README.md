# Workspace Helpers

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `steps`

**Expected outcome:** `success`

## What it exercises

The steps of the DSL, one by one, and what each of them guarantees

## The stages and the steps

### 1. `workspace-helpers`

- `pwd` — a step this example declares
- `echo` — `"current dir: " + dir`
- `isUnix` — a step this example declares
- `echo` — `"is unix: " + unix.toString()`
- `timestamps` — a block
  - `echo` — `timestamped output`
- `waitUntil(initialRecurrencePeriod = 100)` — a wait block
- `echo` — `waitUntil done`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `waitUntil done` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/steps/workspace-helpers/workspace-helpers.pipeline.kts
pipelinek run      examples/catalogue/features/steps/workspace-helpers/workspace-helpers.pipeline.kts
```

## Its lineage

- **Historical name:** `13-workspace-helpers.pipeline.kts`
- **Origin path:** `examples/steps/13-workspace-helpers.pipeline.kts`
- **Body digest:** `sha256:65512e5476a331742ba11436de51f2d17eeabf81c8bfb26e9e98628e6ee05b00`
- **Origin digest:** `sha256:9a87f6458cf7721c0867983e74a48834b50e452bc949b29606b36f9651bd3ff5`
