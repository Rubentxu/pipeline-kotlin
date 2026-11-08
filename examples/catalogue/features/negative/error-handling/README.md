# Error Handling

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `unstable`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

## The stages and the steps

### 1. `error-and-milestone`

- `warnError(intentionally flaky section)` — a warning block
  - `catchError` — a block of recovery steps
    - `sh` — `exit 1`
- `milestone` — `ordinal = 1, label = "post-error"`

### 2. `post-milestone`

- `echo` — `milestone reached`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `unstable` |
| stdout contains | `milestone reached` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/error-handling/error-handling.pipeline.kts
pipelinek run      examples/catalogue/features/negative/error-handling/error-handling.pipeline.kts
```

## Its lineage

- **Historical name:** `12-error-handling.pipeline.kts`
- **Origin path:** `examples/negative/12-error-handling.pipeline.kts`
- **Body digest:** `sha256:0a5ba7e4bd985d779dd9f33c1ad758d9c896923aac134c8fc87269ad770acf65`
