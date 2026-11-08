# Milestone

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `success`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> The subject is a milestone step reached through the open registry rather than
> by a hard-wired call. Each milestone writes a durable trace entry, and the
> two ordinals here must both land before the pipeline reaches the final echo:
> that the run finishes is what tells us neither of them threw.

## The stages and the steps

### 1. `milestones`

- `echo` — `pre-1`
- `milestone` — `ordinal = 1, label = "post-error"`
- `echo` — `between`
- `milestone` — `ordinal = 2, label = "post-build"`
- `echo` — `post-2`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `post-2` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/milestone/milestone.pipeline.kts
pipelinek run      examples/catalogue/features/durable/milestone/milestone.pipeline.kts
```

## Its lineage

- **Historical name:** `21-milestone.pipeline.kts`
- **Origin path:** `examples/durable/21-milestone.pipeline.kts`
- **Body digest:** `sha256:01d9bc425c54a76f65f741e861ad18aab35354a716828614c7ba961012384de4`
- **Origin digest:** `sha256:32f3fe6693cf6057c7c84da7503227957ccb8c812812c25f50cfdb57fde4326c`
