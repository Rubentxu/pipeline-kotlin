# PipelineK — DSL de pipelines

> **Divergencia documental.** Esta página afirmaba antes el contrato de `0.39.0` y afirmaba que
> `parallel` / `retry` / `catchError` no existían. Esa afirmación no se sostenía contra el código: sí
> están declarados en el DSL de v2 y los ejercita `examples/run.sh`. La página documenta ahora la rama
> de desarrollo (`0.47.0`). Registrado el 2026-10-06. Ver `docs/user/README.es.md` → «Divergencias conocidas».

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**No verificado contra un binario publicado.** La release publicada actual es la `0.47.0` (el digest de su ZIP está en el `SHA256SUMS` de la release); ver la nota de divergencia abajo.

## Qué podrás hacer al terminar

- Escribir un fichero `.pipeline.kts` que el motor acepte de verdad, usando la
  forma real `pipeline { stages { stage("…") { … } } }`.
- Saber, antes de escribir una línea, si un constructo está **probado**, si sólo
  está **declarado** o si **falla cerrado** — y nunca afirmar que uno declarado
  funciona.
- Evitar los cuatro constructos que compilan y luego `run` rechaza con exit `2`,
  y la forma de `retry` que lanza en el punto de la llamada.
- Leer el catálogo sabiendo de dónde sale la evidencia de cada constructo.

## Palabras que vas a necesitar

| Término | Imagen cotidiana | Significado en PipelineK |
|---|---|---|
| **stage** | una habitación de una casa que recorres en orden | una unidad de trabajo con nombre; los steps de dentro se ejecutan en orden |
| **step** | una instrucción de una lista de comprobación | la unidad más pequeña que produce eventos |
| **workspace** | la caja de herramientas, la carpeta donde se trabaja | el directorio de trabajo en el que se ejecutan los steps |
| **journal** | el cuaderno del operario, donde queda escrito el log | el historial SQLite durable; es a lo que apunta `--db` |
| **event** | una línea escrita en el cuaderno | un registro tipado que otro proceso puede leer |
| **block step** | una caja que contiene instrucciones más pequeñas | un step cuyo cuerpo es a su vez una secuencia de steps |

El DSL es un DSL de Kotlin dentro de un fichero `.kts` normal: tienes todo el
lenguaje Kotlin a nivel de fichero, y el DSL sólo gobierna el cuerpo de
`pipeline { … }`.

## Los tres estados — léelo antes del catálogo

Cada constructo de esta página lleva exactamente una de estas etiquetas. No
te saltes la tabla: es la diferencia entre una página honesta y una página que
promete cosas.

| Etiqueta | Qué significa | Qué puedes afirmar |
|---|---|---|
| **Probado por los ejemplos** | `examples/run.sh` lo ejecuta **y lo comprueba**: exit code, outcome terminal y (para 07–10) contrato de eventos | «esto funciona» |
| **Declarado, sin ejemplo de referencia** | Existe en el código; **ningún ejemplo de este repositorio lo usa** | «esto está declarado», y nada más fuerte |
| **Falla cerrado** | Compila, y `run` lo rechaza con exit `2` (o lanza en la llamada) | «no uses esto» |

> La etiqueta del medio no es una forma amable de decir «sin probar». Significa
> exactamente lo que dice: **ningún ejemplo lo ejercita, y por tanto esta
> página no afirma que funcione.**

## Estructura mínima

```kotlin
pipeline {
    stages {
        stage("build") {
            sh("./gradlew --no-daemon build")
            sh("test -f build/libs/*.jar")
            echo("GRADLE-DEMO-OK")
        }
    }
}
```

Los steps de dentro de un `stage` se ejecutan **en orden**. El siguiente sólo
se ejecuta si el anterior terminó con éxito. Un step que falla hace fallar el
stage y el run, y el CLI sale con `1`.

Un ejemplo trabajado y comprobado de exactamente esta forma es
`examples/01-hello.pipeline.kts`.

## Nivel 1 — Probado por los ejemplos

 Estos son los constructos que `examples/run.sh` ejecuta y comprueba de verdad.

| Constructo | Ejemplo | Exit CLI | Outcome terminal |
|---|---|---|---|
| `pipeline { }` / `stages { }` / `stage("x") { }` | 01, 02 | `0` | `success` |
| `echo(text)` | todos | `0` | `success` |
| `sh(command)` | 03, 05 | `0` / `1` | `success` / `failure` |
| `script { }` | 04 | `0` | `success` |
| `catchError(buildResult, stageResult, message) { }` | 07 | `0` | **`unstable`** |
| `parallel { branch("x") { } }` | 08 | `0` | `success` |
| `retry(count) { }` — la forma **de bloque** | 09 | `0` | `success` |
| `timeout(time, unit) { }` — forma de bloque | 10 | `1` | `failure` |
| Journal durable `--db` y reutilización | 06, 08, 09 | `0` | `success` |

### El catálogo de ejemplos

| Fichero | Qué muestra | Exit CLI | Outcome terminal |
|---|---|---|---|
| `01-hello.pipeline.kts` | Pipeline mínimo: un stage, un `echo` | `0` | `success` |
| `02-multi-stage.pipeline.kts` | Tres stages, en orden | `0` | `success` |
| `03-shell.pipeline.kts` | Procesos reales del SO con `sh` | `0` | `success` |
| `04-kotlin-control-flow.pipeline.kts` | `script { }` con control de flujo | `0` | `success` |
| `05-failing-step.pipeline.kts` | `sh("exit 3")`; el stage posterior **no** se ejecuta | **`1`** | `failure` |
| `06-durable.pipeline.kts` | `--db` dos veces: la segunda reutiliza | `0` (×2) | `success` |
| `07-catch-error.pipeline.kts` | `catchError` anidado: FAILURE→UNSTABLE | `0` | **`unstable`** |
| `08-parallel.pipeline.kts` | `parallel` + `branch`; la 2ª ejecución reutiliza | `0` (×2) | `success` |
| `09-retry.pipeline.kts` | `retry(3) { }`: falla una vez y luego tiene éxito | `0` (×2) | `success` |
| `10-timeout.pipeline.kts` | `timeout(2,"SECONDS")` sobre `sleep 30` | **`1`** | `failure` |

### Lo que `run.sh` comprueba de verdad

Ejecutar el ejemplo no basta: el harness comprueba el comportamiento
observable, así que son afirmaciones de comportamiento, no decoración:

| Ejemplo | Comprobación |
|---|---|
| `05` | Un stage posterior **no debe** ejecutarse. |
| `07` | Exactamente `1` `CatchErrorTriggered(FAILURE)` y luego `1` `CatchErrorTriggered(UNSTABLE)`, **primero el más interno**, y el `echo` posterior a los scopes sigue ejecutándose. |
| `08` | La **segunda** ejecución con el mismo `--db` produce `0` `ParallelBranchStarted` y `0` `StepStarted` — reutiliza, no relanza. |
| `09` | Exactamente `2` `RetryAttemptFinished`, `failed` y luego `succeeded`. |
| `10` | Al menos `1` `TimeoutScheduled`, más un `StepFailed` cuyo mensaje contiene "timed out". |

`catchError` en acción (ejemplo 07):

```kotlin
pipeline {
    stages {
        stage("catch-demo") {
            catchError(buildResult = "UNSTABLE", stageResult = "UNSTABLE") {
                catchError(buildResult = "FAILURE", stageResult = "FAILURE") {
                    sh("echo inner-body-failing")
                    sh("exit 1")
                }
            }
            echo("continues after nested catch")
        }
    }
}
```

`parallel` y `branch` (ejemplo 08):

```kotlin
stage("parallel-demo") {
    parallel {
        branch("left") {
            echo("left branch")
            sh("echo left-work")
        }
        branch("right") {
            echo("right branch")
            sh("echo right-work")
        }
    }
}
```

## Las dos formas de `retry` — léelo dos veces

Hay **dos** sobrecargas de `retry` en el DSL, y se comportan de forma
distinta. Confundirlas es el error más frecuente en esta página.

| Forma | Qué ocurre | Etiqueta |
|---|---|---|
| `retry(count = 3) { … }` — la forma **de bloque** | Registra el block step `core.retry` y lo honra el coordinador. El ejemplo 09 la ejecuta. | **Probado por los ejemplos** |
| `retry(count = 3, delaySeconds = 5)` — la forma **de step**, sin bloque | **Lanza `IllegalArgumentException` en el punto de la llamada**, antes de registrar ningún step (`StageScopeBuilders.kt:306`) | **Falla cerrado** |

Por qué existen las dos: la sobrecarga de step-level se eliminó porque la
política de reintentos proyectada **no tenía consumidor en runtime** — la
ruta compilada sólo lee el `maxAttempts` del block step `core.retry`, así que
`delaySeconds` se aceptaba y luego nunca se ejecutaba. Se conservó como una
firma que lanza explícitamente, para que quien migre desde ella reciba este
diagnóstico en lugar de un error de firma de Kotlin a secas:

```text
retry(count = N) at step level was removed: the projected retry policy had no
runtime consumer … Use the block form retry(N) { ... }.
```

**Cuál usar: siempre `retry(count) { … }`.**

## Nivel 2 — Declarado, sin ejemplo de referencia

Todo lo que aparece en esta sección está declarado en el DSL de v2. **Ningún
ejemplo de este repositorio lo usa.** Esta página no afirma nada sobre si
funciona; trátalo como «declarado» y verifícalo tú mismo antes de depender de
ello.

`withEnv`, `dir`, `withCredentials`, `stash` / `unstash`, `archiveArtifacts`,
`artifactQuery`, `deleteDir`, `cleanWs`, `writeFile`, `readFile`, `fileExists`,
`error`, `sleep`, `warnError`, `unstable`, `pwd`, `isUnix`, `waitUntil`,
`milestone`, `publishHTML`, `lock`, `input`, `timestamps`, `post`,
`environment { }`, `options { timeout }`, `agent` / `agentAny` /
`agentWithCapabilities`, `whenGate` / `whenEnvIs` / `whenEnvPresent`,
`checkout` / `scmGit` / `git`, `registryStep` / `registryBlock`, y todos los
builders de plugin (`httpRequest`, `junitResults`, `scmGitCheckout`,
`core-utils.*`).

### Firmas de los principales

| Constructo | Firma | Línea |
|---|---|---|
| `echo` | `echo(text: String)` | `StageScopeBuilders.kt:110` |
| `sh` | `sh(command: String)` | `StageScopeBuilders.kt:114` |
| `sh` | `sh(script: String, isScriptBlock: Boolean = false, returnStdout: Boolean = false)` | `StageScopeBuilders.kt:118` |
| `error` | `error(message: String, failureKind: FailureKind = FailureKind.USER)` | `StageScopeBuilders.kt:128` |
| `sleep` | `sleep(seconds: Long)` | `StageScopeBuilders.kt:132` |
| `writeFile` | `writeFile(file, text, encoding = "UTF-8")` | `StageScopeBuilders.kt:312` |
| `readFile` | `readFile(file, encoding = "UTF-8")` | `StageScopeBuilders.kt:335` |
| `fileExists` | `fileExists(file)` | `StageScopeBuilders.kt:339` |
| `archiveArtifacts` | `archiveArtifacts(artifacts, allowEmptyArchive = false, excludes = "", fingerprint = false, name = null)` | `StageScopeBuilders.kt:357` |
| `deleteDir` | `deleteDir(path = ".")` | `StageScope.kt:69` |
| `cleanWs` | `cleanWs(deleteDirs = true, patterns: List<String>? = null)` y `cleanWs(deleteDirs = true, vararg patterns: String)` | `StageScope.kt:90,118` |
| `catchError` | `catchError(buildResult: String? = null, stageResult: String? = null, message: String? = null, block)` | `StageScope.kt:141` |
| `warnError` | `warnError(message, catchInterruptions = true, block)` | `StageScope.kt:171` |
| `unstable` | `unstable(message: String)` | `StageScope.kt:197` |
| `waitUntil` | `waitUntil(initialRecurrencePeriod = 1L, quiet = false, body)` | `StageScope.kt:391` |
| `timestamps` | `timestamps(block)` | `StageScope.kt:421` |
| `milestone` | `milestone(ordinal: Int, label: String? = null)` | `StageScope.kt:473` |
| `stash` | `stash(name, includes, excludes = "")` | `StageScope.kt:515` |
| `unstash` | `unstash(name, into = null)` | `StageScope.kt:552` |
| `publishHTML` | `publishHTML(name, reportDir, reportFiles = "**", keepAll = false, …)` | `StageScope.kt:599` |
| `timeout` | `timeout(time: Long, unit: String, block)` | `StageScope.kt:638` |
| `retry` | `retry(count: Int, block)` | `StageScope.kt:652` |
| `dir` | `dir(path: String, block)` | `StageScopeBuilders.kt:386` |
| `lock` | `lock(resource, timeoutSeconds = null, reason = null, skipIfLocked = false, block)` | `StageScopeBuilders.kt:409` |
| `input` | `input(message, ok = "Proceed", submitter = null, id = null, timeoutSeconds = null, block)` | `StageScopeBuilders.kt:446` |
| `withEnv` | `withEnv(overrides, block)` | `StageScopeBuilders.kt:343` |
| `withCredentials` | `withCredentials(vararg bindings, block)` | `StageScopeBuilders.kt:240` |
| `script` | `script(block: ScriptScope.() -> Unit)` — **DEPRECATED**, aplana a un `StepSpec.Shell` | `StageScopeBuilders.kt:269` |
| `post` | `post(block: PostScope.() -> Unit)` | `StageScopeBuilders.kt:222` |
| `environment` | `environment(block: EnvironmentScope.() -> Unit)` | `StageScopeBuilders.kt:206` |
| `options` | `options(block)` — sólo `timeout(seconds)` | `StageScopeBuilders.kt:214` |
| `scmGit` | `scmGit(url, branch = "master", credentialsId = null, changelog = true, poll = true, relativeTargetDir = ".")` — **cero efectos**, devuelve un carrier | `StageScopeBuilders.kt:175` |

Dos entradas merecen aviso propio:

- `script { }` está **DEPRECATED**: aplana a un `StepSpec.Shell`
  (`StageScopeBuilders.kt:269`). El ejemplo 04 todavía lo usa y todavía pasa,
  y por eso está en el Nivel 1 — pero se va a retirada.
- `scmGit(...)` es un **pure builder**: no hace E/S, no emite ningún step ni
  evento, y devuelve un carrier `CheckoutSpec` (`StageScopeBuilders.kt:175`).
  Quien hace el trabajo es `checkout(...)`. Un builder que ejecutara el
  efecto sería un step con el nombre de un builder.

### No todos los scopes ofrecen todo

Un scope es un conjunto restringido de movimientos. Llamar a un constructo
desde el scope equivocado no compila.

| Scope | Qué expone | Línea |
|---|---|---|
| `ScriptScope` | `line`, `echo`, `sh`, `error` | `StageScope.kt:421,427,437,441,445` |
| `BranchScope` | `echo`, `sh`, `error`, `sleep`, `dir`, `lock` | `StageScope.kt:355-396` |
| `PostStepsScope` | `echo`, `sh`, `error`, `sleep` | `StageScope.kt:315-330` |

Es decir, dentro de un `branch { … }` puedes llamar a `echo`, `sh`, `error`,
`sleep`, `dir` y `lock` — y a nada más de esta página.

## Nivel 3 — Falla cerrado

Cuatro constructos **compilan y luego `run` los rechaza con exit `2`**, porque
no tienen descriptor ni registro válido. No están «a medio hacer»: se
rechazan, y rechazar es el contrato.

| Constructo | Por qué se rechaza | Dónde |
|---|---|---|
| `git(url, branch = "master", …)` | Delega en `checkout(scmGit(...))` → clave `core.checkout`, que **nadie registra** | `StageScopeBuilders.kt:193` |
| `load(path)` | Emite `core.load`, sin descriptor | `StageScope.kt:349` |
| `node(label) { }` | Bloque sin descriptor | `StageScope.kt:449` |
| `ansiColor(colorMapName) { }` | Bloque sin descriptor | `StageScope.kt:435` |

El gate que los rechaza está en `Main.kt:423` (en memoria) y `Main.kt:827`
(durable), y la elegibilidad se calcula en `CanonicalStructuralDecisions.kt:154`.
El mensaje de error enumera 11 claves fijas, pero **la regla real es el
registro efectivo**, no esa lista: no la memorices como si fuera el contrato.

Y un quinto caso, distinto en naturaleza: el `retry(count, delaySeconds)` de
step-level lanza en el momento de la llamada. Ver
[Las dos formas de `retry`](#las-dos-formas-de-retry--léelo-dos-veces).

## Lo que no existe

No lo busques. No está oculto, no es experimental, no es «próximamente»: no
existe.

- `when { }` — no es un constructo de este DSL. Usa `catchError` / `whenGate`,
  no un bloque `when`.
- `parameters { }` — no existe.
- `properties { }` — no existe.
- `triggers { }` — no existe.
- `agent { }` como **bloque** — no existe. (Los **callables** `agent`,
  `agentAny` y `agentWithCapabilities` sí están declarados; mira el Nivel 2.
  Eso es otra cosa que un bloque `agent { }`.)
- `options { }` con algo más que `timeout` — no existe.

## Referencia del registro de Steps

El motor resuelve cada constructo a través de un registro abierto. Registrado
hoy:

| Familia | Claves |
|---|---|
| Handlers core (22) | `core.echo`, `core.sh`, `core.error`, `core.sleep`, `core.file.writeFile`, `core.readFile`, `core.fileExists`, `core.archiveArtifacts`, `core.artifact.query`, `core.emit.event`, `core.isUnix`, `core.pwd`, `core.pwd.tmp`, `core.deleteDir`, `core.cleanWs`, `core.milestone`, `core.waitUntil`, `core.stash`, `core.unstash`, `core.publishHTML`, `core.lock`, `core.input` (`CoreStepRegistryFactory.kt:25-199`) |
| Descriptores de bloque (exactamente 9) | `core.catchError`, `core.warnError`, `core.withEnv`, `core.dir`, `core.withCredentials`, `core.timeout`, `core.timestamps`, `core.retry`, `core.waitUntil` (`StepDescriptorRegistry.standard()`: `:102,122,147,160,173,186,201,219,239`) |
| Plugin, vía `ServiceLoader` | `http.request`, `junit.results`, `scm-git.checkout`, `core-utils.*` (`readJson`, `writeJson`, `sha256`, `readYaml`, `writeYaml`, `findFiles`, `zip`, `unzip`) |

Leer esta tabla te da algo útil: `catchError`, `timeout` y `retry` son
**descriptores de bloque**, y por eso llevan cuerpo; `echo` y `sh` son
**handlers**, y por eso llevan argumentos.

## Checklist antes de ejecutar

- [ ] Mi fichero usa `pipeline { stages { stage("…") { … } } }` y nada más arriba.
- [ ] Cada constructo que uso está en el Nivel 1, o asumo que me apoyo en uno del Nivel 2 sin probar.
- [ ] No estoy usando `git(...)`, `load(...)`, `node { }` ni `ansiColor { }`.
- [ ] Mi `retry` tiene bloque.
- [ ] No estoy buscando `when { }`, `parameters { }` ni `agent { }`.
- [ ] Lo he ejecutado con `run`, no sólo con `validate` — `validate` no es un ensayo.

## Nota de autoridad

Este repositorio **no tiene CI remota desde 2026-09-30** (`.github/workflows/`
no existe; el commit `754ddda0` eliminó los workflows de CI). Por eso esta página nunca promete
«CI verde» ni dice «listo para producción»: el PRODUCT-GATE está
`BLOCKED_EXTERNAL`. Lo que sí te da son citas al código y la evidencia de
comportamiento que `examples/run.sh` comprueba.

## Siguiente

- [`cli-reference.es.md`](cli-reference.es.md) — todos los subcomandos, todos los
  flags, las trampas del parser y la tabla de exit codes.
- [`README.es.md`](README.es.md) — el hub de toda la documentación de usuario.

_Esta página describe la rama de desarrollo. La release publicada actual es la `0.47.0`; la
diferencia está registrada en la nota de divergencia de arriba._