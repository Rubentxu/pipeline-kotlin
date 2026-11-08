# Plugin Capability Omitted

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> The subject is a plugin whose manifest forgets to declare a capability its own code demands.

## Las etapas y los pasos

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `Plugin admission refused for`, `capabilities that differ from the contract`, `example.uppercase.announcedCased`, `declared=[example.uppercase.case-table]`, `contract=[example.uppercase.case-table, plugin.event-emission]` |

## Que necesita

- distribution

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/plugin-capability-omitted/plugin-capability-omitted.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-capability-omitted/plugin-capability-omitted.pipeline.kts
```

## Su linaje

- **Nombre historico:** `plugin-capability-omitted.pipeline.kts`
- **Ruta de origen:** `examples/negative/plugin-capability-omitted.pipeline.kts`
- **Huella del cuerpo:** `sha256:470fd0ae860f3c82a2fdce5c8d77b58cd8e9d1cd47d72feb7e8711ceb9b57eaa`
- **Huella del origen:** `sha256:1aa1fc8bad6eb24b12974101c61f82ffbfa58161e371d52820cd912ce8ed5724`
