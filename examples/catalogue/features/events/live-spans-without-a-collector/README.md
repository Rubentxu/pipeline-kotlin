# Live Spans Without A Collector

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `events`

**Expected outcome:** `success`

## What it exercises

The event lane: what a run tells a consumer, and in what order

*The example says this about itself:*

> The live spans, with NO collector to receive them.

## The stages and the steps

### 1. `live-spans`

- `sh` — `""" cat > inner.pipeline.kts <<'INNER' pipeline { stages { stage("probe") { retry(3) {...`
- `sh` — `""" if PIPELINEK_OTEL_ENABLED=true OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:1 OTEL_...`
- `sh` — `""" if grep -qi opentelemetry live.err; then echo LIVE_SPANS_THE_EXPORTER_FAILURE_IS_RE...`
- `sh` — `""" if grep -q INNER_ATTEMPT_2 live.out; then echo LIVE_SPANS_RETRY_HAPPENED_UNDER_THE_...`
- `sh` — `""" if env -u PIPELINEK_OTEL_ENABLED '${product}' run --isolated --db quiet.db --contro...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `LIVE_SPANS_RUN_SURVIVES_AN_UNREACHABLE_COLLECTOR`, `LIVE_SPANS_THE_EXPORTER_FAILURE_IS_REPORTED`, `LIVE_SPANS_RETRY_HAPPENED_UNDER_THE_LIVE_SPANS`, `LIVE_SPANS_DISABLED_COSTS_NOTHING` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/events/live-spans-without-a-collector/live-spans-without-a-collector.pipeline.kts
pipelinek run      examples/catalogue/features/events/live-spans-without-a-collector/live-spans-without-a-collector.pipeline.kts
```

## Its lineage

- **Historical name:** `05-live-spans-without-a-collector.pipeline.kts`
- **Origin path:** `examples/events/05-live-spans-without-a-collector.pipeline.kts`
- **Body digest:** `sha256:0d7700143d6ec752b3f225352ac48114719397cd3f4ae9d781330e6feda6d1c9`
- **Origin digest:** `sha256:08e599c064e960d47a941b09baa2fd4eb0b0c4079b440de6347391d312bf089d`
