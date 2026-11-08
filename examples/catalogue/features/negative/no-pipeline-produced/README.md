# No Pipeline Produced

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> THE GUARANTEE UNDER TRIAL

## The stages and the steps

_The pipeline declares no stage._

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `did not produce a PipelineSpec` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/no-pipeline-produced/no-pipeline-produced.pipeline.kts
pipelinek run      examples/catalogue/features/negative/no-pipeline-produced/no-pipeline-produced.pipeline.kts
```

## Its lineage

- **Historical name:** `no-pipeline-produced.pipeline.kts`
- **Origin path:** `examples/negative/no-pipeline-produced.pipeline.kts`
- **Body digest:** `sha256:1d88c20ed3a3d276a1e7c06faf5e394297c0f0dfd308b8bbad552f1ba53ad19a`
