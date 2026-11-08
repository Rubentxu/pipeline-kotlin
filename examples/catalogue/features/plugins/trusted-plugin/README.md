# Trusted Plugin

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `plugins`

**Expected outcome:** `success`

## What it exercises

The steps that come from a plugin, and the capabilities they declare

*The example says this about itself:*

> The subject is the operator's trust policy over a plugin they bring.

## The stages and the steps

### 1. `External`

- `uppercase` — `hello` — a step this plugin provides (`example-uppercase-plugin`)

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `registrystep-0 finished` |
| stderr contains | `Discovered external Step plugins: example.uppercase`, `admitted under the operator's trust policy` |

## What it needs

- plugin-jar
- plugin-trust

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/plugins/trusted-plugin/trusted-plugin.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/trusted-plugin/trusted-plugin.pipeline.kts
```

## Its lineage

- **Historical name:** `trusted-plugin.pipeline.kts`
- **Origin path:** `examples/plugins/trusted-plugin.pipeline.kts`
- **Body digest:** `sha256:c3b73133a7684032bedbe408a47c4ffa0fbb075856611352a7862b6cc5354c26`
