# Body Slots

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `plugins`

**Resultado esperado:** `success`

## Que ejercita

Los pasos que aporta un plugin, y las capacidades que declara

## Las etapas y los pasos

### 1. `Slots`

- `slotPlan` — `forwardCommand = "echo forward >> '$home/forward.log'; exit 1", compensateCommand = "ec...` — un paso que aporta este plugin (`example-block-plugin`)
- `sh` — `test -f '$home/compensated.log'`
- `sh` — `cat '$home/compensated.log'`
- `slotPlan` — `forwardCommand = "echo forward >> '$home/forward.log'", compensateCommand = "echo compe...` — un paso que aporta este plugin (`example-block-plugin`)
- `sh` — `test ! -f '$home/should-not-exist.log'`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `compensated` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/plugins/body-slots/body-slots.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/body-slots/body-slots.pipeline.kts
```

## Su linaje

- **Nombre historico:** `body-slots.pipeline.kts`
- **Ruta de origen:** `examples/plugins/body-slots.pipeline.kts`
- **Huella del cuerpo:** `sha256:417ee94f9ade9e6dae6be56f27ab2ea9125d2e7c3a9c521e8e5a202ddab2ff06`
- **Huella del origen:** `sha256:f36dd7cec30a67b935423f0a9fe0fbcafcc6cdb91ea963e504502641aeff4391`
