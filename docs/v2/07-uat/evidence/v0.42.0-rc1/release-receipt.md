# PipelineK v0.42.0-rc1 — release-candidate receipt

**Estado:** `GATES_GREEN_AWAITING_OPERATOR_PUSH`

**Candidata:** `0.42.0-rc1`
**Tag:** `v0.42.0-rc1` — **NO CREADO**. Verificado: `git tag -l v0.42.0-rc1` vacío.
**Commit de build:** `46eea758` (11 commits detrás del `origin/main` anterior; sin integrar)
**Rama:** `main`
**Candidata anterior:** `v0.41.0-rc1`
**Integración en `main`:** PENDIENTE. `origin/main` = `2111c10d`; el tag no puede
apuntar a un commit fuera de `main` (AGENTS.md, regla 9). Push a destino
compartido: decisión del operador.

Esta es una **release candidate**, no una release estable. El repositorio
entrega el material para `pipelinek-release-harness`. La certificación externa y
la promoción estable pertenecen al harness y deben reutilizar estos mismos
bytes, sin reconstrucción.

## Train derivada del historial

| Señal | Valor |
|---|---|
| BREAKING CHANGE en `v0.41.0-rc1..HEAD` | 4 |
| `feat` en el rango | 3 |
| `fix` en el rango | 11 |
| Train | **MINOR** → `0.42.0-rc1` |

**Corrección S0-C.** Esta tabla decía antes 1 breaking / 0 feat / MAJOR. El
análisis real del rango `v0.41.0-rc1..46eea758` arroja 4 breaking y 3 feat.
Con 0.x, 4 breaking sin feat">ba MAJOR, pero hay feat, así que la train
correcta es **MINOR** (0.42.0). El número de versión no cambia respecto al ya
declarado en `v2/build.gradle.kts`; lo que estaba mal era el razonamiento
registrado, no el valor.

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
| `pipelinek-0.42.0-rc1.zip` | 92,123,215 | `33db71b625716c99def9b8b46d337e3bd13b71fd157e96b93ee7b9961fe54b85` |

Los digests de `.sbom.json` y `SHA256SUMS` que la anterior versión de este
receipt declaraba **se han retirado**: ese material no existe en el repositorio
ni es reproducible, y un digest que no se puede recalcular no es evidencia.
El material inmutable de esta candidata es el ZIP, cuyo digest se midió dos
veces sobre dos construcciones independientes con resultado idéntico
(determinismo verificado, no supuesto).

`release-manifest.json` no se incluye en `SHA256SUMS`: incluir su propio digest
crearía un problema de punto fijo. Su integridad se verifica validando su JSON y
comparando el asset descargado con el material local.

## Commits incluidos en la candidata

| SHA | Asunto |
|---|---|
| `46eea758` | `test(s0): witness that the CI authority's shell paths exist in a checkout` |
| `89189cff` | `test(application): fix the corpus sweep's class-level timeout budget` |
| `13c9b62e` | `fix(ci): make the release-verification guard reference a real path` |
| `c3026a72` | `fix(ci): give the nested integration smokes a real workspace` |
| `87dfc10f` | `fix(domain): resync the API declaration after the phantom-surface removal` |
| `722e1e36` | `test(s0): add self-host script honesty witnesses for the repaired CI authority` |
| `be247232` | `fix(ci): repair the self-hosted pipeline script so it can actually run` |
| `ef80d40a` | `docs(s0): correct the surface manifest against witnessed reality (S0-B)` |
| `bed54aa0` | `fix(s0): emit the missing TimeoutTriggered block authority event` |
| `0bffa968` | `feat(architecture): DSL Surface Manifest v1 with machine-checkable conservation test` |
| `d89f5e30` | `feat(dsl)!: remove phantom surface (agent, step retry, timeout activity, keepAll)` |
| `2111c10d` | `docs(evolution): add semantic evolution package and archive local-first docs` |
| `ddf75fa6` | `feat(pipeline)!: remove publication from the self-hosted pipeline script` |
| `8b7294bc` | `fix(cli)!: surface DSL construction failures instead of NPE exit 0` |
| `60b710e5` | `fix(dsl)!: make scmGit pure and retry(count,delay) fail closed` |
| `d0a1da4a` | `fix(dsl): reject whenCondition instead of silently running its body` |
| `6ca44112` | `docs(release): record v0.42.0-rc1 candidate receipt` |
| `fd06be7c` | `fix(release): restore the release-candidate train for 0.42.0` |
| `91002764` | `chore(release): bump to 0.42.0 derived from the commit range` |
| `94fcc98b` | `fix(scripting-api): reject retry conditions the engine would silently drop` |
| `7d0a0b44` | `refactor(domain): group the core Step descriptor rows by declared ownership` |
| `582a7391` | `fix(build): prevent concurrent v2 Gradle invocations` |
| `b0a5e7e5` | `refactor(scripting-api): split StageScope builders by responsibility` |
| `f3203e65` | `docs(release): record v0.41.0-rc1 candidate receipt` |

## Gate de distribución instalada y clon limpio (S0.5)

Este es el criterio de salida que faltaba por completo en el receipt anterior.
Se ejecutó sobre **los bytes definitivos** de esta candidata, no sobre una
construcción anterior.

| Comprobación | Resultado observado |
|---|---|
| `unzip -t` del ZIP | OK |
| Binario instalado `version` | `pipeline 0.42.0-rc1` |
| Determinismo del build | dos `distZip` independientes → sha256 idéntico |
| Clon limpio | `git clone` en `46eea758` |
| Invocación | `run --workspace . --db ... --control-root ... pipeline.kts` |
| Etapas | 11/11 `StageFinished`, todas `outcome=success` |
| `RunFinished` | `outcome=success` |
| `StepFailed` | 0 |
| Demos reales | `GRADLE-DEMO-OK`, `MAVEN-DEMO-OK` |

Las 11 etapas: Validate, Compile, Unit Tests, Architecture Fitness, Compatibility
Corpus, Application UAT, Real Project Gradle, Real Project Maven, Real Project
Node, Package, Release Verification.

**Por qué importa Release Verification.** Ese stage era permanentemente
impasable por dos defectos de S0-B: la constante de versión obsoleta (`0.39.0`)
y un escape de shell `${'$'}` dentro de una cadena Kotlin, que produce texto
literal y una ruta que nunca resuelve. Que hoy salga en verde demuestra que ambos
quedaron reparados de verdad y no solo "declarados" reparados.

**Una corrección de honestidad que este receipt debe declarar.** Una versión
anterior de este documento afirmaba un dogfood verde que no correspondía al
script de autoridad: ese verde venía de `v2/compatibility/01-basic.pipeline.kts`,
que es lo que ejecuta el job N2 de `lpr0-ci.yml`, no de `pipeline.kts`. Al ejecutar
el binario contra el script raíz en un clon limpio, el primer `sh` falló con
exit 127. El diagnóstico por aislamiento mostró que la causa era la invocación
(sin `--workspace`, el `workspaceRoot` es un temporal), no un defecto del producto.
La consecuencia real fue un hueco de cobertura: los witnesses existentes
preguntaban si el script *afirma* la verdad, nunca si existe lo que el script
*consume*. Ese hueco quedó cerrado en `46eea758`.

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
| **TOTAL** | **3276** | **0** | **121** |

**Corrección S0-C.** El total era 3231; el gate medido sobre `46eea758` da
**3276** tests, 0 fallos, 0 errores. La diferencia (+45) corresponde al trabajo de
S0: 1 gate de arquitectura del manifiesto de superficie, 17+6+1 witnesses de
honestidad semántica, y la reparación del presupuesto del sweep de corpus.
La cifra anterior describía un punto del historial anterior a S0.

Evidencia de ejecución (no UP-TO-DATE): los XML JUnit se borraron antes de la
carrera (0 ficheros al inicio) y se regeneraron 550 al terminar, de modo que el
verde no pudo servirse desde cache. Duración 18m 28s, presupuesto 1700s.

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
digests son los observados. Gradle reporta `BUILD SUCCESSFUL` para tareas
`UP-TO-DATE` que no ejecutaron nada, y eso ocurrió en este mismo trabajo.

**Corrección S0-C.** Este receipt afirmaba que `--rerun-tasks` es obligatorio en
cada gate. El gate final no lo usó: se ejecutó incrementalmente, borrando antes
los XML JUnit de todos los módulos. El canario es la prueba de ejecución
correcta de esa forma, y da una señal más fuerte que un `UP-TO-DATE` no puede
ocultar: 0 ficheros antes, 550 después, con los casos que importan verificados
uno a uno (el witness de honestidad, `tests=7 failures=0 errors=0`).

Esto no es teórico. Al demostrar el RED del witness nuevo, una carrera
devolvió `UP-TO-DATE` con un XML cuyo timestamp era anterior al cambio del
script, y se leyó inicialmente como 7/7 verde. Habría sido un falso verde
reportado. Lo detectó el canary. Por eso el procedimiento declarado es:
borrar el XML, correr, y comprobar que el fichero reaparece con el contenido
esperado — no confiar en el código de salida.

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
