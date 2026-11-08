# Plugin Capability Lies

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> The subject is a plugin that claims to need something its own code never asks for.

## Las etapas y los pasos

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `Plugin admission refused for`, `capabilities that differ from the contract`, `example.uppercase.ghostCapability` |

## Que necesita

- distribution

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/plugin-capability-lies/plugin-capability-lies.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-capability-lies/plugin-capability-lies.pipeline.kts
```

## Su linaje

- **Nombre historico:** `plugin-capability-lies.pipeline.kts`
- **Ruta de origen:** `examples/negative/plugin-capability-lies.pipeline.kts`
- **Huella del cuerpo:** `sha256:a2d04759c8825f9417a8f69c7b5887b7f96f7cc8271fbc8f74dfb71edfe60aaa`
