# Saga Settled

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `plugin`, `process`, `shell`

**Expected outcome:** `success`

## What it exercises

Steps that come from a plugin, and the capabilities they declare

*The example says this about itself:*

> The subject is a SAGA that settles: the engine ran the actions of the
> chain, in order, and reported the chain as SETTLED.

## The stages and the steps

### 1. `checkout`

- `sagaChain` — `sagaId = "canary-checkout", actions = listOf( SagaChainScript( actionId = "reserve", co...` — a step this plugin provides (`example-saga-plugin`)
- `sh` — `"test \"$(wc -l < '$log')\" -eq 2"`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stderr contains | `saga [canary-checkout]: SETTLED` |
| files | `out/canary-saga-effects.log` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- el plugin de saga (example.saga) viene en la imagen de la candidata

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/saga-settled/saga-settled.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/saga-settled/saga-settled.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `saga-settled.pipeline.kts`
- **Origin path:** `scenarios/canary/saga-settled.pipeline.kts`
- **Body digest:** `sha256:19047751154e49e9bc7cb6bf8015c96a6fa34b8ca190f6d2d430bba449cd6659`
