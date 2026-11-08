# Events Unknown Run

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
| stderr contiene | `Error: unknown run` |

## Que necesita

- durable-once
- events-refusal

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/events-unknown-run/events-unknown-run.pipeline.kts
pipelinek run      examples/catalogue/features/negative/events-unknown-run/events-unknown-run.pipeline.kts
```

## Su linaje

- **Nombre historico:** `events-unknown-run.pipeline.kts`
- **Ruta de origen:** `examples/negative/events-unknown-run.pipeline.kts`
- **Huella del cuerpo:** `sha256:0dac869feb13165cb131daad644b645192285ef85182f5e816e06e375736e30b`
