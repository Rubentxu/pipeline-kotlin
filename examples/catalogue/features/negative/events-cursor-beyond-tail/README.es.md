# Events Cursor Beyond Tail

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
| stderr contiene | `Error: cursor`, `past the tail` |

## Que necesita

- durable-once
- events-refusal

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/events-cursor-beyond-tail/events-cursor-beyond-tail.pipeline.kts
pipelinek run      examples/catalogue/features/negative/events-cursor-beyond-tail/events-cursor-beyond-tail.pipeline.kts
```

## Su linaje

- **Nombre historico:** `events-cursor-beyond-tail.pipeline.kts`
- **Ruta de origen:** `examples/negative/events-cursor-beyond-tail.pipeline.kts`
- **Huella del cuerpo:** `sha256:7d529348274391fdcc084cc8dd398522b6c0ef4bfc61d5a0d3df26ddb0209eea`
- **Huella del origen:** `sha256:9eb761a6b74a6a13fec5bc1c1bbeebaf75550dac388cac19fba63b5e2786ecdf`
