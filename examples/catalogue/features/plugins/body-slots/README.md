# Body Slots

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `plugins`

**Expected outcome:** `success`

## What it exercises

The steps that come from a plugin, and the capabilities they declare

## The stages and the steps

### 1. `Slots`

- `slotPlan` — `forwardCommand = "echo forward >> '$home/forward.log'; exit 1", compensateCommand = "ec...` — a step this plugin provides (`example-block-plugin`)
- `sh` — `test -f '$home/compensated.log'`
- `sh` — `cat '$home/compensated.log'`
- `slotPlan` — `forwardCommand = "echo forward >> '$home/forward.log'", compensateCommand = "echo compe...` — a step this plugin provides (`example-block-plugin`)
- `sh` — `test ! -f '$home/should-not-exist.log'`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `compensated` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/plugins/body-slots/body-slots.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/body-slots/body-slots.pipeline.kts
```

## Its lineage

- **Historical name:** `body-slots.pipeline.kts`
- **Origin path:** `examples/plugins/body-slots.pipeline.kts`
- **Body digest:** `sha256:417ee94f9ade9e6dae6be56f27ab2ea9125d2e7c3a9c521e8e5a202ddab2ff06`
- **Origin digest:** `sha256:f36dd7cec30a67b935423f0a9fe0fbcafcc6cdb91ea963e504502641aeff4391`
