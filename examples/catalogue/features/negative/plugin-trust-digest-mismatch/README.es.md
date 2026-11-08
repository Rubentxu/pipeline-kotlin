# Plugin Trust Digest Mismatch

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> The subject is the swap: the operator approved some bytes, and the bytes on disk are not them.

## Las etapas y los pasos

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `Plugin admission refused for`, `RefusedDigestMismatch` |

## Que necesita

- plugin-jar
- plugin-trust

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/plugin-trust-digest-mismatch/plugin-trust-digest-mismatch.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-trust-digest-mismatch/plugin-trust-digest-mismatch.pipeline.kts
```

## Su linaje

- **Nombre historico:** `plugin-trust-digest-mismatch.pipeline.kts`
- **Ruta de origen:** `examples/negative/plugin-trust-digest-mismatch.pipeline.kts`
- **Huella del cuerpo:** `sha256:9bfac5726a5623b59a6c565c14e23f801480e3f522997abdf8b87dc378f19e5b`
- **Huella del origen:** `sha256:42b5622cdc4b464d87d7711fbfc7221f9605cfc963ed50c8ee11ccd2babfaf21`
