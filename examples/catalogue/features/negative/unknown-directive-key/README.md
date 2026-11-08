# Unknown Directive Key

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> A directive key that no contributor registers must fail CLOSED and name the
> key, never be skipped.
> This is the other half of plugins/lock-directive.pipeline.kts. That scenario
> would stay green if admission turned fail-OPEN and simply ignored the whole
> directives block; this one goes red in the same circumstance, and it also
> pins the specific refusal rather than accepting any nonzero exit, so a
> compiler error or an unrelated crash cannot stand in for the real guarantee.

## The stages and the steps

### 1. `Unknown directive`

- `directives` — a block
  - `directive` — `"acme.no.such.directive", """{}"""`
- `sh` — `echo should never run`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `unresolved directive 'acme.no.such.directive'` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/unknown-directive-key/unknown-directive-key.pipeline.kts
pipelinek run      examples/catalogue/features/negative/unknown-directive-key/unknown-directive-key.pipeline.kts
```

## Its lineage

- **Historical name:** `unknown-directive-key.pipeline.kts`
- **Origin path:** `examples/negative/unknown-directive-key.pipeline.kts`
- **Body digest:** `sha256:d92c777ee392d34ab1e9c32759413cfe0a15a3a0253fa6c759b83cbe17930c6d`
- **Origin digest:** `sha256:224beb0470fe1777b5402843ab2acf405af920326cc2aabcf4d818148a616287`
