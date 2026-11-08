# Platform Queries

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `durable`

**Resultado esperado:** `success`

## Que ejercita

El diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera

*El ejemplo dice de si mismo:*

> Two platform queries in one scenario: the boolean the platform reports, and
> the directory it hands out.

## Las etapas y los pasos

### 1. `platform-queries`

- `isUnix` — un paso que declara este ejemplo
- `echo` — `is unix: true`
- `echo` — `is unix: false`
- `pwd` — `tmp = true` — un paso que declara este ejemplo
- `echo` — `"pwd(tmp=true) is the workspace placeholder: " + (dir == "<workspace>")`
- `pwd` — un paso que declara este ejemplo
- `echo` — `"pwd() is the workspace placeholder: " + (legacy == "<workspace>")`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `is unix: true`, `pwd(tmp=true) is the workspace placeholder: true`, `pwd() is the workspace placeholder: true` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/durable/platform-queries/platform-queries.pipeline.kts
pipelinek run      examples/catalogue/features/durable/platform-queries/platform-queries.pipeline.kts
```

## Su linaje

- **Nombre historico:** `19-platform-queries.pipeline.kts`
- **Ruta de origen:** `examples/durable/19-platform-queries.pipeline.kts`
- **Huella del cuerpo:** `sha256:2a1755da4b132d9560c7d4a8b9da587bea43dd9f0cbd8589c39e62094fcfa905`
