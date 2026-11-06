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
"$PIPELINEK" doctor           # jdk / os / workdir / writable / cache de scripts
```

Hace falta Java 21 o superior en el `PATH`. El ZIP trae `bin/pipelinek`
(UNIX) y `bin/pipelinek.bat` (Windows); funcionan Linux, macOS y Windows
(WSL). No hace falta `sudo` y no arranca ningún demonio.

## La caché de scripts compilados

Compilar un `.pipeline.kts` cuesta segundos; ejecutar el resultado compilado,
milisegundos. PipelineK guarda la forma compilada en una caché para que la
segunda ejecución del mismo script sea rápida, y la caché está **activada por
defecto**.

| | |
|---|---|
| Dónde | `${XDG_CACHE_HOME:-$HOME/.cache}/pipelinek/script-cache` |
| Qué tan privada | El directorio lo crea PipelineK, es tuyo y nadie más lo puede leer. Si no se puede demostrar que es privado —existe pero es de otro usuario, o otros pueden leerlo— la caché **no se usa**, y `doctor` dice por qué. |
| Cómo cambiarla | `PIPELINEK_SCRIPT_CACHE_DIR=/otra/ruta` |
| Cómo desactivarla | `PIPELINEK_SCRIPT_CACHE_DIR=off` |

Un script compilado lleva dentro los literales del script que lo produjo, así
que la caché es un sitio donde tu código puede quedar en forma legible. Por
eso la regla es cerrada por defecto: una caché que no se puede demostrar
privada no se usa, y la ejecución simplemente compila otra vez.
`pipelinek doctor` dice siempre dónde está la caché y si se está usando.

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
| Quién firmó un release y cómo lo comprueba un consumidor | [`RELEASE-SIGNING.md`](RELEASE-SIGNING.md) |
