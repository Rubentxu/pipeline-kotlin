# Plugin Trust Digest Mismatch

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> The subject is the swap: the operator approved some bytes, and the bytes on disk are not them.

## The stages and the steps

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `Plugin admission refused for`, `RefusedDigestMismatch` |

## What it needs

- plugin-jar
- plugin-trust

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/plugin-trust-digest-mismatch/plugin-trust-digest-mismatch.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-trust-digest-mismatch/plugin-trust-digest-mismatch.pipeline.kts
```

## Its lineage

- **Historical name:** `plugin-trust-digest-mismatch.pipeline.kts`
- **Origin path:** `examples/negative/plugin-trust-digest-mismatch.pipeline.kts`
- **Body digest:** `sha256:9bfac5726a5623b59a6c565c14e23f801480e3f522997abdf8b87dc378f19e5b`
- **Origin digest:** `sha256:42b5622cdc4b464d87d7711fbfc7221f9605cfc963ed50c8ee11ccd2babfaf21`
