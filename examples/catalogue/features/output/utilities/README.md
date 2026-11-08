# Utilities

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `output`

**Expected outcome:** `success`

## What it exercises

The output plane: what a step produced, and how it is read back

*The example says this about itself:*

> The utilities plugin, end to end.

## The stages and the steps

### 1. `utilities`

- `sh` — `""" rm -rf build/utilities mkdir -p build/utilities/src/nested echo 'one' > build/utili...`
- `writeJsonRaw` — `"build/utilities/data.json", """{"name":"alice","age":30}"""` — a step this plugin provides (`core-utils`)
- `readJson` — `build/utilities/data.json` — a step this plugin provides (`core-utils`)
- `sha256` — `build/utilities/data.json` — a step this plugin provides (`core-utils`)
- `writeYaml` — `"build/utilities/config.yaml", value = YamlDocument.Map(listOf( YamlDocument.Map.Entry(...` — a step this plugin provides (`core-utils`)
- `readYaml` — `build/utilities/config.yaml` — a step this plugin provides (`core-utils`)
- `findFiles` — `base = "build/utilities/src", glob = "*.txt"` — a step this plugin provides (`core-utils`)
- `zipDir` — `path = "build/utilities/source.zip", directory = "build/utilities/src",` — a step this plugin provides (`core-utils`)
- `unzip` — `path = "build/utilities/source.zip", destination = "build/utilities/out",` — a step this plugin provides (`core-utils`)
- `sh` — `test -f build/utilities/data.json && grep -q alice build/utilities/data.json`
- `sh` — `test -f build/utilities/config.yaml && grep -q pipelinek build/utilities/config.yaml`
- `sh` — `""" test -f build/utilities/out/a.txt test -f build/utilities/out/b.txt test -f build/u...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/output/utilities/utilities.pipeline.kts
pipelinek run      examples/catalogue/features/output/utilities/utilities.pipeline.kts
```

## Its lineage

- **Historical name:** `24-utilities.pipeline.kts`
- **Origin path:** `examples/output/24-utilities.pipeline.kts`
- **Body digest:** `sha256:ade79668d9000b0766d9280b97541932c6e788252715b3fbd4c378230bfea990`
- **Origin digest:** `sha256:533f8cc0fcc214a708e58d74c32dc004242e698d29d0a4f3c757e2ec2f2b3917`
