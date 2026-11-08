# Uppercase

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `plugins`

**Expected outcome:** `success`

## What it exercises

The steps that come from a plugin, and the capabilities they declare

*The example says this about itself:*

> The subject is an external plugin's step: the DSL resolves a name it does not
> know at compile time, the plugin claims it, and the run completes.

## The stages and the steps

### 1. `External`

- `uppercase` — `hello` — a step this plugin provides (`example-uppercase-plugin`)

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `registrystep-0 finished` |
| stderr contains | `Discovered external Step plugins: example.uppercase` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/plugins/uppercase/uppercase.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/uppercase/uppercase.pipeline.kts
```

## Its lineage

- **Historical name:** `uppercase.pipeline.kts`
- **Origin path:** `examples/plugins/uppercase.pipeline.kts`
- **Body digest:** `sha256:63ffffb0d2cb4db28ea29f888eb69a054ee298159beccde64b9ff6675871e1be`
- **Origin digest:** `sha256:ad1be266b1e3234c29e284f15b35f4bc2527580e40cee1c7a393dbb56882f855`
