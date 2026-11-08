# Dsl Invalid

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** 

**Resultado esperado:** `failure`

## Que ejercita

Un ejemplo que el catalogo declara sin categoria

*El ejemplo dice de si mismo:*

> Fixture: DSL malformado a propósito. El pipeline debe fallar con
> diagnostics tipados y sin efectos en el workspace.
> Este fichero NO es DSL válido: usa un step inexistente y un bloque sin cerrar.

## Las etapas y los pasos

### 1. `broken-stage`

- `stepInexistente` — `esto no existe en ninguna version` — un paso que declara este ejemplo

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/dsl-invalid/dsl-invalid.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/dsl-invalid/dsl-invalid.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `dsl-invalid.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/dsl-invalid.pipeline.kts`
- **Huella del cuerpo:** `sha256:45aa1b3d031a09605507487947fa7414bbc8a7843be73c3e8c09608f84fa25cc`
