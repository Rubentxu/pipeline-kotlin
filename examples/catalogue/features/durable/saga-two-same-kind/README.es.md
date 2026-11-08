# Saga Two Same Kind

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `failure`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

## Las etapas y los pasos

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", actions = listOf( SagaChainScript( actionId = "reserve", compensat...` — un paso que aporta este plugin (`example-saga-plugin`)
- `post` — el bloque de pasos que se ejecutan tras la etapa
  - `always` — el bloque de pasos que siempre se ejecutan
    - `sh` — `"test \"$(cat '$home/effects.log')\" = " + "\"$(printf 'example.saga.reserve.forward\\n...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `the action ship failed`, `saga [checkout]: SETTLED` |

## Que necesita

- durable-once

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/saga-two-same-kind/saga-two-same-kind.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-two-same-kind/saga-two-same-kind.pipeline.kts
```

## Su linaje

- **Nombre historico:** `40-saga-two-same-kind.pipeline.kts`
- **Ruta de origen:** `examples/durable/40-saga-two-same-kind.pipeline.kts`
- **Huella del cuerpo:** `sha256:b9204ee7c3320ee3f5012ec888488f2d1fa25e5239debfa054eab2639f71a311`
- **Huella del origen:** `sha256:771fe7cd67162b21461922bc5f770f1306246e38561451ef8149a1266c64de6d`
