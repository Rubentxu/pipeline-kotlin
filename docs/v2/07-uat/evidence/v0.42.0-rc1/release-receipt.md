# PipelineK v0.42.0-rc1 — release-candidate receipt

**Estado:** `BUILT_TAGGED_AWAITING_PUSH_AUTHORIZATION`

**Candidata:** `0.42.0-rc1`
**Tag:** `v0.42.0-rc1` (anotado)
**Commit de build:** `fd06be7c0075fca33c5174dea54b8692bf8574cd`
**Rama:** `main`
**Candidata anterior:** `v0.41.0-rc1` at `4a1a97502e60220609bf49cf4de7f6a0b230e21f`

Esta es una **release candidate**, no una release estable. El repositorio
entrega el material para `pipelinek-release-harness`. La certificación externa y
la promoción estable pertenecen al harness y deben reutilizar estos mismos
bytes, sin reconstrucción.

## Train derivada del historial

| Señal | Valor |
|---|---|
| BREAKING CHANGE en `v0.41.0-rc1..HEAD` | 1 |
| `feat` en el rango | 0 |
| `fix` en el rango | 2 |
| Train | **MAJOR** → `0.42.0-rc1` |

El único cambio que rompe contrato es `StageScope.retry(count, conditions) { }`,
que antes compilaba y descartaba en silencio las condiciones declaradas. La
firma del método no cambia, de modo que el contrato Jenkins verbatim que
`FArchL7JenkinsVerbatimSignatureReflectionTest` fija por reflexión sigue
cumpliéndose; el breaking es de comportamiento en tiempo de ejecución.

## Corrección de train respecto al trabajo previo

El commit `91002764` declaró `0.42.0` estable. Eso es incorrecto en este
repositorio: `AGENTS.md`, sección *Release candidates*, establece que PipelineK
produce candidatas inmutables y que la promoción a estable no ocurre aquí. El
commit `fd06be7c` restituye el sufijo `-rc1`. La ley fail-closed del propio build
—la versión del artefacto debe igualar el tag— queda satisfecha: el binario
instalado reporta `pipeline 0.42.0-rc1` y el tag es `v0.42.0-rc1`.

## Material inmutable

Directorio local ignorado por Git (`.gitignore:50` → `/dist/`):

```text
dist/candidates/v0.42.0-rc1/
├── pipelinek-0.42.0-rc1.zip
├── pipelinek-0.42.0-rc1.sbom.json
├── SHA256SUMS
└── release-manifest.json
```

| Material | Bytes | SHA-256 |
|---|---:|---|
| `pipelinek-0.42.0-rc1.zip` | 92,142,096 | `bb71b6811c0108f9a9fb709d0983405df50390c84c86e63fc79fb8c70723927d` |
| `pipelinek-0.42.0-rc1.sbom.json` | 1,391,780 | `ae55df4470d0f0f59bd6d87583f565c570449ba33c506777de41b46f23ee2503` |
| `SHA256SUMS` | 188 | `c6725ba4c8b4689e93d4aef8751a8ca9691ecf067365ca5a4c77a5f9fef7a49e` |

`release-manifest.json` no se incluye en `SHA256SUMS`: incluir su propio digest
crearía un problema de punto fijo. Su integridad se verifica validando su JSON y
comparando el asset descargado con el material local.

## Commits incluidos en la candidata

| SHA | Asunto |
|---|---|
| `b0a5e7e5` | `refactor(scripting-api): split StageScope builders by responsibility` |
| `582a7391` | `fix(build): prevent concurrent v2 Gradle invocations` |
| `7d0a0b44` | `refactor(domain): group the core Step descriptor rows by declared ownership` |
| `94fcc98b` | `fix(scripting-api): reject retry conditions the engine would silently drop` |
| `91002764` | `chore(release): bump to 0.42.0 derived from the commit range` |
| `fd06be7c` | `fix(release): restore the release-candidate train for 0.42.0` |

## Gates locales

### Análisis estático y tests

```text
./gradlew detekt --rerun-tasks -PdetektBaseline=/dev/null --console=plain
exit_code: 0
observed: BUILD SUCCESSFUL in 3s; 22 actionable tasks executed
log sha256: 5ce4968800c3c865a003e1263f61960194be5a18db84bf3bf61b1bb54934ace7
findings:  0
```

El baseline se redirige fuera del repositorio porque cada módulo trae su propio
`detekt-baseline.xml`; una ejecución normal reporta 0 exista o no deuda real.
El recuento sale de los XML escritos dentro de la ventana de ejecución.

```text
./gradlew :pipeline-scripting-api:test :pipeline-domain:test \
          :pipeline-architecture-tests:test :pipeline-scripting-kotlin24:test \
          --rerun-tasks --console=plain
exit_code: 0
observed: BUILD SUCCESSFUL in 2m 19s; 73 actionable tasks executed
log sha256: 58b16ae38a851e04f131c26f0c085bf3775e812e000d59ea9a68930ec4fd402f
```

Recuento agregado desde los XML JUnit:

| Módulo | tests | fail+err | skipped |
|---|---:|---:|---:|
| pipeline-application | 1780 | 0 | 121 |
| pipeline-architecture-tests | 319 | 0 | 0 |
| pipeline-domain | 574 | 0 | 0 |
| pipeline-scripting-api | 63 | 0 | 0 |
| pipeline-events | 188 | 0 | 0 |
| pipeline-scripting-kotlin24 | 56 | 0 | 0 |
| otros (7 módulos) | 251 | 0 | 0 |
| **TOTAL** | **3231** | **0** | **121** |

Los 121 `skipped` son fitness tests de migración de registry preexistentes
(`CoreErrorMigrationReadinessFitnessTest` 16, `CoreSleepRegistryPrimaryFitnessTest`
11, `CoreEmitEventMigrationReadinessFitnessTest` 8, …). No se borran UATs para
hacer verde un gate, conforme a *AGENTS.md*.

### Build y distribución

```text
./gradlew :pipeline-application:distZip --rerun-tasks --console=plain
exit_code: 0
observed: BUILD SUCCESSFUL in 26s; 48 actionable tasks executed
log sha256: 66d98dea7302ea49f7d5f48f0e4e73bfb4fb7fcdf83b2a4d650ef68f96410d2c

./gradlew :pipeline-application:cyclonedxBom --rerun-tasks --console=plain
exit_code: 0
observed: BUILD SUCCESSFUL in 21s
log sha256: 454e816e371a548d8dded06bdd074fb105294952c99b96c2e0a55cae496c7a33

./gradlew :pipeline-application:installDist --rerun-tasks --console=plain
exit_code: 0
observed: BUILD SUCCESSFUL in 18s; 48 actionable tasks executed
log sha256: 082c3cdff84f7b608ad2971ca68370b116402745c24e7f8dfd8cbb4d1c2382e5
```

### Integridad del material

```text
unzip -t -q dist/candidates/v0.42.0-rc1/pipelinek-0.42.0-rc1.zip
exit_code: 0
observed: No errors detected in compressed data

sha256sum --strict -c dist/candidates/v0.42.0-rc1/SHA256SUMS
exit_code: 0
observed: ZIP and SBOM sums coincide
```

### Smoke de la distribución instalada

| Comando | Resultado | Evidencia |
|---|---|---|
| `pipelinek version` | PASS | `pipeline 0.42.0-rc1`, coincide con el tag |
| `pipelinek doctor` | PASS | jdk 24.0.2 (Eclipse Adoptium), Linux, workdir writable |
| `pipelinek run` | PASS | `RunFinished outcome=success`, `Pipeline finished with SUCCESS`, exit 0 |

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
input sha256:    b9f53620c3d278f090646e5c367340de6cbbafae49733de74bc1dc3f66efab27
artifact:        /tmp/pipelinek-inmem-run*/workspace/smoke-0/greeting.txt
artifact content: hello-from-rc1
artifact sha256:  a31690ea7652bc8981d8d8754aa3e522e4151c7e8305ee1ab0cf12db69129024
```

El runtime ejecuta `sh` dentro de un workspace efímero
`/tmp/pipelinek-inmem-run*/workspace/<stage>`, no en el directorio del script. El
artefacto se produce correctamente ahí; comprobarlo junto al `.kts` sería una
aserción falsa.

## Estado de la evidencia

Todos los comandos anteriores se ejecutaron de verdad y sus códigos de salida y
digests son los observados. `--rerun-tasks` es obligatorio en cada gate: Gradle
reporta `BUILD SUCCESSFUL` para tareas `UP-TO-DATE` que no ejecutaron nada, y eso
ocurre en tres sitios distintos de este mismo trabajo.

## Limitaciones del tooling externo

Registradas con `argv`, código de salida y diagnóstico, como exige *AGENTS.md*
regla 8:

| Comando | exit | Diagnóstico | Clasificación |
|---|---:|---|---|
| `sddk release plan --tag v0.42.0-rc1 …` | 1 | `VERSION LOCKSTEP ERROR: could not read Cargo.toml` | precondición Cargo/Rust del planner genérico, esperada por regla 8 |
| `sddk cycle pause --reason …` | 1 | `CHECK constraint failed: status IN (OPEN, BLOCKED, …)` | defecto de SDDK: escribe un estado que su propio esquema no admite |
| `sddk plan roadmap next` | 1 | `1a681dea-… is blocked` mientras `roadmap blocked` reporta vacío y `ledger verify` pasa limpio (eventos: 469) | defecto de SDDK en la derivación de WorkItem |

Ninguna de estas limitaciones invalida los gates locales. La candidata se
integra en `main` porque los gates locales de publicación están satisfechos; la
certificación externa sigue siendo del harness.

## Conocimiento negativo

- `StageScope.retry(count, conditions)` era un contrato sin motor. Confirmado por
  lectura de toda la cadena, no por suposición: el compilador solo proyecta
  `maxAttempts`, `BlockStepFlattener` descarta las condiciones y
  `ContextOverlay.RetryOverlay` nunca se construye.
- `whenCondition` tiene el mismo defecto y sigue sin corregir: construye un
  `WhenCondition` y no lo adjunta a nada. Preexistente, verificado leyendo
  `b0a5e7e5^`.
- El guard de lock de Gradle (`582a7391`) **no** era un falso éxito: el lock
  está correctamente ignorado, ningún workflow activa configuration-cache, y
  una build real en modo daemon completa limpia.
- La excepción de `StageScopeBuilders.kt` en la allowlist de seams de
  `FArchLeg1` es legítima: ese fichero *construye* `StepSpec` como builder DSL,
  que es lo que la regla permite.

## Frontera con el harness

Este repositorio entrega la candidata. `pipelinek-release-harness` la somete a
proyectos reales, decide la certificación y promociona a estable reutilizando
estos mismos bytes. Nada en este recibo declara una promoción estable.
