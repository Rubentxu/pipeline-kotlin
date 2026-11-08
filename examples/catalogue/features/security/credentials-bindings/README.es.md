# Credentials Bindings

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `security`

**Resultado esperado:** `success`

## Que ejercita

La frontera de confianza: que bytes de un plugin se pueden ejecutar

*El ejemplo dice de si mismo:*

> The subject is the seven binding kinds, and the launcher seeds each of them
> into this scenario's own store before the run. The seeded values are
> synthetic fixtures: nothing here asserts WHAT a binding resolved to, because
> the fixture is not a fact about the product. What is asserted is that every
> kind went from "declared in the DSL" to "a value the step could read".
> That distinction matters more than it looks. A binding that silently resolved
> to an empty string would satisfy any assertion about shape and fail only here,
> which is why the check below is on non-emptiness rather than on the names.

## Las etapas y los pasos

### 1. `credentials-bindings`

- `withCredentials(listOf( // STRING StepSpec.CredentialsBinding.string( "string-creds", "API_KEY" ), // U...)` — un bloque de credenciales
  - `sh` — `"echo API_KEY=\${API_KEY} DB_USER=\${DB_USER} SSH_KEY_FILE=\${SSH_KEY_FILE} SECRET_FILE...`
  - `sh` — `"test -n \"\${API_KEY}\" && test -n \"\${DB_USER}\" && test -n \"\${SSH_KEY_FILE}\" &&...`
  - `echo` — `7 credential binding kinds resolved`

### 2. `credentials-door`

- `sh` — `""" mkdir -p empty-store fresh="${'$'}PWD/empty-store/creds.bin" # 1. There is no store...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `7 credential binding kinds resolved` |
| la transcripcion contiene | `API_KEY=****`, `NO_STORE_NEEDS_NO_KEY`, `NO_STORE_LEAVES_NO_FILE`, `NO_PASSPHRASE_IS_ITS_OWN_ANSWER`, `WRONG_KEY_CHANGES_NOTHING`, `WRONG_KEY_IS_NAMED`, `MISSING_CREDENTIAL_IS_NAMED` |

## Que necesita

- credentials
- credentials
- credentials
- credentials
- credentials
- credentials
- credentials

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/security/credentials-bindings/credentials-bindings.pipeline.kts
pipelinek run      examples/catalogue/features/security/credentials-bindings/credentials-bindings.pipeline.kts
```

## Su linaje

- **Nombre historico:** `14-credentials-bindings.pipeline.kts`
- **Ruta de origen:** `examples/security/14-credentials-bindings.pipeline.kts`
- **Huella del cuerpo:** `sha256:0c26067c3194368c151bb197ddaccca72ef5a249ce0c10499aad055f045184b5`
