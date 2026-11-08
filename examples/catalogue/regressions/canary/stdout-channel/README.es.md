# Stdout Channel

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `process`, `shell`

**Resultado esperado:** `success`

## Que ejercita

La vida de una corrida: como arranca, como acaba y que concluye

*El ejemplo dice de si mismo:*

> The subject is the CHANNEL: what a step prints has to reach the run's
> stdout, and the oracle has to be able to see it there.

## Las etapas y los pasos

### 1. `stdout`

- `sh` — `printf 'CANARY-STDOUT-MARKER\\n'`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `CANARY-STDOUT-MARKER` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/stdout-channel/stdout-channel.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/stdout-channel/stdout-channel.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `stdout-channel.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/stdout-channel.pipeline.kts`
- **Huella del cuerpo:** `sha256:162b32c4e9d3e916e2c89e932ac6defc8226cdc3433b63a365500491b40fca2f`
