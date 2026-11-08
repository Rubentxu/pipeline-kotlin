# Credential Store And Unreadable Plugin

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> El sujeto: cuando DOS cosas están mal a la vez, la corrida dice UNA sola
> cosa, y dice lo mismo con journal que sin journal.

## The stages and the steps

### 1. `two-things-wrong-at-once`

- `withCredentials(listOf( StepSpec.CredentialsBinding.string( "door-creds", "API_KEY" ) ))` — a credentials block
  - `sh` — `"test -n \"\${API_KEY}\" && echo THE_BINDING_RESOLVED"`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `3` |
| outcome | `failure` |
| stderr contains | `Passphrase required: set PIPELINE_STORE_PASSPHRASE` |

## What it needs

- plugin-jar

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/credential-store-and-unreadable-plugin/credential-store-and-unreadable-plugin.pipeline.kts
pipelinek run      examples/catalogue/features/negative/credential-store-and-unreadable-plugin/credential-store-and-unreadable-plugin.pipeline.kts
```

## Its lineage

- **Historical name:** `credential-store-and-unreadable-plugin.pipeline.kts`
- **Origin path:** `examples/negative/credential-store-and-unreadable-plugin.pipeline.kts`
- **Body digest:** `sha256:fd247769f171b8bf5cadc0c2fb59ac5a2c56a7f5b4e8dd22e6b72fd1aa87fb89`
