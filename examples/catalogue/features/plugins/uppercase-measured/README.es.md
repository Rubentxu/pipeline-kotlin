# Uppercase Measured

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `plugins`

**Resultado esperado:** `success`

## Que ejercita

Los pasos que aporta un plugin, y las capacidades que declara

*El ejemplo dice de si mismo:*

> The subject is an external plugin's step that asks the RUNTIME to observe its own work.

## Las etapas y los pasos

### 1. `External`

- `uppercaseMeasured` — `hello` — un paso que aporta este plugin (`example-uppercase-plugin`)

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `registrystep-0 finished` |
| stderr contiene | `Discovered external Step plugins: example.uppercase` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/plugins/uppercase-measured/uppercase-measured.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/uppercase-measured/uppercase-measured.pipeline.kts
```

## Su linaje

- **Nombre historico:** `uppercase-measured.pipeline.kts`
- **Ruta de origen:** `examples/plugins/uppercase-measured.pipeline.kts`
- **Huella del cuerpo:** `sha256:c8bb7daff071ddb0f900632229d907b40bf485aa3de27435c8bf16b1a33024c1`
- **Huella del origen:** `sha256:ded442496c3df5d00b5fcb67b38ec00604859662f9c5121b51a7058a3e06077a`
