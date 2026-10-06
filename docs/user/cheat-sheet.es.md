# PipelineK — Chuleta

**Documentado contra**: rama de desarrollo, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**No verificado contra un binario publicado.** La release publicada actual es la `0.47.0` (el digest de su ZIP está en el `SHA256SUMS` de la release); ver la nota de divergencia abajo.

> **Divergencia de documentación.** Esta página afirmaba antes el contrato de `0.39.0` y decía que
> `parallel` / `retry` / `catchError` no existían. Esa afirmación no se sostenía contra el código:
> están declarados en el DSL de v2 y los ejercita `examples/run.sh`. La página documenta ahora la
> rama de desarrollo (`0.47.0`). Registrado el 2026-10-06. Ver `docs/user/README.es.md` → "Divergencias conocidas".

> **Estado.** Este repositorio no tiene CI remota desde el 2026-09-30. El PRODUCT-GATE está
> `BLOCKED_EXTERNAL`; nada de esto es una afirmación de que el producto esté listo para producción.

Referencia para copiar y pegar. Los datos salen de leer el código en `b08fa948`; los exit code de los
ejemplos son los que `examples/run.sh` comprueba.

## Subcomandos

| Comando | Qué hace | Fuente |
|---|---|---|
| `version` | imprime `pipeline <versión>` desde el manifiesto del jar | `Main.kt:72` |
| `doctor` | jdk / os / escribibilidad del directorio de trabajo | `Main.kt:91` |
| `events` | historial estructurado del journal | `Main.kt:115` |
| `events verify` | comprueba el historial persistido contra un contrato YAML. **No** re-ejecuta | `Main.kt:116` |
| `console` | transcripción de salida de un `opId` | `Main.kt:133` |
| `credentials` | `add` \| `list` \| `remove` | `Main.kt:140`, `MainCredentialsCli.kt:94` |
| `validate` | compila e imprime diagnósticos. **No ejecuta** | `Main.kt:190` |
| `run` | ejecuta el pipeline | `Main.kt:232` |

Sólo `validate` y `run` pasan por el parser de argumentos (`CliParser.kt:135`).

## Flags — sólo `run` y `validate`

| Flag | Argumento | Efecto | Fuente |
|---|---|---|---|
| `--db` | ruta | journal SQLite. Sin él todo está en memoria | `CliParser.kt:192` |
| `--resume` | — | reanuda una ejecución previa. Exige `--db` | `CliParser.kt:196`, `Main.kt:239` |
| `--rerun` | — | fuerza una ejecución nueva. Exige `--db` | `CliParser.kt:202` |
| `--control-root` | ruta | raíz de control del shell durable | `CliParser.kt:208` |
| `--workspace` | ruta | directorio de trabajo | `CliParser.kt:212` |
| `--isolated` | — | workspace scratch gestionado | `CliParser.kt:216` |
| `--plugin-jar` | ruta, repetible | JARs de plugin | `CliParser.kt:220` |
| `--allow-network` | — | permite egress. Por defecto **denegado** | `CliParser.kt:229` |
| `--sandbox-profile` | `none` \| `local` \| `os` | `os` se rechaza | `CliParser.kt:233` |

**Los flags van antes de la ruta del script.** `run script.kts --db x` ignora `--db x` en silencio
(`CliParser.kt:144`, `CliParser.kt:151`).

## Exit codes

| Code | Significado |
|---|---|
| `0` | éxito, incluido `RunOutcome.Unstable` |
| `1` | `Failure` / `Aborted` del pipeline, **y argumentos CLI inválidos** (`Main.kt:151`) |
| `2` | invocación / admisión: script no encontrado, `validate` fallido, `--resume`/`--rerun` sin `--db`, `--control-root` inválido, Step no canónico, lease ya poseído, compilación fallida, `doctor` no escribible |
| `3` | artefacto sin `Implementation-Version`; credenciales sin passphrase o con passphrase incorrecta |
| `4` | almacén de credenciales adulterado |

Los argumentos CLI inválidos son `1`; el resto de rechazos de entrada son `2`. No los unifiques
(`Main.kt:151` frente a `Main.kt:186`, `:225`, `:241`, `:269`, `:430`, `:830`).

## Comandos para copiar y pegar

```bash
# Ejecutar
pipelinek run --workspace . pipeline.kts
pipelinek run --db ./.pipelinek/run.sqlite --control-root ./.pipelinek/ctl pipeline.kts

# Durable: la segunda ejecución con el mismo --db reutiliza lo ya completado
pipelinek run --db ./.pipelinek/run.sqlite --control-root ./.pipelinek/ctl --rerun pipeline.kts
pipelinek run --db ./.pipelinek/run.sqlite --control-root ./.pipelinek/ctl --resume pipeline.kts

# Sólo compilar (NO ejecuta los steps, NO ejecuta el puente canónico)
pipelinek validate pipeline.kts

# Eventos
RUN_ID=$(pipelinek run --db ./run.sqlite pipeline.kts | jq -r '.[0].runId')
pipelinek events --db ./run.sqlite "$RUN_ID"
pipelinek events --db ./run.sqlite "$RUN_ID" --kind StepFinished --limit 20
pipelinek events verify --db ./run.sqlite --run "$RUN_ID" --contract c.yaml [--scope last-segment]

# Transcripción de consola
pipelinek console --control-dir ./.pipelinek/ctl "$RUN_ID" "$OP_ID" --max-bytes 65536
```

## Ejemplos — exit codes esperados

`examples/run.sh` comprueba todas las filas. "×2" significa que el ejemplo se ejecuta dos veces con
el mismo `--db` y la segunda debe reutilizar, no re-ejecutar.

| Fichero | Qué muestra | Exit | Outcome |
|---|---|---|---|
| `01-hello.pipeline.kts` | pipeline mínimo: un stage, un `echo` | `0` | success |
| `02-multi-stage.pipeline.kts` | tres stages en orden | `0` | success |
| `03-shell.pipeline.kts` | procesos reales del SO con `sh` | `0` | success |
| `04-kotlin-control-flow.pipeline.kts` | `script { }` con control de flujo | `0` | success |
| `05-failing-step.pipeline.kts` | `sh("exit 3")`; el stage siguiente **no** corre | `1` | failure |
| `06-durable.pipeline.kts` | `--db` dos veces: la segunda reutiliza | `0` ×2 | success |
| `07-catch-error.pipeline.kts` | `catchError` anidado: FAILURE → UNSTABLE | `0` | **unstable** |
| `08-parallel.pipeline.kts` | `parallel` + `branch`; la 2ª reutiliza | `0` ×2 | success |
| `09-retry.pipeline.kts` | `retry(3) { }`: falla una vez y luego tiene éxito | `0` ×2 | success |
| `10-timeout.pipeline.kts` | `timeout(2, "SECONDS")` sobre `sleep 30` | `1` | failure |

```bash
examples/run.sh                          # todos los ejemplos
examples/run.sh 01-hello.pipeline.kts    # un ejemplo
```

## Pipeline mínimo

```kotlin
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

Falla la ejecución a propósito (el CLI sale con `1`):

```kotlin
pipeline {
    stages {
        stage("fail") { sh("exit 3") }
    }
}
```

## Lo que el DSL hace de verdad

| Estado | Constructos |
|---|---|
| **Probado por los ejemplos** | `pipeline` `stages` `stage` `echo` `sh` `script` `catchError` `parallel` + `branch` `retry(count) { }` `timeout(time, unit) { }`, además de la reutilización con `--db` |
| **Declarado, sin ejemplo de referencia** | `withEnv` `dir` `withCredentials` `stash`/`unstash` `archiveArtifacts` `artifactQuery` `deleteDir` `cleanWs` `writeFile` `readFile` `fileExists` `error` `sleep` `warnError` `unstable` `pwd` `isUnix` `waitUntil` `milestone` `publishHTML` `lock` `input` `timestamps` `post` `environment { }` `options { timeout }` `agent*` `whenGate`/`whenEnvIs`/`whenEnvPresent` `scmGit`, builders de plugin (`httpRequest`, `junitResults`, `scmGitCheckout`, `core-utils.*`) |
| **Falla cerrado — compila y `run` sale con `2`** | `git()` (resuelve a la clave no registrada `core.checkout`), `load()` (`core.load`), `node { }`, `ansiColor { }` |
| **Lanza en la llamada** | `retry(count, delaySeconds)` — la forma de step, sin bloque (`StageScopeBuilders.kt:306`). No es la forma de bloque `retry(count) { }` |
| **No existe** | `when { }`, `parameters { }`, `properties { }`, `triggers { }`, `agent { }` como bloque, `options` más allá de `timeout` |

## Variables de entorno

| Variable | Efecto |
|---|---|
| `PIPELINE_STORE_PASSPHRASE` | passphrase del almacén de secretos (`PassphraseResolver.kt:20`) |
| `PIPELINE_SANDBOX_ALLOW_EXTRA` | relajación del sandbox (`SandboxConfig.kt:54`) |
| `PIPELINE_SANDBOX_PATH_KEEP` | conservar rutas del sandbox (`SandboxConfig.kt:55`) |
| `APP_HOME` | classpath de plugins empaquetados (`ScriptDefinition.kt:81`) |

No se lee ningún otro `System.getenv` en `src/main`. `.env` **no** se carga automáticamente.

## Trampas

| Trampa | Qué pasa |
|---|---|
| Flags después de la ruta del script | se ignoran en silencio, sin error (`CliParser.kt:151`) |
| Usar `validate` como ensayo | puede decir `VALIDATION SUCCESSFUL` para un script que `run` rechaza con exit `2`; nunca arranca procesos (`Main.kt:191`) ni ejecuta el puente canónico |
| Exit code `1` frente a `2` | argumentos CLI inválidos son `1`; el resto de rechazos son `2` |
| Esperar el código del step en el exit code | `sh("exit 3")` → el CLI sale con `1`, no con `3` |
| `--subject-kind` | ese flag no existe; el real es `--subject` (`MainEventsCli.kt:36`) |
| `[--after-last-run-started]` | aparece en el texto de uso pero **no está implementado**; el flag real es `--scope last-segment` (`MainEventsVerifyCli.kt:32`, `:37`) |
| `--allow-network=true` | no se acepta; el flag no lleva valor (`CliParser.kt:224`) |
| `--isolated --workspace X` | rechazado, mutuamente excluyentes (`CliParser.kt:154`) |
| `--resume --rerun` | rechazado, mutuamente excluyentes (`CliParser.kt:196`, `:202`) |
| `--resume`/`--rerun` sin `--db` | exit `2` (`Main.kt:239`) |
| Esperar `--help` / `--version` | no son flags. Comando o flag desconocido → exit `1` (`CliParser.kt:133`, `CliParser.kt:138`, `Main.kt:151`) |
| Esperar que `--db` haga `validate` durable | `validate` acepta `--db` y lo ignora (`Main.kt:190`) |
| `--sandbox-profile os` | rechazado (`CliParser.kt:233`) |

## Siguiente página

| Si quieres | Ve a |
|---|---|
| empezar de cero | [`quickstart.es.md`](quickstart.es.md) |
| instalar / actualizar | [`installation.md`](installation.md) |
| detalle completo del CLI | [`cli-reference.md`](cli-reference.md) |
| detalle completo del DSL | [`pipeline-dsl.md`](pipeline-dsl.md) |
| eventos y recuperación | [`events-and-troubleshooting.md`](events-and-troubleshooting.md) |
| el índice | [`README.es.md`](README.es.md) |