# Events No Event History

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> THE GUARANTEE UNDER TRIAL

## Las etapas y los pasos

### 1. `Fixture`

- `echo` — `this run exists, and is what the refusal is asked about`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `Error: db has no event history` |

## Que necesita

- durable-once
- events-refusal

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/events-no-event-history/events-no-event-history.pipeline.kts
pipelinek run      examples/catalogue/features/negative/events-no-event-history/events-no-event-history.pipeline.kts
```

## Su linaje

- **Nombre historico:** `events-no-event-history.pipeline.kts`
- **Ruta de origen:** `examples/negative/events-no-event-history.pipeline.kts`
- **Huella del cuerpo:** `sha256:c5b60d1550acbf9bf02225f93c33212cc37ab4c6c2ea93c591a007fe397beaac`
- **Huella del origen:** `sha256:cc4d4dff57266a77ec0423dc8edf78cfb9201bd4ad513f8ed334be4e6cb8ca3c`
