# Live Spans Without A Collector

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `events`

**Resultado esperado:** `success`

## Que ejercita

La via de eventos: que le cuenta una corrida a un consumidor, y en que orden

*El ejemplo dice de si mismo:*

> The live spans, with NO collector to receive them.

## Las etapas y los pasos

### 1. `live-spans`

- `sh` — `""" cat > inner.pipeline.kts <<'INNER' pipeline { stages { stage("probe") { retry(3) {...`
- `sh` — `""" if PIPELINEK_OTEL_ENABLED=true OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:1 OTEL_...`
- `sh` — `""" if grep -qi opentelemetry live.err; then echo LIVE_SPANS_THE_EXPORTER_FAILURE_IS_RE...`
- `sh` — `""" if grep -q INNER_ATTEMPT_2 live.out; then echo LIVE_SPANS_RETRY_HAPPENED_UNDER_THE_...`
- `sh` — `""" if env -u PIPELINEK_OTEL_ENABLED '${product}' run --isolated --db quiet.db --contro...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `LIVE_SPANS_RUN_SURVIVES_AN_UNREACHABLE_COLLECTOR`, `LIVE_SPANS_THE_EXPORTER_FAILURE_IS_REPORTED`, `LIVE_SPANS_RETRY_HAPPENED_UNDER_THE_LIVE_SPANS`, `LIVE_SPANS_DISABLED_COSTS_NOTHING` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/events/live-spans-without-a-collector/live-spans-without-a-collector.pipeline.kts
pipelinek run      examples/catalogue/features/events/live-spans-without-a-collector/live-spans-without-a-collector.pipeline.kts
```

## Su linaje

- **Nombre historico:** `05-live-spans-without-a-collector.pipeline.kts`
- **Ruta de origen:** `examples/events/05-live-spans-without-a-collector.pipeline.kts`
- **Huella del cuerpo:** `sha256:0d7700143d6ec752b3f225352ac48114719397cd3f4ae9d781330e6feda6d1c9`
