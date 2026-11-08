# Plugin Manifest Lies

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> The subject is a plugin that ADMITS and then lies.

## The stages and the steps

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `Plugin admission refused for`, `declared Steps with no implementation`, `example.uppercase.ghost` |

## What it needs

- distribution

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/plugin-manifest-lies/plugin-manifest-lies.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-manifest-lies/plugin-manifest-lies.pipeline.kts
```

## Its lineage

- **Historical name:** `plugin-manifest-lies.pipeline.kts`
- **Origin path:** `examples/negative/plugin-manifest-lies.pipeline.kts`
- **Body digest:** `sha256:0921c730bbb694419a2344ac23779c1afa538e5595ca3a0c2afa22dab8bc1e47`
