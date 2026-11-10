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

"$PIPELINEK" version     # → pipeline 0.95.0
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
| **CLI estructurada** | `validate`, `run`, `observe`, `console`, `events`, `inspect`, `credentials`, `doctor`, `version`, `capabilities`, `sdk-info`, `serve`, `runs cancel`, `runs recover`: cada verbo imprime su propio uso | Disponible |
| **SDK de plugins** | `StepContract`, códecs tipados, capacidades declaradas, un manifiesto con su rango de API. El autor de un plugin compila contra el SDK publicado, y nada más | Disponible |
| **Confianza en plugins** | `--plugin-trust` admite un plugin solo si sus bytes coinciden con el SHA-256 fijado y llevan una firma Ed25519 válida; `--require-plugin-trust` rechaza lo que no esté avalado, antes de que corra su código | Disponible |
| **OpenTelemetry** | Exportación OTLP opcional de tiempos, recuentos y buckets: nunca cargas útiles. Apagada salvo `PIPELINEK_OTEL_ENABLED=true` | Disponible |
| **Introspección y recuperación** | `inspect`, `runs cancel`, `runs recover`, lecturas digeridas, fijación de salida, retención de rechazos | **Experimental** |

La última fila es la honesta: las capacidades `runtime.inspect.v1`, `runtime.cancel.v1`, `runtime.recover.v1` y `output.pin.v1` se publican como **experimentales**. `pipelinek capabilities` imprime el estado exacto de cada clave que admite esta build.

**Lo que está publicado:** `output.follow.v2`, `events.follow.v3`, `events.stepoutcome.v1`, `events.failurestage.v1`.

### El servicio local

`pipelinek serve` levanta un servicio local sobre un socket de Unix, para que un cliente pueda preguntar por las ejecuciones de una máquina —y, con el ámbito adecuado, arrancar una. Es opt-in en el sentido literal: nadie lo arranca por ti, y sirve hasta que lo paras. Lo que queda detrás cuando para no es nada que CORRA —el socket se va—, y lo que ESCRIBIÓ se queda: las autorizaciones, el registro de los arranques y el directorio de las ejecuciones supervisadas son ficheros del operador, junto al socket, y no se borran.

```bash
pipelinek serve --db ./state/pipelinek.db --control-root ./state/durable --socket ./state/pipelinek.sock
```

Las tres rutas son obligatorias y ninguna tiene valor por defecto: el servicio lee el sustrato que nombras y escucha donde dices, de modo que lo que el socket dice de una ejecución es comparable con lo que dice `inspect --state` sobre las mismas entradas. El servicio nunca escribe en el sustrato que sirve —las ejecuciones que arranca sí, en el almacén que nombraste— y no crea el control root que va a leer. Lo que el servicio escribe es suyo, junto al socket: el fichero de las autorizaciones que posee, el registro de los arranques (`starts`) y el directorio de las ejecuciones supervisadas (`supervisions`).

El socket es un fichero del sistema de ficheros, y su modo es la frontera. El socket queda en `rw-------`, y su directorio tiene que ser privado (`rwx------` cuando el servicio lo crea): si el directorio concede algo al grupo o a otros, el servicio no arranca en vez de publicar un socket que esos usuarios podrían abrir.

Toda petición lleva una **credencial**: un token que el servicio emitió, en el campo `auth`. Una petición sin ella se rechaza por su nombre (`not-authorized`), y también una petición sobre una ejecución que el ámbito de la credencial no cubre (`run-not-authorized`).

Una petición por línea, una respuesta por línea, en JSON, con la versión del protocolo en el campo `schema` y el tipo de la línea en `kind`:

```json
{"schema":"pipelinek.service/2","op":"run.state","runId":"34b28ddc-…","auth":"…"}
{"schema":"pipelinek.service/2","kind":"answer","result":{"state":"terminal","attempt":1,"at":0,"outcome":"succeeded","terminalAt":0}}
{"schema":"pipelinek.service/2","kind":"refused","refusal":{"kind":"unknown-run","runId":"34b28ddc-…"}}
```

El servicio lee tres cosas: `version` (la versión del producto y la del protocolo), `health` (el servicio y el sustrato con el que se compuso) y `run.state` (el estado de una ejecución, la misma respuesta que imprime `inspect --state` sobre el mismo almacén). También puede ARRANCAR una ejecución y CANCELAR una, que son una cuarta y una quinta puerta, cada una con un derecho propio. Cada rechazo lleva un nombre — `unknown-run`, `incompatible-protocol`, `request-too-large`, `too-many-connections`, `not-authorized`, `run-not-authorized`, `too-many-subscriptions`, `start-not-authorized`, `script-not-admitted`, `workspace-not-admitted`, `idempotency-conflict`, `too-many-supervised-runs`, `run-start-refused`, `cancel-not-authorized`, `run-cancel-refused` — para que el cliente lea una palabra y no una frase.

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

La clave es lo que hace segura un reintento: la misma clave con la misma petición responde con la MISMA ejecución, y la misma clave con otra petición es un conflicto (`idempotency-conflict`). La promesa es duradera mientras el servicio pueda escribir su fichero —el servicio guarda el registro de los arranques en el fichero que posee junto al socket, `starts`—, así que un cliente que reintenta después de un reinicio recibe la ejecución que ya había arrancado. Si el disco se niega a escribir, la promesa degrada a la vida del servicio: el registro se queda en memoria, al cliente se le contesta igualmente, y un reintento DESPUÉS de un reinicio puede arrancar una segunda ejecución con la misma clave.

El hijo es un proceso, lanzado por el puerto que ya existe para lanzar procesos, y es el mismo `pipelinek run` que el operador escribiría en la línea de órdenes. La ejecución escribe en el mismo almacén y el mismo control root que el servicio lee, así que una ejecución arrancada por el socket es una ejecución sobre la que `run.state`, `events.follow` y `output.follow` pueden responder — y sobrevive a la conexión que la pidió.

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

Una cancelación nunca cruza una continuidad VIVA: el servicio no toma la continuidad de una ejecución que otro proceso está publicando, y el rechazo nombra a quien la tiene (`the-lease-is-held-by-another`). Por eso el hijo se acaba primero —el hijo ES quien la tiene, y quien está muerto se releva—, y eso es lo que hace que la cancelación sea la RECONCILIACIÓN de una ejecución cuyo escritor murió sin decir nunca cómo terminó.

Un servicio que no puede cancelar ejecuciones abre el sustrato en SOLO LECTURA y lo dice al arrancar (`substrate read-only`). Un servicio que sí puede las abre en lectura-escritura, porque el terminal de una ejecución cancelada es un hecho y el servicio es quien lo escribe.

El fichero del socket se borra cuando el servicio para. Un socket que dejó una caída se reconoce por lo que es y se reemplaza en el siguiente arranque; un socket en el que ya escucha otro servicio no se desplaza nunca, y una ruta que no sea un socket no se borra nunca.

#### Quién puede preguntar, y cuál es la frontera de verdad

El servicio emite una credencial la primera vez que arranca y la guarda en el fichero que posee junto al socket, `authorizations`, con modo `0600`. Una autorización por línea — un token, una etiqueta y un ámbito:

```text
<token> <etiqueta> <ámbito>
```

El ámbito es `all`, `none`, o las ejecuciones que cubre, y puede llevar los sufijos `+start` y `+cancel` cuando el cliente además puede arrancar y cancelar ejecuciones. La etiqueta es para el operador y **nunca viaja**: una etiqueta que viaja se puede enumerar con un cliente que va probando tokens hasta que uno pasa.

Dos perfiles, y cada uno dice en voz alta, al arrancar el servicio, qué promete:

- **`trusted-local`** (el de por defecto) guarda la autorización en ese fichero, y el fichero lo puede leer cualquier proceso del mismo usuario. La frontera es el sistema de ficheros, y el perfil lo dice.
- **`isolated`** lee la autorización de la **entrada estándar** del servicio y no la escribe en ningún sitio, así que no hay fichero que otro proceso del mismo usuario pueda leer.

Ninguno de los dos perfiles separa dos procesos del mismo usuario, y el servicio también lo dice: la JVM no expone las credenciales del par de un socket de Unix, así que nada en esta build puede saber quién se conectó. Lo que una credencial separa son las *identidades que el operador nombra* —un cliente que presenta el token y otro que no— y no dos procesos que corren con el mismo usuario. Léelo como una frontera sobre el **servicio**, no sobre los **datos**: el mismo sustrato se puede leer desde la línea de órdenes, sin ninguna autorización.

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

La línea de órdenes también puede hablar con un servicio, con `--endpoint`:

```bash
pipelinek inspect --state --endpoint ./state/pipelinek.sock 34b28ddc-…
```

`--endpoint` lo admite **un** modo de **un** verbo: `inspect --state`, la única superficie que esta CLI publica y que el servicio responde con la misma renderización. Cualquier otro verbo rechaza la opción **por su nombre**, y no hay alternativa: un servicio que no está se reporta como que no está, nunca se responde desde el almacén local.

La línea de órdenes lee el token de la variable `PIPELINEK_SERVICE_TOKEN`, o del fichero que el servicio posee junto a su socket, o del fichero que nombra `--authorization-file`. Nunca lo imprime: un secreto que esta CLI imprime acaba en un scrollback, en un log y en el historial de la shell.

El plazo del cliente cierra la conexión que abrió y no toca nada más: las preguntas que el servicio responde son de solo lectura, así que un cliente que se va deja la ejecución exactamente como estaba. Una ejecución que el cliente ARRANCÓ sigue viva: la respuesta es la identidad de una ejecución, no su dueño.

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

### Ecosistema extendido

Estos proyectos se construyen alrededor de PipelineK. La **columna 0.95.0** es el rango de SDK que cada uno declara frente a la release vigente: un proyecto fijado a un SDK anterior no carga con ella.

| Proyecto | Qué es | Madurez | 0.95.0 |
|---|---|---|---|
| pipelinek-fabric | Plano de control distribuido: workers, proveedores de podman y Kubernetes. *El repositorio aún no es público* | Preview | Fijado a 0.62.0 |
| [pipelinek-studio](https://github.com/Rubentxu/pipelinek-studio) | UI web, servidor MCP por stdio y extensión de VS Code sobre un proceso real de PipelineK | Preview | Fijado a 0.48.0-rc1 |
| [pipelinek-policy](https://github.com/Rubentxu/pipelinek-policy) | Comprobaciones de política como plugin (`policy.check`) | Preview | Declara `[0.47.0, 0.49.0)` |
| [pipelinek-assurance](https://github.com/Rubentxu/pipelinek-assurance) | Comprobaciones de assurance como plugin | En desarrollo | Declara `[0.48.0, 0.52.0)`; su handler todavía no puede ejecutarse en vivo |
| [pipelinek-saga](https://github.com/Rubentxu/pipelinek-saga) | Contrato de saga para compensaciones | En desarrollo | Todavía no declara rango |
| pipelinek-fabric-backstage | Integración con Backstage para la Control API de Fabric. *El repositorio aún no es público* | En desarrollo | El cliente todavía no está sellado |
| [asdf-pipelinek](https://github.com/Rubentxu/asdf-pipelinek) | Plugin de gestor de versiones para `asdf` y `mise`: resuelve la última versión estable y verifica su SHA-256 | Preview | Resuelve 0.95.0 |

**Escala de madurez:** *Disponible* — publicado y soportado en la release. *Preview* — funciona de extremo a extremo, todavía en movimiento. *En desarrollo* — código real, contrato incompleto. *Exploratorio* — una idea con un spike.

Nada de lo anterior es una promesa de compatibilidad: un proyecto declara el rango de SDK con el que se construyó, y el runtime rechaza un plugin fuera de ese rango antes de que sus clases se carguen.

### Escribir un plugin

Un plugin es un build de Gradle independiente que compila contra el SDK publicado y contra nada más. Declara un `StepContract` — la clave de familia, el descriptor, los códecs tipados y las capacidades que requiere — y un manifiesto con el rango de API con el que se construyó.

El SDK viaja con cada release como `pipelinek-<version>.sdk.zip`: siete artefactos (`pipelinek-domain`, `pipelinek-events`, `pipelinek-output`, `pipelinek-runtime-api`, `pipelinek-scripting-api`, `pipelinek-service-client`, `pipelinek-sdk-bom`) y un BOM que fija sus versiones entre sí. `pipelinek sdk-info` imprime el grupo y las coordenadas exactas contra las que compila un plugin.

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
./install-pipelinek.sh install 0.95.0   # descargar, verificar, instalar
./install-pipelinek.sh use 0.95.0       # cambiar la release activa
export PATH="$HOME/.local/share/pipelinek/current/bin:${PATH}"
```

`asdf` y `mise` instalan PipelineK a través del plugin [asdf-pipelinek](https://github.com/Rubentxu/asdf-pipelinek):

```bash
asdf plugin add pipelinek https://github.com/Rubentxu/asdf-pipelinek
asdf install pipelinek 0.95.0

mise plugins add pipelinek https://github.com/Rubentxu/asdf-pipelinek
mise install pipelinek@0.95.0
```

**Explora.** [`examples/`](examples/) contiene los cuatro plugins de ejemplo y un ejemplo de observabilidad: una configuración local de Collector, consultas PromQL y TraceQL, y un dashboard de Grafana que las ejecuta. El [catálogo de ejemplos](examples/catalogue/) reúne los ejemplos trabajados de las capacidades de arriba: cada uno es un pipeline ejecutable, con un README que explica sus etapas, sus pasos y qué espera. El arnés de releasing es quien los ejecuta. `pipelinek capabilities` y `pipelinek sdk-info` imprimen lo que admite esta build y contra qué compila un plugin.

**Construye un plugin.** El contrato, el SDK y las banderas de confianza están en [Escribir un plugin](#escribir-un-plugin). Los cuatro ejemplos de `examples/` son exactamente eso, y se construyen fuera del build de este repositorio.

**Cómo se verifica el build.** `scripts/verify-all.sh` ejecuta tres patas: **unit tests** para las reglas del dominio, **integration tests** que componen dos o más módulos reales con cada efecto que saldría del proceso sustituido por un doble, y **mutants** sobre las costuras que los módulos declaran: un mutante superviviente es un agujero en el test que debía matarlo. El gate también lee este README contra el código: `DocumentationDriftTest` falla cuando los dos discrepan, así que este documento no puede prometer lo que el build no hace.

**Informa de un problema.** [Abre un issue](https://github.com/Rubentxu/pipeline-kotlin/issues) con la versión de `pipelinek version` y la salida de `pipelinek doctor`.

**Contribuye.** [AGENTS.md](AGENTS.md) dice cómo se verifica el build y qué queda fuera de alcance. El repositorio tiene licencia MIT: mira [LICENSE](LICENSE).

---

<sub>PipelineK está en desarrollo activo. Las capacidades marcadas como *experimentales* pueden cambiar; las capacidades publicadas que se listan arriba son las que esta release garantiza.</sub>
