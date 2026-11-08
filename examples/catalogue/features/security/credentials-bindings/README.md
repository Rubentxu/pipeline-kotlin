# Credentials Bindings

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `security`

**Expected outcome:** `success`

## What it exercises

The trust boundary: whose bytes a plugin is allowed to run

*The example says this about itself:*

> The subject is the seven binding kinds, and the launcher seeds each of them
> into this scenario's own store before the run. The seeded values are
> synthetic fixtures: nothing here asserts WHAT a binding resolved to, because
> the fixture is not a fact about the product. What is asserted is that every
> kind went from "declared in the DSL" to "a value the step could read".
> That distinction matters more than it looks. A binding that silently resolved
> to an empty string would satisfy any assertion about shape and fail only here,
> which is why the check below is on non-emptiness rather than on the names.

## The stages and the steps

### 1. `credentials-bindings`

- `withCredentials(listOf( // STRING StepSpec.CredentialsBinding.string( "string-creds", "API_KEY" ), // U...)` — a credentials block
  - `sh` — `"echo API_KEY=\${API_KEY} DB_USER=\${DB_USER} SSH_KEY_FILE=\${SSH_KEY_FILE} SECRET_FILE...`
  - `sh` — `"test -n \"\${API_KEY}\" && test -n \"\${DB_USER}\" && test -n \"\${SSH_KEY_FILE}\" &&...`
  - `echo` — `7 credential binding kinds resolved`

### 2. `credentials-door`

- `sh` — `""" mkdir -p empty-store fresh="${'$'}PWD/empty-store/creds.bin" # 1. There is no store...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `7 credential binding kinds resolved` |
| transcript contains | `API_KEY=****`, `NO_STORE_NEEDS_NO_KEY`, `NO_STORE_LEAVES_NO_FILE`, `NO_PASSPHRASE_IS_ITS_OWN_ANSWER`, `WRONG_KEY_CHANGES_NOTHING`, `WRONG_KEY_IS_NAMED`, `MISSING_CREDENTIAL_IS_NAMED` |

## What it needs

- credentials
- credentials
- credentials
- credentials
- credentials
- credentials
- credentials

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/security/credentials-bindings/credentials-bindings.pipeline.kts
pipelinek run      examples/catalogue/features/security/credentials-bindings/credentials-bindings.pipeline.kts
```

## Its lineage

- **Historical name:** `14-credentials-bindings.pipeline.kts`
- **Origin path:** `examples/security/14-credentials-bindings.pipeline.kts`
- **Body digest:** `sha256:0c26067c3194368c151bb197ddaccca72ef5a249ce0c10499aad055f045184b5`
- **Origin digest:** `sha256:f592bfa56977e899d3cfe00655b9c38fe1020d651a8d924d0d47bb39d2e909da`
