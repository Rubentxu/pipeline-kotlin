# Env Model Applied Once

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `steps`

**Resultado esperado:** `success`

## Que ejercita

Los pasos del DSL, uno a uno, y que garantiza cada uno

## Las etapas y los pasos

### 1. `env`

- `withEnv(mapOf("JAVA_HOME" to "/opt/jdk", "PATH" to "/usr/bin:/bin"))` — un bloque de entorno
  - `sh` — `"""test "${'$'}PATH" = "/opt/jdk/bin:/usr/bin:/bin" || { printf 'OBSERVED_PATH=%s\n' "$...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/steps/env-model-applied-once/env-model-applied-once.pipeline.kts
pipelinek run      examples/catalogue/features/steps/env-model-applied-once/env-model-applied-once.pipeline.kts
```

## Su linaje

- **Nombre historico:** `35-env-model-applied-once.pipeline.kts`
- **Ruta de origen:** `examples/steps/35-env-model-applied-once.pipeline.kts`
- **Huella del cuerpo:** `sha256:a4a51bdb166892d6b64fb16d702c59a6ce1d45b25aaa00979487e8397e53f22d`
- **Huella del origen:** `sha256:a4a51bdb166892d6b64fb16d702c59a6ce1d45b25aaa00979487e8397e53f22d`
