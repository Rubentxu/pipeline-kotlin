# Environment

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `smoke`

**Expected outcome:** `success`

## What it exercises

The shortest path through the engine: a pipeline that starts, runs and finishes

## The stages and the steps

### 1. `build`

- `withEnv(listOf("KEY=value", "BUILD_VERSION=1.0.0"))` — an environment block
  - `echo` — `building...`
  - `sh` — `"""test "${'$'}KEY" = value && test "${'$'}BUILD_VERSION" = 1.0.0"""`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/smoke/environment/environment.pipeline.kts
pipelinek run      examples/catalogue/features/smoke/environment/environment.pipeline.kts
```

## Its lineage

- **Historical name:** `02-environment.pipeline.kts`
- **Origin path:** `examples/smoke/02-environment.pipeline.kts`
- **Body digest:** `sha256:ccc7c8e2cd05228e818050409348b8b25cc94cb950c671835e306e40be7b4b91`
