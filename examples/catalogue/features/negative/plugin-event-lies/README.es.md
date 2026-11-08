# Plugin Event Lies

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> The subject is a plugin that declares an Event nobody contributes.

## Las etapas y los pasos

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `Plugin admission refused for`, `declared Events with no contributor`, `example.uppercase.ghostEvent` |

## Que necesita

- distribution

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/plugin-event-lies/plugin-event-lies.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-event-lies/plugin-event-lies.pipeline.kts
```

## Su linaje

- **Nombre historico:** `plugin-event-lies.pipeline.kts`
- **Ruta de origen:** `examples/negative/plugin-event-lies.pipeline.kts`
- **Huella del cuerpo:** `sha256:c6cbe7932257d57ba8d27404072a064cba0dce189cc25c2a888d549f4f82fb58`
