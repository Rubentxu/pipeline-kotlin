# Stderr Channel

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `process`, `shell`

**Resultado esperado:** `success`

## Que ejercita

La vida de una corrida: como arranca, como acaba y que concluye

*El ejemplo dice de si mismo:*

> The subject is the OTHER channel: the engine's own launch event has to
> reach stderr.

## Las etapas y los pasos

### 1. `launch`

- `sh` — `printf 'CANARY-STEP-TEXT\\n'`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `CANARY-STEP-TEXT` |
| stderr contiene | `pipelinek.launch/1` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/stderr-channel/stderr-channel.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/stderr-channel/stderr-channel.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `stderr-channel.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/stderr-channel.pipeline.kts`
- **Huella del cuerpo:** `sha256:223a07251be3e7a83506a46dcad45637453479e60ba0ba48a28dcc7ae7cb653a`
