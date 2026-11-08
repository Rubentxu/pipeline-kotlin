# Trusted Plugin

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `plugins`

**Resultado esperado:** `success`

## Que ejercita

Los pasos que aporta un plugin, y las capacidades que declara

*El ejemplo dice de si mismo:*

> The subject is the operator's trust policy over a plugin they bring.

## Las etapas y los pasos

### 1. `External`

- `uppercase` — `hello` — un paso que aporta este plugin (`example-uppercase-plugin`)

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `registrystep-0 finished` |
| stderr contiene | `Discovered external Step plugins: example.uppercase`, `admitted under the operator's trust policy` |

## Que necesita

- plugin-jar
- plugin-trust

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/plugins/trusted-plugin/trusted-plugin.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/trusted-plugin/trusted-plugin.pipeline.kts
```

## Su linaje

- **Nombre historico:** `trusted-plugin.pipeline.kts`
- **Ruta de origen:** `examples/plugins/trusted-plugin.pipeline.kts`
- **Huella del cuerpo:** `sha256:c3b73133a7684032bedbe408a47c4ffa0fbb075856611352a7862b6cc5354c26`
- **Huella del origen:** `sha256:735f7c0a425450edb4f3b1796f4ac3a0ca7fd0941c8a0821866bff6edad6c8be`
