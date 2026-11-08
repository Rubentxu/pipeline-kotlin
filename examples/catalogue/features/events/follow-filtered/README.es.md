# Follow Filtered

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `events`

**Resultado esperado:** `success`

## Que ejercita

La via de eventos: que le cuenta una corrida a un consumidor, y en que orden

*El ejemplo dice de si mismo:*

> THE GUARANTEE UNDER TRIAL

## Las etapas y los pasos

### 1. `first`

- `sh` — `echo 'the step that ends the run'`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- events-follow

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/events/follow-filtered/follow-filtered.pipeline.kts
pipelinek run      examples/catalogue/features/events/follow-filtered/follow-filtered.pipeline.kts
```

## Su linaje

- **Nombre historico:** `06-follow-filtered.pipeline.kts`
- **Ruta de origen:** `examples/events/06-follow-filtered.pipeline.kts`
- **Huella del cuerpo:** `sha256:c94ec18c0c22a92c37ceef7b2f3f474702d7b193d24023d8897353827ace9f66`
- **Huella del origen:** `sha256:19d94d77f631051a6958d0db8685cb560cb25cd035c70d13997c2dd846fc36aa`
