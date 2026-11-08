# Child Env Has No Store Passphrase

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `security`

**Expected outcome:** `success`

## What it exercises

The trust boundary: whose bytes a plugin is allowed to run

*The example says this about itself:*

> The subject is the boundary between the controller's authority and the
> child's environment.

## The stages and the steps

### 1. `the-store-opened`

- `withCredentials(listOf( StepSpec.CredentialsBinding.string( "store-witness", "WITNESS" ), ))` — a credentials block
  - `sh` — `"test -n \"\${WITNESS}\" && echo THE_STORE_OPENED_FOR_THE_BINDING"`

### 2. `the-inherited-env`

- `sh` — `""" out=$(sh -c env) if printf '%s\n' "${'$'}out" | grep -q '^PIPELINE_STORE_PASSPHRASE...`

### 3. `the-declared-env`

- `environment` — a block
  - `env` — `"PIPELINE_STORE_PASSPHRASE", controllerPassphrase`
- `sh` — `""" if [ -n "${'$'}PIPELINE_STORE_PASSPHRASE" ]; then echo THE_DECLARED_PASSPHRASE_REAC...`

### 4. `the-redaction`

- `environment` — a block
  - `env` — `"PIPELINE_STORE_PASSPHRASE", controllerPassphrase`
- `sh` — `"echo \"THE_DECLARED_PASSPHRASE_VALUE=\${PIPELINE_STORE_PASSPHRASE}\""`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| transcript contains | `THE_STORE_OPENED_FOR_THE_BINDING`, `PASSPHRASE_NOT_IN_THE_CHILD_ENV`, `THE_STORE_LOCATOR_REACHED_THE_CHILD`, `THE_DECLARED_PASSPHRASE_REACHED_THE_CHILD`, `THE_DECLARED_PASSPHRASE_VALUE=****` |

## What it needs

- credentials

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/security/child-env-has-no-store-passphrase/child-env-has-no-store-passphrase.pipeline.kts
pipelinek run      examples/catalogue/features/security/child-env-has-no-store-passphrase/child-env-has-no-store-passphrase.pipeline.kts
```

## Its lineage

- **Historical name:** `29-child-env-has-no-store-passphrase.pipeline.kts`
- **Origin path:** `examples/security/29-child-env-has-no-store-passphrase.pipeline.kts`
- **Body digest:** `sha256:23c8aa5f29550979edb73e72ef15f01548d51ce3c80caa1d258b76d7285391a0`
