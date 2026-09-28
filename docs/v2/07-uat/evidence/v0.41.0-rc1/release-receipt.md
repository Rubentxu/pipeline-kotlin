# PipelineK v0.41.0-rc1 — release-candidate receipt

**Estado:** `PUBLISHED_PRERELEASE_READY_FOR_HARNESS_INTAKE`

**Candidata:** `0.41.0-rc1`
**Tag:** `v0.41.0-rc1`
**Commit de build:** `4a1a97502e60220609bf49cf4de7f6a0b230e21f`
**Rama:** `main`
**Commit anterior estable:** `v0.40.0` at `b71ec999c1ee70da21e768227a090561a8bc38e1`
**Commits desde la estable:** `45`
**GitHub Release:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.41.0-rc1

Esta es una **release candidate**, no una release estable. El repositorio
entrega `READY_FOR_HARNESS_INTAKE`. La certificación externa y la promoción
estable pertenecen a `pipelinek-release-harness` y deben reutilizar estos
mismos bytes, sin reconstrucción.

## Material inmutable

Directorio local ignorado por Git:

```text
dist/candidates/v0.41.0-rc1/
├── pipelinek-0.41.0-rc1.zip
├── pipelinek-0.41.0-rc1.sbom.json
├── SHA256SUMS
├── release-manifest.json
└── release-notes.md
```

| Material | Bytes | SHA-256 |
|---|---:|---|
| `pipelinek-0.41.0-rc1.zip` | 92,135,132 | `d49edc0852a678d97281c7b584af047f632c8ba8f04606c5b5f9165076fa4cea` |
| `pipelinek-0.41.0-rc1.sbom.json` | 17,557 | `dbb284b35170458945599380b7ac66d8d66ebd2eb3f95eec41a2106b9992df90` |
| `SHA256SUMS` | 244 | `91dfc6e51ebe7e5432e7ed64d9e1fe0707e1937ac564fe9e2454873a4a2fa5cf` |
| `release-manifest.json` | 12,496 | `12b496363097a46bc7b42415385addc57e1de5a12dd0682c4aae8f99984e66ec` |

`release-manifest.json` no se incluye en `SHA256SUMS` porque incluir su propio
digest crearía un problema de punto fijo. Su integridad se verifica comparando
el asset descargado con el material local y validando su JSON.

## Gates locales

### Build y distribución

```text
./gradlew :pipeline-application:distZip --rerun-tasks --console=plain
exit_code: 0
observed: BUILD SUCCESSFUL in 32s; 48 actionable tasks executed

./gradlew :pipeline-application:installDist --rerun-tasks --console=plain
exit_code: 0
observed: BUILD SUCCESSFUL in 19s; 48 actionable tasks executed
build log sha256: c26a1660aaa7339eaaca03fc7e79ec252414f8e3d1e0ebba210747bd2cb77f0c
```

### Integridad del material

```text
unzip -t -q dist/candidates/v0.41.0-rc1/pipelinek-0.41.0-rc1.zip
exit_code: 0
observed: No errors detected in compressed data

sha256sum --strict -c dist/candidates/v0.41.0-rc1/SHA256SUMS
exit_code: 0
observed: ZIP and SBOM sums coincide

python3 -m json.tool dist/candidates/v0.41.0-rc1/release-manifest.json
exit_code: 0
observed: valid JSON
```

### Smoke de distribución instalada

El primer intento terminó con `126` antes de lanzar PipelineK porque el
workspace temporal no tenía `.tool-versions`. El entorno ofrecía
`temurin-24.0.2+12`; se repitió el mismo smoke con ese JDK fijado.

| Comando | Resultado | Evidencia |
|---|---|---|
| `pipelinek version` | PASS | `pipeline 0.41.0-rc1`, coincide con el tag |
| `pipelinek doctor` | PASS | JDK 24.0.2, Linux, workspace writable |
| `pipelinek run` | PASS | `RunFinished outcome=success`, exit code 0 |

Input del smoke:

```kotlin
pipeline {
    stages {
        stage("smoke") {
            sh("printf 'hello-from-rc1\\n' > greeting.txt && cat greeting.txt")
        }
    }
}
```

```text
input sha256: b9f53620c3d278f090646e5c367340de6cbbafae49733de74bc1dc3f66efab27
log sha256:   c47d6d7c3675dbc3b91cb1ad756df923e25ff7ee5a9873369fa78de2342fd7e0
```

### Tests y análisis estático

- `detekt --rerun-tasks` de los módulos V2: **PASS**.
- Tests afectados con `--rerun-tasks` de domain, events, architecture,
  credentials, harness, binding, scripting y artefactos: **PASS**.
- `pipeline-architecture-tests`: **319/319 PASS**.
- `pipeline-scripting-kotlin24`: **56/56 PASS**.
- Tests de aplicación afectados antes del fix final: **82 tests, 0 fallos,
  7 skipped** por un `assumeTrue` de Linux preexistente.
- Tests de `CoreDeleteDirStepUnitTest` y `CorePwdStepUnitTest` después del
  fix de fuga temporal: **BUILD SUCCESSFUL**.

El comando local completo `./gradlew test` no terminó dentro del timeout de
la herramienta. No se declara como gate verde. Conforme a `AGENTS.md`, la
matriz completa de proyectos externos y UAT de distribución pertenece al
harness, no a este repositorio.

## Publicación y verificación externa

```text
git push origin main
observed: origin/main = 4a1a97502e60220609bf49cf4de7f6a0b230e21f

git push origin v0.41.0-rc1
observed: annotated tag published; peeled tag resolves to 4a1a97502e60220609bf49cf4de7f6a0b230e21f

gh release create v0.41.0-rc1 --prerelease ...
observed: GitHub prerelease published at 2026-09-28T13:51:40Z
```

GitHub publica exactamente cuatro assets:

- `pipelinek-0.41.0-rc1.zip`
- `pipelinek-0.41.0-rc1.sbom.json`
- `SHA256SUMS`
- `release-manifest.json`

Los cuatro assets descargados desde GitHub fueron comparados con los ficheros
locales mediante `cmp`:

```text
byte-perfect: PASS
```

El harness externo está `NOT_RUN`. No se promociona a estable.

## Limitación del planner SDDK

Se ejecutó el planificador:

```text
sddk release plan --root /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-restored \
  --scope . --route forge --repo Rubentxu/pipeline-kotlin --branch main \
  --tag v0.41.0-rc1 --previous-tag v0.40.0 --release-type minor
```

Resultado observado:

```text
VERSION LOCKSTEP ERROR: could not read .../pipeline-kotlin-restored/Cargo.toml
```

Es la limitación de tooling externo prevista en `AGENTS.md` §Release candidates
para repositorios Kotlin/Gradle. No se creó `Cargo.toml`, y se siguió la ruta
manual autorizada sin saltarse la certificación del harness.

## Deuda técnica y límites

La candidata incluye la deuda medida y no maquillada:

- baseline repo-wide tras el lote: `225` entradas;
- `pipeline-application`: `175` entradas;
- `pipeline-scripting-kotlin24`: `20`;
- `pipeline-events`: `14`;
- `pipeline-domain`: `4`;
- `pipeline-architecture-tests`: `3`.

La existencia de la candidata no certifica la deuda pendiente ni la promoción
estable. El siguiente trabajo debe continuar bajo el WorkItem SDDK activo y no
debe reinterpretar este receipt como un cierre del harness.

## Cierre

- Candidata: **publicada**.
- Release estable: **no**.
- Harness externo: **pendiente**.
- Bytes publicados: **verificados byte-perfect**.
- Main y tag: **integrados y apuntan al commit de build**.
