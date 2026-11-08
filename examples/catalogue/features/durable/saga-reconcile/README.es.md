# Saga Reconcile

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `failure`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> A Saga whose COMPENSATION happened, lost its answer, and was RECONCILED.

## Las etapas y los pasos

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", // The provider RECOGNISES ITS OWN KEYS, so the engine may ask it...` — un paso que aporta este plugin (`example-saga-plugin`)
- `post` — el bloque de pasos que se ejecutan tras la etapa
  - `always` — el bloque de pasos que siempre se ejecutan
    - `sh` — `"test \"\$(grep -c '^reserve:' '$home/effects.log')\" = 1 " + "&& test \"\$(grep -c '^r...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `the write-ahead for reserve was refused`, `saga [checkout]: SETTLED` |

## Que necesita

- resume

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/saga-reconcile/saga-reconcile.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-reconcile/saga-reconcile.pipeline.kts
```

## Su linaje

- **Nombre historico:** `37-saga-reconcile.pipeline.kts`
- **Ruta de origen:** `examples/durable/37-saga-reconcile.pipeline.kts`
- **Huella del cuerpo:** `sha256:4de15cc45ebe71074cfc6b5e2cbe99ab47074b0e9249528ee7d81cc316400901`
- **Huella del origen:** `sha256:c3cdeabc05345aa075ec4909c548d7becc653c1a0990089dad70c07a51500375`
