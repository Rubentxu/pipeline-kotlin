# Read Only Verb Streams

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `output`

**Resultado esperado:** `success`

## Que ejercita

El plano de salida: que produjo un paso, y como se vuelve a leer

*El ejemplo dice de si mismo:*

> The two streams of a read-only verb, at the boundary of the process.

## Las etapas y los pasos

### 1. `streams`

- `sh` — `""" '${product}' capabilities > capabilities.out 2> capabilities.err echo ${'$'}? > cap...`
- `sh` — `""" '${product}' sdk-info > sdk-info.out 2> sdk-info.err echo ${'$'}? > sdk-info.code i...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `CAPABILITIES_ANSWER_ON_STDOUT`, `SDK_INFO_ANSWER_ON_STDOUT`, `DOCTOR_ANSWER_ON_STDOUT`, `CAPABILITIES_REFUSAL_ON_STDERR` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/output/read-only-verb-streams/read-only-verb-streams.pipeline.kts
pipelinek run      examples/catalogue/features/output/read-only-verb-streams/read-only-verb-streams.pipeline.kts
```

## Su linaje

- **Nombre historico:** `34-read-only-verb-streams.pipeline.kts`
- **Ruta de origen:** `examples/output/34-read-only-verb-streams.pipeline.kts`
- **Huella del cuerpo:** `sha256:41ef9b77264e6d575b5f7635040a3bbe57696db20be03e34c774ce97196488ac`
- **Huella del origen:** `sha256:28ff50a0ed82b8fef6920ae798af4ec8716bc841cf82e7980eacf53f330393ed`
