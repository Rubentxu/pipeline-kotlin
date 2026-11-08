# Plugin Duplicate Identity

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> The subject is a composition where two DIFFERENT artifacts claim the same plugin identity, and
> the refusal has to say which of them claimed it first, and that the composition is refused.

## The stages and the steps

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `Plugin admission refused for`, `DuplicateIdentity`, `uppercase.jar`, `the same identity was already claimed by`, `example-uppercase-plugin-0.1.0.jar`, `the whole composition is refused` |

## What it needs

- plugin-jar

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/plugin-duplicate-identity/plugin-duplicate-identity.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-duplicate-identity/plugin-duplicate-identity.pipeline.kts
```

## Its lineage

- **Historical name:** `plugin-duplicate-identity.pipeline.kts`
- **Origin path:** `examples/negative/plugin-duplicate-identity.pipeline.kts`
- **Body digest:** `sha256:be65ae24ae7937ec553bf5ba1774598f0644bf3c82dd247e283823722c728349`
- **Origin digest:** `sha256:2d2f9aeda60d64115ce5b5a6b3adecf9858abe3f450816aa85787cdd94ae0450`
