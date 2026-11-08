# Plugin Directive Lies

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> The subject is a plugin that declares a Directive nobody contributes.

## Las etapas y los pasos

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `Plugin admission refused for`, `declared Directives with no contributor`, `example.uppercase.ghostDirective` |

## Que necesita

- distribution

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/plugin-directive-lies/plugin-directive-lies.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-directive-lies/plugin-directive-lies.pipeline.kts
```

## Su linaje

- **Nombre historico:** `plugin-directive-lies.pipeline.kts`
- **Ruta de origen:** `examples/negative/plugin-directive-lies.pipeline.kts`
- **Huella del cuerpo:** `sha256:1456ff014f64d7ff9184d2d8de3b2e63ad0d574809e99cf65852bc1ad2f8e8cc`
