# Writefile In Warnerror

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `control-flow`

**Resultado esperado:** `unstable`

## Que ejercita

Que rama de una bifurcacion se ejecuta, y cuantas veces corre el cuerpo de un bucle

*El ejemplo dice de si mismo:*

> THE GUARANTEE UNDER TRIAL

## Las etapas y los pasos

### 1. `writefile-en-warnerror`

- `warnError(message = "aviso sin fallo")` — un bloque de aviso
  - `sh` — `printf antes > avisado.txt`
  - `writeFile` — `file = "avisado.txt", text = "café", encoding = "UTF-8"`
  - `sh` — `"od -An -tx1 -N5 avisado.txt | tr -d \" \\n\" | grep -qx 636166c3a9 && echo fichero-avi...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `unstable` |
| stdout contiene | `fichero-avisado` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/control-flow/writefile-in-warnerror/writefile-in-warnerror.pipeline.kts
pipelinek run      examples/catalogue/features/control-flow/writefile-in-warnerror/writefile-in-warnerror.pipeline.kts
```

## Su linaje

- **Nombre historico:** `13-writefile-in-warnerror.pipeline.kts`
- **Ruta de origen:** `examples/control-flow/13-writefile-in-warnerror.pipeline.kts`
- **Huella del cuerpo:** `sha256:7ab3ff0d4c00ceccc18c800dbe2877dcfb3f5f5b9bd250c39b21157ff18aa52d`
- **Huella del origen:** `sha256:df3e9960bfc46be79aa26c5cc76cc36f0f4e57b3a71c56c386f5680b09290af0`
