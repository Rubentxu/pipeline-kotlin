# Non Canonical Step Refusal

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> THE GUARANTEE UNDER TRIAL

## Las etapas y los pasos

### 1. `never-runs`

- `registryStep` — `PluginStepId("custom.notCanonical"), EncodedStepValue("{}")`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `custom.notCanonical` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/non-canonical-step-refusal/non-canonical-step-refusal.pipeline.kts
pipelinek run      examples/catalogue/features/negative/non-canonical-step-refusal/non-canonical-step-refusal.pipeline.kts
```

## Su linaje

- **Nombre historico:** `non-canonical-step-refusal.pipeline.kts`
- **Ruta de origen:** `examples/negative/non-canonical-step-refusal.pipeline.kts`
- **Huella del cuerpo:** `sha256:7403f7f91be9ba28ccac778e2cf4d45b37b5c2ec69c7897bd2f0f173d02d27ec`
