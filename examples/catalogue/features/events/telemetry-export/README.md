# Telemetry Export

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `events`

**Expected outcome:** `success`

## What it exercises

The event lane: what a run tells a consumer, and in what order

*The example says this about itself:*

> The telemetry export, at the boundary a consumer actually reaches it.

## The stages and the steps

### 1. `telemetry`

- `sh` — `""" cat > inner.pipeline.kts <<'INNER' pipeline { stages { stage("branches") { parallel...`
- `sh` — `""" run_id="${'$'}(cat run-id.txt 2>/dev/null || true)" trace="${'$'}(cat trace-id.txt...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `TELEMETRY_EVERY_LINE_NAMES_THE_TRACE`, `TELEMETRY_BRANCHES_HAVE_A_SPAN_EACH`, `TELEMETRY_OTLP_CARRIES_THE_TRACE`, `TELEMETRY_OTLP_CARRIES_THE_METRICS`, `TELEMETRY_OTLP_IS_TWO_STANDARD_DOCUMENTS`, `TELEMETRY_OTLP_ALONE_IS_REFUSED` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/events/telemetry-export/telemetry-export.pipeline.kts
pipelinek run      examples/catalogue/features/events/telemetry-export/telemetry-export.pipeline.kts
```

## Its lineage

- **Historical name:** `03-telemetry-export.pipeline.kts`
- **Origin path:** `examples/events/03-telemetry-export.pipeline.kts`
- **Body digest:** `sha256:3524fe03a0ba3be91aa21283c3e5b660950c66ac98b94a6c43d2ab864bf5630c`
- **Origin digest:** `sha256:25b4442a4f521ab482c9a4e24cd95d9b6c79211c959d96b808468895035b4e39`
