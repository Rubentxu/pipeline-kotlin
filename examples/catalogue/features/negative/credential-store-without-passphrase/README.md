# Credential Store Without Passphrase

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> El sujeto: una corrida que ENLAZA una credencial no puede abrir el store
> cuando la passphrase que lo abre no está a mano, y lo dice con SU propio
> código en vez de fingir que la credencial iba vacía.

## The stages and the steps

### 1. `the-door-without-a-key`

- `withCredentials(listOf( StepSpec.CredentialsBinding.string( "door-creds", "API_KEY" ) ))` — a credentials block
  - `sh` — `"test -n \"\${API_KEY}\" && echo THE_BINDING_RESOLVED"`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `3` |
| outcome | `failure` |
| stderr contains | `Passphrase required: set PIPELINE_STORE_PASSPHRASE` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/credential-store-without-passphrase/credential-store-without-passphrase.pipeline.kts
pipelinek run      examples/catalogue/features/negative/credential-store-without-passphrase/credential-store-without-passphrase.pipeline.kts
```

## Its lineage

- **Historical name:** `credential-store-without-passphrase.pipeline.kts`
- **Origin path:** `examples/negative/credential-store-without-passphrase.pipeline.kts`
- **Body digest:** `sha256:cab8f11efdefe430db2bd8addfc53d1349580dccd517562c5a2bb6b272af5dff`
- **Origin digest:** `sha256:ca440efc692937db9e1b88ae19a8177aac189209c3d5f1e36344e3bfb4739a14`
