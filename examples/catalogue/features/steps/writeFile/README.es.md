# Writefile

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `steps`

**Resultado esperado:** `success`

## Que ejercita

Los pasos del DSL, uno a uno, y que garantiza cada uno

*El ejemplo dice de si mismo:*

> The capability under trial is the WRITE, and a write needs no store: what
> lands on disk and what the next step reads back are decided by the
> filesystem. The directive says so, and the launcher refuses this scenario if
> it ever needs the store to keep its promise.

## Las etapas y los pasos

### 1. `writefile-step`

- `writeFile` — `file = "out.txt", text = "hello"`
- `sh` — `grep -qx hello out.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/steps/writeFile/writeFile.pipeline.kts
pipelinek run      examples/catalogue/features/steps/writeFile/writeFile.pipeline.kts
```

## Su linaje

- **Nombre historico:** `17-writeFile.pipeline.kts`
- **Ruta de origen:** `examples/steps/17-writeFile.pipeline.kts`
- **Huella del cuerpo:** `sha256:774e8e2f9950da837ed013d1170ac2980a55f5e9286b6be00218fed5b7b5cfc4`
