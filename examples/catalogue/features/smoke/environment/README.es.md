# Environment

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `smoke`

**Resultado esperado:** `success`

## Que ejercita

El camino mas corto por el motor: un pipeline que arranca, corre y termina

## Las etapas y los pasos

### 1. `build`

- `withEnv(listOf("KEY=value", "BUILD_VERSION=1.0.0"))` — un bloque de entorno
  - `echo` — `building...`
  - `sh` — `"""test "${'$'}KEY" = value && test "${'$'}BUILD_VERSION" = 1.0.0"""`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/smoke/environment/environment.pipeline.kts
pipelinek run      examples/catalogue/features/smoke/environment/environment.pipeline.kts
```

## Su linaje

- **Nombre historico:** `02-environment.pipeline.kts`
- **Ruta de origen:** `examples/smoke/02-environment.pipeline.kts`
- **Huella del cuerpo:** `sha256:ccc7c8e2cd05228e818050409348b8b25cc94cb950c671835e306e40be7b4b91`
