# Workspace Helpers

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `steps`

**Resultado esperado:** `success`

## Que ejercita

Los pasos del DSL, uno a uno, y que garantiza cada uno

## Las etapas y los pasos

### 1. `workspace-helpers`

- `pwd` — un paso que declara este ejemplo
- `echo` — `"current dir: " + dir`
- `isUnix` — un paso que declara este ejemplo
- `echo` — `"is unix: " + unix.toString()`
- `timestamps` — un bloque
  - `echo` — `output inside the announced scope`
- `sh` — `touch marker-workspace-helpers`
- `waitUntil(initialRecurrencePeriod = 100)` — un bloque de espera
  - `sh` — `test -f marker-workspace-helpers && echo READY`
- `sh` — `rm marker-workspace-helpers`
- `echo` — `waitUntil done`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `waitUntil done` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/steps/workspace-helpers/workspace-helpers.pipeline.kts
pipelinek run      examples/catalogue/features/steps/workspace-helpers/workspace-helpers.pipeline.kts
```

## Su linaje

- **Nombre historico:** `13-workspace-helpers.pipeline.kts`
- **Ruta de origen:** `examples/steps/13-workspace-helpers.pipeline.kts`
- **Huella del cuerpo:** `sha256:cd3343e943c63507643bcad501368a06bab4cc650596f68865aa8a261b10bb9a`
- **Huella del origen:** `sha256:9a87f6458cf7721c0867983e74a48834b50e452bc949b29606b36f9651bd3ff5`
