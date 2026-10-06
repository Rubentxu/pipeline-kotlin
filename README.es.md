# PipelineK

[![Latest release (incl. prereleases)](https://img.shields.io/github/v/release/Rubentxu/pipeline-kotlin?include_prereleases&sort=semver)](https://github.com/Rubentxu/pipeline-kotlin/releases)
[![Licencia: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-blueviolet.svg)
![JVM](https://img.shields.io/badge/jvm-21-orange.svg)

**Español** · [English](README.md)

Un motor CI/CD local-first con un DSL de Kotlin familiar con Jenkins.

Escribes un fichero `.pipeline.kts`. PipelineK lo compila, lo valida y lo ejecuta **en tu máquina**.
Cada paso emite un evento tipado; cada proceso de shell se journaliza con una huella SHA-256 de sus
entradas; cada run deja un registro durable que puedes reanudar después de un crash.

Sin controller, sin agente, sin estado remoto, sin phone-home.

```kotlin
// pipeline.kts
pipeline {
    stages {
        stage("build") {
            sh("./gradlew --no-daemon build")
            sh("test -f build/libs/*.jar")
            echo("PIPELINE-OK")
        }
    }
}
```

```bash
pipelinek validate pipeline.kts
pipelinek run --workspace . pipeline.kts
```

---

## Índice

- [Instalación](#instalación) · [Métodos de instalación](#métodos-de-instalación) · [Ejemplo rápido](#ejemplo-rápido) · [Ejemplos](#ejemplos)
- [Qué es PipelineK, en palabras simples](#qué-es-pipelinek-en-palabras-simples)
- [Qué hace y qué **no** hace](#qué-hace-y-qué-no-hace)
- **[⚠️ Dos fuentes de verdad en este repositorio](#dos-fuentes-de-verdad-en-este-repositorio)**
- [Arquitectura](#arquitectura) · [Códigos de salida](#códigos-de-salida) · [Capacidades](#capacidades)
- [Canales de distribución](#canales-de-distribución) · [Documentación](#documentación)
- [Desarrollo local](#desarrollo-local) · [Contribuir](#contribuir)

---

## Instalación

### 1. Descarga del ZIP canónico

PipelineK publica **un único ZIP canónico** por versión. Todos los canales consumen los mismos bytes.

```bash
VERSION=0.39.0

curl -fL -o "pipelinek-${VERSION}.zip" \
  "https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"

# Verifica la integridad antes de extraer nada
echo "385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8  pipelinek-${VERSION}.zip" \
  | sha256sum -c -

unzip "pipelinek-${VERSION}.zip"

./pipelinek-${VERSION}/bin/pipelinek version          # → pipeline 0.39.0
./pipelinek-${VERSION}/bin/pipelinek doctor           # jdk / os / workdir / writable
```

Linux, macOS y Windows (WSL) están soportados. El ZIP trae `bin/pipelinek` (UNIX) y
`bin/pipelinek.bat` (Windows). Requiere **Java 21+** en el `PATH`. El snippet asume que lo ejecutas
desde el directorio donde quieres que viva `pipelinek-${VERSION}/`, así que **no necesitas tocar el
`PATH` ni usar `sudo`**.

> **SDKMAN todavía no está disponible.** `sdk install pipelinek 0.39.0` es el comando canónico a
> largo plazo, pero la candidatura sigue en onboarding con el proveedor y hoy puede fallar. Hasta
> que SDKMAN esté vivo, instala desde el ZIP de GitHub Releases.

### 2. Instalador multi-versión

Si quieres varias versiones en paralelo y rollback fácil, usa el instalador del repo. Descarga el
mismo ZIP canónico, verifica su SHA-256 contra el manifiesto `SHA256SUMS` de la release, y desempaqueta
bajo `~/.local/share/pipelinek/versions/`:

```bash
VERSION=0.39.0

curl -fL -o install-pipelinek.sh \
  "https://raw.githubusercontent.com/Rubentxu/pipeline-kotlin/main/scripts/install-pipelinek.sh"

chmod +x install-pipelinek.sh
./install-pipelinek.sh install "${VERSION}"   # descarga + verifica + instala
./install-pipelinek.sh use "${VERSION}"       # cambia la versión activa
./install-pipelinek.sh list                  # versiones instaladas + activa
./install-pipelinek.sh doctor                # ejecuta `pipelinek doctor` de la activa

export PATH="$HOME/.local/share/pipelinek/current/bin:${PATH}"
pipelinek version
```

Subcomandos: `install`, `use`, `list`, `uninstall`, `doctor`, `help`. El script tiene una **allowlist
fail-closed de URLs** (`github.com`, `objects.githubusercontent.com`, más hosts loopback para mirrors
locales o air-gapped).

La instalación es **transaccional**: descarga, digest, extracción y verificación de identidad ocurren
en un directorio temporal, y el directorio final se crea con un único rename sólo cuando **todos** los
checks han pasado. Una instalación fallida no deja nada, y la versión activa nunca se toca. Además
exige **identidad exacta de runtime**: el binario instalado debe reportar exactamente la versión
pedida. Un desajuste de versión, un digest que no cuadra, una raíz de archivo que no coincide con la
versión, o un binario con sufijo de candidata bajo un nombre final **fallan cerrado** en vez de
instalar algo mal etiquetado.

Nunca usa `sudo` ni lanza un demonio. Fuente:
[`scripts/install-pipelinek.sh`](scripts/install-pipelinek.sh). Tests de contrato:
[`scripts/test_install_pipelinek.py`](scripts/test_install_pipelinek.py).

**Necesita bash 4+, no `sh` POSIX.** Usa arrays asociativos y `[[ ]]`, y activa
`set -Eeuo pipefail`. Eso importa para el método 5.

---

## Métodos de instalación

Cinco formas de entrar. **Sólo las dos primeras existen hoy**; las otras tres están especificadas en el
roadmap de distribución y no se han construido. Lee la columna de estado antes de copiar un comando.

| # | Método | Estado | Dónde vive |
|---|---|---|---|
| 1 | **ZIP canónico** de GitHub Releases | ✅ **Disponible, verificado** | Este repo |
| 2 | **Instalador multi-versión** (`install-pipelinek.sh`) | ✅ **Disponible, con tests de contrato** | Este repo |
| 3 | **`mise`** (`mise use -g pipelinek@0.39.0`) | 📋 **Especificado, sin construir** (DIST-4) | Harness externo de releases |
| 4 | **`asdf`** (el plugin `asdf-pipeline`) | 📋 **Especificado, sin construir** (DIST-7) | Harness externo de releases |
| 5 | **One-liner `curl \| sh`** | ❌ **No existe** — ver abajo | Sería este repo |

La regla que lo gobierna está en
[`ADR-0089`](docs/v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md) y en
[`DISTRIBUTION_RELEASE_SPEC.md`](docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md): **todos los
canales consumen el mismo ZIP canónico y verifican el mismo SHA-256. Ningún canal reconstruye
PipelineK.** Por eso un adaptador nunca puede darte bytes distintos de los del ZIP.

### Método 1 — ZIP canónico ✅

El camino principal, y el único cuyos bytes están verificados contra una release publicada. Snippet
completo en [Instalación](#1-descarga-del-zip-canónico). Digests en
[Digests](#digests).

### Método 2 — Instalador multi-versión ✅

Detallado más arriba. Úsalo cuando quieras varias versiones en paralelo y un rollback de un comando.

### Método 3 — `mise` 📋 sin construir

Planificado como **DIST-4** en
[`DISTRIBUTION_ROADMAP.md`](docs/v2/05-roadmap/DISTRIBUTION_ROADMAP.md): registrar `pipelinek` como
herramienta instalable desde el backend de Aqua o de GitHub Releases, para que un gestor de versiones
resuelva el digest y la URL canónicos por ti.

La forma que está diseñada para tener:

```bash
mise use -g pipelinek@0.39.0     # resuelve el digest y la URL canónicos, y luego instala
mise ls pipelinek                 # muestra la versión instalada
```

```toml
# .mise.toml — fija PipelineK junto al JDK del proyecto
[tools]
java = "temurin-21"
pipelinek = "0.39.0"
```

**Estado:** *no iniciado*. El registro de plugins es externo a este repositorio, así que no puede
entregarse aquí. Hasta que se registre, estos comandos **no funcionan**: no los pongas en tu bootstrap.
`mise` **sí** se usa aquí para el toolchain (`.tool-versions` fija java, gradle y maven), pero eso es
otra cosa: ofrecer PipelineK como herramienta.

### Método 4 — `asdf` 📋 sin construir

Planificado como **DIST-7**: un plugin externo `asdf-pipeline` que exponga las entradas estándar
`bin/install`, `bin/download` y `bin/list-bin`, para que `asdf` pueda mover el mismo ZIP.

La forma que está diseñada para tener:

```bash
asdf plugin add pipelinek <plugin-repo-url>    # cuando exista el plugin
asdf list all pipelinek                         # versiones disponibles
asdf install pipelinek 0.39.0
asdf global pipelinek 0.39.0
```

```toml
# .tool-versions — asdf ya lee este fichero, que este repo ya publica
[tools]
pipelinek = "0.39.0"
```

**Estado:** *no iniciado*, y como `mise`, vive en el harness externo. `asdf` ya lee el
`.tool-versions` de este repo para `java`, `gradle` y `maven`, pero `pipelinek` **no** está ahí todavía,
y añadir la línea hoy rompería a todo usuario de `asdf` con un plugin sin resolver.

### Método 5 — `curl | sh` ❌ no existe

Muchos proyectos publican `curl -fsSL https://…/install.sh | sh`. **PipelineK no, y tampoco funciona
pipear el instalador actual.** Es una propiedad del código, no un descuido:

| Motivo | Evidencia |
|---|---|
| El instalador es **bash**, no `sh` POSIX — arrays asociativos, `[[ ]]`, `local` | `scripts/install-pipelinek.sh:46` activa `set -Eeuo pipefail`; declara **bash 4+** como precondición |
| Leyendo desde stdin, `BASH_SOURCE` es un **array vacío**, y `set -u` aborta | `scripts/install-pipelinek.sh:56` lee `BASH_SOURCE[0]` |
| Efecto neto | `curl … \| sh` **falla siempre**; `curl … \| bash` funciona sólo con **bash ≥ 4.4** y pierde el nombre del script en su texto de uso |

Un futuro one-liner tendría que ser un bootstrap POSIX pequeño y aparte que haga `curl` de este
instalador y lo **reejecute con bash y un argumento explícito**, en vez de ser este instalador
pipeado. Hasta que ese bootstrap exista y tenga tests de contrato, usa el método 1 o el 2.

---

## Ejemplo rápido

### 1. Compila el CLI

```bash
cd v2 && ./gradlew :pipeline-application:installDist
```

> ⚠️ **El wrapper Gradle sólo existe en `v2/`.** No hay `./gradlew` en la raíz del repositorio.
> Desde la raíz usa `v2/gradlew -p v2 <tasks>`, nunca `./gradlew -p v2 <tasks>`.

El binario queda en `v2/pipeline-application/build/install/pipelinek/bin/pipelinek`. El nombre
`pipelinek` **no está hardcodeado**: se lee del build file, así que el harness de ejemplos lo sigue
aunque se renombre la distribución.

### 2. Ejecuta

```bash
PK=v2/pipeline-application/build/install/pipelinek/bin/pipelinek

$PK doctor                                  # jdk / os / directorio escribible
$PK validate examples/01-hello.pipeline.kts
$PK run examples/01-hello.pipeline.kts

# Con journal durable (SQLite)
$PK run examples/06-durable.pipeline.kts --db /tmp/journal.db
$PK run examples/06-durable.pipeline.kts --db /tmp/journal.db --resume   # reanuda
$PK run examples/06-durable.pipeline.kts --db /tmp/journal.db --rerun    # fuerza run nuevo
```

> **Los flags van antes del path del script.** `run --db /tmp/x.db pipeline.kts`, no al revés.
> El parser deja de consumir flags en el primer argumento no-flag.

El recorrido completo (workspaces, `--db`, redacción de secretos, inspección de runs) está en
[`docs/user/quickstart.md`](docs/user/quickstart.md).

---

## Qué es PipelineK, en palabras simples

Imagina un **restaurante con cocina, servicio y recurrimiento**:

```text
   📄 TU pipeline.kts                  La receta. Es Kotlin que se compila de verdad.
            │
            ▼
   🧪 COMPILADOR DE SCRIPTS            Si hay un error de tipo, NO se ejecuta nada.
      (Kotlin24ScriptingHost)          Autocompletado y errores en el IDE, no en producción.
            │
            ▼
   🧱 DEFINICIÓN COMPILADA             La receta ya entendida y validable.
      (CompiledPipeline)               Inmutable: se puede inspeccionar antes de ejecutar.
            │
            ▼
   📖 REGISTRO DE STEPS                "¿qué pasos conozco?" — falla cerrado si no.
      (registry + plugins)             Un Step desconocido se rechaza ANTES de cualquier efecto.
            │
            ▼
   🧭 COORDINADOR DURABLE              El ÚNICO bucle de control del run. Decide.
            │
            ▼
   ⚙️ MOTOR DE DISPATCH                Ejecuta lo decidido: journal → replay → efecto → evento.
      (StepDispatchEngine)             Un Step pide capacidades por nombre, no "un contexto".
            │
            ├────────────► 🗄️ EVENT STORE   SQLite · JSON · en memoria
            │              Eventos + journal + cursor de replay.
            ▼
   📤 SALIDA                          pipelinek events  (lee el store)
                                      pipelinek console (lee el Output Plane)
```

**Las tres ideas que explican casi todo el proyecto:**

1. **Fail-closed.** Ante una duda, **parar**. Un error visible es mejor que un éxito falso.
   Step desconocido, token desconocido, versión de schema incompatible: todo se rechaza antes de
   producir un efecto. Es la política central, y por eso un plugin desconocido no "se ignora".

2. **Los eventos son semántica, no logs.** Cada Step emite sus propios eventos tipados
   (`StepStarted`, `StepFinished`, `StepFailed`…) precisamente para que un sistema externo pueda
   observar y reaccionar desde otro proceso. Un Step cuyo único efecto observable es su valor de
   retorno está incompleto.

3. **El estado se persiste, y por eso se puede reanudar.** Un journal registra cada operación con
   una huella de sus entradas. Si el proceso muere, el siguiente run **reconoce** lo ya hecho en
   lugar de repetirlo a ciegas.

---

## Qué hace y qué **no** hace

| ✅ Hace | ❌ No hace (a propósito o aún) |
|---|---|
| Compila, valida e inspecciona pipelines `.pipeline.kts` | **No** hay CI remota en este repositorio |
| Ejecución durable con journal SQLite (`--db`), reanudable | **No** hay controller ni ejecución remota: el control plane vive en otro proyecto |
| Stream de eventos tipado, consultable con `pipelinek events` | **No** hay Jenkins ni Kubernetes aquí: eso es de `pipelinek-fabric` |
| DSL familiar con Jenkins: `sh`, `echo`, `dir`, `timeout`, `retry`, `catchError` | **No** hay demonio: una ejecución por invocación del CLI |
| Bloques `script {}` con control de flujo Kotlin real | **No** es todavía un "listo para producción": el PRODUCT-GATE está bloqueado (ver abajo) |
| Plugins externos que aportan Steps y eventos **sin tocar el core** | `agent`, `load`, `node`, `ansiColor` y el retrofit de `retry` **no funcionan**: son sintaxis aceptada que falla cerrado |
| Credenciales locales cifradas, con redacción en el log | **No** es un sustituto de un plan de control remoto |
| Artefactos, `stash`, `archiveArtifacts`, `publishHTML` | |

> **Estado del proyecto.** La release publicada actual es la **0.47.0**: trae
> `pipelinek-0.47.0.zip` y un manifiesto `SHA256SUMS`, así que su digest es verificable por el
> consumidor. Release anterior: `0.39.0` (digests más abajo).
> La rama de desarrollo va también por la **0.47.0** (`v2/build.gradle.kts:75`) en el commit
> `b08fa948`, y es la versión que describe la documentación de usuario en
> [`docs/user/`](docs/user/README.es.md).
> Y un aviso honesto: desde el 2026-09-30 este repositorio **no tiene integración continua remota**,
> así que "CI verde" no es una evidencia disponible aquí. La verificación se hace en local, sobre el
> SHA exacto.

---

## Dos fuentes de verdad en este repositorio

Esto es lo que más confunde a quien llega nuevo. Léelo antes de tocar nada.

```text
pipeline-kotlin/
├── v2/          ← ESTE es el build activo. 27 módulos. El producto vive aquí.
│
├── core/              ← V1. Tiene su propio build.gradle.kts… que NADA compila.
├── pipeline-cli/      ← V1. shadowJar, GraalVM native, flags -c/-s: todo obsoleto.
├── pipeline-backend/  ← V1. Una API REST que el producto actual no tiene.
├── pipeline-config/   ← V1.
├── pipeline-steps-system/  ← V1 (prohibido en el camino crítico de V2).
└── v2/pipeline-protocol/   ← Módulo HUÉRFANO: existe en disco, no está en el build.
```

El `settings.gradle.kts` de la raíz contiene, en lo esencial, **una línea**:

```kotlin
includeBuild("v2")     // ← eso es todo
```

El propio repositorio lo dice: *"V1 source remains in the repository as legacy/history, but is not
part of the active build"* (`settings.gradle.kts:20-21`). Los `build.gradle.kts` de V1 están **vivos
en disco** con sus propios catálogos de dependencias, y eso los hace parecer parte del build. **No lo
son.** Editarlos no tiene ningún efecto.

**Tres trampas concretas:**

| Trampa | Qué pasa si no lo sabes |
|---|---|
| `./gradlew -p v2 …` desde la raíz | No hay `./gradlew` en la raíz. El wrapper está en `v2/`. Usa `cd v2 && ./gradlew …` o `v2/gradlew -p v2 …` |
| `demo-native-dsl.pipeline.kts` (raíz) | Usa la forma V1 (`pipeline { stage { steps {…} } }`) y **no compila** contra el DSL vigente |
| Módulos con `build.gradle.kts` propio | Que exista un fichero de build no significa que el módulo esté en el build |

---

## Arquitectura

### Dependencias hacia dentro

```text
   application · events-store · scripting-kotlin24 · step-sdk:*     adaptadores
                          ↓
              scripting-api · step-sdk:api · events · output      contratos
                          ↓
                    pipeline-domain                                núcleo, sin framework
```

`pipeline-domain` **no** puede depender de Spring, de la CLI ni de SQLite. Un fitness test lo vigila
(`FArch001DomainFrameworkFreeTest`). Si necesitas algo del mundo exterior desde el dominio,
**se define un puerto**, no se importa la librería.

### Contrato publicado ≠ implementación

El proyecto tiene una regla estricta: **lo que se publica no lleva infraestructura dentro.**

| Artefacto publicado | Lleva | No lleva |
|---|---|---|
| `:pipeline-events` | Contrato del plano de eventos | Nada de SQLite ni de ficheros |
| `:pipeline-events-store` | Journal, cursor, lease, stores | **No se publica** (a propósito) |
| `:pipeline-output` | Lado **lectura** del Output Plane | Nada |
| `:pipeline-output-store` | Escritura de segmentos | **No se publica** (a propósito) |

### Superficie pública y madurez

Cada artefacto publicado declara su madurez en
`v2/contract/published-contract-maturity.json`, **por superficie y no por módulo**, y cada nombre
declarado tiene que resolver a la vez en el dump `.api` del módulo *y* en
[`docs/v2/surface/DSL_SURFACE_MANIFEST.md`](docs/v2/surface/DSL_SURFACE_MANIFEST.md). Una superficie
que sólo aparece en uno de los dos no es una superficie: es un error de transcripción.

Los contratos declarados como `UNSUPPORTED_FAIL_CLOSED` son constructos que **compilan y siempre
rechazan**, antes de cualquier efecto. No son "aún no implementado": son una negativa explícita, y
por eso hay tests que comprueban que lanzan.

---

## Códigos de salida

| Código | Significado |
|---|---|
| `0` | `SUCCESS` o `UNSTABLE` |
| `1` | `FAILURE` / `ABORTED`, o error de compilación |
| `2` | Error de uso o de flags (incluye un Step no canónico) |
| `3` | Manifest sin versión, o credenciales sin passphrase |
| `4` | Secret store adulterado |

Detalle en [`docs/user/cli-reference.md`](docs/user/cli-reference.md) y
[`docs/user/cheat-sheet.md`](docs/user/cheat-sheet.md).

---

## Capacidades

PipelineK `0.39.0` incluye:

- Compilar, validar e inspeccionar pipelines `.pipeline.kts`.
- Ejecución local durable con journal SQLite (`--db`) y *control-root* para aislar estado.
- Stream de eventos tipado: `CompilationStarted`, `RunStarted`, `StageStarted`, `StepStarted`,
  `StepFinished`, `StepFailed`, `RunFinished`, `EchoOutputCaptured`.
- Forma familiar con Jenkins: `pipeline { stages { stage { … } } }`.
- Block steps: `parallel`, `retry`, `timeout`, `catchError`.
- Bloques `script {}` con control de flujo Kotlin real.
- Contratos tipados de plugin y capacidad (`StepContract`, `requiredCapabilities`, descubrimiento por registro).
- Credenciales locales, con redacción de secretos en la costura durable del shell.
- Steps locales: `artifacts`, `stash`, `unstash`, `archiveArtifacts`, `publishHTML`, `writeFile`,
  `pwd`, `isUnix`, `milestone`, `cleanWs`, `deleteDir`, `waitUntil`, `unstable`, `warnError`.

### Requisitos del sistema

- **Java**: 21 o superior (certificado en Temurin 21.0.8 y 24.0.2).
- **SO**: Linux, macOS, Windows vía WSL.
- **Disco**: ~200 MB para la distribución, más los datos de control por run.
- **Concurrencia**: un run por invocación del CLI. Sin modo demonio.

---

## Ejemplos

El directorio [`examples/`](examples/) contiene diez pipelines ejecutables contra el CLI real de la
distribución publicada `0.39.0`:

```bash
# Con el binario instalado
pipelinek run --workspace /tmp/pk-example examples/03-shell.pipeline.kts

# Si clonaste el repo, el script automatiza las aserciones
# (exit code, outcome terminal, contrato de eventos para 07–10)
examples/run.sh 03-shell.pipeline.kts
examples/run.sh            # los diez
```

| Ejemplo | Qué muestra | Exit esperado |
|---|---|---|
| `01-hello.pipeline.kts` | Pipeline mínimo: un stage, un `echo` | `0` |
| `02-multi-stage.pipeline.kts` | Los stages se ejecutan en orden de declaración | `0` |
| `03-shell.pipeline.kts` | Procesos reales del SO con `sh`, incluido un bucle `for` de shell | `0` |
| `04-kotlin-control-flow.pipeline.kts` | Control de flujo Kotlin dentro de `script {}` | `0` |
| `05-failing-step.pipeline.kts` | Fallo tipado: `sh` sale con 3 → `StepFailed(kind=SCRIPT)`, outcome `failure` | `1` |
| `06-durable.pipeline.kts` | Ejecución durable con `--db`: journal, huellas, reanudación tras crash | `0` |
| `07-catch-error.pipeline.kts` | `catchError` anidado: `FAILURE` interno → `UNSTABLE` externo, el pipeline continúa | `0` |
| `08-parallel.pipeline.kts` | Dos ramas concurrentes con su propia identidad durable | `0` |
| `09-retry.pipeline.kts` | `retry`: el primer intento falla, el segundo funciona | `0` |
| `10-timeout.pipeline.kts` | `timeout` aborta un `sh` que se pasa de tiempo, outcome `failure` | `1` |

Ver [`examples/README.md`](examples/README.md) para la demo de ejecución durable, el detalle del
contrato de eventos y las limitaciones conocidas de cada ejemplo.

---

## Documentación

**Empieza aquí → [`docs/user/README.es.md`](docs/user/README.es.md) · [English](docs/user/README.md)**

Esa página es el punto de entrada a toda la documentación de usuario. Te da tres rutas según por qué
estés aquí, y es también donde registramos qué construcciones del DSL están **probadas** y cuáles
sólo **declaradas**, para que sepas en qué puedes fiarte.

| Ruta | Para quién | Páginas |
|---|---|---|
| **A** | Quiero ejecutar pipelines | Instalación → Primeros pasos → DSL → Referencia del CLI |
| **B** | Quiero operarlo en condiciones | Configuración y workspace → Credenciales → Eventos y diagnóstico |
| **C** | Quiero contribuir con código | `CONTEXT.md` → constitución semántica → protocolo de certificación → `AGENTS.md` |

Páginas sueltas, si prefieres ir directo:

| Documento | Contenido |
|---|---|
| [`installation.es.md`](docs/user/installation.es.md) | Linux / macOS / Windows (WSL) desde el ZIP, con verificación de digest |
| [`quickstart.es.md`](docs/user/quickstart.es.md) | Tu primer pipeline, de principio a fin |
| [`pipeline-dsl.es.md`](docs/user/pipeline-dsl.es.md) | Superficie del DSL: probado vs declarado vs falla cerrado |
| [`cli-reference.es.md`](docs/user/cli-reference.es.md) | Subcomandos, flags, códigos de salida y las trampas |
| [`configuration-and-workspace.es.md`](docs/user/configuration-and-workspace.es.md) | `--workspace`, `--db`, `--control-root`, `--isolated` |
| [`credentials-and-security.es.md`](docs/user/credentials-and-security.es.md) | Redacción de secretos y almacén de credenciales |
| [`events-and-troubleshooting.es.md`](docs/user/events-and-troubleshooting.es.md) | Eventos tipados, transcripciones, síntoma → causa → arreglo |
| [`upgrading.es.md`](docs/user/upgrading.es.md) | Selección de versión y rollback |
| [`cheat-sheet.es.md`](docs/user/cheat-sheet.es.md) | Tabla corta y copiable de códigos de salida |

> Cada página existe en inglés y en español. Los números de versión, digests, flags y claves de
> evento son **idénticos** en ambos; sólo se traduce la prosa.

**¿Vienes a contribuir y no a usar la herramienta?** No empieces por aquí. Empieza por
[`CONTEXT.md`](CONTEXT.md) y
[`docs/pipelinek-semantic-evolution/01-semantic-constitution.md`](docs/pipelinek-semantic-evolution/01-semantic-constitution.md).

---

## Canales de distribución

PipelineK publica **un único ZIP canónico** por release. Ningún canal reconstruye nada.

| Canal | Estado | Notas |
|---|---|---|
| **ZIP de GitHub Releases** | **Disponible** | Artefacto canónico. ZIP + SHA-256 + SBOM CycloneDX + manifest de release. |
| **Instalador multi-versión** (`scripts/install-pipelinek.sh`) | **Disponible** | Instalador bash en este repo: `install` / `use` / `list` / `uninstall` / `doctor`. Allowlist fail-closed, SHA-256 verificado, sin `sudo`. |
| **Descarga directa** | **Disponible** | El mismo ZIP desde la página de release; sin salto extra. |
| **SDKMAN** (candidatura `pipelinek`) | **Pendiente** | Onboarding con el proveedor en curso; el script de publicación está listo pero bloqueado por `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN`. Resuelve digests del manifiesto `SHA256SUMS` de la release vía `scripts/release/resolve-release-digest.sh`. Contratos: `python3 scripts/release/test_sdkman_digest.py`. Ver [ADR-0089](docs/v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md). |
| **Homebrew** (`rubentxu/tap/pipeline`) | **Futuro** | Tap sin empezar. Reutilizará el mismo ZIP. |
| **mise / asdf** | **Futuro (harness)** | Viven en el repo externo `pipelinek-release-harness`, no aquí. |
| **Scoop / imagen de contenedor** | **Futuro** | Puertas a demanda; reutilizarían el ZIP. |

Ningún canal se saltará el ZIP de GitHub Releases: todos descargarán o referenciarán el mismo
artefacto canónico y verificarán su SHA-256.

### Digests

**`0.47.0` — la release actual.** El digest de su ZIP procede del manifiesto `SHA256SUMS` de la release:

- **SHA-256 del ZIP**: `2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c` (según `SHA256SUMS`)
- **También en esa release**: `distribution-manifest.json`, `candidate-handoff.json`, `pipelinek-0.47.0.sbom.json`

**`0.39.0` — la release contra la que se ejecutó la verificación local de abajo.**

- **SHA-256 del ZIP**: `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`
- **SHA-256 del binario**: `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee`
- **Commit certificado**: `951b3cb5695ecc46c877776e330266e4bd44aa9e`
- **Ejemplos 01–10**: cada fila de la tabla de arriba se ejecutó contra ese binario en JDK 24.0.2
  (Temurin), Linux x86_64, con el exit code y el outcome indicados.
- **`pipelinek doctor`**: jdk 24.0.2 (Eclipse Adoptium), os Linux, workdir escribible.

---

## Desarrollo local

### Requisitos

| Herramienta | Versión | Nota |
|---|---|---|
| JDK | 21 | El build pide toolchain 21. ⚠️ `.tool-versions` dice 24.0.2 y `devbox.json` dice 21 |
| Gradle | 8.14.5 | Vía el wrapper, que **sólo existe en `v2/gradlew`** |

Opcional: `just` + `devbox` (`just bootstrap` = `devbox install`).

### Comandos

```bash
# ✅ las dos formas correctas
cd v2 && ./gradlew check
v2/gradlew -p v2 check

# ❌ esta falla: no hay ./gradlew en la raíz
./gradlew -p v2 check
```

| Tarea | Qué hace |
|---|---|
| `cd v2 && ./gradlew check` | Gate normal |
| `cd v2 && ./gradlew check --rerun-tasks` | Gate completo desde cero (es el que exigen los recibos) |
| `cd v2 && ./gradlew :pipeline-application:installDist` | Compila el binario en `build/install/pipelinek/bin/` |
| `cd v2 && ./gradlew :pipeline-application:test --tests 'CanonicalInMemoryCliTest'` | Bucle rápido de un solo test |
| `cd v2 && ./gradlew :pipeline-architecture-tests:test` | Sólo los ~45 fitness tests de arquitectura |

Vías `just` (con la ruta del wrapper corregida): `just t <patrón>`, `just app-fast`, `just gate-app`,
`just gate`, `just gate-escalate`, `just changed [base]`, `just doctor`, `just bootstrap`.

### Variables de entorno

| Variable | Para qué |
|---|---|
| `PIPELINE_CREDENTIALS_STORE` | Ruta del almacén de secretos cifrado |
| `PIPELINE_STORE_PASSPHRASE` | Passphrase para descifrarlo |
| `JAVA_HOME`, `GRADLE_USER_HOME`, `MAVEN_OPTS` | Sólo build (los define `devbox.json`) |

No se carga un `.env` automáticamente (`justfile` lo desactiva explícitamente).

### Cinco trampas operativas

1. **Nunca lances dos Gradle a la vez** sobre el mismo checkout. Hay un `FileLock` fail-fast y la
   segunda invocación lanza `GradleException`. Usa worktrees separados.
2. **Lee `^e: ` en la salida antes de creer cualquier resultado.** Si la compilación de tests falla,
   Gradle ejecuta la clase compilada *anterior* e informa su resultado. **Un fallo de compilación no
   es un RED.**
3. **No hay CI.** El gate es local y manual, sobre el SHA exacto.
4. **Un test que afirma sobre milisegundos es una prueba sobre la máquina.** Afirma un resultado
   discreto, no una duración.
5. **No afirmes que un test pasó si no lo has visto fallar.** Si mutas el código a propósito y el
   test sigue verde, el test es malo, no el código.

---

## Contribuir

### Los 6 ficheros que te dan el 80%

En este orden. No es el orden habitual, y es a propósito:

| # | Fichero | Por qué ese |
|---|---|---|
| 1 | [`CONTEXT.md`](CONTEXT.md) (45 líneas) | El glosario canónico **con anti-términos**. Te evita decir "pipeline spec" cuando el término oficial es *Compiled Pipeline*. |
| 2 | [`docs/v2/01-product/PRD_V2.md`](docs/v2/01-product/PRD_V2.md) | La única descripción vigente del producto: usuarios y trabajos. |
| 3 | [`v2/settings.gradle.kts`](v2/settings.gradle.kts) | El grafo real de módulos, con comentarios que explican **por qué** está cada separación y cuál no se publica. |
| 4 | [`docs/pipelinek-semantic-evolution/01-semantic-constitution.md`](docs/pipelinek-semantic-evolution/01-semantic-constitution.md) | **Las leyes.** Esto es lo que hay que saber de memoria. |
| 5 | [`docs/v2/07-uat/CERTIFICATION_PROTOCOL.md`](docs/v2/07-uat/CERTIFICATION_PROTOCOL.md) | Qué significa `STEP-CERT`, qué exige un gate y qué es `NOT_RUN`. Sin esto te crees los recibos demasiado. |
| 6 | [`docs/v2/05-roadmap/ROADMAP.md`](docs/v2/05-roadmap/ROADMAP.md) | La secuencia operativa: qué está activo y en qué orden. |

> ⚠️ Este fichero es el espejo traducido de `README.md`. Si los dos se contradicen, **manda
> `README.md`** y dilo como bug.

### Los conceptos que hay que interiorizar

1. **Fail-closed.** Ante una duda, parar. Un hueco silencioso es **peor** que un valor erróneo,
   porque el valor se comprueba y el hueco no se nota.
2. **Un recibo vale para su SHA y no hereda nada.** Si el código se movió, la evidencia caducó.
3. **Un test que no puede fallar no es una prueba.** Si una aserción nunca la viste caer, no la has probado.
4. **Compilar no es conservar.** Cada constructo del DSL tiene que tener un carrier tipado, ser un
   desugar puro con tests de equivalencia, o fallar cerrado.
5. **Mutación > revisión.** Una aserción que nadie ha visto caer no ha sido probada. Y mutar de más
   también es un error: cada mutación se atribuye 1:1 a lo que tumba.

### Flujos que conviene leer con el código abierto

- Un Step de principio a fin: su `StepContract`, cómo pide una capacidad, cómo llega al journal.
- El camino fail-closed: qué pasa cuando un Step no está en el registro.
- Un evento completo: quién lo emite, cómo se serializa, cómo se relee, y qué pasa si no se puede leer.
- Un replay: qué decide `EffectReplayPolicy` y por qué un `UNSTABLE` sí es reutilizable.

### Antes de abrir tu primer PR

El proyecto tiene una disciplina de **work units** y una puerta de calidad. Léelas
([`AGENTS.md`](AGENTS.md)) antes de escribir código: no toda modificación entra por la puerta de las
grandes, y algunas necesitan trazabilidad explícita a un criterio de salida.

---

## Licencia

[MIT](LICENSE) © Rubén Torres.

---

## Agradecimientos

- Al equipo de Kotlin, por un lenguaje excelente.
- A [Jenkins Pipeline](https://www.jenkins.io/doc/book/pipeline/) y GitHub Actions, por el modelo
  mental al que este DSL pretende parecerse.