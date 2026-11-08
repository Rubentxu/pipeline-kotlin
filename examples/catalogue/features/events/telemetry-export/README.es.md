# Telemetry Export

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `events`

**Resultado esperado:** `success`

## Que ejercita

La via de eventos: que le cuenta una corrida a un consumidor, y en que orden

*El ejemplo dice de si mismo:*

> The telemetry export, at the boundary a consumer actually reaches it.

## Las etapas y los pasos

### 1. `telemetry`

- `sh` — `""" cat > inner.pipeline.kts <<'INNER' pipeline { stages { stage("branches") { parallel...`
- `sh` — `""" run_id="${'$'}(cat run-id.txt 2>/dev/null || true)" trace="${'$'}(cat trace-id.txt...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `TELEMETRY_EVERY_LINE_NAMES_THE_TRACE`, `TELEMETRY_BRANCHES_HAVE_A_SPAN_EACH`, `TELEMETRY_OTLP_CARRIES_THE_TRACE`, `TELEMETRY_OTLP_CARRIES_THE_METRICS`, `TELEMETRY_OTLP_IS_TWO_STANDARD_DOCUMENTS`, `TELEMETRY_OTLP_ALONE_IS_REFUSED` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/events/telemetry-export/telemetry-export.pipeline.kts
pipelinek run      examples/catalogue/features/events/telemetry-export/telemetry-export.pipeline.kts
```

## Su linaje

- **Nombre historico:** `03-telemetry-export.pipeline.kts`
- **Ruta de origen:** `examples/events/03-telemetry-export.pipeline.kts`
- **Huella del cuerpo:** `sha256:3524fe03a0ba3be91aa21283c3e5b660950c66ac98b94a6c43d2ab864bf5630c`
