# PipelineK

[![Latest release (incl. prereleases)](https://img.shields.io/github/v/release/Rubentxu/pipeline-kotlin?include_prereleases&sort=semver)](https://github.com/Rubentxu/pipeline-kotlin/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Un motor de CI/CD local con un DSL de Kotlin familiar para Jenkins.
PipelineK ejecuta pipelines en tu máquina, con ejecución durable, eventos
tipados y fallos explicados — sin controlador, sin agente y sin estado
remoto.

Esta es la guía breve en español. La documentación completa está en
[`README.md`](README.md), en inglés.

## Instalar

El release dice cómo se llama su asset y cuál es su digest, así que este
bloque no hay que tocarlo cuando se publica una versión nueva:

```bash
# El feed del release nombra la versión más reciente, candidatos incluidos.
TAG=$(curl -fsSL https://github.com/Rubentxu/pipeline-kotlin/releases.atom \
  | sed -n 's#.*releases/tag/\(v[^"]*\)".*#\1#p' | head -1)
BASE="https://github.com/Rubentxu/pipeline-kotlin/releases/download/${TAG}"

# El manifiesto nombra la única distribución del release; el bundle del SDK
# tiene otro nombre y no es el programa.
curl -fL -o manifest.json "$BASE/manifest.json"
ASSET=$(grep -o '"pipelinek-[^"]*\.zip"' manifest.json | grep -v sdk | head -1 | tr -d '"')

curl -fL -o "$ASSET" "$BASE/$ASSET"
curl -fL -o SHA256SUMS "$BASE/SHA256SUMS"
grep " ${ASSET}$" SHA256SUMS | sha256sum -c -

unzip -q "$ASSET"
PIPELINEK="./$(basename "$ASSET" .zip)/bin/pipelinek"

"$PIPELINEK" version          # → pipeline <versión>
"$PIPELINEK" doctor           # jdk / os / workdir / writable
```

Hace falta Java 21 o superior en el `PATH`. El ZIP trae `bin/pipelinek`
(UNIX) y `bin/pipelinek.bat` (Windows); funcionan Linux, macOS y Windows
(WSL). No hace falta `sudo` y no arranca ningún demonio.

## Tu primer pipeline

Escribe esto junto a tu terminal:

```kotlin
// hello.pipeline.kts
pipeline {
    stages {
        stage("hello") {
            echo("PIPELINE-OK")
        }
    }
}
```

```bash
"$PIPELINEK" run hello.pipeline.kts
```

Un pipeline que no pide ningún secreto no necesita ningún secreto: la
corrida no abre el almacén de credenciales y no pide ninguna frase de
paso. Un pipeline que **sí** liga una credencial la pide, y lo dice
claramente si le falta la frase de paso.

## Qué leer después

| Para | Lee |
|---|---|
| El DSL, las capacidades y los ejemplos | [`README.md`](README.md) |
| Cada bandera de la CLI y cada código de salida | `"$PIPELINEK" --help`, `"$PIPELINEK" run --help` |
| Los canales de distribución y sus estado | La sección *Releases and distribution channels* de [`README.md`](README.md) |
| Cómo se verifica el repositorio y qué queda fuera | [`AGENTS.md`](AGENTS.md) |
