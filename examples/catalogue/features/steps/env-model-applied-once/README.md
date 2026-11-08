# Env Model Applied Once

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `steps`

**Expected outcome:** `success`

## What it exercises

The steps of the DSL, one by one, and what each of them guarantees

## The stages and the steps

### 1. `env`

- `withEnv(mapOf("JAVA_HOME" to "/opt/jdk", "PATH" to "/usr/bin:/bin"))` — an environment block
  - `sh` — `"""test "${'$'}PATH" = "/opt/jdk/bin:/usr/bin:/bin" || { printf 'OBSERVED_PATH=%s\n' "$...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/steps/env-model-applied-once/env-model-applied-once.pipeline.kts
pipelinek run      examples/catalogue/features/steps/env-model-applied-once/env-model-applied-once.pipeline.kts
```

## Its lineage

- **Historical name:** `35-env-model-applied-once.pipeline.kts`
- **Origin path:** `examples/steps/35-env-model-applied-once.pipeline.kts`
- **Body digest:** `sha256:a4a51bdb166892d6b64fb16d702c59a6ce1d45b25aaa00979487e8397e53f22d`
