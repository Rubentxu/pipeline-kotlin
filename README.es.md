# PipelineK — Construye, verifica y ejecuta con confianza

[![Última versión](https://img.shields.io/github/v/release/Rubentxu/pipeline-kotlin?include_prereleases&sort=semver)](https://github.com/Rubentxu/pipeline-kotlin/releases)
[![Licencia: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Java 21+](https://img.shields.io/badge/java-21%2B-blue)](https://adoptium.net/)
[![DSL en Kotlin](https://img.shields.io/badge/DSL-Kotlin-purple)](https://kotlinlang.org/)

**Un motor de CI/CD local primero, con un DSL en Kotlin familiar para Jenkins.**
PipelineK compila, valida y ejecuta tus pipelines en tu propia máquina, con un journal durable y eventos tipados: sin controlador, sin agente y sin estado remoto.

**[English](README.md) | Español**

**[→ Primeros pasos](#primeros-pasos) · [→ Características](#capacidades-principales) · [→ Ecosistema](#ecosistema-de-plugins)**

---

## ¿Por qué PipelineK?

Tres problemas aparecen en casi todos los equipos que ejecutan CI.

**El feedback llega tarde.** El pipeline se ejecuta a minutos de distancia, en la máquina de otro, y descubres qué se rompió cuando el job ya ha desaparecido. PipelineK ejecuta ese mismo pipeline en tu propia máquina, en segundos, antes de hacer el push.

**La automatización queda atada a un proveedor.** El pipeline es un fichero YAML en el dialecto de un proveedor, y moverlo significa reescribirlo. El pipeline de PipelineK es un fichero Kotlin que te pertenece, y el runner es un único binario sin ningún servicio detrás.

**Los fallos son difíciles de entender.** Una build roja dice "exit code 1" y nada más. PipelineK emite eventos tipados, journaliza cada paso con una huella de sus entradas y atribuye el fallo al stage al que pertenece.

Lo que PipelineK **no** es: no es un planificador y no promete builds byte a byte idénticas entre máquinas distintas. Es un **motor** de pipelines: lo que ejecuta tu pipeline, registra lo que ocurrió y te dice con precisión por qué falló.

---

## Primeros pasos

**Requisitos:** Java 21 o posterior en el `PATH`, y Linux, macOS o Windows (WSL). Nada necesita `sudo`; ningún demonio se arranca solo.

```bash
# La última versión ESTABLE. El digest se publica con la release, así que los
# bytes que ejecutas son los bytes que se firmaron.
TAG=$(curl -fsSL https://github.com/Rubentxu/pipeline-kotlin/releases.atom \
  | sed -n 's#.*releases/tag/\(v[^"]*\)".*#\1#p' | grep -v -- '-rc' | head -1)
BASE="https://github.com/Rubentxu/pipeline-kotlin/releases/download/${TAG}"

curl -fL -o manifest.json "$BASE/manifest.json"
ASSET=$(grep -o '"pipelinek-[^"]*\.zip"' manifest.json | grep -v sdk | head -1 | tr -d '"')

curl -fL -o "$ASSET" "$BASE/$ASSET"
curl -fL -o SHA256SUMS "$BASE/SHA256SUMS"
grep " ${ASSET}$" SHA256SUMS | sha256sum -c -

unzip -q "$ASSET"
PIPELINEK="./$(basename "$ASSET" .zip)/bin/pipelinek"

"$PIPELINEK" version     # → pipeline 0.97.0
"$PIPELINEK" doctor      # jdk / os / workdir / writable / script cache
```

El ZIP trae `bin/pipelinek` (UNIX) y `bin/pipelinek.bat` (Windows). Te queda un directorio `pipelinek-<version>/`: úsalo desde ahí o pon su `bin/` en el `PATH`.

### Tu primer pipeline

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
"$PIPELINEK" validate hello.pipeline.kts   # VALIDATION SUCCESSFUL
"$PIPELINEK" run hello.pipeline.kts        # PIPELINE-OK … Pipeline finished with SUCCESS
```

`validate` compila el script **y evalúa su cuerpo**: un `.pipeline.kts` es código de confianza, no datos. Usa `validate --compile-only` cuando solo quieras saber que compila.

Un valor que el script construye y luego descarta es un **error de compilación**, no un paso que no se ejecutó sin que nadie lo dijera. Un `scmGit(...)` que nunca llega a `checkout(...)` se rechaza con su línea y su columna, dentro de un `stage { }` o en una función auxiliar:

```text
build.pipeline.kts:9:13: Unused return value of 'scmGit'. It was coerced to 'Unit' due to a functional parameter type.
VALIDATION FAILED: 1 error
```

La regla cubre todo resultado que deba usarse, también los de la biblioteca estándar (`"x".uppercase()` solo en su línea se rechaza; `list.add(x)` no). Una llamada de script como `pwd()` ya emite su paso, así que compila como sentencia, igual que en un Jenkinsfile.

La definición de un `.pipeline.kts` (la clase `@KotlinScript`, sus imports y estas opciones del compilador) se publica como `pipelinek-scripting-definition`, para que un IDE compile el script con las mismas reglas que el runner. Es nueva en este árbol: la release 0.97.0 no la lleva.

### Leer una variable del entorno

```kotlin
stage("build") {
    environment { env("TARGET", "staging") }
    sh("echo " + env("TARGET"))
}
```

`env("TARGET")` es cómo lee un script una variable: la que declara el stage con `environment { }` cuando declara alguna, y si no, la que tiene la máquina que construye el plan. **No** es `env.TARGET`: Kotlin no tiene properties dinámicas, así que un nombre que solo se conoce en runtime no puede ser una property de un objeto.

El valor se lee **mientras se construye el plan**, así que viaja dentro del plan y un replay lo reproduce. Una variable que no está definida se lee como `null` y no como un texto vacío, y el material de una credencial nunca está entre las respuestas: una credencial llega al paso por el entorno del proceso hijo, nunca por el entorno del motor.

### Lo que declara el pipeline para sus stages

```kotlin
pipeline {
    environment {
        env("TARGET", "staging")
        env("REGISTRY", "registry.internal")
    }
    agent(label = "linux")
    stages {
        stage("build") {
            sh("echo " + env("TARGET"))
        }
        stage("deploy") {
            environment { env("TARGET", "production") }
            agent(label = "linux-arm")
        }
    }
}
```

`environment { }` y `agent(…)` existen también a **nivel de pipeline**, con la misma grafía que tienen dentro de un stage. Lo que declara el pipeline es el POR DEFECTO de sus stages: un stage que declara una variable propia la conserva, y un stage que declara un agente propio lo conserva también. La precedencia es la que ya usa el gate —una declaración gana sobre un valor que llega de fuera— y se decide **nombre por nombre**: el stage `deploy` de arriba corre con `TARGET=production` y con `REGISTRY=registry.internal`, el `TARGET` del pipeline no le llega, y su agente es `linux-arm` y no el `linux` del pipeline.

Dos reglas sobre el orden, y se diferencian porque son sobre cosas distintas:

- las **variables** se leen mientras corre el script, así que el bloque `environment { }` del pipeline tiene que estar escrito **antes** del bloque `stages { }` para que una lectura dentro de un stage la vea. Una lectura de una variable declarada más tarde responde con el valor que tiene la máquina, y no con `null`: la variable está declarada, sólo que demasiado tarde para esa lectura. Las variables llegan a los pasos igual: son el entorno con el que corren los pasos de esos stages;
- el **agente** se aplica al final del bloque del pipeline, así que el sitio donde está escrita la declaración `agent(…)` no cambia qué agente corre un stage.

Un segundo bloque `environment { }` a nivel de pipeline **añade** al primero: las variables de los dos llegan a los stages, y el nombre que declaran los dos es el del bloque posterior. Un pipeline declara **un** agente: un segundo `agent(…)` se rechaza donde se escribe, porque pondría dos exigencias de la misma directiva en cada stage que no declara ninguno.

Las variables del pipeline son también lo que lee el **gate** de un stage: el stage `deploy` de arriba se condiciona con el mismo `TARGET=production` con el que corren sus pasos, y el `REGISTRY` del pipeline es visible para ese gate también.

### El `post { }` cierra el pipeline, no sólo un stage

```kotlin
pipeline {
    stages {
        stage("build") { sh("make") }
    }
    post {
        always { echo("the run ended") }
        success { echo("the run succeeded") }
        failure { echo("the run failed") }
    }
}
```

El `post { }` existe también a **nivel de pipeline**, con la misma forma y las mismas siete condiciones que tiene dentro de un stage (`always`, `success`, `failure`, `unstable`, `aborted`, `unsuccessful`, `cleanup`). El `post` de un stage cierra **ese stage**; el del pipeline cierra **el pipeline**, y sus condiciones se deciden sobre el resultado del **run completo**: `success` significa que todos los stages terminaron bien, `failure` que el run falló, y `aborted` que el run fue detenido — que es una afirmación distinta de la de que falló.

El motor tiene un **punto de cierre** del pipeline, y ahí se ejecuta el `post` del pipeline: después de los stages y antes del propio libro del run. Un finalizador que falla hace fallar el run, igual que el finalizador de un stage hace fallar el run al que pertenece.

Los finalizadores del pipeline llevan su propia identidad durable — el índice de stage **uno más allá del último stage** — así que nunca comparten fila de journal con los de un stage, y el segundo intento de un run los encuentra donde el primer intento los dejó: el `post` del pipeline no se vuelve a ejecutar desde el principio, el modelo durable sirve la finalización que ya escribió.

Un pipeline que no declara `post` cierra sin nada que ejecutar.

### El bloque `script { }` contiene pasos

```kotlin
stage("build") {
    script {
        val branch = env("BRANCH")
        if (branch == "main") {
            echo("building main")
        }
        sh("make")
    }
}
```

`script { }` es un **bloque de pasos**, como lo escribe un Jenkinsfile: `echo("…")` dentro de él es un `core.echo` y `sh("…")` es un `core.sh`, cada uno un paso con su propia identidad y su propia política. No es un bloque de líneas de shell: un `core.sh` corre en un agente, ejecuta un subproceso y replayea desde el principio, mientras que un `core.echo` corre en el controlador, no lee nada y replayea desde su resultado. La identidad de los pasos es la identidad de Jenkins.

El bloque lleva el receptor de un stage, así que tiene todo el vocabulario (`echo`, `sh`, `dir`, `retry`, …). Un bloque de **líneas** de shell es un paso, escrito con `sh("""…""")`.

Lo que el bloque aporta es el control de flujo de Kotlin del autor, y ese control de flujo se evalúa **mientras se construye el plan**: el `if` de arriba decide qué brazo se declara, antes de que corra el primer paso. Una condición que hay que decidir mientras corre la ejecución se escribe con las puertas `when*`.

### Editar pipelines en IntelliJ IDEA

El editor resuelve un script contra el classpath del módulo al que pertenece, y un `.pipeline.kts` fuera de cualquier módulo no tiene DSL ni diagnósticos. `ide init` escribe ese módulo, como un proyecto Gradle, a partir de la instalación con la que lo ejecutas:

```bash
"$PIPELINEK" ide init --dir mis-pipelines
# ide-project /…/mis-pipelines: ready
# created settings.gradle.kts
# created build.gradle.kts
# created gradle.properties
# created pipelines/
# installation /…/pipelinek-<version>/lib
# scripts /…/mis-pipelines/pipelines
```

Pon los scripts en `mis-pipelines/pipelines/` y abre `mis-pipelines` en IntelliJ IDEA. El proyecto los compila con los jars del directorio `lib/` de la instalación y con las opciones del compilador de su definición de script, así que el editor marca como error un `scmGit(...)` descartado en la línea y la columna que nombra `validate`, con las mismas palabras, y `compileKotlin` ejecutado en ese directorio lo rechaza igual:

```text
e: file:///…/mis-pipelines/pipelines/build.pipeline.kts:9:13 Unused return value of 'scmGit'. It was coerced to 'Unit' due to a functional parameter type.
```

- **Nunca sustituye un fichero.** Un directorio que tiene otro fichero en una de esas rutas se rechaza nombrándolo, y no se escribe nada. Una segunda ejecución sobre el proyecto que escribió no cambia nada.
- **No escribe un wrapper de Gradle.** IntelliJ IDEA escribe uno cuando abre el proyecto (la 2026.2 lo escribe para Gradle 9.6.0); el proyecto compila con Gradle 8.14 y 9.6.
- **El proyecto pertenece a una instalación.** `build.gradle.kts` nombra la ruta absoluta de su directorio `lib/`. Después de instalar otra versión, borra `settings.gradle.kts`, `build.gradle.kts` y `gradle.properties` y vuelve a ejecutar `ide init` con el lanzador nuevo. Un build cuya instalación ya no está se niega a configurarse y lo dice.
- **Sin barra invertida en las rutas.** Gradle lee una barra invertida en una ruta como un separador, así que se rechaza un directorio de proyecto, una instalación o un jar de plugin cuya ruta la contenga.
- **Tus plugins, por su ruta.** El proyecto ve los plugins del directorio `lib/` de la instalación y los jars que nombras con `--plugin-jar <jar>`, una vez por jar; `build.gradle.kts` nombra cada uno por su ruta absoluta, e `ide init` imprime una línea `plugin` por cada uno. Antes de escribir nada, `ide init` comprueba esos jars —los de la instalación y los que trajiste— como lo hace `run` antes de cargarlos: sus manifiestos, su rango de API y, con `--plugin-trust <fichero>` o `--require-plugin-trust`, tu decisión de confianza. Un jar cuyo manifiesto, cuyo rango de API o cuya decisión de confianza rechace `run` se rechaza con las mismas palabras y la misma ruta absoluta, y no se escribe nada. Lo que `run` sólo aprende al ejecutar el código de los plugins no se comprueba aquí: un jar que pasa estas comprobaciones puede ser uno que `run` rechace una vez lo carga.
- **La comprobación se hace al escribir el proyecto.** Gradle y el editor leen los jars después desde esas rutas, y nada los vuelve a comprobar: un jar sustituido después de `ide init` se compila tal como esté. Ejecuta `ide init` otra vez con las mismas opciones para volver a comprobarlos; el proyecto no cambia. Un build cuyo jar de plugin ya no está se niega a configurarse y lo dice.
- **El aviso.** IntelliJ IDEA muestra *"This script is not supposed to be inside source root"* encima de cada script. Deja los scripts en `pipelines/`: ahí los compila el build, y ahí los resalta el editor.
- **Solo el editor.** El inspector de línea de órdenes de IntelliJ IDEA (`inspect.sh`) no analiza los ficheros `.pipeline.kts`; en una máquina de CI, usa `pipelinek validate` o `gradle compileKotlin`.

`ide init` es nuevo en este árbol: la release 0.97.0 no lo lleva.

### Consultar la ejecución después

Dale a la ejecución un journal y un control root, y cada paso, evento y salida se queda legible:

```bash
"$PIPELINEK" run --db state/pipelinek.db --control-root state/ctl hello.pipeline.kts
# {"schema":"pipelinek.launch/1","runId":"34b28ddc-…","correlationId":"…","ownerId":"…","startedAtMs":1730000000000,"status":"Launched"}

"$PIPELINEK" inspect <runId> --db state/pipelinek.db --control-root state/ctl
"$PIPELINEK" events  --db state/pipelinek.db <runId>          # eventos tipados, un JSON por línea
"$PIPELINEK" observe <runId> --db state/pipelinek.db --control-root state/ctl --view events
```

`inspect` muestra los stages, los pasos y sus resultados; `events` lee el carril de eventos; `observe` lee la consola y el plano de salida de la ejecución. La lista completa de verbos está en `pipelinek --help`.

### Leer una ejecución desde fuera

Los hechos de una ejecución se leen desde fuera del producto, y sólo por las puertas que el producto abre.

**Qué puede leer un consumidor.** Los puertos de lectura publicados son todo el lado de lectura:

- el carril de eventos, como eventos tipados, paginados por secuencia (`EventRecordReadPort`);
- el plano de salida, como bytes comprometidos, dirigidos por stream y por cursor (`OutputReadPort`);
- el estado de la ejecución, como una observación (`RuntimeObservation`).

**Qué no puede hacer un consumidor.** El lado de escritura del almacén de eventos, el puerto de anexado de salida, la entrada de recuperación y la identidad de la ejecución no forman parte de ningún contrato publicado. Un consumidor que anexara al carril de eventos de una ejecución, o comprometiera bytes en su plano de salida, sería una segunda autoridad sobre los hechos de esa ejecución: el plano se lee, pero no se escribe, no se reserva, no se recupera y no se poda.

**Dos puertas, un hecho.** La línea de órdenes lee una ejecución del almacén; el servicio lee la misma ejecución del mismo almacén y responde por el socket. `pipelinek inspect --state` y `pipelinek inspect --state --endpoint <socket>` imprimen la misma línea y salen con el mismo código, porque el valor que hay detrás de las dos es el mismo `RuntimeObservation`.

---

## Capacidades principales

| Capacidad | Qué te da | Estado |
|---|---|---|
| **DSL en Kotlin** | `pipeline { stages { stage { … } } }` y control de flujo real de Kotlin, bloques `script {}`, `parallel`, `retry`, `timeout`, `catchError`, `whenGate` | Disponible |
| **Ejecución local** | Una ejecución por invocación de la CLI, en tu propio directorio de trabajo. Sin controlador, sin agente, sin demonio que se arranque solo | Disponible |
| **Journal durable** | Un journal en SQLite (`--db`) más un control root: una ejecución matada deja un registro que puedes inspeccionar, reanudar, cancelar o recuperar | Disponible |
| **Eventos tipados** | `CompilationStarted`, `RunStarted`, `StageStarted`, `StepStarted`, `StepFinished`, `StepFailed`, `RunFinished`, `EchoOutputCaptured`, con el stage del fallo adjunto al propio evento de fallo | Disponible |
| **CLI estructurada** | `validate`, `run`, `observe`, `console`, `events`, `inspect`, `credentials`, `doctor`, `version`, `capabilities`, `sdk-info`, `steps`, `describe`, `serve`, `runs cancel`, `runs recover`: cada verbo imprime su propio uso | Disponible |
| **SDK de plugins** | `StepContract`, códecs tipados, capacidades declaradas, un manifiesto con su rango de API. El autor de un plugin compila contra el SDK publicado, y nada más | Disponible |
| **Confianza en plugins** | `--plugin-trust` admite un plugin solo si sus bytes coinciden con el SHA-256 fijado y llevan una firma Ed25519 válida; `--require-plugin-trust` rechaza lo que no esté avalado, antes de que corra su código | Disponible |
| **OpenTelemetry** | Exportación OTLP opcional de tiempos, recuentos y buckets: nunca cargas útiles. Apagada salvo `PIPELINEK_OTEL_ENABLED=true` | Disponible |
| **Introspección y recuperación** | `inspect`, `runs cancel`, `runs recover`, lecturas digeridas, fijación de salida, retención de rechazos | **Experimental** |

La última fila es la honesta: las capacidades `runtime.inspect.v1`, `runtime.cancel.v1`, `runtime.recover.v1` y `output.pin.v1` se publican como **experimentales**. `pipelinek capabilities` imprime el estado exacto de cada clave que admite esta build.

**Lo que está publicado:** `output.follow.v2`, `events.follow.v3`, `events.stepoutcome.v1`, `events.failurestage.v1`.

### El catálogo de steps y directivas

`pipelinek steps` imprime el índice de lo que admite esta build: cada step y cada directiva de la composición que ejecutaría —el núcleo, los plugins de la instalación y los que nombras con `--plugin-jar`. El índice se lee del mismo registro del que se compone una ejecución, así que no puede nombrar un step que nadie registró ni omitir uno que todo el mundo puede usar.

También lleva los scopes que escribe el DSL —`retry`, `timeout`, `catchError`, `dir`, `withEnv`, `withCredentials`, `timestamps` y `warnError`—, que el motor ejecuta desde su declaración y no desde un manejador registrado.

```bash
$ pipelinek steps
directive  core.agent                        agent
step       core.echo                         echo
step       core.file.writeFile               writeFile
step       core.retry                        retry
step       core.sh                           sh
step       scm-git.checkout                  scm-git.checkout
```

`pipelinek steps --json` publica el mismo catálogo como un documento del esquema `pipelinek.steps/3`: una entrada por clave, con los efectos que declara el step, qué hace un replay con él, qué hace el motor con él cuando la ejecución murió con él todavía en RUNNING, las capacidades que necesita, el JSON Schema de su carga útil, el equivalente en Jenkins y la divergencia de ESA entrada. El documento lleva además una sección `divergences`: las diferencias que son las mismas para todo script —las que impone el lenguaje y las que decide el producto—, cada una con su nombre y su motivo. Y una sección `forms`: las formas de Jenkins en las que un script puede estar escrito, la forma de PipelineK que sustituye a cada una, y la frase que el compilador imprime cuando un diagnóstico cae sobre una de ellas.

Cuando un `.pipeline.kts` está escrito en la forma de Jenkins, el diagnóstico dice qué escribir en su lugar. El compilador apunta al token que está mal —la comilla de una cadena de Groovy, el dos puntos de un argumento nombrado de Groovy, el `env` de `env.BRANCH_NAME`— y la sugerencia nombra la forma de PipelineK que la sustituye:

```bash
$ pipelinek validate jenkins.pipeline.kts
[PipelineK] Compiling pipeline
[PipelineK] Compilation finished with 1 error (compiled)
jenkins.pipeline.kts:4:16: Too many characters in a character literal. In PipelineK: a string is written between double quotes: "make".
VALIDATION FAILED: 1 error
```

La sugerencia es la frase de la sección `forms` del catálogo, y llega al runner: la consola, `validate` y `run`. No llega al IDE, que compila el script con su propio compilador y nunca pasa por el host del runner.

`pipelinek describe sh` imprime la ficha de una entrada, nombrada por su clave (`core.sh`) o por su nombre (`sh`):

```bash
$ pipelinek describe sh
core.sh
  kind          step
  name          sh
  provider      core
  effects       EXECUTES_SUBPROCESS
  replay        RERUN
  recovery      ExternalSubprocess
  capabilities  shellOperations
  location      AGENT
  body          none
  input schema:
    {}
  output schema:
    {}
  jenkins       sh
  divergence    (none)
  deprecation   (none)
```

El esquema de la carga útil es el que declara el códec del step, y los steps del núcleo todavía no declaran ninguno: su esquema es `{}` hasta que un códec diga qué forma tiene la carga útil.

La tabla de equivalencias con Jenkins NO está escrita aquí, y ese es el punto: vive en el catálogo, que se construye desde el código —cada step declara su equivalente y su divergencia en el descriptor que lo describe, y el catálogo los lee. Una tabla escrita en un documento es una segunda autoridad sobre un hecho que el código ya posee, y se desvía la primera vez que se añade un step. Este README nombra los dos verbos que la publican (`pipelinek steps` para el índice, `pipelinek describe` para la ficha de una entrada) y el documento que la lleva; la tabla legible se genera desde ese mismo código para la web.

Un nombre que nadie lleva se rechaza nombrando las entradas que se le parecen, y un nombre que llevan dos entradas se rechaza nombrando las dos: `core.pwd` y `core.pwd.tmp` son dos steps distintos, y responder con uno de ellos sería un azar disfrazado de respuesta.

### El servicio local

`pipelinek serve` levanta un servicio local sobre un socket de Unix, para que un cliente pueda preguntar por las ejecuciones de una máquina —y, con el ámbito adecuado, arrancar una. Es opt-in en el sentido literal: nadie lo arranca por ti, y sirve hasta que lo paras. Lo que queda detrás cuando para no es nada que CORRA —el socket se va—, y lo que ESCRIBIÓ se queda: las autorizaciones, el registro de los arranques y el directorio de las ejecuciones supervisadas son ficheros del operador, junto al socket, y no se borran.

```bash
pipelinek serve --db ./state/pipelinek.db --control-root ./state/durable --socket ./state/pipelinek.sock
```

Las tres rutas son obligatorias y ninguna tiene valor por defecto: el servicio lee el sustrato que nombras y escucha donde dices, de modo que lo que el socket dice de una ejecución es comparable con lo que dice `inspect --state` sobre las mismas entradas. El sustrato lo prepara un paso PROPIO —`pipelinek substrate init --db <path>`—, que crea el fichero con la forma que el servicio lee y dice lo que hizo: la ruta, si se creó o ya estaba, y las tablas que lleva. El servicio no crea el sustrato que sirve ni el control root que va a leer: un `--db` que no está se rechaza por nombre, en vez de dejar un fichero vacío donde el operador guardaba sus hechos. En el sustrato el servicio escribe UN hecho, y sólo cuando puede cancelar: el terminal de una ejecución que cierra —las ejecuciones que arranca escriben el resto, en el almacén que nombraste—. Abrir ese almacén en lectura-escritura también lo PREPARA: el esquema se crea cuando falta y los ficheros del log aparecen en cada arranque, así que un fichero existente y vacío se convierte en un almacén. Lo que el servicio nunca hace es crear el sustrato que no está. Lo que el servicio escribe aparte es suyo, junto al socket: el fichero de las autorizaciones que posee, el registro de los arranques (`starts`) y el directorio de las ejecuciones supervisadas (`supervisions`).

El socket es un fichero del sistema de ficheros, y su modo es la frontera. El socket queda en `rw-------`, y su directorio tiene que ser privado (`rwx------` cuando el servicio lo crea): si el directorio concede algo al grupo o a otros, el servicio no arranca en vez de publicar un socket que esos usuarios podrían abrir.

Toda petición lleva una **credencial**: un token que el servicio emitió, en el campo `auth`. Una petición sin ella se rechaza por su nombre (`not-authorized`), y también una petición sobre una ejecución que el ámbito de la credencial no cubre (`run-not-authorized`).

Una petición por línea, una respuesta por línea, en JSON, con la versión del protocolo en el campo `schema` y el tipo de la línea en `kind`:

```json
{"schema":"pipelinek.service/2","op":"run.state","runId":"34b28ddc-…","auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"state":"terminal","attempt":1,"at":0,"outcome":"succeeded","terminalAt":0}}
{"schema":"pipelinek.service/2","kind":"refused","refusal":{"kind":"unknown-run","runId":"34b28ddc-…"}}
```

El servicio lee tres cosas: `version` (la versión del producto y la del protocolo), `health` (el servicio y el sustrato con el que se compuso) y `run.state` (el estado de una ejecución, la misma respuesta que imprime `inspect --state` sobre el mismo almacén). También puede ARRANCAR una ejecución, CANCELAR una y RESPONDER la pregunta de una, que son una cuarta, una quinta y una sexta puerta, cada una con un derecho propio. Cada rechazo lleva un nombre — `unknown-run`, `incompatible-protocol`, `unknown-op`, `unknown-operation`, `request-too-large`, `too-many-connections`, `not-authorized`, `run-not-authorized`, `too-many-subscriptions`, `start-not-authorized`, `script-not-admitted`, `workspace-not-admitted`, `idempotency-conflict`, `too-many-supervised-runs`, `run-start-refused`, `run-configuration-refused`, `configuration-provider-refused`, `credential-provider-refused`, `cancel-not-authorized`, `run-cancel-refused`, `answer-not-authorized`, `input-answer-refused`, `parameters-refused`, `describe-parameters-refused` — para que el cliente lea una palabra y no una frase.

Un cliente **negocia** preguntando primero `version`. Un servicio del mismo protocolo la responde con su protocolo y su build; un servicio de otro protocolo la rechaza con `incompatible-protocol`, antes de cualquier pregunta sobre una ejecución; y una build de este protocolo a la que le falta una pregunta que el cliente hace la rechaza con `unknown-op`. El protocolo no tiene una operación de capacidades propia: lo que admite esta build, y si cada capacidad está publicada, es experimental o está obsoleta, es lo que imprime `pipelinek capabilities`, y un cliente no lee una capacidad experimental como una estable.

La respuesta de `run.state` lleva la observación ENTERA: el intento, la continuidad y su token de fencing, la posición del journal, las colas de la salida y el terminal con su desenlace. Un cliente que la reconstruye obtiene el mismo `RuntimeObservation` que la línea de órdenes renderiza, y no hay una segunda forma del mismo hecho.

#### Arrancar una ejecución

El servicio también puede ARRANCAR una ejecución. Es una puerta aparte con un derecho propio, porque arrancar una ejecución no es una pregunta sobre una ejecución: el ámbito de un cliente que puede arrancar lo dice con sus propias palabras.

El ámbito de una autorización crece con el sufijo `+start`: `all+start` puede leer todas las ejecuciones Y arrancar ejecuciones; `none+start` puede arrancar ejecuciones y no leer ninguna. Un ámbito sin el sufijo no arranca nada, y el rechazo lleva su propio nombre (`start-not-authorized`) — no `not-authorized`, porque el token se aceptó y lo que le falta es el derecho.

El ámbito gobierna la LECTURA, y el servicio no lo ensancha porque una ejecución haya arrancado: un cliente `none+start` recibe el `runId` de la ejecución que arrancó, y ese identificador es uno que no puede consultar con la misma credencial. Un cliente que tiene que seguir la ejecución que arranca necesita un ámbito que también lea ejecuciones (`all+start`).

El operador declara qué puede arrancar el servicio, y esa declaración es parte de la orden:

```bash
pipelinek serve --db ./state/pipelinek.db --control-root ./state/durable --socket ./state/pipelinek.sock \
  --scripts ./pipelines --workspaces ./scratch \
  --run-deadline-ms 900000 --max-supervised-runs 4
```

`--scripts` es el directorio de los guiones que un cliente puede nombrar y `--workspaces` el de los espacios de trabajo. Un cliente nombra un guion RELATIVO a ese directorio —`sub/build.kts` es un nombre, `/etc/passwd` y `../secret` no lo son— y un nombre que sale del directorio —absoluto, subiendo de nivel, o un enlace que apunta fuera de él— se rechaza por su nombre (`script-not-admitted`, `workspace-not-admitted`). Sin `--scripts` el servicio arranca y no arranca nada: un servicio que pudiera ejecutar lo que se le pidiera sería una línea de órdenes con un socket delante. `--run-deadline-ms` es cuánto puede vivir una ejecución supervisada, `--start-deadline-ms` cuánto espera el servicio a que un hijo tome su continuidad (30 s por defecto — súbelo en una máquina donde arrancar una JVM y compilar un guion tarden más, o el servicio rechazará una ejecución que estaba a punto de arrancar), `--max-supervised-runs` cuántas vigila el servicio a la vez (`too-many-supervised-runs`), y `--run-allow-network` dice si una ejecución supervisada puede llegar a la red.

La petición nombra un guion y, quizá, un espacio de trabajo y una clave de idempotencia:

```json
{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts","workspace":"scratch","idempotencyKey":"order-42","auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"runId":"34b28ddc-…","correlationId":"…","ownerId":"…","startedAtMs":0}}
```

La respuesta es la identidad de una ejecución y NO su desenlace: la ejecución sigue viva cuando se escribe la respuesta, y lo que concluya es la respuesta de otra pregunta (`run.state`). La respuesta se escribe solo después de que el hijo tomara su continuidad (su lease) y imprimiera su propio recibo del arranque, así que "arrancada" significa "esta ejecución existe y es dueña de sí misma".
#### Una ejecución con configuración

La petición puede además llevar una **configuración**: los pares NOMBRE/VALOR que los pasos de ESA ejecución verán en su ambiente. Es opcional, y su ausencia significa "sin configuración" — la ejecución es la que siempre fue.

```json
{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts","idempotencyKey":"order-42","configuration":{"BUILD_PROFILE":"release"},"auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"runId":"34b28ddc-…","correlationId":"…","ownerId":"…","startedAtMs":0,"configuration":{"BUILD_PROFILE":"release"}}}
```

La respuesta **devuelve** la configuración, y ese es el motivo: el esquema del cable no cambia cuando una operación gana un campo, así que un servicio que no conoce el campo lo ignora en silencio y arranca la ejecución SIN la configuración — y el cliente, que la mandó y vio una respuesta bien formada, cree en una configuración que no está ahí. El eco convierte ese silencio en un hecho, y el cliente tipado que compara el eco con lo que mandó convierte el hecho en un negativo con nombre (`unreadable`: la ejecución EXISTE, así que el operador tiene que poder cancelarla).

La configuración es un **canal público, y lo dice**. No hay oráculo que distinga un secreto de un texto plano, así que el servicio no puede prometer guardar un secreto: los valores se escriben en la instantánea que el servicio posee (modo `0600`, en su propio directorio de supervisión), están en el ambiente de los pasos y, si un paso los imprime, están en la salida de la ejecución. Lo que el servicio rechaza es el vocabulario del producto — `PIPELINE_*`, `PIPELINEK_*` y `DURABLE_SH_*`, los nombres que la caja quita de un ambiente, y `JAVA_HOME`/`M2_HOME`/`PATH`, que el lanzador consume para construir el PATH de un paso — por su nombre, con `run-configuration-refused` y un motivo (`the-configuration-name-is-reserved`, `the-configuration-name-is-denied`, `the-configuration-name-is-a-path-anchor`, …). Una configuración que rompe una regla NO ocupa la clave del cliente: el cliente puede corregirla y repetir con la misma clave.

#### Una ejecución con parámetros, y la descripción de sus parámetros

Un script declara sus parámetros como lo hace un Jenkinsfile —`parameters { string("target", defaultValue = "staging"); boolean("dryRun"); choice("region", listOf("eu", "us"), defaultValue = "eu") }`— y un cliente puede preguntar al servicio cuáles son ANTES de arrancar la ejecución. El valor por defecto se llama `defaultValue`, el nombre que le da Jenkins; la grafía histórica `default` sigue compilando y sigue declarando el mismo valor, y lleva un aviso de deprecación que nombra `defaultValue`. El servicio responde desde un **manifiesto que el operador aprobó**, y nunca compila el script para responder: compilar un script ejecuta código del script, y un cliente que puede pedir una descripción no puede ejecutarlo.

El operador escribe el manifiesto en su lado y lo coloca junto al script, dentro del directorio de `--scripts`:

```bash
pipelinek validate --parameter-manifest scripts/build.kts > scripts/build.kts.parameters.json
```

El manifiesto lleva la declaración, su digest y el digest de los bytes del script, y nada del cuerpo del script. La petición nombra el script, y la respuesta lleva el JSON Schema de los valores que acepta un arranque (cada valor es un texto; un `boolean` se describe con `"true"` y `"false"`, un `choice` con sus textos), la propia declaración en la forma en que la lleva un arranque, y su digest:

```json
{"schema":"pipelinek.service/2","op":"describe.parameters","script":"build.kts","auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"description":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"target":{"type":"string","default":"staging"},"dryRun":{"type":"string","enum":["true","false"]},"region":{"type":"string","enum":["eu","us"],"default":"eu"}},"required":["dryRun"],"propertyNames":{…},"additionalProperties":false},"contract":[{"name":"target","type":"string","defaultValue":"staging"},{"name":"dryRun","type":"boolean"},{"name":"region","type":"choice","defaultValue":"eu","choices":["eu","us"]}],"contractSha256":"…"}}
```

El cliente tipado comprueba `contractSha256` contra el `contract` que recibió y lee como `unreadable` una respuesta cuyo digest no casa. Un arranque lleva después los valores y la declaración contra la que se rellenaron, y el hijo comprueba los valores contra la declaración del script que compiló:

```json
{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts","idempotencyKey":"order-42","parameters":{"dryRun":"false"},"parameterContract":[{"name":"target","type":"string","defaultValue":"staging"},…],"auth":"…"}
```

Una descripción que el servicio no puede producir se rechaza con `describe-parameters-refused` y un motivo: `the-script-has-no-approved-manifest`, `the-manifest-is-not-readable`, `the-manifest-contract-does-not-match-its-digest`, `the-script-changed-since-its-manifest-was-approved` (el script se editó después de aprobar el manifiesto: el operador aprueba uno nuevo), `the-script-is-not-readable`, o un motivo de las reglas de los parámetros cuando la declaración es una que un arranque rechazaría. Los valores que rompen la declaración se rechazan con `parameters-refused` y un motivo (`the-parameter-is-missing`, `the-parameter-is-unknown`, `the-parameter-has-the-wrong-type`, …): el cliente corrige el valor y lo vuelve a mandar. Un nombre que un arranque no admitiría es también aquí `script-not-admitted`, así que describir nunca es una forma de mirar fuera del directorio de los scripts.

#### Una ejecución cuya configuración suministra un controlador

Los valores no tienen por qué viajar en la petición: la ejecución puede nombrar un **proveedor** y las claves que quiere, y el servicio se las pide al controlador que registró ese proveedor ANTES de lanzar nada. El controlador es un cliente del mismo socket, y su sesión son cuatro operaciones, cada una una línea, cada una en su propia conexión:

```json
{"schema":"pipelinek.service/2","op":"config.provider.register","provider":"vault","keys":[{"name":"BUILD_PROFILE","type":"string","sensitivity":"non-secret"}],"auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"sessionId":"8c3f…","leaseMillis":15000}}
{"schema":"pipelinek.service/2","op":"config.provider.poll","sessionId":"8c3f…","waitMillis":5000,"auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"state":"asked","requestId":"b71d…","keys":["BUILD_PROFILE"]}}
{"schema":"pipelinek.service/2","op":"config.provider.answer","sessionId":"8c3f…","requestId":"b71d…","revision":"r7","values":{"BUILD_PROFILE":"release"},"auth":"…"}
{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts","idempotencyKey":"order-42","configurationProvider":{"provider":"vault","keys":["BUILD_PROFILE"]},"auth":"…"}
```

`config.provider.close` termina la sesión. Todas exigen el derecho de ARRANCAR ejecuciones, porque una sesión es la manera en que un arranque consigue sus valores. El servicio nunca llama al controlador: el controlador **consulta**, reteniendo una llamada como máximo `waitMillis` (el servicio la acota), y esa consulta es también lo que renueva el arrendamiento de la sesión. Una sesión que no se renueva caduca, y una ejecución que la esperaba se rechaza.

Un rechazo de cualquier operación de la sesión lleva el tipo `configuration-provider-refused` y un motivo; un arranque que no pudo conseguir sus valores se rechaza con `run-configuration-refused` y un motivo, y suelta la clave, así que el cliente puede repetir la petición con la misma clave cuando el controlador vuelva. Lo que el servicio promete sobre la respuesta:

- **Exactamente las claves pedidas, con el tipo declarado.** Una respuesta con una clave que nadie pidió, o sin una que se pidió, o con un valor que no es un `string`, un `integer` o un `boolean` según lo declarado, se rechaza al controlador (`answer-key-unexpected`, `answer-key-missing`, `answer-value-wrong-type`) Y hace fallar la ejecución que preguntó (`provider-answer-refused`): a un controlador no se le da un segundo intento con otro valor.
- **Ningún secreto es configuración.** Una clave declara una sensibilidad, y la única que el servicio acepta es `non-secret`: una clave etiquetada como secreta se rechaza al registrar la sesión (`a-secret-is-not-configuration`), y una etiqueta que el servicio no conoce no se lee como `non-secret` (`sensitivity-label-unknown`). La etiqueta es una declaración y no un detector — vale la misma advertencia de antes sobre lo que el controlador decide enviar.
- **Sin sustituto.** El nombre de un proveedor pertenece a la credencial que lo registró, y un duplicado vivo se rechaza (`provider-name-taken`). Una ejecución pregunta a la sesión de SU credencial y a ninguna otra: si esa sesión no está, la ejecución se rechaza (`provider-not-registered`), y si se cierra mientras la ejecución espera, el rechazo es inmediato (`provider-gone`). Un controlador que calla es un `provider-timeout` pasado un plazo de 10 segundos, y no se arrancó ninguna ejecución.
- **Una instantánea por ejecución.** La respuesta se mezcla por debajo de la `configuration` explícita de la petición, pasa por las mismas reglas, y la ejecución conserva exactamente esa instantánea. La instantánea gana una `provenance` — el proveedor, su revisión, el resumen de lo que dio la fuente y el origen y el tipo de cada clave — y la ejecución comprueba que el resumen que lleva es el resumen de sus valores.

Todo ello se puede preguntar después, sin un solo valor:

```json
{"schema":"pipelinek.service/2","op":"run.configuration","runId":"34b28ddc-…","auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"runId":"34b28ddc-…","digest":"…","precedence":["request","provider"],"entries":[{"name":"BUILD_PROFILE","origin":"provider","type":"string"}],"provider":"vault","revision":"r7","sourceDigest":"…"}}
```

`run.configuration` es una lectura de la ejecución, con la misma autorización que `run.state`, y responde desde la instantánea que la propia ejecución lee — no hay un segundo almacén que se desvíe. Un proveedor que no intervino deja fuera `provider`, `revision` y `sourceDigest`. El servicio guarda como máximo cuatro sesiones, para que las consultas que esperan no llenen los hilos que contestan las preguntas; un quinto registro se rechaza (`too-many-provider-sessions`). El cliente tipado tiene un método por operación (`registerProvider`, `pollProvider`, `answerProvider`, `closeProvider`, `runConfiguration`) y `startRun` acepta la referencia al proveedor.

La configuración es la capa **más baja** del ambiente: el `environment { }` de un stage se compone encima y el ámbito `withEnv` encima de ese, y gana el ámbito más específico. Los valores llegan a los pasos por el ambiente que la ejecución ya compone, así que no hay ninguna regla de precedencia nueva que aprender.

#### Una ejecución cuyas credenciales conserva un controlador

Los valores de arriba no eran secretos. Una credencial sí lo es, y el servicio no quiere custodiarla: un servicio que guarda un secreto lo entrega por un socket a la ejecución que lo pide, y el operador preferiría que ese secreto nunca llegara. El controlador que arranca la ejecución puede conservarlo, y el servicio lo pide en el momento en que la ejecución lo necesita.

```bash
pipelinek serve --db ./state/pipelinek.db --control-root ./state/durable --socket ./state/pipelinek.sock --credential-sessions
```

`--credential-sessions` es una OPCIÓN, y cambia lo que el servicio custodia: con ella el servicio NO abre el almacén local de credenciales en absoluto —ni el fichero, ni la frase de paso—, así que la única fuente de una credencial es el controlador que registró la sesión de la ejecución. Sin ella el almacén es la única fuente, las cuatro operaciones de la sesión se rechazan por nombre (`credential-sessions-not-composed`), y un arranque que nombra un proveedor de credenciales se rechaza antes de convertirse en una reclamación. La alternativa considerada y rechazada: abrir ambos y preferir el controlador —haría que un controlador que ya no está se sustituyera, en silencio, por un secreto en disco, que es exactamente lo que esta capacidad promete no hacer—.

La sesión la forman cuatro operaciones, como la de configuración, y el controlador **consulta** las peticiones:

```json
{"schema":"pipelinek.service/2","op":"credential.provider.register","provider":"controller","auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"sessionId":"c1d2…","leaseMillis":15000}}
{"schema":"pipelinek.service/2","op":"credential.provider.poll","sessionId":"c1d2…","waitMillis":5000,"auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"state":"asked","requestId":"a91f…","correlationId":"34b2…","credentialId":"deploy-key","kind":"string"}}
{"schema":"pipelinek.service/2","op":"credential.provider.answer","sessionId":"c1d2…","requestId":"a91f…","decision":"grant","value":"czNjcjN0…","auth":"…"}
{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts","idempotencyKey":"order-42","credentialProvider":"controller","auth":"…"}
```

`credential.provider.close` termina la sesión. El valor viaja en Base64 y está acotado a 32 KiB. Las cuatro operaciones exigen el derecho de ARRANCAR ejecuciones, porque una sesión es la manera en que una ejecución consigue sus credenciales. Lo que el servicio promete sobre la respuesta:

- **La ejecución la pide cuando la necesita, y sólo entonces.** La ejecución no custodia ningún secreto hasta que entra en `withCredentials`; la petición queda atada a la ejecución que preguntó, a la credencial que la arrancó y a la credencial que el operador le concedió.
- **Una respuesta por petición, y sólo por la sesión que la recibió.** Una segunda respuesta a la misma `requestId` se rechaza (`request-unknown`), y también una respuesta de otra credencial o de otra sesión (`provider-session-unknown`): el nombre del proveedor pertenece a la credencial que lo registró, y nadie más puede contestar lo que se le preguntó.
- **Sin sustituto, nunca.** Un controlador que lo niega hace fallar la ejecución con `credential-provider-denied`; uno que cierra su sesión mientras la ejecución espera se rechaza de inmediato (`provider-gone`); uno que calla es un `provider-timeout` pasado ocho segundos. En ningún caso se busca la credencial en el almacén local ni en otro proveedor.
- **El secreto viaja por el socket privado y no se escribe en ningún otro sitio.** Está en la línea de la petición de la respuesta, en el socket del intermediario de la ejecución y en el ambiente del paso que lo pidió —y en ningún otro lugar: ni en el almacén de los hechos, ni en el plano de salida, ni en la observación de la ejecución, ni en el argv del hijo—. El servicio nunca lo imprime: el tipo que lo lleva redacta su propio `toString`.

Ninguno de los dos perfiles separa dos procesos del mismo usuario, y las sesiones de credenciales no cambian eso: lo que separa identidades es el TOKEN que el operador emitió, y la JVM no ve el UID del par de un socket Unix. La sesión añade una regla por encima —una sesión pertenece a la credencial que la registró, así que un cliente que presenta otra no puede registrar el nombre, ni recibir lo que se preguntó, ni contestar la petición—.


La clave es lo que hace segura un reintento: la misma clave con la misma petición responde con la MISMA ejecución, y la misma clave con otra petición es un conflicto (`idempotency-conflict`). La promesa es duradera mientras el servicio pueda escribir su fichero —el servicio guarda el registro de los arranques en el fichero que posee junto al socket, `starts`—, así que un cliente que reintenta después de un reinicio recibe la ejecución que ya había arrancado. La RECLAMACIÓN de una clave se escribe ANTES de lanzar al hijo, y una reclamación que no se puede escribir no es una reclamación: el arranque se rechaza por nombre (`run-start-refused`, con el motivo `the-start-cannot-be-recorded`) y no se lanza ningún hijo, porque un arranque que no se puede recordar es un arranque que no se puede prometer. Lo que degrada cuando el disco se niega a escribir el RESULTADO es el REGISTRO de una ejecución que ya arrancó: el registro se queda en memoria, al cliente se le contesta igualmente con la ejecución que arrancó, y la degradación se dice en voz alta en el log del servicio —qué arranque fue, qué ejecución está en juego y qué tiene que hacer el operador—. La clave NUNCA se arranca dos veces: la reclamación de la clave está en el disco, y una reclamación no es una clave libre, así que un servicio que reinicia después de ese fallo recupera la clave RECLAMADA, con efecto desconocido, y el reintento se rechaza en vez de arrancar una segunda ejecución.

El hijo es un proceso, lanzado por el puerto que ya existe para lanzar procesos, y es el mismo `pipelinek run` que el operador escribiría en la línea de órdenes. La ejecución escribe en el mismo almacén y el mismo control root que el servicio lee, así que una ejecución arrancada por el socket es una ejecución sobre la que `run.state`, `events.follow` y `output.follow` pueden responder — y sobrevive a la conexión que la pidió.

Una caída es la otra cara de la misma promesa. La reclamación de una clave está en el disco ANTES de lanzar al hijo, así que un servicio que murió en esa ventana deja una clave cuyo efecto es DESCONOCIDO: la ejecución puede seguir viva, y el servicio no puede decir cuál. Un reintento con esa clave se rechaza por nombre (`run-start-refused`, con el motivo `the-effect-of-this-key-is-unknown`) y no se arranca una segunda ejecución —la clave promete una petición, y el servicio no adivina cuál de las dos sería—. El rechazo lleva la salida: nombra la correlación del arranque perdido, que es lo que permite encontrar la ejecución, y las operaciones que la reconcilian sobre el sustrato —`runs cancel` cuando la ejecución sigue ahí, y `runs recover` cuando su trabajo terminó sin terminal—. Reconciliar esa ejecución es cosa del operador. La CLAVE queda reclamada mientras el servicio viva, y ningún verbo la libera: soltarla es cosa del operador, sobre el registro de los arranques que el servicio posee.

#### Cancelar una ejecución

El servicio también puede CANCELAR una ejecución. Es una quinta puerta con un derecho propio, porque cerrar una ejecución no es una pregunta sobre una ejecución: el ámbito de un cliente que puede cancelar lo dice con sus propias palabras.

El derecho crece con el sufijo `+cancel`, y se compone con `+start`: `all+start+cancel` puede leer todas las ejecuciones, arrancar ejecuciones y cancelarlas. Un ámbito que no nombra ninguna ejecución no puede llevar el derecho a cancelar —no hay nada que cancelar— y el servicio se niega a leer un ámbito así. Un cliente sin derecho a cancelar se rechaza por su nombre (`cancel-not-authorized`), y el derecho se pregunta ANTES que el ámbito, así que un cliente que no puede cancelar nada no aprende nada de las ejecuciones que existen.

```json
{"schema":"pipelinek.service/2","op":"run.cancel","runId":"34b28ddc-…","reason":"the operator changed their mind","auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"runId":"34b28ddc-…","outcome":"cancelled","terminalAtMs":1730000000000}}
```

Cancelar CIERRA la ejecución: el hijo acaba, el hecho terminal de la ejecución se escribe en los hechos durables, y la ejecución queda terminal para todo lector posterior. El orden es el contrato —el hijo ya está muerto ANTES de que se escriba el terminal, porque solo el dueño de una ejecución puede escribir su terminal, y el dueño de una ejecución viva es su hijo—.

La respuesta es el terminal de la ejecución, y es DURABLE cuando la respuesta viaja: el hecho está confirmado antes de que el servicio conteste, así que un cliente que lee la respuesta y después pregunta por la ejecución lee el mismo terminal, y nunca un EOF donde el producto prometió un hecho.

Cancelar dos veces no es un error. La segunda llamada contesta `already-cancelled` con el MISMO terminal, porque una ejecución se cierra una vez y no dos. Una ejecución que ya alcanzó un terminal propio no se cancela otra vez, y una ejecución que nadie conoce se rechaza por su nombre: ambas son `run-cancel-refused`, y llevan el motivo (`the-run-is-already-terminal`, `the-run-is-unknown`).

Una cancelación nunca cruza una continuidad VIVA: el servicio no toma la continuidad de una ejecución que otro proceso está publicando, y el rechazo nombra a quien la tiene (`the-lease-is-held-by-another`). Por eso el hijo se acaba primero —el hijo ES quien la tiene, y quien está muerto se releva—, y eso es lo que hace que la cancelación sea la RECONCILIACIÓN de una ejecución cuyo escritor murió sin decir nunca cómo terminó. Un escritor que sigue VIVO no lo reconcilia el verbo: tiene el cerrojo, el rechazo lo nombra, y la ejecución se libera sólo cuando ese proceso termina.

Un servicio que no puede cancelar ejecuciones abre el sustrato en SOLO LECTURA y lo dice al arrancar (`substrate read-only`). Un servicio que sí puede las abre en lectura-escritura, porque el terminal de una ejecución cancelada es un hecho y el servicio es quien lo escribe.

El fichero del socket se borra cuando el servicio para. Un socket que dejó una caída se reconoce por lo que es y se reemplaza en el siguiente arranque; un socket en el que ya escucha otro servicio no se desplaza nunca, y una ruta que no sea un socket no se borra nunca.

#### Responder una pregunta de una ejecución

Una ejecución que llega a `input("…")` PUBLICA una pregunta y espera. El servicio la muestra y recibe la respuesta, como una sexta y una séptima puerta con UN derecho propio: `+answer`. Listar y responder están cerradas a un cliente sin él, porque ver una pregunta es ver lo que el pipeline está a punto de hacer.

```json
{"schema":"pipelinek.service/2","op":"inputs.list","runId":"34b28ddc-…","auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"inputs":[{"opId":"34b28ddc-…-s0-0","message":"Deploy to production?","ok":"Proceed","publishedAtMs":1730000000000}]}}
{"schema":"pipelinek.service/2","op":"input.answer","runId":"34b28ddc-…","opId":"34b28ddc-…-s0-0","decision":"proceed","auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"runId":"34b28ddc-…","opId":"34b28ddc-…-s0-0","decision":"proceed","outcome":"answered"}}
```

La decisión es `proceed` o `abort`, y significa lo que significa en el pipeline: `proceed` deja que se ejecute el cuerpo del paso, `abort` cierra el paso y el cuerpo nunca se ejecuta. El derecho crece con el sufijo `+answer` y se compone con los demás (`all+start+cancel+answer`); un ámbito que no nombra ninguna ejecución no puede llevarlo. Un cliente sin el derecho se rechaza por su nombre (`answer-not-authorized`), y el derecho se pregunta ANTES que el ámbito, así que un cliente que no puede responder nada no aprende nada de las ejecuciones que existen.

QUIÉN respondió es un hecho de la credencial y nunca una afirmación de la petición. La petición no lleva ningún nombre: el servicio escribe, como `submitter` del historial propio de la ejecución, la etiqueta de la autorización a la que pertenece el token. El `declaredSubmitter` que una pregunta puede mostrar es lo que el autor del pipeline ESCRIBIÓ en el paso; dice a quién espera y no autoriza a nadie.

Una pregunta tiene UNA respuesta. Dos clientes que responden a la vez no hacen que la ejecución continúe dos veces: una se registra y la otra se rechaza (`input-answer-refused`, motivo `the-question-was-already-answered`). Repetir la misma respuesta con la misma autorización contesta `already-answered`. Los demás motivos son `the-run-is-unknown`, `the-run-is-already-terminal`, `the-question-is-unknown` y `the-question-expired`, y cada uno es un rechazo y nunca un éxito silencioso.

El estado vive en el disco, junto a la ejecución, así que sobrevive a un reinicio del servicio y a una desconexión del cliente: el servicio nuevo lista la pregunta que sigue esperando y recibe la respuesta una vez, y una pregunta ya respondida no se vuelve a hacer.

Lo que esta puerta NO hace, dicho una vez: el canal entre el servicio y la ejecución es un directorio de la raíz de control, así que cualquier proceso del mismo usuario con escritura sobre ella aún puede responder; una JVM no puede leer el usuario del par de un socket, y el servicio separa las identidades que el operador EMITIÓ, no los procesos de un usuario. El `declaredSubmitter` no es una lista de respondedores que se haga cumplir. Las preguntas publicadas por una compilación anterior (sin `runId`) y los ficheros de más de 16 KiB son invisibles, lo que falla cerrado.

#### Quién puede preguntar, y cuál es la frontera de verdad

El servicio emite una credencial la primera vez que arranca y la guarda en el fichero que posee junto al socket, `authorizations`, con modo `0600`. Una autorización por línea — un token, una etiqueta y un ámbito:

```text
<token> <etiqueta> <ámbito>
```

El ámbito es `all`, `none`, o las ejecuciones que cubre, y puede llevar los sufijos `+start`, `+cancel` y `+answer` cuando el cliente además puede arrancar ejecuciones, cancelarlas y responder sus preguntas. La etiqueta es para el operador y **nunca viaja**: una etiqueta que viaja se puede enumerar con un cliente que va probando tokens hasta que uno pasa.

Una línea puede terminar con un cuarto campo, `credentials=<id>:<tipo>,...`, que nombra las credenciales que las ejecuciones arrancadas por ese token pueden pedir. El tipo es la FORMA en la que la credencial puede entregarse: `string` (un secreto), `file` (un fichero y el nombre que tenía), `private-key` (una clave y la frase de paso que puede llevar), `keystore` (un almacén de claves y la contraseña que puede llevar) y `archive` (un archivo con varias entradas). El PERMISO dice la forma y la petición no: una ejecución pide una credencial por su nombre y la autorización dice cómo puede viajar, así que a un controlador nunca se le pide una forma que el operador no concedió. Una credencial que no es de la forma que nombra su permiso se rechaza (`credential-kind-not-brokered`) en vez de entregarse de otra manera. El campo es la lista blanca del broker que recibe cada ejecución: una ejecución no custodia ningún secreto al arrancar y pide uno sólo cuando entra en `withCredentials`, por un socket privado bajo `brokers/` junto al socket del servicio. Una credencial que la línea no nombra se rechaza por su nombre (`credential-not-granted`) aunque el almacén local la tenga, y una ejecución que arrancó con el broker nunca sustituye al almacén local.

Dos perfiles, y cada uno dice en voz alta, al arrancar el servicio, qué promete:

- **`trusted-local`** (el de por defecto) guarda la autorización en ese fichero, y el fichero lo puede leer cualquier proceso del mismo usuario. Lo que protege es el **sistema de ficheros**: nada fuera de ese usuario puede leer la autorización ni abrir el socket. Lo que **no** protege es la autorización frente a otro proceso del mismo usuario.
- **`private-auth`** lee la autorización de la **entrada estándar** del servicio y no la escribe en ningún sitio, así que no hay fichero que otro proceso del mismo usuario pueda leer. Lo que protege es el **disco**: ningún fichero lleva la autorización. Lo que **no** protege es la autorización frente a un proceso del mismo usuario que lea la entrada estándar del servicio mientras arranca.

Ninguno de los dos perfiles separa dos procesos del mismo usuario, y el servicio también lo dice: la JVM no expone las credenciales del par de un socket de Unix, así que nada en esta build puede saber quién se conectó. Lo que una credencial separa son las *identidades que el operador nombra* —un cliente que presenta el token y otro que no— y no dos procesos que corren con el mismo usuario. Léelo como una frontera sobre el **servicio**, no sobre los **datos**: el mismo sustrato se puede leer desde la línea de órdenes, sin ninguna autorización.

Los dos perfiles son perfiles de **autenticación**: se diferencian en de dónde sale la autorización y en nada más. Ninguno de los dos es un perfil de aislamiento, y ninguno lleva un nombre que lo parezca. La palabra `isolated` pertenece a una separación que hay que **demostrar** —una ejecución que pide un entorno aislado sólo se admite contra lo que el proveedor atestigua— y a la opción `--isolated` del verbo `run`, que nombra un workspace scratch gestionado y ninguna frontera. Un perfil que lee una autorización de un sitio no demuestra ninguna separación de procesos, ficheros ni sockets.

El perfil que `0.96.0` nombraba `isolated` se llama ahora **`private-auth`**, y la retirada es una ruptura deliberada de las palabras que sirve este producto, registrada con su evidencia en [`pipelinek/contract/published-contract-exceptions.json`](pipelinek/contract/published-contract-exceptions.json). Un servicio al que se le pide `--trust-profile isolated` se rechaza por su nombre (`trust-profile-unknown`), y la línea de uso nombra las dos palabras que sirve esta build: `trusted-local` y `private-auth`.

Las dos fuentes se leen bajo un cupo declarado: **diez mebibytes** (10 485 760 bytes), el cupo con el que el producto lee cualquier fichero. Un fichero de autorizaciones que no cabe se rechaza por su nombre, con el cupo y el tamaño que tiene, y nada de su contenido se lee en la memoria del servicio —el fichero se mide antes de leerse—. La entrada estándar se lee un byte más allá del cupo y ni uno más, así que un flujo que no cabe se rechaza igual y nunca se lee entero.

El servicio sostiene dieciséis conexiones a la vez y sirve ocho suscripciones a la vez, y los dos presupuestos son distintos a propósito: una suscripción ocupa su conexión mientras dura, mientras que una pregunta la ocupa por una línea. Con un solo presupuesto, dieciséis suscriptores podrían quedarse con todas las ranuras y dejar sin respuesta `version` y `health` —las dos preguntas que dicen si el servicio está vivo—. Una suscripción por encima del presupuesto se rechaza por su nombre (`too-many-subscriptions`).

#### Hablar con el servicio desde un programa

El vocabulario del cable es un **artefacto publicado**: `pipelinek-service-client` lleva las peticiones, los rechazos, el códec y un cliente tipado, así que un consumidor no escribe una copia de nada de eso.

```kotlin
val client = ServiceClient(Path.of("/run/pipelinek/service.sock"), token, Duration.ofSeconds(10))

when (val answer = client.runState("34b28ddc-…")) {
    is ServiceAnswer.Answered -> println(answer.value)   // un RuntimeObservation
    is ServiceAnswer.Refused -> println(answer.refusal.kind)
    is ServiceAnswer.Unreachable -> println("no hay ningún servicio")
    is ServiceAnswer.Unreadable -> println("habla otro protocolo")
    ServiceAnswer.TimedOut -> println("no respondió a tiempo")
}
```

El token es OBLIGATORIO: un cliente que se pudiera construir sin él conectaría, escribiría una línea y recibiría `not-authorized`, y un cliente que no puede funcionar no debería compilar.

`runState` devuelve el mismo `RuntimeObservation` que la línea de órdenes renderiza, así que la respuesta que lee un cliente y la línea que ve un operador son el mismo hecho.

`startRun` pide al servicio arrancar una ejecución y devuelve su identidad —el identificador, la correlación, el propietario y el momento en que tomó su continuidad— como un `ServiceRunStart`:

```kotlin
val started = client.startRun("build.kts", workspace = "scratch", idempotencyKey = "order-42")
```

La clave es lo que hace segura un reintento: la misma clave con la misma petición responde con la misma ejecución, y la misma clave con otra petición se rechaza con `idempotency-conflict`. El cliente no posee la ejecución: la respuesta es la identidad de una ejecución que sigue viva después de que se cierre la conexión.

`startRun` también acepta la configuración de la ejecución, y el `ServiceRunStart` devuelto lleva la configuración que la ejecución TENDRÁ:

```kotlin
val started = client.startRun(
    "build.kts",
    idempotencyKey = "order-42",
    configuration = mapOf("BUILD_PROFILE" to "release"),
)
println(started.configuration) // mapOf("BUILD_PROFILE" to "release")
```

El cliente **compara** la configuración devuelta con la que mandó, y una diferencia es una respuesta `unreadable`, no una ejecución que tiene en silencio otra configuración. La misma clave con otra configuración es un `idempotency-conflict`: la huella de una petición cubre su configuración, así que una clave nombra una petición y no un guion.

La línea de órdenes también puede hablar con un servicio, con `--endpoint`:

```bash
pipelinek inspect --state --endpoint ./state/pipelinek.sock 34b28ddc-…
```

`--endpoint` lo admite **un** modo de **un** verbo: `inspect --state`, la única superficie que esta CLI publica y que el servicio responde con la misma renderización. Cualquier otro verbo rechaza la opción **por su nombre**, y no hay alternativa: un servicio que no está se reporta como que no está, nunca se responde desde el almacén local.

La línea de órdenes lee el token de la variable `PIPELINEK_SERVICE_TOKEN`, o del fichero que el servicio posee junto a su socket, o del fichero que nombra `--authorization-file`. Nunca lo imprime: un secreto que esta CLI imprime acaba en un scrollback, en un log y en el historial de la shell.

El plazo del cliente cierra la conexión que abrió y no toca nada más: las preguntas que el servicio responde son de solo lectura, así que un cliente que se va deja la ejecución exactamente como estaba. Una ejecución que el cliente ARRANCÓ sigue viva: la respuesta es la identidad de una ejecución, no su dueño.

#### Lo que cuesta

El servicio ahorra el coste de PREGUNTAR, no el de EJECUTAR. Una pregunta por el socket la responde un proceso que ya está en marcha, así que paga una conexión y una línea; un verbo de la línea de órdenes paga el arranque de una JVM cada vez. Una ejecución que el servicio arranca sigue costando una JVM, porque el servicio lanza el mismo `pipelinek run` que escribirías tú: el socket abarata las preguntas sobre las ejecuciones y evita que quien pregunta sea dueño de la ejecución, pero no la hace más rápida.

Los huecos de `--max-supervised-runs` vuelven cuando el hijo ha SALIDO, un instante después de que su terminal pueda leerse. Un cliente que arranca la siguiente ejecución en cuanto ve el terminal de la anterior puede recibir `too-many-supervised-runs`: ese rechazo significa «lleno por ahora», y volver a preguntar es la respuesta.

Lo que el servicio retiene no crece con el número de peticiones ni de ejecuciones que ha atendido, y cada techo tiene su razón. Los hilos de trabajo son dos pools fijos, uno para las preguntas y otro para los arranques y las cancelaciones, cada uno tan grande como las conexiones que el servicio admite a la vez (`too-many-connections` más allá). La memoria residente de la JVM sube mientras se tocan por primera vez las páginas de su generación joven, y hasta dónde llega depende del heap que la JVM dimensiona a partir de la memoria de la máquina; lo que sigue vivo tras una recolección es plano. Los descriptores sobre el almacén de eventos son las conexiones del pool más las lecturas servidas en el mismo instante: SQLite conserva el descriptor de una conexión que se cerró mientras otra conexión del mismo proceso mantiene el fichero abierto en modo WAL, lo reutiliza para la siguiente y los suelta todos cuando el servicio se para. El techo lo fija, por tanto, el máximo de lecturas que llegaron a ser concurrentes, no cuántas se hicieron.

`scripts/bench-service-cost.py` mide ambos costes en la máquina que tiene delante —el arranque de la línea de órdenes frente a una petición por el socket, `run.start` y una ejecución hasta su terminal, y la memoria, los hilos y los descriptores del servicio antes y después de las ejecuciones, y lo que queda al pararlo— y escribe cada muestra en un informe JSON junto al hardware, el JDK y la revisión en que corrió. Es una medición y no una puerta: no fija ningún umbral, y las cifras son las de la máquina que las produjo.

### Arrancar el servicio desde una instalación

Una instalación es un directorio con el lanzador en `bin/` y los jars en `lib/`. `scripts/install-pipelinek.sh` deja cada release bajo `$PIPELINEK_HOME/versions/<release>` (por defecto `~/.local/share/pipelinek`) y apunta `current` a la que está en uso, así que el lanzador de la instalación en uso es `~/.local/share/pipelinek/current/bin/pipelinek`. El ZIP no lleva el bit de ejecución —un ZIP es un contenedor de nombres y bytes, no de modos— y el instalador lo pone en el fichero que instala.

El servicio necesita tres rutas y no tiene ninguna por defecto. Dale al socket un directorio propio, y deja el estado fuera de la instalación:

```bash
mkdir -p ./state/socket && chmod 700 ./state/socket
~/.local/share/pipelinek/current/bin/pipelinek serve \
  --db           ./state/pipelinek.db \
  --control-root ./state/durable \
  --socket       ./state/socket/pipelinek.sock
```

| Ruta | Qué es | Modo |
|---|---|---|
| `./state/socket/pipelinek.sock` | el socket al que preguntan los clientes | `rw-------` |
| `./state/socket/authorizations` | los tokens que sirve el servicio, uno por línea | `0600` |
| `./state/socket/brokers/<lease>/broker.sock` | el socket del broker de UNA ejecución | `rw-------` |

**El directorio del socket es la frontera, y el modo del socket no es toda la frontera.** La JVM enlaza un socket de Unix con el modo del proceso —medido en esta máquina, `0755`— y el servicio lo estrecha a `rw-------` justo después del enlace. La ventana entre los dos la cruza nadie, porque el directorio no se puede recorrer: el servicio lo crea `rwx------` cuando no está, y se niega a arrancar cuando está y concede algo al grupo o a los demás. Un socket que no se puede estrechar es un fallo al arrancar y no un aviso.

**Lo que el servicio no crea.** No crea el sustrato: un `--db` que no está se rechaza por su nombre, en vez de dejar un fichero vacío donde el operador guardaba sus hechos. Tampoco crea el directorio de control: lee el estado durable de las ejecuciones donde el operador le dice.

**Pararlo.** SIGINT (Ctrl-C) y SIGTERM lo paran limpiamente: el socket desaparece, y las autorizaciones y el resto del estado se quedan, porque son ficheros del operador. Medido: un SIGKILL deja el fichero del socket detrás, y el siguiente arranque lo encuentra, ve que nadie contesta en él y lo borra. Una salvedad, que conviene saber antes de dejar el servicio en segundo plano: un shell que ignora SIGINT —un shell no interactivo lo ignora para los trabajos que arranca en segundo plano— transmite esa disposición a la JVM, y una JVM no captura una señal que ya venía ignorada al arrancar. En ese caso Ctrl-C no hace nada, y la señal que lo para es SIGTERM.

**Los dos sockets.** El socket del servicio responde a las preguntas de los clientes. El socket del broker responde a UNA ejecución: se crea cuando una ejecución entra en `withCredentials`, bajo `brokers/` junto al socket del servicio, en un directorio propio que es `rwx------`, con el token de la sesión en un fichero propio que es `0600`. Los dos son sockets que enlaza la JVM —`0755` al nacer, estrechos por el producto— y los dos son la misma clase de frontera: el token separa las identidades que el operador NOMBRA, y no dos procesos del mismo usuario, porque la JVM no expone las credenciales del par de un socket de Unix.

**El token de la instalación.** El servicio escribe el fichero de autorizaciones que sirve junto a su socket cuando ese fichero no está, y lo estrecha a `0600` cuando está. El token que presenta un cliente es la primera palabra de su primera línea. La línea de órdenes lee el token de `PIPELINEK_SERVICE_TOKEN`, del fichero junto al socket o del fichero que nombra `--authorization-file`, y nunca lo imprime.

### El perfil OCI

La distribución es también un contenedor, y el contenedor es un EMPAQUETADO de ella: `pipelinek/pipeline-application/oci/Containerfile` lleva el ZIP que descarga quien opera, desempaquetado, y `scripts/build-oci-image.sh` pide a un runtime de contenedores que lo construya. Nada de la construcción ordinaria llama a ese script, y el producto funciona sin runtime de contenedores.

| | |
|---|---|
| usuario | `1000:1000` (`pipelinek`), nunca root |
| home | `/var/lib/pipelinek`, el dueño del estado |
| socket | `/var/lib/pipelinek/socket/pipelinek.sock`, `rw-------` |
| tokens | `/var/lib/pipelinek/socket/authorizations`, `0600` |
| control root | `/var/lib/pipelinek/control` |
| sustrato | `/var/lib/pipelinek/state/store.db` |

**Por qué el cliente usa el mismo uid.** El servicio estrecha su socket a `rw-------`, y la JVM no expone las credenciales del par de un socket de Unix: el modo del socket y el token son las dos mitades de una misma frontera, y ninguna basta sola. Un cliente del mismo entorno se ejecuta con el MISMO uid y presenta el token que lee del fichero que está junto al socket. Medido en esta máquina con podman rootless: un contenedor cliente con el mismo uid leyó el estado de una ejecución por el socket, y un contenedor cliente con otro uid fue rechazado — no pudo ni abrir el socket ni leer el token. Un cliente que se ejecuta como root dentro del contenedor no lo detiene el modo, porque root tiene las capacidades que lo ignoran.

**Los volúmenes tienen nombre, y eso es lo que los hace rootless.** Un runtime rootless mapea los directorios del host dentro del contenedor a través de un espacio de nombres de usuario, y un directorio del host llega con un uid con el que el servicio no puede escribir. Un volumen CON NOMBRE lo crea el runtime DESDE la imagen, así que llega con la propiedad y los modos declarados arriba.

**Lo que tiene que estar ahí antes de arrancar el servicio, en este orden.** Los tres volúmenes con nombre, y luego el contenedor: el entrypoint de la imagen PREPARA el sustrato antes de servirlo, así que un primer arranque contra volúmenes vacíos arranca, y no hay ningún paso que hacer a mano.

```bash
# 1. los tres volúmenes CON NOMBRE
podman volume create pipelinek-socket
podman volume create pipelinek-control
podman volume create pipelinek-state

# 2. el contenedor: PREPARA el sustrato antes de servirlo
podman run -d --name pipelinek-service \
  -v pipelinek-socket:/var/lib/pipelinek/socket \
  -v pipelinek-control:/var/lib/pipelinek/control \
  -v pipelinek-state:/var/lib/pipelinek/state \
  localhost/pipelinek:0.97.0
```

**El servicio sirve un sustrato; no lo crea.** La preparación es un paso PROPIO —`pipelinek substrate init --db <path>`— y el contenedor lo da antes de pedirle al servicio que sirva: el entrypoint de la imagen ejecuta ese verbo y luego le pasa el relevo al lanzador. Fuera de un contenedor el mismo paso se da a mano, y su resultado se puede leer: la ruta, si el sustrato se creó o ya estaba, y las tablas que lleva. El servicio sigue negándose a arrancar con un sustrato que no está y lo dice —`the substrate cannot be served: the store is not there`— porque ese fichero es de la vía del productor. Una ejecución crea el almacén de su propia vía, y los datos de esa ejecución quedan después en el volumen:

```bash
podman run --rm \
  -v pipelinek-state:/var/lib/pipelinek/state \
  -v pipelinek-control:/var/lib/pipelinek/control \
  -v "$PWD":/scripts:ro,z \
  localhost/pipelinek:0.97.0 \
  run --db /var/lib/pipelinek/state/store.db \
       --control-root /var/lib/pipelinek/control \
       /scripts/my.pipeline.kts
```

**El cliente del mismo entorno** pregunta por el socket, y el token es el que el servicio escribió junto a él:

```bash
podman run --rm \
  -v pipelinek-socket:/var/lib/pipelinek/socket \
  localhost/pipelinek:0.97.0 \
  inspect <runId> --state --endpoint /var/lib/pipelinek/socket/pipelinek.sock
```

**Pararlo** envía SIGTERM, y el contenedor se comporta como la instalación: el socket se borra, las autorizaciones se quedan, y el arranque siguiente vuelve a enlazarlo y responde por las mismas ejecuciones, porque sus datos están en el volumen. Medido en esta máquina: la ejecución que leyó el cliente antes del reinicio es la que leyó después.

**En un sistema con SELinux en modo enforcing**, un directorio montado dentro del contenedor necesita la etiqueta (`:z` o `:Z`), o el contenedor no lee nada: un volumen con nombre no necesita etiqueta, porque lo crea el runtime. **Lo que el perfil no hace**: no monta ningún socket de Docker, no pide contenedor privilegiado ni red del host, y no arranca ningún orquestador. La imagen es un empaquetado, y el servicio que lleva dentro es el mismo proceso que corre una instalación.

### Telemetría

La telemetría es opcional, y lo que transporta son **tiempos, recuentos y buckets: nunca cargas útiles**. Ni el texto del script, ni la orden, ni la transcripción viajan en una señal: ese contenido vive en el plano de salida y en el journal, y una segunda copia en la telemetría sería un segundo sitio donde esconderse un secreto.

| | |
|---|---|
| Attributes | `run.id`, `stage.index`, `step.index`, `script.origin`, `failure.class` |
| Dimensions | `operation_kind`, `status`, `failure_class` |

Las convenciones son las de CI/CD de OpenTelemetry, fijadas en `1.44.0` y declaradas como candidata a release: el pin es parte de lo que el producto promete, y se lee del producto, no de la carga útil. No se exporta nada salvo `PIPELINEK_OTEL_ENABLED=true`, y el endpoint, el protocolo y las credenciales vienen del entorno: la misma build apunta a un Collector local, a Tempo, a Jaeger o a nada.

---

## Ecosistema de plugins

### Plugins oficiales (incluidos en la distribución)

| Plugin | Claves de paso | Para qué sirve |
|---|---|---|
| **SCM Git** | `scm-git.checkout` | Clonar un repositorio en el workspace, con la transcripción journalizada |
| **HTTP** | `http.request` | Lanzar una petición HTTP; necesita las capacidades `network.egress`, `http.transport` y `credentials.basic` |
| **JUnit** | `junit.results` | Leer informes JUnit XML y publicarlos como resultado de pruebas |
| **Utilities** | `core-utils.findFiles`, `core-utils.readJson`, `core-utils.readYaml`, `core-utils.sha256`, `core-utils.unzip`, `core-utils.zip`, `core-utils.writeJson`, `core-utils.writeYaml` | Ayudas de ficheros y de archivos para los scripts del pipeline |

En la distribución viajan además cuatro plugins que son ejemplos de trabajo del contrato: `example-uppercase-plugin`, `example-directive-plugin`, `example-block-plugin` y `example-saga-plugin`. Sus fuentes están en [`examples/`](examples/).

**El cupo de una lectura.** `core-utils.readJson` y `core-utils.readYaml` leen un documento bajo un cupo declarado: **diez mebibytes** (10 485 760 bytes), el mismo cupo con el que el paso `readFile` lee un fichero y el que el producto usa para cualquier fichero. Un documento que lo pase se rechaza con un fallo que nombra el cupo y el tamaño que tiene el fichero, y no se produce nada de su contenido: el fichero se mide ANTES de leerse, así que un documento que no cabe nunca llega a estar entero en memoria. Un documento que cabe se lee igual que antes.

### Ecosistema extendido

Estos proyectos se construyen alrededor de PipelineK. La **columna 0.97.0** es el rango de SDK que cada uno declara frente a la release vigente: un proyecto fijado a un SDK anterior no carga con ella.

| Proyecto | Qué es | Madurez | 0.97.0 |
|---|---|---|---|
| pipelinek-fabric | Plano de control distribuido: workers, proveedores de podman y Kubernetes. *El repositorio aún no es público* | Preview | Fijado a 0.62.0 |
| [pipelinek-studio](https://github.com/Rubentxu/pipelinek-studio) | UI web, servidor MCP por stdio y extensión de VS Code sobre un proceso real de PipelineK | Preview | Fijado a 0.48.0-rc1 |
| [pipelinek-policy](https://github.com/Rubentxu/pipelinek-policy) | Comprobaciones de política como plugin (`policy.check`) | Preview | Declara `[0.47.0, 0.49.0)` |
| [pipelinek-assurance](https://github.com/Rubentxu/pipelinek-assurance) | Comprobaciones de assurance como plugin | En desarrollo | Declara `[0.48.0, 0.52.0)`; su handler todavía no puede ejecutarse en vivo |
| [pipelinek-saga](https://github.com/Rubentxu/pipelinek-saga) | Contrato de saga para compensaciones | En desarrollo | Todavía no declara rango |
| pipelinek-fabric-backstage | Integración con Backstage para la Control API de Fabric. *El repositorio aún no es público* | En desarrollo | El cliente todavía no está sellado |
| [asdf-pipelinek](https://github.com/Rubentxu/asdf-pipelinek) | Plugin de gestor de versiones para `asdf` y `mise`: resuelve la última versión estable y verifica su SHA-256 | Preview | Resuelve 0.97.0 |

**Escala de madurez:** *Disponible* — publicado y soportado en la release. *Preview* — funciona de extremo a extremo, todavía en movimiento. *En desarrollo* — código real, contrato incompleto. *Exploratorio* — una idea con un spike.

Nada de lo anterior es una promesa de compatibilidad: un proyecto declara el rango de SDK con el que se construyó, y el runtime rechaza un plugin fuera de ese rango antes de que sus clases se carguen.

### Escribir un plugin

Un plugin es un build de Gradle independiente que compila contra el SDK publicado y contra nada más. Declara un `StepContract` — la clave de familia, el descriptor, los códecs tipados y las capacidades que requiere — y un manifiesto con el rango de API con el que se construyó.

El SDK viaja con cada release como `pipelinek-<version>.sdk.zip`: ocho artefactos (`pipelinek-domain`, `pipelinek-events`, `pipelinek-output`, `pipelinek-runtime-api`, `pipelinek-scripting-api`, `pipelinek-scripting-definition`, `pipelinek-service-client`, `pipelinek-sdk-bom`) y un BOM que fija sus versiones entre sí; 0.97.0 los publicó todos menos `pipelinek-scripting-definition`. `pipelinek sdk-info` imprime el grupo y las coordenadas exactas contra las que compila un plugin.

Un plugin se carga desde `--plugin-jar`. Con `--plugin-trust <file>` solo se admite si sus bytes coinciden con el SHA-256 fijado por el operador y llevan una firma Ed25519 válida; con `--require-plugin-trust`, un plugin que nadie ha avalado se rechaza antes de que corra su código.

---

## Pensado para desarrolladores, agentes y plataformas

**Para desarrolladores.** Escribe el pipeline en el editor que ya usas, ejecútalo en tu máquina en segundos y recibe un fallo tipado que nombra el stage. Una caché de scripts compilados hace que la segunda ejecución del mismo script sea rápida, y es privada por defecto: un directorio de caché del que no se puede probar la privacidad no se usa.

**Para agentes.** Un pipeline es un artefacto tipado y legible, y cada ejecución deja un rastro legible por máquina: eventos tipados, un cursor reanudable y un journal durable. Un agente puede leer una ejecución, decidir qué hacer después y recibir un rechazo con un motivo preciso cuando la respuesta no está.

**Para equipos de plataforma.** Un binario, sin demonio que se arranque solo, sin controlador y sin estado remoto. El runtime está pensado para ser embebido: un plugin declara las capacidades que necesita y el motor rechaza todo lo que no haya declarado. La red está detrás de una capacidad: un plugin que quiere alcanzarla declara `network.egress`, y un paso que la quiere la ha tenido que declarar.

---

## Flujos de trabajo reales

### CI local

El pipeline se ejecuta donde está el código. El bucle es: editar, validar, ejecutar, leer el fallo.

```mermaid
flowchart LR
    A[pipeline.kts] --> B[validate]
    B -->|compila y ejecuta el cuerpo| C[run]
    C --> D[journal durable]
    D --> E[inspect / events]
    E --> F[fallo tipado con su stage]
```

### Verificación con confianza

Un plugin traído de fuera solo se admite contra los bytes que fijaste, y el carril de eventos de una ejecución se puede comprobar contra un contrato que escribiste.

```mermaid
flowchart LR
    P[jar del plugin] --> T{SHA-256 fijado y<br/>firma Ed25519 válida?}
    T -->|sí| L[cargado desde una copia privada]
    T -->|no| R[rechazado antes de que corra su código]
    C[run] --> E[carril de eventos]
    E --> V[events verify --contract]
```

### Ejecución distribuida

**No está en este runtime.** `agent(remoteUri = …)` lo transporta el DSL y se rechaza en la resolución, porque aquí no hay un asignador remoto. La ejecución distribuida vive en **pipelinek-fabric**, un proyecto aparte con su propia línea de releases y cuyo repositorio aún no es público.

```mermaid
flowchart LR
    L[runtime local] -->|se pide un agente remoto| X[rechazado en la resolución]
    F[plano de control de Fabric] --> W[workers]
    W --> P[PipelineK en cada worker]
```

---

## Arquitectura de un vistazo

```mermaid
flowchart TB
    subgraph Script
        K[.pipeline.kts — DSL en Kotlin]
    end
    subgraph Motor
        C[Compilador y validador]
        R[Runner: stages, pasos, bloques]
        J[Journal durable — SQLite]
        E[Carril de eventos — tipado, reanudable]
    end
    subgraph Extensiones
        S[Plugins de paso]
        D[Plugins de directiva]
    end
    subgraph Fuera
        O[OpenTelemetry — opcional]
        CRED[Almacén de credenciales — local]
    end
    K --> C --> R
    R --> J
    R --> E
    S --> R
    D --> R
    R --> O
    R --> CRED
```

Cada frontera tiene un único dueño. El runner es la autoridad de escritura sobre una ejecución; el journal es el registro durable; el carril de eventos es la cara de lectura que un consumidor pagina por cursor; los plugins se admiten, no se confían por defecto.

- **Superficie de la CLI** — `pipelinek --help`
- **Contratos** — el KDoc de `StepContext.kt` (`:pipeline-step-sdk`), `RunTelemetry.kt` (`:pipeline-runtime-api`), `OutputReadPort.kt` (`:pipeline-output`) y `EventRecordReadPort.kt` (`:pipeline-events`)
- **Referencia de la API** — `./pipelinek/gradlew -p pipelinek apiReferenceHtml`
- **Procedencia de la release** — [RELEASE-SIGNING.md](RELEASE-SIGNING.md)

---

## ¿Por qué elegir PipelineK?

| Si necesitas… | PipelineK… | En lugar de… |
|---|---|---|
| Un pipeline que se pueda leer y comprobar con el compilador | …es un fichero Kotlin, compilado antes de ejecutarse | Un dialecto YAML propiedad de un proveedor |
| Feedback en segundos en tu propia máquina | …se ejecuta en local, un binario, sin servicio | Una cola de jobs remotos con su retardo |
| Un fallo sobre el que poder actuar | …nombra el stage, el paso y el resultado en eventos tipados | "exit code 1" al final de un log |
| Una ejecución auditable | …journaliza cada paso y ofrece un cursor reanudable | Un fichero de logs que nadie puede paginar |
| Extensibilidad sin forkear | …admite plugins por capacidad declarada y rango de API | Forkear el motor para añadir un paso |
| Ejecución distribuida | …**no** es esto: necesita Fabric, en su propia línea de releases | Suponer que un runner local planifica workers |

**Dónde complementa y no sustituye.** PipelineK no pretende ser Jenkins ni GitHub Actions: no tiene controlador, ni protocolo de agente, ni runners alojados. Su sitio natural es junto a ellos, como el motor local que ejecuta el mismo pipeline que ejecuta un desarrollador, y como el runtime que conduce una plataforma distribuida como Fabric.

**Las limitaciones que conserva.** Una ejecución por invocación. Sin planificación incorporada. Sin marketplace específico de proveedor. Sin garantía de builds idénticas en máquinas distintas. La asignación remota se rechaza en este runtime.

---

## Cómo participar

**Pruébalo.** [Descarga una release](https://github.com/Rubentxu/pipeline-kotlin/releases) y sigue los [primeros pasos](#primeros-pasos). El instalador multi-versión mantiene varias releases en paralelo:

```bash
curl -fL -o install-pipelinek.sh \
  "https://raw.githubusercontent.com/Rubentxu/pipeline-kotlin/main/scripts/install-pipelinek.sh"
./install-pipelinek.sh install 0.97.0   # descargar, verificar, instalar
./install-pipelinek.sh use 0.97.0       # cambiar la release activa
export PATH="$HOME/.local/share/pipelinek/current/bin:${PATH}"
```

`asdf` y `mise` instalan PipelineK a través del plugin [asdf-pipelinek](https://github.com/Rubentxu/asdf-pipelinek):

```bash
asdf plugin add pipelinek https://github.com/Rubentxu/asdf-pipelinek
asdf install pipelinek 0.97.0

mise plugins add pipelinek https://github.com/Rubentxu/asdf-pipelinek
mise install pipelinek@0.97.0
```

**Explora.** [`examples/`](examples/) contiene los cuatro plugins de ejemplo y un ejemplo de observabilidad: una configuración local de Collector, consultas PromQL y TraceQL, y un dashboard de Grafana que las ejecuta. El [catálogo de ejemplos](examples/catalogue/) reúne los ejemplos trabajados de las capacidades de arriba: cada uno es un pipeline ejecutable, con un README que explica sus etapas, sus pasos y qué espera. El arnés de releasing es quien los ejecuta. `pipelinek capabilities` y `pipelinek sdk-info` imprimen lo que admite esta build y contra qué compila un plugin.

**Construye un plugin.** El contrato, el SDK y las banderas de confianza están en [Escribir un plugin](#escribir-un-plugin). Los cuatro ejemplos de `examples/` son exactamente eso, y se construyen fuera del build de este repositorio.

**Cómo se verifica el build.** `scripts/verify-all.sh` ejecuta tres patas: **unit tests** para las reglas del dominio, **integration tests** que componen dos o más módulos reales con cada efecto que saldría del proceso sustituido por un doble, y **mutants** sobre las costuras que los módulos declaran: un mutante superviviente es un agujero en el test que debía matarlo. El gate también lee este README contra el código: `DocumentationDriftTest` falla cuando los dos discrepan, así que este documento no puede prometer lo que el build no hace.

**Informa de un problema.** [Abre un issue](https://github.com/Rubentxu/pipeline-kotlin/issues) con la versión de `pipelinek version` y la salida de `pipelinek doctor`.

**Contribuye.** [AGENTS.md](AGENTS.md) dice cómo se verifica el build y qué queda fuera de alcance. El repositorio tiene licencia MIT: mira [LICENSE](LICENSE).

---

<sub>PipelineK está en desarrollo activo. Las capacidades marcadas como *experimentales* pueden cambiar; las capacidades publicadas que se listan arriba son las que esta release garantiza.</sub>
