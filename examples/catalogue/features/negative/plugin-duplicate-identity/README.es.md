# Plugin Duplicate Identity

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> The subject is a composition where two DIFFERENT artifacts claim the same plugin identity, and
> the refusal has to say which of them claimed it first, and that the composition is refused.

## Las etapas y los pasos

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `Plugin admission refused for`, `DuplicateIdentity`, `uppercase.jar`, `the same identity was already claimed by`, `example-uppercase-plugin-0.1.0.jar`, `the whole composition is refused` |

## Que necesita

- plugin-jar

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/plugin-duplicate-identity/plugin-duplicate-identity.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-duplicate-identity/plugin-duplicate-identity.pipeline.kts
```

## Su linaje

- **Nombre historico:** `plugin-duplicate-identity.pipeline.kts`
- **Ruta de origen:** `examples/negative/plugin-duplicate-identity.pipeline.kts`
- **Huella del cuerpo:** `sha256:be65ae24ae7937ec553bf5ba1774598f0644bf3c82dd247e283823722c728349`
- **Huella del origen:** `sha256:2d2f9aeda60d64115ce5b5a6b3adecf9858abe3f450816aa85787cdd94ae0450`
