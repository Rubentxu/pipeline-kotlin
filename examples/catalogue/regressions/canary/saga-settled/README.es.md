# Saga Settled

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `plugin`, `process`, `shell`

**Resultado esperado:** `success`

## Que ejercita

Pasos que aporta un plugin, y las capacidades que declara

*El ejemplo dice de si mismo:*

> The subject is a SAGA that settles: the engine ran the actions of the
> chain, in order, and reported the chain as SETTLED.

## Las etapas y los pasos

### 1. `checkout`

- `sagaChain` — `sagaId = "canary-checkout", actions = listOf( SagaChainScript( actionId = "reserve", co...` — un paso que aporta este plugin (`example-saga-plugin`)
- `sh` — `"test \"$(wc -l < '$log')\" -eq 2"`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stderr contiene | `saga [canary-checkout]: SETTLED` |
| ficheros | `out/canary-saga-effects.log` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- el plugin de saga (example.saga) viene en la imagen de la candidata

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/saga-settled/saga-settled.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/saga-settled/saga-settled.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `saga-settled.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/saga-settled.pipeline.kts`
- **Huella del cuerpo:** `sha256:19047751154e49e9bc7cb6bf8015c96a6fa34b8ca190f6d2d430bba449cd6659`
