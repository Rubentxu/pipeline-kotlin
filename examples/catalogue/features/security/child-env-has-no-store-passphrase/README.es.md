# Child Env Has No Store Passphrase

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `security`

**Resultado esperado:** `success`

## Que ejercita

La frontera de confianza: que bytes de un plugin se pueden ejecutar

*El ejemplo dice de si mismo:*

> The subject is the boundary between the controller's authority and the
> child's environment.

## Las etapas y los pasos

### 1. `the-store-opened`

- `withCredentials(listOf( StepSpec.CredentialsBinding.string( "store-witness", "WITNESS" ), ))` — un bloque de credenciales
  - `sh` — `"test -n \"\${WITNESS}\" && echo THE_STORE_OPENED_FOR_THE_BINDING"`

### 2. `the-inherited-env`

- `sh` — `""" out=$(sh -c env) if printf '%s\n' "${'$'}out" | grep -q '^PIPELINE_STORE_PASSPHRASE...`

### 3. `the-declared-env`

- `environment` — un bloque
  - `env` — `"PIPELINE_STORE_PASSPHRASE", controllerPassphrase`
- `sh` — `""" if [ -n "${'$'}PIPELINE_STORE_PASSPHRASE" ]; then echo THE_DECLARED_PASSPHRASE_REAC...`

### 4. `the-redaction`

- `environment` — un bloque
  - `env` — `"PIPELINE_STORE_PASSPHRASE", controllerPassphrase`
- `sh` — `"echo \"THE_DECLARED_PASSPHRASE_VALUE=\${PIPELINE_STORE_PASSPHRASE}\""`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| la transcripcion contiene | `THE_STORE_OPENED_FOR_THE_BINDING`, `PASSPHRASE_NOT_IN_THE_CHILD_ENV`, `THE_STORE_LOCATOR_REACHED_THE_CHILD`, `THE_DECLARED_PASSPHRASE_REACHED_THE_CHILD`, `THE_DECLARED_PASSPHRASE_VALUE=****` |

## Que necesita

- credentials

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/security/child-env-has-no-store-passphrase/child-env-has-no-store-passphrase.pipeline.kts
pipelinek run      examples/catalogue/features/security/child-env-has-no-store-passphrase/child-env-has-no-store-passphrase.pipeline.kts
```

## Su linaje

- **Nombre historico:** `29-child-env-has-no-store-passphrase.pipeline.kts`
- **Ruta de origen:** `examples/security/29-child-env-has-no-store-passphrase.pipeline.kts`
- **Huella del cuerpo:** `sha256:23c8aa5f29550979edb73e72ef15f01548d51ce3c80caa1d258b76d7285391a0`
