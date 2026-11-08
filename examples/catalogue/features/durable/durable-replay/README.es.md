# Durable Replay

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `success`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> 32-durable-replay.pipeline.kts — the ONE real replay guarantee.

## Las etapas y los pasos

### 1. `replay`

- `sh` — `printf 'effect\\n' >> '$marker'`
- `sh` — `"test \"\$(wc -l < '$marker')\" -eq 1 && echo 'effects=1'"`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `effects=1` |

## Que necesita

- durable

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/durable-replay/durable-replay.pipeline.kts
pipelinek run      examples/catalogue/features/durable/durable-replay/durable-replay.pipeline.kts
```

## Su linaje

- **Nombre historico:** `32-durable-replay.pipeline.kts`
- **Ruta de origen:** `examples/durable/32-durable-replay.pipeline.kts`
- **Huella del cuerpo:** `sha256:e171865d064a94a5f3dad196eab96bcadfeb867fc874b2f4dd246dd5f776c476`
- **Huella del origen:** `sha256:34b1cc10e4d96e0c31093283f3fefda2d81b648662df08d8d3055393ab32c58b`
