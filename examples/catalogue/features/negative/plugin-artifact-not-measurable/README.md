# Plugin Artifact Not Measurable

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> The subject is a plugin artifact the runtime cannot read.

## The stages and the steps

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `Plugin admission refused for`, `ArtifactNotMeasurable` |

## What it needs

- plugin-jar

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/plugin-artifact-not-measurable/plugin-artifact-not-measurable.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-artifact-not-measurable/plugin-artifact-not-measurable.pipeline.kts
```

## Its lineage

- **Historical name:** `34-plugin-artifact-not-measurable.pipeline.kts`
- **Origin path:** `examples/negative/34-plugin-artifact-not-measurable.pipeline.kts`
- **Body digest:** `sha256:a33bc075a307139e8af8b95c8fb17e181a264f73549e285ef8b725f9950b8d66`
