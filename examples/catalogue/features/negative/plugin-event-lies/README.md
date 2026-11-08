# Plugin Event Lies

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> The subject is a plugin that declares an Event nobody contributes.

## The stages and the steps

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `Plugin admission refused for`, `declared Events with no contributor`, `example.uppercase.ghostEvent` |

## What it needs

- distribution

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/plugin-event-lies/plugin-event-lies.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-event-lies/plugin-event-lies.pipeline.kts
```

## Its lineage

- **Historical name:** `plugin-event-lies.pipeline.kts`
- **Origin path:** `examples/negative/plugin-event-lies.pipeline.kts`
- **Body digest:** `sha256:c6cbe7932257d57ba8d27404072a064cba0dce189cc25c2a888d549f4f82fb58`
