# Withenv Pipeline

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `steps`

**Resultado esperado:** `success`

## Que ejercita

Los pasos del DSL, uno a uno, y que garantiza cada uno

## Las etapas y los pasos

### 1. `envtest`

- `sh` — `"""export MY_VAR=test_value && echo 'MY_VAR is set' && test "${'$'}MY_VAR" = test_value...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/steps/withEnv-pipeline/withEnv-pipeline.pipeline.kts
pipelinek run      examples/catalogue/features/steps/withEnv-pipeline/withEnv-pipeline.pipeline.kts
```

## Su linaje

- **Nombre historico:** `08-withEnv-pipeline.pipeline.kts`
- **Ruta de origen:** `examples/steps/08-withEnv-pipeline.pipeline.kts`
- **Huella del cuerpo:** `sha256:f08d864eca0a35aba1bdebf40fd3e7c90ba3bccd024ec23c657cbcf388dc03d0`
