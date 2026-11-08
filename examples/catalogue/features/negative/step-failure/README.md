# Step Failure

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> A command that fails must fail the whole run, and the report must say WHICH
> failure it was. The typed 'error' step has a scenario of its own; this one
> covers the other kind, the one a shell brings: the command exits with a code,
> and the run must name that as a SCRIPT failure instead of collapsing it into
> a generic error. An exit-0 expectation here would let an engine that
> swallowed the command's failure pass, and expecting only "it failed" would
> accept any failure at all, which is the difference between a scenario that
> pins the behaviour and one that only pins that something broke.

## The stages and the steps

### 1. `failing-command`

- `sh` — `echo 'the command refuses to succeed' && exit 1`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `Pipeline finished with FAILURE`, `cause [SCRIPT]: shell exited with code 1` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/step-failure/step-failure.pipeline.kts
pipelinek run      examples/catalogue/features/negative/step-failure/step-failure.pipeline.kts
```

## Its lineage

- **Historical name:** `30-step-failure.pipeline.kts`
- **Origin path:** `examples/negative/30-step-failure.pipeline.kts`
- **Body digest:** `sha256:c571e125e1ef4a7578ab5e8c0456c84d4ffd50b58afb9ce4facfd87dfe4ac842`
- **Origin digest:** `sha256:6c9b9b156c2211828de29997863969144efe360506c2b301526526687375b6ab`
