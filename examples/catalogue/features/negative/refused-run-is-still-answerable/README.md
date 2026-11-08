# Refused Run Is Still Answerable

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> 35-refused-run-is-still-answerable.pipeline.kts — a run refused before it starts can still be asked about.

## The stages and the steps

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `Plugin admission refused for` |

## What it needs

- plugin-jar
- run-state

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/refused-run-is-still-answerable/refused-run-is-still-answerable.pipeline.kts
pipelinek run      examples/catalogue/features/negative/refused-run-is-still-answerable/refused-run-is-still-answerable.pipeline.kts
```

## Its lineage

- **Historical name:** `35-refused-run-is-still-answerable.pipeline.kts`
- **Origin path:** `examples/negative/35-refused-run-is-still-answerable.pipeline.kts`
- **Body digest:** `sha256:7e46b1db853a247eeac9cb457df52faf0fc2cf35175ec21f1fed25debf2a4479`
- **Origin digest:** `sha256:c1b92d0e4f1c23502fd955dc54f7366043b1e945c9ca87666a74c3837cfd1df9`
