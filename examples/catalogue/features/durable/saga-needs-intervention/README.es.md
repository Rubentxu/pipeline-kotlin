# Saga Needs Intervention

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `failure`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> The SAME chain as the scenario before it, with ONE thing changed: the provider cannot
> promise that a failed call left nothing behind.

## Las etapas y los pasos

### 1. `Checkout`

- `sagaChain` — `sagaId = "checkout", // THE ONE DIFFERENCE. The provider cannot promise that a failed c...` — un paso que aporta este plugin (`example-saga-plugin`)
- `post` — el bloque de pasos que se ejecutan tras la etapa
  - `always` — el bloque de pasos que siempre se ejecutan
    - `sh` — `"test \"$(cat '$home/effects.log')\" = \"$(printf 'reserve\\ncharge')\" " + "|| { echo...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `the action ship failed`, `saga [checkout]: NEEDS_INTERVENTION (UNKNOWN_EFFECT)` |

## Que necesita

- durable-once

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/saga-needs-intervention/saga-needs-intervention.pipeline.kts
pipelinek run      examples/catalogue/features/durable/saga-needs-intervention/saga-needs-intervention.pipeline.kts
```

## Su linaje

- **Nombre historico:** `34-saga-needs-intervention.pipeline.kts`
- **Ruta de origen:** `examples/durable/34-saga-needs-intervention.pipeline.kts`
- **Huella del cuerpo:** `sha256:165a16a2d7d06723dac0540f42edaf2ad7c01bc50bff6955e896e3f7344a5d1b`
- **Huella del origen:** `sha256:0d9a4a2e7dd66e2782fb962e7d029e31a0b47ea8868b031808d6a86002139547`
