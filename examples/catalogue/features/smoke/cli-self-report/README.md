# Cli Self Report

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `smoke`

**Expected outcome:** `success`

## What it exercises

The shortest path through the engine: a pipeline that starts, runs and finishes

*The example says this about itself:*

> The product, asked about itself.

## The stages and the steps

### 1. `self-report`

- `sh` — `""" mkdir -p 'probe/NOT WRITABLE' (cd 'probe/NOT WRITABLE' && '${product}' doctor) > do...`
- `sh` — `""" if [ ! -d /proc ]; then echo "this machine has no directory nobody can write into,...`
- `sh` — `""" '${product}' doctor > doctor-cache.txt 2>&1 line="$(grep -F 'script-cache:' doctor-...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `DOCTOR_HEALTHY_EXIT`, `DOCTOR_SAID_WRITABLE`, `DOCTOR_DEFECTIVE_EXIT`, `DOCTOR_SAID_UNWRITABLE`, `DOCTOR_NAMES_THE_CACHE`, `DOCTOR_SAYS_WHETHER_IT_IS_USED`, `CAPABILITIES_ONE_LINE`, `CAPABILITIES_SCHEMA_V2`, `CAPABILITIES_ONE_VOCABULARY`, `ONE_VERSION_THREE_FACES`, `REFUSAL_ANSWERS_INVALID_ARGUMENT`, `MISSING_CAPABILITY_ANSWERS_RUNTIME_NOT_AVAILABLE`, `OPERATIONS_ADDRESS_THEIR_OWN_BYTES`, `FOLDED_IDENTITY_GETS_NO_NEIGHBOUR_BYTES` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/smoke/cli-self-report/cli-self-report.pipeline.kts
pipelinek run      examples/catalogue/features/smoke/cli-self-report/cli-self-report.pipeline.kts
```

## Its lineage

- **Historical name:** `11-cli-self-report.pipeline.kts`
- **Origin path:** `examples/smoke/11-cli-self-report.pipeline.kts`
- **Body digest:** `sha256:cf1819b8a99e6182c6563a6600670e894f051c27be66698b194c1683aee53c83`
