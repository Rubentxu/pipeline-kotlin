# Sleep

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `steps`

**Expected outcome:** `success`

## What it exercises

The steps of the DSL, one by one, and what each of them guarantees

*The example says this about itself:*

> The subject is the 'sleep' Step: the run must not continue before the
> requested delay has elapsed.

## The stages and the steps

### 1. `sleep-step`

- `sh` — `date +%s > sleep-started`
- `sleep` — `1`
- `sh` — `date +%s > sleep-ended`
- `sh` — `"test \"$(( $(cat sleep-ended) - $(cat sleep-started) ))\" -ge 1"`
- `echo` — `woke`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `woke` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/steps/sleep/sleep.pipeline.kts
pipelinek run      examples/catalogue/features/steps/sleep/sleep.pipeline.kts
```

## Its lineage

- **Historical name:** `16-sleep.pipeline.kts`
- **Origin path:** `examples/steps/16-sleep.pipeline.kts`
- **Body digest:** `sha256:6be132a30975c0cbe58d952e161e7b770ffe6e7d58fb7a3ed0b8107eaac23e0b`
- **Origin digest:** `sha256:72e18772ecdb1904f128a74ef422763efbf24b35ad2ef1aaebe0a35d044ee01f`
