# Utilities

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `output`

**Resultado esperado:** `success`

## Que ejercita

El plano de salida: que produjo un paso, y como se vuelve a leer

*El ejemplo dice de si mismo:*

> The utilities plugin, end to end.

## Las etapas y los pasos

### 1. `utilities`

- `sh` — `""" rm -rf build/utilities mkdir -p build/utilities/src/nested echo 'one' > build/utili...`
- `writeJsonRaw` — `"build/utilities/data.json", """{"name":"alice","age":30}"""` — un paso que aporta este plugin (`core-utils`)
- `readJson` — `build/utilities/data.json` — un paso que aporta este plugin (`core-utils`)
- `sha256` — `build/utilities/data.json` — un paso que aporta este plugin (`core-utils`)
- `writeYaml` — `"build/utilities/config.yaml", value = YamlDocument.Map(listOf( YamlDocument.Map.Entry(...` — un paso que aporta este plugin (`core-utils`)
- `readYaml` — `build/utilities/config.yaml` — un paso que aporta este plugin (`core-utils`)
- `findFiles` — `base = "build/utilities/src", glob = "*.txt"` — un paso que aporta este plugin (`core-utils`)
- `zipDir` — `path = "build/utilities/source.zip", directory = "build/utilities/src",` — un paso que aporta este plugin (`core-utils`)
- `unzip` — `path = "build/utilities/source.zip", destination = "build/utilities/out",` — un paso que aporta este plugin (`core-utils`)
- `sh` — `test -f build/utilities/data.json && grep -q alice build/utilities/data.json`
- `sh` — `test -f build/utilities/config.yaml && grep -q pipelinek build/utilities/config.yaml`
- `sh` — `""" test -f build/utilities/out/a.txt test -f build/utilities/out/b.txt test -f build/u...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/output/utilities/utilities.pipeline.kts
pipelinek run      examples/catalogue/features/output/utilities/utilities.pipeline.kts
```

## Su linaje

- **Nombre historico:** `24-utilities.pipeline.kts`
- **Ruta de origen:** `examples/output/24-utilities.pipeline.kts`
- **Huella del cuerpo:** `sha256:ade79668d9000b0766d9280b97541932c6e788252715b3fbd4c378230bfea990`
