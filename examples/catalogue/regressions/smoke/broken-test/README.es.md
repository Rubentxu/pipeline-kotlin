# Broken Test

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `workspace`, `process`

**Resultado esperado:** `failure`

## Que ejercita

El directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra

*El ejemplo dice de si mismo:*

> Canary test roto a proposito. El unittest debe fallar (>0 failures), el
> pipeline debe terminar en failure y dejar el XML de resultados fresco.

## Las etapas y los pasos

### 1. `python-broken`

- `sh` — `mkdir -p out && python3 -m unittest discover -s tests -v > out/tests.log 2>&1; exit \\$?`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| ficheros | `out/tests.log` |
| registro de tests | `out/tests.log` |

## Que necesita

- el fixture negativo esta vendoreado (fixtures/small-python-broken) y el escenario scenarios/smoke/broken-test.pipeline.kts existe
- no forma parte de la bateria: se ejecuta a mano para HAR-016

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/broken-test/broken-test.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/broken-test/broken-test.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `broken-test.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/broken-test.pipeline.kts`
- **Huella del cuerpo:** `sha256:0bf5dba2940cc182636879f6de9e4676850ea36c68a89c9e2cd6a6b7c7a7b1c2`
