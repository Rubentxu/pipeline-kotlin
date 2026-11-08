# Uppercase

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `plugins`

**Resultado esperado:** `success`

## Que ejercita

Los pasos que aporta un plugin, y las capacidades que declara

*El ejemplo dice de si mismo:*

> The subject is an external plugin's step: the DSL resolves a name it does not
> know at compile time, the plugin claims it, and the run completes.

## Las etapas y los pasos

### 1. `External`

- `uppercase` — `hello` — un paso que aporta este plugin (`example-uppercase-plugin`)

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `registrystep-0 finished` |
| stderr contiene | `Discovered external Step plugins: example.uppercase` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/plugins/uppercase/uppercase.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/uppercase/uppercase.pipeline.kts
```

## Su linaje

- **Nombre historico:** `uppercase.pipeline.kts`
- **Ruta de origen:** `examples/plugins/uppercase.pipeline.kts`
- **Huella del cuerpo:** `sha256:63ffffb0d2cb4db28ea29f888eb69a054ee298159beccde64b9ff6675871e1be`
