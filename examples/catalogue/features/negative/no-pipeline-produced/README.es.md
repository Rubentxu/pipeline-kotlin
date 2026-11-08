# No Pipeline Produced

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> THE GUARANTEE UNDER TRIAL

## Las etapas y los pasos

_The pipeline declares no etapa._

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `did not produce a PipelineSpec` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/no-pipeline-produced/no-pipeline-produced.pipeline.kts
pipelinek run      examples/catalogue/features/negative/no-pipeline-produced/no-pipeline-produced.pipeline.kts
```

## Su linaje

- **Nombre historico:** `no-pipeline-produced.pipeline.kts`
- **Ruta de origen:** `examples/negative/no-pipeline-produced.pipeline.kts`
- **Huella del cuerpo:** `sha256:1d88c20ed3a3d276a1e7c06faf5e394297c0f0dfd308b8bbad552f1ba53ad19a`
