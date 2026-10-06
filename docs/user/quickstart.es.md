# PipelineK — Inicio rápido

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**No verificado contra un binario publicado.** La release publicada actual es la `0.47.0` (el digest de su ZIP está en el `SHA256SUMS` de la release); ver la nota de divergencia abajo.

> **Divergencia de documentación.** Esta página afirmaba antes el contrato de `0.39.0` y decía que
> `parallel` / `retry` / `catchError` no existían. Esa afirmación no se sostenía contra el código:
> están declarados en el DSL de v2 y los ejercita `examples/run.sh`. La página documenta ahora la
> rama de desarrollo (`0.47.0`). Registrado el 2026-10-06. Ver `docs/user/README.es.md` → "Divergencias conocidas".

> **Estado.** Este repositorio no tiene CI remota desde el 2026-09-30 (`.github/workflows/` no
> existe). No leas esta página como una afirmación de que el producto está listo para producción: el
> PRODUCT-GATE está `BLOCKED_EXTERNAL`.

## Qué vas a poder hacer

Escribir un `pipeline.kts`, ejecutarlo con el comando `pipelinek`, leer su exit code y leer el
registro estructurado de lo que ocurrió — empezando de cero, sin experiencia previa en CI.

## 1. Antes de empezar

| Necesitas | Detalle |
|---|---|
| Java 21 o superior | la distribución no incluye un JDK |
| El binario `pipelinek` | mira [`installation.md`](installation.md) |

Comprueba lo que tienes:

```bash
pipelinek version      # → pipeline 0.47.0
pipelinek doctor       # jdk / os / workdir; exit 2 si el directorio de trabajo no es escribible
```

`version` lee `Implementation-Version` del manifiesto del jar — informa del artefacto compilado, no
de una cadena fija. Un jar sin ese atributo sale con `3` (`Main.kt:72`, `Main.kt:80`).

En un checkout compilado el binario está en:

```text
v2/pipeline-application/build/install/pipelinek/bin/pipelinek
```

## 2. Tu primer pipeline

Un **pipeline** es una receta: una lista de cosas que hacer, en orden.
Un **stage** es una habitación con nombre de esa receta; los stages se ejecutan uno detrás de otro,
en el orden en que los escribes. Un **step** es lo que haces de verdad dentro de una habitación.

Crea un fichero llamado `pipeline.kts`:

```kotlin
pipeline {                     // 1. un pipeline
    stages {                   // 2. las habitaciones, en orden
        stage("hello") {       // 3. una habitación llamada "hello"
            echo("hello from pipeline-kotlin v2")   // 4. un step: decirlo en voz alta
        }
    }
}
```

Línea a línea:

| Línea | Qué es |
|---|---|
| `pipeline { }` | la receta entera. Exactamente uno por fichero. |
| `stages { }` | la lista ordenada de habitaciones. |
| `stage("hello") { }` | una habitación, llamada `hello`. El nombre aparece en los eventos. |
| `echo("…")` | un step que imprime texto. No arranca ningún proceso del sistema operativo. |

Esto es `examples/01-hello.pipeline.kts` en este repositorio.

## 3. Ejecútalo

```bash
pipelinek run --workspace . pipeline.kts
```

Resultado esperado:

| Canal | Qué obtienes |
|---|---|
| stdout | un array JSON de eventos: `[ {...}, {...} ]` (`Main.kt:436`) |
| stderr | `Pipeline finished with SUCCESS` (`Main.kt:440`) |
| exit code | `0` |

Lee el último evento, que lleva el resultado final:

```bash
pipelinek run --workspace . pipeline.kts | jq '.[-1]'
```

> ### Trampa 1 — los flags van ANTES del script
>
> ```bash
> pipelinek run --db ./.pipelinek/run.sqlite pipeline.kts   # ✅ correcto
> pipelinek run pipeline.kts --db ./.pipelinek/run.sqlite   # ❌ --db se ignora en silencio
> ```
>
> El parser lee tokens `--` sólo mientras lleguen **antes** del primer token que no sea un flag, y ese
> primer token plano termina el parseo (`CliParser.kt:144`, `CliParser.kt:151`). Un flag colocado
> después de la ruta del script no es un error — se descarta en silencio. No hay ningún aviso.

`--workspace .` apunta la ejecución al directorio que contiene tu proyecto (la caja de herramientas
en la que trabajan los steps). La resolución por defecto cuando se omite el flag es
**NO VERIFICADO** en esta página: pasa `--workspace` explícitamente.

El exit code `0` cubre tanto `success` como `unstable` (`Main.kt:440`, `Main.kt:443`). Sólo
`failure` y `aborted` producen un exit code distinto de cero.

## 4. `validate` no es un ensayo

```bash
pipelinek validate pipeline.kts      # → VALIDATION SUCCESSFUL (stderr)
```

`validate` compila el script e imprime diagnósticos. Eso es todo lo que hace.

> ### Trampa 2 — `validate` no demuestra que el script corra
>
> `validate` **nunca** arranca un proceso del sistema operativo (`Main.kt:191`) y **no ejecuta el
> puente canónico** — la puerta que aplica `run` es `Main.kt:423` (en memoria) y `Main.kt:827`
> (durable). Por tanto un script puede imprimir `VALIDATION SUCCESSFUL` (`Main.kt:227`) y aun así
> ser rechazado por `run` con exit `2`. La única comprobación real es `run`.
>
> `--db` lo acepta `validate` y **lo ignora**: vuelve antes de abrir ningún almacén
> (`Main.kt:190`, `Main.kt:228`).

| | `validate` | `run` |
|---|---|---|
| Arranca procesos del SO | nunca (`Main.kt:191`) | sí |
| Escribe un journal | no — `--db` se ignora | sí, en `--db` |
| Rechaza `git()` / `load()` / `node {}` / `ansiColor {}` | no | sí → exit `2` |
| Mensaje final | `VALIDATION SUCCESSFUL` | `Pipeline finished with …` |

## 5. Ahora algo real: un step de shell

`sh("…")` pide al sistema operativo que ejecute un comando — arranca un proceso real, y el **exit
code** de ese proceso lo decide todo. Un exit code es el número con el que responde un comando; `0`
significa "todo bien", cualquier otra cosa significa "algo ha ido mal".

```kotlin
pipeline {
    stages {
        stage("system-info") {
            sh("uname -a")
        }
        stage("loop") {
            sh("for i in 1 2 3; do echo iteration-\$i; done")
        }
    }
}
```

Esto es `examples/03-shell.pipeline.kts`. La regla, y es toda la regla:

```text
sh sale 0   → el step tiene éxito, el stage continúa
sh sale ≠0  → el step falla, el stage falla, los stages posteriores NO se ejecutan
```

Fíjate en el `\$` del bucle: si no, Kotlin interpolaría `$i` al analizar el script.

## 6. Ver los eventos

Un **evento** es un registro tipado y estructurado de algo que ha pasado. Piénsalo como una línea
del cuaderno del operario: una ejecución no sólo produce un aprobado/rechazado, produce un
historial que puedes volver a leer. El **journal** es ese cuaderno — un fichero SQLite — y `--db`
es dónde lo dejas. Un **runId** es el número de ticket de una ejecución concreta.

Sin `--db`, la ejecución se lo guarda todo en memoria y nada sobrevive al proceso (`Main.kt:234`).
Con `--db`, queda escrito donde después puedas pedirlo.

```bash
pipelinek run --db ./.pipelinek/run.sqlite --workspace . pipeline.kts > run.json
RUN_ID=$(jq -r '.[0].runId' run.json)

pipelinek events --db ./.pipelinek/run.sqlite "$RUN_ID"
pipelinek events --db ./.pipelinek/run.sqlite "$RUN_ID" --kind StepFinished
pipelinek events --db ./.pipelinek/run.sqlite "$RUN_ID" --limit 20
```

`events` imprime un envelope JSON por línea en **stdout** y un token de cursor
(`evt-cursor-v1:<runId>:<sequence>`) en **stderr** (`MainEventsCli.kt:77`, `MainEventsCli.kt:80`).
`--db` y el `runId` son ambos obligatorios — sin ellos el exit code es `2`
(`MainEventsCli.kt:46`).

## 7. Ahora algo que falla

Los fallos son el objetivo de un motor de CI. Esto es `examples/05-failing-step.pipeline.kts`:

```kotlin
pipeline {
    stages {
        stage("ok") {
            echo("this stage runs")
        }
        stage("boom") {
            sh("echo 'about to fail' && exit 3")
        }
        stage("never-reached") {
            echo("this stage must NOT run")
        }
    }
}
```

```bash
pipelinek run --workspace . failing.pipeline.kts
```

| Canal | Qué obtienes |
|---|---|
| stdout | los eventos, terminando en `RunFinished` con `outcome=failure` |
| stderr | `Pipeline finished with FAILURE` (`Main.kt:451`) |
| exit code | **`1`** |

Dos cosas a las que conviene prestar atención:

1. `sh` salió con `3`, pero el CLI sale con **`1`**. `1` significa "el pipeline falló"; el exit code
   propio del step viaja dentro del fallo tipado, no en el exit code del proceso.
2. `stage("never-reached")` no se ejecuta. `examples/run.sh` comprueba exactamente eso en el
   ejemplo 05.

## 8. Lista: "¿lo he entendido?"

- [ ] He instalado Java 21+ y el binario, y `pipelinek version` imprime una versión.
- [ ] He creado `pipeline.kts` con `pipeline { stages { stage("…") { … } } }`.
- [ ] He ejecutado `pipelinek run --workspace . pipeline.kts` y he obtenido exit `0`.
- [ ] He puesto **todos los flags antes** de la ruta del script, y sé que un flag colocado después
      se ignora en silencio.
- [ ] Sé que `validate` compila pero no ejecuta, y que sólo `run` demuestra que un script funciona.
- [ ] Sé que `sh("…")` arranca un proceso real, y que un exit code distinto de cero detiene los
      stages siguientes.
- [ ] He mirado la ejecución con `pipelinek events --db <path> <runId>`.
- [ ] Sé los exit codes: `0` success o unstable, `1` fallo del pipeline **o argumentos CLI
      inválidos**, `2` rechazo de invocación o admisión.

## Siguiente paso

| Si quieres | Ve a |
|---|---|
| instalar / actualizar el binario | [`installation.md`](installation.md) |
| todos los subcomandos y flags | [`cli-reference.md`](cli-reference.md) |
| toda la superficie del DSL, y lo que *no* está probado | [`pipeline-dsl.md`](pipeline-dsl.md) |
| una página para copiar | [`cheat-sheet.md`](cheat-sheet.md) |
| eventos, transcripciones, recuperación | [`events-and-troubleshooting.md`](events-and-troubleshooting.md) |
| el índice de esta documentación | [`README.es.md`](README.es.md) |