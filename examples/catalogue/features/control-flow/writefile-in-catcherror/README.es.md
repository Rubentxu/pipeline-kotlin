# Writefile In Catcherror

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `control-flow`

**Resultado esperado:** `success`

## Que ejercita

Que rama de una bifurcacion se ejecuta, y cuantas veces corre el cuerpo de un bucle

*El ejemplo dice de si mismo:*

> THE GUARANTEE UNDER TRIAL

## Las etapas y los pasos

### 1. `writefile-en-catcherror`

- `catchError` — un bloque de pasos de recuperacion
  - `sh` — `printf antes > capturado.txt`
  - `writeFile` — `file = "capturado.txt", text = "café", encoding = "UTF-8"`
  - `sh` — `"od -An -tx1 -N5 capturado.txt | tr -d \" \\n\" | grep -qx 636166c3a9 && echo fichero-c...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `fichero-capturado` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/control-flow/writefile-in-catcherror/writefile-in-catcherror.pipeline.kts
pipelinek run      examples/catalogue/features/control-flow/writefile-in-catcherror/writefile-in-catcherror.pipeline.kts
```

## Su linaje

- **Nombre historico:** `12-writefile-in-catcherror.pipeline.kts`
- **Ruta de origen:** `examples/control-flow/12-writefile-in-catcherror.pipeline.kts`
- **Huella del cuerpo:** `sha256:d00511c3614d6cb24517bfab56adcba17a3292439f532ff9b2d8883ed4b4610b`
- **Huella del origen:** `sha256:5dcff692a9f5aa780c916193785242004b7f36d7a54f2070db5c147880b9c5af`
