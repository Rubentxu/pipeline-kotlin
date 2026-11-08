# Credential Store And Unreadable Plugin

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> El sujeto: cuando DOS cosas están mal a la vez, la corrida dice UNA sola
> cosa, y dice lo mismo con journal que sin journal.

## Las etapas y los pasos

### 1. `two-things-wrong-at-once`

- `withCredentials(listOf( StepSpec.CredentialsBinding.string( "door-creds", "API_KEY" ) ))` — un bloque de credenciales
  - `sh` — `"test -n \"\${API_KEY}\" && echo THE_BINDING_RESOLVED"`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `3` |
| conclusion | `failure` |
| stderr contiene | `Passphrase required: set PIPELINE_STORE_PASSPHRASE` |

## Que necesita

- plugin-jar

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/credential-store-and-unreadable-plugin/credential-store-and-unreadable-plugin.pipeline.kts
pipelinek run      examples/catalogue/features/negative/credential-store-and-unreadable-plugin/credential-store-and-unreadable-plugin.pipeline.kts
```

## Su linaje

- **Nombre historico:** `credential-store-and-unreadable-plugin.pipeline.kts`
- **Ruta de origen:** `examples/negative/credential-store-and-unreadable-plugin.pipeline.kts`
- **Huella del cuerpo:** `sha256:fd247769f171b8bf5cadc0c2fb59ac5a2c56a7f5b4e8dd22e6b72fd1aa87fb89`
- **Huella del origen:** `sha256:9ec2c93ebe0e426244b27d4c476e96301c286c13ff7494f1bac7f48282359786`
