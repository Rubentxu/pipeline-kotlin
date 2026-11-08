# Branch Isolation

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `process`, `shell`, `cross_stage`

**Resultado esperado:** `success`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> Ramas contextuales aisladas + side effects caracterizados.
> parallel con dos branch(): ambos se ejecutan (aislados, identidad
> b0:/b1: en journal) y cada uno deja su side effect marcado en out/.

## Las etapas y los pasos

### 1. `branch-iso`

- `parallel` — un bloque paralelo
  - `branch(left)` — una rama de un bloque paralelo
    - `sh` — `mkdir -p /workspace/out && printf 'left-taken\\n' > /workspace/out/branch-left.txt`
  - `branch(right)` — una rama de un bloque paralelo
    - `sh` — `mkdir -p /workspace/out && printf 'right-taken\\n' > /workspace/out/branch-right.txt`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/branch-left.txt`, `out/branch-right.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/branch-isolation/branch-isolation.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/branch-isolation/branch-isolation.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

**Cubre:** `HAR-008`

## Su linaje

- **Nombre historico:** `har008-branch-isolation.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/har008-branch-isolation.pipeline.kts`
- **Huella del cuerpo:** `sha256:0a7206141216ab2e538aa50d0663fbd4a8c5e0159b025a8e0136ebb88f840d97`
