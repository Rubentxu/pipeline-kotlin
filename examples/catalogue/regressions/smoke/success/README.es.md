# Success

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `process`

**Resultado esperado:** `success`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> Fixture: real Python unittest with test report + output marker in workspace.
> Do not use unsupported steps { } wrapper in a PipelineK version that rejects it.

## Las etapas y los pasos

### 1. `python-unit`

- `sh` — `"mkdir -p out && python3 -m unittest discover -s tests -v > out/tests.log 2>&1 && print...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/test-ok.txt` |
| registro de tests | `out/tests.log` |
| minimo de tests | `1` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/success/success.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/success/success.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

**Cubre:** `HAR-037`

## Su linaje

- **Nombre historico:** `success.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/success.pipeline.kts`
- **Huella del cuerpo:** `sha256:95541fd183cbd4c1feec4baf6328d8fa920ce71427a5868b41d1d50505547724`
