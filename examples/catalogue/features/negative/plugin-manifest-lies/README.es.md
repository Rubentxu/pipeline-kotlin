# Plugin Manifest Lies

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> The subject is a plugin that ADMITS and then lies.

## Las etapas y los pasos

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `2` |
| conclusion | `failure` |
| stderr contiene | `Plugin admission refused for`, `declared Steps with no implementation`, `example.uppercase.ghost` |

## Que necesita

- distribution

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/plugin-manifest-lies/plugin-manifest-lies.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-manifest-lies/plugin-manifest-lies.pipeline.kts
```

## Su linaje

- **Nombre historico:** `plugin-manifest-lies.pipeline.kts`
- **Ruta de origen:** `examples/negative/plugin-manifest-lies.pipeline.kts`
- **Huella del cuerpo:** `sha256:0921c730bbb694419a2344ac23779c1afa538e5595ca3a0c2afa22dab8bc1e47`
