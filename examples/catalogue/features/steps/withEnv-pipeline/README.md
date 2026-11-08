# Withenv Pipeline

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `steps`

**Expected outcome:** `success`

## What it exercises

The steps of the DSL, one by one, and what each of them guarantees

## The stages and the steps

### 1. `envtest`

- `sh` — `"""export MY_VAR=test_value && echo 'MY_VAR is set' && test "${'$'}MY_VAR" = test_value...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/steps/withEnv-pipeline/withEnv-pipeline.pipeline.kts
pipelinek run      examples/catalogue/features/steps/withEnv-pipeline/withEnv-pipeline.pipeline.kts
```

## Its lineage

- **Historical name:** `08-withEnv-pipeline.pipeline.kts`
- **Origin path:** `examples/steps/08-withEnv-pipeline.pipeline.kts`
- **Body digest:** `sha256:f08d864eca0a35aba1bdebf40fd3e7c90ba3bccd024ec23c657cbcf388dc03d0`
- **Origin digest:** `sha256:f08d864eca0a35aba1bdebf40fd3e7c90ba3bccd024ec23c657cbcf388dc03d0`
