# Workflow Control

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `control-flow`

**Resultado esperado:** `unstable`

## Que ejercita

Que rama de una bifurcacion se ejecuta, y cuantas veces corre el cuerpo de un bucle

*El ejemplo dice de si mismo:*

> THE GUARANTEE UNDER TRIAL

## Las etapas y los pasos

### 1. `workspace-control`

- `dir(workflow-test)` — un bloque de directorio
  - `sh` — `echo 'inside dir'`
  - `sh` — `pwd > where.txt && test -s where.txt && grep -q 'workflow-test' where.txt`
  - `sh` — `touch marker`
- `deleteDir`
- `sh` — `test ! -e workflow-test/marker && test ! -e workflow-test/where.txt`

### 2. `retry-block`

- `timeout(30, "SECONDS")` — un bloque con tiempo limite
  - `retry(3)` — un bloque de reintentos
    - `sh` — `echo 'step-A'`
    - `sh` — `echo 'step-B'`

### 3. `error-handling`

- `catchError` — un bloque de pasos de recuperacion
  - `sh` — `exit 1`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `unstable` |
| stdout contiene | `step-A` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/control-flow/workflow-control/workflow-control.pipeline.kts
pipelinek run      examples/catalogue/features/control-flow/workflow-control/workflow-control.pipeline.kts
```

## Su linaje

- **Nombre historico:** `11-workflow-control.pipeline.kts`
- **Ruta de origen:** `examples/control-flow/11-workflow-control.pipeline.kts`
- **Huella del cuerpo:** `sha256:c737e6ae45c487f80b31097b7da09010c5711368f3112fc748f62708ac02f8fc`
