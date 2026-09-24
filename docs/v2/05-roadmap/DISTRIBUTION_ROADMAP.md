# DISTRIBUTION_ROADMAP — Distribuir el ZIP canónico sin romper la separación cross-repo

> **Estado:** ACTIVO (2026-09-24).
> **Autoridad:** directiva del operador 2026-09-24T10:53Z ("corregir primero el README, dejar GitHub Releases como instalación principal y continuar con la imagen OCI que ya estáis probando. En paralelo, desarrollar un instalador ligero y la integración con mise. SDKMAN puede seguir su proceso de alta sin bloquear nuevas releases").
> **Source of truth:** este documento + `DISTRIBUTION_RELEASE_SPEC.md` (propuesto) + `ADR-0089` (active) + el histórico `DISTRIBUTION_STRATEGY.md` (archivado) + `LPR_GATE_1` §8 (canales pendientes).
> **Aplica a:** el repositorio `Rubentxu/pipeline-kotlin`. El canal OCI y la certificación por canal viven en `Rubentxu/pipelinek-release-harness` (ver `HARNESS_AGENTS_TEMPLATE.md` §"Frontera de responsabilidad" punto 3).

## 0. Principio fundamental (no cambia)

Una sola construcción por candidata. Un ZIP canónico con SHA-256 y SBOM. Cualquier canal — GitHub Releases, OCI, mise, Homebrew, SDKMAN, Scoop, lo que sea — consume **los mismos bytes** y verifica el digest contra el manifiesto del release. **Ningún canal reconstruye PipelineK.** Esto ya está fijado por `ADR-0089-distribution-artifact-authority-sdkman.md` y por `DISTRIBUTION_RELEASE_SPEC.md` §1, y se mantiene con la nueva separación de repositorios:

```
pipeline-kotlin (este repo)
    │
    └── Genera candidata ZIP + SHA-256 + SBOM
                │
                ▼
pipelinek-release-harness (externo)
    │
    ├── Verifica ZIP instalado
    ├── Certifica proyectos reales (M1..M3)
    ├── Certifica imagen OCI (DIST-3)
    └── Aprueba o rechaza la candidata
                │
                ▼
          Release estable
                │
       ┌────────┼─────────┐
       ▼        ▼         ▼
    GitHub     OCI     Instaladores
    Releases   image    externos
                         │
                  ┌──────┼──────┐
                  ▼      ▼      ▼
                mise  Homebrew SDKMAN
```

## 1. Estado al 2026-09-24 (post-merge PR #73, #74, #75, #77, #78)

| Canal | Estado | Detalle |
|---|---|---|
| **GitHub Releases ZIP** | **Available** | `pipelinek-0.39.0.zip` (91.4 MB, SHA-256 `385b140c…cbb8`). Install path oficial hoy. |
| **Direct download** | **Available** | Mismo ZIP sin caché de red intermedio. |
| **Instalador autónomo** (bash) | **Available** | DIST-2 cerrado: `scripts/install-pipelinek.sh` versionado en main (commit `9a4a09a3`, SHA-256 `f86d1d2f…`). URL allowlist fail-closed, SHA-256 verificado, no sudo, no daemon. |
| **OCI image** | **In progress (harness)** | DIST-3: el harness ya tiene `pipelinek:0.39.0` con mise; falta separar la imagen producto (mínima) de la imagen del harness (con toolchains de 5 lenguajes) y publicar la primera en Docker Hub con digest. Vive en el harness. |
| **mise Aqua backend / GitHub release backend** | **Not started (harness)** | DIST-4: registrar `pipelinek` como herramienta instalable desde Aqua o GitHub release backend. **Vive en el harness** (registry externo a este repo). |
| **Homebrew tap** (`rubentxu/tap/pipeline`) | **Not started (harness)** | DIST-5: tap externo. Histórico LFC9-004 archivado. **Vive en el harness.** |
| **SDKMAN** (`pipelinek` candidate) | **Pending (harness)** | DIST-6: vendor onboarding. 3 pasos pendientes: publish, install UAT, promote to default. **No bloquea otras releases**; el operador confirmó 2026-09-24T11:20Z que SDKMAN no es necesario para distribuir v0.39.0 hoy. |
| **asdf-vm** (`asdf-pipeline` plugin) | **Not started (harness)** | DIST-7: plugin externo con `bin/install`, `bin/download`, `bin/list-bin`. **Vive en el harness.** |
| **Scoop** | **Future** | Condicionado a demanda Windows. |

**Resumen v0.39.0:** los usuarios pueden instalar la versión pública YA, por dos canales oficiales (ZIP + instalador bash). Ningún otro canal es necesario para usar el producto hoy.
| **Jlink / native image** | **Future** | Optimización; gated por benchmarks. |

## 2. Hoja de ruta DIST-1..DIST-6

### DIST-1 — README e instalación manual del ZIP corregidos y probados

- **Entregable**: README del repo describe la receta de instalación del ZIP con verificación de SHA-256 inline (variable `VERSION=`, `curl -fL`, `sha256sum -c -`, `unzip`); SDKMAN mencionado como canal futuro, no como método primario.
- **Estado**: **closed (2026-09-24T10:54Z)**. Commits `cc372595`, `6d6d9752`, `ed610a08`, `b31xxxxx` sobre `wu/rp-harness-coordination`.
- **Verificación empírica**: ZIP oficial `pipelinek-0.39.0.zip` descargado (91.416.100 bytes), SHA-256 confirmado, descomprimido y ejemplos 01–10 ejecutados con los exit codes y outcomes documentados en el README.

### DIST-2 — Instalador autónomo versionado

- **Entregable**: `scripts/install-pipelinek.sh` (Bash, sin dependencias externas más allá de `curl`, `sha256sum`, `unzip`, `sed`, `awk`, POSIX shell). Comportamiento:
  - `install <version>` → descarga ZIP canónico desde GitHub Releases, verifica SHA-256 contra el manifiesto del release (digest publicado), instala en `~/.local/share/pipelinek/versions/<version>/`, registra el actual en `~/.local/share/pipelinek/current`, opcionalmente añade `bin/` al `PATH` del usuario (sin tocar shell rc).
  - `use <version>` → cambia el symlink `current` a esa versión.
  - `list` → muestra versiones instaladas y cuál es la activa.
  - `uninstall <version>` → elimina una versión.
  - `doctor` → invoca `pipelinek doctor` de la versión activa.
  - **No** crea archivos de estado de PipelineK dentro del repositorio consumidor (separación clara entre directorio de instalación y datos de ejecución).
  - **No** requiere `sudo`.
- **Compatibilidad**: Linux, macOS, Windows/WSL. Sin daemon, sin servicio, sin package manager propio.
- **Criterios de cierre**:
  - El script corre end-to-end sobre la candidata `v0.39.0` con `sha256sum -c -` verde.
  - Después de instalar, `~/.local/share/pipelinek/current/bin/pipelinek version` devuelve `pipeline 0.39.0`.
  - `use 0.39.0` (segunda vez) es idempotente.
  - Rollback (`use <previous>`) funciona contra una versión previamente instalada.
  - El instalador se prueba en CI local con pipelinek (DIST-2 + WU-RP-043 dogfooding).
  - El script vive en `scripts/install-pipelinek.sh`, versionado en el repo, con `set -Eeuo pipefail`, fail-closed ante digest mismatch.

### DIST-3 — Imagen OCI mínima certificada

- **Entregable 1**: `pipelinek:<version>` publicado en Docker Hub (digest OCI estable).
  - Contenido: ZIP canónico + JRE compatible + utilidades mínimas (`bash`, `unzip`, `ca-certificates`, `tini` como PID 1 opcional). Sin toolchains de Java/Rust/Python/Go/.NET.
  - Tamaño objetivo: ≤ 250 MB (ZIP + JRE).
- **Entregable 2**: separación explícita con `pipelinek-harness:<perfil>`. Esa imagen es del harness, no se publica como producto.
- **Criterios de cierre**:
  - Digest OCI inmutable en la release de GitHub.
  - `docker run --rm pipelinek:<version> --version` devuelve `pipeline <version>`.
  - `docker run --rm pipelinek:<version> --doctor` devuelve un informe razonable del contenedor.
  - Smoke test: ejecutar `examples/03-shell.pipeline.kts` dentro del contenedor contra un `--workspace` montado como volumen.
  - El harness (`pipelinek-release-harness`) verifica la imagen antes de la promoción a `stable`.

### DIST-4 — Instalación reproducible mediante mise

- **Entregable**: registro de `pipelinek` como herramienta instalable en **Aqua backend** (preferido) o **GitHub release backend** (fallback). Sin plugin bespoke.
- **Mecánica**:
  - `mise use -g pipelinek@0.39.0` resuelve el digest y la URL canónicos desde Aqua o desde GitHub Releases (con `sha256sum -c`).
  - Configuración `.mise.toml` / `mise.toml` permite fijar la versión de PipelineK junto al JDK del proyecto.
- **Criterios de cierre**:
  - `mise use -g pipelinek@0.39.0` en una máquina limpia (Fedora o Ubuntu reciente) instala y deja `pipelinek` en el `PATH` resultante.
  - `mise ls pipelinek` muestra 0.39.0.
  - El binario invocado es el del ZIP canónico (digest verificado).
  - Diferencia explícita con `mise install java`: mise en este repo se usa **primero** para provisionar JDK/Gradle/herramientas del harness, **segundo** (DIST-4) para ofrecer PipelineK mismo como herramienta instalable.

### DIST-5 — Homebrew tap

- **Entregable**: tap `rubentxu/homebrew-tap` con la fórmula `pipelinek.rb` que descarga el ZIP canónico y verifica SHA-256.
- **Criterios de cierre**:
  - `brew install rubentxu/tap/pipelinek` en macOS arm64 instala correctamente.
  - El binario resultante coincide con el ZIP (`shasum -a 256` sobre `bin/pipelinek`).
  - Tests funcionales (no sólo `--version`): ejecutar `pipelinek doctor` y un pipeline de ejemplo.

### DIST-6 — SDKMAN, Scoop y binarios nativos cuando proceda

- **SDKMAN**: cuando `WU-LPR-080` cierre (vendor onboarding + install UAT + promote). No bloquea DIST-1..DIST-5.
- **Scoop**: si la demanda Windows lo justifica. Reutiliza `bin/pipelinek.bat` y el mismo ZIP.
- **Jlink / native image**: optimización post-1.0; gated por benchmarks reales de startup y footprint.

## 3. Compatibilidad con la separación cross-repo

| Tarea | Owner repo | Razón |
|---|---|---|
| Construir ZIP canónico y SBOM | `pipeline-kotlin` | Único autoritativo de la fuente. |
| Firmar / etiquetar el release | `pipeline-kotlin` (release receipt) | La candidata es del repo de producto. |
| Verificar digest y bytes del ZIP | `pipelinek-release-harness` | Es certificación externa. |
| Publicar `pipelinek:<version>` en Docker Hub | `pipelinek-release-harness` | Es la cara visible del canal OCI; el harness garantiza que la imagen sea los mismos bytes. |
| Definir fórmula Homebrew | `pipelinek-release-harness` (o `Rubentxu/homebrew-tap` con feedstock versionado) | Es un adaptador del ZIP; no compila. |
| Mise Aqua backend entry | `pipelinek-release-harness` (tooling de certificación) o contribución al índice Aqua | Igual que Homebrew: consume el ZIP. |
| Publicar candidato en SDKMAN | `pipelinek-release-harness` (credentials vendor viven allí) | Aislamiento: la identidad que verifica es distinta de la que publica. |

Regla firme: **este repo NO modifica los scripts de publicación del harness**, y el harness NO toca el código fuente de PipelineK. Sólo se intercambian artefactos por la frontera del ZIP.

## 4. Riesgos conocidos

- **ZIP universal vs Jlink image**: la propuesta histórica (LFC-9) prefiere Jlink. Hoy seguimos con ZIP JVM estándar. Si el tamaño del ZIP (91 MB) o el requisito de JDK externo se vuelven un problema para adopción, hay que reabrir ADR-0089 y promover DIST-3 (OCI mínima) o DIST-6 (Jlink) antes que ahora.
- **SHA-256 inline en README**: cualquier release nueva obliga a actualizar el digest en este README y en `installation.md` / `upgrading.md`. Plantilla pendiente: revisar antes de cada release (checklist de release include).
- **SDKMAN vendor onboarding**: está fuera del control de este repo. Si el operador activa una ventana de onboarding, la WU es del harness.
- **Seguridad de la receta Bash**: `scripts/install-pipelinek.sh` debe validar que la URL es exactamente `https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/...` antes de ejecutar; de lo contrario, una variable manipulada podría apuntar a un ZIP hostil. Fail-closed si el dominio no coincide.

## 5. Anti-patrones explícitos

- ❌ Volver a poner `sdk install pipelinek 0.39.0` en el primer bloque de comandos del README. SDKMAN está pending, no available.
- ❌ Publicar `pipelinek:<version>` sin digest OCI inmutable.
- ❌ Reconstruir PipelineK dentro de un formula Homebrew, Scoop, plugin asdf o installer. Todo consume el ZIP.
- ❌ Mover scripts de publicación de este repo al harness (o viceversa) sin justificación técnica y PR cruzada.
- ❌ Lanzar DIST-5 antes de que DIST-3 (OCI) esté verde: el OCI image es el camino más limpio para runners reproducibles; Homebrew añade poco si el OCI ya cubre Docker.
- ❌ Comprometerse a fechas de SDKMAN; el proceso de vendor onboarding es externo.

## 6. Acción inmediata para este repo

1. ✅ DIST-1 cerrado en este turno.
2. Abrir **WU-DIST-2** sobre `wu/rp-distribution-installer`: `scripts/install-pipelinek.sh`. Sigue el patrón de los scripts en `scripts/release/`: bash con `set -Eeuo pipefail`, fail-closed, sin daemon.
3. Mantener el WIP del operador intacto en working tree (no tocar `scripts/run-pipelinek.sh`, `scripts/run-pipelinek`, `scripts/verify-rp-043.py`, `scripts/gen-certification-ledger.py`, `docs/pipeline-kotlin-config-overlay-package/`, `Main.kt`, `WorkspaceOperations.kt`, recibos WU-RP-053, fixtures `v2/compatibility/*.pipeline.kts`).
4. La imagen OCI (DIST-3) pertenece al harness; este repo se limita a entregar el ZIP canónico.
