# External Reader Resumes

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `events`

**Resultado esperado:** `success`

## Que ejercita

La via de eventos: que le cuenta una corrida a un consumidor, y en que orden

*El ejemplo dice de si mismo:*

> THE GUARANTEE UNDER TRIAL

## Las etapas y los pasos

### 1. `parallel-branches`

- `parallel` — un bloque paralelo
  - `branch(a)` — una rama de un bloque paralelo
    - `echo` — `a`
  - `branch(b)` — una rama de un bloque paralelo
    - `echo` — `b`
  - `branch(c)` — una rama de un bloque paralelo
    - `echo` — `c`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- durable-once
- events-resume

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/events/external-reader-resumes/external-reader-resumes.pipeline.kts
pipelinek run      examples/catalogue/features/events/external-reader-resumes/external-reader-resumes.pipeline.kts
```

## Su linaje

- **Nombre historico:** `01-external-reader-resumes.pipeline.kts`
- **Ruta de origen:** `examples/events/01-external-reader-resumes.pipeline.kts`
- **Huella del cuerpo:** `sha256:44ad295f484ec7053c5f02a4af6187d4cdc8a91cc71d3c88f1e48356d1a5e1c5`
- **Huella del origen:** `sha256:cf520454bc67842f43e2d97920b259c6d130db2b32e1094b98993d516d39106a`
