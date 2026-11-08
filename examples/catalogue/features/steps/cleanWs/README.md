# Cleanws

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `steps`

**Expected outcome:** `success`

## What it exercises

The steps of the DSL, one by one, and what each of them guarantees

*The example says this about itself:*

> The effect of 'cleanWs' is an ABSENCE: the matched paths must be gone. A
> scenario that only ran the step and checked the exit code passed just as
> happily when the step deleted nothing, and leaving another run's files on
> disk is exactly the failure this step exists to prevent. So the assertion
> has to be about what is NOT there.
> 'test ! -e' rather than 'test ! -f': cleanWs is documented over patterns,
> not over regular files, and a pattern match that removed the directory
> itself would satisfy '-f' on a leftover path differently from '-e'. '-e'
> asks the only question that matters here -- did anything at all survive.

## The stages and the steps

### 1. `cleanws-step`

- `sh` — `mkdir -p test && touch test/a.txt test/b.txt`
- `cleanWs` — `patterns = listOf("test/**")`
- `sh` — `test ! -e test/a.txt && test ! -e test/b.txt`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/steps/cleanWs/cleanWs.pipeline.kts
pipelinek run      examples/catalogue/features/steps/cleanWs/cleanWs.pipeline.kts
```

## Its lineage

- **Historical name:** `18-cleanWs.pipeline.kts`
- **Origin path:** `examples/steps/18-cleanWs.pipeline.kts`
- **Body digest:** `sha256:254071f0e82d29d33a27740145da41ee526143ab8e1d759834aa2be58fb32207`
