# Roadmap de refactorización y correcciones — PipelineKotlin

Base: `main` · commit `bd5edb8de` (medido) · publicación `1ac1afe7` · v0.91.0

La propuesta es reorganizar `pipeline-application` y simplificar `pipeline-step-sdk` mediante vertical slices, separación de responsabilidades y reducción de connascencia, aprovechando las abstracciones existentes.

No se añadirán funcionalidades. Se conservarán los contratos públicos y los comportamientos correctos; únicamente cambiarán los comportamientos en los que se haya demostrado un defecto.

## 1. Objetivo arquitectónico

El problema no se resuelve simplemente dividiendo archivos grandes. Hay que conseguir que cada caso de uso tenga un propietario claro de sus decisiones, dependencias y efectos.

Organización objetivo de las responsabilidades

```
pipeline-application
├── run/
│   ├── admission/       # Validar ejecución
│   ├── compilation/     # Preparar pipeline
│   ├── execution/       # Coordinar ejecución
│   ├── recovery/        # Recuperación existente
│   └── composition/     # Construcción de dependencias
├── observe/
│   ├── console/         # Lectura de salida
│   ├── events/          # Consulta de eventos
│   ├── inspect/         # Inspección
│   └── projections/     # Explain, Cost, Telemetry
├── plugins/
│   ├── admission/       # Integridad y confianza
│   └── composition/     # Registros y capacidades
└── cli/                 # Parsing, dispatch y formato

pipeline-step-sdk/runtime
└── durable/
    ├── shell/           # Orquestación shell
    ├── process/         # Lanzamiento y terminación
    ├── transcript/      # Captura y drenaje
    ├── watchdog/        # Timeout y cancelación
    ├── agent/           # Adaptación al agente existente
    └── protocol/        # Archivos y compatibilidad
```

Es una organización lógica orientativa dentro de los módulos actuales, no una propuesta para crear nuevos módulos Gradle ni nuevas API públicas.

El recorrido de ejecución debe quedar claramente delimitado:


El SDK no debe conocer decisiones de negocio del CLI, y la capa de aplicación no debe implementar detalles de procesos Unix o FIFOs.

## 2. Connascencia: qué corregiremos

La connascencia mide cuánto necesitan coordinarse dos piezas de código para funcionar. El objetivo es mantener los acoplamientos fuertes dentro de límites pequeños y evitar que atraviesen módulos.


connascence.io

|Tipo|Problema identificado|Refactor|
|---|---|---|
|Significado|`fencingToken` empleado como instante temporal|Tipos de identidad y tiempo distintos|
|Ejecución|Recover depende del orden de apertura, lease, lectura y escritura|Separar inspección, decisión y aplicación|
|Timing|Watchdog, resultado y terminación del shell dependen de carreras|Decisor de terminal y propiedad del ciclo de vida|
|Identidad|IDs de operaciones interpretados mediante convenciones de cadenas|Resolver la identidad una sola vez|
|Posición|Algunas funciones agrupan numerosos parámetros relacionados|Requests y contextos tipados, sin bolsas genéricas|
|Algoritmo|Varias proyecciones reconstruyen asociaciones entre eventos|Compartir la regla de correlación|

Principio de diseño: connascencia fuerte cerca, connascencia débil lejos. No se creará una abstracción nueva si únicamente desplaza la complejidad.

## 3. Roadmap propuesto

## H1 — Corregir invariantes de recuperación

P0

Alcance: `pipeline-runtime`, `pipeline-runtime-api` y `pipeline-application`.

Este hito corrige los tres defectos funcionales principales de la auditoría anterior, antes de mover sus implementaciones.

- `--dry-run`: ninguna adquisición de lease ni recuperación implícita con escritura.
    
- `deadlineMs`: reemplazar el cálculo desde `fencingToken` por una decisión temporal coherente con `Clock`.
    
- `cursors.load`: diferenciar ausencia legítima de error de lectura.
    
- Reutilizar los puertos y ADTs actuales siempre que expresen correctamente estos casos.
    
- Conservar los formatos de almacenamiento existentes.
    

Entrega verificable: recuperación real e inspección simulada con resultados coherentes; ninguna repetición de efectos externos y fallos de almacenamiento expresados mediante negativas tipadas.

### Estado de H1 — cerrado

Los tres invariantes están corregidos y la puerta completa está en verde.

|Defecto|Corrección|Commits|
|---|---|---|
|`--dry-run` tomaba la lease|La acquisition se declara en vez de hacerse; el dry run no escribe|`eb9a22723`|
|El plazo se calculaba desde `fencingToken`|El decider no calcula tiempo; el plazo lo lee el adaptador de su propio reloj|`eb9a22723`|
|La lectura fallida del marcador se leía como ausencia|Una lectura que falla es `StorageError`; solo la ausencia es ausencia|`eb9a22723`|

La ruptura de ABI que implica el plazo —`RecoveryChoice.Reattach` pierde `deadlineMs`— está autorizada por el maturity `EXPERIMENTAL` de `pipeline-runtime-api` y recibida en `published-contract-exceptions.json` (entrada 21, con el SHA que la introduce). El tren 0.90.0 quedó cerrado en `v0.90.0`; como el árbol ya no producía los mismos bytes, la 0.91.0 se abre aquí (`ed22ff91f`) y su candidata es `v0.91.0-rc1`.

Medido sobre `bd5edb8de` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=bd5edb8de33a89e6b9f56d80c5ea09bc3c1acfe4  elapsed=170s  budget=900s  tests=rewritten:1339 read:0  scenarios=observed:62 executed:62
```

- 183 informes JUnit reescritos por esta ejecución, 1339 tests, 0 fallos, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde, 588 comprobaciones sobre 112 ejecuciones.
- 170 s de reloj de pared frente al presupuesto de 900 s.

### Publicación de `v0.91.0-rc1` — verificada sobre el remoto

La candidata se publicó desde `1ac1afe7` y la publicación se comprobó sobre el remoto, no sobre el árbol local:

```
release-pipelinek: PUBLISHED v0.91.0-rc1 with candidate_id sha256:b847f81d80c85d431fb552290dc2dc3926106d31c64d317b3333dfae2f7732b7
```

- El tag remoto es **anotado**: `git ls-remote --tags origin v0.91.0-rc1` devuelve el objeto de tag `c00cbbc6` y, con él, el peel `refs/tags/v0.91.0-rc1^{}` al commit `1ac1afe7`. Un tag ligero no tendría esa segunda línea.
- La firma **verifica** contra la clave declarada `9600F7D49B9C966E3BB04E6A17E44CA7C5288281` (uid `test@example.com`), comprobada sobre el objeto descargado del remoto y no sólo sobre la copia local.
- La prerelease de GitHub existe (`draft=false`, `prerelease=true`) con 7 assets. Los seis digests de `SHA256SUMS` coinciden con los assets descargados, y el digest de `pipelinek-0.91.0.zip` es exactamente el `candidate_id` anunciado.
- `git-head.txt` y `manifest.json` nombran el commit `1ac1afe7`, que es el mismo commit al que apunta el tag.

No queda ninguna publicación pendiente de H1.

## H2 — Refactor del núcleo durable del SDK

Foco principal: `DurableShellExecutor.kt` — aproximadamente 1.803 líneas.

Actualmente concentra materialización de scripts, construcción del wrapper, creación de FIFOs, lanzamiento del agente, procesos, watchdog, cancelación y limpieza.

La separación propuesta conserva `DurableShellExecutor` como fachada compatible y extrae responsabilidades internas:

|Componente interno|Responsabilidad única|
|---|---|
|`ShellProtocolLayout`|Nombres y rutas del protocolo persistente|
|`ShellLaunchPreparation`|Preparar scripts, wrapper y entorno|
|`ShellProcessLauncher`|Lanzar procesos y devolver su identidad|
|`AgentRendezvous`|Coordinar los FIFOs y el agente ya existente|
|`ShellWatchdog`|Vigilar tiempos y solicitar terminación|
|`ShellTerminalDecision`|Clasificar salida, timeout o fallo|
|`ShellResourceCleanup`|Liberar los recursos pertenecientes al lanzamiento|

Los nombres son orientativos. Antes de crear cada componente se debe comprobar si `DurableShellFiles`, `LaunchedShell`, `TranscriptDrain` u otra abstracción ya resuelve esa responsabilidad.

No habrá dos motores shell. `executeCore()` debe convertirse en un coordinador legible, no duplicarse en una nueva implementación.

Entrega verificable: ejecutar, terminar, cancelar y recuperar `sh` con las mismas salidas, efectos y protocolos persistentes.

### Estado de H2 — en curso; los tres primeros componentes están cerrados

Antes de crear cada componente se comprobó qué abstracción existente ya es la dueña de cada responsabilidad. El resultado, medido sobre `DurableShellExecutor.kt` (1.802 líneas):

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|Transcripto de consola: nombre canónico y compatibilidad de lectura|`DurableShellFiles` (41 líneas)|Ya es la dueña del nombre `console.log`; se conserva y se le añade el resto del protocolo|
|Identidad de un lanzamiento: hijo, bombas, sumideros, descriptores de calentamiento|`LaunchedShell`|Ya es la forma de retorno del lanzador; no se crea otra|
|Bombas de transcripción, drenaje y veredicto de persistencia|`TranscriptDrain.kt`|Ya está fuera del ejecutor; no se toca|
|Materialización de scripts y wrapper, FIFOs, agente, watchdog, cookie-scan, limpieza|Dentro del ejecutor|Es lo que se extrae|

La investigación previa contó 26 apariciones de literales del protocolo (`.cookie`, `result.txt`, `result.txt.tmp`, `script.sh`, `script.sh.copy`, `wrapper.sh`, `output.txt`, `fifos/stdout.fifo`, `fifos/stderr.fifo`, `fifos/agent.ready`, `agent.log`, `timeout.flag`) repartidas entre el ejecutor y el contenido del wrapper. Faltaba, por tanto, una autoridad única de los nombres del protocolo persistente. `ShellProtocolLayout` era el primero de los siete componentes, y el que menos mueve comportamiento.

#### El corte: `ShellProtocolLayout`

Los nombres pasaron de literales a propiedades de una clase que se construye con el directorio de control. `ShellProtocolLayout` (en `pipeline-step-sdk:runtime`) publica los trece nombres del protocolo y las rutas que se derivan de ellos; el nombre del transcripto de consola no se replica, delega en `DurableShellFiles`, que sigue siendo su única dueña.

Cuatro consumidores se unieron: el ejecutor (escribe), `StepReconcilerL1` (reconcilia), `RunningSubprocessRecovery` (observa el valor capturado) y `ObsPc2IngestAgent` (abre los FIFOs). El cuarto no estaba en el inventario inicial y apareció al barrer el árbol con los literales del protocolo: una copia de los nombres que nadie había contado también es una copia que puede derivar.

Medido sobre el árbol tras el corte, los únicos ficheros que aún escriben un literal del protocolo son la propia autoridad, el test que fija el formato, y `durable.task.ProcessDurableTaskRuntime` — que es OTRO protocolo, con otro directorio de control y otro contenido en su `result.txt` (un REGISTRO terminal, no un código de salida). Mezclarlos fundiría dos formatos que sólo comparten dos palabras.

Dos documentos falsos se corrigieron: la tabla "Control Directory Layout" de `DurableShConfig` nombraba `jenkins-log.txt` como si fuera el transcripto, y el KDoc del ejecutor afirmaba que el script del usuario se detectaba y se rechazaba en el argv. La primera frase era falsa; la segunda describía un mecanismo que el código no tiene. Un comentario que afirma una propiedad que el cableado no tiene es peor que ningún comentario: convierte una ausencia en una garantía.

**La garantía, la capacidad y el instrumento.** La garantía es que el escritor y los lectores del protocolo se encuentran: el wrapper publica el código de salida donde el ejecutor lo sondea y donde un proceso posterior decide entre "terminado", "expirado" y "perdido". La capacidad que esa garantía sostiene es que un paso `sh` durable se puede ejecutar, expirar y reconciliar más tarde desde su directorio de control. El instrumento es un test puro: ni proceso, ni hilo, ni reloj, ni sistema de ficheros. Fijar los nombres como literales no congela un detalle de implementación — son el formato durable que otro proceso, y un operador con un directorio delante, leen; el test es donde queda registrada esa decisión.

**Visto en rojo dos veces.** Antes de dar el corte por bueno, el instrumento se rompió a propósito dos veces, leyendo el diff antes de leer el resultado:

- El escritor ata una ruta distinta de la del layout: se enciende exactamente una fila, la que comprueba que el wrapper ata las rutas del protocolo.
- La constante `RESULT_FILE` pasa a `result.bin`: se enciende exactamente una fila, la que fija los nombres, mientras la anterior sigue en verde. Esa ortogonalidad es lo que prueba que las dos filas miran cosas distintas.

Medido sobre `f5968af75` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=f5968af756cf2faa943ea9af85828fd48f52694a  elapsed=141s  budget=900s  tests=rewritten:1344 read:0  scenarios=observed:62 executed:62
```

- 184 informes JUnit reescritos por esta ejecución, 1344 tests, 0 fallos, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde, 588 comprobaciones sobre 112 ejecuciones.
- 141 s de reloj de pared frente al presupuesto de 900 s.
- `VERIFY_LOC = 25449`, `PRODUCT_LOC = 71128`, ratio `0.358` (límite 2). El censo excluye 2 ficheros como generados (los dos Java de `buildSrc`, cuya ruta de paquete contiene `/build/`) y deja 53 sin contar en ninguna parte (README, `AGENTS.md`, `ROADMAP.md`, ficheros de contrato `.api`, bases de detekt y el `gradle-wrapper.jar`).

La release candidata de este corte se publicó desde `c868ec136`, y la publicación se comprobó sobre el remoto y no sobre el árbol local:

```
release-pipelinek: PUBLISHED v0.91.0-rc2 with candidate_id sha256:5ae24f3f8392d19ab5cdacea24cb2c8c011e641aaebe7bf6add5d9d5b25cbff5
```

- `git ls-remote --tags origin 'v0.91.0-rc2*'` devuelve DOS líneas: el objeto de tag ANOTADO `cff7fe44bebc07bc0e611dbcb844cbff9b61b3f2` y el peel `refs/tags/v0.91.0-rc2^{}` al commit `c868ec136baa96e4ce7d9d21bd258adff0546ae2`. Un tag ligero no tendría esa segunda línea.
- El objeto de tag descargado FRESCO del remoto es de tipo `tag` y lleva un bloque PGP. La firma verifica contra la clave declarada `9600F7D49B9C966E3BB04E6A17E44CA7C5288281` (uid `test@example.com`) mediante `scripts/release/verify-tag-signature.sh`, con las tres filas en PASS: A, el objeto está anotado y la firma verifica; B, la huella y el uid son los de la clave declarada; C, el tag apunta al commit esperado.
- La prerelease de GitHub existe con `draft=false`, `prerelease=true` y el conjunto EXACTO de 7 assets. Los seis digests de `SHA256SUMS` coinciden con los assets descargados, y el digest de `pipelinek-0.91.0.zip` es exactamente el `candidate_id` anunciado.
- `git-head.txt` y `manifest.json` nombran `c868ec136…`, el mismo commit al que apunta el tag.

#### El corte: `ShellLaunchPreparation`

El ejecutor escribía los ficheros del protocolo y componía el entorno del hijo dentro de `launch`, entre las decisiones de descriptores. La composición era una política de cuatro reglas que sólo se podía leer en línea y que no se podía comprobar sin lanzar un proceso. `ShellLaunchPreparation` (222 líneas) la recoge: materializa el script, su copia y el wrapper, y compone el entorno. `DurableShellExecutor` queda como coordinador —lanza, vigila y decide el desenlace— y `executeCore()` sigue siendo el único motor de shell.

**La comprobación previa, por responsabilidad.** Antes de crear el componente se comprobó si otra abstracción ya era la dueña de esa responsabilidad:

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|Preparar scripts, wrapper y entorno|Dentro del ejecutor, en `launch`|Es lo que se extrae|
|Orden de precedencia del entorno|`GateEnvironmentPrecedence` (dominio, `pipeline-domain`, creado en `e0e32abae`)|Ya es la dueña de la regla "declarado gana"; no se crea otra|
|Componer el entorno del hijo|`EnvironmentComposer` (155 líneas)|Parecía la dueña y no lo era: cero llamadas y no gobernaba nada — se elimina|
|Transcripto de consola: nombre canónico|`DurableShellFiles`|No toca esta responsabilidad; se conserva intacto|
|Identidad de un lanzamiento: hijo, bombas, sumideros|`LaunchedShell`|Es la forma de retorno del lanzador, no una segunda política; se conserva intacta|

`EnvironmentComposer` merecía una comprobación aparte, porque su nombre prometía exactamente la responsabilidad del corte. Medido: `grep` sobre el árbol devuelve **cero** referencias en código Kotlin y **cero** en los ficheros de contrato `.api`; no estaba publicada ni formaba parte de ningún contrato de compatibilidad. Sus 155 líneas eran un segundo sitio donde la política del entorno podía vivir, y nadie lo llamaba. Se borró, y el comentario de `CompositionRoot` que la nombraba pasó a nombrar la autoridad real, `GateEnvironmentPrecedence.DEFAULT` (`DeclarationWins`).

**El entorno como función.** Antes, `composeChildEnvironment(pb, …)` mutaba el `ProcessBuilder` del lanzador. Ahora la composición es una función de entorno a entorno: `composeEnvironment(ambient, sandbox, userEnv): Map<String, String>` devuelve el entorno del hijo, y el lanzador toma una foto del ambiente (`pb.environment().toMap()`), lo vacía y adopta el mapa compuesto entero (`DurableShellExecutor.kt:479-482`). La política —sentinelas de identidad, lista denegada y normalización del PATH bajo el perfil local, variables del llamante por último, y normalización de nuevo si el llamante nombra un PATH— se lee ahora como una política y se comprueba como una política: sin proceso, sin hilo, sin reloj.

**Un `outerRedirect` que se calculaba y no se usaba.** El wrapper construía una redirección (`"> fichero"`, `2>&1`, …) dentro de un `when` y **nunca la interpolaba** en su contenido: el wrapper no abre el transcripto, el lanzador redirige los descriptores. Su comentario afirmaba una propiedad que el cableado no tiene. Se eliminó. El cambio es inocuo porque la cadena nunca llegó al contenido del wrapper: las cuatro variantes de redirección por los dos modos de bombeo producen exactamente el mismo resultado.

**La garantía, la capacidad y el instrumento.** La garantía es que el hijo recibe un entorno cuyo alcance no depende de quién pregunta: la identidad de la operación viaja, la lista denegada no alcanza al hijo bajo el perfil local, el PATH sólo conserva prefijos autorizados y un llamante no puede ampliar ese alcance por la puerta de atrás. La capacidad que esa garantía sostiene es que un paso `sh` durable se ejecuta con el entorno que su autor declaró, y no con el que hereda la máquina. El instrumento es un test puro de la política —13 tests de JUnit, sin proceso, hilo, reloj ni sistema de ficheros— porque una garantía de entorno se puede observar sin lanzar nada; lo que sólo aparece al ejecutar sigue cubierto por los escenarios E2E, y un segundo instrumento para lo mismo sería duplicación, no evidencia.

**Visto en rojo tres veces.** Cada mutación se leyó en el diff antes que en el resultado, y después de cada una el árbol se restauró y se comprobó limpio:

|Mutación|Filas que se encendieron|
|---|---|
|Quitar la aplicación de la lista denegada|1 — `the deny-listed variables do not reach the child under the local profile`|
|Quitar la segunda normalización del PATH del llamante|3 — `a prefix named in the keep set survives the filter`, `a caller's path cannot smuggle an entry past the filter`, `the path keeps only the sanctioned prefixes under the local profile`|
|Vaciar la identidad de la operación|1 — `the child's environment carries the operation's identity`|

Ninguna encendió filas ajenas a la regla quitada: cada mutación encendió exactamente las que la describen, y ninguna más.

**Una fuga que el propio test podía producir.** El primer borrador comparaba el entorno entero, y su mensaje de fallo volcaba el mapa completo —el mapa completo del entorno de quien ejecuta los tests puede llevar dentro un token real— dentro del XML de JUnit, que se queda en disco. Un test de entorno no puede imprimir el entorno: se cambió por aserciones sobre nombres concretos. Comprobado después sobre los XML que escribió la ejecución del gate: ninguna coincidencia de `ANTHROPIC_AUTH_TOKEN`, `sk-cp-` ni `sk-ant-`, ni en los informes, ni en el árbol versionado, ni en el repositorio entero.

**Un hallazgo preexistente que este corte no arregla.** `EnvModel.apply` se aplica dos veces sobre las variables del llamante: una en la capa de aplicación (`ShExecution.kt:234`, `:256`, `:400`) y otra en el runtime (antes en `DurableShellExecutor.kt:493`, ahora en `ShellLaunchPreparation.kt:185`). El corte movió el sitio; no lo creó. Se reporta y no se arregla aquí: decidir cuál de las dos capas es la dueña de la coerción del PATH es una pregunta de la costura entre `ShExecution` y el runtime, y esa costura es de H3.

**El diagnóstico de detekt, que no era del código.** El gate de este corte se puso rojo en `detektMain` —la tarea que sí analiza el *source set*, a diferencia del `detekt` agregado, que no analiza ningún fichero y reporta verde— con dos `UseOrEmpty` en `ShellLaunchPreparation.kt:192-193`. La causa no era el código: el baseline suprime POR RUTA, y el código se había mudado de `DurableShellExecutor.kt` a su propio fichero, de modo que la supresión dejó de alcanzar a su sujeto. Se corrigió con `.orEmpty()` y retirando del baseline las entradas que ya no tenían sujeto (`7e3cf6d94`).

Lo mismo se repitió con cinco entradas más. Cuatro (`val cookieFile`, `val resultFile`, `val resultTmp` y `val scriptFile`, todas `= shellFiles.*`) quedaron huérfanas en el primer corte, que ya usaba `protocol.*`; la quinta, `val outerRedirect`, quedó huérfana en este. Que el texto de una entrada del baseline no aparezca en el código NO prueba que la entrada esté inerte: dentro de la ruta la entrada casa de forma laxa —hay una entrada viva cuyo texto ya no coincide con el código actual—, y sólo la ruta tiene que coincidir. La prueba es la inversa, quitar la supresión y ver qué hace el detector, y se hizo: `detektMain` siguió en verde tras retirar las cinco, así que ninguna tapaba un hallazgo real. Se retiraron (`0a7a7e7b4`). Una supresión sin sujeto no es una garantía: es deuda que finge ser política.

Medido sobre el árbol del corte con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=0a7a7e7b40e103828306747239cec78860c741bc  elapsed=143s  budget=900s  tests=rewritten:1357 read:0  scenarios=observed:62 executed:62
```

- 185 informes JUnit reescritos por esta ejecución —todos con su marca de tiempo dentro de una ventana de 4 segundos—, 1357 tests, 0 fallos, 0 errores, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde; 588 comprobaciones sobre 112 ejecuciones; ninguno sin comprobar.
- 143 s de reloj de pared frente al presupuesto de 900 s.
- `VERIFY_LOC = 25602`, `PRODUCT_LOC = 71061`, ratio `0.360` (límite 2). El censo cuenta 165 ficheros de tests unitarios y 62 pipelines E2E como verificación, y 720 ficheros de código de producción como producto.

#### El corte: `ShellProcessLauncher`

El ejecutor lanzaba el hijo dentro de `launch`, entre la preparación del entorno y la decisión de qué hacer con su desenlace. El lanzamiento era un bloque de decisiones —el argv, el directorio de trabajo, el entorno, la redirección de la entrada, y una matriz de cuatro ramas que decidía dónde iban el stdout y el stderr— que sólo se podía leer en línea y que sólo se podía comprobar arrancando un hijo y mirando los ficheros que dejaba. `ShellProcessLauncher` (382 líneas) las recoge; `DurableShellExecutor` queda como coordinador y `executeCore()` sigue siendo el único motor de shell.

**La comprobación previa, por responsabilidad.** Antes de crear el componente se comprobó si otra abstracción ya era la dueña de esa responsabilidad:

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|Identidad de un lanzamiento: hijo, bombas, sumideros, descriptores de calentamiento|`LaunchedShell`|Es la FORMA de retorno del lanzador, no el lanzador; se conserva intacta y se sigue devolviendo sin cambios|
|Preparar scripts, wrapper y entorno|`ShellLaunchPreparation` (corte anterior)|No toca esta responsabilidad; se conserva intacta|
|Nombres y rutas del protocolo persistente|`ShellProtocolLayout` (primer corte)|No toca esta responsabilidad; se conserva intacta|
|Transcripto de consola: nombre canónico|`DurableShellFiles`|No toca esta responsabilidad; se conserva intacta|
|Bombas de transcripción, drenaje y veredicto de persistencia|`TranscriptDrain.kt`|Ya está fuera del ejecutor; no se toca|
|Lanzar el hijo y enrutizar sus dos descriptores|Dentro del ejecutor, en `launch`|Es lo que se extrae|

El resultado de la comprobación es que no existía ningún componente que lanzara: `LaunchedShell` es la identidad que el lanzamiento devuelve, no el acto de lanzar. Se crea, y se sigue devolviendo.

**El enrutado pasa a ser un valor.** Dónde van el stdout y el stderr del hijo depende de dos hechos —si el stdout se captura y si los bytes se bombean por la JVM— y esa decisión era una matriz de ramas dentro del cuerpo del lanzamiento. Ahora es una función pura sobre el protocolo (`planChildDescriptors`), así que se puede leer como una regla y se puede comprobar como una regla, sin proceso, sin hilo, sin reloj y sin sistema de ficheros. Cada caso nombra el FICHERO al que apunta, no un enum: "dónde va el stderr" sólo tiene respuesta con una ruta, y una transcripción con dos escritores es un defecto distinto de un valor tipado contaminado por el stderr.

**La propiedad del agente se decide antes, y por qué.** El rendezvous del agente llega al lanzador como un callback (`attachRendezvous`) y no como un componente, porque el lanzador sabe CUÁNDO ocurre —entre configurar el proceso y arrancarlo: abrir una FIFO para escribir bloquea hasta que haya un lector— y no qué es el agente. El corte del agente (`AgentRendezvous`) es otro corte, y este no lo adelanta. El callback se invoca FUERA del `try`, para que un fallo al acoplarse salga como el error del agente y no como un fallo de lanzamiento envuelto: son dos hechos distintos y el llamante los lee distinto. Los descriptores de calentamiento se cierran en el `catch` si el hijo nunca arrancó, porque son los únicos escritores de las FIFOs y nadie más los cerrará.

**Qué se quita por medida, y no por forma.** `setsidPath`, `bashPath` y `resolveCommandPath` se midieron sobre el árbol anterior al corte (`0a7a7e7b4`): `setsidPath` y `bashPath` tienen **una** aparición cada una —su propia declaración— y cero lectores; `resolveCommandPath` tiene tres —su declaración y las dos que sólo se alcanzan desde esos `by lazy`—. Los dos eran `by lazy` y nunca se leyeron, así que su inicializador nunca corrió y ningún `command -v` llegó a ejecutarse: quitarlos no cambia el comportamiento observable, y era lo único que su lectura habría cambiado. `checkLinuxOrThrow` tenía dos lectores —el lanzamiento y el desprendimiento— y ahora es `requireLinuxHost()`, junto a la excepción que lanza, para que las dos puertas no puedan discrepar sobre la plataforma.

**La petición de ejecución sale del fichero del ejecutor.** `DurableShellExecutionRequest` y sus dos proyecciones (dónde acaba la transcripción y qué significa un timeout) salen de `DurableShellExecutor.kt`, donde eran `private` de fichero, a un fichero propio: el lanzador y el ejecutor son DOS colaboradores de la misma petición, y ninguno la posee. Declararla junto a la fachada obligaría al lanzador a depender del fichero de la fachada para la forma de su propio trabajo.

**La garantía, la capacidad y el instrumento.** La garantía es que los bytes del hijo llegan donde el modo de la ejecución exige: un stdout capturado es el valor tipado y NADA más llega a él —ni el stderr, ni los bytes de la transcripción—, el stderr es un canal distinto hasta el descriptor del núcleo, la transcripción tiene UN solo escritor, y cuando el agente de ingesta es el que bombea, los dos canales son sus FIFOs. La capacidad que esa garantía sostiene es que un paso `sh` durable devuelve un valor tipado que no está contaminado por los diagnósticos del propio paso, y que un lector puede seguir la transcripción y saber si mira el stdout o el stderr. El instrumento es un test puro de 12 filas de JUnit 5 —sin proceso, sin hilo, sin reloj, sin sistema de ficheros— porque el enrutado es una función pura de cuatro hechos: el instrumento más barato que puede fallar es una llamada a ella, y los escenarios E2E ya lanzan hijos reales y dicen si los bytes llegaron; esto dice QUÉ regla los envió.

**Visto en rojo seis veces.** Cada mutación se leyó en el diff antes que en el resultado, el árbol se restauró después, y cada ejecución se comprobó contra el XML de JUnit autoritativo —no contra la salida del build, que también dice "BUILD FAILED"—, con la condición de que corrieron las 12 filas:

|Mutación|Filas que se encendieron|
|---|---|
|El stdout y el stderr intercambian su destino|1 — `the captured stdout goes to the value file and the stderr to a pipe`|
|La rama de captura envía el stderr al fichero de valor|1 — `the captured stdout goes to the value file and the stderr to the transcript`|
|La rama del agente desaparece|1 — `the agent owns both channels when it is the one pumping`|
|`||` por `&&` en la regla de bombeo|2 — `the composition pumps the child's bytes when it has a redactor`, `the composition pumps the child's bytes when it has a channel destination`|
|El argv gana un `-c`|1 — `the argv is the wrapper and nothing else`|
|Los dos canales van a la transcripción|1 — `both channels go to the transcript when nothing is pumped`|

Cada mutación encendió exactamente las filas que la describen y ninguna más. La cuarta es la que importa más: al cambiar `||` por `&&` se encendieron las dos filas que afirman el bombeo, y la que afirma que NO se bombea siguió en verde —porque afirma lo contrario—, y ésa es la prueba de que las tres filas miran hechos distintos y no una sola regla repetida.

**El diagnóstico de detekt, que no era del código.** `detektMain` —la tarea que sí analiza el *source set*— se puso en rojo con un `SpreadOperator` en `ShellProcessLauncher.kt:123`: `ProcessBuilder(*shellLaunchArgv(…).toTypedArray())`. La causa era el constructor variádico, no la política: `ProcessBuilder` tiene un constructor que recibe la lista entera, y usarlo quita el `*` sin cambiar el argv. Además, el corte retira siete entradas del baseline cuyo sujeto había desaparecido; la prueba de que eran inertes es quitarlas y ver que `detektMain` y `detektTest` siguen en verde, no que su texto no aparezca en el código.

**Que `detektTest` no sea un verde vacío.** La tarea `detekt` agregada no analiza ningún fichero y reporta verde: un gate que nunca miró no puede estar en rojo. Eso se comprobó con una sonda —un import sin usar en el fichero de test— que hizo aparecer el `UnusedImport` nombrando el fichero de test y su línea, y se revirtió. `detektTest` analiza de verdad `src/test/kotlin`.

Medido sobre el árbol del corte con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=d06e2202697ed4076867d79da3c38c915494a641  elapsed=189s  budget=900s  tests=rewritten:1369 read:0  scenarios=observed:62 executed:62
```

- 186 informes JUnit reescritos por esta ejecución —todos con su marca de tiempo dentro de una ventana de 9 segundos—, 1369 tests, 0 fallos, 0 errores, 0 omitidos. Las cifras se leyeron de los XML de la ejecución, no del resumen del gate: 1369 son los 1357 del corte anterior más las 12 filas nuevas.
- 62 de 62 escenarios E2E ejecutados y en verde; 588 comprobaciones sobre 112 ejecuciones, 219 pasos terminados, 2 terminados inestables; ninguno sin comprobar; 7 credenciales sembradas y 2 reruns durables.
- 189 s de reloj de pared frente al presupuesto de 900 s.
- `VERIFY_LOC = 25750`, `PRODUCT_LOC = 71111`, ratio `0.362` (límite 2); el módulo del corte, `pipeline-step-sdk:runtime`, queda en `913 / 2187 = 0.417`. El censo cuenta 166 ficheros de tests unitarios y 62 pipelines E2E como verificación, y 722 ficheros de código de producción como producto.

**Lo que queda de H2.** Los otros cuatro componentes se extraen conservando `DurableShellExecutor` como fachada compatible y sin crear un segundo motor de shell: `executeCore()` debe quedar como un coordinador legible, no como una copia de la implementación actual.

## H3 — Vertical slice completo `core.sh`

Foco conjunto: `pipeline-application` + `pipeline-step-sdk`.

Es la extracción transversal de mayor valor, porque conecta los componentes que actualmente comparten conocimientos sobre el proceso shell.

Archivos principales:

- `CoreShellStep.kt`    
- `durable/ShExecution.kt`    
- `durable/StepDispatchEngine.kt`    
- `DurableShellExecutor.kt`    
- `ProcessDurableTaskRuntime.kt`    

Las responsabilidades finales quedarían así:

|Propietario|Debe conocer|No debe conocer|
|---|---|---|
|`CoreShellStep`|Contrato y codecs de `core.sh`|`ProcessBuilder`, rutas físicas|
|`ShExecution`|Coordinación del caso de uso, resultados y puertos|FIFOs, señales Unix|
|`DurableShellExecutor`|Ejecución concreta del proceso|Eventos de dominio, CLI|
|`Output Plane`|Persistencia y lectura de bytes|Decisiones sobre el pipeline|
|`StepDispatchEngine`|Admisión, invocación y resultado del step|Implementación interna del shell|

Se revisarán especialmente los dos recorridos de ejecución de shell existentes, para distinguir compatibilidad legítima de lógica duplicada. No se fusionarán implementaciones diferentes sin demostrar equivalencia.

Entrega verificable: un `sh` completo con stdout, stderr, redacción, captura tipada y reanudación conserva la misma semántica pública.

## H4 — Refactorizar los casos de uso de `pipeline-application`

Foco principal: ejecución durable, composition y despacho.

`MainDurableRun.kt` concentra compilación, identidad, stores, leases y ejecución. `StepDispatchEngine.kt` incorpora preparación, reconciliación, invocación, bloques y limpieza de credenciales.

Propongo extraer verticalmente:

|Slice|Trabajo|
|---|---|
|Run admission|Reunir decisiones de admisión e identidad|
|Run preparation|Separar compilación y preparación del contexto|
|Run execution|Mantener `runCanonical()` como único punto de coordinación|
|Step invocation|Separar preparación, reconciliación y despacho|
|Plugin admission|Conservar un único propietario de confianza y composición|

`CanonicalRunRequest`, `CanonicalRunState` y `CanonicalRunPolicy` ya son buenos límites. No deben sustituirse por un gran `ApplicationContext` con referencias a todos los servicios.

Reducir la dependencia de `CanonicalRuntimeCapabilityAccess` sobre capacidades no relacionadas; agrupar únicamente las que pertenezcan al mismo ciclo de vida.

Entrega verificable: ambas modalidades de ejecución existentes utilizan los mismos contratos canónicos, sin duplicar el camino de ejecución durable ni alterar el comportamiento del registro de plugins.

## H5 — Separar CLI, consultas y proyecciones

Foco: `CliParser.kt`, `MainConsoleCli.kt`, Observe, Explain, Cost y Telemetry.

El parser actual, de unas 1.931 líneas, debería delegar en parsers por familia de comando. La interpretación de opciones debe terminar antes de comenzar cualquier efecto.

`MainConsoleCli.kt` debe separar selección, lectura, seguimiento y presentación, reutilizando los lectores existentes.

Las proyecciones Explain, Cost y Telemetry deben compartir las reglas de identidad y asociación de pasos, no una única proyección gigantesca.

Se corregirá además la inconsistencia de `CLI_VERBS` para que la ayuda refleje los comandos ya implementados, incluido `runs recover`.

Entrega verificable: mismas invocaciones válidas, mismas respuestas públicas y mismo comportamiento de lectura, sin lógica de consulta duplicada en los controladores CLI.

## H6 — Reducir deuda del harness y asegurar la publicación

Foco: `examples/run.sh`, `verify-all.sh` y scripts de release.

Refactorizar internamente el lanzador E2E, conservando los escenarios actuales que aporten garantías diferentes.
Corregir el procedimiento existente de promoción estable y su evidencia de firma, sin introducir un sistema de publicación nuevo.
Consolidar verificaciones redundantes cuando protejan exactamente la misma propiedad, y mantener la clasificación entre pruebas parciales y puerta completa.
Entrega verificable: misma cobertura contractual o superior con menor código de infraestructura de pruebas, release reproducible y procedencia verificable.

## 4. Orden y dependencias


H1 y H2 pueden desarrollarse en paralelo, en equipos separados. Después, H3 debe integrar los cambios del shell antes de acometer H4. Así se evitan múltiples agentes modificando simultáneamente `ShExecution` o `StepDispatchEngine`.

Para estos refactors grandes emplearía migraciones incrementales en `main`, manteniendo el proyecto compilable tras cada integración. Branch by Abstraction es apropiado cuando existe un límite estable y se necesita sustituir gradualmente una implementación; no debería utilizarse para crear capas temporales innecesarias.


martinfowler.com

## 5. Reglas obligatorias para el agente

Los agentes deberán trabajar con estos criterios:

1. Refactor antes que rediseño. Identificar la responsabilidad, moverla y eliminar la implementación anterior; no dejar dos caminos activos indefinidamente.    
2. Una autoridad por concepto. Especialmente identidad de operación, ciclo de vida de procesos, resultado terminal, redacción y persistencia.    
3. Sin cambios accidentales de ABI ni de formatos durables. Toda modificación externa debe justificarse por un defecto demostrado.    
4. Programación funcional donde aporta valor. Decisiones mediante funciones puras y ADTs; filesystem, relojes, procesos y streams aislados tras puertos.    
5. Propiedad explícita de recursos. Cada proceso, pipe, hilo y descriptor debe pertenecer a un lanzamiento concreto, y su limpieza debe producirse también ante excepciones o cancelaciones. El ciclo de vida estructurado de Kotlin es una referencia útil, sin necesidad de reescribir todo el shell utilizando coroutines.    
6. No multiplicar interfaces. Introducirlas donde separen una política de un mecanismo o exista una necesidad real de sustitución, no por cada clase.    
7. Borrar código reemplazado. El objetivo es reducir complejidad neta, no aumentar la cantidad de envoltorios.    
8. Verificar comportamiento, no estructura interna. Las pruebas deben seguir siendo válidas si una responsabilidad cambia de archivo o paquete.
    
## 6. Política de testing y aceptación

No hace falta construir otra suite. Para cada hito usaría la infraestructura ya existente:

|Fase|Verificación|
|---|---|
|Desarrollo|Unitarios puros de las decisiones modificadas|
|Integración|Tests reales mínimos sobre puertos afectados|
|Cierre funcional|1–2 escenarios discriminantes existentes o efímeros|
|Antes de release|`scripts/verify-all.sh` completo|
|Después del bloque|Eliminar harness auxiliar, UAT/E2E/AAT efímeras y fixtures desechables|

Los objetivos medibles serán:

# 0
Funcionalidades nuevas y cambios de comportamiento no justificados

# 0
Duplicaciones de caminos de ejecución introducidas
# ≤ 15 min
Presupuesto total de la puerta completa
# ≤ 1×

Objetivo de líneas de pruebas permanentes frente a producción

Metas de aceptación, no resultados ya medidos tras los refactors.

Para valorar la mejoría, además de LOC mediría dependencias cíclicas, número de responsables por caso de uso, supresiones de complejidad, tiempo de compilación incremental y tiempo real de pruebas. Una reducción de líneas en `DurableShellExecutor` que simplemente traslade su complejidad a otro archivo no contará como mejora.

## 7. Qué queda expresamente fuera

No se implementará `output release`, ni nuevas opciones CLI, ni proveedores de ejecución, ni protocolos de agente, ni capacidades de observabilidad. Tampoco se modificarán las políticas de retención actualmente elegidas.

En el caso del Output Plane solo se refactorizarán las responsabilidades existentes y se corregirán los problemas demostrados de propiedad de recursos, caché o gestión de ciclos de vida.

Cada hito debe acabar con una release candidata integrada en Git remoto, verificable de forma independiente, sin depender de que el siguiente hito llegue a completarse.

Mi prioridad sería H2 y H3, después de corregir H1. Es donde confluyen la mayor complejidad, la connascencia temporal y el riesgo de errores difíciles de reproducir. A continuación abordaría H4, que es donde la extracción por vertical slices puede producir la mayor reducción de acoplamiento en `pipeline-application`.
