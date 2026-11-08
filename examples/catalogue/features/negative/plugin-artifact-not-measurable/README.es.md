# Plugin Artifact Not Measurable

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> The subject is a plugin artifact the runtime cannot read.

## Las etapas y los pasos

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `Plugin admission refused for`, `ArtifactNotMeasurable` |

## Que necesita

- plugin-jar

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/plugin-artifact-not-measurable/plugin-artifact-not-measurable.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-artifact-not-measurable/plugin-artifact-not-measurable.pipeline.kts
```

## Su linaje

- **Nombre historico:** `34-plugin-artifact-not-measurable.pipeline.kts`
- **Ruta de origen:** `examples/negative/34-plugin-artifact-not-measurable.pipeline.kts`
- **Huella del cuerpo:** `sha256:a33bc075a307139e8af8b95c8fb17e181a264f73549e285ef8b725f9950b8d66`
