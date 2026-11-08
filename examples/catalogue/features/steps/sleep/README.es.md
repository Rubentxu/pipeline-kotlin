# Sleep

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `steps`

**Resultado esperado:** `success`

## Que ejercita

Los pasos del DSL, uno a uno, y que garantiza cada uno

*El ejemplo dice de si mismo:*

> The subject is the 'sleep' Step: the run must not continue before the
> requested delay has elapsed.

## Las etapas y los pasos

### 1. `sleep-step`

- `sh` — `date +%s > sleep-started`
- `sleep` — `1`
- `sh` — `date +%s > sleep-ended`
- `sh` — `"test \"$(( $(cat sleep-ended) - $(cat sleep-started) ))\" -ge 1"`
- `echo` — `woke`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `woke` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/steps/sleep/sleep.pipeline.kts
pipelinek run      examples/catalogue/features/steps/sleep/sleep.pipeline.kts
```

## Su linaje

- **Nombre historico:** `16-sleep.pipeline.kts`
- **Ruta de origen:** `examples/steps/16-sleep.pipeline.kts`
- **Huella del cuerpo:** `sha256:6be132a30975c0cbe58d952e161e7b770ffe6e7d58fb7a3ed0b8107eaac23e0b`
- **Huella del origen:** `sha256:72e18772ecdb1904f128a74ef422763efbf24b35ad2ef1aaebe0a35d044ee01f`
