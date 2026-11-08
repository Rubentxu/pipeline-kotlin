# Error

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> The 'error' Step is the subject: it must fail the run. An exit-0
> expectation here would let an engine that swallowed the typed USER
> failure pass. The substrings pin the report, not just the outcome: the
> outcome line says what happened, and the cause line says WHY and of which
> kind, which is the difference between a report a person can act on and one
> they have to guess about. Both execution paths print the same report, so
> both of them are asserted by the same scenario.

## The stages and the steps

### 1. `error-step`

- `error` — `"test error message", failureKind = FailureKind.USER`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `Pipeline finished with FAILURE`, `cause [USER]: test error message` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/error/error.pipeline.kts
pipelinek run      examples/catalogue/features/negative/error/error.pipeline.kts
```

## Its lineage

- **Historical name:** `15-error.pipeline.kts`
- **Origin path:** `examples/negative/15-error.pipeline.kts`
- **Body digest:** `sha256:fdab90aa92ad06426ee78579bbb3bda104dffc4f3f336452fc947228119be304`
- **Origin digest:** `sha256:b2aac33f9c7aa4540e532931d1a9ea17c26b709bd01038082d940cff12d5ea33`
