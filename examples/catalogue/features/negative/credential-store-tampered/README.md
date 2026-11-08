# Credential Store Tampered

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> El sujeto: un store de credenciales que NO pasa su propia comprobación de
> integridad tiene su propia respuesta, distinta de la de un store que está
> cerrado y una clave que no es la.

## The stages and the steps

### 1. `the-store-that-opens`

- `withCredentials(listOf( StepSpec.CredentialsBinding.string( "door-creds", "API_KEY" ) ))` — a credentials block
  - `sh` — `"test -n \"\${API_KEY}\" && echo THE_INTACT_STORE_OPENED"`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `4` |
| outcome | `failure` |
| stderr contains | `Error: store tampered` |

## What it needs

- credentials
- credentials-refusal

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/credential-store-tampered/credential-store-tampered.pipeline.kts
pipelinek run      examples/catalogue/features/negative/credential-store-tampered/credential-store-tampered.pipeline.kts
```

## Its lineage

- **Historical name:** `credential-store-tampered.pipeline.kts`
- **Origin path:** `examples/negative/credential-store-tampered.pipeline.kts`
- **Body digest:** `sha256:b04e3e6990984d8c20ae27769cb7f025929b61ef7a88880f16e6fb3b85ca455c`
- **Origin digest:** `sha256:be6f0c53c2be63e362dacf194d61fda6e0f6c29fb856d8a70fa9bfbe9ff8d2ea`
