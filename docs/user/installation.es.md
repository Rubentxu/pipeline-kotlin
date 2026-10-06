# PipelineK — Instalación

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**Última release publicada**: `0.47.0`. Confirmado el 2026-10-06: `releases/latest` redirige a
`releases/tag/v0.47.0`, y la release publica `pipelinek-0.47.0.zip` más un fichero `SHA256SUMS`.
**Los comandos de abajo se leyeron del código fuente, no ejecutando un binario descargado.**

> **Divergencia de documentación.** Esta página antes llevaba la cabecera *"Release verified against:
> pipelinek 0.39.0"*. Esa cabecera queda retirada: los comandos de aquí se leyeron de la rama de
> desarrollo, no de un binario publicado. Antes la página mezclaba dos cosas distintas con el mismo
> nombre, "la release publicada". Ahora están separadas:
>
> - **`0.47.0`** es la **última release publicada**. Su digest de ZIP viene del propio fichero
>   `SHA256SUMS` de la release — procedencia fiable, pero nadie en este repositorio ejecutó ese ZIP.
> - **`0.39.0`** es la **última release con un recibo ejecutado** (commit `951b3cb5…`). Su digest de
>   ZIP *y* de binario están registrados en este repositorio.
>
> Registrado el 2026-10-06. Ver `docs/user/README.es.md` → "Divergencias conocidas".

> **Autoridad.** Este repositorio **no tiene CI remota desde 2026-09-30**: `.github/workflows/` no
> existe. El commit `754ddda0` eliminó los workflows de CI Nada de esta página está respaldado por un pipeline en verde, y nada aquí
> afirma que el producto esté listo para producción. Lo que sí obtienes es un digest que puedes
> comprobar tú mismo.

## Al terminar esta página podrás

- [ ] Instalar el ZIP de la release y demostrar que los bytes son los canónicos.
- [ ] Confirmar que el binario funciona en tu máquina con `pipelinek version` y `pipelinek doctor`.
- [ ] Usar el instalador multiversión para tener más de una versión en paralelo.
- [ ] Saber qué canales de instalación **todavía no existen**, para no perder tiempo con ellos.

## Palabras que vas a encontrar

| Palabra | Significado cotidiano | Aquí |
|---|---|---|
| Binario | El programa compilado | `bin/pipelinek` dentro del ZIP |
| ZIP | Una caja de archivos | El artefacto canónico de release (`docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md`) |
| Digest SHA-256 | Una huella de 64 caracteres hexadecimales | Dos archivos distintos nunca comparten una |
| `PATH` | La lista de carpetas donde tu shell busca comandos | Si `pipelinek` no está ahí, "no está instalado" |
| Raíz de instalación | La única carpeta donde vive todo | `~/.local/share/pipelinek` |

## Requisitos

| Requisito | Valor | Notas |
|---|---|---|
| Java | **21 o superior** | Certificado en Temurin 21.0.8 y 24.0.2. El ZIP **no** incluye un JDK |
| Sistema operativo | Linux, macOS o Windows vía WSL | WSL es el único camino soportado en Windows |
| Shell | Bash 4+ | Sólo para el instalador multiversión (`scripts/install-pipelinek.sh:23`) |
| Herramientas para el instalador | `curl`, `sha256sum`, `unzip` en `PATH` | `scripts/install-pipelinek.sh:25` |
| Disco | ~200 MB libres | Distribución más datos del workspace |

> **Nota sobre macOS.** El instalador llama a `sha256sum` (`scripts/install-pipelinek.sh:276`), que
> macOS no trae por defecto. Instala GNU coreutils antes (`brew install coreutils`) o usa el camino
> manual con ZIP de abajo. **NO VERIFICADO**: en este repositorio no hay ninguna ejecución del
> instalador registrada en macOS.

## Opción A — instalar el ZIP canónico (funciona hoy)

### 1. Los digests

Comprueba esto antes de descargar nada.

| Elemento | Valor |
|---|---|
| Release a instalar | `0.47.0` |
| SHA-256 del ZIP | `2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c` |
| De dónde sale ese digest | Del fichero `SHA256SUMS` de la propia release |
| SHA-256 del binario | **NO VERIFICADO** — ningún recibo de este repositorio registra el digest del binario dentro del ZIP de `0.47.0` |
| URL del ZIP | `https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.47.0/pipelinek-0.47.0.zip` |

> **Dónde *no* está ese digest.** La release también muestra `v0.47.0.zip` y `v0.47.0.tar.gz` en su
> lista de adjuntos. Esos son los **archivos de código fuente** que GitHub genera desde el tag.
> `SHA256SUMS` no los cubre y no son la distribución. Si descargas `v0.47.0.zip` en lugar de
> `pipelinek-0.47.0.zip` te baja un tarball del repositorio, y el digest de arriba no coincidirá.

<details>
<summary>Release anterior con un recibo totalmente ejecutado: <code>0.39.0</code></summary>

| Elemento | Valor |
|---|---|
| Release | `0.39.0` |
| SHA-256 del ZIP | `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8` |
| SHA-256 del binario | `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee` |
| Commit certificado | `951b3cb5695ecc46c877776e330266e4bd44aa9e` |
| URL del ZIP | `https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.39.0/pipelinek-0.39.0.zip` |

Estos son los digests de una release que sí se descargó, se comprobó y se ejecutó. Si quieres la
evidencia más fuerte disponible en vez del código más nuevo, instala esta.

</details>

### 2. Descargar y verificar

```bash
VERSION=0.47.0
URL="https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"
curl -fsSL -o "pipelinek-${VERSION}.zip" "${URL}"

# Verifica el digest del ZIP ANTES de descomprimir nada.
echo "2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c  pipelinek-0.47.0.zip" | sha256sum -c -
# Equivalente en macOS:  shasum -a 256 pipelinek-0.47.0.zip
```

Continúa sólo si imprimió `pipelinek-0.47.0.zip: OK`. Si no coincide, para.

### 3. Descomprimir y ponerlo en `PATH`

```bash
ROOT="$HOME/.local/share/pipelinek"
mkdir -p "${ROOT}/versions"
unzip -q "pipelinek-${VERSION}.zip" -d "${ROOT}/versions"
export PATH="${ROOT}/versions/pipelinek-${VERSION}/bin:${PATH}"
```

> El ZIP contiene exactamente un directorio de primer nivel, `pipelinek-<version>/`, y el binario
> debe estar en `bin/pipelinek` dentro de él. El instalador rechaza cualquier otra cosa
> (`scripts/install-pipelinek.sh:294-311`). Si tu archivo tiene otra forma, no es el correcto.

### 4. Hacer el `PATH` permanente

Añade la misma línea a `~/.bashrc` o `~/.zshrc`:

```bash
export PATH="$HOME/.local/share/pipelinek/versions/pipelinek-0.47.0/bin:$PATH"
```

### 5. Verificar

```bash
pipelinek version
pipelinek doctor
```

| Comando | Esperado | Fuente |
|---|---|---|
| `pipelinek version` | `pipeline 0.47.0` — fíjate en que la salida empieza por `pipeline `, **no** por `pipelinek` | `Main.kt:83` |
| `pipelinek doctor` | Tres líneas: `jdk:`, `os:`, `workdir:` | `Main.kt:91-108` |

`doctor` sale con `0` si todo está bien y con `2` si el directorio de trabajo no es escribible
(`Main.kt:110`).

## Opción B — el instalador multiversión

`scripts/install-pipelinek.sh` mantiene varias versiones en paralelo y cambia entre ellas con un
único enlace simbólico. Es la opción correcta si actualizas a menudo o trabajas en proyectos
fijados a versiones distintas.

**Analogía**: en lugar de sustituir la única llave inglesa de la caja de herramientas, guardas un
cajón etiquetado por versión y apuntas el mango al cajón que te hace falta hoy.

### Subcomandos

| Comando | Qué hace |
|---|---|
| `install <version>` | Descarga, verifica el digest, extrae, verifica la identidad y luego publica |
| `use <version>` | Apunta el enlace `current` a una versión instalada |
| `list` | Tabla de versiones instaladas; la activa se marca con `*` |
| `uninstall <version>` | Elimina una versión que **no** está activa |
| `doctor` | Informa del binario resuelto y del `PATH`, y luego ejecuta `pipelinek doctor` |
| `help` | Uso |

Tabla de despacho: `scripts/install-pipelinek.sh:532-536`.

### Instalar y activar

```bash
# Ejecutar desde un checkout del repositorio
scripts/install-pipelinek.sh install 0.47.0
scripts/install-pipelinek.sh use 0.47.0
export PATH="$HOME/.local/share/pipelinek/current/bin:${PATH}"
pipelinek version
```

### Qué garantiza

| Garantía | Detalle | Fuente |
|---|---|---|
| Allowlist de URLs | Sólo `github.com` y `objects.githubusercontent.com`; cualquier otra se rechaza antes de escribir un solo archivo | `scripts/install-pipelinek.sh:54`, `:121` |
| Autoridad de digest | `SHA256SUMS` junto al ZIP. Se niega a instalar sin una entrada válida | `scripts/install-pipelinek.sh:225`, `:254-270` |
| Transaccional | Descarga, digest, extracción y verificación de identidad ocurren en un directorio temporal; el directorio de la versión aparece sólo después de que todas las comprobaciones pasaron | `scripts/install-pipelinek.sh:40-43` |
| Identidad exacta | El binario extraído debe reportar exactamente la versión solicitada | `scripts/install-pipelinek.sh:310-335` |
| Sin `sudo` | Nunca escribe en `/usr` ni en `/opt`, nunca lanza un demonio | `scripts/install-pipelinek.sh:38` |
| Idempotente | `install` sobre una versión existente no hace nada; `uninstall` sobre una ausente tampoco | `scripts/install-pipelinek.sh:37-38` |

### Variables de entorno

| Variable | Por defecto | Para qué sirve |
|---|---|---|
| `PIPELINEK_HOME` | `~/.local/share/pipelinek` | Raíz de instalación |
| `PIPELINEK_RELEASE_BASE_URL` | GitHub Releases | Debe cumplir la allowlist |
| `PIPELINEK_MIRROR_BASE_URL` | *(vacío)* | Instalar desde un mirror o una copia sin red; los hosts de loopback siempre están permitidos |
| `PIPELINEK_SHA256_<VERSION>` | *(sin definir)* | Digest esperado, p. ej. `PIPELINEK_SHA256_0_39_0` (los puntos se convierten en guiones bajos) |

Fuente: `scripts/install-pipelinek.sh:30-35`, `:228`, `:503`.

## Canales que hoy no existen

Dos métodos funcionan hoy: la **Opción A** (ZIP canónico) y la **Opción B** (instalador
multiversión). Tres más están especificados pero sin construir, y uno es un patrón habitual que este
proyecto no soporta en absoluto:

| Método | Estado | Detalle |
|---|---|---|
| `mise` (`mise use -g pipelinek@0.47.0`) | **Especificado, sin construir** | Planificado como **DIST-4** en [`DISTRIBUTION_ROADMAP.md`](../v2/05-roadmap/DISTRIBUTION_ROADMAP.md): registrar `pipelinek` en el backend de Aqua o de GitHub Releases. El registro de plugins es externo a este repositorio, así que los comandos aún no funcionan |
| `asdf` (el plugin `asdf-pipeline`) | **Especificado, sin construir** | Planificado como **DIST-7**, un plugin externo que expone `bin/install`, `bin/download` y `bin/list-bin`. También vive en el harness externo. `asdf` ya lee el `.tool-versions` de este repo para `java`, `gradle` y `maven`, pero `pipelinek` no está therein, y añadirlo hoy rompería a todo usuario de `asdf` con un plugin sin resolver |
| One-liner `curl \| sh` | **Existe, con un matiz** | `scripts/install-pipelinek-curl.sh` es un bootstrap POSIX `sh` que resuelve el instalador, lo verifica y delega en él. Existe porque `scripts/install-pipelinek.sh:46` activa `set -Eeuo pipefail` y la línea 56 lee `BASH_SOURCE[0]`, que está vacío en stdin — así que `curl … \| sh` contra el instalador *en sí* falla siempre. **Bloqueado en la práctica**: ninguna release publicada incluye el instalador como asset (verificado el 2026-10-06: `…/releases/download/v0.47.0/install-pipelinek.sh` devuelve HTTP 404), así que el bootstrap sale con `10` en vez de instalar. Es código correcto contra un pipeline de release incompleto |

Todos los canales —los dos que existen y los tres planificados— deben consumir el mismo ZIP canónico y
verificar el mismo SHA-256. Esa regla está en
[`ADR-0089`](../v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md) y en
[`DISTRIBUTION_RELEASE_SPEC.md`](../v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md): ningún canal
reconstruye PipelineK.

El README principal documenta los cinco métodos, incluida la forma exacta para la que están diseñados
`mise` y `asdf`: [`README.es.md` → Métodos de instalación](../../README.es.md#métodos-de-instalación).

| Canal | Estado | Detalle |
|---|---|---|
| ZIP de GitHub Releases | **Disponible** | Opción A de arriba |
| `scripts/install-pipelinek.sh` | **Disponible** | Opción B de arriba |
| Homebrew (`brew install`) | **No disponible** | No existe un tap de Homebrew para PipelineK. `brew install pipelinek` no funciona |

## Trampas y bordes

Léelas después de una instalación correcta.

| Trampa | Qué ocurre | Qué hacer |
|---|---|---|
| Te saltaste la comprobación del digest | Confías en un archivo que no puedes probar | Ejecuta siempre `sha256sum -c -` primero |
| `pipelinek` no aparece tras instalar | `${ROOT}/.../bin` no está en `PATH` | Añádelo a `~/.bashrc` y abre una shell nueva |
| Un `pipelinek` más antiguo tapa el tuyo | `version` imprime el número equivocado | `scripts/install-pipelinek.sh doctor` avisa exactamente de esto (`scripts/install-pipelinek.sh:474-478`) |
| `pipelinek version` sale con `3` | El artefacto no tiene `Implementation-Version` en su manifiesto. El CLI se niega a inventar uno | Reconstruye o vuelve a descargar: es un artefacto roto, no una máquina rota (`Main.kt:80`) |
| Mezclar la Opción A y la Opción B | El instalador nombra los directorios `versions/0.47.0`; el camino manual descomprime en `versions/pipelinek-0.47.0` | Elige un método por máquina |
| Le pasaste una pre-release al instalador | `0.47.0-rc1` se rechaza: la versión debe ser `MAJOR.MINOR.PATCH` | Usa sólo releases publicadas (`scripts/install-pipelinek.sh:55`) |

## Siguiente

- [`quickstart.es.md`](quickstart.es.md) — escribe y ejecuta tu primer pipeline.
- [`upgrading.es.md`](upgrading.es.md) — cambia de versión y vuelve atrás.
- Hub: [`docs/user/README.es.md`](README.es.md).