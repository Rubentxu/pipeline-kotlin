# Cli Self Report

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `smoke`

**Resultado esperado:** `success`

## Que ejercita

El camino mas corto por el motor: un pipeline que arranca, corre y termina

*El ejemplo dice de si mismo:*

> The product, asked about itself.

## Las etapas y los pasos

### 1. `self-report`

- `sh` — `""" mkdir -p 'probe/NOT WRITABLE' (cd 'probe/NOT WRITABLE' && '${product}' doctor) > do...`
- `sh` — `""" if [ ! -d /proc ]; then echo "this machine has no directory nobody can write into,...`
- `sh` — `""" '${product}' doctor > doctor-cache.txt 2>&1 line="$(grep -F 'script-cache:' doctor-...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `DOCTOR_HEALTHY_EXIT`, `DOCTOR_SAID_WRITABLE`, `DOCTOR_DEFECTIVE_EXIT`, `DOCTOR_SAID_UNWRITABLE`, `DOCTOR_NAMES_THE_CACHE`, `DOCTOR_SAYS_WHETHER_IT_IS_USED`, `CAPABILITIES_ONE_LINE`, `CAPABILITIES_SCHEMA_V2`, `CAPABILITIES_ONE_VOCABULARY`, `ONE_VERSION_THREE_FACES`, `REFUSAL_ANSWERS_INVALID_ARGUMENT`, `MISSING_CAPABILITY_ANSWERS_RUNTIME_NOT_AVAILABLE`, `OPERATIONS_ADDRESS_THEIR_OWN_BYTES`, `FOLDED_IDENTITY_GETS_NO_NEIGHBOUR_BYTES` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/smoke/cli-self-report/cli-self-report.pipeline.kts
pipelinek run      examples/catalogue/features/smoke/cli-self-report/cli-self-report.pipeline.kts
```

## Su linaje

- **Nombre historico:** `11-cli-self-report.pipeline.kts`
- **Ruta de origen:** `examples/smoke/11-cli-self-report.pipeline.kts`
- **Huella del cuerpo:** `sha256:cf1819b8a99e6182c6563a6600670e894f051c27be66698b194c1683aee53c83`
- **Huella del origen:** `sha256:b602fa00de8e903a1779f72d7acc71925219337d02afa0b16939ab9e85ad5139`
