# Failure Stage

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `events`

**Resultado esperado:** `failure`

## Que ejercita

La via de eventos: que le cuenta una corrida a un consumidor, y en que orden

*El ejemplo dice de si mismo:*

> THE GUARANTEE UNDER TRIAL

## Las etapas y los pasos

### 1. `first`

- `sh` — `echo 'this one works'`

### 2. `second`

- `sh` — `exit 4`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |

## Que necesita

- failure-stage

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/events/failure-stage/failure-stage.pipeline.kts
pipelinek run      examples/catalogue/features/events/failure-stage/failure-stage.pipeline.kts
```

## Su linaje

- **Nombre historico:** `04-failure-stage.pipeline.kts`
- **Ruta de origen:** `examples/events/04-failure-stage.pipeline.kts`
- **Huella del cuerpo:** `sha256:88ae5cef033e3f3f8b52b82db03202e334aa4bb40149543563356dc2fbedbcfb`
- **Huella del origen:** `sha256:a167e39f86259d410103ab5e5866d3e5cbebaaaa2f67b0af9836c78042cb4955`
