# Credential Store Tampered

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `negative`

**Resultado esperado:** `failure`

## Que ejercita

Las negativas: a que se niega el motor, y que dice cuando se niega

*El ejemplo dice de si mismo:*

> El sujeto: un store de credenciales que NO pasa su propia comprobación de
> integridad tiene su propia respuesta, distinta de la de un store que está
> cerrado y una clave que no es la.

## Las etapas y los pasos

### 1. `the-store-that-opens`

- `withCredentials(listOf( StepSpec.CredentialsBinding.string( "door-creds", "API_KEY" ) ))` — un bloque de credenciales
  - `sh` — `"test -n \"\${API_KEY}\" && echo THE_INTACT_STORE_OPENED"`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `4` |
| conclusion | `failure` |
| stderr contiene | `Error: store tampered` |

## Que necesita

- credentials
- credentials-refusal

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/negative/credential-store-tampered/credential-store-tampered.pipeline.kts
pipelinek run      examples/catalogue/features/negative/credential-store-tampered/credential-store-tampered.pipeline.kts
```

## Su linaje

- **Nombre historico:** `credential-store-tampered.pipeline.kts`
- **Ruta de origen:** `examples/negative/credential-store-tampered.pipeline.kts`
- **Huella del cuerpo:** `sha256:b04e3e6990984d8c20ae27769cb7f025929b61ef7a88880f16e6fb3b85ca455c`
- **Huella del origen:** `sha256:be6f0c53c2be63e362dacf194d61fda6e0f6c29fb856d8a70fa9bfbe9ff8d2ea`
