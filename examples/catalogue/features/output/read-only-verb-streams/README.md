# Read Only Verb Streams

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `output`

**Expected outcome:** `success`

## What it exercises

The output plane: what a step produced, and how it is read back

*The example says this about itself:*

> The two streams of a read-only verb, at the boundary of the process.

## The stages and the steps

### 1. `streams`

- `sh` — `""" '${product}' capabilities > capabilities.out 2> capabilities.err echo ${'$'}? > cap...`
- `sh` — `""" '${product}' sdk-info > sdk-info.out 2> sdk-info.err echo ${'$'}? > sdk-info.code i...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `CAPABILITIES_ANSWER_ON_STDOUT`, `SDK_INFO_ANSWER_ON_STDOUT`, `DOCTOR_ANSWER_ON_STDOUT`, `CAPABILITIES_REFUSAL_ON_STDERR` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/output/read-only-verb-streams/read-only-verb-streams.pipeline.kts
pipelinek run      examples/catalogue/features/output/read-only-verb-streams/read-only-verb-streams.pipeline.kts
```

## Its lineage

- **Historical name:** `34-read-only-verb-streams.pipeline.kts`
- **Origin path:** `examples/output/34-read-only-verb-streams.pipeline.kts`
- **Body digest:** `sha256:41ef9b77264e6d575b5f7635040a3bbe57696db20be03e34c774ce97196488ac`
