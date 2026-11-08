# Live Process Output

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `output`

**Resultado esperado:** `success`

## Que ejercita

El plano de salida: que produjo un paso, y como se vuelve a leer

*El ejemplo dice de si mismo:*

> The output of a PROCESS has to reach the terminal, not only the output of
> a script that echoes a value back.

## Las etapas y los pasos

### 1. `live`

- `sh` — `"echo RAW_PROCESS_MARKER", isScriptBlock = false, returnStdout = false`
- `sh` — `"echo STDERR_ONLY_MARKER >&2", isScriptBlock = false, returnStdout = false`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `RAW_PROCESS_MARKER` |
| la transcripcion contiene | `STDERR_ONLY_MARKER` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/output/live-process-output/live-process-output.pipeline.kts
pipelinek run      examples/catalogue/features/output/live-process-output/live-process-output.pipeline.kts
```

## Su linaje

- **Nombre historico:** `33-live-process-output.pipeline.kts`
- **Ruta de origen:** `examples/output/33-live-process-output.pipeline.kts`
- **Huella del cuerpo:** `sha256:c317f8301f2461f10dc5c7315be0ce38d6c5afe0a8af81981573c6ceb2ea2f76`
