# Uppercase Measured

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `plugins`

**Expected outcome:** `success`

## What it exercises

The steps that come from a plugin, and the capabilities they declare

*The example says this about itself:*

> The subject is an external plugin's step that asks the RUNTIME to observe its own work.

## The stages and the steps

### 1. `External`

- `uppercaseMeasured` — `hello` — a step this plugin provides (`example-uppercase-plugin`)

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `registrystep-0 finished` |
| stderr contains | `Discovered external Step plugins: example.uppercase` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/plugins/uppercase-measured/uppercase-measured.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/uppercase-measured/uppercase-measured.pipeline.kts
```

## Its lineage

- **Historical name:** `uppercase-measured.pipeline.kts`
- **Origin path:** `examples/plugins/uppercase-measured.pipeline.kts`
- **Body digest:** `sha256:c8bb7daff071ddb0f900632229d907b40bf485aa3de27435c8bf16b1a33024c1`
- **Origin digest:** `sha256:ded442496c3df5d00b5fcb67b38ec00604859662f9c5121b51a7058a3e06077a`
