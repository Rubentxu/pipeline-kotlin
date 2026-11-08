# Credential Store Without Passphrase

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> El sujeto: una corrida que ENLAZA una credencial no puede abrir el store
> cuando la passphrase que lo abre no está a mano, y lo dice con SU propio
> código en vez de fingir que la credencial iba vacía.

## Las etapas y los pasos

### 1. `the-door-without-a-key`

- `withCredentials(listOf( StepSpec.CredentialsBinding.string( "door-creds", "API_KEY" ) ))` — un bloque de credenciales
  - `sh` — `"test -n \"\${API_KEY}\" && echo THE_BINDING_RESOLVED"`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `3` |
| conclusion | `failure` |
| stderr contiene | `Passphrase required: set PIPELINE_STORE_PASSPHRASE` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/credential-store-without-passphrase/credential-store-without-passphrase.pipeline.kts
pipelinek run      examples/catalogue/features/negative/credential-store-without-passphrase/credential-store-without-passphrase.pipeline.kts
```

## Su linaje

- **Nombre historico:** `credential-store-without-passphrase.pipeline.kts`
- **Ruta de origen:** `examples/negative/credential-store-without-passphrase.pipeline.kts`
- **Huella del cuerpo:** `sha256:cab8f11efdefe430db2bd8addfc53d1349580dccd517562c5a2bb6b272af5dff`
