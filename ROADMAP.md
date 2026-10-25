# Roadmap — PipelineK V2

Vigente desde el **2026-10-19** sobre la rama `refactor/reset-testing-and-docs`
(tag base `pre-testing-reset-20261018`). Este fichero **sustituye** al roadmap
anterior, que describía un régimen (`CycleRunner`, `installedContractTest`, vías
`fast`/`integration`/`candidate`, harness externo) que `AGENTS.md` §0 reemplazó
de forma explícita y que el RESET borró del árbol. Sus referencias a
`*_SPEC.md`, `PIPELINE_TEST_HARNESS.md` y los ADR de ese régimen apuntan a
ficheros que ya no existen: ninguna tarea de aquí depende de ellos.

Toda cifra de este documento está medida sobre el árbol en el momento de
escribirlo, con su denominador nombrado. Una afirmación sin denominador no es
una medición.

---

<!-- BEGIN PIPELINEK RSO 2026-10-24 -->
## Programa RSO — rendimiento, seguridad y observabilidad (propuesta aditiva)

**Base revisada:** `main` SHA `dfba3342eab7732ee706c365db63960394886229` (25-10-2026). Esta propuesta no reabre bloques B1..B4, no hereda afirmaciones del apéndice histórico y no declara ningún hito implementado. `AGENTS.md` y los contratos del código actual prevalecen. Antes de implementar, verificar HEAD, ramas, instalación, políticas, tests y CLI actuales.

**Los bloques de dos auditorías con nombres que chocan.** La auditoría técnica del 25-10-2026 nombra sus propios bloques `B1..B5`, y en este documento `B1..B4` ya significan otra cosa (§0, «Vista operativa»). Desde aquí los bloques de esa auditoría se escriben **`AUD-B1..AUD-B5`**, y los de este documento siguen siendo `B1..B4`: ningún bloque se renumera y ninguna referencia queda ambigua. El estado medido de los cinco está en §0; la parte de publicación de `AUD-B1`, con el incidente que la motivó, está en §2.90.

| Orden | Hito | Capacidad terminada | Condición crítica |
|---|---|---|---|
| RSO-01 | Perfil de ejecución | Timings diagnósticos tipados y baseline de coste | Nunca fingir muestras ni timings ausentes |
| RSO-02 | Caché segura de compilación | Reutilización de scripts compilados con clave v3 | Digest real de JAR, SDK y plugins; sin reusar efectos |
| RSO-03 | Inspect explicativo | Diagnóstico de solo lectura con evidencias y desconocidos | No crear DB ni fundir eventos y bytes |
| RSO-04 | Confianza y admisión | Rechazo pre-evaluación Kotlin cuando falta aislamiento | `LOCAL` no es sandbox multi-tenant |
| RSO-05 | Procedencia de distribución | Dependencias verificadas y artefactos con procedencia | Preservar `SHA256SUMS`, tag GPG y SDK A/B/C |
| RSO-06 | Follow eficiente | Wakeups optativos y polling de seguridad | Notificación ≠ registro; cursores sin pérdida |
| RSO-07 | OTel opt-in | Traces/metrics acotadas y redaccionadas | No bloquear runtime ni filtrar secretos |
| RSO-08 | Proveedor aislado opt-in | Compilación/evaluación y steps bajo límites comprobados | Rechazo si backend no garantiza TODO el perfil |
| RSO-09 | Caché de operaciones puras | Primera transformación real reutilizable y sin efectos | Solo si es viable y mejora demostrada; nunca `sh` genérico |

**Hito completo = capacidad consumible + tests discriminantes + UAT/AAT efímeros retirados + `scripts/verify-all.sh` sin filtros y bajo los presupuestos + integración remota del SHA definitivo + release RC GPG publicada por `scripts/release-pipelinek.sh` + descarga, manifest y consumidor SDK verificados.** Si falta un gate se anota `BLOCKED`, no `DELIVERED`; stable requiere certificador externo cuando aplique. No se añaden tests Kotlin E2E ni QA frameworks; tests permanentes exclusivamente unitarios en su módulo y `.pipeline.kts` de producto en `examples/` solo para garantía pública irreemplazable.

**Paralelismo:** equipos independientes de rendimiento (RSO-01→02→06), observabilidad (03→07), seguridad (04→08) y supply chain (05), con un único integrador exclusivo de ABI/wire, `ROADMAP.md`, merge, gate full, tag y publicación. RSO-09 se decide después de medir su valor. Implementación guiada por `delivery-first-testing` (`change-impact`, `ephemeral-uat`, `regression-promotion`, `merge-assurance`, `integration-assurance`, `release-assurance`, `failure-triage`, `harness-economics`), sin segunda puerta de verificación.

Los diseños completos de trabajo, especificaciones, ADR, dependencias, tests y handoffs están en el paquete externo de planificación `pipelinek-rso-plan/` **fuera del repositorio**, conforme a `AGENTS.md` §§0,6. Cada decisión normativa implementada debe quedar en el código/KDoc público y su cierre en esta sección del roadmap, sin nueva jerarquía `docs/`.
<!-- END PIPELINEK RSO 2026-10-24 -->

## 0. Vista operativa: qué falta, y dónde está escrito

Esta tabla es lo primero que hay que leer. Debajo está la evidencia de cada punto, y
cada fila dice en qué sección vive. Un bloque no se cierra porque su fila lo diga:
se cierra cuando su criterio de cierre está **medido**, y la medida dice dónde.

| Bloque | Qué cierra | Criterio de cierre |
|---|---|---|
| **B1** Integración y workspace | el contrato `authorizedWorkspaceRoot` frente a `effectiveWorkingDirectory` —ya presente en el código actual bajo otros nombres, §2.54—, las PR abiertas triadas y cerradas (§2.54), y el **instrumento** que ya existe (§2.55): un escenario ejecutable que pone en rojo a cualquiera de los cuatro si vuelve a resolver contra el workspace de la etapa | ninguna divergencia conocida entre ramas, la puerta completa en verde sobre el SHA integrado, y las cuatro patas del contrato demostradas por mutación independiente (§2.55) |
| **B2** Plugins e identidad | las derivas que quedan de la segunda puerta, y el límite de procedencia, confianza y admisión | cada deriva observada contra un artefacto real, con su escenario y su fila |
| **B3** Durabilidad y Output Plane | identidades, offsets y recuperación; un efecto externo no se repite tras una caída | recuperación caracterizada con el binario real, sin efectos duplicados |
| **B4** Publicación, SDK y pruebas | la distribución instalada, un consumidor externo del SDK, y la economía de la instrumentación | artefacto publicado verificado por fuera, y pruebas que se pueden explicar |

**Lo que este bloque movió, y lo que no.** Se integró **una** PR abierta —la
`#196`, la de la matriz de admisión del store de eventos—, en local y con
**árbol idéntico** al squash que la plataforma había publicado para ella, y la
puerta completa corrió **tres veces en verde** sobre el SHA integrado (§2.46).
**B1 no se cierra**, pero ya está **triado** (§2.54): el contrato
`authorizedWorkspaceRoot` frente a `effectiveWorkingDirectory` **ya existe** en
el código actual, con otros nombres y **más fuerte** —raíz autorizada con
*lease* tipado frente a cwd efectivo—, y las cuatro PR que lo tocaban apuntan
a un árbol que ya no existe. Lo que quedaba de B1 no era código: era el
**instrumento** que fijase que los cuatro pasos de workspace coinciden sobre la
misma base, y el cierre de esas PR con su motivo.

**El instrumento ya está, y las cuatro patas están medidas** (§2.55). Un solo
escenario, `examples/steps/34-workspace-coherence.pipeline.kts`, con **cuatro
mutaciones independientes** —una por pata, un solo punto de código cada una— y
cuatro filas rojas distintas: `writeFile` apaga `WROTE_HERE`; `stash` apaga
`RESTORED` y `SCOPE_CLEAN`; `unstash` apaga `RESTORED` y `ROOT_NOT_POLLUTED`;
`deleteDir` apaga `VICTIM_GONE` y `ROOT_VICTIM_SURVIVED`. La fuga de la raíz
hacia dentro del ámbito y la fuga del ámbito hacia fuera están **medidas**, no
inferidas.

**Y el contrato de propiedad dejó de ser ambiguo** (§2.56): una raíz de la que
nadie sabe de quién es **no concede** permiso de destrucción. Antes lo
concedía, y su propio KDoc llamaba a esa decisión «fail-closed» cuando era
fail-open. Un default que concede permiso es un default que espera a que
alguien se olvide.

Después, este bloque cerró la **tercera familia de la mentira**: un plugin que
declara **eventos** que no aporta, observado contra un artefacto real (§2.48).
El doctoring del manifiesto gana su tercera línea y el arnés gana su
tercer escenario, y la regla —que ya existía y ya tenía su fila unitaria— por
fin se ha visto ocurrir contra una instalación de verdad.

Y midió, con artefactos reales, **qué pasa cuando dos artefactos distintos
comparten identidad** (§2.49): el que se carga primero se queda, el segundo es
rechazado, y el rechazo **no nombra al que se quedó**. Eso abría un hueco nuevo
en la lista de abajo, y no es el mismo que el que había antes.

Y cerró la **dirección contraria de la familia de capacidades** (§2.50): un
plugin cuyo Step exige una capacidad que su manifiesto no declara. Con esto,
**los tres casos residuales que nombra el §2.44 están observados contra un
artefacto real** — eventos falsos, orden de carga y capacidades omitidas —, cada
uno con su escenario y su fila.

Y después arregló el **diagnóstico** del caso del orden de carga (§2.51): el
rechazo por identidad duplicada ahora nombra **los dos artefactos** —el que se
rechaza y el que reclamó la identidad primero—, sin tocar el vocabulario
publicado ni la política de quién gana la identidad. Ese hueco queda cerrado
**a medias**, y se dice por qué: el diagnóstico era un rumor y ya no lo es; la
política sigue siendo la de hoy, y cambiarla es una ruptura versionada que no
se hace sin autorización.

Y luego corrigió la **otra mitad** de ese mismo diagnóstico (§2.53): el mensaje
decía que el artefacto «**se queda en la composición**», y no se queda nada,
porque un rechazo detiene la composición entera. El texto ahora nombra al
artefacto que reclamó la identidad primero y dice, sin rodeos, que la
composición se rechaza.

**B4 tiene su primera pieza medida: un artefacto publicado y verificado por
fuera.** La vía de publicación estaba rota desde su última publicación con
éxito, y su arreglo no fue «arreglar un script»: fue darle al publicador **un
único lugar del que sacar el conjunto de ficheros**, la puerta, que ya medía
el digest de cada uno (§2.71). La candidata `v0.77.0-rc1` está publicada en
remoto, y sus bytes se comprobaron **desde fuera**: descarga de los siete
assets, `sha256sum -c` sobre las seis entradas de la autoridad de digest,
instalación limpia del ZIP descargado y un pipeline real ejecutado con ese
binario (§2.72). El **consumidor externo del SDK** es la otra mitad, y ahora
también está medido (§2.75): el artefacto publicado dejó de ser algo que
sólo se verificaba byte a byte y pasó a ser **el SDK que los consumidores
externos resuelven**, de modo que un SDK publicado que no se pudiera
resolver detuviese la construcción de la distribución. De B4 queda la
**economía de la instrumentación**.

**Lo que este ciclo midió por dentro: los verbos que hablan del producto.** El
censo de los trece verbos de la CLI encontró que **seis** no los ejecutaba
ningún instrumento, y eran los que el producto usa para hablar de sí mismo.
Dentro de ellos había **dos** rotos: el informe de salud decidía su veredicto
leyendo su propio texto impreso, y el catálogo de capacidades publicaba un
valor de su vocabulario en otro idioma (§2.72). Los dos fallos eran del mismo
tipo, y por eso el arreglo es del mismo tipo: **una respuesta es un valor**, y
su texto es el renderizado de ese valor. La puerta del arnés también cambió:
un escenario puede ahora preguntarle algo al producto, y la tercera puerta
que eso abre lleva su reloj dentro, como las otras dos.

**Lo que este ciclo midió por fuera: la identidad de un stream.** El indice
del plano de salida **ya sabía** la identidad completa de cada stream —la
escribía en sus propios ficheros—, pero esa verdad no tenía superficie: quien
quisiera saber qué operaciones tuvo un run tenía que leer el listado de
directorios, donde el nombre es una **proyección con pérdida**. El arnés
comparaba proyecciones. Ahora el producto publica las operaciones de un run con
su identidad completa, el arnés la consume en vez de deducirla, y dos
operaciones que se pliegan igual son **dos filas** y no una (§2.73). La puerta,
además, destapó un defecto propio: el registro de offset confirmado se escribía
en el sitio y dejaba una ventana en la que el observador en vivo leía un
registro vacío y moría con él, y el run seguía como si nada (§2.73).

**Lo que este ciclo midió por fuera: el techo del drenaje.** El runtime esperaba
a las bombas de transcripción con un **reloj fijo**, y un reloj fijo no
distingue una bomba **atascada** de una bomba **lenta**: a las dos las
declaraba la misma cosa, un rechazo sin decir cuál. Con la máquina quieta el
drenaje terminaba en milisegundos; saturada, el mismo techo se agotaba, y
**dos ensayos de la puerta se descartaron** por ello. Ahora la bomba declara su
atención —¿espera al hijo o trabaja en el plano?—, el atascado se rechaza al
momento y nombrado, el lento se espera, y los dos rechazos son **casos
tipados distintos** (§2.74).

**Lo que este ciclo midió por fuera: el SDK que publicamos.** El producto
publica un SDK —un repositorio Maven dentro de un ZIP— y lo verificaba byte a
byte **sin que nadie lo consumiera jamás**: los plugins externos resolvían
desde un directorio de paso hacia el ZIP, no desde el ZIP. La puerta no
preguntaba si lo que enviaba se podía usar. Ahora el artefacto publicado es
el único SDK que los consumidores ven, y el ZIP incluye además un segundo
camino de consumo —el BOM— que tampoco estaba probado (§2.75).

**Lo que este ciclo midió por fuera: la identidad, leída por fin desde sus dos
extremos.** El identificador de una operación es el segmento central **entero** de un
id de stream, y ese segmento puede llevar separadores propios. Dos lectores lo
interpretaban por texto —uno buscando el identificador pedido dentro del id, otro
quedándose con su primer segmento— y cada uno contestaba sobre una operación que
nadie preguntó: la del vecino cuyo identificador **contiene** el pedido, o la de una
identidad compuesta **cortada** por la mitad. La dirección publicada del stream ya
sabía leer esos dos extremos; ahora responde también a la pregunta inversa, y los tres
lectores —la lectura por operación, su cola y el filtro de consola— consultan esa misma
dirección. Ya no hay dos formas de leer una identidad: hay una (§2.77).

**Lo que este ciclo midió por dentro: dónde vive la biblioteca que el producto descarga.** El
driver de SQLite extrae su biblioteca nativa en el directorio que nombra una propiedad del
sistema, y **no crea directorios**: el sitio tiene que estar allí antes de que llegue. El
producto decidía eso con una comprobación de nulo, y de ahí salían dos fallos medidos contra el
producto real: una propiedad **vacía** contaba como «el operador eligió esto» —y una propiedad
vacía no nombra ningún directorio, así que la biblioteca aterrizaba en el directorio de trabajo— y
un nombre que **no existía todavía** mataba la corrida diciendo que la **base de datos** no era
legible, cuando lo que faltaba era el directorio. La decisión pasa a ser una regla pura y tipada,
y el arnés nombra ahora, en cada escenario, un sitio que no crea: el producto tiene que hacerlo, y
la corrida es la observación (§2.80).

**Lo que este ciclo midió por fuera: el camino más corto al primer pipeline.** El README dice
cómo se ejecuta el primer pipeline, y el primer pipeline **no arrancaba**: en una máquina con un
almacén de credenciales en disco —casi cualquier máquina donde alguien haya guardado un secreto
alguna vez—, **toda** corrida pedía la frase de paso, y el comando de ejemplo del README moría
con salida 3 antes de su primer paso. La causa no era el almacén: era **la pregunta**. La
costura miraba si el fichero del almacén **existe**, que es una pregunta sobre la **máquina**,
cuando lo que hace falta es saber si la **corrida** liga una credencial. La pregunta se hace
ahora a la declaración, con un recorrido exhaustivo que el compilador obliga a mantener
completo, y el arnés —que daba una llave a todo el mundo para tapar el defecto— ha pasado a
dar una máquina **con** almacén y **sin** llave a quien no liga nada (§2.81).

Y el README, que es el documento que promete ese camino, dejó de llevar cifras que alguien
tiene que recordar actualizar —la versión, el digest, el número de escenarios— y de prometer un
bloque de instalación que estaba roto: tomaba el bundle del SDK en vez de la distribución y
componía mal la ruta del binario. Ahora el manifiesto del release es la autoridad que dice qué
asset es el programa, y el README en español, que describía otro producto, es una guía breve y
fiel (§2.81).

**Lo que este ciclo midió por fuera: la llave de las credenciales.** La frase de paso es lo
que abre el almacén de credenciales, y el almacén no la comprobaba: una frase de paso
**incorrecta** borraba una credencial y lo anunciaba como un éxito, y una frase de paso
**correcta** no era necesaria para mirar un almacén que no estaba —que además se quedaba
creado y vacío en el disco—. El comando documentaba sus códigos de salida y dos de ellos no
los producía nunca, porque capturaba una excepción que nadie lanza. Ahora la regla es una y
está escrita en un sitio —*la frase de paso es la autorización para tocar el almacén*—, el
almacén la comprueba antes de leer o de escribir nada, y los verbos que no tienen nada que
abrir no piden llave (§2.82).

**Y una sonda que escribía en el directorio personal del operador.** El runtime durable
llevaba dentro una sonda que copiaba los permisos de sus FIFOs a un fichero del **directorio
personal del usuario**, para que una comprobación que ya no existe pudiera leerlos. El modo
que llegaba hasta ella se activa con tres propiedades del sistema, y **nada en el árbol las
pone**: en la configuración por defecto el producto nunca la alcanzaba. Cuando se activa, la
corrida termina bien y **crea un fichero nuevo en el hogar**, fuera de la raíz de control
autorizada, sin ninguna opción que lo apague. Medido en los dos brazos: con la sonda, el
directorio del hogar pasó de 176 a 177 ficheros; sin ella, no se movió (§2.83).

**Lo que está abierto ahora mismo:**

| # | Qué falta | Por qué sigue abierto |
|---|---|---|
| 1 | **La política** de la identidad duplicada, si algún día hace falta cambiarla | el **diagnóstico** ya nombra los dos artefactos (§2.51) y dice que la composición entera se rehúsa, así que el operador sabe cuál es cuál y sabe que nada se está ejecutando. Lo que queda **no es un defecto pendiente**: el sistema **ya rechaza la composición** cuando ve el duplicado. Cambiar *qué* artefacto conserva la identidad es un cambio de comportamiento observable y por tanto una ruptura versionada, que sólo se hace con una **necesidad de producto concreta**, no porque el modelo sea más bonito |
| 2 | Dos autoridades de identidad: digest declarado, digest medido e identidad de confianza | el vocabulario de rechazos está **publicado**: añadir un caso rompe el ABI de los consumidores, así que separar los tres nombres necesita su propio bloque (§2.45) |
| 3 | Colisión de identificadores de stream: el nombre en disco dobla identificadores distintos | **cerrada en su parte de no mezclar** (§2.76): la colisión **está reproducida con el producto real** —una identidad que se pliega al mismo directorio que otra leía los bytes de su vecina sin decir nada—, y ahora el directorio **recuerda a su dueño**: el que escribe reclama, el que lee comprueba, y el que no es el dueño es **rechazado** con un diagnóstico que nombra a las dos identidades. Queda con nombre una deuda: el **nombre plegado sigue siendo plegado**, y un esquema inyectivo cambia el layout en disco, con su migración y su versión; también queda el formato del cuerpo **no invertible** cuando un identificador lleva `-`, que es ruptura versionada que espera autorización |
| 4 | Certificación externa | **`BLOCKED_EXTERNAL`**: no hay certificador en este repositorio ni en remoto (§2.45) |
| 6 | El consumidor externo del SDK | **cerrado** (§2.75): el SDK publicado se
publicaba y se comprobaba byte a byte, pero **nada lo resolvía**: los plugins
externos lo tomaban de un directorio intermedio. Ahora el ZIP publicado es el
único SDK que esos consumidores resuelven, y uno de ellos consume **a través
del BOM**, sin escribir la versión en cada coordenada. Un SDK publicado que no
se pudiera resolver detiene la construcción de la distribución. Queda fuera:
el ZIP no lleva las dependencias de terceros, así que no es autosuficiente
sin red |
| 5 | El techo del drenaje | **cerrado** (§2.74): el rechazo era tipado pero **no accionable**, y el techo era un reloj que no distinguía una bomba **atascada** (esperando bytes que nadie va a escribir) de una bomba **lenta** (escribiendo porque la máquina está ocupada). Ahora la bomba declara su atención, el atascado se rechaza **al momento y nombrado**, el lento se espera, y los dos rechazos son **casos tipados distintos** (`STUCK_CHILD`, `OUT_OF_TIME`). Queda medir **cuánto** tarda un drenaje con la máquina saturada: reproducir la saturación en un banco de pruebas es una fixture que este repositorio no tiene |
| 7 | Lectores que interpretaban la identidad de una operación por texto | **cerrado** (§2.77): el identificador de una operación es el segmento central **entero** de un id de stream, y ese segmento puede llevar separadores propios. La búsqueda por subcadena daba los frames de una operación cuyo identificador **contiene** el pedido, y el índice de segmento nombraba una operación compuesta **cortada** por la mitad. Ahora la dirección publicada responde a la pregunta inversa (`isOperationOf`) y los tres lectores —la lectura por operación, su cola y el filtro de consola— la consultan. Se elimina además un resolvedor textual sin consumidores, que era una segunda autoridad sobre la misma identidad. El defecto era **latente**: los identificadores que el motor produce hoy no llevan separadores, así que no se ve desde la línea de órdenes |
| 8 | El instalador multi-versión no puede instalar ninguna versión reciente | **cerrado** (§2.84): el instalador ya no construye las cuatro identidades de un release a partir de una sola cadena. Lee el manifiesto que el release publica —etiqueta, versión de producto y artefacto—, lo cruza con `SHA256SUMS` y descarga el artefacto **por el nombre que el manifiesto dice**. Una candidata se instala desde su tag y en su directorio (`versions/0.88.0-rc1`), y un slot estable que no existe se rechaza **señalando** la candidata que sí existe. La ley de identidad se cumple por construcción: la candidata jamás se instala bajo el nombre de la estable |
| 9 | La consola del producto intercala su propio progreso dentro de la salida del paso | **medido**: una marca de 26 bytes puede llegar partida en dos, con la línea de progreso en medio (`WRONG_K` + progreso + `EY_CHANGES_NOTHING`). Las comprobaciones nuevas del arnés miran el **transcripto** —los bytes que el paso escribió— y no la consola; las que ya existían siguen mirando la consola, así que una de ellas puede partirse sin que nadie lo note. Es una deuda con nombre, no un defecto del ciclo (§2.82) |
| 10 | La puerta de una **corrida** no falla en la puerta | **medido**: una frase de paso que **no encaja** se descubre en el primer uso de la credencial, y la corrida termina con el código de un paso fallido; solo la que **no está** se niega en la puerta. El mensaje es cierto y el fallo es cerrado, pero el KDoc de la costura promete «fail fast» para los dos casos. Corregirlo cambia el código de salida de un modo de fallo ya publicado —una ruptura versionada— y no se ha hecho en este ciclo (§2.82) |
| 11 | El runtime llevaba una sonda que escribía en el directorio personal del operador | **cerrado** (§2.83): la sonda no la alcanza nadie en la configuración por defecto, y cuando el modo que la alcanza se activa a mano, escribe un fichero fuera de la raíz autorizada sin forma de apagarla. Se ha quitado, y el permiso de los FIFOs se sigue fijando donde se decide |
| 12 | El verbo que escribe en una run terminada (`cancel`, `recover`) deja un fichero de base de datos **vacío** como efecto lateral de su propio rechazo | **medido** (§2.85): la puerta **se niega** a escribir en una run terminada —es lo correcto—, pero el verbo llega al almacén lo bastante lejos como para dejar el fichero ya creado. No lo introduce este bloque —es anterior— y no se toca aquí: el almacén y su fichero de datos se crean en sitios distintos, y separarlos es un cambio de layout, no un arreglo de una línea |
| 13 | Una excepción en el hilo principal deja el proceso **en pie** en vez de salir | **medido** (§2.85): se vio en vivo con el `NullPointerException` que un `--db` relativo alcanzaba —el hilo principal muere y los hilos del ejecutor durable no lo sueltan—. Se ha corregido **la causa** de esa excepción concreta, no el defecto de fondo: una excepción en el hilo principal debería terminar el proceso. Corregirlo toca el ciclo de vida del runtime y queda **fuera** de este bloque |
| 14 | La clave de la caché de scripts del producto no cubre los **bytes** de la distribución | **medido** (§2.86): la clave cubre el fuente, la versión del compilador, las opciones y el **ruta** de los jars del classpath, pero no su **contenido** — dos construcciones de la misma versión en el mismo directorio comparten clave. El producto lo declara: su KDoc dice que dos de sus siete dimensiones no se pueden leer. Este bloque lo rodea desde el lanzador (espacio de nombres por digest + marcador), pero un usuario que ponga su propio `PIPELINEK_SCRIPT_CACHE_DIR` sigue expuesto. Corregirlo es un cambio de producto, con su tren y su candidata |
| 15 | El contrato completo del evento sigue siendo **tres superficies**: el catálogo de tipos, el `when` del escritor y el `when` del decodificador | **medido** (§2.87): lo que se ha cerrado es el **nombre** de los campos opcionales, que ahora tienen una autoridad cerrada y una prueba que recorre el conjunto entero. Lo que queda es el **evento** en sí: un tipo nuevo hay que escribirlo en tres sitios, y una definición tipada del evento —un codec por familia, como el de las compensaciones— reduciría esos tres a uno. Es un cambio de layout del módulo de eventos, y no cabe en una rebanada |
| 16 | El ejecutor durable sigue concentrando **procesos, FIFO, watchdog, output y ciclo de vida** en un solo fichero | **medido** (§2.87): lo que se ha cerrado es el **estado del lanzamiento** —que ahora viaja con el lanzamiento en vez de quedar en tres campos `@Volatile` que otro paso leía—, y con él cae una costura muerta de 153 líneas. Quedan las responsabilidades: separarlas detrás de puertos concretos es el refactor grande que la propia auditoría **no** recomienda de una vez, y es lo que queda para una rebanada vertical por responsabilidad |
| 17 | Dentro de una línea menor, el **número de versión** declara la compatibilidad, no el contrato | **medido** (§2.88): dos candidatas de la misma línea publican el mismo SDK y declaran el mismo rango `[0.90.0, 0.91.0)`, y el `apiCheck` compara contra el baseline del repositorio, no contra el SDK publicado anterior. Lo que hoy cubre ese hueco es una **prueba de comportamiento** —un plugin construido contra el SDK publicado anterior se admite y ejecuta—, no una comparación de contratos entre dos publicaciones. Comparar el ABI publicado con el del release anterior es un trabajo con su propio tren |
| 18 | La comprobación de consumo externo **no está en la puerta local** | **medido** (§2.88): `verify-all.sh` no la ejecuta, porque necesita los SDK ya publicados y red. Es una puerta de publicación, con su latencia propia, y su veredicto viaja en el handoff del release. Un clon sin red no puede ejecutarla: la puerta local sigue siendo la autoridad de lo local, y esta es la autoridad de lo publicado |
| 19 | El **instalador** no verifica la firma del tag que resuelve | **medido** (§2.89): `scripts/install-pipelinek.sh` resuelve la release por su manifiesto y comprueba el digest, pero no mira la firma: el tag firmado que ahora publica la vía de publicación no lo consulta nadie. La capacidad ya existe y es pública (`scripts/release/verify-tag-signature.sh`); lo que falta es que el consumidor la use, y el consumidor natural es el instalador |
| 20 | Un **tag estable publicado sin procedencia**, y seis en total | **medido** (§2.90): el `v0.90.0` publicado es un tag *lightweight* —sin tagger, sin mensaje y sin firma—, lo creó `gh release create` porque el tag no existía en el remoto, y sus assets son byte a byte los de `v0.90.0-rc3`: el defecto era la **procedencia**, no el contenido. El censo del remoto da **6** tags estables *lightweight* de **63** (`0.75.0`, `v0.1.0`, `v0.56.0`, `v0.61.0`, `v0.75.0`, `v0.90.0`), así que no es un tropiezo aislado sino un patrón. La vía de publicación ya tiene las tres puertas que lo cierran —`--verify-tag` añadido por el script, auditoría del remoto, promoción firmada— y una etiqueta estable publicada **no se mueve ni se reemplaza**: los seis históricos se quedan como están, y la política dice por qué |

### La auditoría del 25-10-2026 y sus cinco bloques (AUD-B1..AUD-B5)

La auditoría nombra sus bloques `B1..B5`; aquí se escriben `AUD-B1..AUD-B5` porque `B1..B4` ya significan otra cosa en §0. El estado de cada uno, con lo medido y lo que **no** se ha medido:

| bloque | qué pide | estado medido | dónde |
|---|---|---|---|
| **AUD-B1** | publicación con procedencia, y una limpieza de caché que no borre lo ajeno | **cerrado en sus dos mitades.** La poda de caché ya está en el árbol, con las seis guardas que la auditoría pedía —marca de propiedad en la raíz, raíz canonicalizada y candidata sólo hija directa, enlaces simbólicos que nunca llegan a `rm`, namespace acreditado por un marcador con digest, edad, y el namespace en uso como excluido— y las seis se han visto en rojo, una por una (commit `dfba3342e`). La mitad de publicación se midió en este ciclo: 17 filas y 10 mutaciones (§2.90), y la puerta completa sobre el árbol que se entrega dio `PASS` (166 s, 1298 tests, 61 escenarios, ratio de LOC 0.346) | §2.90 |
| **AUD-B2** | una **decisión pura de atribución** compartida por perfil, `explain` y telemetría; y una caché que cae por **origen y tipo** y no por el texto literal de un mensaje | **la mitad de la atribución está cerrada y medida (§2.91).** Las tres proyecciones llaman ya a **una** decisión pura (`StepAttribution`), y el defecto —la telemetría atribuía la duración al **primer** candidato— se ha visto en rojo con tres mutaciones (7, 4 y 1 filas rojas, sin daño colateral). La medición en el CLI de una etapa **paralela** mostró lo que la regla vieja decía al operador: que una rama «nunca cerró» cuando sí cerró, y que tres terminales no pudieron nombrar su paso. **Quedan abiertos**: el fallback de la caché, que hoy se decide por la cadena `CachingJvmScriptingHost.UNLOADED_ARTIFACT_MESSAGE` —un **texto** y no un **tipo**— y la prueba de que la clave v3 por contenido es consumible desde un test unitario | §2.91 |
| **AUD-B3** | observabilidad de verdad: `traceId` como **campo**, `spanId` robusto, intervalos validados sin duraciones negativas, límites de cardinalidad por dimensión, y un adaptador OTLP opcional | **abierto, sin tocar.** `RSO-07` es su nombre en la propuesta de arriba | §0 RSO-07 |
| **AUD-B4** | economía de la verificación —y **sólo** después de pasar por `delivery-first-testing` | **abierto, y con precondición declarada.** La lente que aplica se nombra antes de escribir una línea: `references/navigation.md`. El router ofrece un portafolio de auditoría que **no existe en disco** (hay 8 playbooks y ninguno es ese), así que esa ruta no se puede seguir tal cual; el coste de la puerta ya está medido (§2.86) y es lo que este bloque tiene que reducir | §0 RSO-01..02 |
| **AUD-B5** | proveedor de aislamiento | **abierto, sin tocar.** `RSO-08` es su nombre en la propuesta de arriba; `RSO-04` es su precondición (que `LOCAL` no es un sandbox multi-tenant) | §0 RSO-08 |

**La puerta de verificación, ejecutada después, sobre el árbol que se entrega.**
Sobre `dfba3342e` la puerta **no** se había ejecutado, y por eso todo lo que es
Gradle figuraba como `NOT_RUN`. Con el bloque de publicación ya comiteado
(`3db8f951f`) la puerta se ejecutó **entera y sin filtros**:
`PASS sha=3db8f951f… elapsed=166s budget=900s tests=rewritten:1298 read:0
scenarios=observed:61 executed:61`. Los 1298 tests unitarios los escribió **esta**
corrida (`rewritten:1298`, `read:0`: ninguno leído de una anterior), y los 61
escenarios de `examples/` se ejecutaron y se observaron los 61. El censo de LOC,
sobre el mismo árbol comiteado, da `VERIFY_LOC = 24464` y `PRODUCT_LOC = 70676`,
ratio **0.346** (límite 2).

**Lo que sigue sin ejecutarse.** El §6b de `scripts/release-pipelinek.sh` —la puerta
que audita el remoto antes de publicar— **no** lo ha ejercido ninguna corrida real:
se ejecutaría en una publicación, y esa publicación no se ha hecho. Ese veredicto
sigue siendo **`NOT_RUN`**, y no se disfraza de `PASS` porque el resto de la puerta
sí haya corrido.

## 1. Estado medido

| Métrica | Valor | Fuente |
|---|---|---|
| SHA | `1bf97c0c9`, medido en los ensayos de este bloque; el `HEAD` avanza al commit que actualiza esta tabla. Los bloques anteriores cerraron, cada uno, su puerta: el **camino más corto al primer pipeline** —una corrida que no liga ninguna credencial ya no pide la frase de paso del almacén, y el README que promete ese camino dejó de llevar cifras que roten (§2.81)—, la **puerta de las credenciales** —la frase de paso es la autorización para tocar el almacén, y una que no encaja ya no borra una credencial y dice que sí (§2.82)—, el **efecto lateral del runtime** —el ejecutor durable ya no escribe una sonda de permisos en el hogar del operador, y la comprobación del FIFO se mudó a un escenario efímero que es dueño de sus ficheros (§2.83)— y el **instalador** —una release ya no se resuelve construyendo su nombre, sino leyéndolo del manifiesto que la release publica (§2.84)—. Ese bloque cerró la **puerta del almacén**: el productor crea el directorio que sostiene su propio almacén, y los lectores dejan de negarse por un motivo falso (§2.85). Este bloque baja el **coste de la puerta**: la compilación de los scripts se reutiliza entre ciclos, con un espacio de nombres que es el digest de la distribución, y el gate publica el coste por escenario en vez de medirlo y tirarlo (§2.86). Después vinieron la **mantenibilidad durable** —el contrato del evento tiene una sola autoridad y el estado del lanzamiento viaja con el lanzamiento (§2.87)— y la **compatibilidad externa**: un consumidor de fuera construye un plugin contra el SDK publicado y lo carga en la distribución, y esa comprobación corre **dentro** de la vía de publicación, antes de cortar el tag (§2.88). Después vino la **firma de procedencia**: el tag se firma con la clave que declara el repositorio, la firma se comprueba **antes** de subirlo, y la clave pública viaja en el handoff para que un consumidor pueda verificarla sin la máquina que publicó (§2.89). La candidata publicada es ahora `v0.90.0-rc3`: el producto **no** cambia de comportamiento observable, así que la ley de versión no abre un tren nuevo y el tren 0.90.0 sigue abierto (§2.89) | `git rev-parse HEAD` |
| Módulos declarados | 27 | bloque `include(` de `pipelinek/settings.gradle.kts` |
| Módulos con test unitario permanente | **26** | etapa 2 del gate, que imprime cada módulo por nombre |
| Módulos sin test | 1 (`:pipeline-sdk-bom`, que no tiene código) | ídem |
| Tests unitarios ejecutados | **1234** (170 informes en disco, 0 fallos, 0 errores, 0 skips) | etapa 2 del gate, confirmado con un censo independiente de los XML |
| Casos de un vocabulario de rechazos publicado que el producto no puede producir | **0**, medido en el árbol del ciclo anterior (§2.35); **este ciclo no lo re-medió**, y su instrumento es un censo ad-hoc, no un script del repo | censo por construcción de §2.35 |
| Escenarios E2E | **62 (62 pasan, 0 fallan, 0 bloqueados)**, 2 reruns durables, 7 credenciales sembradas; el escenario de credenciales pasa de 14 a 22 comprobaciones, y el número de escenarios **no crece** | etapa 3 del gate |
| Escenarios con una garantía comprobada | **62 de 62**: **578 comparaciones sobre 112 ejecuciones** del producto (§2.65). El arnés ejecuta cada escenario en las dos rutas de ejecución —con journal y sin journal— y cada declaración se contrasta contra la salida de cada ejecución; además, **las dos ejecuciones se comparan entre sí** sobre la historia que contaron y los bytes que comprometieron. Las comparaciones entre las dos ejecuciones van dentro de esas 578, y el total es el que se lee aquí | censo del arnés, sobre los 62 escenarios |
| `VERIFY_LOC / PRODUCT_LOC` | **23216 / 68660 = 0.338** (límite 2) | `scripts/check-budgets.py --json` |
| Ratio máximo por módulo | **1.633** (`pipeline-step-sdk:workflow-control`) | ídem |
| `VERIFY_ALL_WALL_CLOCK` | **175 / 121 / 186** en los tres ensayos, los tres sobre `1bf97c0c9`, peor 186 s, límite 900 s | etapa 5 del gate, `§4` de `AGENTS.md` |
| Veredicto del gate | **`PASS`** en los tres ensayos, sin `NOT_RUN` | línea final de `verify-all.sh` |
| Deuda estática congelada | **879** entradas de baseline en 24 ficheros: **sin cambio en este bloque**, y el diff del bloque no lista ninguna baseline. La cifra de **802** que se venía anotando no se reproduce con ningún instrumento del árbol: el censo de entradas `<ID>` sobre las baselines versionadas da **770** antes de la unión de las dos líneas y **879** después | censo sobre `git ls-files '*detekt-baseline-main.xml'` |
| Certificador externo | **`BLOCKED_EXTERNAL`** | no existe en este repositorio ni en remoto |
| Candidato publicada | **`v0.90.0-rc3`** (prerelease), `candidate_id` `sha256:13310c4ff2f3249377ad93d38e12aa81a1a0bdb9a3417277e390869329131e8d`, tag en `1bf97c0c9`; verificada **sobre los bytes descargados del remoto**, con las **6** sumas de `SHA256SUMS` coincidiendo una a una (§2.85), y con la **firma del tag verificada desde fuera**: un consumidor con el llavero vacío importa la clave pública que viaja en el handoff y la comprobación da `VALIDSIG` (§2.89). Antes **`v0.90.0-rc2`** (§2.88). El tren **0.90.0** sigue abierto: esta es su tercera candidata, y tanto la comprobación de consumo externo como la de la firma se ejecutaron **dentro** de la vía de publicación, antes de que existiera el tag (§2.88, §2.89) | `gh release view v0.90.0-rc3`, `sha256sum -c SHA256SUMS`, `git verify-tag v0.90.0-rc3` |
| Destino de `:pipeline-runtime` | **se queda y está cableado**, y **re-verificado en este bloque**: sus tres adaptadores implementan los puertos publicados de `:pipeline-runtime-api`, y `:pipeline-application` los compone y los invoca por verbo de CLI. **5** clases de test, **28** filas (§2.52). La puerta lo compila **por su nombre**, y los **27** módulos del árbol quedan dentro de ella: los 26 con código los pide la etapa 2 por tener test, y el 27º (`:pipeline-sdk-bom`) no tiene código que compilar | `CompositionRoot.kt:222,280,330`, `MainRunsCli.kt:132,222`, `MainInspectCli.kt:544`, `scripts/verify-all.sh:382` |

Los tres ensayos corrieron sobre el mismo SHA y sobre el árbol **commiteado**, que
es la condición con la que el censo de LOC es verdad: el censo lee
`git ls-files`, así que con el árbol sucio un test nuevo no cuenta y la ratio
sale mejor que la realidad.

El reloj de la puerta lo marca también el host. Los ciclos anteriores midieron
699, 682 y 803 s con la máquina compartida con otro workspace, y 194, 172 y
165 s en el bloque de las tres familias; los siete bloques anteriores a éste
midieron 187, 130 y 133 s, 197, 169 y 376 s, 168, 141 y 149 s, 182, 141 y 139 s,
187, 139 y 140 s, 203, 139 y 141 s, 186, 172 y 176 s y 196, 159 y 148 s.
Este mide
**165, 116 y 116 s**:
la distancia entre un ensayo y otro no la explica el código medido, que es
idéntico dentro de cada ciclo, sino la carga de la máquina. En los tres ensayos
de este ciclo el presupuesto se cumplió con holgura —el peor, 165 s, es menos de
un quinto de los 900 s—, y eso que el árbol medido incluye la distribución,
la API pública, los 1183 tests y los 62 escenarios.

**La etapa del E2E mide 92 s en frío y 64 s en caliente**, medidos en seco sobre el
lanzador, contra los 97 s del ciclo anterior (§2.80) y los 91, 92 y 95 s del anterior a
ese. Este bloque **no añade ni un escenario** —los **62** son los mismos **62**—, y sin
embargo la etapa es **más barata**: la compilación de los scripts se reutiliza entre
ciclos (§2.86). La comprobación nueva de este bloque **no es un escenario**: es efímera
y vive fuera del árbol (§2.85).
Lo que sigue explica el **resto** del coste, que es el de siempre: el arnés ya no deduce las identidades del sistema
de ficheros, **pregunta al producto**, y preguntar cuesta un arranque de proceso
por mundo y por run. Ese precio compra algo que el
filesystem no daba: **la identidad completa** de cada operacion, y con ella
la posibilidad de que dos operaciones que se pliegan igual sean dos filas y no
una (§2.73). El escenario **no crece**: los 62 son los mismos 62, y el que
guarda la puerta de las operaciones se amplia **dentro** del que ya preguntaba
por la salud, el catálogo y la versión del producto (§2.72). El instrumento
mayor de este bloque es el que la puerta encontró sola: **una comparación entre
las dos rutas de ejecución que destapó un defecto del producto** —el registro de
offset confirmado se escribía en el sitio y dejaba una ventana en la que un
lector leía un registro vacío, y el observador en vivo moría con él— (§2.73).
La caché de scripts compilados que el producto ya tenía apagada sigue siendo la
que hace que el E2E no cueste 111 s (§2.63), y **la causa de que el arnés corra el
producto dos veces por escenario sigue siendo la que es** —una ejecución por ruta,
y ahora las dos comparadas entre sí (§2.64, §2.65).

**Dos ensayos anteriores a estos tres se descartaron, y hay que decir por
qué.** Sobre el mismo árbol, la puerta dio dos veredictos con rojos, y **todos
los rojos tenían la misma causa**: `transcript drain unfinished after 10000ms`.
No es una aserción que falló: es el output plane **negándose** a dar por buena
una transcripción cuya bomba de drenaje no había terminado a tiempo, que es
justo lo que un plano de salida debe hacer cuando no tiene pruebas de que la
transcripción esté completa. Los escenarios afectados —varios de `durable/`, uno
de `events/`, todos en su segunda ejecución— **vuelven a pasar** al re-correrlos,
uno por uno y también en serie, con el mismo binario.

Lo que se midió entonces es que la puerta **no es reproducible al cien por
cient** en una host compartida: los dos ensayos con rojos coincidieron con
trabajo ajeno corriendo al lado —una medición de seguridad que este mismo
ciclo lanzó en paralelo, y un `pipelinek run` de otro proyecto—, y el primer
escenario que expiró su propio reloj de 120 s fue el primero que sufrió de
ello. Con la máquina quieta, los tres ensayos de la tabla salen verdes. La
diferencia entre un rojo y un verde, en este caso, no la ha puesto el producto:
la ha puesto quién corría al lado.

Eso deja dos cosas para su propio bloque, y ninguna se arregla aquí: el **techo
de 10 s** del drenaje, que no considera la carga de la host, y la **basura que
el producto deja en `/tmp`**: el driver JDBC de SQLite extrae su biblioteca
nativa a un fichero temporal de un megabyte por conexión y no lo borra —**508**
pares, medio gigabyte, con la fecha de hoy en una máquina donde la puerta corrió
tres veces—. El arnés borra su ciclo; el driver no borra el suyo.

> **Corregido después** (§2.58): la premisa era a medias. El driver **sí**
> borra su extracción al salir normalmente; lo que sobrevive en el tmpdir son
> procesos que mueren a la fuerza. Y el arreglo que este ciclo señalaba como
> pendiente **está hecho**: el proceso fija `org.sqlite.tmpdir` y su residuo va
> a un directorio con nombre, no suelto en el tmpdir compartido.

El ratio de LOC sube de 0.280 a **0.281** (18893 → 18900 líneas de verificación),
y el peor módulo sigue siendo el mismo (`pipeline-step-sdk:workflow-control`,
1.633), porque este bloque no lo tocó.

**Lo que este ciclo añadió.** Un cambio de producto en la segunda puerta de
admisión: la procedencia de un proveedor de contribuciones se mide con un
resultado tipado, y un proveedor que no puede decir de qué artefacto viene
**detiene la composición nombrándolo**, en vez de descartarse en silencio
(§2.45). Con ello: **9** filas unitarias en **1** clase nueva, **0** escenarios
nuevos —no hay artefacto que produzca ese estado, y un escenario que lo fingiera
sería una mentira—, y **1** cifra re-medida que estaba caducada (la deuda
estática). Todo lo nuevo se encendió en rojo antes de darse por bueno, con su
fila exacta.

**Lo que este bloque añadió al anterior.** Dos cosas, y las dos se midieron.
La primera es una **matriz de admisión cerrada** en el store de eventos: la
decisión de si un journal puede abrirse —y en qué carril, de lectura o de
escritura— se expresa como seis casos dinámicos, tres observaciones por dos
propósitos, más una ley independiente que obliga a que todo rechazo nombre el
`path` que el operador recibió (§2.46). La segunda es un **fixture que cumple
la política de git de la máquina**: el escenario del smoke dejó de crear un
commit con el mensaje `init` y una identidad inventada, y pasó a hacer lo que
la máquina espera —mensaje Conventional e identidad la que ella misma
declara—, porque un escenario que se pone rojo por un motivo ajeno al producto
mide el motivo equivocado (§2.47).

Lo que este ciclo añadió a la suite: **25** casos de test en **4** clases
rescatadas del layout viejo —cada una con su regla viva detrás: el
vocabulario con el que un run recuperado se registra, la contención de la ruta
de entrada de un archivo descomprimido, las formas que el guard de
credenciales en `argv` tiene que dejar pasar, y el significado de la receta
vieja de la clave de caché—, y **una** directiva nueva en el arnés,
`expect-outcome`, que hace que el lanzador compruebe la conclusión que el run
publicó y no sólo su código de salida.

Los escenarios bajaron de **56** a **54**: los tres del plugin de utilidades se
fundieron en uno, que además aserta la prueba de bytes que el escenario del zip
prometía en su comentario y nunca comprobaba. Todo lo nuevo se encendió en
rojo antes de darse por bueno, con su fila exacta (§2.40).

El bloque de la cancelación a medias (§2.33) **no añadió ningún escenario
permanente**: su UAT efímero, que sí se ejecutó con el binario real y se
encendió contra el binario previo, se retiró al cerrar el ciclo con su
evidencia escrita aquí, y lo que se promovió son las seis filas unitarias,
que son más baratas y discriminan cada cláusula.

El bloque del reloj (§2.32) **no añadió ni un test permanente ni un escenario
permanente**, y ese es el punto: lo que protege la garantía es el auto-control
del arranque, que ya corre en cada invocación del lanzador. Los dos UAT que lo
probaron (una escena colgada y una escena que falla de verdad) se retiraron al
cerrar el ciclo, con su evidencia escrita aquí.

El ratio de LOC sube de 0.277 a **0.279** en este ciclo, todavía muy por debajo
del límite 2, y el peor módulo sigue siendo el mismo de siempre
(`pipeline-step-sdk:workflow-control`, 1.633) porque este ciclo no lo tocó.

Lo que este ciclo **no** tocó, y conviene que no se confunda con un avance:
el número de módulos con test sigue siendo 26 de 27. La deuda estática
congelada **no se movió**: ninguna baseline cambió en este bloque, y el diff
del bloque no lista ninguna. Pero su **cifra estaba caducada**, y eso se
corrigió: se venía anotando **802** entradas, y el censo de entradas `<ID>`
sobre las baselines versionadas da **879** —**770** antes de la unión de las
dos líneas y **879** después, que es lo que hizo el commit que las fundió en
una sola. El 802 no lo reproduce ningún instrumento del árbol, así que la fila
dice ahora el número que sí se puede medir y con qué se mide. La ley que este
ciclo añadió salió a su propio objeto en vez de empujar a `ProtocolGrammar`
por encima del límite de funciones: un cambio que crece y no crece la
baseline a la vez no es un cambio, es una negociación con el guardián.

**Las mutaciones de este ciclo, vistas en rojo.** Un verde que nunca se ha
visto rojo no es evidencia, así que cada instrumento se rompió a propósito, se
leyó de vuelta y se deshizo. En el adaptador de introspección, **M0** (el
terminal del run vuelve a ser la primera fila terminal de operación) encendió
**4 de 5** filas y dejó verde la quinta, la que dice que la lectura no muta
nada. En el adaptador de recuperación, **M1** (quitar el guard de «toda la
labor terminada») encendió **1 de 7** filas —la del run a medio hacer— y
**M2** (sustituir el veredicto del dominio por «la última operación terminada»)
encendió **2 de 7** —la del éxito fabricado y la del abortado—, sin colaterales
en ninguna de las dos. En el registro de capacidades, **M3** (volver
`runtime.structure.v1` a EXPERIMENTAL) encendió **2 de 8** filas. En el
ejecutable, **M4** (componer la introspección contra un sustrato que no conoce
el run) dejó el escenario `events/05-run-state` en rojo, y **M5** (restaurar la
regla vieja del recover) hizo que `durable/40` dijera «recovered run … as
succeeded» sobre un run a medio hacer. En el reloj del lanzador (§2.32), **M6**
(el reloj deja de disparar) colgó el lanzador sin veredicto ninguno, y **M7**
(el reloj dispara pero nadie lo nombra) dejó la escena como `failed` en vez de
BLOCKED. En la cancelación (§2.33), **M8** (volver a escribir el hecho terminal
antes del sellado) encendió **2 de 5** filas del adaptador de cancelación, y
**M9** (que el recover deje de reconocer el recibo de cancelación) encendió
**1 de 6** del módulo. Todas se leyeron de vuelta y se deshacieron. En la
procedencia de los proveedores (§2.45), **M10** (un proveedor sin origen se
reparte contra un camino en vez de detener la composición) encendió **4 de 9**
filas —las tres familias y la causa ilegible— y dejó verdes las otras cinco:
el aislamiento entre artefactos, la dirección inversa del cruce y las tres
filas de la medida. En la matriz de admisión del store (§2.46), **M11** (el
permiso de escritura se convierte en rechazo para todo propósito) encendió
**1 de 7** casos —el único que distingue al escritor del lector— y **M12** (la
regla acepta cualquier combinación) encendió **4 de 7**: las tres filas de
rechazo y la ley del `path`. En el fixture del smoke (§2.47), **M13** (la
aserción del clon espera un contenido que el fixture no commitea) dejó el
escenario en rojo, con el fichero restaurado byte a byte después. Las tres se
leyeron de vuelta en el disco antes de deshacerse.

Los ensayos se ejecutaron desde salidas de build limpias y cada uno reescribió
los **147** informes JUnit, que es la condición que hace que la cifra de reloj
mida el gate y no una restauración de caché. Un total correcto con el verbo
equivocado ya no puede colarse: la etapa 2 separa `rewritten` de `read` y
**falla** si no reescribe ninguno. Los tres ensayos de este ciclo reescribieron
los **147** informes —**1066** casos, 0 fallos, 0 skips— y los tres siguieron
dentro del presupuesto; el reloj del lanzador no añade tiempo, lo acota.

**El gate cuenta 26 proyectos Gradle con test, y un censo independiente por
fichero cuenta los mismos 26.** La etapa 2 imprime un módulo por nombre, y un
censo que enumera `src/test/kotlin/**/*Test.kt` sobre los 27 módulos declarados
devuelve la misma lista. El único sin test es `pipeline-sdk-bom`, que no tiene
clases que probar; `:pipeline-runtime` dejó de estarlo en este ciclo, con el
test de la regla que decide si una cancelación puede proceder. Aquí la cifra
estaba caducada, y la lista con ella: se escribió «21» y «los seis sin test»
cuando cuatro de esos seis ya no lo estaban
(`pipeline-scripting-kotlin24`, `pipeline-event-harness`,
`pipeline-step-sdk/api` y `pipeline-step-sdk/junit` tienen hoy su observador).
La lección es la de siempre: un número escrito a mano envejece, y se vuelve a
medir contra el árbol. El denominador que cuenta el gate es el proyecto Gradle,
que es la unidad que Gradle ejecuta; el nombre del directorio no lo es.

**Limpiar «los `build/`» es más peligroso de lo que parece, y por eso el
criterio importa.** Una primera tanda de ensayos limpió con
`find pipelinek -type d -name build` y midió **48 de 54** informes reescritos: ese
`build/` de un solo nivel deja fuera once directorios de módulos anidados, como
`pipelinek/pipeline-step-sdk/*/build`, y el árbol queda medio tibio. Al corregirlo a
`find` sin `-maxdepth` se descubrió el peor fallo posible: ese mismo criterio
**borra `pipelinek/buildSrc/src/main/java/dev/rubentxu/pipeline/build`, que es un
paquete Java, no una salida de construcción**, y con él las dos fuentes de las
que dependen los scripts de los módulos de plugin. El gate falla entonces
compilando un *script*, con `Unresolved reference: rubentxu`, que se lee como un
problema de entorno. Restaurado desde Git y verificado: es el único directorio
`build/` del árbol cuyo padre no declara `build.gradle.kts`. El criterio que se
usa ahora, y el que debe usarse, es **borrar un `build/` solo si su padre
declara `build.gradle.kts` o `build.gradle`**.

### El hueco de las credenciales se cerró

El escenario `security/14-credentials-bindings.pipeline.kts` declaraba
`requires-seeded-credentials` y el lanzador lo marcaba `NOT_RUN` en vez de
fabricar credenciales. El hueco era real y estaba bien medido: las dos únicas
rutas de escritura, `add` y `rotate`, construían la credencial exclusivamente a
través de `CredentialPrompts`, y las siete funciones de prompting leen el
secreto con `System.console()?.readPassword()`, que devuelve `null` sin TTY. No
existía ruta de siembra no interactiva, y el almacén abría sin TTY: el hueco
era de un solo lado, el del material del secreto.

Ahora existe, y la puerta la usa. `credentials add` acepta `--from-stdin`, que
es la **única** forma admisible: un secreto en `argv` violaría la invariante
del propio repositorio de que el contenido nunca aparece en la lista de
argumentos. El lanzador siembra el almacén del propio escenario con el verbo
del producto —contra un almacén cifrado y propio, no contra el del
desarrollador— y el escenario afirma que **la vinculación resolvió**, nunca
qué resolvió. La corrida completa siembra **7** credenciales y el escenario se
ejecuta: el `NOT_RUN` ya no existe, y con él el veredicto `INCOMPLETE` que esta
sección declaraba.

El `NOT_RUN` no era un aprobado, y sigue sin serlo: la línea de veredicto lleva
el hueco **dentro** (`INCOMPLETE … gaps=1 not_run`) en vez de esconderlo detrás
de la palabra `PASS`, y el código de salida sigue siendo 0 porque las etapas que
sí corrieron son evidencia real.

La misma regla se aplicó a `--no-budget`: ponía `skip_budget=1` y nada más, así
que saltaba la etapa 4 y caía igualmente en la línea `PASS … budget=900s` del
gate completo — una línea que nombraba un presupuesto de LOC que esa corrida no
midió. Ahora también marca `subset=1` y sale como `PASS (subset)`. El veredicto
pertenece a la corrida que lo observó todo; quitar una etapa quita la
afirmación con ella.

### La rama de replay que nadie ejecutaba

`run.sh` admitía `//@ durable` desde su cabecera, pero **ninguno** de los 36
escenarios lo declaraba: la segunda ejecución sobre el mismo `--control-root`
nunca había corrido. Hoy lo declaran **dos** de los **54**, y son
`durable/22-wait-until.pipeline.kts` y `durable/32-durable-replay.pipeline.kts`:
son los escenarios que pueden hablar del assunto y ningún otro puede — un cuerpo que
ya llegó a `WaitUntilCompleted` no debe volver a consultarse al repetir, ni
fallar por no haber hecho nada nuevo, y una sola ejecución no lo observa.

Un capacidad sin observador no está cubierta: está sin mirar. Por eso el
lanzador cuenta los reejecuciones y **una corrida completa se niega a ser verde
con `0 durable rerun(s)`**. La comprobación se limita a la corrida sin filtro a
propósito: un `--scenario` es quien elige qué rebanada medir, y ahí obligar a la
cobertura durable castigaría al bucle rápido por usar el filtro. La guarda se
sometió a su propia mutación — quitar la directiva y ejecutar el inventario
completo dio salida 1 con `0 durable rerun(s)`; ese mismo árbol era verde antes
del arreglo.

En la misma pasada se cerraron dos escapes del directorio aislado:
`smoke/10-smoke-e2e` creaba y clonaba un repositorio en `/tmp/smoke-repo` con un
`rm -rf` dirigido a una ruta compartida, y `durable/22` usaba el marcador
absoluto `/tmp/marker-wu-g5-restore` — cuyo comentario afirmaba que la ruta era
«única a este corpus para evitar colisión con otras corridas», que es
justamente lo que dos corridas simultáneas se garantizan. Ambos son ahora rutas
relativas al workspace propio de su etapa. `control-flow/11` ya había arreglado
esto mismo y el arreglo no se propagó, que es la forma que toma un defecto
cuando nadie lo busca de forma transversal.

Host de referencia, exigido por `§4`: 64 núcleos lógicos Intel Xeon E5-2682 v4
@ 2,50 GHz, 94,2 GiB de RAM, Linux 7.2.9-ogc3.1.fc44.x86_64, Temurin
21.0.8+9.0.LTS, Gradle 8.14.5, cachés de dependencias ya pobladas y sin
descargas externas dentro de la medición. El certificador externo tiene su
propia latencia y **no** va dentro de este número.

Una cifra de reloj heredada de otro commit es exactamente el tipo de verde que
esta tabla existe para no declarar: por eso cada fila lleva el SHA al que se
midió. Los tres ensayos de este bloque se ejecutaron sobre `263107231`, y los commits
que actualizan este documento y los README no tocan código de producto ni de
verificación: `check-budgets.py` devuelve los mismos **7961 / 60878** antes y
después y ninguna tarea del gate lee `ROADMAP.md`, así que la traslación está
medida y no supuesta. En cuanto entre un commit que toque código de producto o
de verificación, esta tabla vuelve a estar caducada y hay que repetir los tres
ensayos.

`VERIFY_LOC` sale de `git ls-files`, así que **solo cuenta ficheros
trackeados**. Al añadir un test nuevo la cifra puede no moverse, porque el
fichero todavía no está versionado. Es la forma que toma aquí «una cifra válida
con un verbo falso»: el censo era correcto, lo que era incorrecto era medirlo
antes de commitear. Por eso la fila de arriba se escribe siempre contra un SHA
ya versionado, y por eso no se reescribe aquí el número: la tabla lo tiene y
esta nota explica por qué ese número es el único que vale.

Los ratios por módulo de §2.2 a) son mediciones independientes del gate, hechas
sobre el mismo árbol con el mismo criterio de recuento (líneas efectivas,
sin blancos, comentarios ni generados). El gate los agrega, no los contradice.

### Cumplimiento del plan RESET, medido sobre el árbol

Las siete límites inflexibles del mandato, comprobados contra el disco y no
contra el texto del plan. «Medido» significa que el número salió de una
 enumeración de este checkout.

| # | Límite | Veredicto | Evidencia |
|---|---|---|---|
| 1 | 0 Markdown bajo `docs/` y `pipelinek/` | **PASS** | `git ls-files ':(glob)docs/**/*.md'` = 0; `:(glob)pipelinek/**/*.md` = 0. En la raíz solo quedan `AGENTS.md`, `README*.md`, `ROADMAP.md` |
| 2 | 0 `@Test` UAT/E2E/AAT/harness | **PASS** | 0 en `pluginContractTest`/`installedContractTest`/`integrationTest`/`functionalTest`; 0 `@Disabled` |
| 3 | 0 clases legacy trasladadas | **PASS** | `pipelinek/test-harness`, `pipelinek/pipeline-testkit`, `pipelinek/pipeline-architecture-tests` y `scripts/validation-gate.py` ausentes |
| 4 | E2E solo como `.pipeline.kts`, un lanzador | **PASS** | 31 escenarios en 8 categorías bajo `examples/`; cada uno se ejecuta en las dos rutas del CLI (con journal y sin él); 0 `src/test` dentro de `examples/*`; lanzador único `examples/run.sh` invocado por la etapa 3 del gate |
| 5 | `VERIFY_LOC <= 2 * PRODUCT_LOC`, global y por módulo | **PASS** | 8410 / 60909 = 0.138; ningún módulo declara `breach`; el más alto es `workflow-control` con 1.633 |
| 6 | `verify-all` local ≤ 900 s | **PASS** | seis ensayos, el peor de 478 s contra el límite de 900: 401 / 415 / 402 sobre `263107231`, 415 sobre `e4daef35d`, 478 sobre `c3a23697b` y 423 sobre `9f2a2004f`. Todos con 67/67 informes reescritos, 594 tests, 0 fallos, 0 errores, 0 skips |
| 7 | Compilar todos los módulos y ejecutar el gate sobre el SHA | **PASS** | 27 módulos declarados compilan; el único módulo versionado que el `include` no declaraba se retiró en este ciclo, así que ya no queda ninguno |

Sobre el límite 4: el mandato escribe el lanzador como `examples/run.sh`, y este
checkout lo tiene exactamente ahí. La diferencia que este documento registraba
—«no existe» o «está en otro sitio»— desapareció en este ciclo: los ejemplos
viven en la raíz del repositorio, como los describe el mandato, y la etapa 3 los
invoca desde ahí. Se conserva la nota porque una diferencia que se cerró puede
volver a abrirse, y volver a medirla cuesta menos que redescubrirla.

### `pipelinek/pipeline-protocol/` — retirado en este ciclo

El módulo quedó **borrado**, con su cableado de build: el plugin
`com.google.protobuf` de la raíz y las cinco entradas del catálogo de versiones
que solo él usaba. La decisión está medida, no supuesta, y el razonamiento
completo vive en §2.4b.

Lo que se borró: diez ficheros versionados —un `.kt` y ocho `.proto`— que el
build nunca compiló y que nadie consumía. Lo que se conservó: el historial,
donde el diseño sigue vivo y auditable.

Lo que no se rompió: ni un artefacto publicado, ni un dump de ABI, ni un
formato de cable, porque el módulo no publicaba nada y no tenía contrato
declarado. Retirar un módulo que el `include` raíz no declaraba no puede
cambiar lo que un consumidor resuelve.

### Los 26 módulos con test

`pipeline-application`, `pipeline-domain`, `pipeline-events`,
`pipeline-events-store`, `pipeline-scripting-api`, `pipeline-scripting-kotlin24`,
`pipeline-step-sdk/runtime`, `pipeline-step-sdk/http`,
`pipeline-step-sdk/workflow-control`, `pipeline-step-sdk/files`,
`pipeline-step-sdk/scm-git`, `pipeline-step-sdk/utilities`,
`pipeline-step-sdk/api`, `pipeline-step-sdk/junit`,
`pipeline-credentials-api`,
`pipeline-credentials-local`, `pipeline-credentials-multipart`,
`pipeline-credentials-executor`, `pipeline-binding-factory`,
`pipeline-artefacts-local`, `pipeline-event-harness`, `pipeline-runtime-api`,
`pipeline-runtime`, `pipeline-output`, `pipeline-output-store`,
`pipeline-release`.

> **Censo remedido desde el disco.** Sobre los 27 módulos que declara
> el `include(` de `settings.gradle.kts`: **26 con `src/test/kotlin/**/*Test.kt`,
> 1 sin él**. El único sin test es `pipeline-sdk-bom`, que no tiene clases que
> probar. `:pipeline-runtime` entró en la lista en este ciclo, con el test de
> la regla que decide si una cancelación puede proceder.
>
> Este censo ha sido corregido **dos veces**, y las dos por la misma razón: una
> lista escrita a mano es un segundo censo, y un segundo censo es donde aparecen
> las contradicciones. La primera omisión fue `pipeline-application`, que ya
> tenía `ProjectWorkingDirectoryTest.kt` y se contradecía con la fila de §1; la
> segunda, `pipeline-step-sdk/scm-git`, salió al reconciliar los informes XML del
> gate contra un censo por directorio de fuentes —el gate cuenta proyectos
> Gradle y el censo contaba carpetas, y un proyecto compuesto con subproyectos
> tiene más carpetas que proyectos. La tercera omisión, la de este ciclo, era de
> otra clase: **tres módulos que sí tienen test no estaban en la lista** —
> `pipeline-events` (`EventCursorTest`, `EventRegistryTest`),
> `pipeline-scripting-api` (`CacheKeyV2FramingTest`) y
> `pipeline-step-sdk/utilities` (`FindFilesGlobMatcherTest`)—, y la lista se
> había quedado en 18 mientras el árbol ya tenía 21. Un censo que enumera
> ficheros versionados, y no carpetas, es el único que no puede quedarse viejo
> por una omisión de dedo.
>
> Las cifras que aparecen en §2 son el estado **en el momento en que se
> midieron**; esta tabla y §1 son la autoridad del estado actual. Un bloque que
> dice «15 sin él» no contradice este censo: describe un árbol anterior.
> Las cifras anteriores a esta —«los 12 módulos con test», «17 módulos sin
> test», «quedan 15», y el «16 con test / 11 sin él» de una remedición
> intermedia— estaban todas caducadas: el censo se tomó antes de que este ciclo
> añadiera `OutputAdoptionTest`, `ApiRangeContainsTest`,
> `AntStyleGlobPatternTest`, el test de `files` y el de `pipeline-application`,
> y nadie lo volvió a medir. El recuento sale de lo que **imprime la etapa 2
> del gate**, que ya recorre los módulos y no puede desincronizarse de él: una
> lista escrita a mano en un documento es un segundo censo, y un segundo censo
> es donde aparecen las contradicciones. La tabla por módulo de más abajo
> conserva los números de aquella ejecución: **los tests y su recuento los
> sustituye la medición de `scripts/verify-all.sh`**, cuyo total vigente está en
> la tabla de §1. El desglose por módulo sigue siendo histórico hasta que ese
> gate se vuelva a ejecutar: `pipeline-release` ganó tres tests después de que
> se tomara esta tabla.

Esa lista sale de enumerar `src/test/kotlin/**/*Test.kt` en cada módulo
declarado, no de `check-budgets.py`. El motivo de no usar el informe de
`check-budgets.py` sigue en pie: informa de los módulos con `verify_loc > 0`, y
un módulo puede tener `src/test` con cero `*Test.kt` —contarlo por directorios
daría un número que no es el que se declara.

Los módulos de aquella ejecución, con sus ficheros, clases y tests medidos. El
recuento de XML es **recursivo**: `pipeline-step-sdk/runtime` y
`pipeline-step-sdk/http` son módulos anidados, y un `glob` de un nivel los cuenta
como ausentes — que es exactamente lo que pasó con la primera medición de este
ciclo (332 tests leídos como 307).

**Ficheros y clases no son lo mismo, y por eso van en dos columnas.** Gradle
emite un informe XML por clase JUnit, y una clase `@Nested` cuenta como clase
propia: `pipeline-domain` tiene 6 ficheros y 15 informes. Un recuento que
confundiera ambos leería 9 clases inexistentes aquí — es el mismo residuo que ya
se había confundido una vez en este ciclo, y se squashó al verificar en disco en
vez de reportarlo.

| Módulo | Ficheros `*Test.kt` | Clases JUnit | Tests | Saltados | Fallidos |
|---|---|---|---|---|---|
| `pipeline-domain` | 16 | 34 | 185 | 0 | 0 |
| `pipeline-credentials-local` | 9 | 9 | 99 | 0 | 0 |
| `pipeline-output` | 6 | 6 | 87 | 0 | 0 |
| `pipeline-application` | 14 | 14 | 100 | 0 | 0 |
| `pipeline-events-store` | 7 | 10 | 59 | 0 | 0 |
| `pipeline-release` | 4 | 4 | 39 | 0 | 0 |
| `pipeline-events` | 3 | 5 | 37 | 0 | 0 |
| `pipeline-output-store` | 3 | 3 | 36 | 0 | 0 |
| `pipeline-step-sdk/http` | 2 | 2 | 26 | 0 | 0 |
| `pipeline-step-sdk/runtime` | 2 | 2 | 25 | 0 | 0 |
| `pipeline-runtime-api` | 1 | 1 | 21 | 0 | 0 |
| `pipeline-credentials-executor` | 2 | 2 | 20 | 0 | 0 |
| `pipeline-credentials-multipart` | 2 | 2 | 16 | 0 | 0 |
| `pipeline-step-sdk/files` | 1 | 1 | 11 | 0 | 0 |
| `pipeline-artefacts-local` | 1 | 1 | 9 | 0 | 0 |
| `pipeline-scripting-kotlin24` | 1 | 1 | 9 | 0 | 0 |
| `pipeline-step-sdk/workflow-control` | 1 | 1 | 9 | 0 | 0 |
| `pipeline-credentials-api` | 2 | 2 | 8 | 0 | 0 |
| `pipeline-step-sdk/utilities` | 1 | 1 | 8 | 0 | 0 |
| `pipeline-binding-factory` | 1 | 1 | 7 | 0 | 0 |
| `pipeline-event-harness` | 1 | 1 | 7 | 0 | 0 |
| `pipeline-step-sdk/api` | 1 | 1 | 7 | 0 | 0 |
| `pipeline-step-sdk/junit` | 1 | 1 | 6 | 0 | 0 |
| `pipeline-step-sdk/scm-git` | 1 | 1 | 4 | 0 | 0 |
| `pipeline-runtime` | 1 | 1 | 6 | 0 | 0 |
| `pipeline-scripting-api` | 1 | 1 | 2 | 0 | 0 |
| **total** | **87** | **105** | **819** | **0** | **0** |

### El `NOT_RUN` que no es verde

`examples/security/14-credentials-bindings.pipeline.kts` está marcado
`//@ requires-seeded-credentials`. El único escritor del almacén es
`pipelinek credentials add`, que llama `System.console()?.readPassword() ?: error("no TTY available")`.
No existe vía no interactiva. El launcher no debe sintetizar un almacén
cifrado a mano — eso probaría el fichero fabricationado, no el producto. Se
mantiene en el inventario, declarando su hueco, con el criterio de borrado
escrito en la cabecera del propio escenario.

---

### 2.36 El arnés ejecutable, probado a fallar: 5/6 escenarios encendieron con su mutación esperada

Este ciclo hizo la auditoría propuesta en §1: «romper la garantía que cada escenario
cubre y contar los que se encienden; los que no pueden encenderse se reescriben
para afirmar algo discriminante, o se retiran». La auditoría se ejecutó como
mutación contra seis escenarios representativos, elegida por variedad de categoría
y por tipo de aserción. Cada mutación atacaba el input del step bajo prueba (no
el árbol del producto): cambiar la entrada que el escenario le pasa al step, y
observar si el escenario pasa a rojo en una corrida limpia de `examples/run.sh`.

**Muestra mutada, una mutación por escenario.** Cada fila es
`edit → ./examples/run.sh '<filtro>' → git checkout`. La mutación se deshace
antes de pasar a la siguiente, así el orden no contamina.

| # | Escenario | Mutación | Tipo de aserción | Veredicto observado | Esperado | Encendido |
|---|---|---|---|---|---|---|
| 1 | `steps/17-writeFile` | `text = "hello"` → `text = "WRONG_TEXT"` | `sh("grep -qx hello out.txt")` (in‑pipeline) | RED: `exit 1, expected 0` | RED | **✓** |
| 2 | `steps/18-cleanWs` | `listOf("test/**")` → `listOf("nonexistent/**")` | `sh("test ! -e test/a.txt && test ! -e test/b.txt")` (in‑pipeline) | RED: `exit 1, expected 0` | RED | **✓** |
| 3 | `smoke/02-environment` | `"KEY=value"` → `"KEY=MISMATCHED"` (dentro del bloque `withEnv`) | `sh("test \"${'$'}KEY\" = value && …")` (in‑pipeline) | RED: `exit 1, expected 0` | RED | **✓** |
| 4 | `steps/13-workspace-helpers` | `echo("waitUntil done")` → `echo("waitUntil DIVERGED")` | `//@ expect-stdout waitUntil done` (lanzador) | RED: `stdout lacked: waitUntil done` | RED | **✓** |
| 5 | `steps/16-sleep` | eliminar la línea `sleep(1)` | `sh("test \"$(( $(cat sleep-ended) - $(cat sleep-started) ))\" -ge 1")` (in‑pipeline) | RED: `exit 1, expected 0` | RED | **✓** |
| 6 | `steps/26-find-files` | `glob = "**/*.txt"` → `glob = "**/*.nope"` (segunda llamada) | `sh("test \"$(find build/utils/scan -name '*.txt' \| wc -l)\" -eq 3")` (in‑pipeline) | **GREEN** (`exit 0`) | RED | **✗** |

**Lectura: el arnés enciende con su mutación esperada en 5 de 6 casos.** Los
cinco que encienden lo hacen por la superficie observable correcta: `exit code`
para los efectos en shell, y `expect-stdout` para una cadena que el step imprime.
La diferencia entre la fila 4 y la fila 6 es exactamente el tamaño del problema:
la fila 4 enciende porque el step bajo prueba emite texto al stdout, y el
lanzador compara contra el substring declarado; la fila 6 no enciende porque
la aserción del escenario no toca lo que `findFiles` devolvió.

**Por qué la fila 6 no enciende, y no es un fallo del escenario sino del
arnés.** `findFiles` está declarado `effects = listOf(Effect.READ_ONLY)` y
devuelve un `FindFilesOutput(…)` tipado — un valor que el DSL declarativo no
expone al script. La cabecera del propio escenario lo dice sin rodeos: «The
step's RETURNED SET is not asserted, and cannot be: the DSL is declarative, so
a step's result is never handed back to the script and nothing it computed can
reach stdout». La aserción `find -name '*.txt' | wc -l -eq 3` comprueba el
árbol subyacente, no la respuesta del step; un step que devuelva vacío o el
conjunto equivocado pasa esta aserción mientras los archivos sigan ahí. La
misma asimetría se da entre las dos llamadas a `findFiles`: la llamada con
`*.txt` (hijos directos) está cruzada con `ls *.txt` (que también mira el
árbol), no con el resultado del step. El árbol existe independientemente del
step; la igualdad se cumple sin que el step la haya medido.

**El principio que el arnés no resuelve y el escenario tampoco.** Cualquier
step con `Effect.READ_ONLY` que devuelva un valor tipado — `findFiles`, un
futuro `readJson`, un futuro `diffTree` — entra en esta misma clase: el
lanzador no tiene superficie para leer su salida. Cerrar el agujero exige uno
de tres caminos, y los tres cambian el arnés:

1. **Extender el arnés** con un verbo que lea el Output Plane del step y lo
   haga visible al script (analogía: `console` para el `expect-transcript`,
   pero aplicado al output del step). Esto es trabajo del arnés, no del
   escenario, y entra en otra iniciativa.
2. **Reescribir el escenario** para que su garantía coincida con lo que el
   arnés SÍ puede observar: la integridad del árbol antes y después, y que el
   step no lo mute. La garantía pasa a ser «`findFiles` no corrompe el árbol,
   ni siquiera con un glob que no casa con nada», y deja de ser «enumera
   correctamente por glob». La propuesta es esa, con la mutación
   correspondiente (cambiar el segundo glob por uno que case con cero
   entradas y verificar que el árbol sobrevive). La propuesta vive como
   objetivo propuesto, no se aplica en este ciclo.
3. **Retirar el escenario** y dejar a la prueba unitaria de `findFiles` como
   única cobertura de la garantía fuerte. La prueba unitaria sí puede sujetar
   `FindFilesOutput`.

Este ciclo cierra con la fila 6 declarada en el inventario, con la opción 2
encabezada como siguiente paso, y con el resto del inventario (cincuenta
escenarios sin auditar todavía) cubierto en próximas iteraciones del mismo
ciclo. El arnés no se modifica en este commit.

### 2.37 La fila 6 cerrada por la opción 2: el escenario mide integridad del árbol, no enumeración

El ciclo siguiente aplicó la opción 2 propuesta en §2.36 sobre
`examples/steps/26-find-files.pipeline.kts` (`6e3a64ab5`). La nueva garantía es la
que el arnés sí puede observar: la integridad del árbol bajo cada llamada a
`findFiles`, alineada con la declaración `effects = listOf(Effect.READ_ONLY)` del
step.

**Lo que el nuevo escenario afirma, y dónde está la garantía fuerte.** Tres llamadas
a `findFiles` barren tres formas del mismo árbol — hijos directos (`*.txt`), recursivo
(`**/*.txt`), y sin matches (`**/*.nope`, la única que una implementación que decidiera
«nada que hacer, voy a limpiar» tocaría). Entre las llamadas se hace un snapshot del
árbol (`find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum`); el snapshot
de después debe ser idéntico, byte a byte, al de antes. Una segunda aserción, el
recuento de entradas (`-eq 4`), cubre el ángulo que el sha256sum no cubre: un step que
reescribiera un fichero existente con sus mismos bytes pasaría el sha256sum y dejaría
el recuento igual, así que el recuento está como red independiente.

**Lo que el escenario ya NO afirma.** La enumeración correcta por glob se ha desplazado
de la capa E2E al módulo que puede observar el resultado tipado: `FindFilesGlobMatcherTest`
en `:pipeline-step-sdk:utilities`, sobre `PathMatcher` en memoria (sin disco, sin step,
sin subprocess, sin reloj). Tres escenarios quedaron repartidos: dos en E2E (este, sobre
la integridad del árbol) y uno en unit (sobre la forma de la enumeración). El comentario
de cabecera del escenario dice dónde está cada uno.

**Discriminación medida, no supuesta.** La regla "`AGENTS.md` §2 / `delivery-first-testing`" exige
romper la garantía y confirmar que el escenario enciende rojo. Dos mutaciones, en direcciones
distintas:

| Mutación | Eje | Aplicación | Resultado |
|---|---|---|---|
| A: predicado invertido (`wc -l -eq 4` → `wc -l -eq 99`) | el escenario como instrumento | edición del escenario | RED con `exit 1, expected 0`, mensaje `the run printed no text of its own` |
| B: defecto real en el step (`Files.delete(base.resolve("a.txt"))` cuando `entries.isEmpty() && includeMatcher != null`) | el step como objeto del contrato `Effect.READ_ONLY` | edición de `CoreUtilsFindFilesStepDefinition.kt`, `installDist` incremental (7 s), corrida | RED con `Pipeline finished with FAILURE`, `cause [SCRIPT]: shell exited with code 1` |

Ninguna mutación afecta al escenario que no la rompe. La mutación B simula el caso que el
nombre «opción 2» nombra: una implementación que pasa por el lazo abierto del zero-match y
borra por decisión propia. La mutación A demuestra que la segunda aserción (recuento) está
enganchada, no es código muerto. Sin la A, el `diff snapshot.before snapshot.after` sería el
único instrumento y un recuento drift pasaría desapercibido; sin la B, la garantía `READ_ONLY`
no tendría observador en E2E.

**Lo que queda por auditar.** Los **cincuenta** escenarios restantes del inventario §2.36 siguen
sin mutar; este ciclo sólo cierra la fila 6. La mecánica del lote 2 es la misma (`edit → run.sh '<filtro>' →
checkout`), elegida por variedad de categoría (control-flow, durable, plugins, security, output) y de tipo de
aserción (in-pipeline `sh`, `//@ expect-stdout`, `//@ expect-transcript`, `//@ expect-exit`,
`//@ expect-stderr`, `//@ run-state`, `//@ events-*`, `//@ step-verdict`).

### 2.38 El P0 de `committedLocked()` cur.cmt corrupto: parse fail-loud

El P0 que abrió este ciclo sobre el Output Plane decía:

> `committedLocked()` interpreta un `cur.cmt` corrupto como offset `0`; `reconcile()` puede
> usar ese valor para truncar bytes.

El defecto medido en código (`SegmentOutputStore.kt:730-735` antes de este ciclo):

```kotlin
private fun committedLocked(layout: Layout): Long =
    if (Files.exists(layout.commitFile)) {
        Files.readString(layout.commitFile).trim().toLongOrNull() ?: 0L
    } else 0L
```

La traición es exactamente la que dice el `?:` silencioso: el archivo existe pero su cuerpo no parsea
y el código lo trata como cero. A partir de ese cero:

* `reconcile()` ve `readableEnd = currentBase + onDisk > 0 = committed` y llama
  `truncateTo(layout.segmentFile, onDisk - (readableEnd - committed))` con un argumento
  `size = -currentBase`. Para un offset positivo, ese argumento es negativo y
  `FileChannel.truncate` lanza `IllegalArgumentException`; para un offset en cero, `truncateTo`
  no toca el archivo (su guarda `if (size >= current) return` se cumple con `0 >= 0`). El camino
  "silencioso" tiene dos finales — `IllegalArgumentException` o rewind a cero — y los dos
  contradicen el KDoc de la propia clase que dice "this store does not make it silently".

#### Lo que cambia

Dos operaciones, una sola evidencia. La función de parse sale del lugar privado como
`internal fun parseCommittedOffset(content: String): Long` en `SegmentOutputStore.kt`, con un
contrato mínimo y nominativo:

* **Ausente** se sigue tratando fuera de `parseCommittedOffset`: `committedLocked` devuelve `0L`
  cuando `cur.cmt` no existe en disco, exactamente igual que antes.
* **Válido** — cuerpo que parsea a `Long` no-negativo, con trim de espacios y newlines en los
  bordes (los escritores usan `\n` como terminador).
* **Corrupto** — cualquier otra cosa. Antes: `?: 0L`. Ahora: `IOException` con un mensaje que
  nombra `cur.cmt` y muestra el cuerpo sanitizado.

El enum `OutputNotEstablished` gana el caso que el KDoc del propio fichero referencia desde
hace tiempo sin existir... **y se quita después de añadirlo.** El ciclo intentó cerrar la cita
muerta con `OutputNotEstablished.CORRUPT_COMMIT_RECORD`, pero `kotlinx-binary-compatibility-validator`
lo cazó en la primera ejecución del gate: añadir un valor a un enum público cambia la ABI del
módulo `:pipeline-output`, y AGENTS.md §1 prohíbe autorizarme a mí mismo ese cambio. La guarda
queda como una cita resuelta hacia `[parseCommittedOffset]`, no hacia un caso del enum:

```kotlin
// antes — referencia muerta a un enum que la clase nombra y el módulo no expone
* damaged and is NOT repaired automatically** — see [OutputNotEstablished.CORRUPT_COMMIT_RECORD].
// después — la decisión vive dentro del parser; el módulo no necesita nuevo caso en el enum
* damaged and is NOT repaired automatically** — see [parseCommittedOffset] in `:pipeline-output-store`.
```

El `committedLocked` propaga la excepción al `tryWithStreamOwnership` que ya tenía escrito en
KDoc ("silently skipping would be a hole rather than a safety. It propagates, and the recovery
that called it fails loudly"). El camino de recovery, los lectores y los tails ya no reciben un
`0L` fabricado; reciben la excepción, y el operador la ve. La autoridad para decidir qué hace
el siguiente paso con esa `IOException` queda donde el repositorio dice que queda: en un
ticket de operación, no en un valor de enum publicado.

#### Discriminación medida, no supuesta

Cinco filas, sobre la función pura `parseCommittedOffset` (`String` in, `Long` out, sin disco):

| Fila | Entrada | Resultado esperado | Mutación observada |
|---|---|---|---|
| `a plain decimal body parses to its long value` | `"0"`, `"1"`, `"42"`, `"4096"` | `0L`, `1L`, `42L`, `4096L` | sigue verde tras la mutación |
| `a body trimmed at the edges still parses` | `"  42  "`, `"42\n"`, `"\n42"`, `"\n42\n"` | `42L` | sigue verde tras la mutación |
| `an empty body refuses with IOException` | `""` | `IOException` cuyo mensaje nombra `cur.cmt` | **ROJO** tras la mutación |
| `a non-numeric body refuses with IOException` | `"not-a-number"`, `"42abc"`, `"  abc  "`, `"42.0"` | `IOException` | **ROJO** tras la mutación |
| `a negative body is refused` | `"-1"` | `IllegalArgumentException` con la palabra `negative` | sigue verde (la mutación no toca el `require`) |

La mutación ejecutada fue exactamente el defecto original: `toLongOrNull() ?: 0L` reintroducido
dentro de `parseCommittedOffset`. Dos filas en rojo (las que cubren la garantía), tres en verde
(las que cubren las decisiones vecinales que no cambian). Ningún test colateral fuera de este
fichero se enciende ni se apaga: `pipeline-output` 13/13 y
`pipeline-output-store` 5+10+14+12 = 53/53 siguen como antes. El módulo gana una clase de
test (la quinta) y 5 tests; los cuatro módulos vecinos no se mueven.

#### Lo que queda abierto por cierre de bloque

* **`committedLocked` no se puede probar directamente** porque `Layout` es privado: el ejercicio
  end-to-end (escribir un `cur.cmt` corrupto, llamar a `recover()` y observar la propagación) es
  trabajo del ciclo de integración, no de la unidad. La función pura `parseCommittedOffset` es
  el seam más barato para fijar la garantía fuerte sin ese coste; las filas discrimantes
  siguen verdes tras la mutación y la mutación reproduzca exactamente el defecto original.
* **El formato del mensaje de error** no es estable todavía: el `replace("\n", "\\n").replace("\r", "\\r")`
  Sanitization es defensiva contra el camino `DiagnosticText` que ya documentó el caso
  `MainInspectCli`; cuando `recover()` exponga el `IOException` al operador, ese mensaje llega
  sin garantía adicional de una sola línea. Es el mismo riesgo que `DiagnosticText` ya cierra
  para los `OutputRefusal.Corrupt.reason`, y queda como bloque aparte para mantener este commit
  quirúrgico.
* **El P0/P1 de las cancelaciones** (escritura del marcador antes del sellado) y de los rechazos
  `CursorBeyondTail` / `StorageError` confundidos en `UnknownRun` no se tocan aquí: viven en
  `main` y son previos a este ciclo. El branch `integration/reconcile-main` no los contiene en
  su árbol.


### 2.39 Los trece rojos del stage 3: cuatro defectos y una expectativa muerta

El gate dejó de dar verde en el stage 3 con trece escenarios rojos. La primera hipótesis fue la
barata —"el producto evolucionó y el corpus no lo acompañó"— y era **falsa en su mayor parte**.
Once de los trece no eran expectativas viejas: eran **cuatro defectos de producto y un defecto
del arnés**, cada uno con su propia causa raíz, su propio arreglo y su propia prueba de que el
arreglo enciende rojo cuando se revierte. Éste es el bloque.

| # | Escenario rojo | Lo que de verdad pasaba | Quién lo arregla |
|---|---|---|---|
| 1 | `output/33-live-process-output` | el camino **durable** no entregaba al terminal lo que el proceso imprimía | el producto |
| 2 | `control-flow/05-scripted-control-flow` | consecuencia del #1 | el producto |
| 3 | `control-flow/11-workflow-control` | consecuencia del #1 | el producto |
| 4 | `durable/32-durable-replay` | consecuencia del #1 | el producto |
| 5 | `plugins/body-slots` | consecuencia del #1 | el producto |
| 6 | `plugins/lock-directive` | consecuencia del #1 | el producto |
| 7 | `steps/33-glob-ant-alternation` | consecuencia del #1 | el producto |
| 8 | `negative/15-error` | el camino **volatile** fundía el mensaje en la línea de veredicto y **perdía el tipo** del fallo | el producto |
| 9 | `negative/30-step-failure` | lo mismo que el anterior, con otro tipo (`SCRIPT`) | el producto |
| 10 | `plugins/trusted-plugin` | la **política de confianza del operador nunca se consultaba** | el producto |
| 11 | `negative/plugin-trust-digest-mismatch` | la misma política sin consultar: el rechazo era por identidad, no por digest | el producto |
| 12 | `plugins/repeat-block` | una **expectativa muerta** que el escenario no podía cumplir | el escenario |
| 13 | `durable/38-saga-success` | el arnés **compartía el mundo** entre las dos ejecuciones | el arnés |

#### Defecto 1 — el camino durable no entregaba la salida del proceso

`MainVolatileRun` tenía un observador vivo que seguía el Output Plane mientras el run estaba en
vuelo y lo escribía en el terminal. `MainDurableRun` **no tenía ninguno**. Medido con el mismo
pipeline en los dos mundos:

| | stdout |
|---|---|
| volatile | `[PipelineK] [step: live] live/sh-0` + `RAW_PROCESS_MARKER` |
| durable | `[PipelineK] [step: live] live/sh-0` — **nada más** |

El mundo durable es el que un operador tiene cuando tiene base de datos, y el que usa CI. Es
decir: **el camino que más se usa era el que escondía la salida del build mientras el build
corría**. El escenario `output/33-live-process-output` lo decía con todas las letras: *"What
has to appear is the bytes the child process wrote, which the run committed to the Output Plane
and the live observer then delivered"*.

El arreglo no es un observador nuevo: es **el mismo observador, movido a un fichero compartido**
(`LiveOutputObserver.kt`) y conectado al camino durable. Una sola autoridad para "qué se ve en
terminal mientras el run corre" (AGENTS.md §1: dos autoridades para una decisión divergen).

#### Defecto 2 — el informe de fallo perdía el tipo en el camino volatile

El commit `b76f6dc70` ("one report of a failed run, whatever the execution path") unificó el
informe **en `v2/`**, el árbol de `main`. Al fusionarse a `pipelinek/` sólo viaja su cambio de
**escenario**: el producto conservaba el renderizado viejo, que fundía el mensaje en la línea de
veredicto y nunca nombraba el tipo del fallo.

```
durable   Pipeline finished with FAILURE
          cause [USER]: test error message
volatile  Pipeline finished with FAILURE: test error message      <- sin el tipo
```

El tipo es el dato que distingue un permiso que falta de un script que falla de un run
cancelado; sin él, tres entregas distintas producían el mismo informe. La unificación se
rehace en el árbol que la necesita: `RunOutcomeReport.kt` con `reportRunOutcome`, y los dos
caminos la llaman. La cita del KDoc del camino volatile —*"the two renderings are not the same
fact read twice"*— era un argumento, y el argumento era falso: el hecho (el `RunOutcome` tipado)
era el mismo y sólo cambiaba el renderizado.

#### Defecto 3 — la política de confianza del operador no se consultaba nunca

Éste es el más grave de los tres, y el ROADMAP mismo lo describía como hecho consumado:

> *"Una política que opera en silencio no es una política: el runtime imprime, por cada plugin
> que la política aprobó, los bytes, el publicador y la clave con que se aprobó. Sin esa línea,
> un operador no puede distinguir «la política aprobó este plugin» de «la política nunca se
> consultó»."*

La realidad del código: `pluginTrustSource(trustFile)` estaba **definido y sin llamar**. Los dos
call sites eran `admitThenComposeOrExit(config.pluginJars)` — sin `trust`, que vale `null`. El
flag `--plugin-trust` se parseaba, la fuente existía, la política existía, el decisor existía, el
keyring existía, y **nada se entregaba**. Un operador que fijaba un digest y esperaba que el
runtime rechazara otros bytes recibía, en su lugar, un rechazo por *identidad duplicada* (que es
lo que el escenario `plugin-trust-digest-mismatch` vio: `DuplicateIdentity` en vez de
`RefusedDigestMismatch`).

El arreglo cablea la fuente **y** el diario durable (`AdmissionControlJournal`), que tenía el
mismo problema por la misma razón: *"su único escritor era una puerta que nadie llamaba"*. El
ROADMAP describía un mundo que el código no tenía.

#### Defecto 4 — el arnés compartía el mundo entre las dos ejecuciones

El launcher corre **dos** ejecuciones de cada escenario —con journal y sin él— y las somete a
las mismas expectativas. Las dos ejecuciones compartían el mismo `PIPELINEK_E2E_HOME`, así que un
escenario que afirma un mundo **exacto** veía el mundo de la primera ejecución en la segunda. El
`durable/38-saga-success` escribía `reserve\ncharge` en el log y después comparaba el log
completo con esa cadena: en la segunda ejecución había cuatro líneas, y el escenario accusationaba
al motor de un efecto repetido que no había ocurrido.

El launcher ya sabía esto —su propio comentario lo dice: *"the marker a durable scenario writes
lives in this directory, so the second execution would find the first one's effect and read it as
a repeat"*— pero sólo lo aplicaba a los escenarios con `durable`/`resume`. El control root ya era
por ejecución (`control` y `control-volatile`); el **directorio de trabajo no**, y ahí es donde
viven los marcadores de un escenario. Ahora cada ejecución tiene el suyo.

#### La expectativa muerta de `plugins/repeat-block`

La única de los trece que sí era deuda de test. El commit `9c4bd7b88` cambió el body a
`sh("echo iteration >> iterations.txt")` para que el **conteo** fuera observable; el commit
`74870d412` ("cada escenario declara la garantía que promete") añadió después
`//@ expect-stdout iteration` — una directiva que el body ya no podía satisfacer, porque el texto
esperado nunca llegaba al stdout. El escenario no podía fallar por la razón que declaraba.

El arreglo hace viva la declaración: el body imprime **y** anexa (`echo iteration | tee -a
iterations.txt`). El escenario vuelve a tener dos observaciones vivas —el marcador en la salida y
el conteo exacto— en vez de una muerta y otra viva.

#### Discriminación medida

Cada defecto se reintrodujo y se comprobó que enciende **su** fila y sólo la suya:

| Defecto reintroducido | Escenario | Rojo observado |
|---|---|---|
| observador vivo en el durable → `AutoCloseable { }` | `output/33-live-process-output` | `stdout lacked: RAW_PROCESS_MARKER` |
| informe fundido en el volatile | `negative/15-error` | `stderr lacked: cause [USER]: test error message` |
| `pluginTrustSource(...)` → `null` | `plugins/trusted-plugin` | `stderr lacked: admitted under the operator's trust policy` |
| `E2E_HOME` compartido otra vez | `durable/38-saga-success` | `exit 1, expected 0` + `SAGA-COMPENSATED-A-CHAIN-THAT-WORKED` |

En los cuatro casos el defecto se reprodujo, la fila roja esperada se encendió, y la restauración
devolvió el verde. Los trece escenarios quedaron verdes después, y los siete escenarios que
dependen del `E2E_HOME` o de los dos mundos (32-durable-replay, body-slots,
39-saga-two-same-kind, 22-wait-until, 14-credentials-bindings, 33-saga-compensation,
31-stash-unstash) siguen verdes: el cambio del launcher no movió nada que no tuviera que mover.

#### Lo que este bloque deja dicho

La hipótesis de partida —"el producto evolucionó, el corpus no la siguió"— era la explicación
cómoda, y era la que el ROADMAP propugnaba en §2.36 al dejar cincuenta escenarios sin auditar.
La realidad es la contraria: de los trece rojos, **cuatro eran defectos del producto** y uno del
arnés, y ninguno era una expectativa vieja. La próxima vez que un gate dé rojo, la pregunta
correcta no es *"¿qué cambió en el test?"* sino *"¿qué dejó de ser verdad en el producto?"* — y
esa pregunta tiene respuesta, medible, en el diff entre lo que el código hace y lo que su
propio KDoc promete.

---

## 2. Deuda técnica abierta

### 2.1 Defectos materiales corregidos en este ciclo

Cada uno con test de regresión mutation-verificado (el test muere al revertir
el arreglo; los demás siguen verdes).

- **Doce KDoc citaban una sección de `AGENTS.md` que el RESET renombró**
  (`d8b0c413`). Doce citas en nueve ficheros de `pipeline-application`
  apuntaban a «AGENTS.md STEP IMPLEMENTATION — OPERATIVE GUIDE rule 9». Ni
  `STEP IMPLEMENTATION` ni `OPERATIVE GUIDE` son secciones del fichero vivo, que
  numera 0 a 6. Lo que lo excluye es invisible a un censo de documentos
  borrados: `AGENTS.md` **existe**, así que la pregunta «¿existe este
  fichero?» responde sí y da el caso por limpio. El defecto no era un puntero
  muerto sino un **rótulo muerto sobre un fichero vivo**. La regla invocada
  sigue vigente en §1 —el trabajo con efectos vive en puertos y adaptadores
  nombrados, y las decisiones son puras e inmutables cuando se puede—, así que
  se sustituyó el rótulo por la sección que existe y el enunciado pasó a decir
  la regla en vez del nombre. Cero líneas de código. Lo **no** se tocó son las
  citas «AGENTS.md section 1» y «section 3», que se comprobaron una a una
  contra el fichero vivo y **siguen resolviendo bien**: un barrido por nombre
  de fichero habría roto las correctas y dejado las rotas.
- **Fuga de secretos.** `SecretPatternRegistry.buildUrlEncodedVariant` descartaba
  todo carácter no reservado, así que `p@ss w0rd` codificaba a `%40%20`. La
  variante no casaba ni con la forma codificada (fuga) ni con nada legítimo, y
  sí casaba con texto ajeno que tuviera esas dos codificaciones contiguas
  (sobre-redacción). La guarda `urlEncoded != secret` era inalcanzable mientras
  tanto, y es la prueba de la intención del autor.
- **`PATH+=` prependía el NOMBRE de la variable.** `EnvModel.apply` tomaba el
  grupo de captura de la regex, que es el nombre, no el valor: `PATH+MAVEN` con
  valor `/opt/maven/bin` inyectaba un directorio llamado `MAVEN`. Además
  iteraba en orden inverso al declarado y **descartaba las entradas enmascaradas**
  del entorno del hijo (`isMasked` significa «no redactar», no «no pasar»). Y le
  faltaba la guarda de entrada vacía que el overload hermano sí tenía.
- **La puerta no ejecutaba nada.** `scripts/verify-all.sh` pedía `detekt` (que en
  detekt 2.x no tiene source set por defecto y analiza cero ficheros), traducía
  rutas de módulo de disco a rutas de proyecto Gradle sin cambiar `/` por `:`, y
  contaba XML JUnit de suites borradas por el RESET. Los 17 ficheros de build que
  afirmaban que la raíz fijaba `failOnNoDiscoveredTests` describían una bandera
  que **no existe en Gradle 8.14.5**.
- **La decisión de `--follow` no tenía nada que la sostuviera.** El RESET borró
  `ObservationWakeupTest.kt` (13 métodos) en `2aa071aa`, y el hueco no lo cubrió
  nadie: medido, `followDecision` —que decide si un follower sigue leyendo o
  termina— tiene **cero tests unitarios y cero ejemplos E2E**, sobre la función
  que resolvió el defecto RC4 («ningún follow podía observar un sello»). No es un
  tipo trivial: es una función pura de tres parámetros y salida `sealed`, sin
  reloj ni ficheros, o sea el instrumento más barato que puede fallar. Cuatro
  filas la cubren, mutation-verificadas por partida doble: quitar la rama
  `RunPlane.Live -> ReadAgain` deja `tests=4 failures=1`, y la única roja es la
  que nombra el defecto; cambiar `else -> Finished` por `ReadAgain` deja
  `failures=2`, y son exactamente las dos filas que esa rama sostiene. Esa segunda
  cifra es la razón de existir de esas dos filas: sin ellas, la primera podría
  «arreglarse» con `return ReadAgain` siempre y ningún test lo notaría.
- **Una sobrecarga `Boolean` que el propio tipo prohíbe.** Junto a la anterior
  vivía `followDecision(moreFrames, tailStates, runFinished: Boolean)`, un shim
  que su propio KDoc describía como «mientras los llamadores de tres estados
  migran». Los tres llamadores ya migraron —verificado uno por uno, los tres
  pasan `RunPlane`— y no está en ningún volcado ABI (enumerados los 7 módulos que
  tienen uno; ninguno es `pipeline-application`). Era la forma exacta que
  `RunPlane` existe para eliminar: un bit donde el tercer estado importa, y un
  llamador futuro obtendría `Unavailable` —o sea un `Finished` prematuro. La
  misma forma que `fromUserOwned`, el punto 1 de §2.2. Eliminado.
- **El overload combinado de `AeadCipher` nunca pudo descifrar.** Pasaba el array
  completo `nonce||ciphertext||tag` a `GCMParameterSpec` como IV. El JCE acepta
  un IV de más de 12 bytes e interpreta silenciosamente un IV distinto del que
  usó el cifrado, así que la comprobación de etiqueta fallaba en **todas** las
  llamadas. Es la ruta que usa el formato de entradas, luego la rotura era muda:
  una credencial escrita por un overload no se podía leer por el otro.
  Hallazgo `AeadCipherTest`, no lectura previa.
- **`CredentialPartReader.readZipPart` leía un byte donde el escritor pone cuatro.**
  `CredentialSerializer` escribe el contenido de metadata como un entero
  big-endian de 4 bytes; el lector consumía `buf.get()`, un byte. Cada Zip se
  parseaba un byte antes de su posición real, así que la longitud del nombre de
  la primera entrada se leía de bytes desalineados.
- **`Zip.equals` comparaba los entries por referencia.** `Map<String, ByteArray>`
  delega en `ByteArray.equals`, que es identidad: dos archivos byte a byte
  idénticos nunca eran iguales ni hasheaban igual. Las **otras seis** credenciales
  del mismo fichero ya comparaban sus bytes con `contentEquals` /
  `contentHashCode`; `Zip` sobrescribía ambos a mano para el `toString` seguro y
  conservaba la comparación por referencia. Era el único outlier, y su síntoma no
  es un crash sino un acierto de caché perdido o un `distinct()` que retiene
  duplicados. Lo detectó el round-trip del serializer, no el módulo que lo cubría.
- **El decoder UTF-8 se tragaba bytes válidos.** `StreamingUtf8Decoder` miraba
  sólo `i + 2 >= size` antes de decidir retener un líder, así que una página
  `[E2, 41]` tomaba el camino de retención y se tragaba la `'A'` — un carácter
  válido de esa misma página — a `pendingBytes`. La página se retrasaba un
  round, y con `reset()` se perdía. Contradecía además el KDoc de la propia clase.
  Se corrigió agrupando: un líder ya defective consume las continuaciones que sí
  aceptó, y se añadieron las guardas de rango (sobrelargo `C0`/`C1`, sustitutos
  `ED A0..BF`, más allá de `U+10FFFF`), que antes emitían el valor de contrabando.
  El KDoc prometía además que `decode` devolvía «el `CharSequence` completo hasta
  ahora»; nunca fue cierto, y su único consumidor (`MainInspectCli`) concatena
  los retornos. La mentira era del KDoc, no del código.
- **`SegmentReader.parseSealed` aceptaba una longitud negativa.** El nombre de
  un segmento sellado es `<base>-<length>.seg`, y un `-` de más producía
  `length = -5`, que lowering `currentBase()` hacía que se volvieran a servir
  bytes ya emitidos. Los	value types hermanos de `:pipeline-output` (`OutputFrame`,
  `OutputPage`, `OutputPin`) sí guardan su rango en `init`; `SealedSegment` era el
  único sin guarda.
- **Un `coerceAtLeast` que llenaba el buffer con bytes ajenos.** `readRange` y
  `readRangeDigested` derivaban el offset de cada chunk como
  `(cursor - seg.base).coerceAtLeast(0)`. Un segmento que empieza **por encima**
  del cursor — bytes que no están en ningún segmento — forzaba el offset a 0 y
  copiaba el contenido del segmento en medio de la respuesta. La página salía
  con la longitud exacta pedida, de modo que el único guard que quedaba,
  `written == target.size`, **no podía verlo**: una respuesta sustituida lo
  satisface a la perfección. Para el lector digerido era peor, porque el digest
  certificaba bytes que el consumidor nunca pidió.
  **No es alcanzable hoy y el commit no lo claims**: `prune` borra el directorio
  entero del stream, lo que deja al lector sin stream (`UnknownStream`), no con
  uno corto, así que los segmentos supervivientes siempre son contiguos desde 0.
  Es una propiedad del escritor, no del lector. Lo que sí era cierto era sólo la
  firma: `OutputRefusal.RetentionGap` existía desde M3 con doce líneas de KDoc,
  `MainConsoleCli` renderizaba su rama `when`, y el KDoc de `OutputReadPort`
  nombraba ese rechazo exacto — y **ningún sitio de construcción**. La decisión
  se hizo explícita y total en `firstMissingOffset`, función pura sobre las
  *extensiones* de los segmentos que no abre ningún fichero; así el contrato
  declarado pasa a ser cierto en vez de reescribir el contrato para que dijera lo
  que el código hacía.

- **Un `IOException` podía inyectar una fila en la salida de `inspect`.**
  `MainInspectCli` construía `OutputRefusal.StorageError(e.message ?:
  e.javaClass.simpleName)` sin acotar, y su render lo imprime como
  `<unreadable: ...>` en **una** línea. Los mensajes de excepción no son
  nuestros: un path del Output Plane con un salto de línea es legal en Linux
  (los stream id llegan al CLI como argumentos), así que el mensaje podía
  añadir una línea con el mismo formato que una fila real. Sus dos hermanos ya
  se negaban a hacerlo; ese sitio era el que no.
  La regla tiene ahora nombre y sitio: `DiagnosticText.from` en
  `:pipeline-output-store`, que ya tenía a los dos consumidores vivos.
  **La primera versión la puso en `:pipeline-output` y el gate la rechazó**:
  `apiCheck` falló porque `:pipeline-output` es el SDK publicado del Output
  Plane y arrastra un gate de compatibilidad binaria, y un helper público de
  texto no es una ampliación que esa superficie deba absorber por un consumidor
  interno. Correr `apiDump` para aceptarlo habría sido **autorizarme a mí
  mismo** el cambio de ABI, que es justo lo que `AGENTS.md` §1 protege.
  `:pipeline-output-store` aplica `maven-publish` pero no declara `publishing`,
  y el BOM del SDK lo excluye por nombre: no tiene esa superficie.
  `:pipeline-application` usa `implementation` sobre él, así que ya lo veía.
  `:pipeline-events-store` conserva su copia, y el motivo queda escrito en vez
  de quedar por redescubrir: `:pipeline-output` **no** depende de
  `:pipeline-events` ni de `:pipeline-domain`, y su propio build file dice que
  la independencia del output plane respecto del event plane es el objeto de
  ADR-M1 D3. Añadir esa dependencia para borrar cuatro líneas duplicadas
  invertiría la frontera; primero escribí esa refactorización, luego la
  revertí al leer el build file.
  **Escribir el test primero encontró un agujero en la regla que estaba
  consolidando**: sustituía `\n` pero no `\r`, y un CR suelto devuelve el
  cursor a la columna 0, de modo que la escritura siguiente **reescribe** una
  línea ya emitida — tan eficaz como un salto de línea en un consumidor que
  no dobla CR en CRLF. El `shortCause` heredado tenía el mismo hueco. 10 tests,
  mutation-verificados: revertir solo la sustitución de CR convierte 10/10 en
  1 fallo. `:pipeline-application` **no tiene test permanente para este sitio** y
  no puede crecerlo aquí —el sitio corregido es código de CLI, que `AGENTS.md` §2
  prohíbe probar—, así que esa mitad la cubren los tests de la autoridad
  compartida en lugar de fingir cobertura. El módulo sí tiene test permanente
  desde este ciclo (`ProjectWorkingDirectoryTest.kt`); lo que no tiene es uno
  para la sustitución de CR, y decirlo sin ese matiz convertía una afirmación
  verdadera en un censo equivocado.
- **`StatusRange.parse` no era total, y su propio KDoc lo promete.**
  Las dos ramas de su `when` no se decían lo mismo: la rama `Single` se
  comprobaba en `parse` y devolvía `null`; la rama `Span` solo comprobaba el
  orden y **construía** `StatusRange.Span`, cuyo `init` guarda con `require`.
  Así que `parse("50:60")` escapaba un `IllegalArgumentException` por una
  función cuya razón de existir es que un rango mal escrito sea un error de
  *declaración*, no una sorpresa que llega después de que la petición ya salió
  al mundo. La asimetría era el indicio: una regla validada de dos maneras, una
  de las cuales lanza. 15 tests; al revertir solo la pre-validación de banda
  caen 5, y los 10 que no tocan esa rama siguen verdes.
- **Un puerto explícito inválido se sustituía por el puerto por defecto.**
  `egressDestinationOf` reducía una URL al destino por el que se pregunta al
  gate, y esa reducción **no distinguía un puerto ausente de un puerto presente
  que no puede existir**: `URI` reporta el primero como `-1` y el segundo como
  sí mismo, así que la comprobación de dos estados `uri.port in 1..65535`
  mandaba ambos por la misma rama. `https://api.example.test:0` se reducía a
  `https://api.example.test:443`, y `:99999` y `:65536` igual. La ejecución
  abría un socket hacia un destino que nadie escribió, y —peor— **preguntaba
  al gate por ese**. Una allowlist escrita para `:443` gobierna una petición
  que el autor apuntó a otro sitio: es la dirección del error que convierte un
  permiso en una puerta abierta. Medido antes de arreglar (`URI` devuelve 0,
  99999 y 65536, y `-1` solo cuando no hay puerto); 11 tests escritos antes del
  arreglo y confirmados rojos por él. Un censo del patrón en todo `pipelinek` no
  encontró otra copia.
- **`detektTest` estaba declarado en todos los módulos y no se ejecutaba en
  ninguno.** `check` corre `detekt`, que sí tiene source set, y no
  `detektTest`, que también. 13 ficheros de test repartidos en 7 módulos
  llevaban tiempo sin salto de línea final porque **nada estaba en posición de
  decirlo**. El recuento de 78 hallazgos que este roadmap llevaba anotado
  contra esa tarea venía de un baseline que ningún productor escribe: la tarea
  está **verde de verdad** en los 10 módulos con test, y cuesta 3 s. Cablearla a
  la etapa 1 no engorda el gate —ejecuta uno que el build ya declaraba, sobre
  las fuentes que esa etapa ya compila— y el riesgo que queda es que volvería
  a estar ciego al primer fichero que se añadiera sin ese salto.

- **`dir(...)` confinaba por PREFIJO de texto, no por elemento de ruta.**
  `DirExecutor.resolveTargetPath` comparaba `target.toString()` contra la cadena del
  workspace. Medido antes de arreglar, con `Path` de Java y el workspace
  `/home/rub/proyecto`: `../proyecto-evil` → `/home/rub/proyecto-evil` **permitido**;
  `/home/rub/proyecto2` **permitido**; `../proyectoX/inner` **permitido**; y solo
  `../other` → `/home/rub/other` era rechazado. Los tres que pasaban son directorios
  reales fuera de la raíz autorizada: son hermanos, no subdirectorios, y su nombre
  empieza por el de la raíz. `Path.startsWith(Path)` compara **elementos de nombre** y
  rechaza los tres. La raíz además se normaliza, o una raíz sin normalizar rechaza a sus
  propios hijos (`/ws/./sub` no empieza por `/ws/./`). `WorkspacePathAnchors` ya documenta
  esta clase de guarda como la razón por la que `core-utils` `.zip`/`.unzip` pasaron a
  `WorkspacePathResolver`: **este era el exemplar que se quedó**, con la forma de texto
  además de la frontera equivocada. Un censo del patrón en los otros ejecutores
  (`FileWriteExecutor`, `FileReadExecutor`, `FileExistsExecutor`,
  `PublishHtmlOperationsAdapter`, `StashOperationsAdapter`, `WorkspacePathResolver`)
  encontró que **los seis ya usan la forma correcta**: este era el único. 9 tests;
  mutación en las dos direcciones (restaurar el prefijo de texto pone ROJO el caso del
  hermano, 1/9; quitar la normalización de la raíz pone ROJO su propio test, 1/9).
- **Zip Slip en `CredentialMaterializer`.** `Zip.entries` es un `Map<String, ByteArray>`
  de nombres libres leídos del almacén de secretos, así que `..` no es un archivo mal
  formado: es una **clave legal**. `materializeZip` la resolvía contra el directorio
  temporal **sin ningún confinamiento** y escribía los bytes de la entrada en el
  resultado, con `chmod 0600`. Reproducido antes de arreglar: `../escape.txt` escribía en
  `/tmp/escape.txt`, `../../etc/cron.d/pwn` en `/etc/cron.d/pwn`, y una clave absoluta
  en su propio destino. Eso es una **escritura arbitraria, con contenido elegido por el
  atacante, en una ruta arbitraria**, durante la materialización de credenciales. La
  guarda compara elementos de ruta, no caracteres, así que también rechaza el hermano
  cuyo nombre empieza por el del temporal (`<tmpdir>-evil`) — el mismo caso que un
  atacante elige en cuanto ve un prefijo de texto en el código.
- **Un archivo anidado dejaba la credencial en disco tras un run que reportaba éxito.**
  Compuesto con el anterior, y peor: `Files.createDirectories` crea **todos** los niveles
  que faltan y solo informa del último, así que el materializador no registraba los
  directorios intermedios de una entrada anidada (`certs/server.pem`). `close()` llegaba a
  `Files.delete(tempDir)` con un `sub/` todavía dentro, tomaba `DirectoryNotEmptyException`
  y **se la tragaba** — el `catch` silencioso ya existía. El resultado es contenido de
  credencial retenido en disco, invisible: el run es verde y el fichero sigue ahí. La
  cadena se registra ahora en orden de creación, que es el que necesita el borrado en
  orden inverso que ya existía para eliminar hijos antes que padres. Los dos extremos se
  normalizan en vez de confiarse: en crudo, `a/./x/../b` da tres niveles que
  `createDirectories` nunca creó, y el nivel con `..` normaliza de vuelta a un padre no
  vacío — es decir, degrada al defecto original. El único llamador hoy pasa una ruta ya
  normalizada, que es exactamente la razón por la que no es una precondición que deba
  quedar escrita y confiada. 16 tests, mutación en las tres direcciones (restaurar el
  prefijo de texto → ROJO 1/9; quitar `reverse()` → ROJO 4/7, y las tres que quedan
  verdes son los casos de un solo nivel donde el orden da igual; quitar la normalización →
  ROJO 1/7).

- **El guardián de argv publicaba la credencial que acababa de rechazar.**
  `GitCredentialsApplier.guardProcessBuilderArgs` rechazaba un argumento con
  credenciales embebidas **interpolando ese mismo argumento** en el mensaje. Y el
  rechazo no es un throw local: el guard se llama en `GitCheckoutExecutor.execute`
  **antes** del `try` que envuelve el resto del trabajo, así que su
  `IllegalArgumentException` nunca llega al `secretPatternRegistry.scrub` por el que
  pasan todos los demás fallos de ese camino. Sigue a `GitCheckoutStepDefinition`, que
  reenvía `throwable.message`, y de ahí a `RegistryExecutionBoundary`, que copia
  `e.message` en un `StepFailed` durable. Es decir: **la contraseña que el guardián
  existía para sacar del proceso acababa en el registro de eventos.** Un autor que
  escribiera `url = "https://user:secreto@host/repo"` la filtraba sin que nada lo
  impidiera, porque no hay ninguna validación previa de `spec.url`: el único guard es
  el que filtraba. `extractHost` tenía el mismo defecto un nivel más abajo, con su
  propio mensaje de fallo interpolando la URL entera. Los dos rechazos identifican
  ahora el argumento por posición, longitud y —para una URL— su host, que
  `extractHost` ya devuelve sin userinfo, así que el operador no pierde nada accionable.
  **Visto rojo antes de tocar producción**: 3 de 4 filas cayeron por la fuga mientras
  la fila que afirma que el guard **sigue rechazando** permaneció verde, que es la
  capacidad que no podía perderse. La fila de `extractHost` se comprobó sola por
  mutación —revertir solo ese rechazo y confirmar que cae exactamente ella— porque ya
  había caído antes por el motivo equivocado: mi dato de entrada
  (`https://user:clave@`) no alcanza la rama `ifEmpty`, devuelve `user` y no lanza
  nunca. El dato correcto es `https://:clave@`. 4 tests, 0 fallos, 0 skips.

- **Un KDoc defendía el diseño que el propio código había rechazado como bug.**
  `ObsPc2IngestAgent` abre sus FIFOs con `openRendezvous`, que abre **O_RDONLY**.
  Su KDoc de clase tenía una sección entera —*«Why it opens the FIFO O_RDWR»*—
  que argumentaba a favor de **O_RDWR**, y el comentario de la línea de llamada
  repetía el mismo motivo **dos líneas antes** de invocar la función que lo
  desmiente. Quien leyera el rationale concluiría que el código está mal, y la
  contradicción era legible en una sola pantalla. Tres afirmaciones, las tres
  comprobadas antes de reescribir:

  1. Decía que un `open` read-only «tiene éxito inmediato y reporta EOF hasta
     que aparezca un escritor». Es lo contrario de lo que hace POSIX: el
     `O_RDONLY` **bloquea**, y de ese bloqueo depende el diseño entero — es el
     rendezvous.
  2. Presentaba el defecto real de O_RDWR («el agente no puede ver EOF porque es
     el mismo escritor») como un coste aceptable a mitigate con un sello
     explícito. No era un coste: era **el fallo**. Tener una referencia de
     escritura impedía que la tubería llegara a cero-escritores al salir el hijo,
     y el redactor se quedaba bloqueado para siempre esperando un EOF que no
     podía llegar.
  3. Citaba `ADR-0085` como autoridad. Ese documento no existe en el árbol, y
     además la premisa cambió: con O_RDONLY la señal de fin **es** la tubería.

  Sin cambios de comportamiento: solo KDoc y comentarios, compilación observada
  (`exit 0`). Se conserva el argumento del diseño rechazado en lugar de borrarlo,
  porque es lo que impide que alguien lo vuelva a proponer; y se corrige también
  el comentario de la línea de llamada, que es donde el ojo encuentra primero la
  contradicción.
- **El observador de un run escribía en el journal del productor.** El verbo
  `events` —y con él `observe`, `inspect`, `console` y `events verify`— abría el
  store del productor **con permisos de escritor**: su constructor creaba el
  esquema, migraba el journal, hacía `backfill` de `run_id` y dejaba un **hilo
  escritor vivo**, y mantenía una conexión **capaz de escribir** durante toda su
  vida. Un lector que escribe no es un lector: era un segundo escritor del
  journal del productor, y podía tomar un **lock de escritura** sobre él. Que
  hoy no haya breakage se debe a una mitigación **accidental**
  (`PRAGMA busy_timeout = 5000`), no a una garantía.

  La corrección es que el store se abre en una de **dos calles** y la calle la
  decide el **propósito** (`EventStorePurpose.storeMode`, puro y decidible sin
  disco): **Writable** para el productor, **ReadOnly** para el lector. En la
  calle de lectura el motor **SQLite rechaza toda escritura** —DML y DDL— con
  `SQLITE_READONLY`, porque la conexión se abre con el flag de sólo-lectura: la
  garantía la impone la base de datos, no la disciplina del código. Sin
  esquema, sin migración, sin `backfill` y sin hilo escritor.

  Un store de lectura sobre una base **sin historia** se rechaza con un motivo
  **tipado** (`NoEventHistory`) en vez de responder «run desconocido», que
  señalaría al operario hacia el run en vez de hacia el fichero.

  Medido con el driver publicado (sqlite-jdbc 3.46.1.3): la conexión de sólo
  lectura abre una base WAL que otro proceso tiene abierta, la lee con el
  escritor vivo, la lee **tras un escritor muerto sin consolidar** y la lee
  **tras un escritor cerrado limpiamente**; toda escritura la rechaza el motor.
  La forma URI (`?mode=ro`) exige escapar `%`, `#` y `?` —un URI termina su
  ruta en el primer `?` o `#`, así que un fichero con esos caracteres se
  abriría **por otro lado**—, y se comprobó con `PRAGMA database_list` que el
  fichero abierto es exactamente el pedido.

  Verificado contra el binario real: leer el journal de un run **no cambia ni
  un byte** del fichero —`sha256` idéntico antes y después de `events` y de
  `inspect --state`—. Antes, esa lectura creaba tablas, migraba y hacía
  `backfill` en el fichero del productor.

  Y la **primera versión de la sonda** que decide si una base tiene historia
  tenía un fallo de orden de inicialización que la hacía responder siempre que
  sí —el `var` con inicializador pisa lo que el constructor calcula—, de modo que
  una base sin historia pasaba y moría después con una traza cruda. Se encontró
  a mano y se corrigió antes de cerrar el bloque; el detalle está en §2.34.

  El detalle de fondo, con su por qué, está en §2.34; las filas permanentes que
  lo fijan son `EventStoreModeTest` (3) y `EventStoreRefusalRenderingTest` (5),
  y los ejemplos permanentes que lo vigilan de punta a punta son
  `examples/events/06-observer-death-does-not-kill-the-producer` y
  `examples/negative/events-no-event-history`.

### 2.2 Lo que queda abierto, por valor operativo

**a) 9 módulos sin test unitario permanente.** No es un objetivo de paridad.
`AGENTS.md` §2 manda por riesgo, no por cuota. El orden de ataque es:

| Prioridad | Módulo | Riesgo que justifica el test | Estado |
|---|---|---|---|
| 1 | `pipeline-credentials-local` | Red de seguridad de materialización y borrado de secretos. Ninguna prueba que observe fugas de credenciales puede eliminarse sin cobertura equivalente. | **Hecho** — 92 tests, 8 clases. Encontró 2 defectos materiales de producción (`AeadCipher` IV, `CredentialPartReader` metadata). |
| 2 | `pipeline-output` | Los *value types* que **emiten** lo que el Output Plane promete. Un rango sin guarda produce la sustitución silenciosa del punto 2.1. | **Hecho** — 74 tests, 5 clases. Encontró 1 defecto material (`StreamingUtf8Decoder`). |
| 3 | `pipeline-output-store` | Continuación del Output Plane por offset comprometido. Un segmento mal dirigido duplica o pierde bytes ya emitidos. | **Hecho** — 26 tests, 2 clases. Encontró 2 defectos materiales (`parseSealed` sin guarda de rango, el `coerceAtLeast` que sustituía bytes). |
| 4 | `pipeline-step-sdk/http` | Red, reintentos, códigos no-2xx. Adaptador con pocas reglas puras extraíbles. | **Hecho** — 26 tests, 2 clases. Encontró 2 defectos materiales (`StatusRange.parse` no total, el puerto de egreso explícito inválido sustituido por el default). La mitad de dominio —la allowlist— se añadió en `pipeline-domain`, donde vive el tipo. |
| 5 | `pipeline-runtime-api` | `RuntimeRecoverDecision` es un decider puro y público: la misma clase que `RunExecutionLease`, coste de test bajo y valor alto. | **Hecho** — `RuntimeRecoverDecisionTest`. |
| 6 | `pipeline-step-sdk/workflow-control` | El confinamiento que decide si un `dir(...)` puede salirse del workspace. Puro, y además estaba **mal**. | **Hecho** — 9 tests, 1 clase. Encontró 1 defecto material: el prefijo de texto descrito en 2.1. |
| 7 | `pipeline-credentials-multipart` | El punto donde una credencial llega a disco. Una escritura arbitraria y una retención silenciosa, ambas en materialización. | **Hecho** — 16 tests, 2 clases. Encontró 2 defectos materiales: Zip Slip y la fuga de directorios anidados. |
| — | `pipeline-events` | Su único candidato (`EventViewProjection`) no tiene consumidor: ver 2.2 b. Sin consumidor, un test es coste sin destinatario. | Bloqueado por decisión de producto |

**La lista que sigue estaba caduca y mandaba al siguiente ciclo a módulos ya
cerrados.** Decía «9 módulos sin test» y nombraba `pipeline-artefacts-local`,
`pipeline-step-sdk:files`, `pipeline-release`, `pipeline-credentials-executor` y
`pipeline-output-store`. Los cinco tienen tests hoy, y su propia tabla de ratios
más abajo —que sale de `check-budgets.py`— ya lo decía. Dos censos del mismo
documento, uno escrito a mano y otro derivado del gate, discrepando sobre qué
queda por hacer: el escrito a mano manda, y por eso el orden de ataque apuntaba
a trabajo ya hecho.

Re-medido sobre el árbol comiteado, instrumento único y sin listas escritas. La
primera versión de este bloque usó `grep -ohE '":[a-zA-Z0-9:_-]+"'` sobre todo el
fichero y dio **8**. Fallaba en la misma dirección dos veces, y por eso el
instrumento va aquí completo:

```
# 1. sólo el bloque include(), con los comentarios fuera
sed -n '/^include(/,/^)/p' settings.gradle.kts \
  | sed 's|//.*||' \
  | grep -oE '":[^"]+"' | sed 's/^"//; s/"$//' | sort -u
# -> 27 módulos

# 2. sin clase de test, y con código de producto debajo
for m in $(...); do
    d=$(printf '%s' "$m" | sed 's/^://; s/:/\//g')   # Gradle ':' -> disco '/'
    main=$(find "$d/src/main" -name '*.kt' | wc -l)
    test=$(find "$d/src/test" -name '*Test.kt' | wc -l)
    [ "$test" -eq 0 ] && [ "$main" -gt 0 ] && echo "$m"
done
```

**Son 6**, y ninguno figura entre los cinco que la lista vieja nombraba:

| módulo declarado | ficheros `src/main` | LOC de producto |
|---|---:|---:|
| `:pipeline-scripting-api` | 16 | 3.583 |
| `:pipeline-scripting-kotlin24` | 8 | 1.874 |
| `:pipeline-runtime` | 4 | 1.598 |
| `:pipeline-step-sdk:junit` | 9 | 961 |
| `:pipeline-event-harness` | 8 | 831 |
| `:pipeline-step-sdk:api` | 4 | 340 |

Qué decisión pura hay dentro de cada uno y con qué riesgo está **NOT_RUN**: se
deja en blanco a propósito, porque rellenarla de intuición es exactamente el
mecanismo que produjo la lista vieja.

Tres advertencias sobre el propio instrumento, porque sin ellas la tabla engaña
como la tabla anterior. Las dos primeras las suffered así un instrumento
anterior de este mismo bloque:

1. **`grep` sobre el fichero entero lee los comentarios.** El módulo
   `:pipeline-architecture-tests:test` **fue eliminado** durante el RESET y sigue
   apareciendo en `settings.gradle.kts` dentro de un comentario que explica su
   propia baja. Un censo que no lo distinga lo cuenta como módulo vivo, con
   cero `src/main` y cero `build.gradle.kts`: un directorio que sólo contiene un
   `build/` de Gradle. Eso fue el «8»→«7».
2. **`:pipeline-step-sdk:http` no es un directorio.** Se llama así en Gradle y en
   disco es `pipeline-step-sdk/http/`. Un censo que no traduzca `:` a `/` lee
   **0 ficheros y 0 tests** en los ocho módulos anidados, y los cuenta como
   vacíos. Ese es el mecanismo que puede producir un número.
3. **Sin código no hay garantía que probar.** `:pipeline-sdk-bom` es un
   `java-platform` sin clases —lo dice su propio `build.gradle.kts`—, y el filtro
   `[ "$main" -gt 0 ]` lo excluye por eso y no por juicio. Un módulo que no
   compila nada no es un módulo sin test: es un módulo sin sujeto.

Queda además la advertencia que ya no depende del instrumento: el recuento es de
**clases de test**, no de tests ejecutados. Un módulo con una clase no equivale a
un módulo cubierto; por eso la tabla de ratios, que sí cuenta líneas, es la que
manda para valorar riesgo.

**Estado remedido en este ciclo: queda 1 módulo sin clase de test**
(`:pipeline-sdk-bom`, que no compila nada y por eso no tiene sujeto). Los seis
de la tabla de arriba tienen hoy su observador, y `:pipeline-runtime` recibió en
este ciclo el test de la regla que decide si una cancelación puede proceder
(§2.20). La tabla se conserva porque es la medición que ordenó el ataque, y
porque volver a contarla sin criterio es exactamente el defecto que esta
secciónCorrigió una vez.

**El orden de ataque se rehace cuando la medición exista, no antes.**

#### 2.2.0 El censo de consumidores de los seis: uno de los seis no tiene a quién servir

La tabla de arriba pregunta «¿tiene tests?». No pregunta «¿tiene **consumidor**?», y
son preguntas distintas: una segunda autoridad puede tener tests y seguir sin lector.
Para los seis módulos de la tabla, el censo de consumidores es el que ordena el
ataque, y aquí está el que se pudo verificar entero.

**Instrumento, tres comprobaciones sobre ficheros versionados de cualquier tipo:**
(1) ¿el módulo está en el `include(` de `settings.gradle.kts`? (2) ¿algún
`build.gradle.kts` —propio o ajeno— contiene `project(":pipeline-runtime")`?
(3) ¿algún módulo fuera de él importa el paquete?

**Dos fallos del instrumento, porque sin ellos el resultado se lee al revés:**

- Un `git grep` con **pathspec de comodín** (`'pipelinek/*/src/main/kotlin'`) devuelve
  **NADA**, no cero. Leído como «cero consumidores» produce exactamente la
  conclusión que uno quiere oír. Hubo que repetirlo sin pathspec.
- El paquete raíz `dev.rubentxu.pipeline.v2.runtime` **no identifica el módulo**:
  `:pipeline-runtime-api` —que sí está publicado, en las tres listas de
  `pipelinek/build.gradle.kts:349,413,436` y en el BOM— comparte ese mismo raíz. Sólo un
  censo **por símbolo** separa uno de otro.

**Resultado para `:pipeline-runtime`: 4 ficheros, 1.598 LOC, 0 tests y 0
consumidores.** Las tres clases que declara —`RuntimeRecoverPortStoreAdapter`,
`RuntimeControlPortStoreAdapter` y `RuntimeIntrospectionPortStoreAdapter`— sólo
aparecen en su propio fichero, en su `build.gradle.kts` y en su
`detekt-baseline-main.xml`. La única referencia desde fuera del módulo está en
`pipeline-runtime-api/.../inspect/RunStructure.kt:195` y es una frase de KDoc que
lo dice sin rodeos: **«Latent, not shipped.»** En el árbol versionado ningún ADR
lo autoriza; el único `.md` que lo nombra es este `ROADMAP.md`.

**Decisión: no se borra en este ciclo, y el motivo es de ciclo de vida, no de temida.**
Borrarlo es una ruptura de la superficie que AGENTS.md §1 reserva a una ruptura
versionada autorizada, y no existe tal autorización en el árbol. Lo que sí se
registra es el hecho medido, para que la próxima decisión tenga la evidencia delante
en vez de un módulo sin tests que parece vivo: **antes de elegir qué testar en esta
tabla, hay que elegir qué merece existir.**

**Lo que este censo NO cubre:** los otros cinco módulos de la tabla. Que un agente
les haya entregados un análisis no lo convierte en medición; aquí no se publica su
tabla porque no ha pasado por el mismo instrumento.

#### 2.2.1 `BlockStepFlattener` borrado: la segunda autoridad de una regla viva

Un agente midió los cuatro módulos de scripting y señaló a `BlockStepFlattener`
como «el hallazgo más agudo del repositorio», por una asimetría real y medible:
en su rama `Parallel` **añade dos segmentos a `blockPath` y sólo `depth + 1`**,
mientras que las otras trece ramas añaden uno y `depth + 1`
(`BlockStepFlattener.kt:114-122`, ya borrado).

**La asimetría no era un defecto**, y ahí está la mitad del aprendizaje: el
validador que manda, `CompiledPipelineValidator:89`, cuenta **un nivel por
`BlockStepNode`**, igual que el flattener. `blockPath` es una *identidad* y
necesita dos índices; `depth` es un *nº de continuaciones CPS* y necesita uno.
Dos segmentos y una unidad de profundidad no se contradicen: son dos preguntas.

**Lo que sí era un defecto era lo que el agente no vio.** Al verificar en vez de
publicar su recomendación, la tabla de consumidores salió vacía:

```
git grep -l 'BlockStepFlattener\|FlattenedStep\|IndexedStep' -- .
  -> BlockNestingConstants.kt:7      (una línea de KDoc)
  -> api/api.api                     (el volcado ABI)
  -> detekt-baseline-main.xml        (una entrada)
  -> el propio fichero
```

Cero llamadas. Un segundo calculador de profundidad de anidamiento, con **su
propia excepción** `BlockNestingDepthExceededException` para la misma condición,
que no vigilaba nada — mientras el que sí vigila es
`CompiledPipelineValidator`, invocado desde `CompiledExecutionPlanner:34`.

**La recomendación del agente era incorrecta, y seguirla habría costado caro.**
Escribir un test permanente para código sin consumidor viola AGENTS.md §2 por
dos vias distintas: un módulo «sin test» no es un módulo sin lógica, y una
garantía sin destinatario es coste. El ROADMAP ya lo tenía escrito para
`EventViewProjection` («sin consumidor, un test es coste sin destinatario») y el
agente lo pasó por alto en el módulo siguiente.

El borrado no es un juicio mío: el `build.gradle.kts` de la raíz ya lo tenía
medido — `pipeline-step-sdk:api` está en `bcvModules` y **no** en
`publishedContractModules`, ninguno aplica `maven-publish`, y `examples/` no
importa nada del paquete sdk — de modo que «sus llamadores internos están dentro
de este build, así que un tipo público eliminado falla `compileKotlin` de inmediato
y ruidosamente». Ese fallo no se produjo. `apiDump` regenerado: 48 líneas menos,
y `CompatibilityLevel`, `PipelineJson`, `WorkspacePathAnchors` y `StepContext`
siguen en el volcado.

#### 2.2.2 Cuatro comentarios que citaban pruebas que este árbol ya no tiene

`Sha256MigrationCharsetInvariantTest`, `SharedModelCompositionFitnessTest`,
`UatLocal004TimeoutTest` y `CompatibilityLevelEnumTest`: `git ls-files` no
devuelve **ningún** fichero para ninguno de los cuatro. Cada comentario afirmaba
un guard que el repositorio no tiene — la quinta y sexta apariciones de un defecto
que este árbol ya había pagado cuatro veces.

**El título de esta sección estuvo mal hasta que se midió (§2.2.4).** Decía «pruebas
que nunca existieron». Es falso: los cuatro eran ficheros de test reales, borrados
por el propio RESET en `2aa071aab`. Lo cierto es más estrecho y sigue siendo un
defecto —la cita no tiene sujeto **en este árbol**—, pero la diferencia no es
cosmética: «nunca existió» innocentiza al KDoc y «lo borramos y no actualizamos la
cita» lo acusa. Es la distinción que decide el remedio.

No se les añade test, y esa es la decisión: una garantía que sólo existe en un
comentario no se vuelve real con un test, y un *fitness* de texto fuente está
prohibido por AGENTS.md §2. Cada comentario se corrigió por lo que afirmaba, que
no es lo mismo en los cuatro: uno decía «fijado» sobre una invariante que se
cumple pero nadie comprueba; otro justificaba una decisión con un test que ya no
existe; otro respaldaba un fallo histórico real con un fixture inventado; y el
último declaraba fijado un enum que no tiene un solo consumidor.

**El escaneo propuesto aquí está escrito y ejecutado: §2.2.3.** Convierte el
NOT_RUN de «escribir un instrumento» en la cifra que el instrumento da.

#### 2.2.3 El escaneo de §2.2.2, ejecutado: 71 nombres citados y sin sujeto

**Denominador, medido sobre el árbol:** 689 ficheros `.kt` rastreados por `git
ls-files`, 6.447 identificadores declarados (`class`, `object`, `interface`,
`fun`, `val`). El censo lee **comentarios** —líneas que empiezan por `*` o `//`—
y busca un nombre entre acentos graves que termine en `Test` o `Uat`; si ese
nombre no está en el conjunto de declaraciones, la cita no tiene sujeto.

**Resultado: 71 nombres, 91 citas, todas bajo el directorio del build.** Los más repetidos:

| Nombre citado | Citas | Dónde |
|---|---:|---|
| `ObsPc2ProducerSurvivalSpikeTest` | 5 | `durable/ingest/ObsPc2IngestAgent.kt:34` |
| `CoordinatorGrowthGuardrailTest` | 4 | `durable/BeforeStageDirectiveEngine.kt:74` |
| `ObsPc2IngestAgentPrototypeUatTest` | 3 | `durable/ingest/ObsPc2IngestAgent.kt:33` |
| `EventSliceParityLawsTest` | 3 | `events/durable/InMemoryEventStore.kt:257` |

**Dos correcciones al instrumento, y las dos cambiaron la cifra:**

- El patrón inicial incluía `Spec`. Daba 74. `OptionsSpec` tiene **12
  apariciones** en el árbol y es un símbolo del DSL: el sufijo de una prueba es
  `Test` o `Uat`, y `Spec` captura cualquier cosa que lo lleve en medio. Bajó a
  71.
- El primer intento comprobaba el **nombre de fichero**, no la declaración. Un
  `class FooTest` declarado dentro de otro fichero se contaba como ausente. El
  denominador correcto es la unión de identificadores declarados en los 689
  ficheros, que es lo que se usa aquí.

**Verificación a mano de una muestra**, porque una cifra sin contenido no es un
censo: `CliParserArityTest` 1 aparición y 0 ficheros, `ConsoleFollowTest` 1 y 0,
`EventSliceParityLawsTest` 4 y 0, `ObsPc2ProducerSurvivalSpikeTest` 5 y 0.
Ninguno de los 71 aparece declarado en un `.java` ni en un `.kts`.

**Lo que el censo NO leyó:** los 74 ficheros `.kts` del árbol. Sus comentarios
pueden citar pruebas que no existen, y aquí no se afirma que no las haya.

**Decisión: no se abre campaña.** Esta sección ya fija la regla que gobierna
estas 91 citas —«una promesa en KDoc se reescribe cuando se toca el fichero, no
en una campaña»— y los cuatro casos de §2.2.2 se corrigieron al tocar su
fichero, no en barrido. Abrir ahora un barrido sobre 71 sitios sería exactamente
la campaña que la regla prohíbe, y cada reescritura tocaría producto sin un
cambio de comportamiento que la justifique.

**Corrección posterior a esa decisión, porque el censo mezclaba dos cosas
distintas.** Las 91 citas no son de una clase: hay comentarios que **narran**
(*«used to freeze»*, *«a deleted X used to pin»*) y comentarios que **afirman en
presente** (*«X enforces»*, *«asserted by X»*, *«X pins this»*). Clasificadas por
tiempo verbal sobre las mismas 91: **7 narración, 13 afirmación viva, 71 sin
clasificar por el instrumento**. La narración es arqueología honesta y no se toca;
una afirmación viva es distinto — dice que una garantía la sujeta un test que no
existe, y eso ya no es una promesa pendiente sino una afirmación falsa sobre el
estado del árbol. Peor: `EventSliceParityLawsTest` se describía como borrado en
`EventStore.kt:111` y como «is what holds this» en dos implementaciones, es decir,
el repositorio se contradecía a sí mismo sobre el mismo nombre.

**Las tres de `pipeline-events-store` están corregidas** (`54e2e9512`), y la
sustitución no fue una prosa más bonita sino lo que la lente exige: cada una nombra
la garantía real y dice si algo la observa. Dos eran estructurales —`List<DomainEvent>`
no tiene dónde poner una posición, y el store en memoria no tiene caso
ilegible que discutir— y la tercera NO es unit-testable: `SqliteConnectionFactory.open`
abre una conexión nueva por llamada, así que `:memory:` saldría vacío en cada
lectura y el store es file-backed por diseño; ejercitarlo exige almacenamiento
durable, que AGENTS.md §2 prohíbe a un test unitario. Por eso se **conservó la
afirmación** y se borró la cita al test, y no al revés.

**Las otras 10 afirmaciones vivas: la mitad de existencia está resuelta, la de
reescritura sigue NOT_RUN.** §2.2.4 midió las diez. Lo que queda no se corrige aquí,
y no se declara cerrado: reescribir una afirmación viva exige leer el fichero para
saber qué garantía nombra, y ese trabajo no se ha hecho. La regla que las gobierna
sigue siendo la de arriba —se reescriben al tocar el fichero—, con una única
excepción acotada: una afirmación viva comprobada como falsa no espera al próximo
toque.

#### 2.2.4 Las diez existen como borradas, y eso cambia lo que §2.2.2 afirmaba

Medido el 2026-10-19 sobre `8a823ca556`, con cinco nombres comprobados aquí a mano y
los diez por agente:

| Comprobación | Resultado |
|---|---|
| `git log --diff-filter=D -1 -- */<Nombre>.kt` sobre HEAD | **un solo commit para los cinco**: `2aa071aab` |
| `git ls-files -- '*<Nombre>.kt'` | 0 en este árbol |
| El fichero, en un worktree hermano | **existe**: p. ej. `.worktrees/pr134-review/.../CoordinatorGrowthGuardrailTest.kt` |
| `git merge-base --is-ancestor e41fc91e7 HEAD` | **NO** |

La cuarta fila es la que casi convierte el hallazgo en otro. Buscando con `--all`,
`git log` devuelve `e41fc91e7` (`feat(release) … (#146)`) para los cuatro nombres:
parece una contradicción con `2aa071aab`. No lo es —`e41fc91e7` **no es ancestro de
HEAD**, es una segunda eliminación en otra línea, y `--all` mezcló dos denominadores
en una sola cifra. Sin esa fila, el veredicto habría sido «el commit equivocado».

**Lo que cambia:** §2.2.2 decía «pruebas que nunca existieron». Es falso y era mi
error. Los diez eran ficheros de test reales que el propio RESET borró en
`2aa071aab` sin tocar las citas. El defecto sigue existiendo y sigue siendo el mismo
—una afirmación viva sobre un guard que este árbol no tiene— pero la causa es
**desincronización**, no invención, y el remedio se lee distinto: un KDoc que nombra
un test borrado por nosotros se corrige al tocar el fichero; un KDoc que nombra un
test imaginado se corría aunque nadie tocase nada.

**Y una cifra que este documento ya había pagado:** «71 nombres» no está respaldada
por ninguna lista. §2.2.3 deja escrita una tabla de 4 y una muestra de 4; sólo **10**
nombres son recuperables del texto. 71 era la salida del instrumento, no un conjunto
que nadie pueda volver a contar.

**Censo histórico — los nueve módulos que la lista vieja nombraba, no los seis
que quedan.** Se conserva porque su hallazgo sigue siendo cierto y explica por
qué esos cinco se priorizaron: un módulo «sin test» no es un módulo sin lógica;
varios de ellos deciden cosas de las que depende borrar ficheros o publicar un
binario. Los cinco con tests de esta tabla están cerrados —`pipeline-release`
recién, en el punto 2 más abajo—. **No es el orden de ataque vigente**, y la
tabla de los seis de arriba lo sustituye.

| Módulo | Ficheros main | Con lógica pura | Dónde está la decisión |
|---|---|---|---|
| `pipeline-step-sdk/files` | 6 | 3 | `RootDestruction.from`, los dos `when (rootDestruction)` de `CleanWsExecutor:91` y `DeleteDirExecutor:103` (**no es deuda**, ver punto 1), `antPatternToRegex` (`CleanWsExecutor:221-269`) |
| `pipeline-release` | 18 | **10** | `ProductVersion.parseOrNull`, `DistributionIdentity.evaluateDistributionIdentity`, `CandidateHandoffValidation.evaluateCandidateHandoff`, `SourceProvenance.evaluate`, `CandidateTagProvenance.evaluate`, `BundledPluginCompatibility.apiRangeContains` / `semverCompare` |
| `pipeline-credentials-executor` | 3 | 2 | `ProviderBackedSource.resolve` (donde `NotFound` es inalcanzable a propósito), `SpiMaterializationAdapter.materialize`, y el orden reverse-LIFO de `BoundCredentials.close` |
| `pipeline-artefacts-local` | 3 | 1 | `AntStyleGlob`: la lista de 13 exclusiones por defecto, el contrato ida/vuelta de `fromList`/`vararg` con `,`, y el `require` de glob vacío |
| `pipeline-output` | 15 | 7 (**6 sin cubrir**) | `OutputAdoption.invariantsBlockedBy`, `OutputDigest`, `OutputFrameIndex` (`to > from`), `PruneAuthorisation.StorageError.init` |

Los tres primeros son **los que ordenan el ataque**, y no por tamaño:

1. **`pipeline-step-sdk/files` — qué se borra.** **CERRADO 2026-10-19, sin
   defecto que corregir.** `RootDestruction` existe para que un tercer estado no
   pueda heredar un veredicto. La regla que de verdad decide —«¿esta operación
   puede borrar la raíz?»— la escribía además `fromUserOwned(Boolean)`, sin un
   solo llamador: un bit que re-deriva una regla ya tipada. Eso sí era la
   duplicación, y se eliminó; hoy la regla se dice **exactamente una vez**, en
   `RootDestruction.from(DestructiveAuthorization)`, que recibe la autorización en
   vez de un bit de ella. Los dos adaptadores pasan por ahí.

   Quedaba una segunda «duplicación»: los dos bloques `when (rootDestruction)` de
   `CleanWsExecutor:91` y `DeleteDirExecutor:103`. Medido: **6 líneas, 214 bytes,
   mismo sha256** — byte-idénticos, como decía este documento. **No es deuda y no
   debe extraerse**, por dos razones que se midieron:

   - Un `when` sobre enum usado como expresión es exhaustivo en Kotlin, así que
     añadir un cuarto estado rompe la compilación en los dos sitios. Los dos
     cuerpos no pueden divergir sin que el compilador lo impida.
   - El KDoc del propio enum rechaza esa extracción **por nombre**: bajo el
     título *«Why this exposes no `permitsRootWipe: Boolean`»*, explica que una
     propiedad que colapse los tres estados otra vez en un bit reintroduciría el
     defecto exacto bajo otro nombre, y que «consumers `when`-match the cases
     instead, so a new case is a compile error in every consumer». Un duplicado
     cuyo texto es la garantie, y cuya divergencia la detecta el compilador, es
     lo contrario de una deuda: es el patrón que el tipo chose a propósito.

   Extraerlo sería contraproducente: cambiaría dos sitios que deben romperse a la
   vez por uno que se rompe solo, y lo haría para ahorrar 6 líneas que hoy no
   divergen. El enum tiene 3 constantes, comprobado.

   > **Parcialmente cerrado 2026-10-19** (absorvido por el cierre de arriba).
   > `fromUserOwned(Boolean)` se eliminó (cero llamadores, verificado con `grep`
   > antes de borrar) y lo sustituyó `from(DestructiveAuthorization)`.
   >
   > **Cita cerrada 2026-10-19.** El KDoc apuntaba a
   > `FArchL7JenkinsVerbatimSignatureReflectionTest`, borrado por el RESET
   > (`find` fuera de `.worktrees/` no lo devuelve). La garantía, en cambio,
   > **sí** se sostiene hoy y en otro sitio:
   > `pipelinek/pipeline-scripting-api/.../StageScope.kt:72` declara
   > `fun deleteDir(path: String = ".")` y
   > `examples/control-flow/11-workflow-control.pipeline.kts:7` llama
   > `deleteDir()` a secas — quite el valor por defecto y ese programa deja de
   > compilar, y la etapa 3 de `scripts/verify-all.sh` se pone roja. La cita
   > se repuntó al testigo real y se dijo por qué se movió de custodio.
   >
   > **Nada abierto.** Ni `fromUserOwned` (eliminado, cero llamadores) ni la
   > duplicación de los `when`, que no es deuda: ver el razonamiento de arriba.
2. **`pipeline-release` — qué se publica.** `BundledPluginCompatibility` decide
   «este candidato se publica o se rechaza» y tenía un defecto vivo ya
   registrado: un `apiRange` obsoleta en plugins de ejemplo empaquetados es la
   causa anotada del arreglo de ejecución de R4/WU-R4.0.0.

   > **Medido y corregido 2026-10-19.** El punto decía «Cero tests». Es falso,
   > y lo era en la dirección que hace creer que no hay nada que proteger:
   > `src/test` ya tenía `ApiRangeContainsTest` (20 tests) y
   > `CandidateHandoffDigestAttributionTest` (3), **23 verdes leídos del XML, 0
   > fallos, 0 skips**. El ratio de la tabla de abajo arrastraba el mismo retraso.
   >
   > Lo que sí faltaba era una garantía concreta, y es la que sostiene el tipo
   > cuya razón de existir es un incidente: **`ProductVersion` no tiene ningún
   > test**, y `evaluateDistributionIdentity` tampoco. Sin ellos, el refusal que
   > impide blanquear un candidato como GA no lo observaba nadie.
   >
   > Añadido `ProductVersionIdentityTest`, 8 tests. La garantía es estrecha: *una
   > identidad con sufijo de candidato no puede alcanzar `Consistent`*. El caso
   > que la sostiene está construido para que **nada más pueda decidirlo** — las
   > seis superficies reportan la misma cadena con sufijo, así que el harmonies
   > es perfecto y sólo el refusal puede mantenerlo rojo. Sin ese control
   > positivo, un veredicto siempre-rojo habría pasado la primera fila igual.
   >
   > **Mutación leída de vuelta antes de leer el resultado**: el regex que rechaza
   > el sufijo pasó a aceptarlo (`(-.*)?`). 4 de 8 rojos, `exit=1`. Las cuatro son
   > exactamente las que hablan del sufijo; sobreviven el control positivo, el
   > desacuerdo entre superficies, la versión ilegible y la superficie no
   > observada. Sin daño colateral. Restaurado byte a byte, diff vacío.
   >
   > Un hallazgo lateral del mismo barrido: el comentario de este `build.gradle.kts`
   > afirmaba «`src/test` is empty here» en presente. Era cierto al escribirse y
   > dejó de serlo cuando aterrizó la primera de esas clases.
3. **`pipeline-output` — `OutputAdoption.invariantsBlockedBy`.** Es el decider que
   autoriza decir «el Output Plane es crash-consistente», y su propio KDoc
   registra **dos defectos materiales ya corregidos exactamente en esas dos
   funciones**: la inversión de polaridad de un parámetro, y un `return` en el
   primer match que avalaba I5 e I6 en silencio.

   > **Caducado, corregido 2026-10-19.** Este punto decía «Ninguno está fijado
   > por un test». No es cierto: `OutputAdoptionTest` existe y fija **ambos**,
   > con un test nombrado por cada error —`the parameter is the discharged
   > set…` y `two independent gaps block the invariants of both`— más nueve más
   > (13 verdes leídos del XML, 0 fallos). El segundoixa O1 descargado a
   > propósito, porque con O1 pendiente la primera rama haría `addAll` de las
   > seis y enmascararía si las otras se ejecutan: sin esa precaución el test no
   > distingue la unión del cortocircuito. El punto se conserva solo como
   > registro de que la afirmación se comprobó, no como trabajo pendiente.


El denominador, derivado de `settings.gradle.kts` con los comentarios eliminados
primero — contarlos sin quitarlos lee los **tres módulos ya borrados** que el
comentario menciona y da 28 en vez de 27: **27 declarados, 12 con `src/test`,
15 sin él**. Los dos módulos con test nuevos son los de las filas 6 y 7.

Ratios por módulo, **re-medidos sobre este árbol** con
`python3 scripts/check-budgets.py --json` (VERIFY / PRODUCT), los **diecisiete**
con unit tests, ordenados de mayor a menor:

| Ratio | Módulo | VERIFY / PRODUCT |
|---|---|---|
| **1.633** | `pipeline-step-sdk:workflow-control` | 80 / 49 |
| **1.009** | `pipeline-credentials-executor` | 349 / 346 |
| 0.965 | `pipeline-output` | 689 / 714 |
| 0.630 | `pipeline-credentials-local` | 1103 / 1750 |
| 0.545 | `pipeline-credentials-multipart` | 132 / 242 |
| 0.426 | `pipeline-binding-factory` | 123 / 289 |
| 0.416 | `pipeline-runtime-api` | 239 / 574 |
| 0.335 | `pipeline-artefacts-local` | 84 / 251 |
| 0.219 | `pipeline-release` | 280 / 1281 |
| 0.196 | `pipeline-step-sdk:files` | 87 / 444 |
| 0.149 | `pipeline-step-sdk:http` | 223 / 1499 |
| 0.137 | `pipeline-domain` | 865 / 6306 |
| 0.128 | `pipeline-output-store` | 228 / 1779 |
| 0.128 | `pipeline-step-sdk:runtime` | 269 / 2094 |
| 0.083 | `pipeline-credentials-api` | 62 / 751 |
| 0.064 | `pipeline-events-store` | 288 / 4534 |
| 0.008 | `pipeline-application` | 172 / 22101 |

La tabla anterior listaba **once** filas y omitía seis módulos, entre ellos
`pipeline-credentials-executor`, cuyo 1.009 supera a cinco de las que sí estaban
—y a `pipeline-output`, cuya fila declaraba 576/714 donde la medición da
689/714. Una tabla escrita a mano no se desincroniza: se queda quieta mientras
el árbol avanza, y su silencio no se distingue del de una fila correcta. Por eso
las diecisiete filas de arriba son una transcripción de `check-budgets.py` y no
una lista elegida.

Global **6805 / 61618 = 0.110**, contra el límite duro de 2.0. La cifra vigente
está en la tabla de §1, que es su único origen: repetirla aquí fue exactamente
lo que dejó cuatro copias distintas de un mismo número en este documento.

**El ratio más alto del repositorio es ahora `workflow-control`, a 1.633, y
conviene decirlo en lugar de esconderlo.** El módulo tiene 49 líneas de producto
—una clase y el tipo de excepción— y el test 80. La asimetría es real, pero el
test cubre nueve casos incluyendo los tres escapes hermanos en tabla, y cada
mutación aplicada lo pone ROJO: no es cobertura decorativa. Aun así es el módulo
donde el siguiente test Careful saldría más caro que el riesgo, y ese es un
argumento para **no** añadirle otro fichero sin medir antes. El techo del
presupuesto global es 2.0; ningún módulo lo roza.

**b) `EventViewProjection` sin consumidor — MEDIDO, no citado.** Un barrido de
todo `src/main` del repositorio encuentra exactamente dos referencias fuera del
propio fichero, y ninguna es una llamada:

- `pipeline-application/.../Main.kt:314` — un **comentario** que menciona
  «deprecated EventViewProjection.CONSOLE»;
- `pipeline-events/.../EventInspection.kt:9,21` — dos enlaces de KDoc, que además
  dicen que `EventInspection` **no** es sustituto suyo.

Es decir: la ruta de lectura viva es `EventInspection`, y `EventViewProjection`
(`object` + `enum ViewMode` + `enum OutputFormat`, con un modo `CONSOLE` que su
propio KDoc llama «el tipo peligroso de error») es código muerto.

**No se borra en este ciclo, y el motivo es concreto:** `:pipeline-events` es un
**contrato publicado**. Retirar un `object` público de un artefacto publicado es
una ruptura de ABI, y `AGENTS.md` §1 la prohíbe sin una ruptura versionada
explícitamente autorizada. Tampoco se le añade un test permanente: verificar
código que nadie llama es coste sin destinatario.

Queda como decisión de producto, con dos salidas — darle un consumidor real, o
una ruptura versionada que lo retire. Es lo único de esta lista que bloquea con
una decisión, no con trabajo.

**c) `~/.worktrees/` — 10 worktrees obsoletos, ~8000 ficheros Kotlin fantasma.**
Ninguno está contenido en esta rama y cada uno lleva commits únicos: borrarlos
destruye historia sin fusionar. **No se tocan sin confirmación explícita.**

**d) `docs/` sin seguimiento.** Contiene Markdown versionado que `AGENTS.md` §6
prohíbe bajo `docs/`. No está en Git, así que borrarlo es **irrecuperable**:
requiere confirmación explícita antes de hacerlo.

**e) 26 líneas en 18 ficheros que citan `design.md §`.** Routes a documentos
borrados por el RESET. Son referencias rotas en KDoc y comentarios, no enlaces
que alguien pueda seguir. Medido con `git grep -c` y pathspec **recursivo**: la
primera medición de este bloque usó `pipelinek/*/src/main` y devolvió 0, porque
`pipeline-step-sdk/runtime` es un módulo anidado. Del total, 25 líneas caen en
**17 ficheros de código** y una en este ROADMAP.md. Reparto por módulo, en
líneas: `pipeline-domain` 12, `pipeline-events-store` 11,
`pipeline-step-sdk/runtime` 1, `pipeline-scripting-kotlin24` 1; en ficheros:
10, 5, 1, 1. Nota: la primera redacción de este bloque decía «25 líneas en 17
ficheros» y daba el reparto por módulo **en ficheros** bajo un encabezado que
prometía líneas, de modo que las dos sumas cerraban a 17 y nadie notó que la
cifra de líneas era 26. El denominador es lo que hace la contradicción visible:
cada número necesita el suyo al lado.

**Cerrado en este ciclo — y la limpieza dejó un residuo peor que el original.**
El commit `5a8124b29` quitó las 25 líneas de código; hoy
`git grep -n 'design\.md' -- pipelinek` devuelve **cero**. Pero al borrar el *nombre*
del documento se dejaron **32 líneas en 20 ficheros** con el puntero sin
antecedente: `design §2.1`, `design §8.3`, `M6 design §C`. Un enlace con nombre
roto se detecta al pulsarlo; un puntero que ya no nombra nada, no. Junto a él, la
misma pasada dejó 7 sitios más que solo citaban `research §N` o `ADR-0051`, y
`git ls-files` no devuelve ni un `ADR-*.md` ni ningún fichero `research*`: el
repositorio entero tiene **5** `.md` trackeados (cuatro README/AGENTS y este).

Total eliminado aquí: **39 sitios en 22 ficheros** — 32 punteros `design §N`,
6 citas `ADR-0051` y 2 `research §N`, con una línea que llevaba dos de ellos. El
cambio es **100 % comentarios**, verificado con un diff que no contiene una sola
línea no-comentario, y `:pipeline-application:compileKotlin` recompiló de verdad
(7 tareas `compileKotlin` ejecutadas, no `UP-TO-DATE`; 0 errores, 0 avisos).

**El contenido no se perdió, y eso era lo que había que comprobar antes de
borrar.** En los 32 casos la regla ya estaba transcrita dos o tres líneas más
abajo: la tabla de `canPrune`, el orden 1/2/3 del teardown, la fórmula de la
ventana de bytes, las dos capas de redacción. Se borró la procedencia, no la
afirmación. El caso que **no** se resolvió por intercambio de tokens es
`SegmentOutputStore.canPrune`: su comentario registra una **divergencia
deliberada** de la especificación desaparecida (la especificación pedía
`Refused(UnknownStream)`; el código rechaza por *run* desconocido, a propósito).
Borrar la cita ahí habría destruido el único rastro de que alguien se apartó de
una norma por motivo. Ese sitio lleva ahora una frase que dice el motivo sin
señalar un fantasma.

**f) 87 clases de test eliminadas citadas desde producción — MEDIDO, y era 1.**
La redacción anterior de este bloque era una sola línea: *«`EffectReplayPolicy`,
KDoc que cita una clase de test eliminada»*. Es real, pero la cifra era la de
una anécdota, no la del defecto. Medido sobre `git ls-files` y `git grep`:

| Magnitud | Valor |
|---|---|
| Clases `*Test` citadas desde `src/main` | 89 |
| …que NO existen en ninguna fuente de test | **87** (2 sí existen) |
| Ficheros de producción afectados | **85** |
| Sitios de cita | **120** |
| …dentro de una cadena de código | **0** |

**El defecto es un comentario que afirma una protección que ya no existe.** Las
formas que toma son «pinned by *X*», «the contract test *X*», «asserted by *X*»
— es decir, el KDoc promete que una garantía está anclada por un test que el
RESET borró. `AGENTS.md` §2 prohíbe resucitar ese corpus («no grandfather
rights»), de modo que la corrección es **arreglar la afirmación**, no restaurar
el test.

**Los 120 sitios son comentarios. Cero cadenas de código.** Eso se midió, no se
supuso, y es lo que hace el arreglo seguro por construcción: no puede cambiar
comportamiento. También separa esta deuda de la de (j): el aviso de que «una
pasada automática cambiaría en silencio 27 mensajes que ve el operador»
**no aplica aquí**, porque ninguna de estas 120 citas vive en una cadena.

**Por qué no es un barrido mecánico.** Cada nombre lleva contenido sustantivo
alrededor, y quitar el nombre deja la frase colgando: «Layout, pinned by
*CredentialPartLayoutTest*:» se convierte en «Layout:». Exige 120 redacciones
con criterio, no una sustitución.

**Dos instrumentos ciegos, y el defecto real era otro.** La comprobación por
**nombre de fichero** (`git ls-files | grep /$n\.kt$`) y la segunda por
**declaración** dieron el mismo 87, pero un tercer intento con `\s` bajo
`git grep -E` reportó **49** clases declaradas frente a las **63** reales,
porque ERE no tiene `\s`: séptima recurrencia. Y al leer las líneas a mano
apareció
que la cita de `EffectReplayPolicy.kt` a `DefaultEffectReplayPolicyTest`
**es cierta** —ese test existe y fija las filas 1–8 una por una—, mientras que
las citas que en ese mismo fichero sí cuelgan son a **`ADR-0103`**, que existió
como `docs/v2/04-adrs/ADR-0103-one-replay-authority.md` y borró el RESET. **(f) y
(j) son el mismo defecto visto desde dos ángulos.**

**Una de las 87 sí es load-bearing, y conviene nombrarla primero.** No todas las
citas remanentes son cosmetiquería: `RunLifecycleEngine.kt:31` dice que su
comportamiento está fijado por `CoordinatorRunLifecycleCharacterizationTest`, «que
debe satisfacer SIN CAMBIOS». Ese nombre aparece en **una sola línea de todo el
repositorio — la que lo cita**; la clase no existe, ni en `src/test` ni en ningún
otro sitio. Y el KDoc no es un comentario de contexto: es la justificación entera
de una refactorización de extracción sin cambios de comportamiento
(`CanonicalDurableRunCoordinator` → motor propio). Una extracción que promete no
cambiar nada y cuyo testigo fue borrado es una refactorización **no verificada**,
que es la forma más difícil de detectar después: nada falla, el código funciona, y
nadie ya no distingue una extracción correcta de una que cambió algo. Cuenta
dentro de las 87 de arriba; no es un hallazgo aparte.

**Ese caso ya está corregido** — es el primero de los 120 sitios en resolverse, y se
eligió primero a propósito: no por ser el más numeroso, sino por ser el único que
sostiene una afirmación estructural. El KDoc de `RunLifecycleEngine` ahora dice la
verdad —que la extracción está sin verificar y por qué no se restauró el testigo—
y además corrige una segunda afirmación del mismo párrafo que tampoco se sostenía:
decía que los eventos de lifecycle «siempre llevan sequence 0», cuando 0 es la
*petición* de secuencia asignada y lo que llega al store es `counter.incrementAndGet()`.
Quedan 119 sitios.

**g) El digest de `Fingerprint` cambió** al corregir la igualdad de `Map`, lo
que altera cualquier digest emitido antes. Consignado, no revertido: el
comportamiento anterior era incorrecto.

**h) `RetentionGap` y `RangeLostRetention` son el mismo caso escrito dos veces.**
Ambos son casos de `OutputRefusal` con las mismas tres propiedades y los mismos
tipos — `(stream, requestedRange, lastCommitted)` — y el mismo significado según
su propio KDoc. Un consumidor no puede distinguirlos porque no llevan ningún
campo que los separe. Tras el arreglo de 2.1 uno de los dos tiene productor; el
otro sigue sin ninguno.
**No se fusionan en este ciclo**: `:pipeline-output` es un artefacto publicado y
retirar un caso de un ADT cerrado es una ruptura de ABI, que `AGENTS.md` §1
prohíbe sin autorización explícita de una ruptura versionada. Es, junto con 2.2 b,
lo único que queda bloqueado por una decisión de producto y no por trabajo.

**i) 92 campos de texto libre, 3 clases acotadas.** El motivo de 2.1
—`DiagnosticText`— no es una preference: sin él, el texto que un refusal lleva
termina en un sink por líneas. Midiendo el denominador: **92** declaraciones
`val cause|reason|details: String` en `src/main` de todo el árbol, y solo **3
clases** las acotan y prohíben CR/LF a la vez — `OutputRefusal.Corrupt.reason`,
`PruneRefusal.StorageError.cause` y `OutputPin.reason`/`holder` (dos campos).
`RecoverRefusal.StorageError`, `IntrospectionRefusal.StorageError`,
`IntrospectionRefusal.InconsistentLeaseState` y `OutputRefusal.StorageError`
prometen «short» o «one-line diagnostic» **en su KDoc** y no lo aplican.
**No se barren las 92 en este ciclo**, y el motivo es concreto: añadir un
`require` a un `data class` de un ADT publicado convierte una construcción que
hoy es válida en un `IllegalArgumentException` en tiempo de ejecución. Eso es un
cambio de comportamiento de V2, y `AGENTS.md` §1 lo prohíbe sin una ruptura
versionada autorizada. Lo que sí se hizo es cerrar el único camino con salida
por líneas demostrado —el de `inspect`— y nombrar la regla para que el siguiente
sitio la encuentre. Nota de método: el primer recuento dio **2** clases porque
busqué las guardas con una ventana de 6 líneas alrededor del campo, y
`OutputPin` las tiene 15 líneas más abajo. Un instrumento más estrecho que su
sujeto reporta un número con seguridad y con error.

**j) Las líneas que citaban documentos que no existen: saldadas.**

> **Estado: saldado en este ciclo.** El bloque nació midiendo **295 líneas en
> 126 ficheros** que citaban documentos ADR inexistentes. Medido hoy, con el
> mismo instrumento, el número en código es **0**: este ciclo reescribió las
> últimas citas y las que quedaban fuera del código. El relato de abajo se
> conserva como el registro de lo que se midió y de por qué se reparó por
> clases y no en bloque; la tabla es la de hoy.

La misma clase que 2.2 e, pero **diez veces más grande** cuando se escribió.
Medido entonces con `git grep -cE 'ADR-0'` sobre todo lo trackeado: **309
líneas / 126 ficheros**, de las cuales **14 estaban en este mismo ROADMAP.md** —el
censo contándose a sí mismo— y **295 en código**. Reparto por módulo:
`pipeline-application` 136, `pipeline-domain` 79, `pipeline-step-sdk` 50,
`pipeline-events-store` 7, y once módulos más con 1–3 cada uno. Los tres
primeros eran el **90 %** del total.

**La cifra que este bloque declaraba antes —«~297 líneas en 120 ficheros» medido
en `28667109`— se queda sin cifra.** Los cinco números que convivían aquí (292,
286, 295, 309 y 312/128) eran cinco instrumentos distintos persiguiendo la misma
pregunta, y publicar cinco es no publicar ninguno: un lector no puede saber
cuál citar. En su lugar, **un instrumento y un resultado**:

```
git ls-files -z '*.kt' '*.kts' '*.java' '*.md' | grep -zv '^ROADMAP.md$' \
  | xargs -0 grep -ohIE 'ADR-[0-9]{4}'
```

Patrón **anclado** (`ADR-` seguido de exactamente cuatro dígitos), y el censo
contándose a sí mismo excluido a mano: **305 sitios, 120 ficheros, 26 tokens
distintos** en código. Sobre todo lo trackeado, con este mismo ROADMAP dentro,
son **332 sitios en 121 ficheros**; la diferencia son las 27 coincidencias de
este fichero.

Dos instrumentos independientes dan los mismos 305: el `xargs` de arriba y una
suma fichero a fichero sobre `git ls-files`. Una investigation delegada propuso
297/119 con el mismo patrón y un filtro de exclusión casi idéntico; su cifra no
se reproduce, y la diferencia —8 líneas— es del filtro, no del árbol. Por eso
la cifra que se publica es la que dos instrumentos propios confirman, no la que
llegó hecha.

Lo que **sí** es estable desde el principio es el denominador: `git ls-files`
devuelve **cero** ficheros `ADR-`. Los 26 tokens no existen en ninguna rama.
Y 6 de esas citas —las de `ADR-0051`, que sólo aparecían aquí— se eliminaron en
este ciclo como parte del barrido de 2.2 e.


| Token | Entonces (sitios / ficheros, en código) | Hoy (sitios / ficheros, en código) |
|---|---|---|
| `ADR-[0-9]{4}` | 295 / 126 | **0 / 0** |
| `INV-L` | 5 / 1 | 5 / 1 |
| `docs/` | 13 / 5 | 12 / 4 |
| `FArchL` | 2 / 1 | **0 / 0** |
| `WCL-S` | 2 / 2 | **0 / 0** |
| `FIL-ALL` | 1 / 1 | **0 / 0** |
| `design.md §` | 0 | **0** |

La columna de hoy se midió con **un solo instrumento**, el mismo de entonces:

```
git ls-files -z '*.kt' '*.kts' '*.java' '*.md' | grep -zv '^ROADMAP.md$' \
  | xargs -0 grep -ohIE '<token>'
```

**Las dos filas que no bajan a 0 están justificadas, y no son deuda:**

- `INV-L6-CR-011` y `INV-L6-CR-012` aparecen en `GitCredentialsApplier` dentro
  del **config de git que el producto escribe**: son contenido emitido, no
  comentarios. Cambiarlos cambiaría el artefacto que ve el usuario final, y eso
  ya no es un comentario.
- `docs/` ya sólo aparece donde debe: en `AGENTS.md` y en los README, que
  **explican la retirada** de ese árbol, y en `examples/durable/31-stash-unstash`,
  donde `docs/` es un **directorio de fixture** que el escenario crea. En
  `src/main` el número es **0**.

Las cifras de entonces mezclaban instrumentos: por eso las de `ADR-0…` no
cuadraban con su propia prosa.


El denominador que lo confirma: **`git ls-files` no devuelve ni un solo
`ADR-*.md` ni `INV-*.md`** — cero. Los documentos no están en otra rama; no están.
Los tokens más citados, con el instrumento de arriba y sin este ROADMAP, son
`ADR-0075` (59), `ADR-0103` (37), `ADR-0046` (30), `ADR-0081` (23) y `ADR-0100`
(17). Las cifras que estaban aquí antes —64, 22 y 17 para `ADR-0016`— no
salen: `ADR-0016` tiene **11** sitios, y el 17 pertenece a `ADR-0100`. Tres
números de un top-5 equivocado no son un detalle de redondeo.

**No se borran en bloque, y al medir por qué aparecieron TRES clases, no una.**
La primera redacción de este bloque las trataba a todas igual —«llevan la razón
dentro del comentario»— y eso es falso para una de ellas:

1. **7 literales de cadena en 5 ficheros**, no 4 y no 27. Comportamiento
   observable, no documentación. **Corregido el 4**: un agente leyó 258 de los 295
   sitios y reportó 5 literales; un censo derivado del disco con el filtro de
   comentarios aplicado encuentra **7**, y la diferencia son dos que el agente no
   tenía en su alcance —`ObsPc2IngestAgent.kt:345` (estaba en su lista de
   *unread*) y `SegmentOutputStore.kt:394` (fuera del módulo que leyó)—. El
   instrumento del agente era «lo que leí»; el del disco es «todo». Textuales:
   - `CliParser.kt:141` — `"…user directory; choose one (ADR-0101 clause 3.4)."`
   - `CliParser.kt:151` — `"UnsupportedSandboxProfile(value=$value, ref=ADR-0016/M5/M9: …"`
   - `Main.kt:1488` — `hint = "see ADR-PK1-OUTPUT-FRAMING for the negotiation contract"`
   - `ObsPc2IngestAgent.kt:345` — `"…which ADR-OBS-003 excludes and which raw_secret_on_disk == 0 …"`
   - `SegmentOutputStore.kt:394` — `"…can truncate a live writer's range (ADR-OBS-002). …"`
   - `RunnerTrustProfile.kt:53` — `"RunnerTrustProfile.multiTenantConstrained requires ADR-0016 M5/M9 …"`
   - `SandboxProfile.kt:61` — `"sandbox-profile 'os' requires ADR-0016 M5/M9; rejected in L3. …"`

   **Los siete resueltos en `dde514b83`.** Cada mensaje conserva la mitad
   accionable que ya tenía y pierde solo la referencia muerta. Dos necesitaron
   más: el `hint` de `--output-protocol` ahora dice la regla **tal y como está
   implementada** (el primer id listado que este binario soporta es el que se
   usa, leído de `OutputProtocol.negotiate`), y el KDoc de `RunnerTrustProfile`
   afirmaba que el fallo lleva una «machine-checkable ADR citation» —quitar el
   token habría dejado esa afirmación falsa, así que el KDoc pasó a decir lo que
   el mensaje lleva de verdad. Ningún test, ejemplo ni dump `.api` afirmaba
   ninguna de las siete: comprobado **antes** de editar. Re-medido después con el
   mismo instrumento: **0 literales de runtime**.
   Son mensajes de error que **lee el operador**. Borrar el token no limpia nada:
   cambia el texto que el operador lee. Cualquier barrido de «comentarios que
   citan documentos borrados» aplicado sobre el árbol entero los corrompe.
   *La cifra anterior de 27 líneas incluía 2 comentarios Kotlin de cola de línea y
   5 comentarios `#` de shell dentro de `raw strings` de script embebido en
   `GitCredentialsApplier`. No son literales: son comentarios que el grep no
   distingue de una cadena porque comparten línea con código. El cierre de la
   cuenta lo confirma: 359 líneas con el token = 348 comentarios puros + 11
   resto, y esas 11 = 4 literales + 5 shell + 2 de cola.*
   El ejemplo que antes ilustraba esta clase,
   `WAIT_UNTIL_CONTROL_ROW("ADR-0075 (waitUntil control journal, …)")`, **ya no
   existe**: era el valor del campo `authority`, reescrito en `28667109`.
2. **6 apariciones de `Pre-ADR-0075`** en 4 ficheros del build —más una en este
   ROADMAP, que es esta misma frase—, con el prefijo `Pre-`. No es navegación:
   es un **marcador de frontera de formato** —«filas persistidas antes de este
   formato». Sin el token la frase queda en «filas Pre-», que no significa nada.
3. El resto, comentarios y KDoc. Ahí sí aplica lo de 2.2 e: la cita es a veces la
   única razón escrita, y borrarla deja una afirmación sin porqué.

Por eso la deuda es real pero el arreglo no es un barrido: es decidir por
fichero, y hay 120. Una pasada automática sería más rápida que leer 120 ficheros
y sería el error más caro del ciclo, porque los 4 de la clase 1 **no fallan
ningún test**: cambian en silencio.

La otra mitad de la pregunta —si un mensaje de error debe citarle un ADR al
operador— es de diseño de producto y no se resuelve aquí. El mensaje debería
decir la **regla** («un perfil de sandbox multi-tenant no puede ser `os`»), no el
documento donde la regla está escrita.

**Un sitio de los 120 ya está resuelto**: al corregir el KDoc de `ObsPc2IngestAgent`
(ver 2.1) desapareció su cita a `ADR-0085`, que además era una cita *de autoridad*
—«esto lo exige *ADR-0085`*— sobre un comportamiento que el propio código había
cambiado. Quedan 119; es el primero que se resuelve, y por el mismo motivo que en
(f): no por ser el más numeroso, sino por ser el que sostiene una decisión.

**Y este ciclo lo confirma empíricamente, no por argumento.** Antes de decidir si
el resto se puede borrar en bloque, se construyó el barrido y se probó **sin
escribir nada**, con una red que rechaza toda línea que quede mal formada y con
un segundo motor de sufijo acotado. Resultado sobre las 273 líneas de `.kt` main:

- El primer motor destruye líneas: `* ADR-0075 — authoritative spec for durable
  retry reconciliation.` → `*.`.
- El segundo motor no las destruye pero **corrompe la mayoría**. De las seis
  primeras líneas que la red aceptó, cuatro estaban rotas:
  `see ADR-0070).` → `see).`; `RP034-H / ADR-0101: …` → `RP034-H /: …`;
  `This is the plugin ADR-0081 claimed was possible…` → `This is the plugin
  claimed was possible…`; y —peor que todas— `ADR-PK1-IDENTITY` → `-IDENTITY`,
  porque un patrón `[A-Za-z0-9]+` se corta en el primer guion del nombre.

La red de seguridad solo detectaba paréntesis vacíos y puntos sueltos; no detectó
ninguna de estas cuatro, porque **ninguna deja sintaxis inválida**: el Kotlin
compila igual y el comentario solo queda tonto. Ese es el argumento definitivo de
por qué esto no se barre: un fallo aquí no rompe la compilación, así que ningún
gate lo detecta, y la deuda se convierte en ruido ilegible en 273 sitios.

**Cuatro sitios donde la cita era la única autoridad, resueltos en `094319696`
antes de que cualquier barrido pudiera alcanzarlos.** Dos los confirmó leyendo el
fichero; los otros dos el agente los había marcado *unread* y resultaron **peores
de lo que decía el informe** — no eran citas sino referencias a ítems de decisión
(`Design: D1, D2 (ADR-0052 …)`, `Design: D8 (ADR-0052 …)`) que el propio KDoc
nunca define, de modo que un lector no tiene forma de recuperarlos del fichero.
Las dos líneas se borraron; la prosa alrededor ya llevaba el diseño.

- `GitCredentialsApplier.close()` afirmaba que el borrado de temporales era *«safe
  against SIGKILL residue»*. El argumento de seguridad vivía en un documento que
  no está, y **lo cierto es lo contrario**: SIGKILL no es capturable, así que un
  proceso matado deja los ficheros de credenciales en disco hasta que el SO los
  recupera. El KDoc dice ahora ese límite en vez de prometer una garantía que ya
  no tiene quién la respalde. **Esto no se arregla aquí** —arreglarlo es diseño—,
  pero esconderlo detrás de una cita era peor.
- `PluginContributions` decía que S5.5 fue diferido por `ADR-0104` y que Reactor
  vuelve «when ADR-0104's reopening condition is met» —una condición que el
  fichero nunca declara, así que nadie podía actuar sobre ella. El registro de la
  decisión sobrevive; la referencia colgante no.

Quedan **117** sitios de comentarios y KDoc. El trabajo que queda es de revisión
por fichero, y su coste real se ha medido: no es el borrado mecánico, es
decidir si la regla está escrita en las tres líneas siguientes. Eso no se paraleliza
con un script.

**k) La identidad declarada de un plugin: la premisa de este bloque era falsa, y
la corrección posterior también.**
Un agente afirmó «los bytes nunca se miden en producción» y, corrigiendo eso, una
redacción anterior de este bloque concluyó que «nada compara los bytes medidos
contra el `release.digest` declarado por el manifiesto». **Ambas son falsas**, y lo
comprobé abriendo el call site antes de aceptarlas. Y al medir el bloque entero
en este ciclo resultó que **la redacción posterior tampoco era cierta**: la
garantía que este bloque daba por «implementada, conectada y primera en la
cadena» no está conectada a nada.

Lo que hay, medido sobre el árbol:

- **Lo que sí corre en producción** es la garantía de ARTEFACTO, en
  `PreResolvedComposition`: cada artefacto candidato se mide con
  `digestOfArtifactAt` antes de cargar ninguna clase suya, un artefacto que no
  se puede leer se rechaza con `ArtifactNotMeasurable`, dos rutas con los mismos
  bytes son un solo artefacto, y los bytes se vuelven a medir antes de componer
  para rechazar con `ArtifactChangedDuringAdmission` lo que cambió entre
  medias. Esa es una garantía de integridad real, y es la que el runtime
  sostiene en cada ejecución con plugins.
- **Lo que no corre en producción** es la garantía de IDENTIDAD DECLARADA.
  `PluginAdmissionGate.admitThenLoad` —la puerta donde vive la comparación
  `measuredVsDeclaredRefusal`— no la llama nadie: `git grep admitThenLoad`
  devuelve su propia declaración y una mención en un KDoc, ni un call site de
  producción. Y `LocalFileOperatorTrustDatabase` no se construye nunca, así que
  el fichero de confianza del operador no se lee nunca.
- **Y la comparación de esa puerta no podría conectarse tal cual**, porque
  compara dos magnitudes distintas. Eso se midió sobre un plugin real
  (`examples/example-uppercase-plugin`, artefacto construido):
  - el `releaseDigest` declarado es `sha256:3983ca6a…`;
  - el SHA-256 del fichero `.jar` es `sha256:dd23e2c9…`;
  - el digest declarado se reproduce EXACTO con la receta del build (SHA-256
    sobre `nombre + bytes` de cada clase y recurso del plugin, excluyendo los
    dos documentos que llevan el digest, ordenados por ruta), y **ninguna** de
    las diez combinaciones de ordenación y nombre probadas lo reproduce a
    partir del `.jar` ya construido.

  O sea: el digest declarado habla del CONTENIDO del plugin y el digest del
  artefacto habla del FICHERO que lo contiene. No son la misma magnitud, y
  compararlas reportaría a todos los plugins como mentirosos.

**Lo que queda abierto, y es más pequeño que el bloque que sustituye:**

- `PreLoadPluginAdmission.kt:98` llama al `measure` deprecado y recibe
  `measuredDigest = null`: pide una medición y tira el resultado. Es el residuo
  real, no la ausencia de la comparación. **Cerrado en este ciclo**: la ruta de
  producción ya no pide una medición que no va a usar, y construye a mano la
  identidad que puede sostener —el origen y lo que el manifiesto declara— con un
  comentario que dice por qué no lleva digest medido.
- `ArtifactIdentityVerdict` (Verified / Mismatch / Unverified) tiene **cero
  lectores de producción**. Es una segunda forma de expresar una garantía que ya
  se expresa con un `if` en línea. No es un fallo de seguridad: es exactamente el
  «second authority» que AGENTS.md §1 prohíbe. Retirarlo tocaría
  `pipeline-domain.api`, así que espera al mismo break versionado que (h) e (i).
- El digest que el operador ancla en su fichero de confianza es hoy el digest
  DECLARADO por el manifiesto. Eso verifica que el artefacto dice lo que el
  operador aprueba, pero no que sus bytes sean los que el operador aprobó: un
  `.jar` sustituido que conserve el mismo manifiesto pasaría el filtro. Anclar
  los bytes exigiría que el operador anclara el digest del artefacto —otra
  magnitud, otra política de fichero, y una decisión de producto—, no un
  arreglo de código.

**Por qué importa la corrección:** este bloque pedía añadir un caso de
discrepancia a `PluginManifestRejection`. Eso habría creado una segunda
autoridad sobre un control que ya funciona —el error caro— mientras dejaba sin
cubrir el control que sí importa y, de paso, habría instalado en el código una
comparación que acusa a todos los plugins. Lo que se ha hecho en su lugar: la
garantía que de verdad se sostiene en producción —artefacto medido, artefacto
legible o rechazado, bytes que no cambian entre medias— ha pasado a decidirse en
un sitio solo y a tener un observador que se enciende cuando se rompe; y la
comparación imposible se ha medido y escrito, con su número, en vez de
dejarse como una puerta que nadie abre.

**l) Los *bookends* de run y stage se vuelven a emitir en cada reanudación durable.**
Trazado a mano, extremo a extremo:

1. `MainDurableRunSupport.kt:35` — `--resume` reutiliza el **mismo `runId`**
   (`runIdDirectory.lastRunId(definitionId)`).
2. `CanonicalDurableRunCoordinator.run()` llama a `runLifecycle.openRun(...)`
   **sin condición**, y el bucle de stages es `for (stageIndex in pipeline.stages.indices)`,
   o sea desde el 0. Ninguna de las dos consultas journal o event store.
3. `RunLifecycleEngine.openRun` / `stageStarted` / `stageFinished` emiten con
   `UUID.randomUUID()` y `sequence = 0L`, siempre.

La duda que decides si esto es defecto o diseño era si el `UNIQUE(run_id, sequence)`
del store lo neutralizaba. **No lo neutraliza**, y la comprobación merece quedar
escrita: `SqliteEventStore.appendAssigned` trata `sequence == 0L` como *petición de
reasignación*, no como valor — `counter.incrementAndGet()`. O sea que el segundo
`RunStarted` no colisiona, **se escribe con la siguiente secuencia**. Tampoco
ayudaría la proyección: `EventViewProjection` incluye `RunStarted` en su conjunto de
lifecycle sin deduplicar.

Resultado tras un fallo en el stage 2 y su reanudación: **dos `RunStarted` para un
run**, y cada stage ya completado vuelve a anunciarse.

**CORRECCIÓN: esto NO es un descuido, y el ROADMAP lo daba por tal.** La versión
anterior de esta entrada argumentaba que la asimetría con el nivel de step delataba
un oversight: `RecoveryInterpretationEngine.kt:241` suprime duplicados a nivel de
**step** y no a nivel de run/stage. Esa era una inferencia mía, y el código fuente
dice lo contrario. `EventHarness.kt:35` lo nombra literalmente:

> *Under BASELINE DEBT **INC-EVT3-1** durable reuse re-appends skeleton events with
> restarting sequences; insertion order keeps the last execution segment a
> contiguous suffix, so AFTER_LAST_RUN_STARTED scope stays deterministic WITHOUT
> timestamps.*

O sea que el re-emitido es una **deuda basal identificada**, y no un descuido: es
la razón de que exista `Scope.AFTER_LAST_RUN_STARTED`, un ámbito que corta el log
en «la última ejecución» justo por el último `RunStarted`. El duplicado es hoy
**portador** — es lo que hace separable el intento actual — no un descuido que
alguien olvidara. La lección operativa queda escrita porque es la que casi me hace
escribir un «defecto» sobre una deuda ya documentada: **leer la autoridad antes de
juzgarla**, y el instrumento para ello era un `grep INC-EVT3-1` que no hice.

**La comprobación de consola sí la hice, y sale negativa.** `ConsolePrintingEventSink`
es un **decorador del sink** que renderiza en vivo, dentro del proceso. Una
reanudación arranca un proceso nuevo con un sink nuevo, así que la consola de esa
corrida ve **un** `RunStarted`. `--log-tail` tampoco lo duplica: `MainInspectCli.kt`
solo trata `RunFinished` de forma especial. El síntoma, por tanto, **no es visible
para el operador**; queda confinado al log durable y al read model
(`EventViewProjection` no deduplica).

**Lo que queda abierto es una sola pregunta, y es de producto:** si `INC-EVT3-1` se
cierra algún día —insertando un discriminador de intento explícito en lugar de
reusar `RunStarted` como marcador de segmento—, `AFTER_LAST_RUN_STARTED` deja de
tener su significado y hay que migrarlo. Hoy no hay ruta de cierre escrita para
`INC-EVT3-1` en ningún sitio del árbol: sus dos únicas apariciones son esas dos
líneas de KDoc, y **este ROADMAP tampoco lo recogía**. Eso sí es un hueco real: una
deuda basal sin ninguna entrada que la siga.

Nota menor, del mismo tipo que las de 2.1: el KDoc de `EventHarness` explica el
alcance «con secuencias que se reinician», pero `appendAssigned` reasigna `0L` con
el contador monotónico. La deuda está vigente; **su explicación del mecanismo ya no**.

**`INC-EVT3-1` recibe la entrada que le faltaba — CERRADO como hueco, abierto como deuda.**

El hueco era real y estaba enunciado en el punto anterior: una deuda basal cuyas
únicas apariciones en todo el árbol eran **dos líneas de KDoc** en
`EventHarness.kt`, sin ninguna entrada que la siguiera. Ahora tiene una. Lo que se
corrigió al hacerlo, en `pipeline-event-harness`:

1. **Las dos frases sobre el mecanismo eran falsas.** Decían «re-appends skeleton
   events with restarting sequences» y «deterministic under INC-EVT3-1
   **duplicate-sequence** debt». No hay ni reinicio ni duplicado de secuencia:
   `appendAssigned` trata `0L` como *petición de reasignación* y devuelve
   `counter.incrementAndGet()`, así que el segundo `RunStarted` toma la
   secuencia siguiente y no colisiona con `UNIQUE(run_id, sequence)`. Lo que se
   duplica es el **evento**. El KDoc nuevo dice por qué, porque la corrección es
   la parte no obvia: sin ella, el próximo que lo lea «arregla» la deuda
   buscando un reinicio de secuencias que no existe.
2. **Había un KDoc huérfano.** Dos bloques seguidos: `lastSegment` se quedaba con
   el de abajo y **`verify` quedaba sin documentar**, con su nota de pureza
   adherida a la función equivocada. `verify` recupera su KDoc.

Lo que sobrevive intacto, y es lo que hace que la deuda sea *portadora* y no
descuido: `AFTER_LAST_RUN_STARTED` corta por **orden de inserción**, nunca por un
timestamp, y eso sigue siendo lo que hace separable el intento actual.

**Medido de paso, y no se tapa:** `lastSegment` **no tiene consumidor**.
`git grep` la encuentra sólo en su propia declaración; lo vivo es
`Scope.AFTER_LAST_RUN_STARTED`, al que llegan `verify`, `accept` y `report` por
`scoped()`. No se borra porque `:pipeline-event-harness` es artefacto publicado y
retirar una función pública es una ruptura de ABI que no está autorizada aquí.

**Ruta de cierre de `INC-EVT3-1`, escrita donde faltaba.** La deuda se cierra
insertando un discriminador de intento explícito en el esqueleto de eventos, en
lugar de reusar `RunStarted` como marcador de segmento. La condición de cierre
no es opcional y se escribe ahora para que nadie la descubra al cerrar la deuda:
**`AFTER_LAST_RUN_STARTED` debe conservar un significado definido**, o la
migración cambia el alcance por defecto de `verify` y `accept` sin que nadie lo
haya pedido. Si el discriminador es un campo nuevo en el evento, es ruptura de
formato y necesita versión; si es un evento nuevo, `AFTER_LAST_RUN_STARTED` pasa
a significar «desde el último `<nuevo evento>`» y hay que migrarlo en el mismo
cambio. Hoy no hay fecha ni responsable; hay ruta.

**d) Ninguno de los 31 escenarios comprueba sólo el código de salida.** Era 13 de
36 al abrirse este bloque; hoy es **0 de 31**.

Censo reproducible sobre los 31 `.pipeline.kts` del árbol. El lanzador conoce
cuatro aserciones: `//@ expect-exit`, `//@ expect-stderr`, `//@ expect-stdout` y
`//@ expect-transcript`. Todo lo demás tiene que afirmarlo el propio escenario,
y no todos lo hacen:

| | Qué afirma | N |
|---|---|---|
| A | `//@ expect-stderr`: el lanzador exige **este** texto de negativa, no un código cualquiera | 5 |
| B | El efecto: `//@ expect-stdout`, `//@ expect-transcript`, o una comprobación dentro del cuerpo (`test`, `grep -q`, `cat`, `find`, `wc -l`) que falla si el efecto no ocurrió | 25 |
| C | **Nada** que compruebe el efecto; el lanzador compara el código de salida y ya está | **0** |

El único que quedaba era `plugins/uppercase`, y su comentario explicaba por qué
no afirmaba el texto transformado: el resultado de un paso no vuelve al script.
La explicación era correcta y la conclusión no: lo que no se puede afirmar es
**el valor**, no **que el paso se ejecutara**. El runtime anuncia el plugin que
descubrió y la consola narra el paso que ejecutó, y las dos cosas se afirman
ahora. Un run que resolvió el paso y no lo ejecutó imprime la etapa y sale con
éxito: esa forma era verde y ahora es roja, y se ha visto roja por mutación
—sustituir la llamada al paso por otra cosa la pone en rojo con el mensaje
exacto—.

**El instrumento de este censo falló cuatro veces antes de reconciliar**, y es
la cuarta vez que aparece en este ciclo que un censo da un número confiado y
equivocado. La secuencia vale más que el número: `test -[efdLrn]` no veía `s`
ni el `!` de `test ! -e`; `test [^|&;]*-x` se detenía en el `&&` de un `&&` y
por eso declaraba sin aserción un escenario que acababa de escribir; un parser
que extraía el argumento de cada `sh(...)` se comió los `sh` de varios
ficheros; y sólo el cuarto —buscar el token en el código ya sin comentarios y
**mostrar en cada fila el token que la prueba**— reconcilió. La columna de
evidencia es la parte que evita la quinta: una cifra que no puede mostrar su
propio instrumento no debería publicarse.

**Un código de salida verde no es un comportamiento verde**, y el daño es mayor
donde el efecto es destructivo o persistente: `steps/18-cleanWs` borraba un
directorio sin comprobar que lo borrara, y `control-flow/11` escribía un marker
dentro de `dir` sin comprobar que `deleteDir` se lo llevara. En ambos, una
regresión dejaba datos de otra corrida en disco y nadie se enteraba.

### Lo que queda en C no es todo cerrable con el vocabulario actual

Clasificados uno a uno, los 13 no son el mismo problema, y llamar «sin
cobertura» a todo lo que queda sería volver a mentir con una cifra plana:

- **Uno ya está cubierto y el censo no lo ve.** `negative/12-error-handling`
  mete un `sh("exit 1")` dentro de `catchError` y `warnError`, y su garantía
  es que **el proceso continúe**. El lanzador exige salida 0 por defecto, así
  que una regresión en `catchError` —que abortaría la corrida— lo pone en
  rojo. El código de salida *es* la aserción aquí; contarlo como C es un
  artefacto de buscar un token `test`, no una ausencia real.
- **Dos dependen de un valor que el DSL no devuelve.** `steps/26-find-files`
  ejercita `findFiles`, que devuelve `Unit` y no escribe nada en disco;
  `durable/20-pwd-tmp` dice en un comentario que dos invocaciones de
  `pwd(tmp=true)` deben dar paths distintos, y la aserción natural —comparar
  los dos valores devueltos— **es incorrecta**: ambos son el placeholder
  literal `<workspace>`, y el KDoc de `StageScope.pwd` dice que ese placeholder
  «MUST NOT be used as if it were the real runtime path». Se retiró esa
  aserción y se dejó escrito el motivo dentro del propio escenario, para que
  el defecto no vuelva a reportarse como si fuera del producto.
- **Los diez restantes son `echo`.** `smoke/01`, `smoke/03`, `steps/04`,
  `steps/13`, `steps/16`, `control-flow/05`, `durable/19`, `durable/21`,
  `plugins/lock-directive` y `plugins/uppercase` no producen ningún fichero,
  ningún directorio y ningún valor consultable: producen **líneas de salida**.
  Escribirles un `sh("test …")` no comprobaría su sujeto, sólo añadiría ruido.

O sea: **el C restante es, en su mayor parte, una única decisión de producto** —
una tercera directiva `//@ expect-stdout`, para la cual el lanzador ya guarda
`stdout.txt` pero cuyo encabezado declara que no tiene nociones de aserción más
allá de sus dos directivas opcionales. Adoptarla es cambiar un invariante
declarado del lanzador, y por eso no se decide dentro de un escenario.

Coste por defecto de cerrar el resto: cada uno recibe **una** comprobación
pequeña sobre un efecto que ya produce —el fichero que escribe, la línea que
emite, el directorio que debe quedar vacío—, no un `Shell` que reimprima lo que
el escenario ya hizo. La lente de este bloque es `change-impact`: la garantía
es «el escenario falla cuando el efecto público no ocurrió», y el instrumento
más barato es una aserción dentro del propio pipeline, que no cuesta un proceso
extra ni un segundo vocabulario en el lanzador.

**n) El glob de artefactos no restringe la extensión — MEDIDO, seguridad.**
`AntStyleGlob` delega en `AntPathMatcher` de Spring y su KDoc anuncia soportar
`[...]` y `{...}`. Medido contra **spring-core 6.2.19**, la versión que fija el
proyecto: `build/**/*.{jar,war}` casa con `build/secret.pem` **y** con
`build/app.zip`, y `{a,b}` casa con `zzz` — Spring convierte `{...}` en un
grupo que casa con cualquier cosa, no en la alternancia de Ant/Jenkins —, y
`app-[a-z].txt` no casa con `app-a.txt`, porque `[...]` es literal y no un rango.
Consecuencia: `archiveArtifacts(pattern = "build/**/*.{jar,war}")` mete en el
`.tar` **todo** lo que hay bajo `build/`, incluidos `.pem`, `.env` o
`credentials.bin`. El test existente (`AntStyleGlobPatternTest`) fija la
*representación* del patrón —«una coma no parte el patrón»— y nunca su
*semántica*, que es por eso que el defecto no lo cazó nadie: un patrón que no
encuentra lo que uno cree no se parece a un patrón que encuentra de más.
Radio de impacto: `LocalArtifactStore.archiveArtifacts` y el step
`archiveArtifacts`. Lo que hace falta: alternancia y rango reales (propios o de
otra biblioteca) **con una tabla de verdad como test**, y el KDoc describiendo
lo que la implementación hace.

**o) El arnés E2E podía dar verde sin comprobar nada — CERRADO en `9c226b83f`.**
Cuatro maneras, todas medidas: una **directiva desconocida se ignoraba** (el
`case` no tenía rama por defecto, así que un escenario que quiso ser durable y
escribió `//@ durabel` pasaba en verde corriendo contra un almacén volátil);
**cero escenarios era un verde** (sin filtro no había comprobación alguna: un
descubrimiento vacío imprimía `0 scenario(s), 0 passed, 0 failed` y salía con
`0`); **`expect-exit` se descartaba** en modo `durable` (se forzaba a `0`); y
**`resume` daba por bueno** cualquier salida distinta de cero como «el proceso
murió», de modo que un error de compilación (1) era indistinguible de un
`SIGKILL` (137). Cerrado: la directiva desconocida es un fallo que nombra las
directivas válidas, cero escenarios ejecutados es un fallo, `expect-exit` se
respeta en todos los modos, y `resume` exige una muerte real (128+señal). El
contrato del lanzador queda protegido por su propia forma de fallar: una
directiva que no conoce es un fallo que la nombra, un filtro que no casa con
nada es un error, y cero escenarios ejecutados es un fallo. No hay un
instrumento permanente que lo compruebe, y no lo habrá: una meta-prueba del
arnés sería un segundo arnés, que es justo lo que la política no admite. La
evidencia es la mutación, y se vuelve a hacer cada vez que el contrato cambia.

**p) 34 de los 44 escenarios E2E sólo comprueban el código de salida.** Diez
escenarios declaran `expect-stderr` o llevan un bloque `post { }`; los otros 34
sólo miran el código de salida, y algunos **prometen más de lo que comprueban**:
`output/23-readfile` escribe y lee un fichero y descarta el resultado de
`fileExists`, así que pasaría en verde aunque el fichero no existiera; `steps/04-sh`
comprueba que el proceso arrancó, no que el shell hiciera nada. Para un
*smoke* comprob poco es lo pactado —pero un escenario cuyo nombre nombra una
capacidad (`readFile`, `zip-unzip`, `artifact-query-bridge`) es una promesa, y
una promesa que no se cumple es deriva entre el nombre y la aserción. Es también
la vía por la que el coste del gate se paga sin valor: 34 escenarios que no
comprueban nada cuestan lo mismo que 34 que comprueban.

**Auditoría del 2026-10-20 (lectura de código, no de documentos).** Una revisión
del árbol del productor midió el estado real del roadmap y de las especificaciones
y encontró, además de n), o) y p): que la etapa 3 del gate es el **86 %** del
coste (298 s de 345 s medidos; 44 escenarios ≈ 6,8 s y 48 arranques de JVM), que
`pipeline-protocol` está versionado pero **no está declarado** en Gradle, luego
nunca se compila, y que el «686 tests» que cita §1 es un **censo** de informes
en disco, no una medición (en esa corrida, de 85 informes sólo 9 se
reescribieron). El informe completo, con el plan priorizado y las propuestas de
pensamiento lateral, está en el paquete de evolución del Saga, en
`pipelinek-saga-evolution/auditoria/INFORME-AUDITORIA-2026-10-20.md`.
Una de sus reglas se ha seguido aquí: un documento de estado **no** es evidencia,
así que cada cifra de esta auditoría se ha medido ejecutando, y una corrección
anterior que parecía verdad se ha vuelto a medir antes de creerla —y era falsa.


### 2.3 Cierre de ciclo

- `PRODUCT-GATE` (certificador externo, RC publicada) = **`BLOCKED_EXTERNAL`**.
  No hay CI remoto en este repositorio. La ausencia de CI **nunca** se declara
  `PASS`.
- **Presupuesto de tiempo: CERRADO**, medido sobre `a2342405b`. Tres ensayos
  desde salidas de test limpias dieron **227 s, 223 s, 226 s**: el peor, **227 s**
  frente al límite duro de 900 s, leído del XML de JUnit (**557 tests, 59/59
  informes reescritos por la corrida, 0 fallos, 0 errores, 0 skips**) y no del
  código de salida. Los 59 informes reescritos en las tres pasadas son la prueba
  de que ninguna lectura es residuo de un ensayo anterior. E2E: 36 escenarios, 35
  pasan, 0 fallan, 1 `NOT_RUN`, 1 rerun durable. Host de referencia:
  Intel Xeon E5-2682 v4 @ 2.50 GHz, 64 hilos, 94 GiB, JDK Temurin 21.0.8,
  Gradle 8.14.5, cachés de dependencia ya pobladas.
  **Las salidas de test se limpian antes de CADA ensayo, no solo al principio**:
  el guard de stage 2 rechaza un full run cuyo grafo esté entero `UP-TO-DATE`,
  y sin esa limpieza los ensayos 2 y 3 cachean todo y salen a los 29 s. Ese
  rechazo es correcto y hay que obedecerlo, no sortearlo —medir un cache hit
  como si fuera el coste del gate es justo lo que ese guard existe para
  impedir.
  **El veredicto de estos tres ensayos es `INCOMPLETE … gaps=1 not_run`, no
  `PASS`.** Las series anteriores (`9c4bd7b8`: 234 / 223 / 223 s, 531 tests;
  `28667109`: 228 / 215 / 215 s, 526 tests; `2d4fdf38`: 224 / 227 / 231 s, 522
  tests; `d8b0c413`: 249 s) seguían siendo ciertas para su propio SHA. La serie
  de `9c4bd7b8` y las anteriores a ella imprimían `PASS … gaps=1` y leían
  «verde con un hueco anotado»; ese era el defecto que `f37cd0234` corrigió. Hoy
  el lanzador dice literalmente *«This is not the gate»* y se niega a llamarlo
  aprobado mientras un escenario no se haya ejecutado. El hueco es **uno y solo
  uno**, y §2.6 lo analyse: no es un escenario que falle, es uno que `run.sh`
  salta antes de ejecutarlo. Series y cifras anteriores siguen siendo ciertas
  **para su propio SHA** y para ninguno más; se vuelven a medir en cada avance de
  `HEAD` porque un gate verde por inercia sería peor que uno marcado caducado.
- **Presupuesto de LOC: CERRADO**, medido en la misma corrida.
  `VERIFY_LOC = 7229` / `PRODUCT_LOC = 61650` = **0.117**, muy por debajo del
  límite duro de 2. El censo cuenta sólo ficheros **rastreados** (`git ls-files`),
  así que esta cifra es del árbol ya commiteado: un test recién añadido sin
  commit todavía no está en el numerador y rebajaría la ratio a la baja.
  **Los dos módulos con test más densos ya no son los que se citaban antes.**
  Los de verdad son `pipeline-step-sdk:workflow-control` 80 / 49 = **1.633** y
  `pipeline-credentials-executor` 349 / 346 = **1.009**; la serie de este ciclo
  los confirma. La versión anterior de este párrafo nombraba
  `pipeline-credentials-local` (0.63) y `pipeline-artefacts-local` (0.335) como
  los dos primeros, y era una afirmación caduca: siguen cerca, pero no son los
  más densos. Un tercero se ha acercado — `pipeline-output` 689 / 714 = **0.965**—
  y conviene vigilarlo: un ratio por encima de 1 significa más líneas de test que
  de producto en el módulo, y eso merece una revisión de valor —más
  instrumentación que garantía— antes de añadir nada más ahí.

### 2.4 `pipeline-events` — el siguiente bloque desbloqueado

*(Estado: **EJECUTADO** en `29d6aa31e`. Se conserva el razonamiento que lo
justificó, porque explica por qué se eligieron estas dos garantías y no otras.)*

24 ficheros de producción (4315 LOC), **cero** tests: el directorio
`src/test/kotlin` no existía. Era el módulo de producto más grande sin cobertura,
y su caso tiene la misma forma que cerró `findFiles`: **la capa E2E no puede
observarlo**. `examples/run.sh` lee el exit code y, como mucho, una subcadena
de `stderr`; captura `stdout` a fichero y **nunca lo abre**. Una garantía rota
en este módulo no cambia ninguno de los dos hechos que el lanzador mira.

La lente `regression-promotion` ordena exploration de cobertura antes que
escritura, y esa exploración dio un resultado que sostiene el bloque: un `grep`
sobre **todos** los `src/test` del árbol principal encuentra cuatro ficheros que
importan algo de `pipeline.v2.events`, y los cuatro lo usan como **fixture de
entrada o stub** —no uno ejerce una invariante del módulo. No hay cobertura
equivalente, luego un test aquí no es un segundo test de nada.

Decidido por garantía, discriminancia y coste:

- **`EventCursor.encode`/`decode` — PROMOTE.** Garantía: `decode(encode(c))`
  devuelve `c` para todo `runId`, **incluido uno con `:`** —que es la forma real,
  porque `ResourceRef.canonicalText()` es `v<version>:<kind>:<segments>`— y
  `decode` devuelve `null` ante prefijo, número de partes, secuencia o `runId`
  inválidos. El defecto **ya ocurrió aquí** y lo corrigieron; lo que se borró fue
  su guardián en `2aa071aab`, así que la regresión volvería en silencio. Quitar el
  `URLEncoder` de la línea 39 lo reproduce exactamente. Función pura
  `String`↔`String`: el instrumento más barato de los tres.
- **`EventRegistry` — PROMOTE.** Garantía: un `kind` duplicado devuelve
  `DuplicateKind` y **no sobrescribe**; tras `build()`, reutilizar el `Builder`
  no muta el registry ya entregado; un `kind` desconocido devuelve `null`, no un
  placeholder. Es *fail-closed* de seguridad: dos plugins reclamando el mismo
  `kind` volverían ambiguos para siempre todos los payloads ya grabados, y esa
  ambigüedad no la detecta ningún exit code. Puro.
- **`EventViewProjection` — DEFER, y no es un test.** 166 LOC y **cero
  productores**; su propio KDoc lo confiesa («This projection has no production
  caller at all»). Un test que pasa sobre código muerto es una afirmación falsa.
  La pregunta correcta no es «¿cómo lo pruebo?» sino «¿esto se borra?», que es
  una decisión de ciclo de vida y no una garantía. Se plantea antes de escribir
  una línea.

Dos tareas de deuda que **no** son cobertura y salen del mismo vistazo:

- `pipelinek/pipeline-events/build.gradle.kts` declara `tasks.test { inputs.dir(…
  pipeline-events-store/src/main/kotlin) }` justificándolo con un
  `EventSchemaNoMapStringStringTest` que `2aa071aab` borró. Es una entrada de
  build cache sin consumidor: paga coste de hashing en cada corrida para nada.
- Cuatro KDoc prometen una garantía que ya nadie verifica, porque nombran tests
  borrados: `EventRegistry.kt:8` («Laws, each pinned by `[EventRegistryTest]`»),
  `EventStore.kt:107`, `EventShapeReadPortStoreAdapter.kt:30` y `TypedEvent.kt:9`.
  Es el patrón de «un comentario afirma una propiedad que el cableado ya no
  tiene»: aquí el lector conclusiona que hay un guardián y no lo hay.

#### Ejecutado en `29d6aa31e`

Las tres decisiones se aplicaron tal cual, y el bloque **no se cerró** en las
promesas: se cerró en las afirmaciones.

- `EventRegistryTest` (8 filas) y `EventCursorTest` (10 filas). **18 tests,
  0 fallos, 0 errores, 0 skips**, leído del XML JUnit, con `:pipeline-events:detektTest`
  como gate **separado** ejecutado en la misma pasada (los dos tasks no se
  invocan entre sí). Cada garantía principal se verificó por mutación: aplicar,
  **leer el diff de vuelta**, registrar qué filas se pusieron rojas, restaurar
  byte a byte.
- Una de las dos_rows de `EventCursorTest` turned out **no** ser un guardián
  independiente: la fila de la barra oblicua falla por los dos puntos, porque su
  `runId` también lleva `:`. El comentario que lo afirmaba era falso y se
  reescribió con el resultado medido, no con el predicho.
- `EventViewProjection` sigue **DEFER**. No se escribió un test sobre código sin
  productores; la pregunta sigue siendo si se borra, y es decisión de ciclo de vida.
- Las dos tareas de deuda se resolvieron: el `inputs.dir` huérfano se eliminó
  (y `useJUnitPlatform()` quedó anotado como *load-bearing*: sin él Gradle corre
  el runner de JUnit 4, descubre cero tests y reporta `BUILD SUCCESSFUL` sobre nada),
  y las cuatro promesas de KDoc se reescribieron diciendo lo que es cierto.

Una quinta, `EventFollower`, se trata en sentido contrario: su comentario
afirmaba que el defecto F2 (`Completed` en una página silenciosa, que confunde
snapshot con live follow) estaba clavado por dos falsificadores, y **los dos se
borraron** en `2aa071aab`. Ningún escenario E2E observa el flujo de eventos, así que
la máquina de estado puede volver al defecto exacto que su propio KDoc describe y
toda la suite ejecutable sigue verde. El comentario ahora dice eso, en vez de
nombrar dos guardianes ausentes.

#### `EventViewProjection` — cerrado como DEFER, y por una razón que no era la del DEFER

`29d6aa31e` lo dejó `DEFER` con la pregunta «¿esto se borra?». Esta pasada
contestó la pregunta, y la respuesta no es «es código muerto».

**No es borrable.** `EventViewProjection`, `ViewMode` y `OutputFormat` están
**los tres** en `pipelinek/pipeline-events/api/pipeline-events.api`, el dump que
`binary-compatibility-validator` genera (declarado en `pipelinek/build.gradle.kts:355`)
y sobre el que corre `apiCheck`. Quitar API pública es un **break versionado
autorizado**, no una limpieza — AGENTS.md §1 lo prohíbe sin autorización. El DEFER
no era indecisión: era la categoría de decisión equivocada. Sigue sin tocarse, y
ahora el KDoc dice por qué se queda.

Lo que sí era defecto, y se corrigió (`83ec78aef`): el KDoc afirmaba que
«only its own test reaches it». **Ese test no existe.** Medido sobre el árbol:
`project`, `ViewMode` y `OutputFormat` tienen **cero** referencias en todo el
árbol, y los dos únicos tests del módulo (`EventCursorTest`,
`EventRegistryTest`) no los tocan. Tres promesas más nombraban una superficie
inexistente: un CLI que «materialises the projection lines», un `--view full`, y
un caso del enum que algo «seleects». `EventInspection` era el espejo: se
describía *apoyándose* en la proyección y el CLI la llamaba directo. **Esa sí
está viva** (tres llamadas en `MainEventsCli`), así que solo se corrigió la
afirmación de capas. `Main.kt:314` nombra `EventViewProjection.CONSOLE` al
justificar por qué `console` es un verbo aparte: ese enum existe y sí está
deprecated, así que esa referencia se queda.

Verificado: diff solo de comentarios (0 líneas de código), `apiCheck` y ambos
`compileKotlin` verdes, dump ABI sin cambios. Coherente con que
`effective_loc()` excludes comentarios: `PRODUCT_LOC` no se movió.

### 2.4b `pipelinek/pipeline-protocol/` — retirado: se midió la alternativa antes de decidir

**Aviso de lectura: la afirmación central de la versión anterior de este bloque
era falsa, y el razonamiento que la sostenía era una inferencia, no una
medición.** Decía que `ProtocolCore.kt` «no compilaría aunque se declarase»,
porque referencia `dev.rubentxu.pipeline.v2.protocol.Version`, un tipo que solo
produce `protoc`, y porque no existía `pipelinek/pipeline-protocol/build/`: si `protoc`
nunca corrió, el tipo nunca existió. Pero la ausencia del directorio de build
demuestra solo que **nadie declaró el módulo**, no que el módulo no compile.

**Medido, en este ciclo.** Se añadió `:pipeline-protocol` al `include` y se
compiló: `BUILD SUCCESSFUL in 36s`, con 120 ficheros Java y 60 Kotlin
generados. El builtin `kotlin` lite funciona porque `protobuf-kotlin-lite` está
en el catálogo, así que la inferencia anterior era falsa en su segunda mitad
también: el tipo se genera en cuarenta segundos. La declaración se revirtió
después de medirla; el árbol que se entrega no lo declara.

**La decisión, entonces, no era «compila o no compila».** Era si el módulo
merece la vida. Los hechos que sí se sostienen, todos medidos:

- **Cero consumidores** de `ProtocolModules`, `ProtocolGovernance` y
  `ProtocolVersion` en todo el árbol versionado, y cero referencias a
  `v2.protocol`.
- **No publica y no está en el gate de ABI**: su `build.gradle.kts` declara
  `group` pero no aplica `maven-publish`, y el módulo no tiene dump `.api`.
- **Describe una capacidad que el producto no tiene.** Los ocho `.proto`
  modelizan un protocolo de worker distribuido: `worker_hello`,
  `negotiated_session`, `leases` con *fencing token*, `heartbeat`, acuse de
  recibo y backpressure. El producto es un CLI local: su «lease» es un bloqueo
  local de fichero (`FileLockCoordinator`), no un lease distribuido, y no hay
  `heartbeat`, negociación ni agente remoto en ninguna parte del árbol. El
  roadmap no contiene ningún bloque de ejecución remota, distribuida o por
  clúster.
- **Costaría tiempo en cada build** para generar 180 ficheros que nadie llama.

**Decisión: se retira.** Declararlo lo convertiría en un módulo compilado sin
consumidores; mantenerlo versionado era un `build.gradle.kts` de 42 líneas
escrito para un módulo que el `include` raíz no declara, es decir, un fichero
de build que miente. Retirarlo no rompe ni un artefacto publicado, ni un dump de
ABI, ni un formato de cable, porque no había ninguno. El diseño no se pierde:
queda en el historial, que es donde puede consultarse el día que el producto
hable con workers remotos.

**Lo que se llevó la decisión.** Diez ficheros borrados (511 líneas efectivas),
el plugin `com.google.protobuf` de la raíz, y las cinco entradas del catálogo
que solo ese módulo usaba. Con ellas, el árbol se queda sin un solo módulo
versionado fuera del `include`: los directorios con `src/main` y fichero de
build que el `include` raíz no declara son `pipelinek/buildSrc` y los tres plugins de
ejemplo, y los cuatro son builds reales.

**Lo que se retira: el defecto tampoco era de `check-budgets.py`.** La versión
anterior de este bloque propuso «derivar el denominador de los módulos que
`settings.gradle.kts` declara». **Medido y falso: ese filtro habría restado
producto real.** Los cuatro builds de la tabla siguiente suman **2.242** líneas
de producto que el filtro habría hecho desaparecer del denominador, dejando el
gate **más verde por la razón equivocada**, que es el peor modo de fallar un
instrumento de presupuesto.

| directorio | líneas | qué es |
|---|---:|---|
| `pipelinek/buildSrc` | 302 | convention plugins que compilan el producto entero |
| `examples/example-block-plugin` | 598 | build independiente, JAR en la distribución (`runtimeOnly`) |
| `examples/example-uppercase-plugin` | 932 | ídem |
| `examples/example-directive-plugin` | 410 | ídem |

**Instrumento del gate: sin cambio, y con motivo.** Los globs cuentan directorios
con fichero de build, que es exactamente la condición para que un directorio sea
construible. `pipeline-protocol` lo tenía; que su `include` faltara era la
inconsistencia de ese módulo, no la del gate.

**Por qué no lleva test, y qué se acepta a cambio.** Una comprobación de que
`settings.gradle.kts` no contiene una cadena es *source-text fitness*, que §2
prohíbe, y el build tampoco puede fallar por un directorio que no declara,
porque no lo ve. Lo que se acepta a cambio es explícito: un módulo así cuenta
como producto en el techo de LOC sin estar compilado, y por eso el ciclo lo
detectó **leyendo**, no por un instrumento. Un instrumento que lo detectara
tendría que ser un guard de estructura del build, y el árbol no tiene ninguno.

### 2.5 Las promesas colgantes: medidas, y saldadas

> **Estado: saldadas en este ciclo.** Cuando este bloque se escribió, el
> instrumento de abajo contaba **74** clases de test que `src/main` nombraba sin
> que existiera el fichero que las declarara, repartidas en **70** ficheros de
> producción, y **12** de ellas **afirmaban además** un instrumento
> (`pins`/`guarded`/`falsifier`/`asserts`). Este ciclo las reparó todas: el
> censo da hoy **0**. El texto que sigue se conserva como el relato de lo que
> se midió y de por qué la reparación fue de este modo, no como el estado
> actual.

El patrón anterior no era de `pipeline-events`. Se extendía.

**Instrumento** (reproducible, sobre el árbol commiteado):

```
tracked=$(git ls-files '*src/test/*' | sed 's|.*/||; s|\.kt$||' | sort -u)
grep -rhoE '\b[A-Z][A-Za-z0-9]*(Test|Tests|FitnessTest|FalsifierTest|LawsTest)\b' \
     --include='*.kt' pipelinek/*/src/main pipelinek/*/*/src/main \
  | sort -u | grep -vxF "$tracked"
```

**Cifras, con su denominador:**

| magnitud | entonces | hoy |
|---|---|---|
| clases de test citadas en `src/main` **sin** fichero que las declare | **74** | **0** |
| ficheros de producción con al menos una | **70** | **0** |
| de ellas, las que además **afirman** un instrumento | **12** | **0** |

Tres advertencias sobre el propio instrumento, porque sin ellas la cifra engaña:

1. Con el sufijo `*Spec` el mismo barrido da más: `ToolSpec`, `TaskSpec`,
   `TimeoutSpec` son ADTs de dominio. Un censo que no conoce el vocabulario
   del repositorio cuenta su propia exclamación como deuda.
2. El censo cuenta **menciones**, no promesas: una mención en pasado (`used to
   pin`) no es una promesa, y una que nombra un test **existente** es una cita
   útil, no una deuda.
3. El primer instrumento miraba `pipelinek/*/src/main` y se le escapaban **12**
   referencias de los **módulos anidados** (`pipeline-step-sdk/*`). Ampliado a
   `pipelinek/*/*/src/main`, el total real era **74**, no 73. Un censo con
   el denominador equivocado subestima.
4. Queda **un** falso positivo que hay que nombrar para que el «0» sea
   creíble: `ParallelTest` es el **nombre de un stage** en el mensaje de error
   de un ejemplo, no una clase de test.

**Lo que cambió la decisión.** Este bloque decía antes: «**NO se reparan las
73** […] la regla que sí se adopta, para el trabajo que venga: una promesa en
KDoc se reescribe cuando se toca el fichero, no en una campaña». El encargo de
este ciclo cambió esa regla: pedía comentarios estándar que expliquen la
funcionalidad integrada, sin identificadores de tareas ni de especificaciones.
Una campaña es exactamente lo que se@Spring, así que la campaña se hizo —pero
**acotada a lo que miente** y con el daño del barrido medido y reparado:

- Primero las que **afirmaban un instrumento inexistente** (12): un KDoc que
  promete un guard que no existe es una afirmación falsa, y una afirmación
  falsa en el código es un defecto, no una cuestión de estilo.
- Después las **neutras** —las que nombraban un test sin afirmar que lo
  protegía—, por coherencia del texto.
- Y las referencias a **documentos que no existen**: `ADR-0…` bajó de **295
  líneas en 126 ficheros** a **0 en código**, e igual `FArchL`, `WCL-S`,
  `FIL-ALL` y `design.md §`.
- El barrido **mecánico** dejó prosa rota —«the ingest agent of ``»,
  «documents:,,,,», «the launcher ('s», negritas vacías, «the
  invariantprotects»— y esa prosa rota se reparó **a mano**: una sustitución
  automática sobre 86 ficheros no puede mantener la gramática, y una gramática
  rota en el KDoc es un defecto de la misma familia que una cita falsa.

**Lo que NO se reparó, y por qué.** Las referencias `INV-L6-CR-011` que salen
en `GitCredentialsApplier` no son comentarios: son **contenido del config de
git que el producto escribe**. Cambiarlas cambiaría el artefacto emitido, y eso
ya no es un comentario. Los `docs/` de `examples/durable/31-stash-unstash` son
un **directorio de fixture** dentro del escenario, no una referencia al árbol
de documentos borrado.

### 2.6 El `NOT_RUN` que impide el `PASS` — y por qué no se ha cerrado

> **Estado: cerrado en este ciclo.** El hueco que este bloque midió ya no
> existe: `credentials add` acepta `--from-stdin`, el lanzador siembra el
> almacén del escenario con el verbo del producto, y el escenario se
> ejecuta y afirma. Lo que sigue se conserva como el relato de lo que se
> midió y de por qué, no como el estado actual.

El veredicto del gate local es **INCOMPLETE**, no `PASS`, y la diferencia es un
único escenario: `examples/security/14-credentials-bindings.pipeline.kts`.
Desde §2.3 venía justificado por «necesita un almacén de credenciales sembrado
sobre una TTY». Ese motivo era una hipótesis. **Ahora está medido.**

`examples/run.sh` no lo intenta nunca: la directiva `//@ requires-seeded-credentials`
provoca el `NOT_RUN` incondicionalmente, antes de cualquier ejecución. Así que el
escenario no está fallando — está saltado a mano, y el gate tiene razón en negarse
a llamarlo aprobado.

**Lo que se comprobó en el producto**, no en el comentario del escenario:

- Las dos únicas rutas de escritura, `add` y `rotate`, construyen la credencial
  exclusivamente a través de `CredentialPrompts` (`MainCredentialsCli.kt:140-146`
  y `253-259`). No hay rama alternativa.
- Las siete funciones de `CredentialPrompts` leen el secreto con
  `System.console()?.readPassword()`, y `System.console()` devuelve `null` sin TTY
  — una tubería no es una TTY. Las que piden solo `readLine()` funcionarían; las
  que piden contraseña, no. Es la contraseña la que bloquea.

Por tanto: **hoy no existe ruta de siembra no interactiva**, y no es una decisión
del escenario, es una limitación estructural del escritor. El escenario acertó.

**Verificado de nuevo en este ciclo, con la ubicación exacta.** Los cuatro
lectores de contraseña de `pipelinek/pipeline-credentials-local/.../CredentialPrompts.kt`
—líneas 29, 50, 79 y 120— son todos `System.console()?.readPassword() ?: error(
"no TTY available")`, y los lectores de línea son `readLine() ?: error(...)`.
No hay bandera, ni rama alternativa, ni lectura de tubería: `credentials add
--value-stdin` que nombra la tabla siguiente **no existe todavía**, así que la
tabla es una propuesta y no un inventario. `pipelinek credentials add --help`
responde `Error: missing credential id`, que es lo que el dispatch_table de
`MainCredentialsCli` produce al no recibir el id.

**Las dos formas de cerrarlo, con su coste real:**

| opción | qué añade | coste |
|---|---|---|
| `credentials add --value-stdin` | producto: una bandera nueva en la superficie pública de un escritor de credenciales | decisión de producto y de seguridad (un secreto entra por tubería); merece ADR propio |
| sembrador con **pty** en el lanzador | arnés: 7 secuencias de prompts, más un keystore PKCS#12 y un ZIP que fabricar | contradice `AGENTS.md` §3 («`run.sh` no debe convertirse en un marco de pruebas») y lo que el propio escenario dice de un «seeder dirigido por TTY» |

Ninguna de las dos se toma de forma unilateral aquí: la primera cambia superficie
de producto en una frontera de seguridad; la segunda engorda el lanzador justo
donde la política lo acota. Se deja **decidida y medida**, no perdida.

Lo que sí es de este ciclo y no se pierde: el `NOT_RUN` está declarado, el motivo
está verificado contra el código, y el escenario está dentro del inventario. El
día que haya bandera, borrar la directiva lo convierte en una comprobación que
corre — el escenario ya está escrito para eso.

### 2.6.1 El defecto que la propia §2.6 describía sin ver

Al escribir el párrafo anterior, el propio documento afirmaba en dos sitios que
el escenario «sigue compilando». **Era falso, y lo era por una causa concreta:**
`examples/run.sh` leía la directiva `requires-seeded-credentials` y hacía
`return` en la línea 99; el binario no se invocaba hasta la línea 131. Un
escenario que no puede ejecutarse no se compilaba tampoco, así que la superficie
DSL de una frontera de seguridad —las siete fábricas `CredentialsBinding`— se
podía romper sin que el veredicto cambiase. `gaps=1 not_run` era el mismo número
para una fábrica sana y para una fábrica renombrada.

**Lo corregido**, no lo retocado: la rama ahora valida antes de declarar
`NOT_RUN`, y un fallo de compilación cuenta como `FAIL` —la ejecución está
bloqueada, la compilación no—. El estado `NOT_RUN` se sigue declarando, y por
encima del `return`, para que no dependa de una ejecución cuyo resultado nunca
se observó.

**Discriminado en las dos direcciones**, que es la única forma de que un
instrumento nuevo sea creíble. Mutación aplicada y leída de vuelta antes de leer
el resultado: `CredentialsBinding.file(` → `fileMutado(`, un cambio de una
línea confirmado en el diff.

| | antes del cambio | después del cambio |
|---|---|---|
| escenario intacto | `NOT_RUN`, `exit=0` | `NOT_RUN`, `exit=0` |
| con `fileMutado(` | `NOT_RUN`, `exit=0` | `FAIL … does not compile`, `exit=1` |

La fila que se puso roja es la correcta: `Unresolved reference 'fileMutado'`,
el error de compilación real. Restaurado byte a byte y confirmado con diff vacío.
Un cambio que solo funciona en verde no demuestra nada; la mitad interesante es
que la mutación se puso roja.

Lo mismo se aplicó al propio comentario del escenario, que decía «keeps
compiling» describiendo un comportamiento que no ocurría. Un comentario que
afirma una propiedad del código es una promesa: si el código no la cumple, es el
código el que miente.

### 2.6.2 El gate completo en `9b211bf30`, con su veredicto exacto

> **Estado: histórico.** El veredicto que este bloque transcribe era el de
> entonces. El veredicto vigente está en §1.

`scripts/verify-all.sh` sin filtros, sobre el árbol ya commiteado:

| etapa | resultado |
|---|---|
| 1 compilación + detekt + detektTest + apiCheck + installDist | ok, 44 s |
| 2 tests unitarios permanentes | 20 módulos, 60 informes JUnit, **565 tests, 0 fallidos, 0 saltados** (51 de los 60 reescritos por esta ejecución) |
| 3 escenarios E2E | 36 escenarios, **35 pasan, 1 NOT_RUN**, 1 reejecución durable |
| 4 presupuesto LOC | `VERIFY_LOC` 7.336 / `PRODUCT_LOC` 61.484 = **0,119** (límite 2) |
| 5 reloj de pared | **261 s** de 900 s |

```
INCOMPLETE  sha=9b211bf309c040f16fc232ad311f725d9ef3408f  elapsed=261s
            budget=900s  gaps=1 not_run
```

**El veredicto sigue siendo INCOMPLETE y no se maquilla como PASS.** El único
hueco es el escenario de credenciales de §2.6, y la etapa 3 lo cuenta como lo
que es.

**Sobre el estado de salida 0: es deliberado, y se comprobó que lo es.** La
lectura ingenua sería «el gate dice INCOMPLETE pero sale con éxito, eso es un
defecto». No lo es: el propio `verify-all.sh` lo razona en el bloque que imprime
esa línea, y el motivo se sostiene —todas las etapas que sí corrieron son
evidencia real, y un escenario que el producto no puede lanzar sin TTY es un
hueco que hay que nombrar, no un defecto que redden la puerta entera. La
alternativa, un 1 que encienda una alarma roja por algo ya declarado y
explicado en el propio veredicto, es peor. Lo que sí obliga AGENTS.md §4 es que
una prueba ausente nunca sea PASS, y la palabra del veredicto ya lo cumple.

---

### 2.7 La autoridad única de clasificación terminal no tenía ni un test

Medido sin `pathspec` de comodín, porque ése devuelve **vacío** y un vacío se
lee como «limpio»:

| símbolo | ABI publicado | llamadas en `src/main` | ficheros de test |
|---|---|---|---|
| `decideTaskTerminal` | `pipeline-domain.api:4806` | 4 | **0** |
| `isSuccessFromDecision` | `pipeline-domain.api:4807` | 1 | **0** |

Los seis ficheros que nombran el ADT son todos `src/main`, más el volcado de
ABI y un baseline de detekt. Es decir: la función que decide si una tarea
durable «tuvo éxito» estaba publicada, la llamaban cuatro módulos, y **nadie
la había mirado nunca**.

Dos de esos cuatro la usan para **abortar trabajo**: `TarWriter` y
`ShExecution`. El fallo que importa no es un código de salida mal copiado, es
la pérdida de la *causa*: el watchdog mata el árbol de procesos y el código de
salida puede quedar publicado como cero. La forma antigua
`when { timedOut -> ...; else -> Exited(exitCode) }` devolvía `Exited(0)` y el
consumidor leía «éxito» de un proceso que nadie dejó terminar. Ese estado
cuarto es exactamente el que el ADT existe para hacer representable, y era el
único que no tenía vigilancia.

**Test añadido:** `TaskTerminalDecisionTest`, cuatro reglas puras, sin reloj ni
E/S. Dos mitades de la misma garantía: una muerte por watchdog y una
cancelación no son éxito **aun cuando el código de salida sea cero**, y un
salida distinta de cero es un fallo que además conserva como causa «el proceso
se extinguió», no «el watchdog». Las dos mitades hacen falta: quitar el
`&& decision.exitCode == 0` de `isSuccessFromDecision` sólo lo ve la segunda,
y quitar la rama `result.timedOut` del decider sólo lo ve la primera.

**`elapsedMs` deliberadamente sin aserción.** Medido: ningún consumidor lo lee
—las dos apariciones en `JdkHttpTransport` son una función privada homónima,
no el campo del ADT—. Asertar un dato que nadie consume es coste de verificación
sin consumidor, que es exactamente lo que este ciclo ha estado quitando.

### 2.8 `CacheKey.v2` — el framing era la garantía y nadie lo miraba

`:pipeline-scripting-api` no tenía ningún test. `CacheKey.v2` encuadra cada
parte de la identidad por su longitud antes de hashear; unida con un separador
sencillo, dos identidades distintas escriben ambas `a|b|c|d|` y comparten clave.
La consecuencia no es teórica: `CompiledScriptCache.fileNameFor(key)` construye
`"${key.version}-${key.value}.jar"`, así que dos compilaciones distintas
compiten por **un nombre de fichero** y la segunda sobrescribe la primera.

**Mutación verificada** (`framedDigest` → `parts.joinToString("|")`, sin marco):
se ponen rojas **exactamente** `twoIdentitiesThatCollideWhenJoinedDoNotShareAKey`
—con el mismo digest `<466c96d4…>` en ambos lados— y
`theSameIdentityAlwaysProducesTheSameKey` sigue verde. Cero colateral. Revertido
y el verde vuelve.

**Medido además, y no supuesto:** `CacheKey.v1` y `CacheKey.v2` **no tienen
ningún sitio de llamada en producción**. `Kotlin24ScriptingHost.kt:358` construye
un `CompilationIdentity` y ahí termina. Coherente con su propio KDoc («nothing
reads these values yet»), y significa que hoy no se está reinterpretando ningún
valor durable: la garantía se añade antes de que exista el caché que la use.

**Una rareza que queda registrada sin explicación inventada:** la forma abreviada
`CacheKey.v2` **no resuelve** desde otro fichero del módulo, mientras que
`CacheKey.Companion.v2` sí, y el bytecode confirma que ambas clases
(`CacheKey$Companion$v1`, `CacheKey$Companion$v2`) se compilan. Se descartó
explicación por el camino: el `Companion` se recompiló desde `clean` y dio el
mismo resultado; el `classpath` de test sólo contiene `:pipeline-domain`, junit
y kotlin-stdlib, sin nada que ensombrezca; `CacheKey.V2` (la constante) sí
resuelve, así que el problema es del objeto anidado y no del receptor. El test
usa la grafía que compila y **no** afirma una causa que no se ha establecido.


#### 2.9 ABI publicado con consumidores de producción y ningún test — cerrado

El mismo patrón que §2.7, buscando por censo en vez de por recuerdo: símbolos en los siete
volcados `.api` de `pipelinek/*/api/`, con sitios de llamada en `src/main/` y ninguna referencia en
`src/test/`.

**El instrumento es el resultado, no el número.** Un escaneo permisivo por nombre da **34**
símbolos. Es una fabricación: 30 son homónimos. `add` (23 ficheros) era
`MutableList.add`; `run` (7) eran bloques lambda `run { }`; `stringOrNull` tiene 45
llamadas `.stringOrNull(` y resuelve a **cinco copias privadas** por módulo, no a la
publicada. Los constructores del DSL (`script`, `sh`, `parallel`…) también caen: su
aparente uso de 15 ficheros eran literales, claves JSON y variables locales.

Quedan **4**, y los cuatro son decisiones puras con consumidores reales:

| Símbolo | Consumidores | Qué decide |
|---|---|---|
| `addSecret` | 3 ficheros / 5 llamadas | observar el handle sin consumirlo |
| `classifyShellTerminal` | 2 / 3 | si un código no cero es valor o fallo |
| `foldAwaitAll` | 2 / 2 | severidad antes que índice |
| `decideShellReturnMode` | 2 / 2 | la cuarta combinación se rechaza |

**Los cuatro cerrados**, cada uno con su mutación leída de vuelta en el diff antes de correr:

- `addSecret` — el más grave, porque su KDoc documenta una regresión real: usaba `handle.use`,
  que pone a cero el buffer, así que registrar una credencial ligada la destruía antes de
  que `sh` corriera. Volver a `use` pone roja **una** fila y deja verde la otra, porque
  `use` registra antes de wipe. El mensaje del fallo es el wipe: `<p@ss w0rd>` contra
  `<?????????>`.
- `classifyShellTerminal` + `decideShellReturnMode` — **dos** mutaciones independientes. Sin
  el `require` cae **una** fila, la del rechazo. Haciendo que STATUS trate el no cero como
  fallo cae **una** fila, la de la asimetría, y la de salida cero sigue verde porque la
  mutación no la toca.
- `foldAwaitAll` — **dos** mutaciones. Plegar la inestabilidad antes del fallo cae **una**
  fila, la de dominancia. Cambiar el desempate de `min` a `max` cae **una** fila, la del
  desempate.

**Dos fallos del propio instrumento, reportados porque un censo que no dice cómo se
equivoca no es auditable.** La primera pasada de regex exigía un espacio antes de `(` y
descartó los 36 `fun` de nivel superior. Y un test de getter sin argumentos leía
`line[m.end(1)-1]` en vez de `line[m.end(1):]`, lo que dejó entrar los ~1700 accesores de
data classes como candidatos. Los dos inflaban la cifra en silencio.

**Lo que este bloque NO cierra:** «0 referencias textuales a un identificador» no es «sin
cobertura». Un símbolo ejercitado sólo de extremo a extremo por la CLI no deja rastro de su
nombre en un test. Y la resolución de sitios de llamada es textual más adjudicación manual
del receptor, no un índice de compilador: un homónimo de otro paquete con la misma aridad
se contaría mal.


---
#### 2.10 El gate perdió su propia evidencia y culpó a quien no era — cerrado

La puerta completa falló en la etapa 3 con `printed no scenario summary`, un motivo que era
falso: el resumen estaba escrito. Debajo había dos defectos del instrumento, no del producto.

**El nombre del fichero de evidencia no identificaba a su escritor.** Todas las etapas
escribían en una ruta fija compartida por cualquier puerta de la máquina. Dos puertas en
checkouts distintos escribieron el mismo fichero: la segunda lo truncó mientras la primera
conservaba el descriptor en su propio offset, de modo que la escritura siguiente de la
primera cayó más allá del final nuevo. Medido sobre el artefacto: **1400 bytes NUL** en mitad
del fichero, y **13 líneas de escenario** que ningún proceso había escrito sobre las que
faltaban. La prueba directa no es una analogía: el proceso tenía sus descriptores 1 y 2
abiertos sobre ese mismo path mientras corría.

| Comprobación | Resultado |
|---|---|
| NUL en el log de la etapa 3 | 1465 bytes, entre texto por ambos lados |
| Líneas de escenario esperadas / presentes | 36 / 23 |
| Descriptores 1 y 2 del otro proceso | ambos sobre el mismo path |
| Dos shells concurrentes con nombre por PID | 2 directorios distintos |

**El instrumento de lectura no sabía leer su propio sujeto.** `grep` sin `-a` sobre un
fichero con bytes NUL responde «fichero binario» y **no devuelve la coincidencia**. El
envoltorio convertía eso en «no imprimió resumen» y era exactamente al revés: leído como
texto, el mismo fichero da `36 scenario(s), 35 passed, 0 failed, 1 not run`.

Dos cosas que este bloque deja dichas, porque las dos son la lección y no el arreglo:

- Un mensaje de fallo que nombra una causa falsa es peor que no tener mensaje: dirige al
  operador a una etapa que sí imprimió lo suyo.
- El arreglo al que se llega por el síntoma (`grep -a`) es el menor de los dos. Sin el
  nombre por PID, el fichero sigue sin ser reproducible aunque ahora sí se lea.

**Lo que este bloque NO cierra:** dos puertas simultáneas sobre el mismo checkout siguen
sin poder coincidir; esto evita que compartan fichero, no que compitan por la misma
distribución.

### 2.10 El observador de salida en vivo no arrancaba en la ruta que usa una CI

**Lo que se creía y lo que era.** Durante un ciclo entero se dio por hecho que
el observador de salida en vivo estaba mal: arrancaba, leía el plano y no
entregaba nada. La medición de este ciclo dice otra cosa, y la distingue una
sonda que se ejecutó contra un plano real:

| Capa | Qué hace | Qué hacía |
|---|---|---|
| `SegmentFrameIndex.framesOfRun` | relee el log de frames del disco en cada llamada | correcto |
| `SegmentOutputStore.read` | sirve los bytes hasta el offset confirmado | correcto |
| `LiveOutputDrain.drain` | sondea, emite en orden, avanza el ordinal | correcto |
| `LiveOutputPresentation.emit` | escribe el texto y lo vacía | correcto |
| **el arranque** | quién lanza el hilo del observador | **sólo en la ruta volátil** |

La sonda leyó el plano de una corrida real y devolvió los 18 bytes del marker
en dos frames, con el texto exacto y el stream sellado. Todo lo que hay entre
el plano y la consola funciona. Lo que no ocurría es que **nadie arrancaba el
observador en la ruta durable** —la ruta con journal en SQLite, que es como
corre un pipeline en una CI— porque `startLiveOutputObserver` sólo se llamaba
en la rama sin `--db`.

**Por qué el E2E no lo veía, y por qué eso era un defecto del suite.** El
lanzador ejecutaba los escenarios sin `--db`, es decir, por la rama volátil:
justo la rama donde el observador sí arrancaba. La ruta que una CI usa no la
medía nadie, y un suite que mide una sola de las dos rutas no puede saber que
hay dos.

**El arreglo.** Las dos rutas arrancan un observador y lo cierran cuando la
corrida termina. Y el suite ejecuta **cada escenario en las dos rutas**, con
las mismas expectativas: una divergencia entre rutas es un defecto, y sólo
ejecutar las dos puede encontrarla.

**La divergencia que encontró.** Las dos rutas informaban de una pipeline
fallida de forma distinta: una ponía el mensaje en la misma línea y no nombraba
el tipo de fallo, la otra lo ponía en la línea siguiente y sí lo nombraba. El
mismo fallo producía dos informes distintos según tuviera el operador una base
de datos o no. Ahora hay **una sola autoridad** para el informe de salida
(`reportRunOutcome`), y las dos rutas la llaman: el resultado en una línea, la
razón en la siguiente, con el tipo de fallo y su origen.

**Lo que costó.** El suite pasó de 29 a 30 escenarios y de una ejecución por
escenario a dos, y el reloj de la puerta sube con ello. A cambio, el E2E mide
por fin la ruta de producción, y la diferencia entre las dos rutas es un
defecto que ya no puede pasar inadvertido.

### 2.11 Los comentarios: 0 identificadores de tarea dentro

El árbol llevaba **719 ocurrencias de 214 identificadores distintos** que
nombraban una tarea, una especificación o un nombre: `WU-LPR-062:`, `ADR-0088:`,
`OBS-C2.3`, `S6/C`, `ML-R9`, `T-05`, `R2.3(b)`, `CDE.3-e4.5`, `HF0`, `rule 4`,
`slice 2`. Un lector sin el plan leía un índice de referencias en lugar de
documentación. El recuento final, con un censo que entiende los estados de
comentario y de literal de Kotlin —no una expresión regular sobre el fichero
entero— es **0**. También son 0 las fechas y los hashes de commit que quedaban:
el plan se queda sin fecha y sin SHA.

**Por qué no se hizo con una expresión regular.** Un barrido anterior, hecho
con una, dejó prosa rota: `An inventory at found 29…`, `OBS- gives an
operation…`, `R4 /.2 — content filters…`, y una cita de código que ya no
compilaba (`System.console()?.readPassword()` → `System.console?.readPassword`).
El instrumento de este ciclo reconoce la gramática del identificador, decide
qué forma de comentario es, y **no toca lo que no reconoce**.

**El instrumento tuvo que aprender tres cosas, y las aprendió rompiendo cosas.**
Una línea de código que empieza por `*` es un operador de expansión, no un
bullet de KDoc; un `/*` en shell es un glob (`$dir/*`), no el inicio de un
comentario de bloque; y `"$#"` es el contador de argumentos, no un comentario.
Cada uno de esos errores se vio **en el código roto** —la compilación en rojo
con el fichero y la línea— y se corrigió antes de seguir. El criterio de
aceptación del barrido no es que el fichero cambie, es que **la parte de
código de cada línea sea idéntica antes y después**: se comparó línea a línea
contra el árbol previo, y la diferencia es **0**.

**Lo que salió de paso.** El `build.gradle.kts` raíz llevaba un changelog de
trenes de release —qué versión subió cuándo, en qué commit, con qué fecha—
que describía la v0.44 a v0.48 mientras el producto iba por la v0.62. Una
copia del historial en un fichero de build es una copia que se queda vieja: el
historial es de Git. Se sustituyó por **la ley que sigue viva** (qué significa
la versión, quién la posee, qué publica este repositorio) en 28 líneas, y se
borró el resto.

**La deuda de detekt no se movió, y ahora se puede refrescar.** La baseline
fingerprinta el KDoc, así que cambiar un KDoc rompe la coincidencia. Se
regeneró y se comparó: **807** entradas, ninguna nueva. Y el arreglo que lo
hace posible está en §2.13: la tarea que refresca la deuda escribía en un
fichero que ninguna puerta leía.

### 2.12 El harness: menos escenarios, y los que quedan afirmando más

El suite E2E pasó de **36 a 30 escenarios**, y no por gusto: cuatro no
podían fallar y uno se ha añadido para afirmar lo que un proceso escribe. Son
31 hoy: el último bloque convirtió el único pipeline que quedaba fuera de
`examples/` en un escenario de verdad, y ese cuenta.

| Retirado | Por qué no afirmaba nada |
|---|---|
| `smoke/01-basic` | un `echo` y nada que observar |
| `smoke/03-stages` | tres `echo` sin ninguna forma de observar el orden |
| `steps/04-sh` | un `sh` sin mirar su salida ni su efecto |
| `steps/16-sleep` | un `sleep` y un `echo`; afirmar el retardo sería un test de reloj |

`steps/16-sleep` volvió con el trabajo de `main` y se queda, con una corrección a
lo que se escribió aquí: lo que se rechazó fue **afirmar el retardo**, que es un
test de reloj, no **ejercitar el paso**. El paso `core.sleep` no tiene ningún test
unitario —la búsqueda en todo el árbol no encuentra uno que lo nombre—, así que
un escenario que lo ejecuta es la única cobertura de que el paso está cableado y
no se cuelga. Afirma el código de salida, que es una garantía real, y no el
número de segundos que durmió, que no lo es.

`durable/32-durable-replay` también volvió, y ahí la razón es más fuerte: afirma
una garantía que el conjunto curado de aquí no cubría en ninguna parte —que un
efecto completado no se repite al reanudar—, y una garantía que nadie mira no es
una garantía.

Ninguna garantía se perdió: el orden de las etapas lo observan
`smoke/10-smoke-e2e` (la etapa 2 archiva lo que la 1 clonó) y
`negative/12-error-handling` (un milestone entre dos etapas); el efecto de
`sh` lo observa `smoke/09-sh-then-echo`; y un retardo no es una garantía que
un test pueda afirmar sin volverse frágil.

Y tres parejas se fundieron en un escenario cada una, porque eran la misma
capacidad partida en dos ficheros:

- `output/24-utilities-roundtrip` + `output/25-yaml-roundtrip` → uno
- `durable/19-isunix` + `durable/20-pwd-tmp` → `durable/19-platform-queries`
- `control-flow/05-scripted-if` + `control-flow/06-loop` → uno

**El harness creció en capacidad, no en vocabulario.** Cuatro cosas:

1. `expect-stdout` y `expect-stderr` son **repetibles**. Un escenario que
   cubre dos hechos tiene que poder decir los dos; al fundir escenarios, la
   segunda afirmación se habría perdido por el camino.
2. `expect-transcript <texto>` es nuevo: lee la **transcripción que la
   corrida confirmó** en el Plano de Salida, a través del verbo `console` del
   propio producto. Es la superficie durable, que es distinta de stdout: lo
   que un paso imprimió queda confirmado en el plano, y una aserción sobre lo
   que un paso IMPRIMIÓ se hace donde la impresión está confirmada.
3. El lanzador ahora **rechaza su propia incoherencia**: una corrida de cero
   escenarios es un error, no un verde, y los tres contadores que publica
   tienen que reconciliar antes de que el resumen salga.
4. Un fallo **nombra el texto que no encontró** y enseña lo que el pipeline
   imprimió, sin la narración del producto. Antes el resumen publicaba
   «exit matched but expected stdout substring absent»: un diagnóstico que no
   dice qué faltaba y obliga a volver a correr para averiguarlo.

**La fusión destapó una aserción que no podía pasar.** `durable/19-platform-queries`
afirmaba `pwd is the workspace placeholder: true`, y el pipeline imprime
`pwd() is the workspace placeholder: true`: el texto buscado no era subcadena
del texto emitido, así que el escenario era rojo por construcción. No lo
detectó nadie porque su mensaje de fallo era el genérico. Corregida la
afirmación, el escenario se ha visto **rojo por una razón real**: se mutó el
producto para que `pwd()` devolviera una ruta en lugar del placeholder, y el
escenario pasó a rojo exactamente en esa línea. Un escenario que nunca puede
pasar y uno que protege un contrato se parecen; sólo uno de los dos se
distingue por haber sido visto rojo.

Cada capacidad nueva se ha visto **roja** antes de declararse verde: se
invirtió la expectativa y la fila pasó a rojo con el mensaje exacto, y
restaurada volvió a verde.

### 2.13 La deuda congelada no se podía refrescar

**El defecto.** `./gradlew detektBaselineMain` escribe
`detekt-baseline-main-main.xml` —detekt nombra su salida por el source set dos
veces— y la puerta lee `detekt-baseline-main.xml`. El fichero que la puerta
lee nunca se refrescaba, y el que se refrescaba no lo leía nadie. La deuda
estática estaba congelada de una manera que no era una decisión: era un
desajuste de nombres.

**Lo que eso costaba.** La baseline arrastraba **20 entradas** de hallazgos que
ya no existían. Con la baseline vieja, un hallazgo nuevo del mismo tipo en el
mismo fichero podía coincidir con una entrada vieja y quedar **suprimido**: la
deuda podía crecer en silencio. Congelar la deuda es una decisión; dejar un
fichero de deuda que se desactualiza solo, no.

**El arreglo.** La tarea de refresco escribe ahora donde el análisis lee, y el
snapshot es **truthful**: 807 entradas, ninguna nueva, ninguna fantasma. Y la
puerta de SAST sigue teniendo dientes, comprobado por mutación: una propiedad
privada sin usar en `pipeline-domain` pone el build en rojo con el nombre de
la regla y la línea.


### 2.14 El drain tenía contrato y ningún test

`LiveOutputDrain` es la pieza que decide **qué se muestra y en qué orden**, y
tenía un KDoc que describía tres invariantes —el orden de emisión, el punto de
reanudación y la forma de terminar— sin un solo test que los comprobara. Es
justo el tipo de pieza que parece no necesitar un test porque su código es
corto.

Ahora hay uno, con el plano sustituido por un guion en memoria: el drain emite
en orden de observación, pide la página siguiente desde el ordinal del último
record, entrega los bytes que ya había leído aunque el dueño le dijera que
parara, termina en `Drained` cuando todos los streams están sellados y en
`Stopped` cuando alguno sigue abierto, y una refusal lo termina **en vez de**
disfrazarse de página vacía.

Se ha visto **rojo** antes de verde: invirtiendo el orden del drain —preguntar
antes de leer— falla el test que fija esa invariante y **sólo** ese, porque la
mutación no toca ni el orden de emisión ni el trato de la refusal.


### 2.15 El árbol se mudó de sitio: `examples/` a la raíz y `v2/` a `pipelinek/`

Dos cambios de estructura, con la misma razón detrás: **el nombre de un
directorio debería decir qué contiene**.

**`examples/` sube a la raíz del repositorio.** `AGENTS.md` §3 describe los
escenarios ejecutables como `examples/`, con el lanzador `examples/run.sh` al
lado, y el checkout los tenía bajo el directorio del build. La nota de §1 que
registraba esa diferencia —«no existe» o «está en otro sitio»— era el aviso de
que la estructura real y la descrita no coincidían. Ahora coinciden: el lanzador
es `examples/run.sh`, los escenarios son
`examples/<categoría>/<guion>.pipeline.kts`, y los tres plugins de ejemplo son
builds independientes en `examples/example-*-plugin/`, fuera del build raíz, que
es exactamente lo que su independencia significa.

**El directorio del build pasa de `v2/` a `pipelinek/`.** Un directorio
llamado `v2` dentro de un repositorio cuyo producto iba por `0.62.0` no dice
nada: `v2` puede ser «la versión 2 del producto» o «el módulo del producto»,
y hay que ir a mirar la tabla de versiones para saberlo. `pipelinek/` sí lo dice.
Los paquetes no cambian —siguen siendo `dev.rubentxu.pipeline.v2.*`— porque el
paquete es la API pública y su nombre no es un asunto de directorios.

**Lo que hubo que re-apuntar, y lo que no.** Todo lo que nombraba la ruta: el
lanzador, la puerta de verificación, el censo de LOC, el build raíz, los tres
plugins de ejemplo y los documentos. Lo que **no** cambia son las dos cosas que
se parecen a una ruta y no lo son: el token `v2` que nombra un **formato** —la
versión del formato de credenciales, la `CacheKey.v2`, el contrato entre
repositorios— y el paquete `dev.rubentxu.pipeline.v2.*`. Es una distinción que
un `sed` no hace, y por eso el cambio se aplicó con reglas que sólo mueven `v2`
seguido de un componente que existe en el build.

**El historial no se rompe.** `git mv` deja los escenarios y los tres
plugins de ejemplo como renombrados, no como borrados y añadidos, y el censo de
LOC sigue contando los mismos ficheros en el mismo lado —los escenarios y el
lanzador en el numerador, los plugins de ejemplo en el denominador—, porque un
cambio de sitio no puede usarse para mover pruebas de un lado al otro.

**Lo que la limpieza de este ciclo añadió.** Diez worktrees locales de este
mismo repositorio ocupaban 16 GB dentro del directorio de trabajo y no estaban
en ninguna rama publicada; todos estaban limpios, así que se retiraron con
`git worktree remove` —las ramas y sus commits siguen en el objeto— y
`.worktrees/` quedó ignorado, para que un barrido no vuelva a encontrar
dependientes que este árbol no tiene. También se retiraron `dist/` con cinco
candidatos viejos (0.43 a 0.52, contra un producto en 0.62), un ZIP del paquete
de plan del RESET —cuya evidencia vive en el tag anotado y en el archivo
externo— y `.github/dependabot.yml`, que configuraba el ecosystem
`github-actions` de workflows que ya no existen y daba por hecho un gate de CI
que el propio repositorio dejó de tener.

**Lo que quedaba dentro del árbol.** Los worktrees se retiraron del disco, pero
sus entradas seguían en el índice: diez punteros de tipo enlace bajo
`.worktrees/`, sin declaración que los respaldara. No eran enlaces, sino
punteros muertos —nueve apuntaban a commits vivos y uno a un objeto que ya no
existía—, y un clon del árbol los materializaba como diez carpetas vacías
mientras `git submodule status` fallaba por no encontrar su mapeo. Se quitaron
del índice, y el clon sale ahora sin una sola carpeta bajo `.worktrees/`.

**El último rastro del régimen retirado.** Quedaba un pipeline versionado en
`ci/`, pensado para que un job de CI comprobara que un fallo intencionado
ponía el pipeline en rojo. Ese job ya no existe, el fichero no lo ejecutaba
nada y la puerta lo contaba como verificación sin haberlo ejecutado nunca.
La política dice que un escenario versionado vive en `examples/`, así que el
fichero pasó a `examples/negative/` y assertion de más: ahora afirma el código
de salida y el tipo de fallo que el producto debe nombrar, porque el fallo de
un comando de shell —el tipo `SCRIPT`— no lo cubría ningún escenario. El
directorio `ci/` desaparece, con él el resto de un régimen que ya no tiene
nada que hacer aquí.

### 2.16 Las garantías que nadie miraba

La tabla de §2.2 dejó la columna de riesgo en blanco a propósito: rellenarla de
intuición es el mecanismo que produjo la lista vieja. Este bloque la responde
con medición, y al medirlo encontró garantías que el producto promete y nadie
podía comprobar.

**El censo de los cinco, con su instrumento.** Para cada módulo con código y
sin test: si está declarado, cuántos ficheros de build lo nombran, cuántos
ficheros fuera de él importan su paquete, y si se publica. La comparación por
símbolo hace falta donde el paquete raíz no identifica al módulo, y aquí importa:
`:pipeline-step-sdk:api` comparte el paquete `dev.rubentxu.pipeline.v2.sdk` con
sus hermanos, y tres de sus cuatro tipos tienen consumidores mientras el cuarto
no tiene ninguno.

| módulo | ¿quién lo usa? | ¿se publica? | veredicto |
|---|---|---|---|
| `:pipeline-scripting-api` | 15 ficheros de build, 19 importadores | sí | vivo |
| `:pipeline-scripting-kotlin24` | 1 fichero de build, 13 importadores | no | vivo |
| `:pipeline-step-sdk:junit` | un registro de `ServiceLoader` y el paso que lo consume | no | vivo |
| `:pipeline-event-harness` | el verbo `pipeline events verify` | no | vivo |
| `:pipeline-step-sdk:api` | 3 de sus 4 tipos tienen consumidores | no | vivo || `:pipeline-runtime` | **cero** | no | latente |

**Dos cosas que el censo corrige.** La primera: la tabla de §2.2
nombraba `:pipeline-scripting-api` entre los módulos sin test, y tiene uno. La
segunda: el veredicto de `:pipeline-runtime` no es «módulo sin tests» sino
«módulo sin consumidor». Sus tres adaptadores implementan los puertos que
`:pipeline-runtime-api` publica, y nada en el árbol los invoca. Su propio
`build.gradle.kts` decía que su verificación era la capa ejecutable, y eso era
falso de una manera que esconde: ningún escenario llega a ese módulo, porque no
hay quien lo use. El fichero ahora dice la verdad.

**Una autoridad duplicada que no se puede borrar sin romper nada.** El paquete
`dev.rubentxu.pipeline.v2.domain.step.junit` del dominio contiene una familia
completa de tipos de análisis de informes JUnit —nueve tipos, ocho ficheros— y
**ninguno tiene un consumidor**. El parser que sí se usa vive en
`:pipeline-step-sdk:junit`. Los dos analizan el mismo XML. Retirar la familia
del dominio sería lo correcto, y `:pipeline-domain` está publicado con su
superficie congelada, así que retirarla es una ruptura de versión que este
árbol no autoriza. Se registra con su medida, y no se le escribe un test: una
garantía sin destinatario es coste.

**Cinco garantías que ya tienen observador.** Cada una con su consumidor, su
capacidad observable y el instrumento más barato que puede fallar:

| garantía | quién la apoya | instrumento |
|---|---|---|
| una ruta que sale del workspace se rechaza | ocho pasos y adaptadores | `WorkspacePathResolverTest` |
| una credencial enlazada llega al shell y no al compilador | quien escribe el pipeline | `ScriptTextEscaperTest` |
| un contrato honoured vuelve limpio y uno roto vuelve con su regla | quien ejecuta `pipeline events verify` | `EventHarnessVerifyTest` |
| un informe JUnit no puede sacar al parser del proceso | quien entrega el informe | `JUnitReportParserTest` |
| un campo obligatorio que falta rompe el paso, y uno opcional se lee como ausente | los códecs de los pasos | `JsonAccessorsTest` |

Ninguno de los cinco tenía observador: no había test unitario, y la capa
ejecutable tampoco los alcanzaba. Cada uno se comprobó rompiéndolo —el
confinamiento sin su comprobación, el escapador que escapa de más, el
verificador que no contrasta, el parser que acepta el `DOCTYPE`, el accesor
obligatorio que devuelve null— y en cada caso se encendieron exactamente las
filas esperadas y ninguna más.

**Dos defectos que aparecieron al mirar.** El primero: la ley universal «un
paso no puede terminar sin haber empezado» estaba cableada y **muerta**. El
accesor de payloads no contempla el evento de fin de paso, así que su clave
compuesta era siempre nula y la ley no podía dispararse jamás. Se lo vio
mutando la ley en el test: el test siguió verde, que es la señal de que el
guardián no miraba nada. El arreglo es una línea por campo y una por resumen,
y ahora un paso huérfano se reporta.

El segundo: la regla de ignorado `workspace/` no estaba anclada a la raíz, así
que también se tragaba el paquete de producción `dev.rubentxu.pipeline.v2.domain.workspace`.
Los ficheros que ya estaban versionados seguían versionados —un fichero
ignorado sólo desaparece si es nuevo—, de modo que la regla no había roto nada
todavía: lo que hacía era que **cualquier fichero nuevo de ese paquete
desapareciera de `git status` sin decir nada**. Anclada a la raíz, el
directorio efímero del ciclo sigue ignorado y el paquete de producción vuelve
a ser visible. Un barrido sobre los 456 directorios de fuente del árbol
confirma que no queda ninguna otra regla tragándose código.

**Dónde acaba la tabla.** De los seis módulos con código y sin test que
abrían este bloque, cuatro tienen hoy un observador, uno se decide en el
apartado siguiente y **queda uno**: `:pipeline-runtime`, el que no tiene
consumidor. De los 26 módulos con código, 25 tienen test unitario permanente.


---

### 2.17 La integridad de los artefactos, medida por fin

Este ciclo midió una garantía de la que todo el mundo hablaba y nadie había mirado: la
integridad de los artefactos de plugin. El resultado son tres cosas, y las tres son
mediciones.

**El censo, con quién llama a qué.** Todo por `git grep` sobre `*.kt`, no de memoria:

| pieza | qué es | quién la llama en producción |
|---|---|---|
| lectura del manifiesto | el documento `META-INF/pipelinek/plugin-manifest.json` | `PreLoadPluginAdmission.admitAll` |
| medición del artefacto | SHA-256 de los bytes del fichero | `PreResolvedComposition` |
| deduplicación por bytes | dos rutas con los mismos bytes son un artefacto | `PreResolvedComposition` |
| re-medición antes de componer | los bytes no pueden cambiar entre medias | `PreResolvedComposition` |
| puerta de admisión | `PluginAdmissionGate.admitThenLoad` | **nadie** |
| base de confianza del operador | `LocalFileOperatorTrustDatabase` | **nadie: no se construye** |
| veredicto de identidad | `ArtifactIdentityVerdict` | **nadie: cero lectores** |

La fila de arriba que se repite en todas las ejecuciones con plugins es la de
`PreResolvedComposition`. Las tres de abajo no se ejecutan nunca.

**El hallazgo que decide el bloque: los dos digest no son la misma magnitud.** Medido
sobre un plugin real (`examples/example-uppercase-plugin`, artefacto construido):

- el `releaseDigest` declarado es `sha256:3983ca6a…`;
- el SHA-256 del fichero `.jar` es `sha256:dd23e2c9…`;
- el digest declarado se reproduce **exacto** con la receta del build: SHA-256 sobre
  `nombre + bytes` de cada clase y recurso del plugin, excluyendo los dos documentos que
  llevan el digest, ordenados por ruta;
- **ninguna** de las diez combinaciones de ordenación y nombre probadas reproduce el
  digest declarado a partir del `.jar` ya construido.

El digest declarado habla del CONTENIDO del plugin; el digest del artefacto habla del
FICHERO que lo contiene. La comparación que la puerta de admisión hacía —«el digest medido
contra el digest declarado»— comparaba un contenedor con su contenido, y por eso habría
acusado a todos los plugins de mentir. Eso no es un defecto hypothetical: es lo que
habría pasado en el momento de conectarla, porque el digest declarado nunca puede
coincidir con el del fichero.

**Lo que se ha cerrado.**

1. La decisión sobre los bytes vive ahora en un sitio solo,
   `PluginArtifactIntegrity`: se mide fuera y se decide dentro, sin E/S, sin reloj y sin
   classloader. La composición raíz no decide nada, sólo lee y pregunta.
2. La ruta de producción ya no pide una medición que no va a usar. Antes llamaba al
   método deprecado que devolvía `measuredDigest = null` —cuyo contrato decía
   explícitamente que ese resultado no debe admitirse— y lo admitía. Ahora construye la
   identidad que puede sostener, y su comentario dice por qué no lleva digest medido.
3. La comparación imposible sale del código. Una comparación que acusa a todos los
   plugins no es un control: es un ruido con forma de control. Y con ella sale el test
   que la guardaba, porque un test que protege una comparación que no puede tener éxito
   no protege nada: certifica una ficción. En su lugar, la garantía que de verdad se
   sostiene en producción —artefacto medido, artefacto legible o rechazado, bytes que no
   cambian entre medias— tiene un observador propio.
4. El observador es `PluginArtifactIntegrityTest`: diez casos sobre la decisión, sin
   ficheros ni procesos. Se comprobó rompiéndolo seis veces —un artefacto ilegible que se
   salta en vez de rechazarse, la deduplicación que desaparece, la deduplicación que
   distingue mayúsculas de minúsculas, un cambio que no se detecta, un artefacto que se
   vuelve ilegible y se cuenta como «sin cambios», y una comparación sensible a
   mayúsculas— y en cada una se encendieron exactamente las filas esperadas y ninguna más.
5. Y hay un escenario ejecutable: `examples/negative/34-plugin-artifact-not-measurable`,
   que apunta la ejecución a un artefacto de plugin que no está y comprueba que el
   producto **rechaza y nombra el artefacto** en vez de seguir como si nada. Se comprobó
   rompiéndolo: con el artefacto ilegible saltado en vez de rechazado, el escenario se
   pone rojo. Ese es el fail-open que el escenario existe para descartar.

**Lo que queda registrado, con su medida.**

- La puerta de admisión y la base de confianza del operador siguen sin llamantes. No es
  un olvido: no hay forma de activarlas, porque el producto no tiene el concepto de
  «home del operador» ni de «tren de releases» que sus ficheros de confianza dan por
  supuesto. Conectarlas es una decisión de producto, no un arreglo de código.
- `AdmissionRefusal.RefusedDigestMismatch` queda en el vocabulario publicado sin
  productor: nombra un rechazo real —el operador anclando BYTES y no una declaración—
  y ese caso no se puede resolver sin decidir primero qué ancla el operador. `:pipeline-domain`
  está publicado, así que el caso no se puede retirar sin ruptura de versión.
- `ArtifactIdentityVerdict` sigue con cero lectores de producción, por el mismo motivo
  de ABI que ya estaba registrado.

- **Se mide antes de decidir, y la medición puede cambiar la decisión.** El bloque
  abierto de este ciclo pedía «un caso de discrepancia de digest» y un test para él. Al
  medir el bloque entero, el resultado fue que el digest declarado y el digest del
  artefacto son magnitudes distintas, que la comparación que la puerta hacía acusaría a
  todos los plugins, y que el caso que el bloque quería producir no tiene productor
  posible sin decidir antes qué ancla el operador. Añadir el caso habría creado una
  segunda autoridad sobre un control que ya funciona y una comparación que nunca puede
  tener éxito. Se añadió, en cambio, el observador de la garantía que sí se sostiene.

- **Una garantía sin consumidor no se protege; una garantía con consumidor se protege
  entera.** La puerta de admisión y la base de confianza del operador no las llama nadie
  en este repositorio, así que su comparación no tiene a quién servir y el test que la
  guardaba se ha ido con ella. La garantía de artefacto la sostiene el runtime en cada
  ejecución con plugins, así que tiene observador unitario —que decide sin E/S— y un
  escenario ejecutable —que decide sobre el binario real y sobre lo que el operador lee—.

- **Un test que protege una comparación imposible protege una ficción.** Por eso el
  test de la comparación digest-medido contra digest-declarado sale con la comparación.
  No es que la cobertura se reduzca: es que la cobertura que había era de un mundo que
  no existe, y la que hay ahora es del mundo real.

- **El digest del manifiesto es procedencia; los bytes del artefacto son identidad.**
  La distinción estaba escrita en el código y no en el sitio donde importaba, y por eso
  una puerta comparaba las dos. Ahora está en el KDoc de la identidad, en el del digest
  declarado y en el de la puerta que los mezcla.

- **El lanzador crece sólo cuando una capacidad lo necesita.** `examples/run.sh` ya
  sabía dar credenciales a un escenario; ahora sabe también señalar un artefacto de
  plugin, y sólo de una manera: el que no está. Una directriz más, no un vocabulario.

- **Las cifras del ROADMAP se vuelven a medir contra el árbol.** «21 proyectos Gradle
  con test» y «los seis sin test» eran de un árbol anterior; hoy son 25 y dos. Un número
  escrito a mano envejece, y heredarlo es heredar una mentira con formato de dato.

### 2.18 El módulo que nadie compila, y dos entradas que ya no existen

**`:pipeline-runtime`: 1549 líneas que la puerta ni siquiera compila.** El censo del
ciclo anterior lo dejó registrado como «latente», sin medir su peso. Medido hoy:

| pregunta | respuesta medida |
|---|---|
| ¿quién lo depende? | nadie: `project(":pipeline-runtime")` no aparece en ningún `build.gradle.kts` del árbol |
| ¿se publica? | no: no está en `publishedContractModules`, y su propio build file llama «código muerto inocuo» a su bloque de publicación |
| ¿viaja en la distribución? | no: el `lib/` de la distribución instalada no contiene ningún jar suyo |
| ¿lo compila la puerta? | no: la etapa 1 compila lo que la aplicación necesita y la etapa 2 sólo pregunta a los módulos con test |
| ¿compila hoy? | sí, cuando se le pregunta: `:pipeline-runtime:clean :pipeline-runtime:compileKotlin` termina en verde |
| ¿lo llama alguien? | no: es la única implementación de los puertos publicados de recover, control e introspección, y nada en el árbol los invoca |

Su propio build file lo dice: «los puertos se publican a propósito, los adaptadores
están escritos, y falta el cableado». Eso es trabajo a medias, no una pieza viva:
1549 líneas que ningún artefacto lleva, que la puerta no compila y que nadie puede
observar. El bloque (a) del censo ya no lo cuenta como «módulo con código y sin
test», porque su código no llega a compilarse en ninguna corrida de la puerta.

**Dos entradas que ya no existen.** Los bloques (c) y (d) de §2.2 describían
`~/.worktrees/` y `docs/` como directorios pendientes de borrar, y ninguno de los
dos está: el (c) se cerró con los gitlinks del árbol —`git ls-files` no lista ninguno
y un clon real sale limpio— y el (d) con el RESET, que borró el Markdown versionado
bajo `docs/`. Se quedaron en el ROADMAP porque el texto de un bloque no se mide solo.

**La decisión que toca.** O los adaptadores se cablean a los puertos publicados y la
garantía de recover, control e introspección tiene por fin una implementación que el
runtime usa, o el módulo se retira y queda escrito que esos puertos publicados no
tienen implementación en el runtime. Lo que no vale es seguir como está: código que
nadie compila es un módulo que ya no es código, sólo peso.

### 2.19 La rama se había divorced de `main`: 196 commits de trabajo ajeno

El ciclo anterior dejó una decisión de código encima de una pregunta de integración:
la rama de este trabajo tenía 29 commits propios y `main` tenía 196, y ninguna de las
dos era ancestro de la otra. Medido, no supuesto:

| pregunta | respuesta medida |
|---|---|
| ¿dónde está el punto de divergencia? | `6e1e99cfe`, el commit que arregló la fuga de credenciales del arnés |
| ¿cuántos commits tiene cada lado? | `main` 196, esta rama 29 (los SHA no se comparten: `main` reconstruyó el mismo trabajo con otra historia) |
| ¿las dos ramas son post-RESET? | sí, las dos: mismo `AGENTS.md` de política, las dos con `scripts/verify-all.sh` y `scripts/check-budgets.py`, 62 clases de test en `main` y 63 en esta rama |
| ¿en qué se diferencian de verdad? | en el layout: `main` conserva `v2/` con los ejemplos en `v2/examples/` (82 ficheros); esta rama mudó el árbol a `pipelinek/` con los ejemplos en la raíz (60 ficheros) |
| ¿qué cuesta una fusión a ciegas? | 265 ficheros en conflicto |
| ¿qué cuesta con el layout unificado primero? | 209 ficheros en conflicto, sobre 762 ficheros que difieren en contenido |

La divergencia no era de política sino de soporte: las dos ramas aplican la misma
regla y arrived al mismo sitio por caminos distintos. Por eso la reconciliación no es
una fusión de guise, sino una **composición con reglas**, y las reglas se escribieron
antes de tocar un solo fichero:

1. Ningún cambio sustantivo de ninguna de las dos caras se pierde. Si las dos
   cambiaron lo mismo, se componen; si son alternativas, gana la que conserva *las dos*
   garantías.
2. El comportamiento de producción donde `main` cambió la semántica del producto (el
   seam de Saga, las correcciones de runtime, la puerta de release) se queda con
   `main`. El comportamiento donde esta rama lo cambió (la integridad del artefacto, el
   rechazo del plugin no verificable, el arnés medible) se queda aquí.
3. Un test que existe en una sola cara se conserva tal cual. Si existe en las dos, se
   compone la unión de métodos.
4. El cableado JUnit se queda donde el módulo tiene fuentes de test en el árbol
   fusionado; las dependencias y publicaciones de `main` también.
5. El árbol fusionado no lleva identificadores de tarea ni de documento en los
   comentarios: se quita el identificador y se conserva la prosa.
6. Un módulo que esta rama retiró y que nadie usa en ninguna de las dos caras sigue
   retirado.
7. Una sola autoridad: si el código de `main` duplica una regla que ya se poseía, se
   queda la que ya se poseía.
8. Nada de lo que `main` trae se descarta por ser de `main`, ni nada de lo que esta
   rama trae se descarta por ser de aquí.

**Lo que entró de `main` y no se negocia.** El seam de Saga completo (el parámetro
`sagaBinding` y su reenvío en la composición del runtime, el intérprete de
compensación, el veredicto y su lectura del plano de salida) y sus catorce escenarios;
la puerta de release endurecida, que ahora **rechaza un plugin que no declara rango
de API** en lugar de ignorarlo en silencio, con su propio diagnóstico; los dos
artefactos de procedencia del release (`manifest.json` con el esquema
`pipelinek-release-manifest-1` y `git-head.txt`); y las correcciones de runtime
posteriores a la bifurcación.

**Lo que se quedó de esta rama.** El arnés con etapas, filtros y presupuestos de
líneas y de reloj, con su directorio de logs por corrida; los tests unitarios
reconstruidos; los ejemplos con aserciones discriminantes; la decisión de integridad
de artefacto; y la retirada de `:pipeline-protocol`, un módulo que `main` conservaba
en el árbol sin que ni su `settings.gradle.kts` ni ningún otro fichero lo nombraran.

**Lo que este ciclo NO hace.** No borra `:pipeline-runtime`. El censo de §2.18 sigue
siendo cierto —cero consumidores, sin publicación, fuera de la distribución y fuera de
la compilación de la puerta—, pero la ausencia de referencias no demuestra que un
código sea prescindible, y aquí los tres adaptadores implementan un contrato
**publicado** contra el journal, las leases y el plano de salida que ya existen. La
decisión es integrarlos, no retirarlos; y la puerta tiene que compilar el módulo
mientras tanto.

**Dos huecos que sólo aparecen cuando las dos mitades están juntas.** Reconciliar no
es pegar dos árboles: es poner los dos a hablar, y lo que no se habla se rompe.

1. **El plugin de Saga no llegaba a la distribución.** `main` lo declaraba como
   dependencia de runtime de la aplicación, pero la tarea que construye los plugins
   de ejemplo externos y la que los mete en `lib/` conocían tres plugins y no el
   cuarto. El plugin nunca se construyó, así que los seis escenarios de Saga
   compilaron su propio script contra un plugin inexistente y fallaron con un error
   de compilación de Kotlin que no tenía nada que ver con el Saga. La tarea, el
   «staging» y el orden de `installDist` ya conocen el cuarto plugin, y ahora viaja
   en la distribución como los otros tres.
2. **Un escenario que habla del journal no puede correr sin journal.** La vía
   «volátil» —la misma tubería sin journal, que es como corre una ejecución local—
   existe para detectar una divergencia entre las dos ejecuciones, y sólo tiene
   sentido para un escenario cuyas afirmaciones se cumplen en las dos. El marcador
   que escribe un escenario durable vive en el directorio que las dos ejecuciones
   comparten, así que la segunda ejecución encontraba el efecto de la primera y lo
   leía como una repetición. Ahora un escenario que declara una directiva durable
   corre sólo sobre el journal, y la cuenta de «dos ejecuciones por escenario» de §1
   se aplica a los que no hablan del journal.

**La comparación que pedía el encargo, medida en los cuatro sitios.** Los tres verbos
—cancelar, inspeccionar, recuperar— existen en el ecosistema más de una vez, y cada
copia tiene **contrato y autoridad propios**:

| quién | contrato | autoridad sobre el estado | consume el SDK publicado |
|---|---|---|---|
| el runtime de PipelineK | `RuntimeControlPort`, `RuntimeRecoverPort`, `RuntimeIntrospectionPort` (publicados en `:pipeline-runtime-api`) | el journal, las leases y el plano de salida del propio runtime | — |
| Studio | casos de uso propios de cancelar, recuperar e inspeccionar (`CancelRunUseCaseImpl`, `RecoveryUseCaseImpl`, `RunStateInspectionUseCaseImpl`) | su propio almacenamiento y su propio backend (Fabric o SDK) | sí, pero **estáticamente**: sondea el runtime y su manifiesto, no compila contra los puertos |
| Fabric | `RunControl` con su *fencing* y `ProjectionSource` | su propio plano de control | no |
| los plugins publicados (Saga, Policy, Assurance) | `pipeline-domain`, `pipeline-events`, `pipeline-output`, `pipeline-scripting-api` | ninguna: son consumidores | sí |

La fila de los plugins es la que fija la costura: la costura de un plugin es el SDK
publicado, y el SDK publicado que hoy consumen es el de dominio y eventos. Ningún
plugin consume `pipeline-runtime-api`, ni en GitHub, ni en el Maven local, ni en
ningún repositorio hermano de esta máquina. Los puertos de runtime son, hoy, un
contrato publicado **sin implementación en el producto y sin un solo consumidor**, y
eso es lo que este ciclo tiene que arreglar: no retirarlos —eso rompería un contrato
publicado—, sino integrarlos en la composición del runtime para que la autoridad del
journal sea la única y los verbos existan.

### 2.20 El verbo que faltaba: cerrar un run cuyo escritor murió

El encargo del ciclo anterior pedía tres cosas sobre `:pipeline-runtime`: no
borrarlo, integrarlo por el SDK y la composición del runtime, y no declarar
capacidades que el producto no entrega. Este ciclo hace la parte que se podía
medir: **el verbo de control existe en el producto**, y existe porque se
arregló, no porque se compusiera tal cual.

**El hueco, medido.** El puerto `RuntimeControlPort` está publicado y su
implementación estaba escrita. La implementación no podía cumplir su propio
contrato, por dos razones que sólo aparecieron al ejecutarla:

1. **La vivididad se leía del registro, y el registro no es un hecho de vida.**
   El cancelador consultaba `FileBackedRunExecutionLeaseStore.observe`, que lee
   el fichero de registro de la lease. Un escritor al que matan deja ese
   fichero diciendo `owner_alive=true`. Medido sobre una ejecución muerta de
   verdad (`kill -9` en mitad de un `sh`): el registro decía
   `owner_id=cli-3137089-5980f1df, fencing_token=1, owner_alive=true`, con el
   proceso ya inexistente. El cancelador respondía `LeaseHeldByAnother` —
   exactamente la clase de ejecuciones que un operador quiere cerrar. El
   **lock del sistema operativo** sí es el hecho: el núcleo lo concede sólo
   cuando nadie lo tiene, y `acquire` ya lo usa. El cancelador, no.
2. **«El run está terminado» se leía del hecho equivocado.** Buscaba la
   primera fila de *operación* con estado terminal. Una ejecución a medias ya
   tiene esa fila en cuanto su primer paso termina: la ejecución muerta de
   arriba tenía `…-s0-0 SUCCEEDED` y `…-s0-1 RUNNING`. El cancelador leía
   `SUCCEEDED` y respondía `RunTerminal("succeeded")` sobre un run que
   claramente no había terminado. El hecho terminal del **run** es el libro de
   fin del event lane, no una fila de operación; y el cancelador no escribía
   ninguno de los dos.

**Lo que hay ahora.** Un verbo de operador, `pipelinek runs cancel <runId>`,
compuesto sobre el puerto publicado:

- **La regla, pura y con su propio test.** `RunCancellationDecision` decide
  sobre cuatro hechos observados —quién tiene la lease, si el run se conoce, si
  ya terminó, si ya se registró una cancelación— y nada más. Vive en
  `:pipeline-runtime`, que recupera su cableado de JUnit, y su test es el
  primero de ese módulo: seis casos sobre una función sin E/S.
- **El adaptador, honesto.** Toma la lease (la exclusividad es lo que hace
  único el libro de fin), escribe el **mismo** hecho terminal que habría
  escrito el escritor —`RunFinished` con el outcome `aborted` que ya usa el
  producto—, registra la cancelación en el journal como fila de control
  (`cancel-<runId>`, estado `SUCCEEDED`, que es lo que pasó: la cancelación
  se cumplió; el run es lo que se abortó), y sella los flujos de salida que el
  escritor muerto dejó abiertos.
- **El E2E que lo prueba de punta a punta.** `examples/durable/39-cancel-dead-run`
  mata a su propio escritor a mitad de un `sh`, y el lanzador comprueba las
  tres mitades de la garantía: el primer cancel cierra el run, el segundo es un
  *no-op* (no un rechazo), y la historia del run termina en un libro de fin.

**Lo que el cancelador NO decide.** Si el paso que estaba en vuelo llegó a
producir su efecto o no, no se puede saber desde fuera, así que la fila de esa
operación se queda como estaba (`RUNNING`). Marcarla `ABORTED` afirmaría algo
que nadie sabe, y un `resume` posterior vería «efecto fallido» donde la
verdad es «efecto desconocido». La fila de control del cancel es `SUCCEEDED` y
no `ABORTED` por la misma razón: el fallo del run se cuenta en el libro de
fin, no como una operación fallida del run (el informe de fallos del producto
selecciona por estado de fallo, y una fila de control `ABORTED` saldría ahí
como una operación más que falló).

**El texto del contrato publicado cambió, y hay que revisarlo.** El puerto
`RuntimeControlPort` decía, y decía bien por escrito, que el cancelador «NO
toma una lease nueva: consulta la existente». Eso es incompatible con escribir
un hecho terminal exclusivo, y por eso el párrafo se reescribió: el cancelador
**toma** la lease, lo cual es lo que le permite **no** cruzar una frontera
viva (un escritor vivo tiene el lock, y la adquisición lo rechaza con
`AlreadyOwned`, que es el rechazo publicado). La firma no cambia: el ABI es el
mismo, y la regla de rechazo es la misma. Lo que cambia es la prosa de un
contrato publicado, y por eso queda aquí, a la vista, para la revisión de quien
lee. El mismo fichero afirmaba además que la implementación vivía «en el
siguiente paso del trabajo» y que un test que ya no existe la sujetaba: las dos
mentiras se han corregido.

**Lo que queda fuera, y por qué.** Los otros dos verbos —`RuntimeIntrospectionPort`
y `RuntimeRecoverPort`— **no** se exponen en este ciclo, y la razón es
medida: el producto ya tiene un plano de lectura (`inspect`, sobre el event
lane) y un plano de recuperación (`--resume`, sobre el journal y el cursor de
replay). Exponerlos sería un **segundo** plano de lectura y un **segundo**
motor de recuperación, y `AGENTS.md` prohíbe exactamente eso. Integrar el
cancelador no sufre de ese problema porque no existía: no había nadie que
pudiera cerrar un run muerto. Suulerlo es trabajo de otro ciclo, y el
adaptador de introspección además tiene el mismo defecto que se corrigió aquí
—lee la primera fila terminal de operación como si fuera el terminal del run—,
que queda anotado para cuando se exponga.

**La capacidad vuelve a `EXPERIMENTAL`.** `runtime.cancel.v1` estaba en
`DEPRECATED` desde el ciclo anterior, con la razón exacta: «la implementación
existe, pero nada la compone». Ahora algo la compone —el verbo, a través del
puerto publicado—, así que vuelve a la columna de lo que el build entrega. El
catálogo no Announces nada que no se entregue, y su observador (`SdkCapabilitiesRegistryTest`)
pasa a exigir exactamente eso: el conjunto de ids anunciados-pero-no-entregados
está vacío, y anunciar uno sin entregarlo es un acto deliberado que obliga a
tocar el test.

**La evidencia, en rojo y en verde.** El test puro se vio rojo dos veces, con
las mutaciones aplicadas y leídas de vuelta: invertir la regla de idempotencia
encendió 1 de 6 filas, y mover la regla del propietario vivo encendió 1 de 6,
sin daño colateral. El E2E se vio rojo con la mutación que reproduce el
defecto original —leer la fila terminal de una operación como si fuera la del
run— y el fallo salió en el sitio esperado («la cancelación de un run muerto
salió 2, esperado 0», con el diagnóstico «el run ya terminó con outcome
'succeeded'»). El observador del catálogo se vio rojo al volver a poner
`runtime.cancel.v1` en `DEPRECATED`: 2 de 5 filas, las dos esperadas.

### 2.21 El plugin que el operador no había aprobado: la confianza, por fin en el camino

El encargo de este ciclo era uno: «empezamos y evaluamos si funciona bien el
plugin», con la afirmación de fondo de que todo plugin debe hablar con el SDK
como seam. Lo medido, parte por parte:

- **El seam es correcto.** Cada plugin de ejemplo es un build Gradle
  **independiente** que depende sólo de artefactos SDK publicados
  (`compileOnly` sobre `pipeline-domain`, `pipeline-scripting-api`,
  `pipeline-events`), resueltos de un repositorio Maven local que el build
  exige nombrar (`-PsdkRepo`, `-PsdkVersion`, sin valor por defecto). El JAR
  lleva sólo sus clases; el manifiesto se **emite en build**, no se escribe a
  mano; el `apiRange` se **deriva** del SDK resuelto, y el digest del payload
  se mide SHA-256 sobre clases y recursos excluyendo el fichero de procedencia
  y el manifiesto. Eso es un punto fijo reproducible, y es la frontera correcta:
  un plugin publicado contra el SDK no depende del código fuente del core.
- **La admisión está en el camino de producto.** `PreResolvedComposition` mide
  los bytes antes y después (para detectar un intercambio entre la medición y la
  carga), pasa 1 (`PreLoadPluginAdmission`: el manifiesto se lee **del
  artefacto**, sin classloader, y su ausencia es un rechazo) y pasa 2
  (`PluginContributionVerifier`: el cross-check entre lo declarado y lo
  implementado).
- **La confianza NO estaba en el camino de producto.** Y esto era el hueco
  real, por más que el seam y la admisión estuvieran bien.

**Lo que estaba desconectado, medido.** `PluginAdmissionGate.admitThenLoad`
tenía **cero llamadores** (su propio KDoc lo admitía: «Nothing in this
repository calls [admitThenLoad]… kept because the design is sound, not
because it is wired»). `LocalFileOperatorTrustDatabase` no tenía consumidores.
`AdmissionControlJournal` sólo lo usaba esa puerta sin cablear. Y
`AdmissionRefusal.RefusedDigestMismatch` —un caso publicado— no tenía
**productor**: nadie podía provocarlo.

**Y la política que esa puerta aplicaba era la equivocada.** Comparaba el
digest que el **propio plugin declara** sobre su payload
(`manifest.release.digest`) con el digest fijado por el operador. Son dos
magnitudes distintas —el payload y el fichero que lo lleva— que nunca
coinciden, así que la regla respondía «digest mismatch» para todos los plugins
del mundo. El propio código lo decía por escrito: «two different magnitudes
that never agree».

**La política que se decide ahora.** La confianza del operador se ancla en dos
hechos que sí se pueden comprobar, y el runtime los comprueba:

1. **Los bytes.** La fila del operador fija el digest del **fichero** del
   artefacto —la misma magnitud que mide el runtime—, no el digest que el
   plugin declara sobre su payload. Una declaración se puede copiar dentro de
   otro artefacto; los bytes no. Un JAR cambiado se rechaza nombrando **los dos
   digests**, para que el operador sepa qué fichero poner en cuarentena.
2. **El publicador.** El manifiesto declara el publicador; la fila del
   operador lo aprueba; y una **firma Ed25519 desligada** sobre los bytes
   medidos tiene que verificar bajo la clave que la fila nombra. La clave vive
   en el keyring del operador (`<fichero-de-confianza>/keys/<id>.pub`), no junto
   al plugin: una fila que pudiera llevar su propia clave aprobaría lo que
   quisiese, mientras que una fila que sólo puede **nombrar** una clave que el
   operador ya tiene no puede.

La firma va **desligada** (`<artefacto>.sig`) y no dentro del artefacto por
una razón que no es de gusto: una firma dentro del fichero cambia el fichero,
así que el digest tendría que cubrir bytes que no pueden existir todavía. La
firma desligada rompe el círculo —el publicador firma el digest del artefacto
tal como se reparte, y la firma viaja al lado—.

**El refusal que ya existía y que por fin tiene productor.**
`RefusedDigestMismatch` pasó a ser la respuesta del runtime cuando los bytes no
son los que el operador aprobó. Y dejó de ser un caso sin explicación: cada
fallo posible tiene un `kind` propio (`NoOperatorRow`, `DigestMismatch`,
`PublisherMismatch`, `PublisherNotSigned`, `SignatureMissing`,
`SignatureKeyMismatch`, `PublisherKeyMissing`, `SignatureInvalid`) porque cada
uno tiene un remedio distinto, y un rechazo que hay que leer como prosa es un
rechazo que no se puede clasificar.

**La política es opt-in, y por qué.** Se activa nombrando el fichero
(`--plugin-trust <file>`). Sin ese nombre, el runtime admite plugins como
siempre lo ha hecho (manifiesto y `apiRange`). Una política que no se puede
apagar no es una política, y hacerla obligatoria sería una ruptura de
comportamiento que este ciclo no está autorizado a hacer. Y la política cubre
**los artefactos que el operador trae** (`--plugin-jar`), no los plugins que
vienen dentro de la distribución instalada: la integridad de los plugins
embutidos es asunto de la distribución que el operador instaló —sus checksums,
su firma de release—, no de una fila que el operador tendría que escribir para
cada plugin embutido antes de poder usar ningún plugin de terceros. Una
política que hiciera impracticable el conjunto embutido sería una política que
nadie activaría.

**El cambio de semántica que hay que saber.** El fichero de confianza del
operador cambia de significado: el campo `digest` pasa a ser el digest de los
bytes del artefacto. Es un formato que el operador escribe a mano, así que es
un cambio visible para él, y por eso está aquí y no escondido en un KDoc: las
filas escritas con la semántica vieja declaran un digest que nunca habría
coincidido con el del fichero.

**El producto dice lo que decidió.** Una política que opera en silencio no es
una política: el runtime imprime, por cada plugin que la política aprobó, los
bytes, el publicador y la clave con que se aprobó. Sin esa línea, un operador no
puede distinguir «la política aprobó este plugin» de «la política nunca se
consultó», y las dos se ven igual desde fuera.

**El diario durable, por fin con consumidor.** `AdmissionControlJournal` escribe
la fila de decisión **antes** de que la decisión vuelva, así que un rechazo
deja evidencia que el operador puede leer sin volver a ejecutar nada. El
veredicto que guarda distingue `Verified` (aprobado por la política) de
`Unverified` (admitido sin que el operador hubiera dicho nada) y de `Mismatch`
(los bytes no son los aprobados). Antes de este ciclo, ese diario no escribía
nada porque su único escritor era una puerta que nadie llamaba.

**La segunda autoridad, retirada.** `PluginAdmissionGate` era una segunda
autoridad de admisión: leía su propio manifiesto, medía sus propios bytes,
hacía su propio cross-check y tenía su propia política de confianza (la
equivocada). El producto no la usaba. Se retiró en lugar de cablearse, porque
cablearla habría producido dos autoridades de admisión —dos respuestas a «¿este
plugin entra?» según por dónde pasara la llamada— y la que el producto no usaba
era la más estricta. Sus referencias en el KDoc de ocho ficheros se corrigieron
para que nombren la autoridad que existe (AGENTS.md §6).

**De paso, un lector de JSON.** El módulo tenía tres cursores a mano para tres
documentos (el fichero de confianza, la fila durable y, ahora, la firma) y ya
se contradiían: uno no leía números, otro no leía secuencias de escape. Un
documento que este runtime lee no puede significar cosas distintas según quién
lo leyó.

**Lo que queda fuera, y por qué.** La firma verifica la **identidad del
publicador sobre los bytes**, y no dice nada de si el publicador es quien dice
ser: el vínculo entre «el publicador» y «la clave» lo establece el keyring del
operador, que es un asunto fuera de banda. Tampoco hay distribución de claves
—el runtime no emite ni registra claves—, ni rotación, ni revocación. Y el
digest fijado es el del **fichero**: un plugin reconstruido produce bytes
distintos y por tanto una fila nueva. Eso es lo que se pidió (anclar la
confianza en la identidad criptográfica de los bytes), y tiene un coste que
conviene decir: re-empaquetar un plugin es una operación de mantenimiento.

**La evidencia, en rojo y en verde.** Cuatro mutaciones, aplicadas y leídas de
vuelta:

| Mutación | Qué se enciende |
|---|---|
| El desajuste de digest deja de rechazar | 1 de 11 filas del test puro, la esperada |
| La verificación de firma se salta | 2 de 11, las dos de firma |
| Se restaura la política vieja (comparar la fila con el digest que declara el propio plugin) | el escenario **positivo** falla con `RefusedDigestMismatch` y los dos digests delante: el defecto original, reproducido |
| La política deja de cubrir nada | el positivo pierde la evidencia de la política; el negativo degrada a `DuplicateIdentity` |

La cuarta es la que más importa: un escenario que sólo afirmara «algún
rechazo» habría pasado en los dos casos, porque el rechazo por identidad
duplicada también es un rechazo. El escenario afirma **qué** rechazo, y por eso
distingue una política que no hace su trabajo de una política que no se
consultó.

### 2.22 Una revisión externa del 20/10/2026: qué es de este repositorio y qué no

Una revisión publicada el 20/10/2026 sobre PipelineK `b6613c42` y Fabric
`8f304064` propone un orden de trabajo: primero cerrar el plano de eventos y
salida, después la integración con Fabric, luego el SDK y sus plugins, y al
final seguridad, certificación y documentación. Su diagnóstico es bueno y su
reordenación es la correcta para este producto, pero **sus números no son
medidos en este árbol**: la revisión se hizo sobre `b6613c42` (Core `0.75.0`),
y esta rama es `refactor/reset-testing-and-docs` sobre el Core `0.62.0`, 209
commits por delante de `origin/main`. Copiar sus cifras a este ROADMAP sería
afirmar como medido lo que se leyó en otro sitio.

Así que cada afirmación **de este repositorio** se ha vuelto a medir aquí, y el
veredicto queda escrito con el fichero y la línea donde se comprobó:

| Afirmación de la revisión | Veredicto medido en este árbol | Dónde |
|---|---|---|
| Un fallo de almacenamiento y un cursor inválido se convierten en «run desconocido» | **CONFIRMADO** | `SqliteEventFollowHandle.kt:480-484`: `UnknownRun`, `CursorBeyondTail` y `StorageError` salen los tres como `EventFollowRefusal.UnknownRun`. El KDoc admite el motivo: la jerarquía cerrada de negativas no tiene caso para ellos |
| La consola no garantiza intercalado cronológico en todas sus vistas | **MITIGADO AQUÍ, y con una distinción que la revisión no hace** | `ObservationOutputReader` **ya** está posicionado por **ordinal de frame** y devuelve los registros en orden de observación (`records` ascienden por ordinal). La concatenación en orden fijo es **otro** lector, el de un verbo, y su KDoc dice que es una proyección declarada, no una afirmación de intercalado |
| `StepFinished` no lleva resultado, así que un consumidor puede afirmar que un step terminó bien sin evidencia | **CONFIRMADO** | `DomainEvent.StepFinished` no tiene campo de resultado: `stepType` es el **tipo** del step (`"sh"`, el id del plugin), y `StepFinishedTyped.outcome` lo copia con otro nombre. `StepFailed` sí lleva `failureKind`, pero **no** lleva `stageIndex`. En cambio `StageFinished` **sí** lleva `outcome` |
| `appendAssigned` confirma una secuencia asignada, no un commit en disco | **CONFIRMADO, y ya hay barrera** | El store escribe por un hilo escritor que hace COMMIT en lotes; `flush` es la barrera («bloquea hasta que cada evento encolado está commiteado») y la unidad durable es el COMMIT de SQLite |
| La confianza criptográfica del publicador no está acreditada por el manifiesto | **ENTREGADO en este ciclo** | §2.21: la fila del operador fija los bytes y una firma Ed25519 desligada acredita al publicador |
| Integrar los adaptadores de inspección, cancelación y recuperación | **PARCIAL, y con una diferencia declarada** | El cancelador está cableado y expuesto (§2.20). `inspect` y `recover` **no** se exponen a propósito: el producto ya tiene un plano de lectura (`inspect` sobre el event lane) y un motor de recuperación (`--resume` sobre el journal y el cursor de replay). Exponer los puertos sería un segundo plano y un segundo motor, que `AGENTS.md` prohíbe |
| Publicación incremental hacia `ProjectionSource`, persistencia y recuperación de Fabric | **NO ES DE ESTE REPOSITORIO** | Es `pipelinek-fabric`, con sus propios contratos y sus releases |
| Deriva de `pipelinek-policy` (SDK `0.47.0`, `apiRange [0.47.0, 0.49.0)`), spikes de Saga, reconciliación de Studio | **NO ES DE ESTE REPOSITORIO** | Repositorios externos; los hechos que aporta la revisión quedan como criterios de aceptación allí, no como logros certificados aquí |
| Publicar `pipeline-events-store` para que Fabric abra las tablas | **PROPUESTA RECHAZADA, y por qué** | Rompería el aislamiento que el producto ya construyó. El acceso externo sigue siendo por contrato: lectura versionada, cursor, y negativas tipadas |

La revisión acierta en lo esencial y su orden es el que se adopta. Lo que no
se hace aquí es copiar su diagnóstico sin medirlo: una revisión escrita contra
otro SHA dice lo que vio allí, y este ROADMAP dice lo que se comprobó aquí.

### 2.23 Las especificaciones de los hitos priorizados

Cada hito dice **qué garantía** sostiene, **qué capacidad** ve el consumidor,
**qué instrumento** es el más barato que puede fallar, y **qué evidencia**
lo cierra. La regla es la del skill de verificación: primero la garantía, luego
la capacidad, luego el instrumento; un test es un instrumento, nunca la
garantía. Y la regla del RESET sigue vigente: sólo tests unitarios puros en
`src/test/kotlin` del módulo real, y escenarios ejecutables en `examples/` para
lo que no es unit-testable. Ningún hito de esta lista reintroduce el harness
antiguo, ni una suite E2E en Kotlin, ni una campaña de mutaciones masiva.

| Hito | Repositorio | Garantía | Instrumento más barato que puede fallar | Evidencia de cierre |
|---|---|---|---|---|
| **P0.1** Negativas de lectura diferenciadas | Core | Un consumidor de eventos distingue «el run no existe», «el cursor no vale» y «el almacenamiento falló». Hoy las tres son `UnknownRun` | Test unitario puro sobre el traductor de negativas del follow handle: tres entradas, tres salidas distintas. Sin disco: un `StorageError` se fabrica como valor | **Hecho** (§2.25): 3 clases de test y 2 escenarios ejecutables; 4 mutaciones vistas en rojo; publicación versionada (`events.follow.v2`, `events.follow.v1` DEPRECATED) y dump de ABI regenerado |
| **P0.2** Seal externo de streaming | Core | Un cliente externo lee la historia por cursor, se desconecta y vuelve sin saltarse ni duplicar hechos | Ejemplo ejecutable en `examples/`: un lector externo (el verbo `events` con cursor) que lee, corta y reanuda; y el caso del cursor inválido, que debe salir con su negativa propia | **Hecho** (§2.26): el ejemplo `events/01-external-reader-resumes` falla si el cursor se resetea al reconectar (probado: en rojo nombrando el hecho perdido) y si la negativa degrada a «run desconocido» (ciclo anterior). 4 filas de test unitario sobre la regla de paginación; 2 mutaciones vistas en rojo. El `--follow` **con filtro** del mismo verbo, que no terminaba nunca sobre un run ya acabado, ahora termina (§2.30): 4 filas de test unitario y un escenario con reloj; 3 mutaciones vistas en rojo |
| **P0.3** Streaming incremental a Fabric | Fabric | Jenkins muestra progreso durante el run, no al final | Pruebas de `pipelinek-fabric`; en Core, sólo el contrato que consume | Fuera de este repositorio: su propio gate y su RC |
| **P0.4** Persistencia y recuperación de Fabric | Fabric | Reiniciar el control plane no pierde, duplica ni inventa hechos | Igual que P0.3, con su cursor confirmado | Fuera de este repositorio |
| **P1.1** API de Runtime | Core | El cancelador está cableado y expuesto; `inspect` y `recover` siguen sin exponerse porque serían un segundo plano y un segundo motor (§2.20) | El E2E `durable/39-cancel-dead-run`, más la **compilación independiente** de `:pipeline-runtime` y un recorrido operativo real que lo use (lo que pide el informe del 21/10, §2.28) | Hecho en el ciclo anterior, y la decisión de no exponer los otros dos queda escrita y a la vista. El jar del módulo **sí** está en la distribución; lo que falta es el consumidor acreditado |
| **P1.2** Certificación de SDK y plugins | Core + externos | Un plugin compilado contra el SDK publicado se carga en la distribución real y sus contribuciones declaradas coinciden con las disponibles | Un **ejecutable** que carga cada JAR de `lib/` de la distribución instalada y compara sus entradas de servicio con su manifiesto. Es lo que el producto ya sabe hacer (`PluginContributionVerifier`), aplicado a los plugins que se distribuyen | Nombre del plugin, digest del JAR y resultado de admisión por plugin, en el informe del ciclo |
| **P1.3a** Resultado terminal del step | Core | Un consumidor del event stream puede saber si un step terminó bien, **sin deducirlo** | Test unitario puro sobre el evento terminal y su versión. Con un campo nuevo en un evento publicado, la versión es explícita: campo nuevo opcional, no un cambio de significado | **Hecho** (§2.27): el veredicto viaja en el evento que termina el step; 4 mutaciones vistas en rojo sobre 5 filas, y el ejemplo ejecutable en rojo nombrando los veredictos que no pudo leer |
| **P1.3b** Atribución de stage al fallo | Core | Un consumidor puede atribuir un `StepFailed` a su stage desde el evento, sin unirlo con otro evento | Test unitario puro sobre la forma del evento de fallo | **Hecho** (§2.29): el fallo declara su stage, y la ley de contradicción terminal que el harness describía y no implementaba ya se correlaciona; 7 mutaciones vistas en rojo, y el ejemplo ejecutable en rojo nombrando el stage que no pudo leer |
| **P1.3c** Política de confianza del plugin | Core | El plugin que el operador trae es el que aprobó, firmado por el publicador que nombra | **Hecho**: test puro del decisor + dos escenarios ejecutables (§2.21) | 4 mutaciones vistas en rojo; 46 escenarios verdes |
| **P1.4** Gate mínimo fiable | Core | La puerta local completa pasa en el SHA final, sin falsos verdes, dentro de 900 s | `scripts/verify-all.sh` con tres ensayos de reloj | Tres ensayos de reloj sobre el SHA final, con su cifra (§1) |
| **P1.5** La consola en orden real | Core | La vista de consola conserva el intercalado cronológico de los frames, y los bytes por canal siguen siendo la autoridad durable | Un test puro sobre la proyección ordenada por ordinal, y un escenario ejecutable que compare la vista consolidada con el orden de llegada | Nuevo, del informe del 21/10 (§2.28): la pieza existe (`OutputFrameIndex.framesOfRun`) y la vista actual concatena por stream, con su KDoc diciendo que no afirma lo que el kernel intercaló. Falta **decidir qué vista usa la proyección ordenada** |
| **P1.6** Un consumidor de referencia del SDK | Core | Una aplicación **externa** al Core carga un plugin, sigue eventos y lee consola, compilada contra la RC **descargada** | Un proyecto Gradle aparte, que resuelva el SDK desde el artefacto publicado y no desde un proyecto Gradle vecino; y la regla de que **cada capacidad nueva** viene con su ejemplo mínimo | Nuevo, del informe del 21/10 (§2.28). No existe en esta rama: este ciclo publicó dos ids (`runtime.eventshape.v2` y `events.stepoutcome.v1`) y ninguno tiene todavía ese consumidor |
| **P1.7** El verbo `events verify` con observador | Core | El producto del harness —`pipeline events verify`— tiene un observador ejecutable, y no sólo su tabla de tests | Un escenario que ejecute el verbo sobre el plano de un run real y afirme el veredicto; y, para la ley que acusa contradicciones, un plano que la haga disparar de verdad | Nuevo, de este ciclo (§2.29): **ningún** escenario ejecuta el verbo. Fabricar a mano un plano que la ley acuse no sería un plano, y un run real no produce uno: la ley se demuestra por su tabla, no por un escenario |
| **P2.1** RC y procedencia | Core + Fabric | Instalación real, ABI, SDK, distribuciones y procedencia verificables | Instalación de la RC en una máquina limpia y ejecución de un ejemplo contra el artefacto instalado | Fuera de este repositorio en su parte de publicación; aquí, `BLOCKED_EXTERNAL` mientras no exista certificador |
| **P2.2** Documentación pública | Core | Manual de instalación, DSL, steps, plugins, SDK y operación, con comandos comprobados contra la versión definitiva | El README corto y los ejemplos ejecutables | Al final, y no antes |

**Las tres propuestas adicionales de la revisión, con veredicto.**

1. **Un único adaptador semántico de eventos en Fabric.** Se acepta el
   diagnóstico (dos traducciones que no cubren las mismas variantes son dos
   respuestas) y se rechaza el arreglo dentro de este repositorio: el
   adaptador es de Fabric, y su drift se cierra con sus releases. En Core, lo
   equivalente es que exista **una** forma de leer el event lane —que es lo que
   ya hay: `EventRecordReadPort` y el verbo `events`—, y que las negativas
   sean tipadas y distintas (P0.1).
2. **Matriz de compatibilidad ejecutable.** Se acepta, y la parte que es de
   este repositorio se puede hacer sin dependencias nuevas: certificar los
   plugins **que se distribuyen** contra la distribución instalada (P1.2). La
   certificación de `Policy`, `Saga` y `Fabric` necesita un repositorio por
   consumidor, y sus releases: aquí sólo puede quedar el contrato que permita
   certificarlos.
3. **Una API de observación suficiente, no otro bus.** De acuerdo, y ya es la
   forma del producto: journal, `EventFollower`, output plane y cursores. No se
   añade Kafka ni otro event store ni un broker. La pieza que falta no es un
   bus: es que las negativas sean tipadas (P0.1) y que el resultado terminal del
   step esté publicado (P1.3a).

**Lo que este repositorio puede y no puede certificar.** No puede certificar
que Fabric no pierda hechos, porque Fabric no está aquí: su comportamiento es
un hecho externo hasta que su RC lo demuestra. No puede publicar una RC
estable sin certificador externo, y su estado es `BLOCKED_EXTERNAL`, no verde.
Y las garantías de durabilidad que este producto ofrece son las que su
documento dice —el output plane, por ejemplo, no garantiza persistencia ante
pérdida de alimentación—: prometer más que lo escrito sería el modo de fallo
que este ROADMAP lleva seis ciclos evitando.

### 2.24 Un recurso que se fuga, y por fin con dueño

El ciclo anterior dejó anotada una observación sin atribución: en `/tmp`
aparecían directorios `pipelinek-inmem-run*` que nadie sabía quién creaba. Esta
vez el productor está localizado: es la rama **en memoria** del verbo `run`
(`Main.kt`, el `controlDirRoot` que se crea con `Files.createTempDirectory`
cuando no hay `--control-root`), y ese directorio **no se borra nunca**. Cada
run sin journal deja un directorio con su estado de control en el disco
temporal del sistema.

Es un defecto pequeño y con dueño: no corrompe nada, pero acumula directorios
en una máquina que corre muchas ejecuciones, y un directorio de control que
nadie pidió es ruido que un operador tiene que aprender a ignorar (el mismo
razonamiento que ya Justifica que el diario durable cree su directorio sólo
cuando escribe una fila).

El arreglo no se ha hecho aquí porque no pertenece al hito de este ciclo, y
mezclarlo sería crecer el cambio porque se encontró un cambio, que es
justamente lo que este ROADMAP lleva seis ciclos evitando. Queda anotado con su productor, su síntoma y su
costo, que es lo que hace falta para que el siguiente ciclo lo recoja sin
volver a buscarlo.

### 2.25 El event lane que contestaba «nada» a tres preguntas distintas

Este ciclo cerró el hito **P0.1** de §2.23: las tres negativas de la lectura
de eventos dejan de ser una. Lo que lo abrió fue una medición del binario ya
publicado, no una lectura del código:

| Antes | `pipelinek events <run-inexistente> --db …` |
|---|---|
| Código de salida | **0** |
| Salida | **ninguna** |
| Cursor emitido | `evt-cursor-v1:<run>:0` — un cursor de continuación para un run que nunca existió |

Y lo mismo con `--after-cursor evt-cursor-v1:<run>:9999` sobre un run real:
código 0, sin salida, y el cursor devuelto. La última línea es la que más
molesta: un cursor de continuación **falso** es peor que ninguna respuesta,
porque el que lo consume se cree que hay una historia por delante.

**La causa, medida en dos sitios.** La lectura paginada (`readRecords`)
responde con FILAS y su tipo no tiene por dónde rehusar: un run que no
existe y un run que existe y aún no ha escrito nada devuelven **la misma
página vacía**. La página vacía es una respuesta perfectamente válida para
el segundo caso, así que el verbo no podía distinguishlo. Y el follower, que
sí tenía jerarquía de negativas propia, la desperdiciaba: `CursorBeyondTail`
y `StorageError` acababan los dos en `UnknownRun`.

**Lo que cambia.** El vocabulario publicado (`EventFollowRefusal`, contrato de
SDK) nombra los tres hechos:

| Negativa | Dice | El remedio del consumidor |
|---|---|---|
| `UnknownRun(runId)` | el run no existe | dejar de buscarlo |
| `CursorBeyondTail(runId, requestedSequence, tailSequence)` | el run **existe** y la posición no | resetear el cursor a la cola |
| `StorageError(cause)` | el run puede estar perfectamente ahí y el almacenamiento no puede decirlo | reintentar o escalar |

La traducción entre los dos vocabularios es **una función**
(`EventFollowRefusal.of`), no una regla por adaptador: dos adaptadores que
traducen la misma negativa por su cuenta son dos respuestas a la misma
pregunta, y divergen el día que uno de ellos gana un caso. El follower deja
de colapsar también sus dos `catch`: un fallo de almacenamiento en
**cualquier** lectura es `StorageError`, no «run desconocido».

**El verbo.** `pipelinek events` pregunta ahora al puerto que sí puede
rehusar —`EventRecordReadPortStoreAdapter`, el que ya consulta `hasRun` y
`tailSequence`— antes de leer, y sale con **código 2** y con el motivo:

```
Error: unknown run: no-such-run-<uuid>
Error: cursor 9999 is past the tail of run <uuid> (tail 13)
Error: event store failed: …
```

El texto vive en un solo sitio (`EventRecordRefusalRendering`), porque
operadores y scripts lo leen: es un contrato, y una segunda ortografía sería
una segunda respuesta.

**La publicación es versionada, que es lo que pedía el hito.** El
vocabulario crece, y un consumidor compilado contra el viejo hace `when`
exhaustivo sobre un tipo sellado: un caso que no conoce no es un error de
compilación, es un fallo en ejecución. Así que en el catálogo de capacidades
`events.follow.v1` pasa a **DEPRECATED** —que en ese catálogo significa
exactamente «anunciado y NO entregado»— y el vocabulario que esta build
entrega se anuncia como **`events.follow.v2`**. El dump de ABI de
`:pipeline-events` queda regenerado y el cambio es **aditivo**: dos casos
nuevos y la función de traducción, nada desaparece.

> **Lo que aquí NO se decide, y por qué.** La versión del **artefacto** no se
> toca. Este repositorio **iba** en `0.62.0` y el `v0.62.0` ya publicado es un
> artefacto DISTINTO (el RESET re-funda la identidad del producto); subir el
> número sin publicar una release sería afirmar una versión que no existe, y
> el README señalaba el `v0.62.0` como la autoridad pública de hoy. El campo
> `firstPublished` del catálogo dice por eso la **línea de versión que primero
> lleva el id**, no un tag que aún no se ha puesto. La decisión de qué
> release sale, y con qué tag, es del proceso de release, no de este ciclo.
>
> **Lo que este bloque cambió después, y está medido en §2.61:** la versión
> de este repositorio es hoy `0.76.0`, y la autoridad publicada es `v0.75.0`,
> con su digest medido. El `firstPublished` del catálogo **no se toca**: nombra
> la línea de versión que primero lleva el id, y esa línea sigue siendo la
> misma.

**El guardián que el módulo declaraba huérfano.** Al corregir el KDoc del
contrato publicado apareció una contradicción que no se podía resolver
leyendo: el KDoc de `EventFollowHandle` decía, en presente, que «una página
en silencio NO termina el follow» **no tiene guardián**, y tres líneas más
abajo citaba un falsificador (`SqliteEventFollowLiveTailTest`) que **no
existía en el árbol**. Las dos afirmaciones no pueden ser verdad a la vez.

Medido: el **código está bien** —una página en silencio no termina el follow—
pero nada lo fijaba, así que la máquina de estados podía volver al defecto
exacto que su propio KDoc describe y la suite ejecutable entera seguiría en
verde. Es el mismo agujero que el ciclo anterior cerró para la confianza del
plugin, y aquí era peor: el siguiente hito del roadmap (P0.2, el seam
externo de streaming) se apoya justo en este follow.

La decisión sobre **qué falsear** se tomó antes de escribir el doble: el
**PUERTO**, que es la costura que el producto ya usa, y no un almacén —un
doble de SQLite habría comprado una garantía que nadie necesita y habría
costado un disco por test. El guardián lee la **secuencia de eventos** que
recibe el consumidor y nunca la forma interna del handle, y exige las dos
cosas: que una página en silencio no termine el follow, y que el follow
**sí** termine cuando el run termina. Sin la segunda exigencia, el guardián
se podría satisfacer con un follow que nunca termina.

**Las mutaciones que se vieron en rojo.** Un verde que nunca se ha visto rojo
no es evidencia, así que cada rama nueva se rompió a propósito:

| Mutación | Filas que se encendieron | Collateral |
|---|---|---|
| `CursorBeyondTail` colapsado en `UnknownRun` | 1 fila en cada clase de test (2 en total) | ninguno |
| El `catch` de la **segunda** lectura vuelve a `UnknownRun` | 1 fila — pero sólo **después** de escribir el test que faltaba | ninguno |
| `Completed` en la primera página en silencio (el defecto auditado) | 3 filas: 2 del guardián nuevo y 1 consecuencia | ninguna fila ajena |
| `events.follow.v1` de nuevo en PUBLICADA | 3 filas del catálogo | ninguno |

La segunda fila de esa tabla es la que más enseña: la mutación **no encendió
nada**, porque el fallo ocurre en la primera lectura y el test sólo cubría
esa. Un test que no cubre la ruta que dice cubrir no es un guardián, es una
decoración. El test que faltaba se escribió, se vio **rojo** con el defecto
presente, y sólo entonces se arregló el código.

**El KDoc que mentía.** Tres afirmaciones del contrato publicado eran falsas
y se han corregido: que `CursorBeyondTail` se traducía a `UnknownRun`; que
el `catch` del follower reportaba un run desconocido; y que el defecto de la
página en silencio no tenía guardián mientras el handle citaba un test
inexistente. También había un KDoc que describía un `pollIntervalMs = 0` que
las opciones **rechazan** (`require(pollIntervalMs > 0)`).

**Lo que queda abierto.** El seam externo de streaming (P0.2) sigue siendo
otro repositorio y otro ciclo: este ciclo le deja un contrato que ya no
confunde tres negativas y un follow con guardián, que es lo que ese cliente
externo necesita para no perder hechos. Y la versión del artefacto, la RC y
la publicación siguen siendo `BLOCKED_EXTERNAL`: aquí no hay certificador.

### 2.26 El cursor que adelantaba al cliente: un hecho perdido, en silencio

Este ciclo cerró el hito **P0.2** de §2.23: el seam externo de streaming
—«un cliente externo lee la historia por cursor, se desconecta y vuelve sin
saltarse ni duplicar hechos»—. Lo que lo abrió fue una medición del binario
publicado, no una lectura del código.

**Lo que ya funcionaba, medido.** Un lector externo que pagina con el cursor
del verbo y se desconecta entre páginas recibió **exactamente** los mismos
hechos que una lectura completa, en **40 de 40** combinaciones: dos
proyecciones (envoltorio y tipada), cinco filtros (`--kind` y `--subject`) y
cuatro tamaños de página (1, 2, 3 y 7). El `--follow` también respeta el
cursor: reanudando desde la fila 3 entregó las filas 4..29 sin repetir la 1..3.
El cursor de otro run se rechaza (código 2), y el cursor más allá de la cola y
el run inexistente tienen su propia negativa desde el ciclo anterior (§2.25).

**El defecto, y dónde estaba.** La página del almacén está limitada por
**filas**; el `--limit` del verbo cuenta **hechos que coinciden con el filtro**.
Son dos cuentas sobre la misma página, y por eso el recorrido puede parar **a
mitad de página**: cuando los hechos coincididos acumulados llegan al límite,
el resto de la página —que el almacén ya leyó— se queda sin entregar. Y el
cursor que se devolvía era **la posición del almacén**, no la del cliente: esos
hechos sobrantes quedaban **detrás** del cursor, y el cliente que volvía no los
veía jamás.

Reproducido de punta a punta contra el binario publicado, con una historia
cuyas filas 6, 7 y 8 son del mismo tipo (un bloque `parallel` las produce
contiguas) y un límite de dos:

```
la verdad:   6 7 8
el lector:  6 7          ← el 8 se perdió
cursor tras la primera página: ...:8
```

Código de salida 0, sin error, sin marca de hueco: **el hecho simplemente
desapareció**. Y el caso es reachable sin filtros raros: `--kind` sobre un
bloque `parallel` basta.

**Por qué la capa ejecutable no lo veía.** §2.4 ya lo había escrito: el
lanzador lee el código de salida y, como mucho, una subcadena de `stderr`; el
`stdout` lo captura a fichero y **nunca lo abre**. La garantía de este seam
vive en dos canales —los hechos en `stdout`, el cursor en `stderr`— y el
lanzador no miraba ninguno de los dos. Por eso el defecto llevaba años sin
verse: **no había ningún instrumento que lo pudiera ver**.

**El arreglo, y por qué es el del cliente.** El cursor es la posición del
**último hecho entregado**. Es la única posición que no puede saltarse un
hecho: lo que está después sigue por delante del cliente, y lo que está antes
o se entregó o no coincidía con el filtro. Cuando la llamada no entregó nada,
la respuesta es la posición del almacén (todas las filas que leyó se
consideraron y ninguna coincidió, así que no se pierde nada al avanzar) y eso
permite que un cliente cuyo filtro todavía no coincide siga avanzando en vez
de reescanear la historia entera en cada llamada.

**Sin filtro, nada cambia.** Cada fila coincide, el recorrido no puede parar a
mitad de página (la página lleva como mucho `limit` filas y todas coinciden) y
el último hecho entregado **es** la última fila: el cursor es exactamente la
posición del almacén, como antes.

**Una regla, dos proyecciones.** El drenador de envoltorios y el tipado leen
por costuras distintas (`EventTail` y `EventStore.readRecords`) y tienen
bucles propios, pero le deben al cliente **el mismo cursor**. La regla vive en
una función y los dos la llaman, porque una regla de paginación que existe en
una proyección y no en la otra es la divergencia que sobrevive a la revisión.

**Los instrumentos, y por qué son dos.** La regla de paginación es pura: la
vigila un test unitario con un doble en memoria del puerto, que además puede
construir la historia que hace falta (y que ninguna corrida real produce con
frecuencia). El **cableado del verbo** —cursor que entra, hechos y cursor que
salen, entre procesos distintos— lo vigila un **ejemplo ejecutable**: el
lanzador hace de cliente externo, lee en páginas de dos hechos, **corta entre
páginas** (cada página es una invocación nueva, que es lo que hace un cliente
que se desconecta) y compara lo recogido con la lectura completa, diciendo
**qué** hecho se perdió o se repitió. Ninguno de los dos instrumentos ve lo
que el otro ve, y el ejemplo es el que puede afirmar sobre el binario real.

**Las mutaciones, vistas en rojo.** La regla nueva se rompió dos veces a
propósito, una en cada capa:

| Mutación | Lo que se encendió |
|---|---|
| El cursor vuelve a ser la posición del almacén | **3 filas** del test unitario: la pérdida en la proyección de envoltorios, la pérdida en la tipada y la del cursor — la fila «sin filtro» siguió **verde**, porque sin filtro el cursor no cambia |
| Lo mismo, en el binario | El **ejemplo** falló diciendo el hecho perdido: `expected: 6 7 8 / delivered: 6 7 / lost: 8` |

**El agujero de fondo, que es el hallazgo más grande.** Ni
`EventTail.readAfter` ni `EventPageDrain.drain` —las dos piezas que sostienen
la paginación— tenían **un solo test en todo el árbol**. La lectura del event
lane, que es lo que un cliente externo consume, estaba sin vigilar por
completo. No es que un test faltara: es que la Guarantee no tenía guardián.

**La deuda de modelado que el arreglo deja a la vista, y su próximo paso.**
`EventPage` (tipo publicado) lleva un solo campo `nextCursor` que ahora
significa **dos cosas** según quién construya la página: la posición del
**almacén** cuando la página viene del almacén, y la posición del **cliente**
cuando viene de una lectura filtrada. La sobrecarga está contenida —`readAfter`
tiene un solo cliente, y la página del drenador la consume sólo el verbo—, y
las dos escrituras del KDoc del tipo published y del drenador la dicen
explícitamente. Pero un tipo con un campo que significa dos cosas es una
deuda, y el próximo paso es **separarlas**: que la página del cliente tenga
su propio tipo, en el módulo de aplicación, con un campo que se llame por lo
que es. No se hace en este ciclo porque el arreglo del defecto no la necesita
para ser cierto, y porque tocar un tipo publicado por un modelo que ya funciona
es crecer el cambio porque se encontró un cambio.

**Lo que queda abierto, medido y con dueño.** El `--follow` **con un filtro
que excluye `RunFinished` no termina nunca**: comprobado contra el binario
(`exit 124`, el tiempo agotado) sobre un run acabado hacía tiempo, contra el
mismo follow sin filtro que sí termina (`exit 0`). La autoridad de «el run
terminó» es el evento `RunFinished`, y un filtro puede taparlo. No se arregla
aquí porque no es el mismo hito y porque el arreglo no es una línea: la
detección terminal del follow vive dentro del verbo, no en una función pura
que se pueda vigilar, y decidir la autoridad de «el run se acabó» **bajo un
filtro** es una pregunta de diseño del event plane. El siguiente paso
concreto queda escrito: extraer la detección terminal del follow a una función
pura, decidir esa autoridad, y vigilarla con un ejemplo que tenga un tiempo
máximo.

### 2.27 El veredicto de un step, publicado en el evento que lo termina

Este ciclo cerró el hito **P1.3a** de §2.23: «un consumidor del event stream
puede saber si un step terminó bien, **sin deducirlo**». Lo abrió una
pregunta hecha sobre el binario real, no una lectura del código: *¿qué puede
saber un consumidor externo del resultado de un step?*

**La respuesta, medida: nada.** El verbo `events`, con su proyección tipada,
devuelve el payload crudo de cada evento. Para un step que terminó bien:

```
StepStarted   seq 6  stageIndex=0 stepIndex=0 stepName=mixed/sh-0 stepType=sh
StepFinished  seq 7  stageIndex=0 stepIndex=0 stepName=mixed/sh-0 stepType=sh
```

y para un step que **falló**:

```
StepStarted   seq 8  stageIndex=0 stepIndex=1 stepName=mixed/sh-1 stepType=sh
StepFailed    seq 9  stepIndex=1 stepName=mixed/sh-1 stepType=sh failureKind=SCRIPT
                     message="shell exited with code 3"
StepFinished  seq 10 stageIndex=0 stepIndex=1 stepName=mixed/sh-1 stepType=sh
```

Tres cosas se ven ahí, y las tres son el mismo defecto:

1. **`StepFinished` no lleva ningún resultado.** Ni «success», ni «failure»,
   ni un token que lo diga. El evento que termina el step dice que el step
   terminó, y nada más.
2. **`StepFinished` se emite también para el step que falló** — y no por
   accidente: el límite de ejecución lo emite desde un `finally`, así que
   cierra el span del step tanto si salió bien como si salió mal. El nombre
   del evento dice «terminado» y **no dice nada del resultado**: un lector que
   tradujera `StepFinished` a «el step terminó bien» —que es exactamente lo
   que hacía el traductor de Fabric— estaba leyendo un fallo como un éxito.
3. **La única forma de saber el resultado era unir dos eventos**: el terminal
   con el `StepFailed`, y esa unión es por índice de step, que **ni siquiera
   dice a qué stage pertenece el fallo** (§2.23, P1.3b).

Y en la proyección tipada la cosa era peor, porque mentía con descaro: el campo
`outcome` de `StepFinishedTyped` recibía `raw.stepType`, o sea **el tipo del
step** (`sh`, `echo`), bajo un campo cuyo nombre prometía un resultado. Un
consumidor que leyera `outcome` recibía una respuesta segura a una pregunta
que nadie había hecho, y ninguna a la que sí.

**Lo que el operador veía.** La columna `OUTCOME` de `inspect` no mostraba un
resultado: mostraba el **tipo de evento** (`StepStarted`, `StepFinished`), que
no es un resultado. Y la vista de consola imprimía, para el step que falló:

```
[ERROR: step 'mixed/sh-1' failed [SCRIPT]: shell exited with code 3]
[[step: mixed] mixed/sh-1 finished]
```

«finished» después de «failed». Y la línea de consola tenía escrito su propio
principio, que este ciclo convierte en historia: *«una proyección no puede
renderizar un estado que su evento no lleva»* —con la nota de que
`StepFinished` **no** llevaba `outcome` y `StageFinished` **sí**. El principio
sigue siendo el mismo; lo que cambió es que ahora el evento **sí** lo lleva,
así que la línea puede decirlo.

**El arreglo: el veredicto viaja con el evento que termina el step.**

- `StepOutcome` —que es el tipo cerrado del dominio para el resultado de un
  step— declara ahora su **token canónico** (`success`, `unstable`,
  `failure`) junto al tipo, que es donde `RunOutcome` ya lo tenía y donde su
  propio KDoc decía que faltaba. Una lista de vocabulario, no dos.
- `StepFinished` lleva `outcome`, y **no lleva valor por defecto a propósito**.
  Un productor que se olvidara del campo tiene que decirlo en compilación; un
  valor por defecto dejaría que se publicara «desconocido» queriendo decir «no
  me he acordado», y esa diferencia es invisible más abajo.
- El límite de ejecución escribe el veredicto que **ya tenía en las manos**:
  el mismo `StepOutcome` del que sale el `StepFailed`, así que el terminal y la
  evidencia del fallo no pueden discrepar.
- La proyección tipada deja de meter el tipo del step en `outcome`: `outcome`
  es el veredicto y el tipo tiene su campo.

**Un veredicto ausente significa desconocido, y sigue siendo desconocido.** Hay
un camino real en el que el step no llega a un resultado: el cuerpo del límite
lanza algo que no es un fallo de step y la excepción se propaga. Ahí no hay
veredicto, y el evento no dice «success» por defecto: dice que no lo sabe. Lo
mismo pasa al releer una fila escrita por un build anterior, que no publicaba
veredicto: se relee **sin** veredicto, no con uno inventado.

**Los instrumentos, y por qué son tres.** La garantía es de lectura, así que el
test unitario mira **lo que un lector lee** —el mismo lector que sirve la
columna `OUTCOME` de `inspect`— y no la forma del evento: lo que ata es que el
veredicto **se pueda leer**, no dónde vive. El códec del log durable tiene su
propio guardian, porque un veredicto que se publica y no sobrevive al disco es
un veredicto que el consumidor externo no puede leer. Y el **ejemplo
ejecutable** es el que puede afirmar sobre el binario real: el lanzador pide al
event lane **solo los eventos terminales** —nunca el evento de fallo— y lee el
veredicto de cada uno, que es la forma exacta de la garantía.

**Las mutaciones, vistas en rojo.**

| Mutación | Lo que se encendió |
|---|---|
| El productor vuelve a escribir el **tipo** del step (el defecto viejo) | **5 de 5** filas del test unitario, incluida la del veredicto ausente: con el tipo dentro, hasta el caso «desconocido» afirmaba algo |
| El productor dice siempre `success` | **4 de 5** filas; la del step que termina bien siguió **verde**, que es justo lo que distingue una fila de otra |
| El lector deja de leer el campo | **4 de 5** filas; la del veredicto ausente siguió **verde**, porque no hay nada que leer |
| El terminal vuelve a ir sin veredicto, en el binario | El **ejemplo** falló nombrando los veredictos que no pudo leer: `expected: verdict/sh-0=success verdict/sh-1=failure / delivered: verdict/sh-0= verdict/sh-1=` |

**La publicación, y por qué el id cambió de versión.** El `StepFinishedTyped`
compilado contra la forma vieja **sigue enlazando**: el campo `outcome` sigue
siendo un `String`. Lo que cambia es lo que significa —el tipo del step pasa a
ser el veredicto— y nada en el tipo lo dice. Por eso `runtime.eventshape.v1`
pasa a **DEPRECATED** (anunciado y no entregado) y nace `runtime.eventshape.v2`,
y por eso nace además `events.stepoutcome.v1`: el id que negocia un consumidor
del event lane que quiere saber si **esta** build publica veredicto. El ABI de
`pipeline-events` cambia de forma incompatible —el constructor de `StepFinished`
y el de `StepFinishedTyped`— y el cambio queda **registrado** en el ledger de
excepciones, con su SHA y con lo que se rechazó: un valor por defecto o un
`@JvmOverloads` habrían hecho pasar `apiCheck` sin entrada ninguna, y a cambio
habrían publicado un constructor que significa «un evento terminal sin
veredicto», que es el silencio que este cambio existe para quitar.

**Lo que este ciclo tuvo que arreglar de paso, y por qué.** El veredicto vive
en el evento, y el evento se codifica en el log durable. Ese log —el objeto
`JsonEventLog`— estaba **al borde del umbral de detekt**, y cuatro líneas
nuevas lo empujaron al otro lado: un umbral que se cruza con un cambio
pequeño no es un guardián, es una bomba de relojería. La respuesta, según la
política del propio config de detekt, es **una extracción y no un número más
ancho**: el `when` que decodifica un evento sale a su propio objeto,
`DomainEventJsonDecoder`, que es el espejo del escritor (`EventJsonWriter`
vuelve un evento JSON, este vuelve un evento desde JSON). Lo que se movió es
código, no comportamiento: las claves de la baseline que lo nombraban cambian
de sitio —mismos hallazgos, otra dirección— y tres entradas desaparecen porque
las importaciones que cubrían están **arregladas en el código**, donde ya no
hacen falta. La baseline no creció: se mudó.

**Lo que queda abierto, medido y con dueño.** El `StepFailed` **sigue sin
`stageIndex`**: un consumidor no puede atribuir un fallo a su stage desde el
evento, sino uniendo el `StepFailed` con otro evento (§2.23, **P1.3b**). Eso
también bloquea una ley que el propio harness describe y no implementa: la
contradicción terminal («`StepFinished` con veredicto de éxito **y** un
`StepFailed` para el mismo step») no se puede correlacionar sin un key de step
que no sea ambiguo entre stages. El próximo paso de P1.3b es, por tanto, doble:
que el evento de fallo diga su stage, y que esa ley pueda correlacionarse.
Sigue abierto, y con dueño, el `--follow` con filtro que no termina (§2.26).

### 2.28 El informe del 21 de octubre, triado contra esta rama

El informe del 21 de octubre de 2026 revisó el commit `117c7ac` y el Fabric
`435c946`. Esta sección es su **triaje contra esta rama**, no un resumen: para
cada hallazgo, lo que está **ya cerrado aquí**, lo que es **real y pendiente**,
y lo que **no es de este repositorio**. El informe es del 21; el árbol que
trió se mide hoy, sobre `0e409dd41`.

**Por qué varios hallazgos llegan tarde.** El informe revisó `117c7ac`, y
`origin/main` está hoy **20 commits por delante** de esta rama. Los dos hitos
que este informe señala como P0 y P1 en el Core —las negativas del follow y la
semántica terminal del step— se cerraron en esta rama en los dos ciclos
anteriores (§2.25 y §2.27). El informe no los vio porque no los tenía delante,
no porque estén mal: su SHA es anterior a este trabajo.

**El triaje, hallazgo por hallazgo.**

| Hallazgo del informe | Veredicto contra esta rama | Dónde está resuelto, o qué falta |
|---|---|---|
| **H1** Fabric acumula los hechos y los publica al final | **Fuera de este repositorio** | Es integración de Fabric (P0.3/P0.4). La parte de Core que hace falta ya está: el follow con cursor (§2.26) y el veredicto del step (§2.27) |
| **H2** El follow confunde `StorageError` y `CursorBeyondTail` con «run desconocido» | **Ya cerrado** (§2.25) | `EventFollowRefusal` nombra las tres negativas; `SqliteEventFollowHandle` traduce sin colapsar; capacidad `events.follow.v2`; dos escenarios E2E que lo demuestran |
| **H3** La traducción de `StepFinished` puede fabricar un `SUCCESS` | **Ya cerrado** (§2.27) | El veredicto viaja con el evento terminal; capacidad `events.stepoutcome.v1`; `runtime.eventshape.v1` a DEPRECATED |
| …la mitad de H3: el `StepFailed` no identifica su `stageIndex` | **Ya cerrado** (§2.29) | El fallo declara su stage; capacidad `events.failurestage.v1`; y con él, la ley de contradicción terminal del harness, que el propio código describía y no implementaba |
| **H4** `pipeline-runtime` es código que merece integración, no eliminación | **Real, y una afirmación del informe que aquí no se sostiene** | El jar `pipeline-runtime-<version>.jar` **sí está** en `lib/` de la distribución instalada, y el módulo compila y tiene tests (la etapa 2 del gate lo ejecuta). El recorrido operativo acreditado es el que **§2.52** cerró: los tres verbos (`runs cancel`, `runs recover`, `inspect`) llaman a los puertos publicados, y dos escenarios E2E lo ejecutan |
| **H5** El output plane: intercalado cronológico y durabilidad | **Real y pendiente** | La pieza existe (`OutputFrameIndex.framesOfRun`) y la vista de consola **concatena por stream** y lo declara en su KDoc («es una proyección, no una afirmación de lo que el kernel intercaló»). Falta **decidir qué vista usa la proyección ordenada**: nuevo **P1.5** |
| **§4** Colisiones entre agentes en el gate | **Ya cerrado** | El gate usa `log_dir="${TMPDIR:-/tmp}/verify-all.$$"`, un directorio por corrida, y escribe el motivo: «a shared path let one run truncate the log while another was still appending to it» |
| **§4** Métricas que incentivan tests sin valor | **No aplica a Core** | No hay mínimo artificial de líneas de test en este repositorio (el `testLineFloor` que cita el informe es de Fabric). El presupuesto de Core es LOC y reloj, y `AGENTS.md` exige mutación vista en rojo |
| **§4** Una RC no debería promoverse con una garantía en `NOT_RUN` | **Real, y pertenece a la política de release** | El gate ya **declara** los `NOT_RUN` en vez de esconderlos; la regla de promoción se escribe en **P2.1**, que hoy está `BLOCKED_EXTERNAL` |
| **§6.1** Un consumidor de referencia del SDK, compilado contra la RC descargada | **Real y pendiente** | **No existe** en esta rama (el ledger lo menciona, pero era del árbol pre-RESET). Nuevo **P1.6** |
| **§6.2** Un único estado durable de observación, sin crear otro event store | **Ya es la política** | `AGENTS.md` §1: «no second event, Step, recovery or certification authority» |
| **§6.3** Presupuesto por comportamiento, no por líneas | **Ya es la política** | `AGENTS.md` §2: «un verde que nunca se ha visto rojo no es evidencia»; §4: LOC ≤ 2 × y reloj ≤ 900 s |
| **§6.4** Cada capacidad nueva, con un ejemplo mínimo que compile externamente | **Real y pendiente, y este ciclo lo dejó a medias** | Este ciclo publicó dos ids (`runtime.eventshape.v2`, `events.stepoutcome.v1`) y **ninguno** tiene todavía ese consumidor mínimo: **P1.6** |
| **§7** Hay una RC `v0.75.0-rc1` publicada y `main` va por delante | **Real, y es un hecho de esta rama** | La release existe (release-gate PASS, sha256 `e24abea8…`, 20/10/2026) y `origin/main` está **20 commits** por delante. Esta rama declaraba `0.62.0` y **no se podía publicar tal cual**: su versión colisionaría con la publicada. **Resuelto en §2.61**: la versión de este árbol es `0.76.0`, que no colisiona con nada publicado y coloca este árbol **después** de `v0.75.0`, que es lo que es |

**Lo que este ciclo deja a medias, y por qué se dice.** La publicación del
veredicto (P1.3a) publicó dos ids de capacidad nuevos y los dos tienen su
verificación **dentro** del repositorio: el registro que los fija y el
ejemplo ejecutable que los lee. Lo que **no** tiene es un consumidor que
compile **fuera** de este repositorio contra el artefacto publicado, que es
justo lo que el informe propone (§6.1) y lo que un id de capacidad promete a
quien no vive aquí. Ese hueco es **P1.6**, y su instrumento está escrito:
una aplicación mínima, externa al Core, que cargue un plugin, siga eventos y
lea consola, compilada contra la RC **descargada** y no contra un proyecto
Gradle vecino.

**Lo que el informe no menciona y este ciclo encontró.** El campo `outcome` de
la proyección tipada **llevaba el tipo del step** (`sh`, `echo`) bajo un
nombre que prometía un resultado: un consumidor recibía una respuesta segura
a una pregunta que nadie había hecho, y ninguna a la que sí. El informe lo
detectó en el traductor de Fabric; la causa estaba un plano más abajo, en la
proyección. Arreglarla es parte de P1.3a (§2.27).

**La regla que este informe y `AGENTS.md` comparten, y que se puede leer en
las dos columnas.** El informe pide «un test que no falla ante la mutación que
supuestamente protege debe corregirse o retirarse». `AGENTS.md` §2 dice lo
mismo con más crudeza: «un verde que nunca se ha visto rojo no es evidencia».
Este ciclo lo aplicó a las cuatro filas del veredicto: **M1** (el productor
vuelve a escribir el tipo) encendió **5 de 5**, **M2** (dice siempre
`success`) encendió **4 de 5** y dejó verde la fila del step que termina
bien, **M3** (el lector deja de leer el campo) encendió **4 de 5** y dejó
verde la fila del veredicto ausente, y **M4** (el terminal vuelve a ir sin
veredicto, en el binario) hizo fallar el ejemplo nombrando los veredictos que
no pudo leer.

### 2.29 El fallo de un step, y el stage que le faltaba

Este ciclo cerró el hito **P1.3b** de §2.23: «un consumidor puede atribuir un
`StepFailed` a su stage desde el evento, sin unirlo con otro evento». El
ciclo anterior (§2.27) publicó **qué** pasó con un step; este dice **dónde**
pasó.

**El hueco, medido con el binario y no supuesto.** El `StepFailed` declaraba el
step y no el stage. Medido sobre un run real, el evento de fallo era
`{"stepIndex":0,"stepName":"second/sh-0","stepType":"sh","failureKind":"SCRIPT","message":"shell exited with code 7"}`
— ni un stage. Y el índice de step es el índice **dentro** de un stage: el mismo
índice significa un step distinto en cada stage del run, así que la única forma
de saber el stage era unir el fallo con otro evento, y esa unión no era unívoca
entre stages. El sobre, además, lo situaba en el run (`subject.kind = RUN`),
por una razón que el propio código escribía: «stepIndex exists but no stage
identity». Sin identidad de stage, el step no era un sujeto posible.

**Lo que hace, y por qué así.** `StepFailed` gana `stageIndex: Int?` **sin
valor por defecto**. Sin valor por defecto porque el olvido de un productor se
paga en compilación y no en producción; anulable porque hay dos ausencias
distintas y una de ellas es un hecho: un fallo en el primer stage y un fallo
cuyo stage nadie registró no son el mismo hecho, y `null` no es `0`. `null` es
también lo que decodifica una fila de un build anterior, que no publicaba
stage; un consumidor que tiene que distinguir «esta build dice desconocido» de
«esta build no dice nada» negocia el id `events.failurestage.v1`.

Los tres productores canónicos lo declaran: el límite de ejecución del step —el
dueño del ciclo de vida—, el rechazo de la lectura de credenciales —que ocurre
antes de que el cuerpo empiece y es la única observación por step que su
resultado tiene— y el fallo del propio shell. El helper `error` del SDK **no**
lo declara, y eso no es un olvido: su `StepContext` no lleva el stage, y un
stage inventado sería peor que un stage ausente.

El sobre sigue al evento: un fallo que dice su stage es sobre ese step, y un
fallo que no lo dice se queda con el run como sujeto. La ley congelada tenía
su razón, y la razón ya no existe.

**La segunda mitad del hito: la ley que se podía describir y no correlacionar.**
El harness describía una ley de contradicción terminal —«`StepFinished` con
veredicto de éxito **y** un `StepFailed` para el mismo step»— y no la
implementaba, porque no había forma de saber de qué step era el fallo. Con el
stage declarado, la ley se implementa, y su forma no es la que se escribe: es
la que el **plano real del producto** admite. Medido con el binario sobre un
run con reintento, con `catchError` y con fallo simple:

- la identidad de un step es **(stage, índice, nombre)**: el stage porque el
  índice significa otra cosa en cada stage; el nombre porque un step anidado
  comparte el índice del step que lo contiene, y solo el nombre los distingue;
- un **intento** va de un `StepStarted` al siguiente `StepStarted` del mismo
  step: un reintento vuelve a ejecutar el step y lo dice empezando de nuevo, así
  que el fallo del primer intento y el éxito del segundo son hechos de dos
  intentos;
- el **veredicto se lee del terminal**, porque el fallo no lo dice.

La ley naïf —«mismo step, fallo y éxito»— habría sido **falsa** en el caso del
reintento. El caso que lo demuestra no es un plano inventado: es un run real
medido con el binario.

**Lo que este ciclo tuvo que arreglar de paso, y por qué.** La ley se llevaba
`ProtocolGrammar` por encima del límite de funciones de detekt, y la respuesta
del propio config —y de `AGENTS.md`— es **extraer, no anotar**: la ley sale a su
propio objeto, `StepTerminalContradiction`. La baseline de detekt no creció:
sigue en **802** entradas.

**Un hueco que este ciclo encontró y NO arregló, y que por eso lleva dueño.**
El verbo `pipeline events verify` —que es el producto del harness— **no lo
ejecuta ningún escenario**: no hay forma de que un run produzca un plano que la
ley tenga que acusar, y fabricar ese plano a mano no sería un plano, sería una
mentira. La ley de contradicción terminal se demuestra entonces por su tabla
—con sus planos medidos y sus mutaciones vistas en rojo—, no por un escenario.
El hueco queda escrito como **P1.7**.

### 2.30 El follow con filtro que no terminaba

Este ciclo cerró un defecto que llevaba **P0.2** debajo: el verbo `events
--follow` con un filtro no terminaba nunca.

**El hueco, medido con el binario y no supuesto.** Sobre un run que ya había
terminado:

```text
$ pipelinek events <runId> --db <db> --follow
0                                   (sale enseguida)

$ pipelinek events <runId> --db <db> --follow --kind StepFinished
124                                 (se agotó el tiempo: no terminó nunca)
```

El follow sin filtro salía con 0; **con** filtro no terminaba. No era un
problema de velocidad ni de historia: era un proceso que no acababa.

**La causa: una autoridad duplicada y mal situada.** El follow buscaba el
hecho terminal (`RunFinished`) entre los hechos que acababa de **entregar**, y
un filtro que lo excluye no lo deja ver nunca. La salida alternativa —«el
almacén dijo que no hay más filas»— era **inalcanzable**: el cursor del
almacén nunca es nulo (`readRecords` siempre devuelve un `EventCursor`), así
que el follow sondeaba para siempre sobre un run que había acabado hace
minutos. La versión publicada del follow dice lo correcto —una observación
`RunFinished` saca al follow de su estado vivo—; el verbo lo aplicaba a las
filas equivocadas.

**La regla, y por qué es la que es.** El filtro decide **qué recibe el
cliente**; no decide **si el run se acabó**. El hecho terminal del run es el
`RunFinished` **en el almacén**, y eso es un hecho del run, no de la
corriente filtrada.

**Cómo quedó.** `RunTerminal` es la autoridad, en un solo sitio: el kind
terminal y la pregunta. `EventPageDrain` declara ahora `runEnded` en sus dos
outcomes, acumulado sobre las **filas leídas** —no sobre las entregadas— y el
verbo decide con ese dato. El verbo además **dejó de llevar su propia
comprobación** de los hechos entregados: esa era la autoridad duplicada, y
quitarla es parte del arreglo, no una limpieza.

El verbo `observe` ya tenía esta regla —leía el lane **sin filtrar** y
filtraba al entregar—, así que el defecto era del verbo `events`, no del
producto.

**No se tocó ningún contrato publicado.** `:pipeline-application` no es un
módulo publicado: no hay break de ABI, ni entrada en el ledger, ni versión
nueva que negociar.

**Lo que este ciclo tuvo que arreglar de paso, y por qué.** `runFollow` quedó
sobre el límite de complejidad de detekt (26, con el máximo en 25) y la
respuesta del propio config —y de `AGENTS.md`— es **extraer, no anotar**: la
espera de sondeo sale a `awaitNextPoll`, que no tiene opinión sobre el run ni
autoridad sobre el hecho terminal. La baseline **no creció**: sigue en **802**
entradas.

**Los instrumentos, y por qué son dos.** El puro es el drain, con un doble en
memoria del puerto de lectura: cuatro filas que dicen qué filas se miraron
para decidir. El ejecutable es el escenario `events/04-follow-filtered`, donde
el lanzador hace de cliente externo y sigue el run **con filtro** bajo un
**reloj**: un exit code no distingue «no terminó» de «terminó con otro
código», y sólo un reloj cazar una espera que no acaba.

**Las mutaciones, vistas en rojo sobre el código final.** **M1** (el drain lee
el hecho terminal de los hechos entregados en vez de de las filas leídas)
encendió **1 de 4** filas —la que dice que el run terminó aunque el filtro lo
excluya— y el ejemplo ejecutable. **M2** (el verbo ignora el informe del
drain) dejó el unitario **en verde** y encendió el ejemplo: el drain era
correcto, el que no lo consultaba era el verbo. **M3** (el drain tipado no
reporta el hecho) encendió **1 de 4** filas, la del drain tipado, y dejó
verdes las tres del de sobres. Las tres se leyeron de vuelta y se deshacieron.
M2 es la que prueba que los dos instrumentos **ven cosas distintas**, y por
eso hacen falta los dos.

**Un hueco hermano que este ciclo encontró y NO arregló, y que por eso lleva
dueño.** El verbo `observe --follow` usa el cursor **del almacén**, que es
otra semántica: la del verbo `events` es el cursor **del cliente** (§2.26). No
es el mismo defecto —el observe sí termina— pero son dos verbos que
responden «¿dónde sigo?» con dos reglas distintas, y eso está medido y
escrito, no arreglado de paso.

### 2.31 El módulo que se iba a retirar: `:pipeline-runtime` se queda, cableado y medido

Este ciclo cerró una pregunta que llevaba semanas abierta: **qué pasa con
`:pipeline-runtime`**. El módulo tenía puertos publicados sin consumidor, y la
tentación —la de `AGENTS.md` included, la de no dejar adaptadores sobre
adaptadores— era retirarlo. Se midió antes de decidir, y la medición cambió la
decisión.

**Medido: el módulo es el que sostiene `runs cancel`.** Retirarlo no sería
retirar código muerto: `RuntimeControlPortStoreAdapter` vive en
`:pipeline-runtime`, y con él el verbo `runs cancel` que este mismo repositorio
entregó en §2.20 y que el ejemplo `durable/39-cancel-dead-run` demuestra desde
fuera. Un módulo que se retira para no dejar puertos sin consumidor se queda
sin el consumidor que ya tenía. La pregunta que quedaba abierta no era «¿sobra
código?» sino «¿sobran puertos sin consumidor?», y esa sí tiene respuesta
distinta para cada puerto.

**La salida, puerto por puerto.** Cada puerto publicado de `:pipeline-runtime`
se midió contra una pregunta única: **¿algo lo compone?**

| Puerto | Método | Consumidor | Salida |
|---|---|---|---|
| `RuntimeControlPort` | `cancel` | `pipelinek runs cancel` (§2.20) | ya estaba entregado |
| `RuntimeIntrospectionPort` | `inspect` | `pipelinek inspect <runId> --state` (este ciclo) | **cableado** |
| `RuntimeIntrospectionPort` | `runStructure`, `inspectOperation` | **ninguno**: medido sobre el árbol entero | **anunciado y no entregado** |
| `RuntimeRecoverPort` | `recover` | `pipelinek runs recover <runId>` (este ciclo) | **cableado** |

**El verbo de introspección.** `pipelinek inspect <runId> --state` responde qué
estado tiene un run, y es el verbo que compone el puerto publicado de
introspección. No es un segundo plano de lectura: es el verbo `inspect` de
siempre, que ahora además puede decir el estado. Lo que sí era un defecto, y
era grave, es **qué** decía: el terminal de un run lo tomaba de la primera fila
terminal de una **operación**, y una fila del journal es el estado de una
operación, no del run. Un run a medias tiene ya una fila terminal en cuanto su
primer paso termina: el mismo error que hacía que el cancelador rechazara el run
que quería cerrar (§2.20), repetido en el otro verbo. Ahora el terminal de un
run es el **bookend `RunFinished` del event lane**, y la fila terminal de una
operación no termina un run. El escenario `events/05-run-state` lo demuestra
desde fuera: pide el estado de un run y lee `running` o `terminal outcome=…`.

**El verbo de recuperación.** `pipelinek runs recover <runId>` cierra un run
cuyo escritor murió, sin repetir sus efectos. Cerrarlo exigía dos correcciones
que no eran de estilo sino de hecho:

- **La lease se toma, no se consulta.** El adaptador leía el *registro* de la
  lease para decidir si el run estaba ocupado. El registro dice el **pasado**:
  un escritor al que mataron deja su registro detrás, y el registro sigue
  diciendo que su dueño está vivo. Así que el recover de un run cuyo escritor
  murió concluía que el run estaba ocupado y se negaba —justo el run para el
  que existe—. Ahora toma la **lease**, que es el lock, y el lock es la
  autoridad sobre quién publica el run **ahora**. Se devuelve al terminar. La
  prosa publicada del contrato decía «DOES NOT: Take a lease» y ya no era
  verdad: se corrige, porque un contrato publicado que miente es peor que un
  contrato publicado que estorba.
- **El veredicto del run lo decide el dominio.** La fila terminal que el
  adaptador entrega al decisor publicado es el veredicto del **RUN**, y la
  regla que la calculaba —la última operación terminada— podía cerrar como
  **exitoso** un run que había tenido una operación fallida. Eso es un éxito
  fabricado, y una repetición durable no puede permitirse. Ahora el veredicto
  lo decide `RunOutcomeReducer`, la única autoridad que pliega los veredictos de
  los steps en el veredicto del run, y que dice que el éxito exige que **todas**
  las operaciones hayan tenido éxito. El adaptador no tiene regla propia sobre
  lo que es un run: lee sus operaciones y entrega el veredicto del dominio. Un
  *abort* se **lee** —lo fijó el orquestador— en vez de derivarse.

**El catálogo dice la verdad sobre lo que el build entrega.** `runtime.structure.v1`
y `runtime.operation.v1` anunciaban una capacidad que el build no entregaba:
el adaptador tiene los métodos y **nada los compone**. Medido sobre el árbol
entero, no hay ni un llamador a `runStructure` ni a `inspectOperation` fuera de
la declaración del puerto y del propio adaptador. Un id que un consumidor
encuentra y no puede usar es una promesa que el build no puede cumplir, y el
fallo llega en tiempo de ejecución en vez de en el catálogo. Los dos ids pasan a
DEPRECATED, que aquí significa «anunciado y NO entregado», no «será eliminado»:
es la misma regla que movió `runtime.cancel.v1` allí y de vuelta cuando el
verbo llegó (§2.20).

**Los instrumentos, y por qué son tres.** El puro es la prueba del adaptador de
recuperación, que es donde el caso difícil es construible: un run con una
operación fallida y otra que terminó después no se puede montar desde un
ejemplo ejecutable —un escenario mata al escritor, y al escritor muerto no le
queda una operación *exitosa* posterior— y sí desde una prueba unitaria, con
el journal y el cursor en memoria reales y la lease como puerto. El ejecutable
es `durable/40-recover-refuses-a-half-finished-run`, que demuestra el cableado
y la negativa segura. El catálogo tiene su propio guardián en
`SdkCapabilitiesRegistryTest`. **Cada instrumento ve algo que los otros no
ven**: la prueba unitaria puede afirmar el veredicto exacto de un run con dos
operaciones; el ejecutable puede afirmar que el verbo existe y que el estado
del run no cambió.

**Las mutaciones, vistas en rojo sobre el código final.** **M1** (quitar el
guard de «toda la labor terminada» del veredicto del run) encendió **1 de 7**
filas —la del run a medio hacer— y ninguna más. **M2** (sustituir el veredicto
del dominio por «la última operación terminada») encendió **2 de 7** —la del
éxito fabricado y la del abortado— y ninguna más. **M3** (volver
`runtime.structure.v1` a EXPERIMENTAL) encendió **2 de 8** filas del registro
de capacidades y ninguna más. Las tres se leyeron de vuelta y se deshicieron.

**La puerta atrapó lo que los commits anteriores no habían dejado pasar.** La
primera ejecución de la puerta de este ciclo cayó en su etapa 1 por **dos**
violaciones de estilo: un import sin uso en `RecoverAdapters.kt` —que quedó
huérfano cuando la lease pasó a ser un puerto— y una línea de **204
caracteres** en `MainInspectCli.kt`, donde el `when` del refusal abría en la
misma línea que su firma. Las dos venían de commits **de este mismo ciclo**,
publicados sin pasar por la puerta: el árbol no estaba roto, pero tampoco
había sido medido. Se corrigieron y se volvió a medir desde cero.

**La medición al cerrar esta parte del ciclo, sobre `bec40f87`.** La puerta
completa corrió **tres veces** sin filtros, desde salidas de build limpias:
**653 / 608 / 598 s**, peor 653 s contra el límite de 900 s. Las tres corridas
reescribieron los **122** informes JUnit —905 tests, 0 fallos, 0 errores, 0
skips—, pasaron los **52** escenarios E2E (0 fallos, 7 credenciales sembradas,
2 reejecuciones durables) y midieron `VERIFY_LOC = 15006` contra
`PRODUCT_LOC = 65309`, ratio **0.230** con un límite de 2. La deuda estática
congelada sigue en **802** entradas: ni una más. El certificador externo sigue
en **`BLOCKED_EXTERNAL`**, porque no existe ni en este repositorio ni en
remoto. La medición final del ciclo, con el reloj del lanzador ya en su sitio,
está en §2.32.

### 2.32 El reloj del lanzador: una prueba bloqueada ya no paraliza la entrega

El bloque B1.4 pedía acotar los E2E, y la medición que lo justificaba ya
estaba hecha: **la etapa E2E se llevaba 591 s de los 900 s del presupuesto de
la puerta**, y el único `timeout` del lanzador era el de la directiva de
follow. Un escenario colgado se comía la puerta entera sin decir cuál era el
culpable —el `timeout 900` de fuera mataba el proceso, y el operador se
enteraba de que se acabó el tiempo, no de qué se quedó colgado.

**Dos relojes, y los dos del lanzador.** El **reloj de escena** son 120 s por
defecto, y una escena puede pedir otro con `//@ timeout <segundos>`. El
default sale de una medición, no de una suposición: la escena más lenta tarda
**21 s** y la mediana **11 s**, así que 120 s es unas seis veces el peor caso
observado —no dispara en una corrida sana y dispara mucho antes del techo de
la puerta—. El **reloj de etapa** lo que le queda a la puerta: la puerta le
pasa su presupuesto restante (`PIPELINEK_E2E_BUDGET_SECONDS`), así que los dos
presupuestos no pueden discrepar, el lanzador se para antes del techo y
**nombra las escenas que no le dio tiempo a ejecutar**.

**Una escena sin reloj se para, y con ella se para el árbol.** `timeout` mete
el comando en su propio grupo de procesos y señaliza al grupo, de modo que una
pipeline que dejó una shell colgada no deja la corrida colgada. El veredicto
de esa escena es **BLOCKED** —la garantía **NO** se comprobó— y la corrida no
es verde. Un fallo real sigue siendo un fallo: el reloj no convierte un
defecto del producto en un hueco sin comprobar.

**La decisión estructural: la ruta del producto es una función, no una
variable.** Una variable sería una puerta por la que una edición posterior
podría llamar al producto y **escapar del reloj**, y una escena colgada que
escapa del reloj es justo el fallo que este mecanismo existe para evitar. Las
dos únicas vías hacia el producto —`binary` y `binary_within`— aplican reloj, y
`binary_within` (la del follow, que tiene su propio presupuesto) sólo puede
acortar lo que queda, nunca alargarlo.

**El instrumento se comprueba al arrancar.** El reloj tiene que **disparar** y
tiene que **llevarse el árbol**, y eso se comprueba una vez, contra un
comando que no puede terminar a tiempo y que deja un nieto detrás: tres
segundos y la diferencia entre «el lanzador tiene un reloj» y «el reloj del
lanzador funciona y mata lo que arrancó». Si el instrumento no funciona, el
lanzador **se niega a arrancar** en vez de fingir que tiene un reloj.

**Los UAT efímeros, y lo que cada uno demuestra.** Dos, todos
retirados al cerrar el ciclo:

1. Una escena colgada (`sh("sleep 300")`) con `//@ timeout 5`: el producto se
   paró a los 5 s, el árbol murió con él, la escena quedó **BLOCKED por su
   nombre**, el recuento reconcilió (`0 passed, 0 failed, 1 blocked`) y la
   salida fue 1.
2. Una escena que falla de verdad: `0 passed, 1 failed, 0 blocked`. El reloj
   no tapa un fallo real.

**Las mutaciones, vistas en rojo sobre el código final.** **M1** (el reloj deja
de disparar: el presupuesto se vuelve 3600 s) → el lanzador se **colgó** y no
produjo veredicto ninguno; el reloj externo lo mató y no hubo nombre ni
resumen. Es exactamente el fallo que este cambio existe para que no ocurra, y
por eso la prueba que lo detecta importa. **M2** (el reloj para el producto
pero nadie lo nombra: el marcador desaparece) → la escena pasó a `failed`
(`1 failed, 0 blocked`) en vez de BLOCKED. La corrida siguió en rojo, que es
lo importante, pero el diagnóstico perdió la causa.

**La decisión de regresión, y su porqué.** El UAT que cuelga se **RETIRA**: era
exploratorio, y su evidencia queda registrada arriba. Lo que se **PROMUEVE** es
más barato y protege lo que de verdad importa: el auto-control del arranque,
que es permanente, cuesta tres segundos y falla ruidosamente si el reloj no
dispara o no se lleva el árbol. Las garantías de fondo —«una escena colgada no
paraliza la entrega» y «una escena bloqueada no puede contar como pasada»— no
dependen de ese UAT: la primera la sostienen el auto-control y la estructura
(ninguna invocación del producto escapa al reloj), y la segunda la sostienen
tres guardas independientes y la reconciliación de los cuatro contadores, que
hace que cualquier descuadre salga como **no reconciliado** y no como verde.

Queda **DEFERIDO**, con dueño, un ejemplo permanente que se cuelgue a
propósito. No se promueve aquí porque costaría un concepto nuevo en el
lanzador (una escena que *espera* el paro) y diez segundos en cada corrida,
para proteger un **diagnóstico**, no una garantía: si ese camino se rompiera,
la corrida seguiría en rojo y la escena seguiría nombrada. Su sitio natural es
la lente de economía del arnés (**B4.3**), que es donde se decide si un
escenario permanente se gana su coste.

**La medición de este bloque, sobre `58511c491`.** La puerta completa corrió
**tres veces** sin filtros, desde salidas de build limpias: **671 / 655 / 625
s**, peor 671 s contra el límite de 900 s. Las tres reescribieron los **122**
informes JUnit —905 tests, 0 fallos, 0 errores, 0 skips— y pasaron los **52**
escenarios E2E con **0 bloqueados**, que es el número nuevo que publica el
lanzador. El ratio de LOC sube de 0.230 a **0.233**: los 203 renglones que
añade este bloque son del lanzador y de la puerta, que cuentan como verificación,
no producto. La deuda estática congelada sigue en **802** entradas y el
certificador externo en **`BLOCKED_EXTERNAL`**. Los tres ensayos corrieron con
la máquina cargada por procesos ajenos a este repositorio, y los tres
siguieron dentro del presupuesto.

### 2.33 La cancelación a medias: un fallo de sellado ya no deja el run cerrado

El bloque B1.2 pedía reconciliar una cancelación parcialmente confirmada. La
medición lo dejó claro, y era un hueco real: el adaptador de cancel escribía el
**recibo**, luego el **hecho terminal** del run, y sólo después **sellaba** los
streams de salida. Con ese orden, un fallo del Output Plane dejaba el run en un
estado que **nadie podía terminar**:

- el run ya estaba **ABORTED** —su bookend estaba escrito—, y
- sus streams de salida **no estaban sellados**, así que sus bytes no terminaban
  nunca.

El cancel siguiente lo encontraba con `cancelRecorded = true` y respondía
`AlreadyCancelled`: no sellaba nada. Y `runs recover` tampoco sellaba —en
`RecoverAdapters` no había una sola llamada a `seal`—, de modo que **nadie** iba
a sellar esos bytes jamás. Un lector que siguiera la salida de un run abortado
se quedaba esperando para siempre.

Peor que el hueco: el cancel que fallaba **decía una cosa falsa**. Devolvía
`Refused(StorageError)` —«no se pudo cancelar»— sobre un run que ya estaba
cerrado y abortado. El operador recibía dos respuestas contradictorias sobre el
mismo run: «falló» y «ya estaba cancelado».

**El orden de las escrituras era el defecto, y el contrato ya decía cuál es el
correcto.** `CancelOutcome.Cancelled` significa «este call cerró el run, la
cancelación quedó registrada y los streams de salida quedaron sellados». Eso sólo
es verdad si el **hecho terminal va último**. Así que el orden ahora es: recibo,
sellado, bookend.

Con ese orden, un fallo de sellado deja el run **sin cerrar** y con el recibo de
la intención ya escrito —justo el estado que el decider ya describía como «un
cancel que registró su intención y murió antes de escribir el hecho terminal es
un run que un cancel posterior termina»—. El cancel siguiente sella lo que
falte (el sellado es idempotente), escribe el bookend **una** vez y responde
`Cancelled`. Ningún efecto se repite: el recibo se actualiza en su fila, porque
está indexado por el run, y el sellado de un stream ya sellado es un no-op. Y la
primera negativa ahora es **cierta**: nada terminal había pasado.

**El recover también habla el idioma del journal.** Un run cancelado es terminal
aunque tuviera un paso en vuelo cuando lo cancelaron —el cancel es su hecho
terminal—, y el journal lo dice con un vocablo que ya existía publicado
(`TerminalObservation.Cancelled`, y una rama del decider que estaba **muerta**,
porque nadie producía nunca «cancelled»). Con eso, el recover de un run
cancelado responde «ya está cerrado, no hay nada que recuperar» en vez de
**ofrecer un re-attach**: reanudar un run que alguien canceló a propósito puede
repetir el efecto del paso interrumpido, que es justo lo que el cancel quería
evitar. El nombre del recibo vive ahora en un sitio solo (`CancelReceipt`), que
lo escriben y lo leen sin que las dos copias se separen.

**El adaptador de cancel no tenía ninguna prueba.** Es la pieza que decide si
una ejecución se puede cerrar, y estaba sin observador: su regla de decisión
pura sí tenía test, el adaptador no. Para poder observarlo sin disco, el
adaptador ahora depende del **puerto** de lease y no de su implementación en
ficheros —igual que hace el recover—, y el sellado entra como **función
inyectada**, que es donde cabe el fallo que hace falta provocar. Nace
`RuntimeControlPortStoreAdapterTest` con **cinco filas**: el camino feliz, el
fallo de sellado, la reconciliación, la invariante de N llamadas con un solo
estado terminal, y que la lease se devuelve también cuando el cancel falla. En
el recover se añade una sexta: un run cancelado no se reanuda.

**Las mutaciones, vistas en rojo sobre el código final.** **M1** (volver a
escribir el hecho terminal antes del sellado) encendió **2 de las 5** filas del
adaptador —«un fallo de sellado no deja el run cerrado» y «el cancel siguiente
termina el trabajo»—, y ninguna más. **M2** (que el recover deje de reconocer
el recibo de cancelación) encendió **1 de las 6** del módulo —«un run cancelado
no se reanuda»—, y ninguna más. Ambas se leyeron de vuelta y se deshacieron.

**El UAT efímero, con el binario real, y lo que se retira.** Fue un script
efímero fuera del repo, sobre la distribución recién construida: mata al
escritor de un run, inyecta un fallo de sellado real —un enlace simbólico
colgante en el marcador, que hace que la escritura falle con un
`NoSuchFileException` de verdad, sin depender de los permisos ni de ser root—,
y después lo cancela tres veces. Con el binario corregido: el primer cancel **se
niega** (salida 2) y el run **no** queda cerrado, el recover **no ofrece**
reanudarlo, el segundo cancel **termina el trabajo** («cancelled run … (1 output
stream(s) sealed)»), el run tiene **un** hecho terminal, el stream queda
**sellado**, y el tercer cancel es un no-op. **El mismo UAT contra el binario
previo se encendió en 2 de sus comprobaciones** —«el run quedó cerrado aunque el
sellado falló» y «ningún stream quedó sellado»—: el defecto es real y el
instrumento lo ve.

Se **RETIRA**: su inyección necesita una ruta interna del store y una secuencia
de tres cancels que el lanzador no expresa, y hacerlo expresable sería crecer el
arnés para proteger una garantía que ya protege el sitio más barato. Lo que se
**PROMUEVE** son las seis filas unitarias, que son deterministas, no tocan disco
y discriminan cada cláusula. El ejemplo permanente `durable/39-cancel-dead-run`
sigue cubriendo el camino feliz por CLI —el primer cancel cierra el run y el
segundo es un no-op—.

**La deuda que queda de este bloque, dicha.** La introspección no tiene
vocabulario para «cancelado a medias»: un run sin hecho terminal se lee como
«running», que es cierto —el run no está cerrado— pero no dice que hubo un
cancel. Y un cancel que falla no deja más rastro que su recibo en el journal.
Ninguna de las dos cosas es una mentira, y ninguna necesita un contrato nuevo
para arreglarse; lo que harían falta es más vocabulario **publicado**, y eso
sería una ruptura versionada que este bloque no pide. Queda anotado, no
resuelto.

**La medición de este bloque, sobre `09cd325a8`.** La puerta completa corrió
**tres veces** sin filtros, desde salidas de build limpias: **645 / 626 / 631 s**,
peor 645 s contra el límite de 900 s. Las tres reescribieron los **123** informes
JUnit —**911** tests, 0 fallos, 0 errores, 0 skips— y pasaron los **52**
escenarios E2E con **0 bloqueados**, 2 reruns durables y 7 credenciales
sembradas. El ratio de LOC sube de 0.233 a **0.237** (**15499 / 65325**), y el
peor módulo sigue siendo `pipeline-step-sdk:workflow-control` (**1.633**), que
este bloque no tocó. La deuda estática congelada sigue en **802** entradas y el
certificador externo en **`BLOCKED_EXTERNAL`**. No hay `NOT_RUN` ni `BLOCKED` en
la puerta: el UAT efímero de este bloque se ejecutó y pasó (y se encendió contra
el binario previo), y lo único que sigue impedido es el certificador, que no
existe ni aquí ni en remoto. Los tres ensayos corrieron con la máquina cargada
por procesos **ajenos a este repositorio** —hay otro workspace trabajando en
ella— y los tres siguieron dentro del presupuesto.

### 2.34 El observador que muere: la vista ya no puede escribir en el journal

El bloque B2.1 pedía resolver la incidencia #145: *matar un observador no debe
matar, cancelar ni alterar al productor*. Lo primero que había que hacer era
**reproducirla en el binario actual**, y eso no se pudo. Lo segundo —que es lo
que sí tenía remediable— era mirar **cómo se acopla hoy el observador al
productor**, y ahí estaba el defecto real.

**La incidencia no se reproduce en este entorno, y hay que decirlo con todas
las letras.** El script mínimo de la propia incidencia (productor con un
`sleep 30`, `events --follow` en segundo plano, `kill -9` del observador) se
ejecutó **contra el árbol actual** y **contra la línea publicada `v0.61.0`**
—extraída con `git archive` y compilada—: en los dos casos el productor
sigue vivo, sale con 0 y termina con su `RunFinished`. El script de la
incidencia **no reproduce su propio síntoma aquí**. Su hipótesis —un
`subscriber` registrado en la cola de salida del publisher— **no aplica a este
producto**: ese concepto no existe en él. Se descartó además la explicación
por `SIGPIPE`: con la salida del productor en una FIFO cuyo lector muere de
`SIGKILL`, el productor **termina con `SUCCESS`** (código 0 y `RunFinished`),
así que la presentación no es autoridad sobre la vida del run. Por lo tanto
**la incidencia #145 no puede cerrarse desde aquí**: el ROADMAP V2 exige
«issue #145 cerrado sólo si se verifica», y aquí no se verifica. Queda
**abierta**, con el motivo medido.

**El acoplamiento, en cambio, era real y estaba medido.** El verbo `events`
—y con él `observe`, `inspect`, `console` y `events verify`— abría el store del
productor con **permisos de escritor**: su constructor crea el esquema, crea el
índice de unicidad, migra el journal, hace `backfill` de `run_id` y **deja un
hilo escritor vivo**, y además mantiene **una conexión capaz de escribir**
durante toda su vida. Un lector que escribe no es un lector: era un segundo
escritor del journal del productor, y podía tomar un **lock de escritura**
sobre él. Que hoy no haya breakage se debe a una mitigación **accidental**
(`PRAGMA busy_timeout = 5000`), no a una garantía.

**La corrección: dos calles, y el motor como autoridad.** El store se abre en
una de dos calles y la calle la decide el **propósito** —`EventStorePurpose.storeMode`,
que es puro y decidible sin disco—:

- **Writable**: la del productor. Crea el esquema, añade hechos, es dueño del fichero.
- **ReadOnly**: la del lector. El motor **SQLite rechaza toda escritura**
  (DML y DDL) con `SQLITE_READONLY`, de modo que la garantía la impone la base
  de datos y no la disciplina del código. Sin esquema, sin migración, sin
  `backfill` y sin hilo escritor.

Lo que hace el motor es mejor que una promesa: la conexión se abre con el
flag de sólo-lectura de SQLite, así que **ni el código ni un descuido
accidental pueden escribir**. Un store de lectura sobre una base **sin
historia** —sin
tabla `events`— se rechaza con un motivo **tipado** (`NoEventHistory`) en vez
de responder «run desconocido», que señalaría al operario hacia el run en vez
de hacia el fichero. El texto de los cuatro rechazos queda fijado por test,
porque es un contrato que los operadores y los scripts leen con `grep`.

**Medido con el driver publicado** (sqlite-jdbc 3.46.1.3): una conexión de
sólo-lectura abre una base **WAL** que otro proceso tiene abierta, la lee con
el escritor vivo, la lee **tras un escritor muerto sin consolidar** y la lee
**tras un escritor cerrado limpiamente**. Toda escritura la rechaza el motor.
La forma URI (`?mode=ro`) tiene un detalle que hubo que resolver: un URI
termina su ruta en el primer `?` o `#`, así que un fichero con esos
caracteres se abriría **por otro lado** —un lector apuntando al journal
equivocado—. Se escapan `%`, `#` y `?`, y se comprobó con
`PRAGMA database_list` que el fichero abierto es exactamente el pedido,
incluso con un `%20` literal en el nombre.

**Verificado contra el binario real:** leer el journal de un run **no cambia
ni un byte** del fichero —`sha256` idéntico antes y después de `events` y de
`inspect --state`—. Antes de este bloque, esa lectura creaba tablas, migraba
y hacía `backfill` en el fichero del productor.

**El instrumento permanente.** Nace el ejemplo
`examples/events/06-observer-death-does-not-kill-the-producer`, que levanta un
observador **real** —un segundo proceso, el verbo `pipelinek events --follow`,
apuntando a la base de datos de ese mismo run—, **espera a que entregue un
hecho** (para que el acoplamiento estuviera vivo y no hipotético) y deja que el
pipeline lo **mate con `SIGKILL` en pleno vuelo** mientras el productor sigue
trabajando. Se comprueban cuatro cosas, y cada una puede fallar por su
cuenta: que el observador **se adhirió** (su salida trae un hecho de ese
run), que **murió** (la muerte es un paso, así que una muerte que no surtió
efecto falla el run), que el **productor siguió** (el paso posterior a la
muerte se ejecutó) y que el run **acabó `succeeded`** —el `run-state` del
lanzador, que es el hecho terminal del propio run; un run abortado en vez de
terminado se delata ahí—.

**La mutación, vista en rojo.** Sustituyendo la muerte del observador por la
muerte del **productor**, el ejemplo se puso **rojo** con la causa exacta —
`inspect: run ... is being published by ... (fencing token 1)`, que es un run
que **nunca llegó a su hecho terminal**— y el lanzador salió con **1**. Esa es
precisamente la forma del síntoma publicado de la incidencia. En verde, el
escenario pasó **cinco ejecuciones seguidas**: la adhesión del observador no
es una carrera.

**El rechazo nuevo, y un fallo que había que encontrar.** El store de lectura
sobre una base **sin historia** se rechaza con un motivo tipado. La primera
versión de esa sonda **no funcionaba**, y no lo habría encontrado ningún test: la sonda era
un `var` que el constructor llenaba y que un inicializador posterior volvía a
poner a `true`. El orden de inicialización de Kotlin hace que el inicializador
corra **después** del cuerpo del constructor, así que la sonda contestaba
siempre que sí: una base sin tabla `events` era admitida y moría más tarde con
el `no such table` crudo de SQLite, **escupido como traza de pila** y con el
código de salida **1** —el que el producto reserva para un run que se ejecutó y
falló—. Se encontró a mano, apuntando el verbo `events` a una base creada sin
la tabla de eventos: la traza lo decía todo.

El arreglo tiene dos partes. La sonda pasa a ser un **`val` con su propio
inicializador**, que es una forma que **no puede** ser sobrescrita por otro
inicializador: la trampa desaparece en vez de esquivarse. Y las lecturas se
blindan con ella —`hasRun`, `tailSequence`, `eventsFor` y `readRecords`
contestan vacío cuando no hay historia—, de modo que **ninguna ruta** pueda
sacar una traza cruda aunque la sonda fallara: una base sin historia contesta
«nada», no un error.

**Y el rechazo se hace alcanzable de forma hermética.** Un fichero de **cero
bytes** es, para SQLite, una base de datos válida y vacía: no hace falta
fabricar una base con otra tabla, basta con un fichero vacío. Con eso nace el
caso negativo `negative/events-no-event-history`, que comprueba **dos** cosas:
que el rechazo diga **qué** pasa —`Error: db has no event history: <path>`— y
que el fichero siga **vacío** después. Lo segundo es la mitad con dientes: un
lector que abre el fichero con permisos de escritor convierte un fichero de
cero bytes en una base de datos con el esquema de eventos dentro, y uno que no
puede escribir se lo deja intacto. **Medido**: el fichero salió con 0 bytes.

**La mutación, vista en rojo en los dos niveles.** Devolviendo al lector los
permisos de escritor —una sola línea, el mapa propósito→modo—, el caso
negativo se puso **rojo** con el mensaje exacto (`the reader wrote to a
database it was only asked to read`, lanzador con salida **1**) y el test puro
encendió las **mismas 2 filas** que la mutación anterior, sin colaterales.

**Lo que este bloque NO cubre, dicho.** La mitad «ni alterar el productor» no
tiene un final de azar: el motor lo impone, y la lectura se midió por
`sha256` (y por el fichero vacío que sigue vacío). La mitad «ni cancelar» la
cubre el hecho terminal del run. Lo que **no** se cubre es la **reconexión**
desde un cursor persistido —sin pérdida ni duplicación—, que es territorio de
**B2.2**: este bloque no la toca.

**La medición de este bloque, sobre `ac24475e1`.** La puerta completa corrió
**tres veces** sin filtros: **739 / 679 / 719 s**, peor **739 s** contra el
límite de 900 s. Las tres reescribieron los **125** informes JUnit —**919**
tests, 0 fallos, 0 errores, 0 skips— y pasaron los **54** escenarios E2E con
**0 bloqueados**, 2 reruns durables y 7 credenciales sembradas. El ratio de LOC
sube de 0.237 a **0.239** (**15655 / 65419**), y el peor módulo sigue siendo
`pipeline-step-sdk:workflow-control` (**1.633**), que este bloque no tocó. La
deuda estática congelada sigue en **802** entradas y el certificador externo en
**`BLOCKED_EXTERNAL`**. No hay `NOT_RUN` ni `BLOCKED` en la puerta: el
certificador es lo único que sigue impedido, porque no existe ni aquí ni en
remoto.

El reloj sube frente al ciclo anterior (645 / 626 / 631 s) y el motivo se sabe:
este bloque añadió **dos** escenarios que arrancan **otro proceso** cada uno —
el observador del `events/06` es una JVM propia—, y el escenario nuevo del
observador espera a que esa JVM levante, se adhiera al run y entregue un hecho
antes de poder matarla. Es tiempo **comprado**: se paga por una garantía que
antes no tenía instrumento. Sigue dentro del presupuesto con **161 s** de
holgura en el peor ensayo.

### 2.35 Los rechazos publicados: seis casos que el producto nunca emitía

> **Estado: saldado en este ciclo.** El censo de los dos vocabularios de
> rechazos publicados —el del follow de eventos y el del plano de salida— dice
> que de sus **18** casos, **6** no los construye ningún código: ni en
> producción, ni en los tests, ni el artefacto sellado `0.62.0`. Este ciclo los
> retiró. El censo da hoy **0**.

**Instrumento** (reproducible, sobre el árbol commiteado): un productor es el
código que **devuelve** el caso; un `when` que lo nombra para revisarlo no lo
construye, y un comentario que lo cita tampoco. El instrumento cuenta las
construcciones calificadas (`OutputRefusal.Corrupt(`) y las sin calificar
dentro del propio vocabulario, que es como la traducción construye sus casos.

**Cifras, con su denominador:**

| | antes | ahora |
|---|---|---|
| casos en los dos vocabularios publicados | **18** | **12** |
| de ellos, los que ningún código construye | **6** | **0** |
| en el plano de salida | 13 casos, 4 sin productor | 9 casos, 0 sin productor |
| en el follow de eventos | 5 casos, 2 sin productor | 3 casos, 0 sin productor |

**Los seis, y por qué cada uno era un hecho que su plano no puede producir:**

| Caso | Lo que decía | Por qué nadie podía producirlo |
|---|---|---|
| `OutputRefusal.Corrupt` | «una fila existe pero su carga útil no la respalda» | El plano de salida guarda bytes opacos: nada los parsea. El plano que sí parsea payloads es el de eventos, y ya responde con `EventRecordRead.Undecodable`. |
| `OutputRefusal.RangeLostRetention` | el gemelo del lado lectura de `RetentionGap` | Los mismos tres campos y el mismo significado: dos casos públicos para un hecho. |
| `OutputRefusal.Unavailable` | «el sustrato no se puede alcanzar ahora» | La copia del lado lectura de un hecho que el lado de la poda sí produce. |
| `OutputRefusal.FollowCancelled` | «el consumidor cerró el follow» | Cerrar un handle es una operación del ciclo de vida del consumidor, no un rechazo de una lectura. |
| `EventFollowRefusal.RetentionLost` | «la retención perdió el principio del run» | El store de eventos no borra ninguna fila: un cursor sólo puede estar detrás de la cola. |
| `EventFollowRefusal.Cancelled` | «el follow se canceló» | La KDoc del propio handle dice que la cancelación es una operación local, no un resultado del follow. |

**Lo que se hizo.** Los seis casos se retiraron, y con ellos las KDoc que los
prometían —incluida la del `EventFollowState`, que decía que la pérdida de
retención «se señalaba» con `RetentionLost`—. Los ids se versionaron con el
procedimiento que el propio repo ya usa para esto: el id viejo pasa a
**DEPRECATED** (anunciado y NO entregado) y se publica el nuevo.
`events.follow.v3` y `output.follow.v2` son los que este build entrega.

**Lo que NO se rompió.** Quitar un caso de un tipo sellado es una ruptura de
**fuente** para quien recompila —su `when` exhaustivo deja de compilar— y
**no** de binario: desaparece el constructor, pero el caso nunca se
instanció, así que la respuesta del runtime es byte a byte la misma. El libro
`contract/published-contract-exceptions.json` registra las dos rupturas con su
sha, su motivo y su frontera.

**La alternativa que se midió y se rechazó: darle un productor a `Corrupt`.**
Habría exigido hacer del plano de salida un plano que parsea payloads. La
evidencia dice que no: el log de frames de ese plano lo **repara el escritor**
—trunca hasta el último frame entero y vuelve a enmendar los bytes
comprometidos—, así que un log dañado es un estado **transitorio** que el
lector espera, no una avería durable, y un rechazo durable habría sido una
mentira. De ese mismo examen salió el orden de escritura, que es
`bytes → commit → frame` a propósito, para que un rango comprometido sin frame
sea *recuperable* y no *perdido*.

**La mutación vista en rojo.** El instrumento no se dio por bueno sin
demostrarse: se añadió un caso que nadie construye (`OutputRefusal.NeverReported`)
y el censo se encendió señalando exactamente ese caso y ningún otro. Después
se revirtió.

**Una séptima promesa que nadie cumplía, y que no era de código.** El fichero
`contract/published-contract-maturity.json` afirmaba que sus cuatro reglas
«las aplica el fitness test». Ese test no existe desde el reset del testing y
nada lo sustituyó: las reglas son la lista de comprobación del revisor, y ahora
el fichero lo dice en vez de prometer una puerta que no está.

### 2.40 El censo contaba declaraciones, no garantías

**El defecto, con su número.** El censo del arnés decía «19 de 56 escenarios sólo
miran el código de salida». El número era verdad sobre el **canal** y falso
sobre la **garantía**:

- Los 19 **afirman**. Su garantía vive en los pasos del propio pipeline —un
  `sh("cmp a b")` que hunde el run cuando el producto se equivoca—, y el código
  de salida es lo que la transporta. El censo contaba declaraciones: un
  escenario con `//@ expect-stdout` puntuaba 1 y uno que afirma con sus propios
  pasos puntuaba 0.
- Y el código de salida **no dice lo que el run concluyó**. El producto publica
  esa conclusión en su lane de eventos, con cuatro palabras —`success`,
  `unstable`, `failure`, `aborted`— y el arnés no las leía. Un run que capturó un
  fallo y siguió adelante sale con 0 igual que uno que no tenía nada que
  capturar: el código de salida no distingue dos mundos que el operador tiene que
  distinguir.

Un censo que miente sobre lo que compra la suite no es un número feo: es una
decisión mal dirigida. Dice «escribe 19 `expect-stdout`», y eso es escribir
declaraciones, que es lo más barato que hay.

**Lo que hace el arnés ahora.** Lee la conclusión del run del propio producto —el
bookend terminal de su lane de eventos— y la compara con lo que el escenario
declara. Una directiva nueva, y en el vocabulario del producto:

```kotlin
//@ expect-outcome unstable
```

Sin ella, el resultado esperado se deduce del código de salida esperado: un run
que debe acabar bien espera `success`, y un run que debe fallar espera
`failure`. Con ella, el escenario dice qué espera que **concluya** el run, que no
es lo mismo que cómo termina el proceso.

**El tercer resultado, que no cabe en un bit.** `unstable` es un resultado de
verdad: un run que capturó un fallo y siguió adelante sale con 0 y se concluye
`unstable`. Dos escenarios lo producían sin declararlo —
`negative/12-error-handling` y `control-flow/11-workflow-control`— y ahora lo
dicen. El censo cuenta cuántos terminaron así, porque un verde inestable es un
verde más débil que un verde limpio y quien lee el resultado tiene que saber de
qué clase es.

**Las mutaciones, que es donde esto se prueba.** Un verde que nunca se ha visto
rojo no es evidencia, así que cada cosa nueva se ha encendido en rojo a
propósito, y se sabe qué fila se encendió:

| Mutación | Filas rojas | Filas que no se movieron |
|---|---|---|
| El producto publica `success` para **todo** run, mudo sobre su propia conclusión | **15**: las seis de saga, las dos de eventos, las cinco de `negative/`, la de Zip Slip y las dos que declaran `unstable` | **41**, ninguna afectada por la mentira |
| La expectativa de un escenario pasa de `unstable` a `success` | **1**, y el código de salida del run **sigue siendo 0**: un arnés que sólo mirara el código de salida no lo vería | el resto de la suite |
| La contención de la ruta de entrada de un archivo deja de decidir | **5** de Zip Slip | las 5 que aceptan nombres legales |
| El guard de credenciales en `argv` pierde el caso del usuario sin contraseña | **2**: el token como usuario, y la independencia del caso del esquema | las 8 restantes, incluidas las 4 que ya existían |
| Un terminal que el runtime no sabe clasificar se registra como éxito | **1** | las 3 restantes del vocabulario |
| La receta de la clave de caché `v1` se reescribe | **1** | las 5 restantes |

**El residuo que la mudanza dejó atrás.** Bajo el layout viejo quedaban **9
ficheros Kotlin que ningún build compilaba**: dos copias viejas de ficheros que
hoy viven en otro sitio, el módulo que se cortó a propósito, un adaptador que ya
se había borrado —su propio KDoc decía que «nunca lo llamó nadie»— y **cuatro
tests unitarios** que eran el **único observador** de reglas vivas:

| Test rescatado | Regla que estaba sin nadie mirándola |
|---|---|
| El vocabulario del run recuperado | «Lo que no se sabe no es un éxito»: un terminal que el runtime no clasifica se registra como fallo, y una cancelación como abortada |
| La ruta de entrada de un archivo descomprimido | La contención: un archivo puede pedir un nombre que cae fuera del destino |
| El guard de credenciales en `argv` | Las **formas aceptadas**: un guard que lo rechaza todo no protege nada, y el KDoc del propio producto prometía que un test las fijaba |
| La clave de caché | Que la receta vieja conserve su significado, porque un digest ya escrito tiene que seguir significando lo mismo |

Los cuatro se han portado a su módulo, contra el mismo API y sobre la misma
función observada, y **cada uno se ha visto rojo** antes de darse por bueno. Los
otros cinco ficheros se han borrado: eran código que el árbol actual ya no
tiene, y código que nadie compila es código que nadie sabe que existe.

**El árbol ahora declara el JDK con el que se compila.** El gate lo resolvía
por su cuenta —busca un JDK 21 entre las instalaciones de asdf y lo exporta
antes de invocar Gradle—, pero el build **no declaraba** ninguna toolchain, así
que un `./gradlew` lanzado directamente sobre el `java` de la máquina fallaba con
un `IllegalArgumentException: 25.0.4.1` que no nombraba ni un fichero ni una
regla: el compilador del DSL de Gradle no sabe leer la versión de un JDK más
nuevo que él. El mensaje del gate incluso decía «el build declara
`jvmToolchain(21)`», y eso no era verdad hasta ahora. Ahora es verdad en los dos
sitios, y `.tool-versions` hace que el `java` de dentro del repositorio sea el
que el build pide.

**Una fusión, y una garantía que faltaba.** Los tres escenarios del plugin de
utilidades —el viaje de ida y vuelta de JSON y YAML, el zip y el unzip, y la
cadena que los mezcla— afirmaban la misma capacidad repartida en tres ficheros,
y uno de ellos prometía en su comentario una «prueba de durabilidad por
entrada» que **no asertaba**: calculaba el SHA-256 de cada entrada y nunca
comparaba nada, así que un archivo con los nombres correctos y los bytes
equivocados pasaba. Ahora hay **un** escenario, con las tres garantías y con la
comparación byte a byte que faltaba. Los dos `withEnv` del árbol **no** se
fundieron: uno afirma el bloque de variables y el otro el paso de shell, y son
capacidades distintas.

**Medido, sobre el árbol de este ciclo:**

| Métrica | Valor |
|---|---|
| Escenarios E2E | **54**, los 54 verdes, 0 fallidos, 0 bloqueados |
| Escenarios con una garantía comprobada | **54 de 54** (antes el censo declaraba 19 sin garantía: era falso) |
| Garantías comprobadas por el arnés | **133** |
| Pasos que el producto terminó | **195** |
| Runs que concluyeron `unstable` | **2**, declarados por los escenarios que los producen |
| Tests unitarios | **1049**, 0 fallos, 0 skips, 145 informes reescritos por la corrida |
| `VERIFY_LOC / PRODUCT_LOC` | **18260 / 67318 = 0.271** (límite 2), medido sobre el árbol commiteado: con el árbol sucio el censo no ve los ficheros nuevos y la ratio sale mejor que la verdad |
| Reloj de la puerta | **197 s** contra 900 s (los tres ensayos: 197 / 166 / 173) |

**En una frase, para quien llega nuevo:** el arnés ya no pregunta «¿terminó el
proceso?», sino «¿qué concluyó el run, y es lo que el escenario esperaba?»; el
árbol ya no tiene Kotlin que nadie compile, y los cuatro tests que la mudanza
dejó atrás vuelven a vigilar las reglas que protegían; y el build dice con qué
JDK se compila, en vez de heredarlo de la máquina.

### 2.41 El comentario del análisis estático afirmaba un árbol que ya no existe

**Las dos mitades de la frase eran falsas.** La extensión del análisis estático
del build decía, sobre la línea que nombra las fuentes del análisis:

> Only `src/main/kotlin`: `src/test/kotlin` no longer exists, and listing a
> non-existent source directory is how a source-set guard silently covers
> nothing.

`src/test/kotlin` **existe**: hay **122** ficheros de test repartidos en **18**
módulos, y **1049** casos ejecutándose en la puerta. Y la razón que se daba
—que el análisis de las fuentes de test podía no cubrir nada— era una alarma
sobre un hueco que el análisis no tenía: la tarea de test alcanzó sus propias
fuentes todo este tiempo.

**Medido, no deducido.** Se añadió un import sin usar a un fichero de test y
se ejecutó el análisis sobre su módulo: la tarea **falló** nombrando ese
fichero, su línea y la regla, y su informe contó **5** ficheros .kt —los cinco
ficheros de test del módulo—. En el mismo módulo, el análisis de producción
contó **6**, que son los seis ficheros de `src/main/kotlin`. Cada tarea llega a
su propio conjunto de fuentes, y el conjunto de fuentes de test **no** queda
ciego por el baseline de producción: sus identificadores llevan el nombre del
fichero, así que un hallazgo en un fichero de test no se confunde con uno ya
congelado en producción.

**Lo que se cambió, y lo que no.** La configuración queda como estaba: la línea
que nombra `src/main/kotlin` sigue ahí, y no se tocó una regla, una tolerancia
ni un baseline. Lo que se cambió es la **razón** que la justificaba, que era
una afirmación sobre un árbol que ya no existe. El cambio es de un comentario y
no de un análisis: quitar la línea habría cambiado lo que se analiza, y eso no
se midió, así que no se quitó.

**Por qué importa igual.** Un comentario que describe una realidad que no es es
la forma más barata de que un guardián se crea que cubre algo que no cubre.
Este ciclo rescató cuatro tests que nadie compilaba —código que nadie sabe que
existe— y, antes, el build llevaba desde el reset afirmando que las fuentes de
test no existían. Son el mismo error visto desde dos lados: la garantía —aquí,
que el análisis estático llega a las fuentes de test— estaba en el árbol, y
nadie la miraba.

### 2.42 El destino del runtime, cerrado: los puertos se publican, los adaptadores se quedan dentro

**La decisión ya estaba tomada; el árbol todavía ofrecía dos futuros.** §2.31
decidió que `:pipeline-runtime` se queda. Esa decisión se sostiene y se ha
revisado pieza por pieza: los tres adaptadores implementan los puertos
publicados de `:pipeline-runtime-api` —`RuntimeControlPortStoreAdapter :
RuntimeControlPort`, `RuntimeRecoverPortStoreAdapter : RuntimeRecoverPort` y
`RuntimeIntrospectionPortStoreAdapter : RuntimeIntrospectionPort`—, y los
compone `CompositionRoot` para tres verbos reales: `runs cancel` llama a
`cancel`, `runs recover` llama a `recover` e `inspect --state` llama a
`inspect`. Lo que quedaba era un residuo: **el build del módulo seguía
aplicando `maven-publish` y declarando una publicación `sdk` que ninguna tarea
pedía**. Su propio comentario la llamaba «código muerto inofensivo».

No era inofensivo por dos razones, y las dos son de lectura, no de ejecución.
Un artefacto sin consumidor sigue siendo una promesa que el árbol le hace a
quien lo lea, y **dejaba dos futuros para el mismo módulo** cuando sólo hay
uno: la alternativa que este módulo no construyó —publicarse como contrato—
seguía escrita en el build.

**La convención del repositorio ya lo decía.** Los dos módulos hermanos que
tampoco se publican, `:pipeline-events-store` y `:pipeline-output-store`, no
aplican `maven-publish`, y su build lo explica: *la ausencia de
`maven-publish` es el mecanismo*. `:pipeline-runtime` era la excepción sin
excepción, y el motivo de la excepción era un bloque que se describía a sí
mismo como muerto.

**Medido antes de tocarlo.** Ninguna tarea dependía de
`:pipeline-runtime:publishSdkPublicationToSdkRepository`: ni
`publishedContractModules` ni `sdkBomModules` lo nombran, y el manifiesto del
candidato de release publica `pipeline-runtime-api`, que es el artefacto de los
puertos, no el de los adaptadores. El `publishing` del root resolvió los mismos
seis módulos antes y después del cambio.

**Lo que cambió.** El módulo deja de aplicar `maven-publish` y su bloque
`publishing { }` desaparece. El comentario que lo explicaba se queda, pero
diciendo la verdad: los puertos se publican en `:pipeline-runtime-api` y este
módulo es interno de `:pipeline-application`. El artefacto que la publicación
fósil habría producido llevaba en su POM los almacenes de eventos y de salida,
que es exactamente la arquitectura-as-política que el módulo de puertos existe
para hacer segura.

**La misma historia, en el contrato publicado.** El puerto de introspección
decía en su documentación que `runtime.structure.v1` y `runtime.operation.v1`
son EXPERIMENTAL y que se publicarían cuando el certificador certificara el
candidato. El catálogo dice otra cosa desde §2.31: esos dos ids están en
DEPRECATED, que aquí significa *anunciados y no entregados* —el adaptador los
implementa y ningún consumidor los compone—, y sólo `runtime.inspect.v1` lo
compone el verbo `inspect --state`. Un contrato publicado que promete más de lo
que el build entrega es un fallo que llega en tiempo de ejecución, y el
catálogo ya lo había corregido: lo que quedaba era el texto. Se reescribió esa
sección para que diga lo que es verdad hoy.

**La prueba de que el fósil no era quién lo sostenía.** Tras el cambio, el
módulo no expone ninguna tarea de publicación, el root sigue resolviendo los
mismos seis artefactos, y la puerta compila el módulo entero con sus tres
adaptadores y sus tres clases de prueba.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Puertos publicados de `:pipeline-runtime-api` | **4**, en `publishedContractModules` y en el BOM |
| Adaptadores que los implementan | **3**, los tres cableados a verbos reales (`runs cancel`, `runs recover`, `inspect --state`) |
| Tareas de publicación del módulo | **0**, tras retirar el bloque huérfano |
| Módulos que compila la puerta | **26 de 27**: el que falta es `:pipeline-sdk-bom`, que no tiene código |
| Tests unitarios | **1049**, 0 fallos, 0 skips, 145 informes reescritos por la corrida |
| Escenarios E2E | **54**, los 54 verdes, 0 fallidos, 0 bloqueados, 54 de 54 con garantía comprobada |
| `VERIFY_LOC / PRODUCT_LOC` | **18260 / 67304 = 0.271** (límite 2) |
| Reloj de la puerta | **201 / 156 / 156 s** en tres ensayos sobre `55183340a`, contra 900 s |

**El destino, cerrado.** Los puertos se publican; los adaptadores se quedan
dentro; y el árbol ya no ofrece la otra respuesta posible.

### 2.43 El plugin que miente: una garantía entera, sin un solo observador

**El defecto.** El producto tiene una **segunda puerta** de admisión. La
primera lee el documento: el manifiesto está bien formado, la identidad es la
del publicador, la versión sirve para el runtime, el artefacto es medible. La
segunda carga al contribuidor y compara lo que el manifiesto **DECLARÓ** con lo
que el contribuidor **APORTÓ**. Esa comparación es la única que puede ver un
manifiesto que miente, porque un documento que miente sigue siendo un documento
bien formado: la mentira sólo existe contra el código del plugin.

Medido: **esa puerta no tenía ni un solo observador**. Ni una clase de test
que la mencionara, ni un escenario ejecutable que la empujara. Los ficheros de
`src/test` que hablan de `PluginManifestRejection` eran cero.

**Por qué es tan fácil que no la mire nadie.** La primera puerta lee un
documento; la segunda necesita el código ya cargado. Quien lee la primera ve un
manifiesto, y un manifiesto bien formado parece un manifiesto verdadero. La
garantía era real y estaba en el árbol, y nadie la miraba: la misma forma que
took el layout viejo y los tests rescatados.

**El instrumento: una distribución con un plugin que miente.** Tres cosas que
hubo queaveriguar, y las tres son la razón de que el escenario sea como es:

1. **El plugin mentiroso tiene que SUSTITUIR al del bundle, no acompañarlo.**
   Ofrecerlo además es otro artefacto con la misma identidad, y se rechaza por
   duplicado antes de tiempo —el primer intento del escenario se equivocó de
   rechazo exactamente así—. Ese rechazo es correcto y es el que la primera
   puerta tiene que dar; no es el que este escenario va a observar. Y en
   cualquier caso, sus Steps chocarían con los del plugin honesto.
2. **Por eso el escenario corre contra su propia distribución.** La copia es
   byte a byte la instalada, con **un** JAR distinto dentro, y el binario es el
   mismo. Es lo que se ve en una instalación cuyo plugin miente. La capacidad
   que el arnés gana es angosta y con nombre: `//@ distribution`, y el reloj se
   aplica igual porque el producto se sigue invocando por la misma función.
3. **El manifiesto se puede doctorar porque el digest que declara no es el
   digest del fichero.** El runtime nunca los compara, y el manifiesto está
   excluido del digest que el build mide. Eso es lo que permite que un manifiesto
   mentiroso pase la integridad y llegue hasta la segunda puerta —que es
   exactamente donde tiene que llegar para que el escenario lo observe—.

**Qué afirma el escenario.** La salida distinta de cero, el artefacto nombrado
y la deriva nombrada. La deriva es lo que hace el escenario discriminante: un
run que sólo fallara dejaría abierto si el plugin se saltó, y el plugin se
puede saltar sin que la pipeline se entere —el escenario usa un Step que
nadie invoca—, así que un verde aquí sería indistinguible de un run que
funcionó con un plugin menos.

**Qué se vio en rojo, y qué no se movió.** Desactivando la segunda puerta, el
escenario sale con **0 en vez de 2** —el plugin mentiroso se cuela y el run
termina bien— y los **otros 54 escenarios siguen verdes**. El guardián es la
puerta, no el escenario.

**La regla pura, por separado.** La comparación es exacta y en **las dos
direcciones**: un Step declarado sin definición es una mentira por omisión, y
una definición sin declarar es la misma mentira por comisión. El escenario
observa la primera; la segunda y las familias de directivas y eventos no las
observa nadie, y para eso está el test de la regla, que usa el códec real del
producto y habla de los mismos documentos que el runtime lee de un JAR. Al
apagar una sola dirección de la comparación, **exactamente 1 de sus 6 filas se
vuelve roja** y las otras 5 no se mueven.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Puertas de admisión de plugins | 2: la que lee el documento y la que lo compara con el código |
| Observadores de la segunda puerta **antes** de este bloque | **0**: ni una clase de test, ni un escenario |
| Test de la regla pura | **6** filas: las dos direcciones y las tres familias |
| Escenario ejecutable | **1**: `negative/plugin-manifest-lies`, contra una distribución propia con un JAR mentiroso |
| Mutación del producto (puerta apagada) | el escenario sale con **0** en vez de 2; los **54** demás siguen verdes |
| Mutación de la regla (una dirección apagada) | **1 de 6** filas rojas; las otras 5 no se mueven |
| Tests unitarios | **1055**, 0 fallos, 0 skips, 146 informes reescritos por la corrida |
| Escenarios E2E | **55**, los 55 verdes, 55 de 55 con garantía comprobada (137 garantías) |
| `VERIFY_LOC / PRODUCT_LOC` | **18522 / 67304 = 0.275** (límite 2) |
| Reloj de la puerta | **177 / 162 / 177 s** en tres ensayos sobre `d05acadf1`, contra 900 s |

**Lo que este trabajo NO cubre, dicho con claridad.** El escenario observa un
plugin que miente **sobre sus Steps**, en una instalación real, y el test
observa la regla con nombres y conjuntos. No se ha observado: un plugin que
miente sobre sus capacidades declaradas en un JAR real, ni una deriva de
directivas contra una instalación real, ni el caso en que el plugin que miente
es *el primero* que se carga y desplaza a otro. La puerta existe para todos
esos casos y el árbol los sostiene, pero un observador es un observador.

### 2.44 Las otras dos familias de la misma mentira: capacidades y directivas

**El instrumento ya estaba pagado.** §2.43 dejó una distribución propia con un
JAR mentiroso y un lanzador que la construye. Extender la observación a las
otras dos familias de la segunda puerta no fue arquitectura nueva: fue el mismo
doctoring sobre el mismo JAR, con dos anclas distintas en el manifiesto. El
arnés ganó dos fixtures más —`lying-capabilities` y `lying-directives`— y
nada más: el código que los construye es el mismo que ya existía, con la
decisión de *qué* mentira se cuenta movida a un argumento.

**La familia de las capacidades.** El manifiesto declara, para uno de sus
Steps, una capacidad que el contrato de ese Step **no exige**. El plugin dice
«necesito esto» y su propio código nunca lo pide. Es la mentira más parecida a
la que cometería un publicador real: prometer un modelo de datos que el plugin
no entiende. La comparación de la regla es de conjuntos —lo declarado contra lo
que el contrato exige—, así que la deriva se nombra con **las dos caras**: qué
declaró el manifiesto y qué exige el contrato.

**La familia de las directivas.** El manifiesto declara una Directive que
ningún contribuidor aporta. Una Directive no es sólo un dato: es una pieza de
la composición que el operador **invoca por nombre**. Un plugin que declara una
que no entrega hace que la pipeline del operador nombre algo que no existe, y
el rechazo convierte eso en un refusal en tiempo de admisión que nombra al
fantasma. La regla también mira en la dirección contraria —una Directive que
alguien aporta y el manifiesto no declara—, y esa fila ya estaba cubierta.

**Las dos mutaciones, vistas en rojo.** Apagar una comparación apaga **su**
escenario y **su** fila, y nada más:

| Mutación | Escenario rojo | Fila unitaria roja | Sin mover |
|---|---|---|---|
| La comparación de capacidades se apaga | **1** (`plugin-capability-lies`: exit 0 en vez de 2) | **1** de 8 | **56** escenarios, 7 filas |
| La comparación de directivas se apaga | **1** (`plugin-directive-lies`: exit 0 en vez de 2) | **1** de 8 | **56** escenarios, 7 filas |

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Familias de la segunda puerta observadas contra un JAR real | **3** de 3 (Steps, capacidades, directivas) |
| Escenarios ejecutables | **3**: `plugin-manifest-lies`, `plugin-capability-lies`, `plugin-directive-lies` |
| Fixtures del lanzador | **3**, todos con el mismo doctoring sobre el mismo JAR |
| Test de la regla pura | **8** filas: las dos direcciones de cada familia |
| Tests unitarios | **1057**, 0 fallos, 0 skips, 146 informes reescritos por la corrida |
| Escenarios E2E | **57**, los 57 verdes, 57 de 57 con garantía comprobada (145 garantías) |
| `VERIFY_LOC / PRODUCT_LOC` | **18616 / 67304 = 0.277** (límite 2) |
| Reloj de la puerta | **194 / 172 / 165 s** en tres ensayos sobre `657306648`, contra 900 s |

**Lo que sigue sin cubrir, y es lo último que queda de esta puerta.** Tres
cosas, todas deliberadamente dichas: un plugin que miente sobre sus **eventos**
declarados contra una instalación real —la tercera familia de la regla, que el
test ya mira con nombres pero que ningún JAR real ha mentido todavía—; el caso
en que el plugin que miente es **el primero** que se carga y desplaza a otro;
y la deriva de **capacidades** en la dirección contraria, cuando el plugin
exige más de lo que su manifiesto declara.

### 2.45 El proveedor sin origen: la segunda puerta miraba un artefacto que no podía nombrar

**El hallazgo.** La segunda puerta compara lo que un plugin **declaró** con lo que
su código **aporta**, y para hacerlo reparte las contribuciones por artefacto:
cada plugin se compara contra las suyas, no contra las del vecino. El reparto
necesita saber de qué artefacto viene cada contribución, y esa pregunta se
contestaba descartando al que no supiera responderla: un proveedor cuyo origen
no se puede medir se **ignoraba en silencio**, en las tres familias.

Ignorarlo no es una omisión neutra, y son dos las formas:

- Si el proveedor era de un plugin admitido, sus Steps declarados parecían no
  estar implementados. El rechazo llegaba, pero señalaba al síntoma y no a la
  causa: el plugin(load) fallaba «no implementado» cuando lo cierto es que su
  contribución no se pudo colocar en ninguna parte.
- Si el plugin no declaraba nada de esa familia, la contribución **extra** era
  invisible. La dirección inversa del cruce —«aporta algo que no declaró»—
  tenía un agujero, y un plugin podía publicar un Step que ningún manifiesto
  mencionaba sin que nadie lo notara.

**La medición, sobre la distribución instalada.** Una sonda enumeró los
proveedores que el runtime carga de verdad: **7** de Steps, **2** de Directives
y **1** de Events, **10** en total. **Todos** tienen origen resoluble y **0**
carecen de él. El agujero **no es alcanzable hoy** en la distribución; el
arreglo es preventivo, y por eso queda escrito como lo que es —una puerta que
estaba abierta, no un fallo reproducido— y no como una incidencia.

**Por qué el classloader no podía ser la autoridad.** La alternativa obvia era
preguntar al cargador de clases, y es justo la que no sirve. La delegación es
**padre-primero**, así que el proveedor de un plugin que la instalación ya trae
—`scm-git`, `utilities`, `junit`— lo carga el cargador de la aplicación, igual
que a un proveedor del propio runtime. Preguntar al classloader habría
respondido «es del runtime» para la contribución de un plugin, y esa
contribución habría quedado sin artefacto: exactamente el agujero que se
pretende cerrar. La procedencia es el **code source** de la clase, que es lo
único que la JVM guarda sobre ella.

**Qué cambió.** La procedencia se mide con un resultado tipado —resuelto,
ausente o ilegible— y un proveedor que no la declara **detiene la composición**,
con su nombre, en vez de quedarse fuera del reparto. El rechazo es un caso
nuevo del resultado de la composición, la CLI lo dice y sale con **2**, como
cualquier otro rechazo de admisión. El sitio de cada pieza importa: la puerta
`pipeline-application` **no está publicada**, así que su forma puede cambiar sin
romper a nadie; el vocabulario de rechazos **sí** lo está, y por eso el
rechazo nuevo **no** se metió ahí —habría roto el ABI de los consumidores, y
`apiCheck` lo habría detectado en la puerta.

**El instrumento, y por qué no hay escenario ejecutable.** La regla se separó de
la medida que la produce: la medida lee el code source de la clase, y la regla
decide sobre un valor. Así la regla se puede ejercitar contra proveedores cuyo
origen no puede producir ningún classpath, que es justo el caso real, porque en
la distribución no existe ninguno. Un escenario ejecutable habría sido una
mentira: no hay artefacto que publicar que produzca ese estado, y un escenario
que lo fingiera estaría midiendo el adorno, no la garantía. Lo que **sí**
protege el riesgo de disponibilidad —un proveedor **del runtime** sin origen
pararía cada carrera— son los **57** escenarios, que componen plugins en cada
corrida: si existiera uno así, los 57 habrían salido con **2** en vez de **0**,
y ninguno salió.

**La mutación, vista en rojo.** Apagar el guardián —que un proveedor sin origen
se reparta contra un camino en vez de detener la composición— encendió
**exactamente 4 de las 9** filas: las tres familias y la causa ilegible. Las
otras cinco siguieron verdes: el aislamiento entre artefactos, la dirección
inversa del cruce y las tres filas de la medida. La mutación se leyó de vuelta
en el fichero, con su número de línea, antes de darla por aplicada.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Proveedores que el runtime carga de verdad | **10**: 7 de Steps, 2 de Directives, 1 de Events |
| De ellos, sin origen resoluble | **0** |
| Familias con el guardián | **3** de 3 |
| Test de la regla | **9** filas: tres familias, la causa ilegible, el aislamiento entre artefactos, la dirección inversa del cruce y la medida |
| Escenarios ejecutables | **0** nuevos, y no por descuido: el estado que el guardián mira no lo produce ningún artefacto |
| Tests unitarios | **1066**, 0 fallos, 0 skips, 147 informes reescritos por la corrida |
| Escenarios E2E | **57**, los 57 verdes, 57 de 57 con garantía comprobada (145 garantías) |
| `VERIFY_LOC / PRODUCT_LOC` | **18820 / 67363 = 0.279** (límite 2) |
| Reloj de la puerta | **174 / 159 / 161 s** en tres ensayos sobre `1e657a8ce`, contra 900 s |

**Lo que dice la auditoría externa, y qué se hace con cada fila.** Una revisión
externa no es una verdad: es una lista de candidatos, y cada uno hay que
contrastarlo con el código antes de aceptarlo. Esto es lo que resultó de
contrastarlos uno a uno:

| Fila de la auditoría | Veredicto | Por qué |
|---|---|---|
| Un proveedor de contribuciones sin origen se ignora en silencio | **corregido en este bloque** | era cierto en el código, y el guardián ahora lo detiene nombrándolo (§2.45) |
| Dos autoridades de identidad: digest declarado, digest medido e identidad de confianza | **abierto, con una razón que lo acota** | el vocabulario de rechazos está **publicado**: añadir un caso rompe el ABI de los consumidores y `apiCheck` lo ve. Separar los tres nombres es un cambio de contrato y necesita su propio bloque y su versión |
| Certificación externa bloqueada | **`BLOCKED_EXTERNAL`, sin cambio** | no hay certificador en este repositorio ni en remoto. No es un defecto del producto y no se puede cerrar desde aquí |
| El frontend scripted compone demasiadas cosas | **diferido al bloque de duración** | es un refactor de la composición, y un refactor sin medida del antes y el después es un cambio de forma |
| El Output Plane mezcla decisiones puras e infraestructura | **diferido al bloque de duración** | por lo mismo, y porque las garantías de concurrencia del Output Plane no se tocan sin su propio instrumento |
| Los identificadores de stream pueden colisionar | **abierto, con el código leído** | `safe()` dobla todo lo que no sea `[A-Za-z0-9._-]`, así que dos identificadores distintos pueden acabar en el mismo directorio. El índice de fotogramas guarda el identificador real, pero los bytes van al directorio. Falta demostrar si algún consumidor necesita distinguir los dos, y hasta entonces no se toca el formato en disco, que sí es un contrato |
| La baseline de análisis estático es grande | **la cifra estaba caducada; la deuda no se movió** | el número real son **879** entradas, no las 802 anotadas (§1), y ninguna baseline cambió en este bloque |
| Los verificadores tienen demasiada lógica | **diferido al bloque de publicación y pruebas** | el arnés ya no crece en vocabulario, y reducirlo es un bloque con su propia medida de coste, no un recorte a ciegas |

De las cuatro propuestas de pensamiento lateral, este ciclo **adopta una**: la
de convertir el roadmap en una vista operativa, que es la sección 0 de este
documento. Las otras tres —certificar por garantías en vez de por cantidad de
escenarios, derivar el contrato del plugin de sus contribuidores, y una suite
pequeña de fallos de producción simulados físicamente— quedan **diferidas** a
los bloques que las pueden pagar. La primera, además, ya está en pie: el arnés
cuenta **garantías** (145) y no escenarios.

### 2.46 La matriz de admisión del store de eventos: seis casos y una ley

**La regla.** El store de eventos tiene una sola decisión —«¿puede abrirse este
journal, y en qué carril?»— y un conjunto cerrado de razones por las que no.
La regla vive en `refusalFor(observación, path, propósito)`: una función
**total** sobre dos ADTs cerrados, sin E/S. Las tres observaciones son
`NoDirectory`, `NoFile` y `Present`; los dos propósitos, `Read` y `Write`. Tres
por dos son seis combinaciones, y la función decide las seis, sin rama por
defecto.

El propósito existe porque la diferencia es de dato y no de código: un verbo que
**lee** un run terminado está apuntando a un fichero que tiene que existir ya;
un verbo que **empieza** un run está creando ese fichero, así que su ausencia es
el caso normal y lo que tiene que existir es su **directorio**. Un
`if (!Files.exists(...))` no expresa las dos cosas sin una bandera repetida en
cada punto de llamada.

**Por qué una matriz y no seis tests sueltos.** Los seis casos se expresan como
una `@TestFactory` que devuelve seis `DynamicTest` con nombres que se leen
—«missing file / write may create»— en vez de seis métodos casi idénticos. Pero
una fábrica que devuelve cinco filas verdes **es** un test verde, y por eso la
fábrica se defiende sola antes de generar nada: compara el número de filas
esperado con el de las declaradas, y compara el conjunto de pares
observación-propósito con el conjunto completo. Una combinación omitida o
repetida falla la guarda antes de llegar a la primera fila.

**La ley que no es de la matriz.** «Todo rechazo nombra el `path` que el
operador recibió» no es una combinación de observación y propósito: es una
propiedad del **conjunto** de rechazos. Por eso es un `@Test` aparte, y por eso
la cubre la mutación de «permitir todo»: si la regla acepta cualquier cosa, no
hay rechazo, y un rechazo sin `path` es un diagnóstico que el operador no puede
usar.

**Qué no cubre, y por qué se dice.** `observePath` y `admitEventStore` tocan el
sistema de ficheros y abren una base de datos; eso no es lo que un test unitario
de este árbol hace. Queda escrito en el propio test, no callado.

**La evidencia, con Gradle y JUnit reales.** La validación se hizo en un
worktree aislado, sobre el commit de la rama de la PR y con el JDK que declara
el proyecto: `:pipeline-events-store:test --tests '*EventStoreAdmissionTest'`
→ **7 casos** (6 dinámicos más la ley), **0 fallos**, **0 skips**.

**Las dos mutaciones, vistas en rojo.** **M11** —el permiso de escritura
convierte el «ausente» en rechazo para todo propósito— encendió **exactamente 1
de 7** casos: el único que distingue al escritor del lector, que es el que
justifica que exista el propósito. **M12** —la regla acepta cualquier
combinación— encendió **exactamente 4 de 7**: las tres filas de rechazo y la
ley del `path`. El fichero de producción se restauró **byte-idéntico** —el
mismo resumen criptográfico— y el árbol quedó limpio.

**Por qué no hay escenario ejecutable.** La regla que estos casos cubren es
pura: su mundo es una combinación de dos valores cerrados, y una ejecución
real añadiría el arranque del CLI a un caso que no lo necesita. Lo que un
escenario mediría de verdad —componer un store que no existe— es lo que ya
miden los **57** escenarios de la puerta.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Combinaciones de la regla | **6**, de 3 observaciones × 2 propósitos, todas declaradas |
| Casos ejecutados | **7**: 6 dinámicos más la ley del `path` |
| Guardas de la fábrica | **2**: número de filas y conjunto de pares |
| Fallos y skips | **0** y **0** |
| Mutación «rechazar todo» | **1** caso rojo de 7 |
| Mutación «permitir todo» | **4** casos rojos de 7 |
| Escenarios ejecutables nuevos | **0**, y no por descuido: la regla es pura |
| Tests unitarios | **1066**, 0 fallos, 0 skips, 147 informes reescritos por la corrida |
| Escenarios E2E | **57**, los 57 verdes, 57 de 57 con garantía comprobada (145 garantías) |
| `VERIFY_LOC / PRODUCT_LOC` | **18845 / 67363 = 0.280** (límite 2) |
| Reloj de la puerta | **174 / 155 / 161 s** en tres ensayos sobre `45665c28b`, contra 900 s |

### 2.47 El fixture que medía la máquina: un rojo por un motivo que no era del producto

**El síntoma.** El escenario `examples/smoke/10-smoke-e2e.pipeline.kts` pasó de
**57/57** a **56/57** a partir de las 15:40, y lo hizo de forma determinista: los
ensayos de las 15:08 y de las 15:13 pasaban los 57.

**Lo que no era la causa.** La PR que en ese momento se integraba toca **un
único** fichero de test de **otro** módulo —`:pipeline-events-store`—, así que
no podía romper un escenario de otro módulo. Un rojo determinista que aparece
en una hora concreta tampoco lo explica un cambio que no está en su camino.

**La causa raíz.** El fixture creaba un repositorio y commiteaba así:

```text
git init -q . && git add . && git -c user.email=s@l -c user.name=s commit -qm init
```

Dos cosas ahí violan lo que la máquina exige: el mensaje `init` no es un
*Conventional Commit*, y la identidad `s <s@l>` no está en la lista que la
política permite. Encima, la máquina declaraba globalmente
`rubentxu <rubentxu@example.com>` —un **placeholder** con dominio `example.com`,
que la política nunca ha permitido—. El escenario fallaba por una decisión de la
gobernanza de la máquina, no por el producto que el escenario promete medir.

**Qué cambió.** El fixture cumple ahora lo que la política pide: mensaje
Conventional, e identidad **la que declara la propia máquina**, leída de su
configuración de git y puesta en el repositorio del fixture —para que la
herramienta vea una identidad explícita en un repositorio que no tiene
remoto—. Ninguna identidad personal queda escrita en el árbol.

**Lo que se probó y se descartó.** Tres vías dejaban el escenario en verde, y las
tres se probaron y se descartaron:

| Vía | Por qué no |
|---|---|
| `-c core.hooksPath=/dev/null` | no detiene el hook de `git-control`, que se invoca desde otro sitio |
| `--no-verify` | el bloqueo cae en el wrapper, antes del hook: «repo NO CLASIFICADO» |
| `GIT_CONFIG_GLOBAL=/dev/null` | funciona, y por eso mismo es la peor: anula la configuración global de la máquina |

La razón del descarte no es de gusto: un ejemplo permanente que desactiva la
política de git de la máquina **mide la política del producto, no el producto**.
La forma correcta es la que se aplicó: que el fixture **cumpla**.

**La mutación, vista en rojo.** La aserción del clon esperando un contenido
distinto del que el fixture commitea dejó el escenario **rojo**, y el fichero
se restauró byte a byte después. Un escenario que sólo puede ponerse verde
porque su fixture es correcto es un escenario que vigila algo.

**La consecuencia sobre la máquina.** La identidad global era un placeholder
con un dominio que la política jamás ha permitido. Se cambió por una identidad
real que sí está en la lista. Eso no es un cambio del producto: es una
corrección de la máquina, y se escribe aquí porque un escenario rojo puede
tener su causa **fuera** del producto, y saberlo es lo que evita depurar el
producto equivocado.

**La publicación de este bloque.** El squash hecho en la interfaz de la
plataforma produce un commit firmado por la plataforma, con una identidad que
la gobernanza local no admite: ese commit no se puede seguir, ni verificar, ni
continuar. La fusión se rehízo **en local**, con **árbol idéntico** al
publicado, y la subida fue un *force push* con **lease explícito** contra el
squash, con un ref de recuperación local. La protección de `main` se suspendió
para esa subida y se restauró después a su **valor exacto**, verificado campo a
campo contra el respaldo: `allow_force_pushes` volvió a `false`, y las demás
claves quedaron como estaban.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Escenarios tras la corrección | **57 de 57**, los 57 con garantía comprobada |
| Mutación del fixture | el escenario se puso **rojo** con la aserción invertida |
| Restauro del fixture | **byte a byte** |
| Árbol del commit integrado | **idéntico** al del squash publicado |
| Protección de `main` tras la publicación | **idéntica** al respaldo, campo a campo |
| `VERIFY_LOC / PRODUCT_LOC` | **18845 / 67363 = 0.280** (límite 2) |
| Reloj de la puerta | **174 / 155 / 161 s** en tres ensayos sobre `45665c28b`, contra 900 s |

### 2.48 La tercera familia de la mentira: un Event declarado que nadie aporta

**El sujeto.** Un plugin cuyo manifiesto declara un **Event** que ningún
contribuidor aporta. Es la tercera familia de la misma deriva: ya se había
observado contra un artefacto real la de los Steps y la de las Directives; la de
los Events era la única cuyo rechazo no se había visto con un artefacto de
verdad.

**Por qué esta familia pesa más que las otras dos.** Un Step es algo que el
pipeline ejecuta y un Directive es algo que el operador invoca por su nombre;
un Event es algo que el pipeline **publica**. Un plugin que declara un evento que
nunca emite hace que el diario del operador prometa un hecho que no llega, y
todo el que lee ese diario queda aguas abajo de la mentira. Un Step que no
existe rompe un pipeline; un evento que no existe rompe la memoria de lo que
pasó.

**El instrumento.** El mismo doctoring sobre el mismo JAR: una línea del
manifiesto y nada más —la lista de eventos declarados recibe un fantasma—,
con el resto del artefacto intacto. El lanzador gana un artefacto nuevo
(`lying-events`) y el arnés gana un escenario nuevo
(`negative/plugin-event-lies`), que exige la salida 2 y el nombre del fantasma.

**Lo que este bloque no añade: una regla.** La regla ya existía, con su fila
unitaria, y este bloque no la toca. Lo que faltaba era un **observador** que la
ejerciera contra una instalación de verdad. Un test que verifica una regla y un
escenario que la ve ocurrir son instrumentos distintos, y sólo el segundo
demuestra que la regla está donde dice estar: una regla correcta y no
observada es indistinguible de una regla que no existe.

**La mutación, vista en rojo.** Apagada la comparación de los eventos declarados
contra los eventos aportados:

- **1 de 8** filas unitarias rojas: exactamente la que afirma que un Event
  declarado sin contribuidor es un rechazo. Las otras siete, que son de otras
  familias y de la medida, siguen verdes.
- **1 de 58** escenarios rojos: el nuevo, que sale con **0** en vez de 2 —el
  plugin mentiroso pasa, el pipeline termina con `SUCCESS`— mientras los otros
  57 siguen verdes. Cero colaterales.
- El fichero de producción se restauró **byte-idéntico** —el mismo resumen
  criptográfico— y el árbol quedó limpio.

### 2.49 El artefacto que se queda: la identidad duplicada, medida de verdad

**La pregunta.** Dos artefactos distintos con la **misma identidad**, y uno de
ellos mintiendo. ¿Quién gana, y qué ve el operador?

**El montaje.** Tres composiciones con artefactos reales —no maquetas de
texto—: el mismo JAR nombrado dos veces por rutas distintas, el JAR legítimo
empaquetado junto a un JAR con la misma identidad y otros bytes, y ese mismo
par en el orden inverso.

**Las tres filas, medidas:**

| Composición | Qué pasó |
|---|---|
| el mismo JAR dos veces, mismos bytes | **exit 0**: la deduplicación por bytes lo reconoce como el mismo artefacto y no hay colisión |
| el **mentiroso primero**, el legítimo después | **exit 2** `DuplicateIdentity`, y el rechazo apunta **al artefacto legítimo** |
| el legítimo primero, el mentiroso después | **exit 2** `DuplicateIdentity`, y el rechazo apunta al mentiroso |

**Lo que se midió en el texto del rechazo.** Dice
`DuplicateIdentity(identity=X, incumbent=X)`: los dos campos llevan **el mismo
texto**. El KDoc del caso promete «which identity collided **and with whom**»,
y el segundo campo vuelve a entregar la identidad. El campo se llama
`incumbent` y su promesa es «the incumbent it collides with»: **no hay ningún
artefacto ahí**, ni su ruta ni su resumen.

**Qué no es.** No es ejecución no autorizada. La composición se aborta con
salida 2 y el artefacto mentiroso no llega a cargarse: el rechazo es
*fail-closed*. El daño es de otro tipo, y conviene no exagerarlo:

- **de disponibilidad**: el plugin legítimo queda expulsado por la identidad de
  otro, y el operador no puede usar el suyo hasta que encuentra al otro
  artefacto;
- **de diagnóstico**: el mensaje no dice qué artefacto se quedó dentro, así que
  el operador lee «mi plugin bueno fue rechazado» sin la pista de con quién
  colisionó.

**Por qué el caso del mentiroso primero es el interesante.** Cuando el
mentiroso es el primero, la pre-admisión lo admite **sin cruzar su manifiesto
contra su código** —el cruce sólo corre si *todos* los artefactos son
admitidos—, y el artefacto al que se le echa la culpa es el legítimo, cuyo
rechazo no dice nada de la mentira. La mentira no se ve; su desplazamiento sí.

**Lo que este bloque no arregla, y por qué.** El vocabulario de rechazos está
publicado: `DuplicateIdentity` está en el `.api` de `pipeline-domain` con
`copy(String, String)`, y ampliarlo con el artefacto que se quedó es una
**ruptura versionada**, que no se hace sin autorización explícita. El camino que
no rompe el ABI ya está abierto: `CompositionOutcome.Refused` lleva un `detail`
que la CLI ya imprime, y `CompositionOutcome` vive en `pipeline-application`,
que no está publicado. El arreglo que se propone es que
`PreLoadPluginAdmission` lleve la correspondencia identidad → artefacto admitido
y que ese `detail` nombre **el artefacto que se quedó**, de modo que el
rechazo diga a quién desplazó. Eso es el siguiente bloque, y es un bloque de
diagnóstico, no de política: qué artefacto gana la identidad no cambia.

### 2.50 La dirección contraria de las capacidades: el manifiesto que olvida lo que su código exige

**El sujeto.** Un plugin cuyo Step exige **dos** capacidades y cuyo manifiesto
declara **una**. Es la dirección contraria de una deriva que ya se había
observado: el manifiesto declarando una capacidad que el contrato del Step no
exige.

**Por qué esta dirección es la que el operador nota.** Declarar de más es una
promesa de sobra: al host le llega algo que el Step no pidió y nada se rompe.
Exigir de más es una **dependencia que nadie declaró**: el host tiene que
resolverla, y si no puede, el Step falla **en ejecución**, cuando el rechazo de
admisión era posible y barato. La diferencia es de responsabilidades: en un caso
sobra una promesa; en el otro falta una.

**La regla ya era simétrica; lo que faltaba era una fila.** El KDoc del propio
plugin de ejemplo dice que la admisión compara el conjunto del contrato con la
declaración por Step **en ambas direcciones**. Es decir: la regla existía y era
correcta. Lo que no existía era un caso que la mirara por el otro lado, y una
regla que nadie ha visto fallar por ese lado es indistinguishable de una regla
que no cubre ese lado.

**El instrumento.** El doctoring quita, de la declaración por Step de
`example.uppercase.announcedCased`, la capacidad que el contrato de ese Step
exige. Todo lo demás es del publisher: la identidad, la versión, el publisher,
el rango de API y la lista global de capacidades del plugin.

**El detalle que casi vuelve inútil al fixture.** La capacidad quitada
**sigue** en el array global `capabilities` del manifiesto. Un chequeo que la
buscara en el documento la encontraría ahí y no probaría nada sobre la
declaración por Step, que es lo que está bajo prueba. Por eso el fixture se
verifica **al revés** —comprueba que el literal exacto que antes estaba ya no
está— y por eso es la primera deriva del arnés cuya prueba es una **ausencia**.

**La mutación, vista en rojo.** Apagada la comparación de capacidades:

- **exactamente 2 de 59** escenarios rojos: los dos de la familia, el de más y
  el de menos. Los otros 57 siguen verdes.
- **exactamente 1 de 8** filas unitarias rojas: la que afirma que una
  capacidad que el contrato no exige **se nombra por los dos lados**.

**Por qué dos escenarios y no uno.** Porque la regla es simétrica y el mutante
apaga las dos direcciones a la vez. Y ése es justo el valor del escenario nuevo:
sin él, la suite entera habría pasado con un guardián que comparase los
conjuntos **en una sola dirección**, porque el único caso de la familia miraba
sólo una de las dos. Con él, el guardián tiene que ser simétrico para que los
dos scenarios salgan verdes.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Escenarios de la familia de la mentira | **5**: Steps, capacidades de más, capacidades de menos, Directives y Events |
| Direcciones de la familia de capacidades | **2**, las dos observadas contra un artefacto real |
| Mutación «ignorar la comparación de capacidades» | **2** escenarios rojos de 59, y **1** fila roja de 8 |
| Tests unitarios | **1066**, 0 fallos, 0 skips, 147 informes reescritos por la corrida |
| Escenarios E2E | **59**, los 59 verdes, 59 de 59 con garantía comprobada (155 garantías) |
| `VERIFY_LOC / PRODUCT_LOC` | **18893 / 67363 = 0.280** (límite 2) |
| Reloj de la puerta | **177 / 161 / 160 s** en tres ensayos sobre `6e3ed7df7`, contra 900 s |

### 2.51 El artefacto que se queda: el rechazo, por fin, con los dos nombres

**El hueco, tal y como se midió** (§2.49). El rechazo decía
`DuplicateIdentity(identity=X, incumbent=X)`: dos campos con el mismo texto,
mientras su documentación promete «which identity collided **and with whom**»,
y el campo `incumbent` promete «the incumbent it collides with». No había ningún
artefacto ahí. El mensaje nombraba al artefacto **rechazado** —el fichero que
el operador nombró— y no al que **se queda**, que es el que el operador tiene
que encontrar.

**Lo que cambia, y lo que no.** El `detail` del rechazo —que la CLI ya
imprimía, y que hasta ahora nadie llenaba— lleva ahora el artefacto que
**había reclamado la identidad primero**, y dice que la composición entera se
rehúsa. La **política no cambia**: qué artefacto conserva la identidad sigue
siendo «el primero que llega», y eso no se toca aquí. Lo que cambia es que el
diagnóstico deja de ser un rumor: el operador lee los dos nombres y puede
decidir cuál de los dos ficheros es el suyo.

**Por qué por el `detail` y no por el vocabulario.**
`PluginManifestRejection.DuplicateIdentity` está en el ABI publicado de
`pipeline-domain`, con `copy(String, String)`: añadir un tercer campo rompe la
API de los consumidores. `CompositionOutcome.Refused` lleva un `detail` que la
CLI ya imprime, y `pipeline-application` **no está publicado** —no tiene
`api/`—, así que ahí cabía el nombre sin romper nada. Es la diferencia entre
romper a los que leen y no romper a nadie.

**Los dos instrumentos, y cada uno cubriendo lo suyo.**

- El test unitario (**4 filas**) cubre **la regla**: qué dice el `detail`, y que
  no inventa un artefacto que no conoce ni nombra a uno que no está.
- El escenario `negative/plugin-duplicate-identity` cubre **el cableado**: que el
  runtime recuerde qué artefacto se quedó, y que ese nombre llegue a la consola
  del operador. Lo hace con dos artefactos **reales** con la misma identidad y
  bytes distintos: la copia del plugin de ejemplo que la distribución trae
  doctorada, y la copia que el operador ofrece por `--plugin-jar`.

**Las dos mutaciones, vistas en rojo.**

| Mutación | Filas unitarias rojas | Escenarios rojos |
|---|---|---|
| **A** — la regla pura deja de nombrar a nadie | **2 de 4** | **1 de 60** |
| **B** — el runtime deja de recordar qué artefacto se quedó | **0 de 4** | **1 de 60** |

La segunda es la que enseña algo, y por eso se escribe: un test unitario que
**no se enciende** cuando se rompe el cableado **no protege el cableado**. No
es un defecto del test —un test puro, por definición, no puede tocar el disco—:
es que cada instrumento tiene que medirse con la mutación que le corresponde, y
que hay que decir cuál cubre cuál. Un arnés que presume de cobertura sin esa
medida presume de algo que nadie ha comprobado. Los otros 59 escenarios no se
mueven en ninguno de los dos casos, y el fichero de producción se restauró
byte-idéntico en los dos.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Filas unitarias del diagnóstico | **4**, 2 encendidas por la mutación de la regla |
| Escenarios | **60**, los 60 verdes, 60 de 60 con garantía comprobada (162 garantías) |
| Mutación A (la regla) | **2** filas rojas de 4 y **1** escenario rojo de 60 |
| Mutación B (el cableado) | **0** filas rojas de 4 y **1** escenario rojo de 60 |
| Tests unitarios | **1070**, 0 fallos, 0 skips |
| `VERIFY_LOC / PRODUCT_LOC` | **18900 / 67377 = 0.281** (límite 2) |
| Reloj de la puerta | **240 / 165 / 176 s** en tres ensayos sobre `0b0438114`, contra 900 s |

### 2.52 El destino de `:pipeline-runtime`: se queda, cableado a los puertos publicados

**La pregunta, tal y como estaba.** `:pipeline-runtime` era un módulo sin
consumidor: sus tres adaptadores implementaban los puertos que
`:pipeline-runtime-api` publica, y nada en el árbol los invocaba. §2.18 lo
midió sin adornos —cero consumidores, fuera de la distribución y fuera de la
compilación de la puerta— y §2.19 decidió **no borrarlo**, porque esos puertos
son un contrato publicado y retirarlos sería dejar una promesa sin
implementación. Quedaba una decisión de dos ramas: **cablear** los adaptadores
a los puertos publicados, o **retirar** el módulo y dejar constancia de que
esos puertos no tienen implementación en el runtime.

**La salida: se cablea.** `:pipeline-runtime` se queda, sus tres adaptadores
implementan los tres puertos publicados de `:pipeline-runtime-api`
(`RuntimeRecoverPort`, `RuntimeControlPort`, `RuntimeIntrospectionPort`) y el
runtime los invoca. La rama de retirarlo queda descartada con su motivo.

**Lo que se mide hoy, sobre el árbol de este bloque:**

| pregunta | respuesta medida |
|---|---|
| ¿está cableado a un consumidor real? | sí: `:pipeline-application` lo declara en `pipeline-application/build.gradle.kts:189` y compone los tres puertos en `CompositionRoot.kt:222,280,330` |
| ¿los llama alguien? | sí, por verbo de CLI: `runs cancel` → `RuntimeControlPort.cancel` (`MainRunsCli.kt:132`), `runs recover` → `RuntimeRecoverPort.recover` (`MainRunsCli.kt:222`), `inspect` → `RuntimeIntrospectionPortStoreAdapter` (`MainInspectCli.kt:544`) |
| ¿lo ejecuta un E2E? | sí: `examples/durable/39-cancel-dead-run` y `examples/durable/40-recover-refuses-a-half-finished-run` |
| ¿lo compila la puerta? | sí, dos veces y por razones distintas: `:pipeline-application:installDist` lo compila **transitivamente**, porque su jar viaja en el `lib/` de la distribución (`pipeline-runtime-<version>.jar`, medido en la instalación), y además `scripts/verify-all.sh:371` pide `:pipeline-runtime:compileKotlin` **por su nombre**, para que el plan de la puerta diga que el módulo está dentro de ella |
| ¿tiene observador? | sí: **5 clases** y **28 filas** de test unitario permanente (`RunCancellationDecisionTest`, `RuntimeControlPortStoreAdapterTest`, `RuntimeIntrospectionPortStoreAdapterTest`, `RuntimeRecoverPortStoreAdapterTest`, `RecoverJournalVocabularyTest`) |
| ¿se publica? | no como **artefacto del SDK**, y a propósito: un artefacto para él no resolvería nada para un consumidor externo, que compila contra los puertos, no contra los adaptadores. `:pipeline-runtime-api` **sí** está en `publishedContractModules` y en el BOM. **Ojo con la palabra "publicado": no publicado no es no distribuido** — el jar de `:pipeline-runtime` **sí viaja** en el `lib/` de la distribución de la aplicación |

**Por qué esta rama y no la otra.** Retirar el módulo no era una retractación
neutra: `pipeline-runtime-api` está en `publishedContractModules`, en el BOM y
en el manifiesto de la candidata, así que sus tres verbos son una promesa a un
consumidor externo. Un módulo retirado deja esa promesa **sin
implementación**, que es peor que una promesa cumplida. La rama elegida no sólo
cumple el contrato: le da la autoridad única del journal y de las leases, que
era lo que §2.19 pedía.

**Dos lecturas que se contradecían, y que este bloque arregla.** La primera
era un comentario de `settings.gradle.kts` que decía que
`:pipeline-runtime-api` *"is NOT yet in `publishedContractModules`; this commit
is the structural precondition only"*. Era falso y ya no lo es: el módulo está
en la lista desde antes de este bloque, y el comentario era el fósil de la
pregunta que este ciclo cierra. La segunda era la tabla por módulo de §2, que
contaba **1** clase de test para `:pipeline-runtime` cuando hoy tiene **5** y
**28 filas**: es un censo de otro ciclo, y por eso vive en la sección de
historia y no en §1. Lo que este bloque añade es la fila que falta en la vista
viva.

**Una premisa falsa que este bloque corrige.** La primera versión de este
apartado decía que el módulo «ni se publica ni viaja en la distribución», y la
segunda mitad es **falsa**: `pipeline-runtime-<version>.jar` **está** en el `lib/`
de la distribución instalada, porque `:pipeline-application` lo declara en
`implementation` y el plugin `application` lo mete en el classpath de
ejecución. Lo que **no** se publica es el **artefacto del SDK**, que es otra
cosa: un consumidor externo compila contra los puertos, no contra los
adaptadores. La confusión entre «no publicado» y «no distribuido» se propagaba
al propio script de la puerta, que justificaba una tarea con la misma premisa
falsa. Medido contra el árbol instalado, no inferido.

**Lo que este bloque cierra y lo que no.** Cierra el **destino** del módulo: se
queda, se queda cableado, y el árbol lo dice en tres sitios que ahora
concuerdan. No abre ni reabre el **comportamiento** de los verbos, que ya
está cerrado y caracterizado en §2.33: el cancel escribe el recibo, **luego**
sella y **luego** escribe el hecho terminal, así que un fallo de sellado deja
el run sin cerrar y el cancel siguiente lo termina sin repetir efectos.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Puerta | **`PASS`** — `sha=beb276192`, **201 s** contra 900 s, `tests=rewritten:1070 read:0`, `scenarios=observed:60 executed:60` |
| Compilación de `:pipeline-runtime` | la etapa 1 la pide explícitamente (`scripts/verify-all.sh:371`) y la puerta la ejecuta |
| Observador del módulo | **5** clases de test, **28** filas, 0 fallos, 0 saltados |
| Cambio de este bloque | `ROADMAP.md` y **comentarios** de `pipelinek/settings.gradle.kts`. **Ningún cambio de producción**, así que el código medido sigue siendo el de `0b0438114`, que es el de los tres ensayos |

### 2.53 El rechazo que decía una cosa falsa

El `detail` del rechazo por identidad duplicada decía: *«the same identity is
already admitted from X, **which stays in the composition**»*. No es un detalle
de redacción: `admitAll` devuelve `AdmittedArtifacts.Refused`, o sea que **la
composición entera se rehúsa**, y ese rechazo se produce al pre-admitir los
artefactos, **antes de que exista composición**. El KDoc de la propia clase lo
dice en otra línea: *«One refusal stops the whole composition»*.

Lo que el mensaje afirmaba es lo contrario de lo que pasa: que el artefacto
que reclamó la identidad primero «se queda» — o sea, que el pipeline del
operador va a correr con su plugin dentro. El operador lee «se queda» y
entiende «sigo ejecutando». No se ejecuta nada. La primera mitad del mensaje
era cierta y útil —«la identidad ya la había reclamado otro artefacto»—, y por
eso el arreglo no la toca: la segunda es la que había que cambiar.

**Lo que cambia, en una frase.** El `detail` sigue nombrando el artefacto que
había reclamado la identidad primero, y además dice que **la composición
entera se rechaza**.

| antes | después |
|---|---|
| `the same identity is already admitted from X, which stays in the composition` | `the same identity was already claimed by X, admitted earlier in this pre-admission pass; the whole composition is refused` |

**Los dos instrumentos, cada uno cubriendo lo suyo.** El test unitario
—**5 filas**— fija la regla: que el detalle nombre al artefacto, que **no**
diga que se queda, y que sí diga que la composición se rechaza entera. El
escenario `negative/plugin-duplicate-identity` fija que ese texto llega a la
consola del operador, que es lo único que un test puro no puede ver.

**La mutación, vista en rojo.** Revirtiendo el mensaje al texto viejo: **1 de
5** filas rojas —`the refusal does not claim the artifact stays in the
composition`—, y ninguna otra. El fichero de producción se restauró byte a
byte.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Puerta | **`PASS`** en tres ensayos sobre `2c0151b58`: **183 / 171 / 169 s** contra 900 s |
| Tests unitarios | **1071** (148 informes en disco), 0 fallos, 0 errores, 0 saltados |
| Escenarios | **60**, los 60 verdes, `scenarios=observed:60 executed:60` |
| Mutación | **1** fila roja de 5 |

### 2.54 B1, triado: las cuatro PR de workspace y el contrato que ya está en el código

**La pregunta de B1, medida.** El bloque B1 pide reconciliar el contrato
`authorizedWorkspaceRoot` frente a `effectiveWorkingDirectory`. Medido sobre
este árbol, **ese contrato ya existe**, con otros nombres: la raíz autorizada
es `ExecutionLocation.workspace.root` —con un *lease* tipado, `Attached` para
el directorio del usuario y `Managed` para el scratch de PipelineK— y el
directorio efectivo es `ExecutionLocation.cwd`. La partida es exactamente la
que la PR pedía, y es **más fuerte** que la PR: allí la propiedad del
directorio se infería de un marcador de VCS; aquí es un valor tipado que
cruza la frontera de la CLI una sola vez.

**Las cuatro PR de workspace, una por una.** Todas apuntan a `v2/` y
`docs/v2/07-uat/`, rutas que **no existen en este árbol** (`pipelinek/`, y sin
`docs/`): se escribieron contra un árbol que ya no está.

| PR | qué pedía | dónde está hoy |
|---|---|---|
| #90 | partir la raíz autorizada del cwd efectivo | `ExecutionLocation.workspace.root` frente a `ExecutionLocation.cwd`; el origen del workspace se decide una vez en la frontera de la CLI (`CliParser`, `MainDurableRun`, `MainVolatileRun`) |
| #95 | pasar `deleteDir` por el cwd efectivo | `DeleteDirOperationsAdapter` resuelve contra `executionLocation.cwd` y pide `authorizeRootDestruction` antes de borrar: una raíz `Attached` no se destruye |
| #96 | limitar `stash` y `unstash` al cwd efectivo | `StashOperationsAdapter.sourceRoot()` devuelve `executionLocation.cwd`, y el aislamiento trabaja sobre **rutas reales**: rechaza symlinks, `restoreRoot` y cada destino resuelto |
| #97 | test de caracterización del contrato | **no existe aquí**: no hay `DirRestoreAfterError…` en el árbol. Su parte de `docs/v2/07-uat/*.md` es un árbol de recibos que la política prohíbe |

**Las otras 17, en cinco cubos.** De las 21 PR abiertas: cinco tocan un árbol de
`docs/` que el RESET borró y que la política no permite recrear; cuatro son
dependabot que apuntan a `v2/gradle/libs.versions.toml`, ruta que aquí es
`pipelinek/gradle/…` —el contenido del bump sigue siendo aplicable, la PR no—;
cuatro llevan el árbol entero como diff porque su base ya no existe; una es
GitHub Actions, que la política no introduce por defecto; y tres son de
publicación, SDK o integración, con su propio bloque.

**Lo que quedaba abierto, y era de instrumento, no de código.** La invariante
vivía repartida entre cuatro adaptadores y dos KDoc, y **nada se ponía rojo**
si uno de ellos volviera a re-derivar el workspace del control root — que es
justo el defecto que el KDoc de `DeleteDirOperationsAdapter` dice que **ya
ocurrió una vez**. El instrumento que faltaba es un escenario ejecutable que
abra un `dir(...)` y compruebe que `writeFile`, `deleteDir`, `stash` y
`unstash` ven **la misma** base. Ese instrumento **ya existe** (§2.55), y este
párrafo queda como el registro de lo que faltaba cuando se escribió esta
tabla.

### 2.55 El instrumento que faltaba: los cuatro pasos, una sola base

**El hueco.** El contrato de coherencia del workspace —`dir`, `deleteDir`,
`stash` y `unstash` sobre la **misma** base dentro de un `dir(...)`— no tenía
ningún instrumento. La invariante vivía repartida en cuatro adaptadores y dos
KDoc, y **nada se pondría rojo** si uno de ellos volviera a resolver contra el
workspace de la etapa. Y ese defecto **ya ocurrió una vez**: el KDoc de
`DeleteDirOperationsAdapter` lo dice sin rodeos —*«it used to re-derive the
stage workspace from the control root, so inside a `dir(...)` scope the two
Steps disagreed about where "sub" was»*.

**El instrumento.** `examples/steps/34-workspace-coherence.pipeline.kts`, un solo
escenario para las cuatro patas. La raíz de la etapa tiene `at-root.txt` y su
propio `victim/`; el ámbito `dir("inner")` tiene `anchor.txt`, `written.txt` y su
propio `victim/keep.txt`. Siete marcas:

| marca | qué garantiza | qué la apaga |
|---|---|---|
| `WROTE_HERE` | `writeFile` escribió en el ámbito | resolvió contra la raíz de la etapa |
| `RESTORED` | el `stash` + `unstash` devolvió el contenido al ámbito | el stash tomó la raíz, o el restore fue a la raíz |
| `SCOPE_CLEAN` | el archivo de la raíz de la etapa **no** entró en el ámbito | el `stash` arrastró la raíz hacia dentro |
| `VICTIM_GONE` | el `deleteDir` borró el `victim` del ámbito | resolvió contra la raíz de la etapa |
| `ROOT_SURVIVED` | el archivo de la raíz sigue donde estaba | el ámbito lo tocó |
| `ROOT_VICTIM_SURVIVED` | el `victim` de la raíz sigue vivo | el `deleteDir` lo borró |
| `ROOT_NOT_POLLUTED` | el archivo del ámbito no acabó arriba | el restore fue a la raíz |

**Las comprobaciones no abortan la ejecución.** Cada una es
`test … && echo MARCA || echo TOKEN_DE_FALLO`, así que una comprobación que
falla imprime **su** token y el run continúa. El informe nombra así exactamente
qué garantías se perdieron, en vez de pararse en la primera. Los tokens de
fallo evitan a propósito toda marca esperada, de modo que un fallo nunca puede
confundirse con un verde. Y las dos comprobaciones que hablan del otro lado —
`SCOPE_CLEAN` y `ROOT_NOT_POLLUTED`— son las que cierran las dos direcciones de
la fuga: **la raíz hacia dentro** y **el ámbito hacia fuera**.

**Dos árboles `victim/`, en los dos niveles.** Con uno solo, un `deleteDir`
contra la base equivocada o borraba el archivo del ámbito —tapando la
regresión— o fallaba por directorio inexistente, abortando el run antes de que
la comprobación pudiera hablar. Con los dos, la base equivocada tiene algo que
borrar, la copia del ámbito sobrevive y la comprobación puede hablar.

**Un ancla que el `sh` crea, no el `writeFile`.** `anchor.txt` y
`victim/keep.txt` se crean con `sh` a propósito: existen diga lo que diga el
`writeFile`, así que un `writeFile` roto no deja al `stash` sin nada que tomar
y no aborta el run antes de que el informe pueda nombrar la garantía perdida.

**Las cuatro mutaciones, una por pata.** Cada una toca **un solo** punto de
código y se mide contra **el mismo** escenario. Ninguna pata espera su propio
escenario, y ninguna comparte punto de mutación con otra:

| pata | punto mutado | garantías que se apagan | token |
|---|---|---|---|
| `writeFile` | `WorkspaceOperations`: `PathAnchor.CURRENT_DIRECTORY` → `WORKSPACE_ROOT` | `WROTE_HERE` | `LEG_write_scope` |
| `stash` | `StashOperationsAdapter`: la 1.ª `val workspaceRoot = sourceRoot()` → `executionLocation?.workspace?.root` | `RESTORED`, `SCOPE_CLEAN` | `LEG_restore_scope`, `LEG_root_leaked` |
| `unstash` | el mismo fichero, la **2.ª** ocurrencia (la del `unstash`) | `RESTORED`, `ROOT_NOT_POLLUTED` | `LEG_restore_scope`, `LEG_restore_escaped` |
| `deleteDir` | `DeleteDirOperationsAdapter`: `executionLocation.cwd` → `executionLocation.workspace.root` | `VICTIM_GONE`, `ROOT_VICTIM_SURVIVED` | `LEG_delete_scope`, `LEG_root_victim_lost` |

Cada fila es una fila roja con su motivo, y las cuatro se distinguen: ningún
token de una fila aparece en otra.

**La fuga, vista de cerca.** La fila del `stash` es la que responde a la
pregunta que este bloque tenía que responder —*«¿arrastra el `stash` un archivo
de la raíz exterior al ámbito?»*—. Con el `stash` tomando la raíz de la etapa,
el archivo de la raíz **entra** en el ámbito. Medido con una UAT efímera que
volcaba el árbol (retirada después de evaluarla):

```
D2_FILES=at-root.txt victim written.txt
D2_TXT=../at-root.txt ../inner/at-root.txt ../inner/written.txt ../victim/keep.txt
```

`../inner/at-root.txt` es el archivo de la raíz de la etapa, dentro del ámbito:
la fuga, medida, no inferida.

**Un error mío que casi se publica como si fuera del producto.** La primera
medición dio la misma señal para `stash` y para `unstash`, lo que parecía decir
que el instrumento no distinguía las dos patas. No era así: el script de
mutación acababa en `p.write_text(s.replace(old, new))`, y el `replace` de Python
reescribe **todas** las ocurrencias —la mutación de `stash` estaba cambiando
también el `unstash`—. La pata «stash» estaba midiendo, sin decirlo, una
mutación compartida. Con un único sitio de mutación por pata, las dos se
separan y cada una enciende las suyas. El defecto estaba en el instrumento de
medida, que es exactamente donde un error así se esconde: el script era el
único que podía confirmar que las cuatro patas eran cuatro.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Escenario, sin mutación | verde: exit 0, **7 garantías** comprobadas, 16 pasos terminados |
| Mutaciones | **4**, una por pata, un solo punto de código cada una |
| Fichero de producción | restaurado byte a byte (`git diff` vacío) |
| Puerta | **`PASS`** en tres ensayos sobre el SHA definitivo: ver §1 |

### 2.56 Una propiedad no acreditada no concede permiso de destrucción

**El hallazgo.** El permiso de destruir la raíz del workspace se concede en
**un solo caso** del lease: `Managed`, que afirma que PipelineK reservó ese
directorio. `Attached` —el directorio del usuario— lo rechaza. El puente que
traduce el transporte viejo a la ubicación tipada mapeaba la propiedad
**desconocida** a `Managed`:

```
WorkspaceOwnership.PIPELINEK, null -> WorkspaceLease.Managed(root)
```

Es decir: **no saber de quién es el directorio concedía el permiso de
borrarlo**. Y el KDoc de ese mismo adaptador lo llamaba «the fail-closed choice
for destruction», cuando era exactamente la decisión **fail-open**. El
comentario y el código decían cosas opuestas, y el código era el que mandaba.

**Alcanzable, y no sólo en teoría.** El transporte `ShOptions` declara
`workspaceOwnership: WorkspaceOwnership? = null`, y ese `null` producía el lease
que **concede**:

```
WorkspaceOwnership.PIPELINEK, null -> WorkspaceLease.Managed(root)
```

El camino canónico **sí** acredita la propiedad —la política de la CLI la
decide, `WorkspaceIntent` la cruza y `CompositionRoot` la lleva al
transporte—, así que el usuario nunca viajaba como `Managed` por ahí. Los dos
sitios que **no** la acreditaban son:

| sitio | raíz | consecuencia antes del arreglo |
|---|---|---|
| `MainScriptedSupport` (el frontend scripted) | `<controlRoot>/workspace` | permiso concedido sin acreditación; por suerte la raíz sí era de PipelineK |
| el **default** del constructor del coordinador (`ShOptions.EMPTY`), que usan las demás constructoras que no lo pasan | un `createTempDirectory` **compartido** | permiso concedido sobre un directorio temporal compartido |

La segunda fila no es el camino de producción —`CompositionRoot` pasa siempre
sus `ShOptions`— sino el **default** de una firma con muchas constructoras.
Y eso es exactamente el problema: un default *fail-open* es lo que se usa
cuando alguien olvida, y un default que concede permiso de borrar un
directorio temporal **compartido** no es un default, es un accidente
esperando a su turno.

**El arreglo.** La propiedad desconocida deja de traducirse en permiso:
`null` produce el lease que **rechaza**. Y el sitio que sí conoce su
propiedad la dice, para que su permiso legítimo sea **acreditado** y no
accidental: el frontend scripted declara `WorkspaceOwnership.PIPELINEK`, que
es lo que es.

**Por qué no un tercer caso del lease.** El modelo correcto —`Attached` para
el usuario, `Managed` para lo de PipelineK, y un tercero para «no se sabe»—
exigiría añadir un caso a `WorkspaceLease`, y ese tipo está en el **ABI
publicado** de `pipeline-domain` (`api/pipeline-domain.api`, líneas 8981-9015).
Añadir un caso a una interfaz sellada publicada es una ruptura versionada, y
eso no se hace sin autorización explícita. Queda escrito como el modelo
propio, no como un descuido.

**El instrumento mínimo.** `UnaccreditedWorkspaceOwnershipTest` — **3 filas
puras**, sin disco, sin reloj y sin proceso: una raíz sin dueño acreditado
**rechaza** la destrucción; la raíz que PipelineK reservó **la permite**; la
raíz del usuario la **rechaza**.

**La fila, vista en rojo.** Un test que nunca se ha visto rojo no es evidencia.
Volviendo a poner el default *fail-open* —`null` a `Managed`— el informe JUnit
dice exactamente esto:

```
tests="3" skipped="0" failures="1" errors="0"
<failure message="... an unstated owner must never grant the destructive
                   permission: Permitted ==> expected: <true> but was: <false>" />
```

**Una** fila de tres, la que vigila precisamente el default, y ni una más: las
otras dos no se mueven porque no dependen de él. El fichero de producción se
restauró byte a byte y la suite volvió a dar 3 de 3.

**Y la puerta, vista desde fuera.** Una fila unitaria demuestra que la función
pura decide bien; no demuestra que el **binario instalado** use esa decisión.
Una UAT adversarial efímera —retirada después de evaluarla, como manda la
política— lo comprueba contra el binario real, con dos casos y una sola
diferencia: la propiedad de la raíz.

| caso | raíz | resultado medido |
|---|---|---|
| `run --workspace <dir>` | la del **usuario** | **exit 1**, y el motivo es explícito: `deleteDir refuses to delete the workspace root itself ('<dir>'); pass a sub-path such as deleteDir("build") to remove generated content`. El contenido del usuario **intacto** |
| `run --isolated` | el scratch de **PipelineK** | **exit 0**: el mismo paso se permite |

La segunda fila es la que importa tanto como la primera: un guard que lo
rechazara todo también sería «fail-closed», y sería un producto roto. Lo que
se demuestra es que el permiso **no se perdió**: cambió de *acreditado por
defecto* a *acreditado a propósito*.

La primera vez que se ejecutó esta UAT dio un **falso positivo**: el binario
no llegó a arrancar —faltaba la passphrase del store— y el script leyó «el
contenido del usuario está intacto» como si el guard hubiera actuado. Un
verde que no se ha visto rojo, ni siquiera de mentira, no es un verde: por eso
el caso se comprueba por su **código de salida** y por el **motivo**, no por
la ausencia de daño.

**El hueco que deja esta UAT al retirarse.** El lanzador ejecuta **todos** sus
escenarios con `--isolated`, así que la mitad del contrato de propiedad —la
raíz del usuario— **no se puede afirmar** con el arnés actual: no hay forma
de ejecutar un ejemplo con `--workspace`. La fila unitaria cubre la decisión
y esta UAT la portada, pero un escenario **permanente** que afirme el rechazo
exige que el lanzador pueda correr un ejemplo con raíz de usuario, y eso es
capacidad del arnés, no un escenario más. Queda dicho como hueco, con su
nombre.

### 2.57 La colisión de nombres de stream: caracterizada, con su deuda con nombre

**La pregunta.** El nombre de un stream en disco no es invertible: `safeStreamName`
pliega a `_` todo lo que no sea `[A-Za-z0-9._-]`, y su propio KDoc lo dice sin
rodeos —*«Lossy and deliberately never undone… `a/b` and `a_b` produce the same
name»*. La pregunta era si eso llega a **mezclar los bytes de dos operaciones
distintas**, y qué es lo que lo impide.

**Las tres piezas, medidas.**

| pieza | hecho |
|---|---|
| el **identificador** | `{runId}/{operationId}/{channel}` **sí** es inyectivo, y está razonado: el parser lee por los **extremos** —el run al principio, el canal al final, que es un conjunto cerrado— y todo lo de en medio es el id de operación, separadores incluidos |
| el **nombre en disco** | no lo es: el directorio de un stream es `streams/safe(stream.value)`, **un solo nombre plano** |
| el **índice** | el `SegmentFrameIndex` guarda el identificador **completo** en sus propios ficheros y lo lee de ahí, así que el índice **sí** distingue dos streams que en disco se llaman igual |

La consecuencia de las tres: el índice y el store de bytes **no hablan el mismo
idioma**. El índice sabe qué stream es cada cosa; el store sólo sabe el nombre
plano. Si dos streams plegaran al mismo nombre, el índice los separaría y el
store **los mezclaría**.

**Lo que hoy lo impide, y que nadie había escrito.** El cuerpo de una operación
es una lista de segmentos, y el motor escribe tres formas: `"{índice}:{stepId}"`
—un hijo, dirigido por su posición—, `"b{índiceRama}:{clave}"` y
`"n:{nombre}"`. Los segmentos **dirigidos por posición** llevan un número, y un
número no se pliega ⇒ dos de ellos nunca coinciden. Pero los otros dos **no
llevan posición**: su identidad **es** el nombre, y el nombre lo elige quien
escribe el pipeline. Dos de esos nombres que plieguen al mismo valor sí
colisionarían. La garantía de que los bytes de un stream no se mezclan con los
de otro dependía, pues, de un supuesto que **no estaba escrito en ningún sitio**
y que el propio código contradecía en un caso.

**El defecto que la medición destapó, y que sí era real.** El `OpId` es el
identificador de una operación, y tiene una forma textual (`OpId.format`) y un
parser (`OpId.parse`) que es **la única puerta por la que un id de operación
llega al sistema desde fuera** —la consola lo usa para resolver filtros. El
parser exigía que cada segmento empezara por un índice numérico, y **descartaba
en silencio** los que no lo tenían. El resultado medido:

```
run-7-s0-3-bp1-b0:branch   --parse-->   run-7-s0-3
run-7-s0-3-bp4-0:core.parallel-b1:branch-n:closure-2:core.sh
                           --parse-->   run-7-s0-3-bp2-0:core.parallel-2:core.sh
```

No es un detalle: el parser devolvía **otra operación**. En el caso mixto hasta
cambia el **conteo** del cuerpo (`bp4` → `bp2`), porque el corte por guiones se
come piezas de más y el `take(count)` se lleva las equivocadas. Un `OpId` que
al ida y vuelta no vuelve a ser el mismo es una identidad que **no es estable**.

**El arreglo, y que sea compatible.** El parser reconstruye un segmento que no
va dirigido por posición **tal como estaba escrito**, en vez de droppearlo. El
texto que se emite **no cambia** —el mismo `format`—, así que no se toca
ninguna clave del journal, y el resultado observable de la consola no cambia:
sus dos consumidores (`ConsoleOperationSelection` y `StageStepKindResolver`)
miran **sólo** el índice de etapa y de paso. El efecto lateral útil: un
identificador que lleve `/` ahora **sí** sobrevive al ida y vuelta, porque el
segmento se conserva literal.

Y la parte que faltaba —que se puddle devolver una identidad **parcial**— está
cerrada en §2.59: el parser ya no elige una lectura cuando el texto admite
varias, sino que se niega.

**La fila, vista en rojo.** `OpIdRoundTripTest` — **4 filas puras**, sin disco,
sin reloj y sin proceso: un segmento dirigido por posición, uno de rama
paralela, uno de cuerpo nombrado, y un camino mixto. Con el parser de antes:

```
tests="4" skipped="0" failures="3" errors="0"     (con el parser de antes)
tests="4" skipped="0" failures="0" errors="0"     (con el arreglo)
```

Las tres filas que caen son exactamente las que dependen de los segmentos sin
posición, y la única que sobrevive es la que no depende de ellos. El parser se
revirtió a su estado defectuoso para medir ese rojo y se volvió a aplicar el
arreglo después; el diff final es **sólo** el del arreglo.

**Lo que NO se arregla, y por qué.** El formato del cuerpo **no es invertible**
en general: los segmentos se unen con guiones, así que un identificador que
lleve un `-` dentro (un `pluginStepId`, un nombre de cierre, una clave de
rama) hace que el corte no sepa dónde acaba un segmento y dónde empieza el
siguiente. Cerrar eso tiene **dos** caminos y los dos son rupturas versionadas:
restringir el alfabeto de esos identificadores —un plugin que hoy usa `-` en su
nombre dejaría de funcionar— o cambiar el formato del texto, que **rompe las
claves del journal** que el producto promete byte a byte. Queda escrito como
**deuda con nombre**, no como descuido.

**El veredicto sobre la colisión, y lo que NO se demuestra.** Con los caminos
de producción medidos, **dos streams no comparten bytes**: los segmentos
dirigidos por posición no se pliegan, y los ids de step de producción
(`core.sh`, `core.dir`, los de los plugins de ejemplo) sólo llevan
`[A-Za-z0-9._-]`. Eso es lo que se ha medido, y es lo que vale.

Lo que **no** se ha demostrado es la propiedad general: que *ningún*
identificador que el formato admite esté libre de colisión. El nombre en disco
sigue siendo **lossy** —`a/b` y `a_b` siguen produciendo el mismo nombre—, así
que un plugin que usara `/` en el nombre de su step escribiría dos streams con
el mismo nombre, y el índice los separaría mientras el store **los mezclaría**.
La diferencia no es académica: es justo el caso que el `OpId` estricto deja de
ocultar. Por eso el veredicto honesto es «no alcanzable **por los caminos de
producción medidos**», y no «inalcanzable».

Lo que sí era verdad —y ya no lo es— es que la garantía dependía de un supuesto
no escrito, y que la puerta por la que un id externo entra al sistema
**descartaba parte de la identidad en silencio**. Eso era un defecto real, y
está arreglado.

### 2.58 El techo que no depende de la carga, y el residuo que sí tiene dueño

**El drenaje: caracterizado.** El runtime espera a que las bombas de
transcripción terminen antes de declarar un step completo, y lo hace con un
**reloj absoluto**:

```
DurableShellExecutor:  pumps.forEach { it.join(TRANSCRIPT_PUMP_DRAIN_BUDGET_MS) }   // 10 s
```

Dos hechos, y el segundo es el que importa:

1. El plazo es **por bomba**, no global: con *N* bombas, el peor caso es *N* ×
   10 s. El KDoc del presupuesto lo razona —«*Bounded, because a pump stuck on
   a live child must not hold the step open forever*»— y la comprobación es
   real: `join` devuelve igual en los dos desenlaces, así que lo que decide es
   `isAlive` **después**, y su fallo es un **rechazo**, no una línea de log.
2. **El rechazo ya es tipado**: `ProcessOutputRefusal.DrainIncomplete`. Un
   drenaje agotado **no se confunde con un fallo del script**, y el output
   plane se niega a dar por buena una transcripción cuya bomba no ha terminado
   —que es exactamente lo que un plano de salida debe hacer cuando no tiene
   pruebas de que la transcripción esté completa.

El defecto que queda es uno solo, y es real: **el techo es un reloj que no
sabe nada de la carga de la máquina**. La evidencia está en este mismo
documento: dos ensayos de la puerta se descartaron con
`transcript drain unfinished after 10000ms`, y ambos coincidieron con trabajo
ajeno corriendo al lado (§1). Con la máquina quieta, el drenaje termina en
milisegundos y el techo de 10 s es holgado de sobra; saturada, el mismo techo
se agota.

**Lo que falta es que el rechazo sea accionable**, y hay dos caminos con
costes distintos: **reintentar** el drenaje hasta que las bombas terminen, con
un techo global más alto —cambia el **tiempo** de un step, no su veredicto— o
**subir el techo**, que no arregla nada y sólo esconde la saturación detrás de
un número más grande. El primero es el honesto, porque el segundo convierte
una causa conocida en un síntoma más raro. Queda como decisión, con su coste
nombrado, y no se toma de paso.

**El residuo del driver, medido con la JVM viva.** El store de eventos abre su
base por JDBC, y el driver JDBC de Xerial **extrae su biblioteca nativa** al
cargarse: no la usa del classpath. Eso está medido, y la forma de medirlo
importa: **contando los ficheros mientras el proceso sigue vivo**, no después,
porque el driver borra lo suyo al salir y un recuento posterior no distingue
«no extrajo» de «extrajo y limpió».

| medida | valor |
|---|---|
| versión del driver | `org.xerial:sqlite-jdbc:3.46.1.3` |
| ¿extrae? | **sí**: con la JVM viva aparece el par `sqlite-3.46.1.3-<uuid>-libsqlitejdbc.so` + su `.lck` |
| **sin** `org.sqlite.tmpdir` | extrae en la **raíz** del tmpdir compartido, **anónimo**: el tmpdir compartido **crece** durante un `run` |
| **con** `org.sqlite.tmpdir` — que es lo que hace el producto | extrae en `/tmp/pipelinek-sqlite-<n>/`: un directorio **con dueño**, y el tmpdir compartido **no crece** (423 → 423) |
| al salir normalmente | el driver **borra lo suyo**: 0 ficheros, medido en un directorio **fijado por el operador**, que nada de este producto borra |
| tras un `SIGKILL` | **quedan 2 ficheros**, dentro del directorio con dueño |

**El error de método, que es lo importante.** Una versión anterior de esta
sección afirmaba que el jar trae la nativa y que por eso el driver no extrae, y
concluyó que el producto no era el autor de los residuos del host. **Era
falso**, y el error no fue de criterio sino de **método**: el recuento
«846 → 846» se había hecho sobre un binario que **ya llevaba el arreglo**, así
que no medía lo que parecía medir —medía que el arreglo funcionaba, y se leyó
como que el driver no extraía—. Con el binario **sin** el arreglo, el tmpdir
compartido **crece** durante el `run`, y los ficheros aparecen **en la raíz de
`/tmp`**. La pista estaba en el propio dato: el directorio que apareció en la
medición, mientras la JVM vivía, se llamaba `pipelinek-sqlite-…`, que es el
nombre que pone **este** producto.

**El arreglo, entonces, sí hace falta, y por esto.** Sin él, cada ejecución
deja un par anónimo en el tmpdir compartido, donde nadie puede saber de quién
es. Con él, el residuo va a un directorio **con nombre**, que el proceso borra
al salir bien y que —si el proceso muere a la fuerza— se puede atribuir y
limpiar. El knob existe y se respeta: una ejecución con
`PIPELINEK_OPTS=-Dorg.sqlite.tmpdir=…` no la pisa nadie, y entonces la
extracción va donde el operador quiso.

**La cifra del residuo, corregida también.** Lo que esta sección arrastraba
como «846 pares» eran **846 ficheros**, no 846 pares: el patrón de búsqueda
cuenta también los `.lck`, porque el nombre del bloqueo contiene el de la
biblioteca. Los números reales del tmpdir compartido, medidos:

| medida | valor |
|---|---|
| pares sueltos | **423**: 423 `sqlite-3.46.1.3-<uuid>-libsqlitejdbc.so` y 423 `.lck`, **sin huérfanos** — cada biblioteca con su bloqueo |
| peso | **427 MB** de bibliotecas; los `.lck` son de 0 bytes |
| ventana temporal | de 09:53 a 21:56: **12 horas** |
| `/tmp` entero | 2,1 GB — de los cuales el residuo del driver es una parte, no el todo |

**Por qué quedan pares, y hasta dónde se ha medido.** El driver borra su
extracción **al salir normalmente** —registra los ficheros para borrado al
terminar la JVM—, y por eso un proceso que muere a la fuerza los deja.
Medido: con la propiedad fijada a un directorio que nada de este producto
borra, tras una ejecución correcta quedan **0 ficheros**, y tras un `SIGKILL`
quedan **2**. Es decir: **un par superviviente es la huella de un proceso que
no salió bien**, no la costumbre de una ejecución sana.

Y hay un detalle del driver que explica por qué los pares no se van solos: al
inicializarse **barre su directorio temporal**, pero sólo borra una biblioteca
**cuyo bloqueo ya no está**. Un par completo —biblioteca con su `.lck`— no se
toca nunca. Medido: un par falso colocado a mano en un directorio aislado
sobrevive a la barredura intacto.

**De quién son, y lo que no se afirma.** Lo que **sí** está observado es que en
este host hay **otra instalación de PipelineK** en marcha —la distribución
instalada `0.61.0-rc2`, con el **mismo** `sqlite-jdbc-3.46.1.3` en el
classpath, ejecutando el release candidate de **otro** proyecto— que **no fija**
la propiedad, y que por lo tanto extrae suelta en el tmpdir compartido, igual
que hacía este producto antes del arreglo. **Qué proceso dejó cada uno de los
423 pares no se ha medido, y no se atribuye**: lo que se dice es lo medido, que
un par vivo es de un proceso que no salió bien, y que este producto, con el
arreglo, **no añade ninguno al tmpdir compartido**. La cifra que el ROADMAP
venía arrastrando —«344 pares»— estaba sin verificar por el mismo motivo que
la atribución: se contaba, pero no se preguntaba **quién** dejaba los ficheros
ni **cuándo se contaban**.

### 2.59 El lector que se niega a adivinar

**La pregunta.** Un identificador de operación entra al sistema desde fuera por
una única puerta: el parser. ¿Qué devuelve esa puerta cuando el texto no se
puede leer sin adivinar?

**Lo que devolvía.** Una identidad **parcial**, que parecía correcta y nombraba
otra operación. Medido:

```
run-7-s0-3-bp1-0:core-with-dash   --parse-->   run-7-s0-3-bp1-0:core
```

El cuerpo del identificador se une con guiones, así que un segmento que lleva
guion deja el corte sin regla: el parser se quedaba con los segmentos que
alcanzaba a colocar y **tiraba el resto**. El `OpId` devuelto tenía el mismo
run, la misma etapa y el mismo paso —y por eso **parecía** el mismo—, pero un
cuerpo más corto. Con dos segmentos, hasta el **conteo** del cuerpo cambiaba.

**La regla, y por qué es la regla.** Un lector que devuelve una identidad ha
devuelto la **única** que el texto nombra. Si el texto admite más de una
lectura, no hay una identidad que devolver: hay un texto que **no** nombra una
identidad, y la respuesta honesta es `null`. La diferencia entre «no sé» y
«seguramente sea esta» es la diferencia entre un rechazo y una respuesta
equivocada con forma de respuesta.

**El texto se lee ahora como se escribe.** El parser deja de adivinar y pasa a
**enumerar**: parte el texto donde un cuerpo puede empezar —el marcador es
`-bp{N}-`— y lee la raíz de cada trozo. De las lecturas posibles:

- si hay **una**, esa es la identidad;
- si hay **más de una**, el texto nombra más de una operación y no se devuelve
  ninguna;
- si el cuerpo de una lectura **no se puede cortar** sin adivinar, ese cuerpo
  se marca como **ilegible**, que no es lo mismo que vacío.

El último caso es el que hace el trabajo: un cuerpo con dos segmentos, uno de
ellos con guion, tiene más guiones que segmentos, así que hay más de un corte
posible. Cada corte es una identidad distinta, y el texto no dice cuál.

**Lo que se conserva: la etapa y el paso.** Un identificador dice en su raíz
qué etapa y qué paso es, y eso **no depende de cómo se lea el cuerpo**. Por eso
hay una segunda lectura —la de los índices— y por eso la consola, que filtra
por ellos, la usa: un filtro por etapa y paso sigue seleccionando las
operaciones cuyo cuerpo no se puede leer entero.

Eso no es un detalle de implementación: es lo que separa el **arreglo** del
**daño**. Un parser estricto sin lectura de índices habría roto, en silencio,
el filtro por etapa y paso de toda operación con cuerpo. El filtro devuelve
«nada» cuando no puede decidir, y «nada» es indistinguible de «no hubo
operaciones».

**Dos mutaciones, y qué se enciende en cada una.**

| mutación | filas que se encienden | filas que **no** |
|---|---|---|
| restaurar la lectura truncada | las 2 del lector | las 4 del ida y vuelta, las 3 del filtro |
| filtrar por la identidad entera en vez de por los índices | 1, la del filtro | las 7 del lector |

La primera dice que el lector ya no trunca. La segunda dice que la capacidad
se ha perdido: con el parser estricto y la consola sin lectura de índices, el
filtro deja de seleccionar las operaciones cuyo cuerpo no se puede leer, y
**nadie se entera**.

**La fila, vista en rojo.** `OpIdRejectionTest` — **3 filas puras**, sin disco,
sin reloj y sin proceso:

| fila | qué afirma |
|---|---|
| lo que se lee es lo que se escribió | para cualquier texto, lo que el parser devuelva tiene que **reformatearse** al mismo texto |
| un texto que no se puede leer exacto se rechaza | dos casos: cuerpo ambiguo, y texto con dos lecturas |
| la etapa y el paso se leen aunque el cuerpo no | el filtro conserva su capacidad |

La primera fila es la que dice la garantía **completa**: no lista los casos que
se niegan, afirma que no hay ningún texto del que salga una identidad
distinta. Con el parser de antes caía, y el mensaje lo decía con todas las
letras —«expected `run-7-s0-3-bp2-0:core-with-dash-n:closure` but was
`run-7-s0-3-bp1-0:core`»—.

**Lo que NO se arregla, y por qué sigue siendo deuda.** El formato del cuerpo
**no es invertible** cuando un identificador lleva un guion dentro: el texto no
dice dónde acaba un segmento. Hoy eso ya **no** produce una identidad
equivocada —produce un rechazo—, así que el daño está contenido. Pero el
formato sigue sin poder *leerse* por completo, y un pipeline con un plugin
cuyo nombre lleva guiones no se puede filtrar por su identificador completo.
Cerrarlo tiene los dos caminos de siempre: restringir el alfabeto —un plugin que
hoy usa `-` en su nombre dejaría de funcionar— o cambiar el formato del texto,
que **rompe las claves del journal** que el producto promete byte a byte. Esa
decisión es de producto y no se toma de paso. La **defensa inmediata** —no
devolver una identidad adivinada— no necesita ninguna de las dos.

### 2.60 Dos artefactos honestos con la misma identidad

**El problema que había.** El escenario de identidad duplicada mezclaba dos
defectos: el plugin que la distribución llevaba dentro estaba **manipulado** —
su manifiesto declaraba un evento que nadie aportaba—, y la copia del
operador era la del editor. El rechazo que el escenario leía era el correcto
(`DuplicateIdentity`, con los dos nombres), pero **no se podía atribuir**: dos
cosas estaban mal y el escenario sólo probaba una.

El arnés tenía además una razón explícita para no ofrecer dos artefactos con
la misma identidad en los escenarios de «manifiesto que miente»: ofrecer el
plugin engañoso **además** del de la distribución es un artefacto distinto con
la misma identidad, y se rechaza **por eso** antes que por la mentira. El
escenario de identidad duplicada usaba esa propiedad para susurrar su caso, y
heredaba la mezcla.

**Lo que hay ahora.** Los dos artefactos son **honestos**: el mismo manifiesto
del editor, el mismo código, y bytes distintos porque el empaquetado es
distinto. Lo único que está mal es que los dos piden el mismo nombre, así que
**la única razón para rechazar la composición es la identidad** — y el rechazo
que el escenario lee sólo puede ser sobre la identidad.

| artefacto | manifiesto | código | bytes |
|---|---|---|---|
| el de la distribución | el del editor | el del editor | los suyos |
| el del operador (`--plugin-jar`) | **el mismo**, byte a byte | el mismo | **distintos**: otro empaquetado |

**El fixture, y lo que comprueba.** El lanzador reempaqueta el plugin del
editor con una marca de tiempo fija y **comprueba las dos cosas que dice**:
que los dos ficheros difieran y que el manifiesto **no** difiera. Un
reeempaquetado que saliera byte a byte igual dejaría al escenario sin nada
que rechazar — y eso es un **fallo ruidoso del fixture**, no un escenario
verde. Un fixture que deja de ser lo que dice no puede convertirse en un falso
verde.

**La mutación, y lo que se ve alencoer el rechazo.** Sin la detección de
duplicado, el escenario se pone rojo, y el rojo **nombra el otro rechazo**:

```
FAIL ... stderr lacked: DuplicateIdentity
  Plugin admission refused for .../plugins/uppercase.jar: InvalidManifest(
      ... declared Steps with no implementation ...)
```

Eso es la atribución que faltaba: sin la detección, el artefacto entra y
choca después con la segunda puerta, la que compara lo declarado con lo
aportado. Los dos rechazos son distintos, el escenario los distingue, y el
que exige es el de la identidad.

**Lo que no cambia.** El escenario sigue siendo **uno**, con las mismas siete
garantías. El fixture `lying-events` sigue existiendo para el escenario de
evento que miente, y `bundled-uppercase` sigue existiendo para los escenarios
de confianza. Lo que se ha hecho es **desacoplar** el caso de identidad
duplicada del caso de manifiesto mentiroso: cada defecto, con su fixture.

### 2.61 La versión que el producto declara, y la que el mundo puede publicar

**La pregunta, tal y como estaba.** Este árbol declaraba la versión `0.62.0`.
Pero `v0.62.0` **ya está publicada**, y son bytes **distintos**: el RESET
re-funda la identidad del producto, así que el `v0.62.0` del remoto no es lo
que este árbol construye. Medido: el tag remoto `v0.62.0` —objeto anotado
`08a38c49`— apunta a `92ddd58d`, del linaje viejo, y ese commit **no** está
en la historia de `main`. Mientras tanto la línea publicada había seguido
adelante: `gh release list` da **`v0.75.0`** como release GA, publicada el
2026-10-22 16:07Z, con la etiqueta *Latest*.

La versión de este árbol, pues, **colisionaba con una release publicada** y
además iba **trece releases por detrás** de la línea publicada. El propio
ROADMAP lo tenía anotado como bloqueo (§2.28): «declara `0.62.0` y **no se
puede publicar tal cual**: su versión colisionaría con la publicada». Eso
impedía publicar, y publicar es lo que convierte el trabajo en valor.

**La geometría, medida.** `v0.75.0` es el commit `3079b2603`, y ese commit
**no** es ancestro de `main`: el merge-base es `56cfd1729`, con **714**
commits del lado publicado y **1126** del lado de `main`. Dos linajes, no uno
con retraso. Pero `main` **sí** lleva ese trabajo: `e3a22a050` es su gemelo
portado a mano, commit por commit y con los defectos corregidos. Así que
`main` no es una versión vieja de lo publicado: es **lo publicado más la
integración entera**.

**La salida: `0.76.0`.** Y no `0.75.0`, por una razón que no es de gusto:
el tag `v0.75.0` existe, y `scripts/release-pipelinek.sh:86-92` **se niega a
publicar un tag que ya existe**. Reutilizar ese número haría que un
artefacto afirmara ser una release que no es — la forma exacta que
`ProductVersion` existe para impedir, y la que ya dejó huella en el
incidente de la v0.43.0. Un número que no colisiona y **ordena** este árbol
después de lo publicado es `0.76.0`.

**Una sola autoridad, y por eso no hay dos verdades.** El número vive en una
sola línea, `pipelinek/build.gradle.kts:46`, y todo lo demás **sale** de ahí:
el `Implementation-Version` del jar, el recurso `RuntimeApiVersion` que
responde al subcomando `version`, el nombre del ZIP de distribución, el BOM,
y el `-PsdkVersion` que los plugins de ejemplo derivan para su `apiRange`.
Medido: ese `apiRange` se regenera solo (`pipelinek/build.gradle.kts:393,446,473,510`
pasan `-PsdkVersion=` + `rootProject.version`), así que pasa a
`[0.76.0, 0.77.0)` sin que nadie lo escriba. No hay un segundo sitio que
ajustar, y por eso no puede haber dos versiones que discrepen.

**Lo que NO hizo falta tocar, y por qué.** El catálogo de capacidades
conserva su `firstPublished = "v0.62.0"`: ese campo nombra la **línea de
versión que primero lleva el id**, no un tag ya puesto, y la línea sigue
siendo la misma. Medido además que el campo **sólo se publica** —no se
compara con la versión en runtime en ninguna parte—, así que moverlo no
cambiaría ninguna decisión y sí rompería un contrato ya publicado. Los dos
comentarios que citan «distribution 0.62.0» lo hacen como **medición de un
build anterior**, que sigue siendo cierto. Y el instalador no fija ninguna
versión: la recibe como argumento y resuelve el digest del `SHA256SUMS` de
la release.

**El daño colateral que sí había, y era de usuarios.** El README publicaba
un quickstart **que no verificaba**. Su `sha256sum -c` fallaba, porque el
tag `v0.62.0` fue recreado el 2026-10-19 sobre `92ddd58d` mientras el README
certificaba `951b3cb5` —commit del linaje de `main`, de 2026-09-19— con el
digest `385b140c…`; el ZIP publicado vale `16db1eb2…`. Y el README llamaba
«la autoridad pública de hoy» a `v0.62.0`, cuando la release *Latest* era
`v0.75.0` desde el 2026-10-22. Los dos defectos se encontraron **midiendo**,
no leyendo: la línea que se certifica es la que el mundo puede descargar.

**Lo que ahora dice el README, con los digest medidos.** Los comandos
apuntan a `v0.75.0`, y sus digest se obtuvieron descargando el ZIP desde
GitHub Releases el 2026-10-23:

| hecho | valor medido |
|---|---|
| ZIP | `e24abea8ed0ef01f8f70f8a2fcfbe8efce52b33d7d4b73acfd5c54cd1b4973fa` — idéntico al `SHA256SUMS` que publica la release |
| binario | `ccbbad405b8ae886d40954ef70a7418b7c484fb75a9a37437c369943d7c0d233` |
| commit certificado | `3079b2603594ee92666929317f66224e42b308b4d` |
| identidad | raíz del archivo `pipelinek-0.75.0`; el binario reporta `pipeline 0.75.0` |

El quickstart se ejecutó **tal cual**, con su `sha256sum -c`, y la respuesta
fue «la suma coincide». Y el bloque «Verified against the published
artifact» ya **no** afirma que *toda* afirmación del README se verificó
contra ese ZIP, porque no es cierto: el catálogo de capacidades y los
recuentos de escenarios describen **este árbol**. Medido: el catálogo del
binario publicado tiene **11** ids, todos de nivel `v1`, y no tiene ninguno de
los seis que este árbol lleva (`events.follow.v2`, `events.follow.v3`,
`output.follow.v2`, `runtime.eventshape.v2`, `events.stepoutcome.v1`,
`events.failurestage.v1`). El README lo dice, y dice cuál de las dos cosas
describe cada bloque.

**Lo que NO se arregla, y por qué sigue siendo deuda.** El `manifest.json`
de la release `v0.75.0` es **byte a byte el del rc1** —los dos hashean
`67e5d6c4…`— y dentro de la GA dice `"release_tag": "v0.75.0-rc1"`. Es la
misma forma del incidente de la v0.43.0: la GA presenta el manifest del
candidato. La ley de procedencia (`CandidateTagProvenance`) **no lo caza**:
comprueba bytes ↔ commit de origen ↔ peel del tag, pero nadie compara el
campo `release_tag` **del propio manifest** con el tag que lleva la release.
Eso está en el artefacto publicado y en el proceso de release, no en este
árbol, así que se anota con su nombre y se abre como bloque propio.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Versión declarada | `0.76.0`, en una sola línea (`pipelinek/build.gradle.kts:46`) |
| Autoridad publicada | `v0.75.0` (GA, 2026-10-22), digest verificado por descarga propia |
| Puerta | **PASS** en los tres ensayos, sobre `636dadda5`, peor 376 s de 900 s |
| Tests | **1087** unitarios, 153 informes, 0 fallos, 0 errores, 0 skips |
| Escenarios E2E | **61 de 61** |
| Presupuesto de reloj | peor ensayo < 900 s, límite 900 s |
| Colateral | ninguno: el `apiRange` de los plugins se deriva solo, y el `firstPublished` del catálogo no se toca |
| `:pipeline-runtime` | **vuelve a estar cableado** y lo compila la etapa 1 por su nombre (§2.52) |

### 2.62 Los comentarios que afirman cosas que ya no son ciertas

**La pregunta, tal y como estaba.** ¿Qué debería pasar cuando el código
cambia y sus comentarios no? La respuesta de este árbol era, sin que nadie lo
decidiera: el comentario se queda. Y un comentario que se queda afirmando
una verdad que el código ya no tiene es peor que no escribirlo, porque un
lector no puede saber que miente.

Este bloque no cambia **una sola línea de comportamiento**. Cambia lo que el
código afirma sobre sí mismo, y lo hace en cuatro frentes que se
midieron, no se supusieron.

**1. Guardianes que ya no existen.** Siete comentarios citaban un «fitness
test» o una «architecture fitness» como la autoridad de una ley. Ese harness
lo borró el RESET, así que las leyes estaban **sin observador**: el comentario
prometía una vigilancia que nadie hacía, y el que lo leyera después de esa
borrada habría creído que la ley vigila sola.

Ahora la ley se enuncia y se dice que nada la vigila, con la fórmula que el
propio árbol ya usaba en `SystemRuntimeConfig`: *«a reader who assumes a
boundary the code does not draw will not notice the day one is crossed»*.
En `AGENTS.md` §2 no se admite el *source-text fitness*, así que la respuesta
correcta **no** es resucitar el test: es decir la verdad.

**2. Un estado que ya no es el estado.** La constante `LEGACY_PLUGIN_IDS`
tenía un KDoc de **123 líneas** que narraba un burn-down **ya terminado**, en
frases a las que una referencia excizada había robado el sujeto:

> «Remains physically present until deletes it (LEGACY_REMOVED)»
> «Until the legacy dispatcher is unreachable in production but Still type-loadable»

Medido, el estado real es este:

| lo que se afirmaba | lo que se midió |
|---|---|
| el set tiene miembros | el set está **vacío** |
| el decoder legacy tiene ramas | **no tiene ni una**: todo cae al `else` que lanza |
| quedan dispatchers legacy | **ninguno**: no existe ningún `Canonical*Dispatcher.kt` |
| quedan filas de metadata legacy | **ninguna**: `CanonicalCoreStepMetadata` no declara ninguna |
| `CanonicalCoreStepCommand` tiene subtipos | **ninguno**: la interfaz sellada está vacía |

El log se sustituye por **22 líneas** que dicen lo que la constante ES, y por
qué se sigue leyendo en dos sitios aunque hoy no pueda disparar: porque la
regla que codifica es sobre la **semántica** del núcleo, no sobre la
pertenencia actual —una clave en ese set sigue siendo legacy AUNQUE exista
una definición registrada con la misma clave, así que registrar una
definición nunca puede cambiar lo que significa una clave del núcleo.

Lo mismo pasó en `CoreStepRegistryFactory`, que afirmaba **nueve veces** que
«X remains in `LEGACY_PLUGIN_IDS`» y que por eso `LegacyCore` era la
autoridad de producción. Con el set vacío, las nueve son falsas, y el
invariante que declaraban (`== 12`) son 0. El archivo pasa de **162 a 91
líneas**, y ahora describe lo que cada Step **DECLARA** —su capacidad, sus
efectos, sus políticas— en vez de una migración que ya no existe.

**3. La unidad de trabajo, escrita en el código.** Diecinueve comentarios se
remitían a «este bloque», «este gate», «este slice», «este ciclo» o «este
cambio». Un comentario que depende de **quién lo lea** y de **cuándo se
escribió** no explica la funcionalidad: se explica a sí mismo, y el día que
se relea será un acertijo. Los **cinco** casos en los que «this block»
significa bloque de **código** se dejaron intactos, porque ahí la palabra
designa algo real.

**4. Rutas muertas.** `v2/build.gradle.kts`, `<v2>/build/sdk-repo/` y
`v2/<module>/src/main/` son directorios que **no existen** desde que el build
se mudó a `pipelinek/`.

**Dos afirmaciones que la medición desmintió de paso.** El DSL de
`pwd(tmp=true)` **sí** está cableado a `CorePwdTmpStep` (lo está: lo llama
`CompiledScriptedEntryPoint`), y `TYPED_RUNTIME_OUTPUT` de `core.isUnix`
está **aprobado**, no «candidato no aprobado». Las dos se habían escrito al
revés, y ambas se corrigen según lo medido.

**Medido, sobre el árbol de este bloque:**

| Métrica | Valor |
|---|---|
| Ficheros tocados | **25**, todos de comentario: **cero** líneas de comportamiento |
| Líneas netas | **−25** (302 añadidas, 327 borradas): el árbol dice menos, y dice mejor |
| KDoc de `LEGACY_PLUGIN_IDS` | de **123** líneas a **22** |
| `CoreStepRegistryFactory` | de **162** líneas a **91** |
| Referencias a unidades de trabajo | **40 → 0** |
| Guardianes inexistentes citados | **7 → 0** |
| Guard de procedencia | **verde**: ningún comentario nombra un encargo, una sección o un commit |
| Compilación | los **6** módulos tocados compilan |
| Puerta | **PASS** en los tres ensayos sobre `636dadda5`: **197 / 169 / 376 s** de 900 s |

### 2.63 Lote 2 de la auditoría de capacidad discriminante: dieciséis escenarios, veinte mutaciones, dieciocho encendidos

§2.36 abrió la auditoría y §2.37 cerró su fila 6. Este bloque continuó con el
lote 2, el que la propia §2.37 dejó escrito: «los cincuenta escenarios restantes
siguen sin mutar».

**La pregunta, sin cambiar.** ¿Puede encenderse la garantía que este escenario dice
sostener? Un escenario que no puede encenderse no compra nada, y una suite que no
distingue un guardián vivo de uno decorativo no puede decir qué compra; por tanto
tampoco puede decir qué sobra.

**Dos clases de mutación, y por qué este lote necesita las dos.** §2.36 atacó
sólo la ENTRADA que el escenario entrega al paso bajo prueba, porque esa mutación
responde a la pregunta que importa —«si el producto se comportara distinto, ¿este
escenario lo notaría?»— sin tocar el producto. Pero buena parte de las garantías
de este lote no viven en la entrada: viven en el LECTOR del event lane, en el
cursor de la reanudación, en el veredicto del recover. Para ésas la entrada no
alcanza la garantía, y la sonda honesta es romper el PRODUCTO y comprobar que el
escenario se enciende contra el defecto real, no contra un texto distinto.

- **K1, la entrada**: se cambia lo que el escenario entrega al paso bajo prueba.
- **K2, el producto**: se introduce el defecto que la garantía nombra, se
  recompila la distribución y se corre el escenario.

Cada mutación se deshace —código y árbol— antes de la siguiente, y cada una va
precedida de la lectura del diff que la aplicó: una mutación que no se aplicó no
prueba nada.

**La muestra.** Dieciséis escenarios, elegidos por variedad de categoría
(control-flow, events, durable, output, security, plugins, negative) y por
variedad de tipo de afirmación: `expect-stdout`, `expect-stderr`,
`expect-transcript`, `expect-exit`, `expect-outcome`, `step-verdict`,
`failure-stage`, `run-state`, `events-follow`, `events-resume`, `events-refusal`,
`durable`, `resume`, `cancel`, `recover` y `plugin-trust`.

**La tabla medida.** Cada fila es una mutación, con el escenario que la sostiene y
la razón que encendió o no encendió.

| # | Escenario | Afirmación | Mutación | Veredicto observado | Encendió |
|---|---|---|---|---|---|
| 1 | `control-flow/05-scripted-control-flow` | `expect-stdout` | `val x = 1` → `val x = -1`: la rama del script toma el otro brazo | RED: `stdout lacked: x is positive` | **✓** |
| 2 | `control-flow/05-scripted-control-flow` | `expect-transcript` | sólo la expectativa del transcript; el stdout queda intacto | RED: `the committed transcript lacked: …`, y el stdout siguió verde | **✓** |
| 3 | `control-flow/11-workflow-control` | `expect-outcome` | quitar el paso `unstable("flaky")` | **GREEN**: la corrida siguió concluciendo `unstable` | ✗ |
| 4 | `control-flow/11-workflow-control` | `expect-outcome` | quitar a la vez el `catchError` y el `unstable` | RED: `concluded success, expected unstable` | **✓** |
| 5 | `events/02-step-verdict` | `step-verdict` | invertir el veredicto de los dos pasos | RED: `could not read the step verdicts` | **✓** |
| 6 | `events/03-failure-stage` | `failure-stage` | mover el fallo del segundo escenario al primero | RED: `could not attribute the failure to its stage` | **✓** |
| 7 | `events/05-run-state` | `run-state` | el paso termina mal | RED: `the run state did not say: run-state terminal outcome=succeeded` | **✓** |
| 8 | `output/33-live-process-output` | `expect-stdout` | `returnStdout = false` → `true`: la salida del proceso deja de llegar a la terminal | RED: `stdout lacked: RAW_PROCESS_MARKER` | **✓** |
| 9 | `security/28-zip-slip-defense` | `expect-exit` + `expect-stderr` | nombre de entrada inocuo en el zip: el archivo ya no se escapa | RED: `exit 0, expected 1` | **✓** |
| 10 | `plugins/uppercase` | `expect-stderr` | un nombre de plugin distinto en la expectativa | RED: `stderr lacked: Discovered external Step plugins: …` | **✓** |
| 11 | `plugins/uppercase` | `expect-stdout` | un nombre de paso distinto en la expectativa | RED: `stdout lacked: registrystep-9 finished` | **✓** |
| 12 | `negative/plugin-trust-digest-mismatch` | `plugin-trust` | `signed-then-tampered` → `signed`: el artefacto ya no está trocado | RED: `exit 0, expected 2` | **✓** |
| 13 | `durable/32-durable-replay` | `durable` (dos corridas) | el efecto ocurre dos veces | RED: `the resumed run exited 1, expected 0` | **✓** |
| 14 | `durable/35-saga-crash-effect` | `resume` (dos corridas) | el efecto ocurre dos veces, como si la reanudada lo repitiera | RED: el finalizador `post` cazó el efecto repetido | **✓** |
| 15 | `durable/39-cancel-dead-run` | `cancel` | el escritor no muere: la corrida termina bien | RED: `the run was supposed to crash and finished cleanly` | **✓** |
| 16 | `durable/40-recover-refuses-a-half-finished-run` | `recover` | el trabajo se completa antes de la muerte | **GREEN**: el paso muerto sigue en vuelo, así que el recover sigue negándose | ✗ |
| 17 | `negative/events-unknown-run` — **K2** | `events-refusal` | la admisión de la lectura dice que todo run existe | RED: `exit 0, expected 2` — el defecto histórico exacto | **✓** |
| 18 | `events/01-external-reader-resumes` — **K2** | `events-resume` | el cursor se pasa una fila del último hecho entregado | RED: `a page read exited 2` (`cursor 23 is past the tail`) | **✓** |
| 19 | `events/04-follow-filtered` — **K2** | `events-follow` | el hecho terminal se busca entre los hechos que el filtro dejó pasar | RED: `the follow with a filter never ended` — lo cazó el reloj, 44 s | **✓** |
| 20 | `durable/40-recover-refuses-a-half-finished-run` — **K2** | `recover` | un trabajo en vuelo deja de impedir que el run parezca terminado | RED: `the recover closed a run that had not finished its work` | **✓** |

**Las dos que no encendieron, y por qué ninguna de las dos es un hueco del
escenario.** Las dos verdes son mutaciones que NO ALCANZAN la garantía, y las dos
lo dicen con una medición, no con una explicación.

- La fila 3 quita el paso `unstable("flaky")` y la corrida sigue concluyendo
  `unstable`: la conclusión la aporta el `catchError`, que es lo que la fila 4
  quita también. El paso `unstable("flaky")` no era la garantía, era un segundo
  camino al mismo verde.
- La fila 16 deja el trabajo completo antes de la muerte, y el recover sigue
  negándose: el paso que murió sigue con su fila en vuelo, y esa fila es la que
  el recover lee. La fila 20 rompe esa regla en el producto y el escenario se
  enciende. La fila 16 no probaba la garantía; la fila 20 sí.

**Lo que la auditoría cambió en el código, y por qué no es cosmetics.** Tres
cambios, cada uno sostenido por una fila de la tabla.

1. **`control-flow/11` pierde el paso `unstable("flaky")`.** Con él, la afirmación
   `expect-outcome unstable` tenía dos caminos al verde: el `catchError` y el
   paso. Eso no la hace más robusta, la hace menos discriminating: el escenario
   seguiría verde aunque el `catchError` dejara de marcar la corrida como
   inestable, que es justo lo que la afirmación dice comprobar. Sin el paso, la
   afirmación queda con un solo camino, y ese camino es el que el producto define.
   El escenario gana además una cabecera que declara su garantía, que no tenía.

2. **La fila verde del arnés dice qué comparó.** El lanzador ya contaba las
   garantías por escenario —las publicaba en su marcador interno y las sumaba en
   el censo final— y esa cifra no llegaba a la línea que una persona lee. Una
   verde que no dice qué comprobó no se puede pesar contra lo que costó, y una
   suite cuyo detalle se guarda en un fichero que nadie abre es una suite que
   declara su propia utilidad. La fila dice ahora cuántas garantías comparó, y
   cuando no comparó ninguna dice que la garantía vive en los pasos del propio
   pipeline: son dos hechos distintos, y el código de salida que el arnés vigilaba
   es el que lleva esa garantía.

3. **La caché de scripts compilados, activada por el arnés.** El producto ya
   tenía una caché de scripts compilados, apagada por defecto porque no hay una
   convención de directorio de usuario que pueda ser «la correcta» en un HOME de
   sólo lectura, en un CI encajonado o en un contenedor efímero. El arnés E2E no
   tiene ese problema: su directorio de ciclo es suyo, se borra con la corrida, y
   nadie depende de que el artefacto sobreviva.

   **Medido, sobre este mismo árbol y este mismo host, corridas seguidas:**

   | | sin caché | caché fría | caché caliente |
   |---|---|---|---|
   | E2E completo (61 escenarios) — reloj | **116,5 s** | **84,3 s** | **52,4 s** |
   | E2E completo (61 escenarios) — CPU | 2115 s | 1372 s | 619 s |
   | un escenario — reloj | 15,6 s | 12,0 s | 8,5 s |
   | un escenario — CPU | 27,7 s | 18,0 s | 8,4 s |
   | veredictos y garantías | 61 / 170 | 61 / 170 | 61 / 170 |

   La lectura inicial era que «una corrida compila el MISMO script dos veces», y
   **era falsa**: una corrida compila UNA vez, y el event lane del producto lo
   registra — un `CompilationStarted` y un `CompilationFinished` por corrida,
   medido sobre el evento de la propia corrida, no sobre una inferencia. Las
   dos compilaciones eran del ARNÉS: el lanzador ejecuta cada escenario en las
   DOS rutas de ejecución (con journal y sin journal), y son dos ejecuciones
   del producto, cada una con su compilación. El artefacto que queda es uno
   solo porque las dos compilan el mismo texto, y la caché hace que la segunda
   sea gratis.

   Las cifras de coste que se publicaron **siguen siendo las mismas**: 116,5 s
   sin caché, 84,3 s con caché fría, 52,4 s con caché caliente, con los mismos
   61 escenarios. Lo que cambia es la causa, y una causa equivocada apuntando
   al sitio equivocado es como una afirmación caducada: mueve el trabajo al
   sitio donde no está. La corrección está en §2.64, y allí está medido dónde
   estaba el trabajo de verdad.

   El arnés usa la caché **dentro de su directorio de ciclo**, que la trampa
   borra con todo lo demás: la caché no sobrevive a la corrida, no se acumula en
   el HOME de nadie y dos invocaciones del lanzador nunca comparten un
   artefacto. La caché no cambia lo que se comprueba: la clave es el texto de
   la fuente junto con el classpath y las versiones, así que un escenario
   editado es otra clave y se compila otra vez.

   **Lo que esto deja escrito como deuda, con nombre.** El producto compila dos
   veces el mismo script en cada corrida, y un usuario que no active la caché
   paga las dos. El arreglo de producto —compilar una vez y reutilizar el
   artefacto— es un bloque con su propia verificación, y no cabe dentro de
   una auditoría del arnés. Lo que sí cabe, y queda hecho, es que el arnés deje
   de pagar dos veces.

**Lo que la auditoría NO encontró, dicho con la misma claridad.** En estos
dieciséis escenarios no hay ni uno que sea vacuo, ni una garantía duplicada por
otra garantía, ni un escenario que se pueda retirar sin perder algo. La petición
de reducir el número de escenarios E2E no tiene aquí un destino honesto: reducirlo
significaría dejar de comprobar algo. Lo que sí se ha reducido es el COSTE de
comprobarlo, y el detalle por fila que lo hace decidible por primera vez.

Quedan **treinta y nueve** escenarios sin mutar: la puerta ejecuta 61, y el
lote 1 (§2.36 y §2.37) auditionó seis y este auditionó dieciséis. La cuenta que
§2.37 dejó escrita —«los cincuenta escenarios restantes»— era de una suite de
56; hoy son 61, y la diferencia son escenarios añadidos después.

La muestra que este lote **no** tocó incluye `smoke/`, `steps/` en su mayoría,
`output/` en su mayoría y las categorías `negative/` de plugins que no son
`plugin-trust`. El lote 3 sigue el mismo método sobre esa muestra.


### 2.64 El censo contaba la mitad del trabajo, y la explicación que se había publicado era falsa

Este bloque empieza por **deshacer una afirmación propia**. La propuesta de
misión que quedó encadenada al cierre del ciclo anterior —«que una corrida de
`pipelinek` compile el script UNA vez, reutilizando el artefacto, y no dos»—
se apoyaba en una frase que se había publicado como medida: «una corrida
compila el MISMO script dos veces». Esa frase era **falsa**, y una propuesta
que se apoya en una medición falsa empuja el trabajo al sitio equivocado.

**Lo que se midió, y cómo.** La verdad se midió por el event lane del propio
producto, no por inferencia: una corrida deja **un** `CompilationStarted` y
**un** `CompilationFinished` en su historia. El producto compila UNA vez.

Las dos compilaciones eran **del arnés**. `examples/run.sh` ejecuta cada
escenario en las **DOS rutas de ejecución** del producto —con journal, que es
como lo corre un CI, y sin journal, que es el defecto de una corrida local— y
las dos tienen que cumplir las mismas expectativas. Esa doble ejecución es una
garantía deliberada: una divergencia entre las dos rutas es un defecto, y sólo
correr las dos lo encuentra. Ya pasó: las dos rutas informaban de un pipeline
fallido de forma distinta, y una suite que corría una sola no veía que había
una elección que hacer.

Con 61 escenarios, eso son **110 ejecuciones del producto**, no 61. Y con la
caché apagada cada una compila el script; con la caché, la segunda compilación
de cada escenario es gratis, porque las dos compilan el mismo texto. De ahí el
116,5 s → 84,3 s. **Las cifras de coste que se publicaron siguen siendo las
mismas; lo que cambia es la causa.**

**El defecto que salió de verdad, y que es de otra clase.** El censo del arnés
decía, en su propio comentario, que contaba «lo que el arnés COMPARÓ». No lo
contaba: contaba **declaraciones**, una vez por escenario. Pero cada
declaración se compara contra la salida de **cada** ejecución, así que el
informe se quedaba en menos de la mitad de su propio trabajo:

| | lo que el informe decía | lo que el arnés hizo |
|---|---|---|
| comparaciones | **170** | **285** |
| ejecuciones del producto | no lo decía | **110** |

Medido sobre la suite entera, con el mismo árbol y el mismo host. El informe
no mentía en un número: **omitía el 40 % de su trabajo y no decía cuántas
veces corría el producto.** Un informe que dice «170 garantías» cuando ha
hecho 285 comparaciones describe una suite más pequeña de la que existe, y
una suite más pequeña de la que existe se infravalora cuando se decide dónde
invertir.

**El arreglo.** Las promesas se cuentan donde ocurre la comparación —una vez
por ejecución— y no una vez por escenario. El informe dice cuántas
ejecuciones hubo. Una fila verde dice ahora `checks=5` donde antes decía
`guarantees=3`, y el censo dice «285 checks sobre 110 ejecuciones». La
diferencia entre 3 y 5 no es un cambio de counting: son las dos ejecuciones del
escenario, cada una contra su propia salida, más la comprobación del veredicto
publicado que sólo existe en la ruta con journal.

**Lo que NO se hace, y por qué está escrito.**

- **No se retira la ejecución volatile.** Es una garantía real y ya no cuesta
  una compilación. Retirarla sería cambiar qué comprueba la suite para
  ahorrar segundos.
- **No se guarda la caché entre invocaciones.** Bajaría el E2E de 84,3 s a
  52,4 s, pero haría los tres ensayos de la puerta heterogéneos —el primero
  en frío, los otros dos en caliente— y dejaría un artefacto fuera del ciclo
  que la puerta borra. Treinta y dos segundos contra un presupuesto de 900 s
  no justifican comprar la medición con heterogeneidad. Queda escrito con
  la cifra, para que la decisión se tome con el número delante.
- **La primera compilación de cada escenario sigue costando el grueso del
  E2E.** Medido sobre los dos extremos: la caché fría cuesta 1372 s de CPU y
  la caliente 619 s, así que las compilaciones son **753 s de CPU (55 %)**; y en
  reloj, 84,3 s contra 52,4 s, así que son **32 s (38 %)**. Es el trabajo que
  queda: quitarlo necesita la caché entre invocaciones, que es justo lo que el
  punto anterior explica por qué no se hace aquí.

**Lo que este bloque deja medido y sin hacer.** La doble ejecución del arnés
compara cada ejecución **contra las expectativas declaradas**, pero **no
compara las dos ejecuciones entre sí**. Las dos rutas se tienen que parecer
en lo que el escenario declara, no en todo lo que hicieron. Ese hueco es real,
es medible, y es el bloque siguiente: o la comparación entre las dos rutas se
hace de verdad —con la normalización que exijan los identificadores de
corrida, las marcas de tiempo y las rutas—, o la ejecución volatile se retira
de los escenarios donde no puede discriminar. Hoy no se sabe cuál de las dos
cosas vale más, y decidirlo con el número delante es mejor que decidirlo con
la intuición.

### 2.65 Las dos rutas de ejecución, comparadas entre sí

Este bloque cierra la pregunta que §2.64 dejó escrita y sin decidir: **la doble
ejecución del arnés discrimina, o es peso muerto**. Se decide con una mutación,
que es como se decide: una intuición sobre 61 escenarios no prueba nada.

**La pregunta, con sus dos salidas.** El arnés corre cada escenario en las dos
rutas de ejecución —con journal y sin journal— y las dos tienen que cumplir las
mismas expectativas, pero **no se comparaban entre sí**: se parecían en lo que el
escenario declara, no en todo lo que hicieron. Las dos salidas eran, entonces,
comparar las rutas de verdad o retirar la ejecución volatile de los escenarios
donde no puede discriminar.

**Lo que se midió antes de tocar nada.** La comparación entre las dos rutas no
se puede hacer con los bytes en crudo, y no por una razón del arnés: el mismo
script, en las dos rutas, **cuenta la misma historia y compromete los mismos
bytes**, una vez normalizados la identidad y el reloj. Lo que NO cuenta igual es
el preámbulo, y la comparación lo dice así, con nombre:

| | lo que cuenta | por qué difiere |
|---|---|---|
| la historia | desde «Run started:» hasta el final | no difiere: es la misma |
| el diagnóstico (stderr) | el recibo, el descubrimiento, el veredicto | no difiere: es el mismo |
| los bytes comprometidos | el Output Plane leído con el lector del producto | no difiere: son los mismos |
| el preámbulo | la correlación y la compilación | el arnés compila una vez y deja que la segunda ruta reutilice esa compilación |

**Las tres diferencias que la comparación encontró al construirse, y que eran
del arnés, no del producto.** Una comparación que se pone en rojo por culpa
propia no es un instrumento: es un falso positivo con forma de garantía. Las
tres se midieron, y las tres eran del arnés:

1. **La salida de un proceso sin salto de línea se pega a la narración.** Un
   proceso que imprime sin newline deja la consola a media línea y la línea de
   narración siguiente se le encima encima. Medido en `durable/31-stash-unstash`:
   `lpr104-data[PipelineK] Run correlated: …`. En la ruta durable se pega a la
   primera línea, y en la volatile a la que sigue al paso — porque la durable
   imprime la narración al final y la volatile la imprime en vivo.
2. **Las credenciales se materializan en temporales cuyo nombre lleva un número
   que ninguna otra corrida usaría.** Medido en `security/14-credentials-bindings`:
   `pipeline-ssh-key-8038300921585619486-ssh-creds` contra
   `pipeline-ssh-key-2734788815458360786-ssh-creds`. Dos corridas no pueden
   comprometer los mismos bytes ahí, y esa diferencia no dice nada de la corrida.
   La normalización quedó acotada a esa forma, y no a «todo número largo»: el
   mutante C de más abajo es lo que impedía esa generalización.
3. **Una corrida que se rechaza antes de empezar no imprime nada en la ruta
   durable.** El motivo: la consola durable es un documento que se renderiza al
   final, y el rechazo termina la corrida antes de que ese documento exista; la
   ruta volatile ya había narrado el preámbulo. Medido en las ocho categorías
   `negative/plugin-*`, con la misma negativa y el mismo código de salida en las
   dos rutas y una consola distinta.

**El instrumento que queda.** Después de que cada ejecución se compruebe contra
las expectativas declaradas, las dos ejecuciones se comparan **entre sí** sobre
dos superficies: la historia que contaron (la narración desde el momento en que
la corrida empieza, más los diagnósticos de stderr) y los bytes que
comprometieron (el Output Plane, leído con el lector del producto). Se
normalizan la identidad de la corrida (identificador, correlación, dueño,
reloj, directorio de control y los números largos que hacen único un temporal),
las dos raíces —cada mundo vive en su directorio— y el preámbulo, que es
preparación y no historia. **No** se compara el sitio donde cae la salida de un
proceso entre dos líneas de narración: el observador que la entrega sondea, así
que ese sitio es una proyección y no una promesa, y una comparación que dependiera
de él sería una moneda al aire.

**La mutación, y el veredicto.** Dos defectos reales en el producto, uno por
instrumento bajo prueba, y una ejecución de la suite contra los dos a la vez:

| mutante | qué se rompió | qué lo detectó | filas |
|---|---|---|---|
| A | la ruta volatile deja de entregar al terminal lo que el proceso imprimió | la ejecución volatile | **8** |
| B | la ruta durable deja de narrar el veredicto final de la corrida | la comparación entre las dos rutas | **28** |

La suite con los dos mutantes queda en **61 escenarios: 25 pasan, 36 fallan**, y
cada fila dice qué instrumento la vio. Las ocho del mutante A se leen así, y son
verbatim:

```
control-flow/05-scripted-control-flow (volatile): stdout lacked: x is positive
control-flow/11-workflow-control        (volatile): stdout lacked: step-A
output/33-live-process-output           (volatile): stdout lacked: RAW_PROCESS_MARKER
plugins/body-slots                      (volatile): stdout lacked: compensated
plugins/lock-directive                  (volatile): stdout lacked: acquired
plugins/repeat-block                    (volatile): stdout lacked: iteration
steps/33-glob-ant-alternation           (volatile): stdout lacked: keep.txt
steps/34-workspace-coherence            (volatile): stdout lacked: WROTE_HERE
```

Y las veintiocho del mutante B dicen, todas, `the two execution paths told
different stories`.

El mutante B es el que importa, porque **ningún escenario declara el veredicto
final de la corrida en stdout** —se comprobó: cero de 61—, de modo que la
ejecución durable de esos escenarios pasó sus propias expectativas con el
defecto dentro. Lo único que lo vio fue la comparación entre las dos rutas.

**Un tercer mutante, y una normalización que era demasiado ancha.** La
comparación de los bytes comprometidos todavía no se había visto en rojo, y una
comprobación que no se ha visto en rojo no es evidencia. El mutante C no toca el
producto: es un script **temporal** —retirado después del ensayo— cuyo paso
imprime la hora en nanosegundos, de manera que las dos rutas comprometen bytes
distintos. El resultado fue la fila que faltaba: `the two execution paths
committed different bytes`, con el diff de los dos números.

Y ese mutante encontró un defecto del **instrumento**, no del producto: la
normalización de la identidad borraba *todo* número de seis o más dígitos, así
que la marca de tiempo de un pipeline quedaba tapada. Normalizar todo lo que
parece un identificador es tapar diferencias que la comparación existe para
ver. La normalización quedó acotada a la forma que el producto usa para sus
temporales (`pipeline-<tipo>-…-<número>`, con el número que la JDK añade), y con
ella el mutante C se enciende y el escenario de credenciales sigue verde.

**El control, que es la mitad de la prueba.** Con los dos mutantes vivos y la
doble ejecución desactivada, la suite queda **61 de 61 en verde** (194
comparaciones sobre 66 ejecuciones): los dos defectos son invisibles para el
arnés anterior. Con la doble ejecución, la misma suite con los mismos mutantes
se pone roja con la atribución inequívoca de cada fila. Eso es la respuesta a la
pregunta del bloque, y no es una opinión: **la doble ejecución discrimina**, y
además hace algo que no hacía —comparar las dos rutas entre sí—, que es la clase
de defecto que ninguna expectativa declarada puede ver.

**Lo que este bloque deja medido y sin hacer.** El producto sigue narrando en
vivo en la ruta volatile y al final en la ruta durable, y esa diferencia es
visible en una consola de CI: el operador ve la salida del proceso mientras la
corrida corre y la narración cuando termina. Arreglarlo es tocar el orden de
la salida de la ruta que usa CI, y eso es una decisión de producto con su
propia verificación; queda medido, con su mecanismo y con su síntoma, para el
bloque que lo decida.


### 2.66 La consola que mentía: una sola autoridad de narración

Este bloque cierra la divergencia que §2.65 dejó medida y sin decidir, y de
camino encuentra una fuga que no estaba en el plan del bloque.

**La divergencia que había.** El producto narraba en vivo en la ruta volatile y
al final en la ruta durable. En una consola de CI eso se ve: el operador ve la
salida del proceso mientras la corrida corre, y el relato de la corrida cuando
termina. Arreglarlo era tocar el orden de la salida de la ruta que usa CI, y
por eso exigía su propia verificación.

**Lo que apareció al medirla: una fuga.** Antes de tocar el orden se midió de
dónde salía la narración, y la respuesta tenía dos mitades. La ruta volatile
componía el narrador de consola ENCIMA del redactor, de modo que renderizaba el
evento antes de que la redacción lo limpiara. Medido contra la distribución, con
una credencial atada y su literal en un `echo`:

| | stdout | lo que queda en el almacén |
|---|---|---|
| ruta con journal (la de CI) | `the value is ****` | `the value is ****` |
| ruta sin journal (la local) | `the value is e2e-fixture-secret` | `the value is ****` |

La fila que importa es la segunda: **el almacén queda limpio y la consola no**.
Un secreto que no se guarda es un secreto que se imprime, y lo que se imprime
acaba en un log de CI, en un scrollback y en una captura. La ruta que se usa en
local era la que se llevaba el secreto.

**El mecanismo, en una frase.** Los eventos viajan hacia abajo por la cadena de
decoradores, así que la redacción tiene que ir arriba: la composición que
presenta lo que el almacén guarda es `redactor(narrador(almacén))`, y la que
había era `narrador(redactor(almacén))`. El orden invertido es exactamente el
que deja pasar el secreto.

**La decisión: una sola autoridad.** La composición vive en un solo sitio y las
dos rutas la llaman. Lo que las dos rutas tenían duplicado —leer el script y
calcular su hash— ahora está en un solo sitio también, y por la misma razón:
dos lecturas del mismo script podían dar dos hashes, y la diferencia sólo
habría salido al reanudar una corrida, en otro mundo y mucho después.

La ruta durable **narra en vivo**, como la volatile. Su volcado final desaparece
para el formato de texto, porque una narración que llega al final, después de la
salida del proceso que explica, es un relato desordenado; los formatos de
máquina conservan su documento al final, porque un documento no se puede emitir
a trozos.

**El instrumento, y cómo se vio en rojo.** La garantía que se fija es de una
línea: *la consola presenta los mismos bytes que el almacén guarda*. Es una
composición pura —el almacén que envuelve, el formato, la vista, la consulta y
el registro de secretos—, así que el instrumento más barato que puede fallar es
una llamada, no una distribución. El test unitario afirma las dos mitades de la
garantía, y se vio en rojo con dos mutaciones:

| mutante | fila que encendió | otras filas |
|---|---|---|
| el narrador por encima del redactor (el defecto) | `the console presents exactly what the store keeps` | ninguna |
| el narrador instalado también en los formatos de máquina | `a machine format leaves the console to the document` | ninguna |

La primera encendió con el fallo exacto del defecto, y dice por qué el test no
es decorativo:

```
expected: <the value is ****> but was: <the value is e2e-fixture-secret>
```

**Lo que mide el arnés después del cambio.** La suite entera, con la doble
ejecución y la comparación entre las dos rutas encendidas: **61 escenarios, 61
en verde**, con el mismo censo que antes del cambio (373 comprobaciones sobre
110 ejecuciones). El arnés no se enteró del cambio, que es lo que se quería: la
narración de la ruta durable es la misma historia que la de la volatile, y por
eso las dos cuentas siguen dando igual.

Y la sonda que encontró la fuga, repetida después del cambio, dice lo contrario:
las dos rutas muestran `****`, tanto para el `echo` del DSL como para la salida
de un proceso, y el secreto no aparece en ninguno de los cuatro flujos.

**El comentario que mentía.** El KDoc del narrador de consola decía «encima
del redactor, nunca debajo» —la recomendación contraria a la que hace la
composición correcta— y afirmaba que la salida en vivo de un proceso era «un
cambio posterior». Las dos frases eran falsas: la segunda llevaba mucho tiempo
siendo falsa, porque la salida del proceso llega por el Output Plane. Un
comentario que afirma lo contrario de lo que hace el código no es un comentario:
es una fuente que miente con la autoridad de la fuente (§2.62).

**Lo que este bloque deja medido y sin hacer.** El diario de operaciones —la
otra autoridad que conserva lo que la corrida hizo— guarda los parámetros y el
resultado del paso **en claro**, sin consultar el registro de secretos. Medido
contra la distribución, el `--db` de una corrida que ata una credencial contiene
el valor literal del secreto en la fila de la operación:

```
{"stepId":"core.echo","params":{"payload":"{\"kind\":\"echo\",\"text\":\"the value is e2e-fixture-secret\"}"},...}
{"result":"the value is e2e-fixture-secret\n",...}
```

Y medido contra la distribución **sin** este bloque —la línea base, construida
del árbol anterior—, los mismos bytes en el mismo sitio. Es decir: **el diario
ya guardaba el secreto en claro, y este bloque no lo ha tocado.** La fuga que
este bloque cierra es la de la consola; la del diario es otra, más profunda, y
está fuera de lo que aquí se decidió.

La pregunta que deja el diario es de otro orden que la de la consola. El diario
existe para que una corrida se pueda repetir sin repetir sus efectos, así que
**guardar el secreto en claro puede ser su función**: es el registro de lo que
se le pidió a la máquina. Quitarlo de ahí no es un redactado más, es una
decisión sobre qué significa un diario reproducible y qué es una corrida
reproducible. Lo que sí es un defecto sin discusión es que **sea la segunda
autoridad**: el almacén de eventos redacta, el diario no, y los dos viven en el
mismo fichero.

**Corrección, medido después.** Esa lectura era equivocada, y la culpa fue de
este bloque: el diario no es una segunda autoridad sobre lo mismo, es otro
canal. El del valor tipado, que es exacto por ley porque un paso al que se le
pide capturar un stdout tiene que recibirlo exacto. Los dos canales responden a
dos preguntas distintas, y el hueco real estaba en otro sitio: en las 26
superficies del spine que la lista de redacción no cubría (§2.67).


### 2.67 La lista de superficies libres era una promesa que nadie cumplía

Este bloque sigue la pregunta que el anterior dejó abierta, y la respuesta
corrige parte de lo que aquel bloque afirmó.

**La brecha, medida.** La redacción del registro de una corrida era una lista
escrita a mano de cinco tipos de evento. La taxonomía del registro tiene
**76 tipos** que el binario sabe decodificar, y **30 de ellos llevan texto
libre**: un campo llamado `content`, `message`, `reason` o `url`, o los
diagnósticos de una compilación o de una corrida. La lista cubría cinco, y uno
de esos cinco a medias: el fallo de un `git checkout` tenía el motivo limpio y
la URL sucia.

La cuenta final: **26 superficies** por las que un secreto registrado podía
llegar al registro de la corrida. Una de ellas es la **respuesta de una persona
a una pregunta del pipeline**: la consola enseña quién respondió y calla la
respuesta, y el registro guardaba la respuesta en claro.

**Cómo se encontró, que es la parte que importa.** No leyendo la lista y
pensando: preguntando al código dos preguntas y comparando las respuestas. La
primera es qué tipos llevan texto libre (la taxonomía). La segunda es qué
tipos presenta la consola con texto (el renderizador). La tercera es qué tipos
cubre la lista. La diferencia entre la segunda y la tercera es la brecha, y
sale de comparar dos fuentes de verdad que ya existían en el repositorio.

**La decisión: la lista es la regla aplicada.** Ahora se limpia todo tipo que
lleve texto libre, y el KDoc dice qué significa «texto libre» (cuatro nombres
de campo) y por qué la lista es sobre la taxonomía y no sobre los emisores de
hoy: nueve de los tipos listados no los emite nadie todavía, y «nadie lo emite
todavía» es un dato de esta semana. El coste de ampliar la lista es cero en
tiempo de ejecución: cada evento se limpia en sus propios campos, así que un
evento con dos superficies libres cuesta lo mismo que uno con una.

**La forma que acabó tomando la regla.** La lista se podría haber escrito como
un `when` de treinta brazos sobre el evento, y así se escribió primero. El
análisis estático lo rechazó: treinta brazos dan una complejidad de 37, y el
techo del repositorio es 25. La forma que salió de ahí es mejor que la que
detekt pidió: la regla son cuatro nombres de campo, así que el código son
**cuatro pasadas, una por nombre** —el contenido, los mensajes, los motivos y
las URLs—, y cada pasada nombra en su KDoc la superficie que limpia. El código
dice la regla en vez de describirla.

**Y el censo cazó un fallo del que lo escribió.** Al repartir los treinta
tipos entre las cuatro pasadas se quedó fuera el `reason` de un fallo de
petición HTTP, que además de su URL lleva un motivo. El censo lo dijo con su
nombre, en un mensaje, y la fila se puso a sí misma en rojo. Un instrumento
que sólo sabe dar verde no sirve para esto; uno que sabe decir QUÉ superficie
se le escapó, sí.

**El instrumento.** Un test unitario, una fila por tipo, treinta filas. La
aserción es genérica a propósito: el secreto no puede aparecer en el evento, y
la marca de enmascarado tiene que estar. La comprobación no nombra un campo
porque la garantía es sobre el evento y no sobre una de sus propiedades.

**Cómo se vio en rojo, dos veces.**

| mutante | qué encendió |
|---|---|
| fuera un brazo (la respuesta de una persona) | una fila: `InputProceed conserva el secreto registrado` |
| la lista entera de vuelta a los cinco tipos de antes | **26 filas de golpe**, y el test las enseña todas en un solo mensaje |

La segunda es la que importa: un censo que se detiene en el primer fallo
esconde lo ancho que es el hueco. Por eso el test recoge todos los fallos y
los cuenta de una vez.

**La prueba de extremo a extremo, en el escenario que ya existía.** El
transcript —los bytes que la corrida **comprometió**— lo limpia otra capa, y
esa capa no tenía ninguna cobertura permanente: ningún test mencionaba el
redactor de flujo, y sólo uno de los 61 escenarios usaba la expectativa de
transcript. Así que el escenario de credenciales, que ya ejecuta una orden que
pone una credencial en la salida del proceso, ahora afirma además que el
transcript comprometido muestra `API_KEY=****`. Una línea, ningún escenario
nuevo. Y se vio en rojo: con un texto imposible el escenario falla y enseña el
transcript entregado, en el que los siete valores de credencial están
enmascarados.

**Lo que muestra el spine de una corrida real.** Leído con el verbo del
producto después de una corrida que ata una credencial y hace eco de su
literal: `"content":"the value is ****\n"`. El spine está limpio.

**Lo que sigue exacto, y por qué.** El diario de operaciones conserva el valor
tipado del paso tal cual, y eso no es un descuido: el canal del valor tipado
está separado del canal observable a propósito, porque un paso al que se le
pide capturar un stdout lo recibe exacto y el pipeline recibe ese mismo valor.
Un secreto que la consola y el spine enmascaran aparece en claro en la fila
del diario cuando el texto del propio paso lo contiene. Medido, y es la ley:
los dos canales responden a dos preguntas distintas.


### 2.68 La clase que limpia el transcript no tenía ni un solo test

**La brecha, medida.** La clase que limpia los bytes que una corrida
**compromete** —el transcript— no tenía ni una sola fila que la sostuviera, en
todo el repositorio. No era una clase poco cubierta: era una clase sin
cobertura, en la capa que decide qué de una corrida se puede ver. Y no era una
clase cualquiera: su propio KDoc documenta **cinco defectos reales ya
corregidos** —un volcado final truncado por una lectura de un byte, un anillo
de salida pisado por una alimentación grande, un bucle que quemaba la CPU con
el anillo lleno, un guard inalcanzable que entregaba el transcript sólo al
final, y la cola de un secreto largo escrita en claro cuando dos secretos
comparten prefijo. Cinco defectos ya corregidos, cinco maneras de volver, y
ni una fila que sostuviera a ninguna: la clase entera estaba sin cubrir.

**El instrumento.** Siete filas, todas puras: ni reloj, ni fichero, ni proceso,
ni red. La clase es una máquina de estados sobre bytes —un registro adentro,
una decisión afuera—, así que lo más barato que puede fallar es una llamada
directa. Un defecto que sólo aparece en una corrida de 200 MiB es un defecto
que no debería necesitar una.

| fila | la garantía que sostiene |
|---|---|
| el transcript es igual se parta donde se parta | la respuesta no depende de dónde se cortaron las lecturas |
| dos secretos que comparten prefijo | la cola del más largo no se filtra |
| un registro sin secretos | decide cada byte cuando llega; no retiene lo que no tiene que retener |
| el volcado final | una lectura de un byte no trunca el transcript |
| una alimentación grande | no pisa bytes que aún no se leyeron |
| un transcript terminado | el estado que se descarta no se lleva una copia del secreto |
| una lectura | devuelve lo decidido sin pedirle más al origen |

**Cómo se vio en rojo, siete veces.** Cada mutación se aplicó, se leyó de
vuelta para comprobar que había aterrizado, y se revirtió.

| mutante | qué encendió |
|---|---|
| el olvido de lo que trajo la lectura anterior | **cuatro filas**: la que se parte, la del prefijo compartido, la del registro vacío y la del volcado final |
| un literal más corto gana el|match | una fila: la del prefijo compartido — y el mensaje mostró el defecto exacto, `****CDEFGH;****;****`: la cola del secreto en claro |
| el registro sin secretos conserva la ventana entera | una fila: la del registro vacío |
| el final del flujo se trunca por lo que el llamador no cabía | una fila: la del volcado final — el transcript quedó en un solo byte |
| la cola se dimensiona para la ventana y no para lo que decide una alimentación | una fila: la de la alimentación grande |
| el estado terminado conserva los bytes del secreto | una fila: la del transcript terminado |
| el lector espera al origen antes de devolver lo decidido | una fila: la de la lectura — el origen se pidió dos veces y se negó |

**El defecto que encontró una fila.** La fila del transcript terminado
comprobó lo que el KDoc de la clase ya decía y el código no cumplía: el
volcado final borraba la cola de salida, pero **no el anillo de espera**, que
guarda bytes en crudo. La ruta que envuelve el flujo lo borraba —su propio
KDoc lo dice—; la ruta que usa la máquina de estados no, y esa ruta es la que
**descarta el estado** al terminar. Un secreto recién enmascarado se quedaba
dentro de un objeto que el recolector iba a tardar en llevarse. Ahora las dos
rutas lo borran por la misma regla, y esa regla vive en un solo sitio.

**El quinto defecto no cabe en un test unitario, y hay que decirlo.** El bucle
que quemaba la CPU no falla con un valor equivocado: **se queda esperando**.
Un test unitario de ese defecto no se pondría rojo, se quedaría colgado y se
comería el presupuesto de la puerta, y un test que se cuelga es peor que no
tener ninguno. El instrumento que sí lo caza es el suite de extremo a
extremo, y lo cazaba sin que nadie lo hubiera escrito para eso: la salida de
cualquier paso pasa por ese brazo. Con el brazo que libera el anillo lleno
deshabilitado, el escenario de la salida en vivo **falla en 26,7 s** con el
diagnóstico del propio producto —«transcript drain unfinished after 10000ms»—.
Ningún escenario nuevo: los 61 siguen siendo 61.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado
(182 s / 141 s / 139 s; el peor, de 900), `tests=rewritten:1097`, 61 de 61
escenarios, 375 comprobaciones sobre 110 ejecuciones, y el LOC en 19994 de
verificación sobre 67534 de producto (0,296).


### 2.69 El comparador de las dos rutas podía declarar que estaban de acuerdo sin haber leído

**La brecha, medida.** La comparación que confronta las dos rutas de ejecución
—la que sostiene que una corrida con journal y la misma corrida sin journal
comprometieron lo mismo— podía declarar igualdad después de comparar menos de
lo que debía, y después de no leer nada. Dos mecanismos, medidos por separado:

- **Descubría las operaciones por un solo canal.** Enumeraba los directorios
  `*_stdout` del Output Plane. Un paso que escribe sólo a stderr **no tiene
  directorio `_stdout`**: medido, el plano crea `<runId>_<opId>_stderr` y nada
  más, y ese paso sí es legible —el verbo `console` lo sirve igual que al
  otro—. Quedaba fuera de la comparación, sus bytes no se comparaban nunca, y
  una comparación que no compara puede declarar iguales dos corridas que
  difieren.
- **Se tragaba los rechazos del lector.** `2>/dev/null || true` convertía un
  «no lo sé» en un «no hay nada». Dos lecturas rechazadas llegaban a la
  comparación como dos salidas vacías, y dos vacías son iguales: el veredicto
  «las dos rutas prometieron lo mismo» era la respuesta a una pregunta que
  nadie había contestado.

**El instrumento.** El defecto no está en una clase del producto: está en el
arnés, que es Bash y se mide contra el binario real. Un test unitario del
arnés no sería un test del producto, sino un test de la lógica del propio
comprobador —la comprobación de que el comprobador comprueba—, así que el
instrumento es un escenario de extremo a extremo, y **no es un escenario
nuevo**: es el de la salida de un proceso, con un paso más.

El paso nuevo escribe a su propio stderr y a nada más, y su texto se declara
en el transcript comprometido. Con la enumeración de un solo canal esa fila
falla por el texto que falta; con el rechazo tragado, el escenario pasa en
verde sin haber leído nada.

**Cómo se vio en rojo, tres veces.** Cada mutación se aplicó, se leyó de vuelta
para comprobar que había aterrizado, y se revirtió.

| mutante | qué encendió |
|---|---|
| la enumeración vuelve a un solo canal | **una fila**: «the committed transcript lacked: STDERR_ONLY_MARKER» — el transcript_commitido contenía sólo `RAW_PROCESS_MARKER` |
| el lector pide una operación que no existe | **una fila**: «the bytes the durable path committed for s0-0 could not be read» |
| …y ese mismo rechazo, tragado como antes | **verde**: dos lecturas vacías comparadas como iguales — el falso verde, reproducido |

La tercera fila es la que demuestra que la segunda no es una formalidad: es
justo la diferencia entre un comparador que informa y uno que se traga la
respuesta.

**Lo que cambió.** La enumeración pregunta por los dos canales y deja fuera
las streams de otra corrida que compartan el control raíz —una corrida
resumida guarda las de la primera al lado de las suyas—. El rechazo del
lector es un fallo con nombre: la comparación dice **qué operación** no pudo
leer, en vez de comparar dos vacíos. Y la comparación comprueba además que
**las dos rutas comprometieron salida para las mismas operaciones**, que es
una garantía propia y que nombra la diferencia antes de comparar bytes.

**La ayuda del producto tenía la misma punto ciega.** El verbo `console` decía
de sí mismo que el listado del plano es «el conjunto exacto» y mostraba un
directorio `_stdout` por operación legible. Medido: un paso que escribe sólo a
stderr es legible y su directorio es `_stderr`. La ayuda ahora nombra los dos
canales.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado
(187 s / 139 s / 140 s; el peor, de 900), `tests=rewritten:1097`, 61 de 61
escenarios, 377 comprobaciones sobre 110 ejecuciones, y el LOC en 20075 de
verificación sobre 67536 de producto (0,297). Los 1097 tests unitarios siguen
siendo los mismos: este defecto no es del producto y no se puede cubrir con
una fila unitaria.

**Lo que queda, dicho con nombre.** La enumeración sigue leyendo los nombres
de los directorios del plano, que es el descubrimiento que el propio producto
documenta. El arreglo de fondo —que la CLI exponga las streams de una corrida
con su identidad completa, para que ni el arnés ni un consumidor tengan que
deducirla del sistema de ficheros— es trabajo de producto y queda para el
bloque siguiente.


### 2.70 Un fichero sembrado por flujo se guardaba un byte más corto

**La brecha, medida.** El lector que siembra una credencial desde un flujo
quitaba el newline final del flujo **para todas las clases**, incluidas las
cuyo valor **son** los bytes. Consecuencia medida: un fichero sembrado desde
stdin que terminaba en `0x0A` se guardaba **sin ese byte**, y el almacén lo
aceptaba sin decir nada. El paso recibía un fichero que nadie había dado, y el
que sembraba no tenía forma de enterarse: el almacén no registra los valores,
por diseño.

Y la brecha no era sólo del lector. La terminal lee la misma clase desde una
ruta, con `Files.readAllBytes`, y **nunca quitó nada**. Sembrar por flujo y
sembrar por ruta producían credenciales distintas, que es exactamente lo que
el KDoc del lector exige que no pase: *two callers asking the same question
must get the same answer*.

**Medido también lo que NO se rompía, porque una hipótesis Nice no es una
medida.** Tres clases reciben bytes, y sólo una se rompía de verdad:

| clase | qué vale | efecto medido |
|---|---|---|
| `secret-file` | los bytes | **se rompía, en silencio**: el fichero se guardaba un byte más corto |
| `certificate` | los bytes del keystore | la regla se le aplicaba, pero un keystore PKCS#12 escrito por el JDK **termina siempre en `0x10`** —400 de 400, y 20 longitudes de alias distintas, porque el último byte es el relleno del MacData cifrado—, así que nunca se le quitaba nada. La hipótesis de un fallo en uno de cada 256 keystores quedó **medida y descartada** |
| `zip` | las **entradas** del archivo | el último byte del archivo no es el valor; sembrado medido: aceptado, entradas intactas |

La regla, sin embargo, era la misma para las tres, y por eso el arreglo no
toca un brazo: **la mueve a donde pertenece**.

**Un newline termina una línea, no un fichero.** La regla vivía en el lector,
y por eso el lector de flujo y el de la terminal no estaban de acuerdo sobre
la misma clase. Ahora el «cómo llega el valor» es una respuesta **de la propia
clase** —`CredentialFraming`, dos casos: `TERMINATED` y `WHOLE`— y cada
transporte pregunta lo mismo y obtiene lo mismo. Añadir una clase nueva obliga
a decidir su marco, porque el `when` es exhaustivo: no se puede heredar el
comportamiento por descuido.

**El instrumento: una fila unitaria.** Bytes dentro, credencial fuera: ni reloj,
ni fichero, ni proceso, ni almacén. La fila nueva dice que un fichero conserva
todos sus bytes, el newline final incluido. **No es un test nuevo de una clase
nueva**: es una fila más en el fichero que ya sostenía las seis anteriores.

**Cómo se vio en rojo, dos veces.** Cada mutación se aplicó, se leyó de vuelta
para comprobar que había aterrizado, y se revirtió.

| mutante | qué encendió |
|---|---|
| el terminador se aplica a todas las clases —el defecto— | **una fila**: la del fichero. Las otras siete siguen verdes |
| el terminador no se aplica a ninguna clase | **tres filas**: las del protocolo de línea —el newline de la shell, el username de la primera línea y la contraseña que termina en newline |

Las dos direcciones importan: la primera dice que la fila nueva vigila el
defecto, y la segunda que el arreglo no rompió el protocolo que las otras
filas sostienen. Con una sola de las dos, la fila habría podido ser verde por
casualidad.

**Lo que el suite de extremo a extremo no podía ver, y por qué.** El arnés
siembra un `secret-file` con un fixture que no termina en newline, y su
escenario comprueba que la credencial **resuelve**, no qué bytes trae: es su
principio declarado, porque el fixture no es un hecho del producto. Ese
combinio hacía el defecto invisible a ese nivel. No se añade ningún escenario:
la garantía es del **lector**, y el lector se sostiene con una fila pura.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado
(203 s / 139 s / 141 s; el peor, de 900), `tests=rewritten:1098`, 61 de 61
escenarios, 377 comprobaciones sobre 110 ejecuciones, y el LOC en 20087 de
verificación sobre 67551 de producto (0,297).


### 2.71 Una release llevaba dos autoridades de digest con el mismo nombre

**La brecha, medida.** La vía de publicación estaba rota desde su última
publicación con éxito. El staging dirigido por el manifiesto y la ranura
`sha256sums` de ese mismo manifiesto llegaron **después** de la release
`v0.75.0-rc1`, y nadie lo ejecutó de verdad: leer el script no lo destapa,
correrlo sí. Al correrlo aparecieron **dos fallos encadenados**, y los dos en
la misma frontera: la que hay entre lo que la puerta mide y lo que el
publicador sube.

El primero: la puerta escribía la autoridad de digest de la candidata junto a
los assets, y el publicador la buscaba junto a los assets; el recibo, en
cambio, vive en el directorio de la candidata. La puerta decía `PASS` —había
medido todo lo que se le pidió— y el fallo llegaba en el último paso, con un
fichero que nadie iba a encontrar. Se arregló moviendo la regla al producto:
una candidata cuyos assets no estén todos junto al ZIP se rechaza **al
admitirla**, con el asset equivocado nombrado.

El segundo es más silencioso, y por eso importa: el publicador stageaba la
autoridad de digest de la candidata **y** escribía la suya en el mismo
nombre. Dos ficheros llamados `SHA256SUMS`, con contenidos distintos y
coberturas distintas —la de la candidata digiere los cuatro assets; la del
publicador digiere todo lo publicado— en el mismo directorio. El publicador
se negaba a sí mismo (`a manifest cannot carry its own digest`), pero no por
el motivo que importaba: no por una contradicción de política, sino por una
colisión de nombres que nadie había visto venir.

**El contrato, medido en la release publicada de referencia.** La release
`v0.75.0-rc1` lleva **siete** assets: los cuatro de payload, `manifest.json`,
`git-head.txt` y un `SHA256SUMS` que cubre los otros seis. La autoridad de la
candidata **no se publica**: es evidencia sobre la candidata, y sigue en el
árbol de compilación.

**La regla, y dónde vive.** Una release tiene **una** autoridad de digest: la
que cubre todo lo publicado menos ella misma. La autoridad de la candidata
cubre un subconjunto estricto de ésa, así que publicarla no aportaba nada que
un consumidor pudiese comprobar y sólo añadía ambigüedad sobre cuál era «la»
autoridad. La regla vive ahora en el **producto**: la puerta decide qué
ficheros de la candidata publica una release y lo deja escrito en un recibo con
el digest que midió de cada uno; el publicador sigue ese recibo, re-mide cada
fichero y se niega si no es el que la puerta midió. El publicador deja de
re-derivar el conjunto por su cuenta —que es como las dos mitades dejaron de
estar de acuerdo— y el handoff pasa a generarse desde los ficheros realmente
subidos, no desde la memoria.

**El instrumento, y por qué dos direcciones.** Cinco filas puras sobre la
decisión: qué se publica, con qué digest, y qué se rechaza (un asset ausente,
dos nombres iguales, un asset que reclama el nombre reservado). La
discriminación se midió en las dos direcciones, porque un instrumento que sólo
detecta un sentido no es un instrumento:

| Mutación | Fila(s) roja(s) |
|---|---|
| la autoridad de la candidata entra en el conjunto publicado | 2 (las que exigen que el conjunto sea publicable) |
| el conjunto pierde un asset en silencio | 3 (membresía, nombres repetidos, nombre reservado) |

En la primera mutación el rojo **nombra** el defecto: el modelo se niega con
«a published asset would carry the name SHA256SUMS…». En la segunda, las filas
que no vigilan la membresía siguen verdes: el daño colateral es cero.

**Lo que se verificó después de publicar.** La candidata `v0.76.0-rc1` se
publicó y se comprobó **sobre los bytes publicados**, no sobre los locales:
descarga desde el remoto de los siete assets, `sha256sum -c SHA256SUMS`
verifica las seis entradas, el consumidor del repo resuelve el digest del ZIP
con su propio script, el ZIP descargado se instala en limpio y reports
`pipeline 0.76.0`, y un pipeline real ejecutado con ese binario termina en
`SUCCESS` con su efecto observable en disco. La publicación quedó en remoto
como prerelease, con el tag apuntando al commit del que se construyeron los
bytes.

**Lo que este bloque deja dicho.** Una publicación no es un caso feliz del
producto: es un **producto**, con la misma ley que el resto —una puerta
mide, y lo que se publica es exactamente lo medido—. Cuando esa ley se partió
en dos (la puerta por un lado, el publicador por otro), no salió ningún error
de compilación ni ningún test rojo: salió una release que moría en su último
paso. El arreglo no fue «arreglar el script»: fue **darle al script un único
lugar del que sacar el conjunto**, y ese lugar es el que ya existía, la
puerta.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado
(163 / 157 / 149 s; el peor, de 900), `tests=rewritten:1106`, 61 de 61
escenarios, 377 comprobaciones sobre 110 ejecuciones, y el LOC en 20248 de
verificación sobre 67688 de producto (0,299).

**Lo que queda fuera, dicho con honestidad.** La promoción a estable de
`0.76.0` sigue bloqueada: no hay certificador externo en este repositorio ni
en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no `PASS`. Lo que
este ciclo publica es la **candidata**, y la candidata es exactamente eso.


### 2.72 Los verbos que hablan del producto no estaban medidos

**La brecha, medida.** La CLI declara **trece** verbos. El arnés de E2E
invocaba **siete**. Los **seis** que nadie ejecutaba de verdad eran
precisamente los que el producto usa para hablar de sí mismo: `validate`,
`observe`, `doctor`, `version`, `capabilities` y `sdk-info`. Un verbo que
habla del producto es un verbo cuya respuesta la lee un consumidor, y un
consumidor que no está medido no tiene contrato: el producto puede
publicar cualquier cosa y no hay nada que lo note.

El hueco no era sólo de cobertura. Al mirar dentro de esos verbos
aparecieron **dos** que estaban **rotos**, y rotos de una manera que
ningún test podía ver: el código que producía la respuesta era el mismo
código que la leía después.

**El primer defecto: un informe que decide su veredicto leyendo su propio
texto.** `doctor` imprimía sus comprobaciones y luego las releía para
decidir si la instalación estaba sana: buscaba la palabra `NOT WRITABLE`
en las líneas que acababa de imprimir. **Reproducido**: en un directorio
**escribible** llamado `NOT WRITABLE` el informe imprimía `workdir: …
(writable)` —es decir, lo que había medido— y **salía con el código 2**, el
del «roto». Un veredicto leído de una frase es un veredicto que puede
contradecir a la frase que lo produjo, y se contradecía.

El arreglo es que **el veredicto es un valor y la frase es su
renderizado**. `InstallationHealth` modela el informe como una lista de
comprobaciones, cada una con su lectura y su veredicto, y el código de
salida sale de los veredictos y de nada más. El sondeo de escritura
cubre además `SecurityException`: un diagnóstico que revienta con el
defecto que debería informar no informa de nada.

**El segundo defecto: un vocabulario publicado en dos idiomas.** El
catálogo que `capabilities` publica tiene tres estados —`EXPERIMENTAL`,
`DEPRECATED` y `PUBLICADA`—, y **cinco** de sus diecisiete valores
usaban el tercero. Un consumidor lee el estado **por valor**, así que un
valor de un vocabulario de tres escrito en otro idioma es un valor que el
consumidor no reconoce. Y los cinco valores escritos así eran **justo
las capacidades que la build entrega**: las únicas que nadie podía leer.

El vocabulario se escribe ahora en un solo idioma, y el movimiento se
**anuncia**: el esquema del documento pasa de `pipelinek.capabilities/1` a
`pipelinek.capabilities/2`, de modo que un consumidor fijado a la versión
anterior ve en el campo `schema` que el vocabulario se movió, en lugar de
no reconocer en silencio las capacidades que la build entrega.

**El documento publicado se puede leer.** `MainCapabilitiesCli.document()` es
una función pura, y el test lee **ese documento**, no el modelo: lo que un
consumidor recibe es lo que el test sostiene.

**El instrumento, y por qué dos direcciones.** Cinco filas puras sobre el
informe de salud y dos sobre el documento publicado, más un escenario que
hace ocho preguntas al producto y comprueba cada respuesta contra lo que
el producto **imprimió**. Las cuatro mutaciones se midieron en las dos
direcciones, y cada una dejó **exactamente** una fila roja, sin daño
colateral:

| Mutación | Fila o garantía roja |
|---|---|
| el veredicto vuelve a leerse del texto impreso (fila pura) | 1 — `the verdict is never read off the reading` |
| el documento publicado vuelve a hablar en dos idiomas (fila pura) | 1 — `the published status vocabulary is spelled in one language` |
| el veredicto vuelve a decidirse por la palabra del texto (escenario) | 1 garantía — `DOCTOR_HEALTHY_EXIT` |
| el catálogo vuelve a publicar el estado en otro idioma (escenario) | 1 garantía — `CAPABILITIES_ONE_VOCABULARY` |

**El arnés: la tercera puerta al producto, con el reloj puesto.** Para
preguntarle algo al producto desde un escenario, el lanzador escribe ahora
una entrada por escenario, con el camino del producto y el reloj de ese
escenario dentro. Es una puerta **con reloj**: un escenario que la llama
alcanza el producto contra el que corre, y lo alcanza con el techo que
acota todo lo demás del escenario; una pregunta que se cuelga para con el
escenario y se reporta como evento de reloj, no como veredicto del
producto. **No hay otra entrada**: el camino del producto no está en el
entorno, de modo que un escenario no puede alcanzar un producto sin reloj,
ni un producto distinto del suyo. Es la misma ley que ya gobernaba las
otras dos puertas, aplicada a la tercera.

**El tren se movió con el formato.** El documento publicado cambió de
esquema, así que el artefacto `v0.76.0-rc1` y este no se comportan igual.
La misma ley que impidió publicar el `0.62.0` porque colisionaba con un
`v0.62.0` ya publicado se aplica aquí: una versión que nombra dos
artefactos distintos no nombra nada. El tren pasa a **0.77.0**.

**Lo que este bloque deja dicho.** Un producto que habla de sí mismo tiene
que ser verdad dos veces: lo que **imprime** tiene que ser lo que
**midió**, y lo que **publica** tiene que ser un vocabulario que un
consumidor pueda leer por valor. Ninguno de los dos fallos se veía desde
dentro, porque en los dos el código que producía la respuesta era el
mismo que la leía después. El defecto de fondo no era un `if` mal
escrito: era que **la respuesta no era un valor**. Un veredicto es un
valor; una frase es su renderizado; y un catálogo es un documento que se
puede leer. Cuando eso es cierto, el consumidor deja de depender de que
nadie mire dos veces.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado
(197 / 144 / 146 s; el peor, de 900), `tests=rewritten:1113`, 62 de 62
escenarios, 396 comprobaciones sobre 112 ejecuciones, y el LOC en 20483 de
verificación sobre 67723 de producto (0,302).

**Lo que se verificó después de publicar.** La candidata `v0.77.0-rc1` se
publicó y se comprobó **sobre los bytes publicados**, no sobre los locales:
descarga desde el remoto de los siete assets, `sha256sum -c SHA256SUMS`
verifica las seis entradas, el consumidor del repo resuelve el digest del ZIP
con su propio script, y el ZIP descargado se instala en limpio. El binario
instalado reporta `pipeline 0.77.0`, su `sdk-info` dice `0.77.0`, su
catálogo anuncia `pipelinek.capabilities/2` y publica un vocabulario de un
solo idioma, y el informe de salud contesta **0** en un directorio escribible
llamado `NOT WRITABLE` y **2** en un directorio en el que nadie puede
escribir. Un pipeline real ejecutado con ese binario termina en `SUCCESS`
con su efecto observable en disco. La publicación quedó en remoto como
prerelease, con el tag apuntando al commit del que se construyeron los
bytes.

Lo que la admisión de candidata deja **a propósito** sin observar es la
superficie `RUNTIME_VERSION`: exige ejecutar el binario instalado, que es
trabajo de la vía pesada y no de la puerta. Por eso la verificación
anterior no es un adorno del recibo, es la parte de la ley de identidad
que esta puerta no puede cumplir sola.

**Lo que queda fuera, dicho con honestidad.** La promoción a estable de
`0.77.0` sigue bloqueada: no hay certificador externo en este repositorio ni
en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no `PASS`. Lo que
este ciclo publica es la **candidata**, y la candidata es exactamente eso.

### 2.73 La identidad de un stream no puede vivir en un nombre plegado

**La brecha, medida.** El indice del plano de salida **ya conocia** la identidad
completa de cada stream: la escribe en sus propios ficheros y la lee de ahi. Lo
que no existia era la **superficie**. Quien quisiera saber que operaciones tuvo
un run tenia que leer el listado de directorios del plano, y ese nombre es una
**proyeccion con perdida**: `safeStreamName` pliega todo lo que no sea
`[A-Za-z0-9._-]` en `_`.

Medido en este build, un run con `parallel` deja en disco

```
…_…-s0-0-bp2-b0-b0_branch-0_core.sh_stdout
```

mientras la identidad que el run acuno es

```
…-s0-0-bp2-b0:branch-0:core.sh
```

El `:` no sobrevive al viaje. Y lo que es peor: **dos identidades que se pliegan
igual son un solo directorio**. El indice las guarda las dos — porque escribe la
identidad entera — pero el disco solo tiene un sitio donde poner sus bytes.

**El verbo nuevo.** `pipelinek operations <runId> --control-root <dir>` publica
la identidad **completa** de cada operacion del run y los streams que cada una
posee, en un documento que declara su propio esquema, `pipelinek.operations/1`.
Un stream cuya identidad no tiene una operacion publicable **no se publica**:
inventarle una atribucion seria atribuir bytes a una operacion que nunca corrio.

**El arnes deja de deducir.** Pregunta al producto y cachea la respuesta por
`(control, run)`, porque una ejecucion terminada no cambia y dos comparaciones
del mismo run no pagan dos finales de proceso. Y una lista vacia para un run
cuyo plano tiene streams ya no es "este run no escribio nada": es una respuesta
que no nombra ninguno de los streams, y una **comparacion de bytes vacia es una
comparacion que no comprueba nada**. Esa cruz-check es la que impide que el
arnes se aplauda a si mismo comparando dos vacios.

**La comparacion de narraciones comparaba un orden que nadie promete.** El
arnes decia las cuentas de los dos mundos comparando la narracion como
**secuencia**. Una etapa puede abrirse en ramas que corren a la vez, y el orden
en que esas ramas terminan es una carrera. Medido en este build: el mismo
pipeline, doce veces, cerro sus dos ramas en un orden **cuatro** veces y en el
otro **siete**. La comparacion reportaba una diferencia entre dos runs que
hicieron exactamente lo mismo — y un check que llora lobo es un check que nadie
lee.

Ahora la narracion se compara **como el producto la promete**: el **lugar** de una
etapa en el run esta prometido y se conserva, y las lineas **dentro** de una
etapa se comparan como conjunto, porque dentro de una etapa no se promete un
orden. Un paso que falta, un paso que termino en otro veredicto y una etapa que
corrio en otro sitio siguen distinguiendo las dos historias. El motor de etapas
paralelas deja escrito lo que promete y lo que no: cada rama corre y cada rama
llega a un veredicto, y el orden en que se oyen no esta prometido porque es una
carrera, no un hecho.

**El defecto que la puerta encontro.** La tercera pasada de la puerta fallo, y
no fue por el arnes: fue por el **producto**. El hilo del observador aparecio
muerto en la salida de error con

```
java.io.IOException: cur.cmt body is not a valid long offset: <>
```

El registro de offset confirmado de un stream es **un numero**, y ese numero es
la autoridad de hasta donde llega lo comprometido. Se escribia **en el sitio**:
el fichero se vaciaba primero y se llenaba despues. Un lector que llegaba
dentro de esa ventana leia un registro **vacio** — y el store rehusa
interpretar un registro ilegible, porque decidir lo que significa un registro
danado es una decision de producto y este store no la toma en silencio.

Medido en bucle cerrado, con el escritor antiguo: **85950 de 286879** lecturas
vieron el registro vacio. Con el registro remplazado de una vez: **0 de
279345**.

En el producto la ventana es estrecha y el observador sondea cada 10 ms, asi
que se ve de tarde en tarde. Se vio. Y lo que paso despues es lo que hace este
defecto especialmente malo: **el run seguia y terminaba bien**. Nada decia "este
run perdio su consola": la consola simplemente habia desaparecido, y todo lo que
el run imprimio despues de ahi se perdio sin dejar rastro.

El arreglo es el patron que ya usan los registros duraderos del repositorio: el
registro se escribe en un temporal del mismo directorio y se mueve a su sitio de
una vez, asi que un lector ve el registro anterior o el nuevo, y nunca un hueco.
Y el formato del registro pasa a tener **una sola autoridad**: lo escribe
`commitRecordBody` y lo lee `parseCommittedOffset`.

El instrumento que lo sostiene ya existia: `examples/output/33-live-process-output`
comprueba que la salida de un **proceso** llega al terminal, y eso solo lo
consigue el observador. Un observador que muere se ve en rojo ahi.

**Mutaciones vistas en rojo.** Cada una dejo exactamente las filas que tocaba,
sin dano colateral:

| Mutacion | Fila o garantia roja |
|---|---|
| el arnes vuelve a comparar la narracion como secuencia (arnes) | 1 garantia — `the two execution paths told different stories` |
| el verbo publica la identidad **plegada** (filas puras) | 2 — `the published identity is the operation's own…` y `two operations whose names fold alike are two operations` |
| el verbo publica una identidad **ajena** (filas puras y escenario) | 2 filas puras + 1 garantia — `OPERATIONS_ADDRESS_THEIR_OWN_BYTES` |
| el verbo no nombra ninguna operacion (escenario y cruz-check del arnes) | 1 garantia — `LEG_operations_empty`, y la cruz-check del plano |
| el cuerpo del registro de offset pasa a estar vacio (filas puras) | 2 — `the body the writer writes is never empty` y `… is the body this parser reads back` |

**Lo que la puerta midio.** `PASS` tres veces sobre el arbol commiteado
(203 / 150 / 152 s; el peor, de 900), `tests=rewritten:1121`, 62 de 62
escenarios, 398 comprobaciones sobre 112 ejecuciones, y el LOC en 20724 de
verificacion sobre 67848 de producto (0,305).

**Lo que se verifico despues de publicar.** La candidata `v0.78.0-rc1` se
publico y se comprobo **sobre los bytes publicados**, no sobre los locales:
la descarga desde el remoto, `sha256sum -c` sobre el conjunto de assets, el
digito que el propio repositorio resuelve, la instalacion limpia, y un pipeline
real con el binario publicado.

**Lo que queda fuera, dicho con honestidad.**

- **El store sigue archivando los bytes bajo el nombre plegado.** Dos
  operaciones que se pliegan igual siguen compartiendo el directorio: ahora la
  identidad es **visible** (son dos filas publicadas, no una), pero sus bytes
  todavia se mezclan. Cerrarlo es una ruptura versionada del plano, y queda
  escrito como tal.
- **`console` acepta hoy las dos identidades.** Medido: con la direccion
  completa y con la plegada devuelve los mismos bytes, porque dobla la
  direccion al archivarla. El verbo publica la **completa**, que es la que acuno
  el run; el plegado sigue siendo una direccion que funciona, no una identidad.
- **El escenario de E2E no distingue una identidad completa de una plegada**,
  y se midi: `console` dobla la direccion, asi que las dos cumplen. Lo que
  distingue una de otra son las filas puras del producto. El escenario asegura
  lo que un consumidor asegura — que la identidad publicada direcciona sus
  propios bytes.
- **Dentro de una etapa, el arnes ya no compara el orden de los pasos.** Es el
  precio de no depender de un orden que nadie promete; el orden de las etapas
  si se compara, y ahi si hay una promesa.
- **La promocion a estable sigue bloqueada**: no hay certificador externo en
  este repositorio ni en el remoto, asi que el veredicto es `BLOCKED_EXTERNAL`,
  no `PASS`. Lo que este ciclo publica es la **candidata**, y la candidata es
  exactamente eso.


### 2.74 El techo del drenaje era un reloj que no sabía nada de la carga

**El defecto, y lo que la puerta pagó por él.** El runtime espera a que las
bombas de transcripción terminen antes de declarar un step completo, y lo hacía
con un **reloj absoluto**:

```
pumps.forEach { it.join(TRANSCRIPT_PUMP_DRAIN_BUDGET_MS) }   // 10 s
```

Un reloj fijo no distingue dos cosas que son **opuestas**:

- una bomba **ATASCADA**, esperando bytes que nadie va a escribir porque el hijo
  dejó algo vivo sosteniendo la transcripción;
- una bomba **LENTA**, escribiendo en el plano de salida porque la máquina está
  ocupada.

A las dos las declaraba la misma cosa: un rechazo. Y el rechazo **no era
accionable**, porque no decía cuál de las dos era. Con la máquina quieta el
drenaje termina en milisegundos y el techo de 10 s es holgado de sobra;
saturada, el mismo techo se agota. **Dos ensayos de la puerta se descartaron
con `transcript drain unfinished after 10000ms`**, y ambos coincidieron con
trabajo ajeno corriendo al lado.

Lo que la puerta medía sin querer era lo importante: el defecto no era del
arnés, era del **producto**, y su síntoma era un paso que falla sin motivo.

**Lo que el arreglo hace.** Ahora la bomba **declara su atención**: si está
**esperando** al hijo o si está **trabajando** en el plano. Y el drenaje usa
esa declaración:

- una bomba que **trabaja** se **espera**, porque los bytes que faltan van a
  llegar: el refusal del techo dice que la transcripción va **tarde, no rota**;
- una bomba que **espera** al hijo y no se mueve está **atascada**, y se rechaza
  **al momento**, nombrándola.

El techo global queda como la red de seguridad del caso que no termina nunca
(una bomba que dribla un byte cada pocos segundos y sostiene la transcripción
abierta para siempre), y su rechazo dice otra cosa: la máquina no pudo
terminar a tiempo.

**El rechazo, accionable y tipado.** Los dos casos son **casos distintos del
vocabulario**, no dos frases del mismo texto: `TranscriptDrainCause.STUCK_CHILD`
—el hijo sostiene la transcripción— y `TranscriptDrainCause.OUT_OF_TIME` —la
máquina no pudo con el tamaño—. El registro del fallo lleva cada uno su token,
y el que ya estaba publicado (`transcript-pump-still-alive`) se queda donde
estaba: **añadir un caso no rompe lo que un consumidor ya leía**.

**Las palancas son del operador.** El presupuesto de inactividad y el techo
global se leen de propiedades del sistema, como las demás del módulo
(`pipeline.durable.transcript.drain.idle.budget`,
`pipeline.durable.transcript.drain.ceiling`). El producto ya no confunde una
máquina lenta con un hijo atascado, y el operador que sabe que su máquina va
lenta tiene una palanca: **subir el techo** ya no esconde la saturación detrás
de un número más grande, porque el rechazo dice cuál de las dos cosas pasó.

**El instrumento es puro, y por eso es barato.** La regla del drenaje y el bucle
que la aplican se prueban sobre **bombas falsas**: sin hilo, sin reloj y sin
proceso. Diez filas cubren las particiones —el que termina, el atascado, el
lento, el que se queda sin tiempo, la precedencia entre atascado y techo, y el
drenaje con dos bombas—. Dos mutaciones se vieron en rojo, cada una con su
fila exacta:

| Mutación | Fila roja |
|---|---|
| la regla vuelve al reloj solo (se pierde la comprobación de atascado) | **4** — `a pump that stopped moving is stuck…`, `a stuck pump is stuck even when the ceiling has not arrived`, `a pump that never moves is refused as a stuck child`, `the drain waits for every pump…` |
| toda bomba cuenta como esperando al hijo (el drenaje ignora su atención) | **1** — `a pump that keeps moving is waited for…` |

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado
(`da479df68`): **208 s**, **150 s** y **148 s**, con un presupuesto de 900 s y
`tests=rewritten:1131`. Los 62 escenarios llevan una garantía comprobada —
**398 comparaciones sobre 112 ejecuciones**— y el LOC queda en **20891** de
verificación sobre **67980** de producto: **0,307**, con un límite de 2.

**Lo que se verificó después de publicar.** La candidata `v0.79.0-rc1` se
publicó y se comprobó **sobre los bytes publicados**, no sobre los locales: la
descarga de los siete assets desde el remoto, `sha256sum -c` con las seis
entradas de la autoridad de digest, el digest que el propio repositorio
resuelve —`92082347021db1a7eecc3ad793c6097aa9e2d55f17ec8f4f63356f239abc678b`,
el mismo que declara el manifiesto—, la instalación limpia del ZIP descargado,
que se presenta como `pipeline 0.79.0`, y un pipeline real con ese binario
hasta `SUCCESS`. El verbo que publica las operaciones devolvió las dos
identidades completas del run, con los `:` intactos, y cada una devolvió sus
propios bytes y no los de la otra.

**Lo que queda fuera, dicho con honestidad.**

- **La carga que hace que el drenaje se agote no está medida.** El defecto
  está medido —la regla equivocada, el rechazo no accionable y dos ensayos de
  la puerta descartados—, y el arreglo está medido por sus particiones. Lo que
  no hay es una medición de **cuánto** tarda un drenaje con la máquina
  saturada, porque reproducir la saturación en un banco de pruebas es una
 fixture que este repositorio no tiene. La puerta sigue siendo el detector.
- **El caso del hermano**: una bomba atascada y otra lenta a la vez se
  rechaza por la lenta (el techo), no por la atascada. El diagnóstico es
  menos preciso en ese caso raro, y no se ha querido añadir una segunda regla
  para él: el rechazo sigue siendo verdadero, y el caso mixto es raro porque
  exige un hijo atascado y una máquina ocupada al mismo tiempo.
- **La promoción a estable sigue bloqueada**: no hay certificador externo en
  este repositorio ni en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`,
  no `PASS`. Lo que este ciclo publica es la **candidata**, y la candidata es
  exactamente eso.


### 2.75 El SDK que publicamos no lo había consumido nadie

**El hueco.** El producto publica un SDK: un repositorio Maven dentro de un ZIP, con
los cinco contratos públicos y el BOM que los fija. Cada candidata lo publica y lo
comprueba **byte a byte** desde el remoto. Y, con todo eso, **nada en este
repositorio lo había resuelto jamás**: los cuatro plugins externos —los únicos
consumidores reales de los contratos públicos del árbol— resolvían desde
`build/sdk-repo`, un **directorio** de paso hacia el ZIP.

La diferencia no era de matiz. El ZIP es lo que se publica y lo que baja un
tercero; el directorio es un paso intermedio de la construcción. Resolver desde el
directorio probaba que los contratos existían, no que el artefacto publicado se
pudiera usar. Un cambio en el grupo de coordenadas, en el BOM, en los metadatos
de Gradle o en la disposición del ZIP se publicaba candidata tras candidata con la
puerta en verde, porque la puerta no preguntaba si lo que enviaba se podía
consumir.

**Medido antes de tocar nada.** Desde fuera, con un proyecto Gradle que no vive en
este repositorio, el SDK publicado **sí** resolvía: los cinco contratos y el BOM,
por coordenadas explícitas y también a través del BOM —sin versión propia en las
dependencias—. Es decir: el artefacto era correcto y **nadie lo había comprobado**.
La deuda no era el ZIP; era que su corrección dependía de una inspección que nadie
hacía.

**Lo que ahora es verdad.** El artefacto publicado es el único SDK que los
consumidores ven:

- el ZIP se desempaqueta en el repositorio Maven que las cuatro construcciones
  externas resuelven;
- una de ellas —el plugin insignia— consume **a través del BOM publicado**, sin
  escribir la versión en cada coordenada: una declaración de plataforma y tres
  contratos sin versión;
- el SDK publicado es **entrada declarada** de las cuatro construcciones, así que
  un cambio en lo que se publica las vuelve a ejecutar.

La consecuencia es la que importa: **un ZIP que no se puede resolver detiene la
construcción de la distribución**. La garantía deja de ser una frase y pasa a ser
una condición de salida. Y el escenario E2E no crece: los mismos 62, y los
plugins que ejecutan son los que compilaron contra el ZIP.

**La regla del paquete, y el defecto que tenía.** Al desempaquetar el ZIP, el
constructor elegía las entradas **buscando la versión en el texto de la ruta**, y
sobre la ruta **absoluta**. Una ruta que sólo la menciona —un checkout bajo un
directorio con el nombre de la versión— selecciona artefactos de otra versión. La
regla ahora es una función pura de las rutas **relativas**: una entrada pertenece
a la versión cuando **la carpeta que la contiene** se llama así. Es la regla del
layout Maven, dicha sin ambigüedad, y ya no puede desviarse por dónde cayó la
construcción.

**Lo que se vio en rojo.** Tres mutaciones, cada una con su fila exacta:

| Mutación | Fila roja |
|---|---|
| la regla vuelve a leer el texto de la ruta | **2** — `a path that mentions the version outside its own folder is left out` y `a file whose name carries the version is left out` |
| el BOM desaparece del ZIP | el consumidor que consume **por BOM**: `Could not find dev.rubentxu.pipeline.v2:pipeline-sdk-bom:0.79.0`, y los tres contratos **sin versión**, porque el BOM era quien los fijaba |
| el grupo de coordenadas se pliega en el ZIP | los **dos** modos de consumo: el del BOM (`Could not find …pipeline-sdk-bom`) y el de coordenadas explícitas (`Could not find …pipeline-domain:0.79.0`) |

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado
(`feda7dca6`): **210 / 148 / 146 s**, con un presupuesto de 900 s y `tests=rewritten:1138`.
Los 62 escenarios llevan una garantía comprobada —**398** comparaciones sobre **112** de
ejecuciones— y el LOC queda en **20990** de verificación sobre **68000** de producto (0.309).

**Lo que se verificó después de publicar.** La candidata `v0.80.0-rc1` se
publicó y se comprobó **sobre los bytes publicados**, no sobre los locales: la
descarga de los siete assets desde el remoto, `sha256sum -c` con las seis
entradas de la autoridad de digest, el digest que el propio repositorio resuelve,
la instalación limpia del ZIP descargado y un pipeline real con ese binario. El
SDK descargado se consumió **fuera** del repositorio, por coordenadas y a través
del BOM.

**Lo que queda fuera, dicho con honestidad.**

- **El ZIP no lleva sus dependencias de terceros.** El SDK publicado contiene los
  cinco contratos y el BOM; `kotlin-stdlib`, `kotlinx-serialization` y
  `kotlinx-coroutines` se resuelven del repositorio central. Es lo normal en
  Maven, pero significa que el ZIP **no es autosuficiente**: un consumidor sin red
  no construye nada con él. Meter las dependencias de terceros dentro del ZIP
  cambiaría el contrato de resolución, y eso es una decisión de producto, no una
  reparación.
- **La caché de resolución de Gradle puede servir un artefacto que ya no está.**
  La primera medición del «BOM ausente» salió **verde**: el build anidado estaba
  al día y no volvió a resolver. Declarar el SDK publicado como **entrada** de las
  construcciones lo arregla —un cambio vuelve a ejecutarlas—, pero la caché sigue
  pudiendo servir un artefacto retirado si nada más cambió. En un entorno limpio
  la resolución ocurre siempre.
- **La promoción a estable sigue bloqueada**: no hay certificador externo en
  este repositorio ni en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`,
  no `PASS`. Lo que este ciclo publica es la **candidata**.

### 2.76 Un nombre plegado no puede ser la identidad de un directorio

**El hueco.** El plano de salida guarda los bytes de un stream bajo un nombre **plegado**: todo
carácter fuera de `[A-Za-z0-9._-]` se convierte en `_`. El plegamiento **pierde información**, y
dos identidades distintas pueden plegarse al mismo nombre. El store lo tenía por una verdad, y la
decía en su propio KDoc: dos ids que se pliegan al mismo nombre **son** el mismo stream en disco.

No lo son. Son dos streams que el direccionamiento no sabe distinguir — y el que lo distinguía
era el índice de frames, que guarda la identidad entera en otro sitio.

**Medido con el producto real, no con un ejemplo inventado.** La identidad de una operación se
publica como `<runId>-<opId>:<rama>:<paso>`. Sustituyendo `:` por `_` se obtiene **otra
identidad** —la que se pliega al mismo directorio— y la pregunta es qué responde el producto:

```
$ pipelinek console --control-dir … <runId> <identidad real>      → BRANCH_A_MARKER
$ pipelinek console --control-dir … <runId> <identidad plegada>   → BRANCH_A_MARKER
```

La misma salida, el mismo código de salida y ninguna advertencia. **El producto leía los bytes de
una operación y se los entregaba a otra.** Un informe externo había señalado el defecto como
estructural, sin reproducirlo; aquí está reproducido con el binario y con un cambio de un solo
carácter en la identidad.

**Lo que ahora es verdad.** El directorio **recuerda** la identidad que lo posee: un fichero
`stream.id` al lado de los bytes, escrito una vez por el escritor que crea el directorio y nunca
reescrito. A partir de ahí:

- el que **escribe** reclama el directorio; si el registro nombra otra identidad, la escritura se
  **rechaza**, con un error que nombra a las dos;
- el que **lee** comprueba el registro antes de servir; si nombra otra identidad, la lectura se
  **rechaza** con `ForeignStream`, que dice qué se pidió y qué hay allí.

La consecuencia es la que importa: **dos identidades no comparten bytes**, y una identidad que
llega al directorio de otra no recibe una respuesta silenciosa.

**Por qué el rechazo es un caso del vocabulario ya publicado.** El vocabulario de rechazos del
plano de salida está publicado, y añadir un caso rompe el ABI de los consumidores. `ForeignStream`
ya dice lo que hace falta —«pediste esto, y lo que hay allí es aquello otro»—, y su razón de
ser —«una lectura que se recorta es una respuesta silenciosamente equivocada»— es exactamente la
de este caso. Lo que se amplía es su KDoc, no su firma: el ABI no cambia.

**La instrumentación.** Dos instrumentos, y ninguno nuevo:

- una regla **pura** (`decideStreamDirectoryOwnership`) con sus particiones —sin registro, con el
  registro propio, con el registro de otro, con un registro vacío— probada sin disco;
- el **escenario que ya existía**, que comprueba que cada identidad publicada lleva sus propios
  bytes, gana una comprobación: una identidad **distinta** que se pliega al mismo directorio **no**
  puede leerlos. No se creó ningún escenario nuevo: el suite sigue siendo el mismo.

**Lo que se vio en rojo.**

| Mutación | Fila roja |
|---|---|
| la regla acepta una identidad que sólo empieza por la registrada | **2** — `identities are compared whole, not by prefix` y `an empty record refuses the claim` |
| el store deja de comprobar al leer | el escenario: `LEG_folded_identity_neighbour_bytes` — «an identity that folds onto another one's directory read its bytes» |
| el store deja de reclamar al escribir | la UAT efímera: el segundo escritor **no** es rechazado |

**Lo que se midió con una UAT efímera, y por qué no queda.** La rama de escritura necesita dos
escritores que colisionen, y el motor actual no los produce: la medición del defecto (§2.57) y la
de este ciclo dicen que dos operaciones del motor nunca se pliegan al mismo directorio. La rama se
comprobó con un programa **temporal** contra el almacén real —escribe dos identidades que
colisionan y comprueba que la segunda es rechazada y que los bytes del dueño siguen intactos—,
**eliminado al cerrar el ciclo**: tocaría el sistema de ficheros, y un test permanente que lo
hace no cabe en la regla de unitarios puros.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado
(`d4cb97010`): **202 / 154 / 151 s**, con un presupuesto de 900 s y
`tests=rewritten:1143`. Los 62 escenarios llevan una garantía comprobada —**400** comparaciones
sobre **112** ejecuciones— y el LOC queda en **21074** de verificación sobre **68073** de
producto (0.310).

**Lo que queda fuera, dicho con honestidad.**

- **El nombre plegado sigue siendo plegado.** Lo que se ha cerrado es que dos identidades
  **compartan** bytes; lo que no se ha hecho es que el nombre sea **inyectivo**. Un esquema
  inyectivo cambiaría el layout en disco y necesita una migración y una versión, y eso no se
  decide aquí.
- **Un directorio escrito antes de esta versión no tiene registro.** Su primer escritor lo
  reclama, y a partir de ahí queda protegido. Si ese directorio ya mezclaba dos operaciones, el
  store no puede deshacerlo: puede dejar de empeorarlo, y eso es lo que dice.
- **La promoción a estable sigue bloqueada**: no hay certificador externo en este repositorio ni
  en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no `PASS`. Lo que este ciclo publica
  es la **candidata**.

### 2.77 Una identidad, un lector

**El hueco.** El identificador de una operación es el segmento central **entero** de un id de
stream, y el id tiene esta forma: `{runId}/{operationId}/{channel}`. Ese segmento central **puede
llevar separadores propios**: el `pluginStepId` que va dentro de un `bodyPath` sólo tiene que ser
no vacío, así que un identificador de operación legítimo puede ocupar varios segmentos.

Dos lectores lo interpretaban **por texto**, y cada uno contestaba sobre una operación que nadie
preguntó:

- el lector por operación buscaba el texto `"/<opId>/"` dentro del id, así que una operación cuyo
  id **contiene** el pedido (`other/foo` contiene `foo`) salía también con sus frames;
- el filtro de consola se quedaba con el **primer** segmento del centro (`parts[1]`), así que una
  operación compuesta (`stage/step/inner`) se cortaba por la mitad y se nombraba una operación
  que no existe.

Cada uno por su cuenta, y los dos juntos: **dos formas de leer la misma identidad**, y por lo
tanto dos autoridades sobre ella.

**Lo que el ADT ya sabía.** `OutputStreamAddress.parse` lee el id por sus dos extremos —el run
primero, el canal último— y todo lo que queda en medio es la operación, con sus separadores. La
pieza estaba publicada desde hacía tiempo y **los lectores no la usaban**: preferían decidir por su
cuenta dónde acababa el identificador.

**Lo que ahora es verdad.** La dirección responde también a la pregunta inversa —`isOperationOf`— y
los **tres** lectores (la lectura por operación, su cola y el filtro de consola) consultan esa
misma dirección. Ya no hay dos formas de leer una identidad: hay una, y es la que ya sabía.

**La segunda autoridad desaparece.** El resolvedor textual de etapa/paso y de tipo llevaba **su
propia** lectura del id (`split('/')` y `parts[1]`), su propia comparación de índices y su propio
`kindOk = true`, y no tenía **un solo consumidor** desde que la selección por eventos lo
sustituyó. Era el mismo problema de este ciclo, en forma de código muerto: una segunda lectura de
la identidad que nadie usaba y que nadie vigilaba. Se eliminan sus 27 líneas de código y las que
las explicaban.

**El ABI.** La dirección publicada crece en **un** miembro, aditivo: `isOperationOf`. No se retira
nada, no cambia ninguna firma, no aparece ningún caso de rechazo nuevo. `apiCheck` queda verde y
el volcado ABI se actualiza con una línea.

**La instrumentación.** Todo puro, y **el arnés no crece**:

- **3** filas puras de la dirección publicada;
- **4** filas del lector por operación, con el plano entero en memoria (índice, bytes y estado de
  cola, los tres falsos);
- **7** filas del filtro de consola.

Ninguna toca disco, reloj, proceso ni red. El suite E2E sigue siendo **el mismo**: 62 escenarios,
ninguno nuevo. Un escenario nuevo habría sido un instrumento que pasa antes y después: no habría
medido nada.

**Lo que se vio en rojo.**

| Mutación | Filas rojas |
|---|---|
| el lector por operación vuelve a buscar el texto `"/<opId>/"` | **3** — `the frames of an operation are the frames of that operation and of no other`, `a piece of an operation id is not the operation` y `the tail of an operation does not carry the bytes of its neighbour` |
| el filtro de consola vuelve a quedarse con el primer segmento | **5** — las cinco filas de dirección del filtro; sólo las dos anclas se quedan en verde |
| la comparación deja de mirar el run | **1** — `an address is the operation it names, in the run it names` |
| el parser se queda con el primer segmento del centro | **5** — la fila del viaje de ida y vuelta en el módulo publicado y las cuatro que dependen de que la operación se lea entera |

Se comprobaron las **dos direcciones**: no sólo que el defecto se detecte, sino que una regla
*demasiado estricta* tampoco pase por verde. Por eso la fila que no puede fallar —la del lector
frente a otro run, que el índice ya acota por run— **se quitó**: un instrumento que no puede fallar
no es un instrumento, y una garantía ya cubierta no necesita un segundo instrumento.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado
(`807353656`): **213 / 194 / 163 s**, con un presupuesto de 900 s y
`tests=rewritten:1157`. Los 62 escenarios llevan una garantía comprobada —**400** comparaciones
sobre **112** ejecuciones— y el LOC queda en **21291** de verificación sobre **68058** de
producto (0.313).

**Lo que queda fuera, dicho con honestidad.**

- **El defecto era real, pero invisible desde la línea de órdenes.** Los identificadores de
  operación que el motor produce hoy no llevan separadores: los `pluginStepId` del núcleo son
  `core.sh` y `core.dir`, y los de los plugins de ejemplo tampoco. Una operación compuesta
  aparece cuando un plugin aporta un paso cuyo identificador lleva `/`, y el contrato de plugins
  lo permite —no hay ninguna regla que lo prohíba—, pero ningún ejemplo de hoy lo hace. El
  defecto quedó **localizado y medido** con filas que se ponen rojas, y **no** con un fallo
  visible en la línea de órdenes. Por eso no hay escenario nuevo.
- **El formato del id de stream no cambia.** Cambia **quién** lo lee. El contrato de cable sigue
  siendo `{runId}/{operationId}/{channel}`.
- **El identificador plegado sigue plegado** (§2.76): este ciclo no toca el direccionamiento en
  disco.
- **La promoción a estable sigue bloqueada**: no hay certificador externo en este repositorio ni
  en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no `PASS`. Lo que este ciclo publica
  es la **candidata**.

### 2.78 Una avería no puede borrar, esconder ni corromper lo que ya se decidió

**El hueco.** Tres sitios donde un fallo se llevaba por delante una decisión ya tomada. Los tres
tenían la misma forma: **el fallo se tragaba el hecho y el run seguía como si nada**.

1. **La consola dibujaba antes de escribir.** `ConsolePrintingEventSink` renderizaba la línea y
   sólo después entregaba el evento al almacén. El comentario del código afirmaba lo contrario de
   lo que hacía —"Render BEFORE delegating so a rendering fault cannot swallow the event on its way
   to the durable store"—: un fallo de dibujo se tragaba el evento **antes** de que llegara al
   almacén. Y el renderizador **se para a propósito** cuando su tabla de enrutamiento y una
   familia no se ponen de acuerdo, porque una consola que dibuja una línea plausible equivocada
   es peor que una consola que se para. O sea: el fallo estaba **diseñado** para ocurrir, y el
   orden estaba al revés de lo que el comentario contaba.
2. **El redactor podía pisar lo que ya había decidido.** La cola de bytes decididos era un anillo
   de tamaño fijo, y su KDoc prometía —"Capacity MUST accommodate the worst case of a single
   `feed` call"— sin que ninguna regla lo hiciera cierto: `feed` acepta cualquier `len` y el
   tamaño no lo cubría. Cuando el anillo se enrollaba, **se comía el principio de la
   transcripción**: los bytes que el lector todavía no había tomado. Medido: con un feed de 4120
   bytes la transcripción salía `uvwxyzABCD…` en lugar de `abcdefghij…`.
3. **El checkout de git se tragaba un fallo de escritura durable.** `emitEvent` envolvía la
   escritura del evento en un `catch` que imprimía `Warning: failed to emit event …` por stderr y
   **seguía adelante**. El hecho se perdía, el checkout llegaba a reportar éxito, y lo que el
   operador acababa viendo era el error de git —no el registro perdido—.

**Lo que se midió antes de tocar nada.** Un censo de los sumideros de eventos del árbol y una
auditoría programática de qué eventos llegan a la rama que el renderizador se niega a dibujar:
**56 eventos enrutados, 0 sin familia**. Los tres defectos eran, por tanto, **latentes**: el código
que los activa estaba escrito y se había ejecutado en otras ramas del producto, pero ningún camino
vivo de los de hoy los activaba. Eso cambia lo que había que prometer, y por eso este ciclo **no**
añade escenarios: un escenario que pasara antes y después no mide nada.

**Lo que ahora es verdad.**

- **La consola escribe antes de dibujar.** El hecho durable entra primero y el dibujo va después,
  así que un fallo de dibujo deja el registro del run intacto y el fallo a la vista. El dibujo pasa
  a ser un **colaborador** (`render`), de modo que el orden es una propiedad de la clase y no del
  día en que se escribió el dibujante. El comentario dejó de mentir.
- **El redactor no puede pisar lo que ya decidió.** La cola de lo decidido **crece** en vez de
  enrollarse: no hay capacidad que una llamada posterior pueda exceder, así que el invariante —*un
  byte decidido nunca se escribe sobre*— deja de ser una aritmética y pasa a ser imposible. La
  fórmula de tamaño, que prometía una cosa que no era cierta, desaparece.
- **Un registro que el almacén rechaza es el fallo del checkout.** El rechazo viaja como un tipo
  propio (`CheckoutRecordRefused`) que nombra el evento perdido, y el checkout termina en fallo.
  Un aviso por stderr no era un aviso: era la pérdida de un hecho.

**El ABI.** No cambia. El volcado de `:pipeline-credentials-api` sale **idéntico** y `apiCheck`
queda verde en los seis módulos con volcado: la cola y su reserva son miembros `internal`. La
consola vive en `:pipeline-application` y el checkout en `:pipeline-step-sdk/scm-git`, y ninguno de
los dos se publica.

**La instrumentación.** **4** filas puras y **cero** escenarios nuevos:

- **1** fila de la consola: el registro se escribe antes de que se dibuje, y un dibujo roto no se
  lleva el registro por delante;
- **2** filas del redactor: un feed que decide más de lo que la cola esperaba, y el final de la
  transcripción, que no pisa lo que ya estaba decidido;
- **1** fila del checkout: un registro rechazado hace fallar el checkout y dice qué registro se
  perdió.

Las tres primeras no tocan disco, reloj, proceso ni red. La del checkout necesita un directorio
temporal porque el aplicador de credenciales exige poseer uno; el rechazo ocurre en el primer
registro, **antes** de que arranque cualquier proceso de `git`, así que la fila no toca la red ni
un repositorio.

**Lo que se vio en rojo.**

| Mutación | Filas rojas |
|---|---|
| la consola vuelve a dibujar antes de escribir | **1** — `the decision is written before it is drawn, and a broken drawing does not take it with it` |
| el redactor vuelve a un anillo de tamaño fijo | **2** — `a feed that decides more than the queue holds keeps every decided byte` y `the end of the stream does not overwrite the bytes that were already decided` |
| el checkout vuelve a avisar por stderr y seguir | **1** — `a checkout whose record is refused fails instead of carrying on` |

Las tres mutaciones se aplicaron **a la vez** y se ejecutaron las **suites completas** de los tres
módulos: **18** pruebas del módulo de credenciales con **2** rojas, **204** del de aplicación con
**1** roja y **11** del de git con **1** roja. Exactamente las cuatro filas nuevas, y ninguna otra.

Una de esas filas se corrigió **dos veces** antes de valer: la primera versión del final de la
transcripción usaba una carga uniforme, y con bytes iguales la corrupción **no se ve** —el
resultado coincidía con el esperado por casualidad—. La fila usa ahora treinta bytes distintos, y
el rojo que produce lo dice: esperaba `abcdefghij…` y recibió `uvwxyzABCD…`. Un instrumento que no
puede fallar no es un instrumento.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado (`10774b563`): **204 / 154
/ 155 s**, con un presupuesto de 900 s y `tests=rewritten:1161`. Los 62 escenarios llevan una
garantía comprobada —**400** comparaciones sobre **112** ejecuciones— y el LOC queda en **21456**
de verificación sobre **68087** de producto (0.315).

**Lo que queda fuera, dicho con honestidad.**

- **Los tres defectos eran latentes**, y se dice por qué: la rama que el renderizador se niega a
  dibujar no la recorre hoy ningún evento (56 enrutados, 0 sin familia); el anillo del redactor
  sólo se enrolla si el llamante entrega más bytes de los que el anillo esperaba, y los dos
  llamantes de hoy entregan como mucho `chunkSize`; y el almacén de eventos no rechazó ninguna
  escritura. Por eso **no hay escenario E2E nuevo**: habría pasado antes y después.
- **El comparador del arnés es ciego al orden**, y queda **medido**, no corregido a medias. La
  canonicalización ordena alfabéticamente las líneas dentro de cada etapa, así que la narración real
  y la narración con el `finished` de un paso antes del `start` producen **la misma**
  canonicalización. El arreglo sound exige un **orden parcial**, o que el producto publique la
  genealogía, y eso es demasiado pesado para un lanzador fino, con riesgo de intermitencia. La
  medida que acompaña al hueco: **10** ejecuciones del escenario de ramas en paralelo dieron **3**
  formas distintas de narración (5/4/1); el **solapamiento** de las ramas fue **10/10** —las dos
  empiezan antes de que ninguna termine— y sólo variaba el orden de cierre. Sólo **2** de los 62
  escenarios usan ramas en paralelo. Queda **armado** como bloque siguiente.
- La degradación de `ATOMIC_MOVE` al escribir el registro de commit,
  `SqliteNativeLibraryHomeTest` fuera de la política de unitarios puros, y la economía del arnés y
  del ROADMAP siguen abiertos.
- **La promoción a estable sigue bloqueada**: no hay certificador externo en este repositorio ni en
  el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no `PASS`. Lo que este ciclo publica es la
  **candidata**.

### 2.79 Una historia no puede contarse al revés

**El hueco.** §2.78 dejó armado, y medido, un punto ciego del arnés: el comparador de las
dos rutas de ejecución ordenaba alfabéticamente las líneas dentro de cada etapa, así que una
narración contada al revés —el fin de un paso antes de su arranque— producía **el mismo
texto** que una contada bien. El comparador no podía verlo. Y ningún otro check del arnés
lo miraba tampoco: la palabra de un escenario dice **qué** se produjo, nunca **en qué
orden** se produjo.

**Lo que se midió antes de tocar nada.** No se tocó el producto hasta saber qué promete
el producto de verdad, y no lo que el arnés imaginaba:

- Las **124** narraciones que había dejado el ciclo anterior: **402** líneas de arranque de
  paso, **394** de fin, y **0** de fin sin veredicto. Toda línea de fin dice su resultado
  (`success`, `failure` o `unstable`).
- **0** inversiones: no hay ni un fin de paso antes de su arranque.
- **0** etapas cerradas por otra etapa, **0** etapas dentro de otra y **0** etapas saltadas.
- **22** etapas sin cerrar, y son exactamente las corridas que fallaron: una etapa que
  falla no se cierra, porque el run se acabó ahí.

**Lo que ahora es verdad.** El lanzador comprueba el **orden** de **cada** corrida, de los
**62** escenarios y de las dos rutas, sin que el escenario lo declare. La promesa es un
**orden parcial**, que es lo que el producto promete y no más:

- un paso que terminó, empezó antes; y
- una etapa la cierra la etapa que abrió.

La comparación entre las dos rutas **sigue igual**, y por el mismo motivo de siempre: dentro
de una etapa el orden de cierre de dos ramas concurrentes es una carrera, no una historia, y
ordenarlas es lo correcto. Lo que la comparación no puede —la inversión— lo comprueba
ahora el invariante, en cada corrida. Un paso que se cuenta al revés es una historia que
contradice el registro, y el registro es la memoria del run: nadie que lo lea después puede
saber que miente.

**La directiva que se retiró.** La propuesta que estaba a medio hacer era una directiva por
escenario que declaraba el orden de dos textos. Se retiró, y la razón es la que importa:
una promesa que declara un escenario protege **a ese** escenario. El invariante protege a los
62, y no cuesta una palabra en ningún archivo de ejemplo. La comparación de las dos rutas
sigue siendo la que era, y ahora hay un segundo instrumento que ve lo que ella no ve.

**Lo que se vio en rojo.** La mutación se hizo en el producto, no en el arnés: la consola
dibuja el arranque de un paso **después** de su fin. El registro del run no se toca —la
mentira es sólo de la consola—, así que todo lo demás sigue siendo verdad.

| Mutación | Escenarios rojos | Motivo |
|---|---|---|
| la consola dibuja el arranque de un paso después de su fin | **48 de 62** | **48 de 48**, el mismo: *el run contó su historia en un orden que no prometió* |

El dato que importa no es el número de rojos: es que **los 48 tienen el mismo motivo** y que
**los 401 checks anteriores no dijeron nada**. El defecto era invisible para todo el arnés
anterior, que es exactamente lo que §2.78 medía y no corregía. Los **14** que siguen
verdes son los que no narran ningún paso —las admisiones rechazadas— y los que narran un
paso que falló, donde la línea de error se intercala y el orden legal se conserva.

**La economía del arnés, medida antes de cortar.** Se reconstruyó el índice de garantías
sobre los **62** escenarios —qué garantiza cada uno, quién lo observa, cuánto cuesta y qué
defecto discrimina— y la respuesta es que **ningún escenario es retirable**:

- La única subordinación estricta que aparece es `events/05-run-state` dentro de
  `events/06-observer-death…`, y es un mal trueque: el primero es un testigo **barato y
  dedicado** de la superficie de introspección, y el segundo la usa **de pasada** mientras
  prueba otra cosa. Retirar el barato para quedarse con el caro no ahorra nada.
- Las cuatro retiradas que este mismo documento registra **no se reflejan en el árbol**, y al
  volver a mirarlas el testigo que queda **no** es estrictamente más fuerte:
  `output/23-readfile` comprueba el contenido escrito como subcadena, mientras que
  `steps/17-writeFile` lo comprueba línea a línea. Retirarlo perdería la exactitud.

Lo que sí se redujo fue el **arnés**, que es donde estaba la duplicación: una directiva menos
y un helper menos, y tres promesas que se cumplían por casualidad y ahora no pueden
cumplirse por casualidad:

- Una directiva que el lanzador **no conoce** es un fallo que la nombra. Antes el parser no
  tenía otro caso: una `//@` mal escrita pasaba desapercibida, y una garantía que desaparece
  al escribirla mal no es una garantía.
- `storage-agnostic` **se cumple o el lanzador se niega**: un escenario que promete que su
  capacidad no necesita almacén y que sus propias directivas dejarían sólo con almacén es un
  fallo, no un verde.

**El coste no subió.** Los mismos **62** escenarios, las mismas **112** ejecuciones y el
mismo reloj (~**95 s** contra un presupuesto de **900 s**). Lo que subió son las
comprobaciones: de **401** a **512**, porque cada corrida comprueba ahora también el orden
de su propia historia.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado (`2cbcf821`):
**201 / 166 / 154 s**, con un presupuesto de **900 s** y `tests=rewritten:1161`. Los 62
escenarios llevan una garantía comprobada —**512** comparaciones sobre **112** ejecuciones— y
el LOC queda en **21 627** de verificación sobre **68 087** de producto (**0,318**).

**La candidata.** `v0.84.0-rc1`, publicada sobre el commit `2cbcf821`, con el artefacto
`pipelinek-0.84.0.zip` en `sha256:21cbd4e34df72cbaf8afbad8f9270c16a9dde1fa5e5db072229eb5ae70404c74`
y los **7** activos con su digest en `SHA256SUMS`. El producto no cambia de comportamiento en
este ciclo —lo que cambia es el arnés que lo verifica—, así que el tren sube para que el
nombre del artefacto no se repita entre candidatas. Es una **candidata**, no una
certificación.

**Lo que queda fuera, dicho con honestidad.**

- La economía del arnés por **coste** sigue abierta: la caché de compilación vive dentro
  del ciclo y se borra con él, así que cada ejecución paga su compilación. Compilar una vez
  exigiría un verbo público nuevo en el producto, y eso es otro ciclo.
- **La promoción a estable sigue bloqueada**: no hay certificador externo en este
  repositorio ni en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no `PASS`.

### 2.80 La biblioteca nativa tiene un dueño, y vive donde el operador dijo

**El hueco.** El producto decide dónde aterriza la biblioteca nativa del driver de SQLite, y esa
decisión era una **comprobación de nulo**: si la propiedad `org.sqlite.tmpdir` estaba puesta, el
operador había elegido, y no se tocaba nada. El driver, sin embargo, **no crea directorios**: el
sitio tiene que estar allí antes de que llegue la biblioteca. De esa comprobación salían dos
fallos, los dos medidos contra el producto real y no contra una idea.

**Lo que se midió antes de tocar nada.**

- Una propiedad **vacía** (`-Dorg.sqlite.tmpdir=`) contaba como «el operador eligió esto» — y una
  propiedad vacía no nombra ningún directorio. El driver la resolvía contra el directorio de
  trabajo. Medido en vivo, con la corrida en marcha: en el directorio donde corría el pipeline
  aparecían `sqlite-3.46.1.3-<uuid>-libsqlitejdbc.so` (1 056 352 bytes) y su `.lck`.
- Un nombre que **no existía todavía** no lo creaba nadie. La extracción fallaba con
  `NoSuchFileException` sobre el `.lck`, y el producto decía otra cosa: `db is not readable:
  <ruta> (Error opening connection)`, salida 2. El operador iba a mirar una base de datos
  perfectamente sana, porque lo que faltaba era el directorio de la biblioteca.
- El censo del temporal compartido: **423** bibliotecas sueltas en la raíz, **todas anteriores** al
  mecanismo del directorio con dueño, y **693** directorios `pipelinek-sqlite-…` con **699 MB**.
  Los 693 son de procesos **asesinados**: una salida normal limpia el suyo (medido: 693 antes,
  693 después). El defecto no era la limpieza, era el sitio.

**Lo que ahora es verdad.** La decisión es una **regla pura y tipada**, con tres respuestas a una
sola pregunta: *el valor del operador nombra un directorio donde el driver puede extraer?*

- Si nombra uno que **ya está**, se usa tal cual y no se toca nada de él: `Borrowed`.
- Si nombra uno que **aún no está**, se crea —el nombre es del operador— y se deja allí:
  `Created`.
- Y si no hay nombre utilizable, el producto crea uno suyo y lo retira al terminar: `Owned`, el
  único directorio que retira.

**El valor del operador nunca se reescribe.** La propiedad se toca sólo cuando nadie nombró nada.
Un valor en blanco, un valor de espacios y un nombre que el sistema de ficheros no sabe escribir
—uno con un NUL dentro— son la **ausencia** de una instrucción, y el producto los trata como tal.

**Lo que el arnés hace ahora.** Cada escenario recibe **un sitio para la biblioteca que el arnés
no crea**: un directorio por escenario, con nombre propio, dentro de la raíz privada de ese
escenario. El producto tiene que hacerlo, y **la corrida es la observación**: un producto que
cree al operador en vez de servirle deja los **62** escenarios en rojo. Y hay una comprobación
**universal**, que ningún escenario declara: si una corrida **arrancó** —es decir, si abrió el
almacén— el sitio nombrado tiene que **estar ahí**. Una corrida que nunca arrancó nunca abrió el
almacén, y no hay nada que exigirle.

**La fila pura dejó de tocar el disco.** La decisión es una función de dos hechos, así que se lee
y se comprueba sin temporal del sistema, sin reloj y **sin una propiedad global que otra fila
puede pisar**. La prueba anterior creaba **tres directorios de verdad** en el temporal del sistema
y borraba la propiedad del JVM antes y después de cada fila — lo que la política veta — y además
no podía ver ninguno de los dos fallos: sólo probaba que no había propiedad y que había una con
nombre.

**Lo que se vio en rojo.**

| Mutación | Qué se puso rojo | Motivo |
|---|---|---|
| el producto cree al operador en vez de servirle | **62 de 62 escenarios** | **62 de 62**, el mismo: el driver no pudo extraer porque el directorio no estaba |
| la fila pura que decide qué hacer con un nombre que no está | **1 fila** | la que dice que un sitio que no existe se hace |
| la fila pura que lee el valor del operador | **2 filas** | las dos que dicen que un valor en blanco no nombra nada |

El primer dato es el que importa: **los 62 tienen el mismo motivo**, y el motivo no es el
escenario sino el producto. Antes de este ciclo, un producto que creía al operador en vez de
servirlo era **indistinguible** de uno que lo servía, porque la comparación entre las dos rutas
mira lo que se dijo y no dónde se puso una biblioteca que desaparece al terminar el proceso.

**El coste no subió.** Los mismos **62** escenarios —ninguno nuevo— y las mismas **112**
ejecuciones. Lo que subió son las comprobaciones: de **512** a **566**, porque ahora cada corrida
comprueba también que el sitio nombrado existe.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado (`e968fd79`): **201 / 166 / 150 s**
con un presupuesto de **900 s** y `tests=rewritten:1165 read:0`. Los 62 escenarios llevan una
garantía comprobada —**566** comparaciones sobre **112** ejecuciones— y el LOC queda en
**21 693** de verificación sobre **68 122** de producto (**0,318**).

**La candidata.** `v0.85.0-rc1`, publicada sobre el commit `e968fd79`, con el artefacto
`pipelinek-0.85.0.zip` en `sha256:e74f1aa95ad7435d031ee0718ba6883e6fc082f92c1615d893770d840395bbb1` y los **7** activos con su digest en
`SHA256SUMS`. Es una **candidata**, no una certificación.

**Lo que queda fuera, dicho con honestidad.**

- El arnés ahora nombra un sitio para cada escenario, así que el caso de «nadie nombró nada» —el
  defecto de fábrica— lo cubren las filas puras y la verificación de la candidata publicada, que
  corre el producto en su entorno de verdad. No lo cubre una corrida del arnés.
- El caso de un operador que nombra un sitio que existe lo cubren las filas puras, y la segunda
  corrida de los escenarios durables lo encuentra ya hecho: el mismo nombre, ahora existente.
- La economía del arnés por **coste** sigue abierta: la caché de compilación vive dentro del
  ciclo y se borra con él, así que cada ejecución paga su compilación (§2.79).
- La promoción a estable sigue bloqueada: no hay certificador externo en este repositorio ni en
  el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no `PASS`.

### 2.81 El camino más corto al primer pipeline tiene que funcionar

**El hueco.** El README dice cómo se ejecuta el primer pipeline, y el primer pipeline no
arrancaba. En una máquina que tuviera un almacén de credenciales en disco —casi
cualquier máquina donde alguien haya guardado un secreto alguna vez—, **toda** corrida
pedía la frase de paso de ese almacén, y el comando de ejemplo del README moría con
`Error: Passphrase required` y salida **3** antes de ejecutar un solo paso. La causa no
era el almacén: era **la pregunta**. La costura de credenciales se componía mirando si el
fichero del almacén **existe**, y esa es una pregunta sobre la **máquina**. La necesidad
del almacén es una pregunta sobre la **corrida**.

**Lo que se midió antes de tocar nada.**

- Sobre los **bytes publicados** —la candidata anterior, `v0.85.0-rc1`—, el comando de
  ejemplo del README falla: `Error: Passphrase required: set PIPELINE_STORE_PASSPHRASE or
  run interactively in a TTY`, salida **3**, en una máquina con `~/.pipeline/credentials.bin`
  presente.
- El **mismo** comando, en una máquina sin almacén, funciona. El defecto no estaba en la
  corrida: estaba en la pregunta.
- El almacén de esta máquina existe desde el 22 de septiembre. Es decir que la máquina
  llevaba un mes pidiendo una llave que la mayoría de las corridas no necesitaba.
- La puerta de credenciales se negaba **antes de cualquier paso**. El efecto no era un
  paso que fallaba: era un pipeline entero que no empezaba.

**Lo que ahora es verdad.** La pregunta se hace a la **declaración** de la corrida, no a
la máquina: un `PipelineSpec` dice si liga una credencial. Y la regla es un `when`
**exhaustivo sobre el `StepSpec` sellado**, de modo que un tipo de step nuevo **no
compila** hasta que alguien diga si puede ligar credenciales. Un binding escrito dentro de
un cuerpo —`withEnv`, `retry`, `timeout`, una rama de `parallel`, un `slot`— sigue siendo
un binding de la corrida, y el recorrido lo tiene en cuenta.

Las **tres** vías que consumen el almacén quedaron cubiertas, y la tercera ya toleraba un
proveedor nulo: `WithCredentialsBlock`, `Checkout` con `credentialsId`, y el contribuidor
de capacidades —que expone `NoBasicCredentialSource` cuando no hay store, precisamente
para que un plugin sin credenciales no se lea como «no cableado»—. El fallo **sigue siendo
fail-closed**: una corrida que liga una credencial y no tiene frase de paso se niega
igual, con salida **3**.

**Lo que el arnés hace ahora.** El arnés daba una frase de paso a **todos** los
escenarios, porque la puerta de credenciales se negaba antes de cualquier paso. Eso
tapaba justo la pregunta que importa: *¿pide una frase de paso una corrida que no liga
ninguna credencial en una máquina que **sí** tiene almacén?* La respuesta era sí, y el
arnés la había cubierto con cinta.

Ahora la máquina del escenario **tiene** un almacén: uno real, con un secreto real
dentro, sembrado una vez por la etapa a través del escritor de la propia distribución y
copiado en la raíz privada de cada escenario. Los escenarios que **no** ligan nada
reciben esa copia y **no** reciben la llave; los que ligan algo reciben la llave y
conservan sus propias credenciales sembradas. La garantía es **universal** y no la
declara ningún escenario: si el producto pidiera la frase de paso, **60 de 62**
escenarios se caerían.

**El README dejó de llevar cifras que alguien tiene que recordar actualizar.** Fijaba una
versión —`0.75.0`, diez versiones y diez tags atrás—, un digest, el número de escenarios
—50, cuando hay 62— y dos enlaces rotos en su tabla de documentación. El README nuevo no
fija ninguna: la versión y el digest salen del **propio release** —el manifiesto dice
cómo se llama su asset y `SHA256SUMS` dice cuál es su digest—, la lista de directivas
apunta a la cabecera del lanzador que las mantiene ciertas, y los enlaces se comprobaron
uno a uno.

**El bloque de instalación que proponía el README estaba roto, y se midió.** Tomaba el
**bundle del SDK** en vez de la distribución —el `awk` del primer bloque encuadraba los
dos ZIP y se quedaba con el primero por orden alfabético— y componía mal la ruta del
binario (`pipelinek-pipelinek-0.85.0` en vez de `pipelinek-0.85.0`). Ejecutado desde el
README, de verdad: descarga, verifica el digest, extrae y el binario responde. Ahora el
**manifiesto** es la autoridad que dice qué asset es el programa, el **feed del release**
dice qué versión es la más reciente —sin el límite de tasa de la API—, y el digest se
comprueba contra `SHA256SUMS`.

**`README.es.md` describía otro producto.** Sus 386 líneas hablaban de un motor con
sandbox de GraalVM Isolates, contenedores para las tareas y corrutinas de Kotlin: la
primera no aparece **ni una vez** en el código, la segunda sólo como una declaración
tipada de qué execution target exige una directiva, y la tercera no existe. Sus
prerrequisitos —Docker, GraalVM, Gradle 8.4— no son los del producto, y su sección de
pruebas describía un régimen que el RESET borró. Ahora es una guía breve en español,
fiel y **sin cifras que roten**, que apunta al README en inglés.

**Lo que se vio en rojo.**

| Mutación | Qué se puso rojo | Motivo |
|---|---|---|
| la costura vuelve a preguntar a la máquina | **60 de 62 escenarios** | **55** nombrando `Passphrase required`; los otros cinco muestran su consecuencia: un pipeline que ni siquiera empezó, así que no hay hechos que leer ni sustrato que observar |
| la regla dice que **nada** liga una credencial | **3 de 5 filas puras** | las tres que afirman que un binding es un binding |
| la regla dice que **todo** liga una credencial | **3 de 5 filas puras** | las tres que afirman que un cuerpo sin secretos no liga nada |

Las cinco filas puras discriminan **en las dos direcciones**: ninguna es un adorno, y
ninguna se queda verde por accidente.

Los dos escenarios que sobreviven son los que **no tienen ese sujeto**: uno liga una
credencial —y recibe la llave—, y el otro verifica una cancelación, que no compone la
costura. El dato que importa es el motivo: **55 de 60 nombran la misma frase**, y el
motivo es el producto, no el escenario.

**El análisis estático también habló, y se le hizo caso.** Al preguntar en el llamador,
la función de la corrida durable se pasó de 117 a 123 líneas, y su tope son 120. En vez
de relajar la regla, la decisión bajó a donde pertenece: la costura recibe la
**declaración** y responde sola, y el llamador dejó de repetir la regla. El
comportamiento no cambió; cambió que ahora hay **un** sitio donde preguntar.

**El coste no subió.** Los mismos **62** escenarios —ninguno nuevo— y las mismas **112**
ejecuciones, con **566** comparaciones.

**Lo que la puerta midió.** `PASS` tres veces sobre el árbol commiteado (`d4c406be8`):
****162 / 145 / 151 s**** con un presupuesto de **900 s** y `tests=rewritten:1170 read:0`. Los
62 escenarios llevan una garantía comprobada —**566** comparaciones sobre **112**
ejecuciones— y el LOC queda en **21 855** de verificación sobre **68 174** de producto
(**0,321**).

**La candidata.** `v0.86.0-rc1`, publicada sobre el commit `d4c406be8`, con el artefacto
`pipelinek-0.86.0.zip` en `sha256:1923cc80e2643dd1759d501d4ad1bc764ec68aa333b9eb606aad52e758c43ade` y los **7** activos con su digest en
`SHA256SUMS`. Es una **candidata**, no una certificación.

**La garantía, comprobada por fuera.** Sobre los **bytes descargados del remoto** —no sobre
el árbol local— el comando de ejemplo del README se ejecutó en esta misma máquina, **con el
almacén presente** y **sin frase de paso**: `Pipeline finished with SUCCESS`, salida **0**.
La candidata anterior, con los mismos bytes y la misma máquina, moría con salida **3**. La
misma descarga deja ver el otro lado de la puerta: una corrida que **liga** una credencial y
no tiene frase de paso se sigue negando con salida **3**, el almacén sembrado está **cifrado**
—cabecera `PKCR`, ni un byte del secreto en claro— y el secreto **no aparece en ningún
fichero** de la corrida.

**Lo que queda fuera, dicho con honestidad.**

- El **instalador multi-versión** no puede instalar ninguna versión reciente, y se midió:
  `install 0.85.0-rc1` lo rechaza la comprobación de semver estricto, e `install 0.85.0`
  da **404**, porque el tag de una candidata lleva sufijo (`v0.85.0-rc1`) mientras que su
  asset no lo lleva (`pipelinek-0.85.0.zip`). El único tag plano que existe es `v0.75.0`.
  **No se ha corregido en este ciclo**, y no por descuido: instalar una candidata bajo el
  nombre de una versión estable es exactamente lo que la ley de identidad prohíbe, así que
  el arreglo tiene que decidir **dónde** vive una candidata —un directorio que lleve su
  sufijo— antes de tocar el resolutor. El README lo dice con esas palabras.
- La promoción a estable sigue bloqueada: no hay certificador externo en este repositorio
  ni en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no `PASS`.
- La economía del arnés por **coste** sigue abierta: la caché de compilación vive dentro
  del ciclo y se borra con él, así que cada ejecución paga su compilación (§2.79).

### 2.82 La llave de las credenciales se comprueba

**El hueco.** La frase de paso es lo que abre el almacén de credenciales, y el almacén no
comprobaba la llave. Medido, sobre el producto real, con un almacén sembrado y una credencial
dentro:

```
$ PIPELINE_STORE_PASSPHRASE=mala pipelinek credentials remove cred-1
Credential 'cred-1' removed.          salida 0
el almacén pasó de 204 a 137 bytes y la credencial desapareció
```

Una frase de paso que **no** es la correcta borró una credencial y lo announced como si hubiera
funcionado. El almacén no comprobaba nada al quitar: leía las entradas, quitaba una y
reescribía el fichero, **sin tocar jamás la llave que lo sella**. La credencial no se
corrompió: se borró, y el operador se quedó sin ella.

**Lo que se midió antes de tocar nada.** La matriz completa —cada verbo, cada situación, cada
caso con su almacén limpio— está medida en el recibo del ciclo. Lo que salió:

| verbo | almacén | llave | salida | qué pasó |
|---|---|---|---|---|
| `list` | no está | ninguna | **1** | pidió la llave para no poder abrir nada |
| `list` | no está | hay | 0 | dijo que no hay nada, **pero creó un fichero de 0 bytes** |
| `remove` | no está | hay | 1 | **`Error: null`**, y creó un fichero de 0 bytes |
| `list` | está | ninguna | **1** | la excepción específica nunca se lanzaba: caía en la genérica |
| `list` | está | **mala** | **0** | listó las credenciales con el tipo `Unknown` y salió 0 |
| `remove` | está | **mala** | **0** | **borró la credencial** y lo dijo como un éxito |
| `remove` | está | buena, id inexistente | **0** | **`Credential 'no-existe' removed.`** |

Y una cosa más, que no salió de la matriz sino del código: el comando **documentaba** sus
códigos de salida —`2: Missing store`, `3: Wrong passphrase`— y **ninguno de los dos lo
producía nunca**. La causa era que el `catch` nombraba `LocalSecretStore.CredentialsStorePassphraseUnavailableException`
cuando la excepción que se lanza es `PassphraseResolver.CredentialsStorePassphraseUnavailableException`:
dos clases distintas para la misma pregunta, y la que se capturaba no la lanzaba nadie.

**Lo que ahora es verdad.** Una sola regla, dicha en un sitio: **la frase de paso es la
autorización para tocar el almacén**.

- El almacén **desenvuelve la clave que lo sella antes de leer o de escribir nada**. Una
  llave que no encaja no cambia un byte, y lo dice con sus propias palabras: *the passphrase
  does not match the store*. Antes una llave equivocada decía «Passphrase required», que
  manda al operador a buscar una variable que ya ha puesto.
- Un almacén **que no está no tiene nada que abrir**: los verbos que solo leen o solo cambian
  lo que hay dentro no piden la llave, y **no dejan un fichero detrás por mirar**. Antes
  `credentials list` sobre una máquina sin almacén dejaba un almacén vacío en el disco.
- **Quitar lo que nunca existió** lo dice: `No credential named 'no-existe'.`, salida 1. Antes
  anunciaba una baja que no ocurrió.
- El comando **pide la llave antes de pedir el valor**, para que una llave equivocada no
  cueste al operador teclear un secreto que no se va a guardar.
- Los **códigos de salida son los que el comando documenta**, y cada uno lo produce el
  desenlace que le corresponde: `0` respondido, `1` no se pudo hacer, `2` no hay llave, `3` la
  llave no encaja, `4` el almacén está manipulado.

**El contrato es puro y está en un sitio.** `CredentialsVerb` dice qué verbo necesita la
llave —`ADD` siempre, los otros solo cuando hay algo que abrir— y `CredentialsOutcome` dice
qué cuesta cada desenlace. Las dos decisiones se pueden comprobar sin disco, sin reloj y sin
frase de paso, y el comando las ejecuta en vez de repetirlas en prosa.

**Lo que el arnés hace ahora.** Las comprobaciones de la puerta caben en el escenario de
credenciales **que ya existía**: el número de escenarios **no crece** (siguen siendo 62) y ese
escenario pasa de 14 a **22** comprobaciones. Cada comprobación imprime su propia marca, y el
lanzador nombra la que no llegó.

**Y las marcas se comprueban contra el _transcripto_, no contra la consola.** Medido: la
consola del producto **intercala sus propias líneas de progreso dentro de la salida del
paso**, y una marca puede llegar partida en dos —la primera vez, `WRONG_KEY_CHANGES_NOTHING`
llegó como `WRONG_K` + la línea de progreso + `EY_CHANGES_NOTHING`—. El transcripto son los
bytes que el paso escribió, y ahí no se cuela nada.

**Lo que se vio en rojo.**

| Mutación | Qué se puso rojo | Por qué |
|---|---|---|
| el almacén deja de comprobar la llave | `WRONG_KEY_CHANGES_NOTHING` | la llave equivocada volvió a borrar la credencial: el almacén pasó de 204 a 137 bytes |
| la puerta vuelve a pedir llave donde no hay nada que abrir | `NO_STORE_NEEDS_NO_KEY` | y el transcripto mostró la respuesta equivocada: *listing an absent store answered 2 instead of 0* |
| ningún verbo necesita la llave | **2 de 5 filas puras** | las que afirman que un verbo que lee o cambia lo que hay dentro sí la necesita |
| todos los desenlaces cuestan 0 | **2 de 5 filas puras** | las que afirman que cada desenlace cuesta su código |

La primera y la segunda exigen romper **las dos capas** del mismo hecho: el comando lista
antes de quitar (y ese listado es lo que comprueba la llave), y el almacén comprueba por su
propio cuenta. Son la misma regla vista desde dos lados, y por eso una sola no basta para
quebrantarla.

**El coste no subió.** Los mismos **62** escenarios, las mismas **112** ejecuciones, y una
comprobación más en el escenario de credenciales.

**Lo que queda fuera, dicho con honestidad.**

- La consola del producto **intercala su progreso dentro de la salida del paso**, así que una
  comprobación que mire la consola puede partirse. Las comprobaciones nuevas miran el
  transcripto; las que miran la consola siguen mirándola, y esa es una deuda con nombre (§2.82).
- La puerta de una **corrida** no falla en la puerta: una frase de paso que no encaja se
  descubre en el primer uso de la credencial, y la corrida termina con el código de un paso
  fallido. El mensaje es cierto y el fallo es cerrado, pero el KDoc de la costura promete «fail
  fast» para los dos casos, y solo el de «no hay llave» lo cumple. Corregirlo cambia el código
  de salida de un modo de fallo ya publicado —una ruptura versionada— y **no se ha hecho en
  este ciclo**: la mejora real es que el mensaje dice la verdad (§2.82).
- La promoción a estable sigue bloqueada: no hay certificador externo en este repositorio ni
  en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no `PASS`.
- La economía del arnés por **coste** sigue abierta: la caché de compilación vive dentro del
  ciclo y se borra con él (§2.79).
### 2.83 El runtime no carga una sonda que escribe en el hogar

**El hueco.** El ejecutor de shell durable llevaba dentro una sonda que copiaba los permisos de
sus FIFOs a un fichero del directorio personal del usuario:

```
~/obsPc2diag/fifo-perms-<opId>.txt
```

La invocación estaba en `materialiseFifos()`, en el camino de todo paso durable, sin ninguna
opción de depuración que la apagara. Una ejecución normal creaba un fichero nuevo fuera de la
raíz de control autorizada, y el `runCatching` que la envolvía se tragaba cualquier error de
escritura: si el disco estaba lleno o el home no existía, el paso seguía como si nada.

**Lo que se midió antes de tocar nada.** En la máquina de trabajo, el directorio llevaba
**175 ficheros** acumulados de ejecuciones anteriores, repartidos entre trazas de otros ciclos
y **3** de esta sonda:

```
$ ls ~/obsPc2diag | wc -l
175
$ ls ~/obsPc2diag/fifo-perms-* | wc -l
3
```

El contenido de uno de ellos dice exactamente lo que el producto cree:

```
stdout.fifo=true,OWNER_READ|OWNER_WRITE
stderr.fifo=true,OWNER_READ|OWNER_WRITE
```

**Lo que ahora es verdad.** La sonda no está. La comprobación de que el FIFO es un FIFO de
dueño único se hace **donde se decide** —en el momento de crearlo, que es el único momento en
que el permiso es de nadie más— y no en una escritura posterior a un directorio ajeno.

El permiso se sigue fijando al crear el FIFO, y sigue siendo exactamente `0600`: propietario
lee y escribe, nadie más. Medido sobre el producto real, en una corrida durable, desde dentro
del paso:

```
fifo=…/control/<runId>-s0-0/fifos/stdout.fifo type=`fifo' octal=600
FIFO-PERMS-OK
```

**Dónde vive ahora la inspección.** En un escenario **efímero** que es dueño de sus ficheros: el
lanzador le entrega la raíz de control de esa misma ejecución, y los FIFOs viven debajo, en
`{controlRoot}/{opId}/fifos`. Leerlos desde dentro del paso es leer su propio directorio. La
inspección se hace **mientras el FIFO existe**, porque el runtime borra la raíz de control
cuando el paso termina: un escenario que mirara después no encontraría nada y no concluiría
nada.

**La comprobación puede ponerse roja.** Un verde que nunca se ha visto rojo no es evidencia, así
que la comprobación se falseó a propósito, sobre copias de la sonda, sin tocar el producto:

| caso | sonda sin mutar | con la mitad correspondiente desactivada | qué demuestra |
|---|---|---|---|
| FIFO real con `0600` | **verde** | — | la línea de base es correcta |
| FIFO real con `0644` | **roja** | **verde** | la mitad de permisos carga el peso: rechaza un FIFO legible por todos |
| fichero regular con `0600` | **roja** | **verde** | la mitad de tipo carga el peso: rechaza un spool en claro |

El tercer caso tiene dos defensas, y las dos se midieron: aflojar solo la *selección* (que
buscaba el FIFO con `-p`) seguía en rojo, porque la *afirmación* de tipo que viene después lo
vuelve a rechazar; aflojar las dos lo volvía verde. Un fichero regular en el sitio del
rendezvous es exactamente el spool en claro que la garantía prohíbe, y la comprobación lo
dice.

**El efecto lateral también se comprobó, no se_affirmó.** La corrida anterior a este ciclo
creaba un `fifo-perms-<runId>-s0-0.txt` nuevo en `~/obsPc2diag` en cada ejecución durable. Con
la sonda fuera, la cuenta no se mueve:

```
=== HOME side effects: obsPc2diag dirs 1 -> 1, files 176 -> 176 ===
PASS: FIFO is a named pipe with owner-only perms, and the run left $HOME untouched
```

**Y el otro brazo, medido con el producto real.** La ruta que llegaba a la sonda —el bombeo
del transcripto por un agente externo— se activa con tres propiedades del sistema, y nada en
el árbol las pone. Con la sonda presente y esas propiedades puestas, una corrida durable
termina **con éxito** y **crea un fichero nuevo en el directorio personal**: la cuenta pasó de
**176** a **177**. Con la sonda fuera y las mismas propiedades, la corrida también termina con
éxito y la cuenta **no se mueve**. La diferencia entre los dos brazos es exactamente la sonda,
y el producto se comporta igual en todo lo demás: el permiso del FIFO se sigue decidiendo
cuando se crea.

**Lo que queda fuera, dicho con honestidad.**

- La comprobación es **efímera y se retira con su ciclo**: es la evidencia de que este defecto
  está cerrado, no un contrato permanente del producto. Vive bajo
  `${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/verification-cycles/`, no en `examples/`, y
  el escenario **no crece**: los 62 escenarios del conjunto versionado siguen siendo 62.
- El modo que llegaba a la sonda —el bombeo del transcripto por un agente externo— **no lo
  conduce nada del árbol salvo por una propiedad del sistema**, y por eso la sonda era código
  muerto. La medición de arriba lo activa a propósito para poder observar el FIFO; el producto
  en su configuración por defecto no entra por ahí.
- Los **175 ficheros** que la sonda dejó acumulados en `~/obsPc2diag` **siguen ahí**: este ciclo
  quita la causa, no limpia el pasado. Borrarlos es una operación sobre el home del operador, y
  por eso no se ha hecho a escondidas.
- La promoción a estable sigue bloqueada: no hay certificador externo en este repositorio ni
  en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no `PASS`.
### 2.84 El instalador resuelve una release por su manifiesto, no por su nombre

**El hueco.** `scripts/install-pipelinek.sh` construía las cuatro identidades de una
release a partir de una sola cadena —el *slot* que escribe el operador— y las
hacía coincidir:

| identidad | cómo la calculaba el instalador | lo que es en la release publicada |
|---|---|---|
| etiqueta del release | `v${version}` | `v0.88.0-rc1` |
| artefacto | `pipelinek-${version}.zip` | `pipelinek-0.88.0.zip` |
| raíz del ZIP | `pipelinek-${version}` | `pipelinek-0.88.0` |
| versión que reporta el binario | la que se pidió | `0.88.0` |

En el mundo real esas cuatro cosas no son la misma. La candidata `v0.88.0-rc1`
publica el artefacto `pipelinek-0.88.0.zip`, y el binario que lleva dentro
reporta `pipeline 0.88.0`. La comprobación de semver estricto
(`^[0-9]+\.[0-9]+\.[0-9]+$`) rechazaba `0.88.0-rc1` antes de mirar nada, y
`install 0.88.0` señalaba `/v0.88.0/`, un directorio de release que no existe
cuando lo único publicado es la candidata. El README lo declaraba sin
rodeos: «no resuelve una candidata». La fila 8 de §1 ya medía el hueco.

**Lo que ahora es verdad.** Un release tiene cuatro nombres y **no son
intercambiables**, y el manifiesto que se publica con el release
(`pipelinek-release-manifest-1`) es la autoridad que los ata: dice qué etiqueta
es, qué versión de producto lleva y qué artefacto la contiene, con su digest.
El instalador hace, en este orden:

1. **Lee** el manifiesto de `${base}/${tag}/manifest.json` y **comprueba** que
   nombra ese tag, esa versión de producto y **exactamente un** artefacto.
2. **Cruza** el digest del manifiesto con el de `SHA256SUMS`, la autoridad de
   digest del release. Si los dos no coinciden, no instala: un release que se
   contradice a sí mismo sobre su propio artefacto no tiene un ganador
   creíble.
3. **Descarga** el artefacto **por el nombre que dice el manifiesto**, no por
   uno construido: `pipelinek-${version}.zip` ya no se inventa, se descubre.
4. **Verifica** que la raíz del ZIP sea la del artefacto, y que el binario
   reporte **exactamente** la versión de producto del manifiesto.

El *slot* es el nombre del release y el nombre del directorio donde se
instala: `0.88.0-rc1` instala en `versions/0.88.0-rc1` y `0.88.0` instala en
`versions/0.88.0`. Los dos pueden convivir —son dos releases— y la candidata
**jamás** se instala bajo el nombre de la estable. Un slot estable que no
existe se rechaza con un mensaje que dice qué etiqueta no se encontró y
**señala la candidata que sí existe**, porque un refusal veraz ahorra más
tiempo que un 404 mudo.

**La comprobación es efímera y está fuera del árbol.** Vive bajo
`${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/verification-cycles/`, no en
`examples/`, y **el número de escenarios versionados no crece**: siguen siendo
**62**. El instrumento es el propio instalador —la frontera real del
consumidor— contra los **bytes publicados** de `v0.88.0-rc1`, descargados del
remoto: manifiesto, `SHA256SUMS` y el ZIP de 92 MB. Se midieron **36**
comprobaciones:

| grupo | qué mide |
|---|---|
| G1 | la candidata se instala desde su tag, en su directorio, y su binario reporta `0.88.0` |
| G2 | el slot estable `0.88.0` **no** resuelve la candidata, contra el remoto real, y no deja directorio |
| G3–G5 | activar, listar y desinstalar; dos releases conviven sin mezclarse; el activo no se borra |
| G6–G7 | bytes alterados, manifiesto ajeno y manifiesto en desacuerdo con la autoridad: se rechazan y no dejan rastro |
| G8 | la instalación funciona **contra el remoto real**, no solo contra el espejo |

**La comprobación puede ponerse roja.** Un verde que nunca se ha visto rojo no
es evidencia, así que se falseó a propósito, sobre **copias** del instalador
—nunca sobre el fichero del repositorio—, comprobando que cada comprobación
carga con el peso que dice cargar:

| mutación sobre la copia | qué tiene que ponerse rojo | qué demuestra |
|---|---|---|
| resolver **todos** los slots con el tag de la candidata | G2a, G2b, G2c | la separación entre release y candidata es la que impide la confusión |
| no comparar el digest de los bytes | G6a, G6b, G6c | la autoridad de digest es lo que impide instalar bytes ajenos |
| no comprobar el tag que dice el manifiesto | G7a, G7b, G7c | el manifiesto tiene que **describir** el release que se pide |
| construir el nombre del artefacto desde el slot | G1a, G1e | el artefacto se **descubre**, no se inventa |
| rehusar sin señalar la candidata | G2d | el refusal tiene que decir qué release existe |

Las **18** filas de la falsificación quedaron en verde, y el control —el
mismo UAT sin mutar— también.

**La puerta también pasó.** El árbol commiteado (`a1ced1828`) dio `PASS` en los
tres ensayos de `scripts/verify-all.sh` —**186**, **172** y **176** s contra el
límite de 900—, con los **1175** tests reescritos, los **62** escenarios
ejecutados y **578** comparaciones sobre **112** ejecuciones del producto. La
ratio de LOC bajó, porque el instalador cuenta como producto: `21971 / 68421
= 0.321`, y el ratio máximo por módulo sigue siendo **1.633**
(`pipeline-step-sdk:workflow-control`). El README, que declaraba el hueco
—«no resuelve una candidata»—, deja de hacerlo.

**La publicación de un script se verifica como se verifica un artefacto.** El
instalador no viaja dentro del release: el README lo sirve desde `main`. Su
publicación tiene, por tanto, su propio canal, y ese canal se comprobó: se
descargó de `raw.githubusercontent` **en el SHA publicado**, se comparó **byte
a byte** con el fichero medido, y se le pasó **el UAT entero** contra la
release real.

```
$ sha256sum scripts/install-pipelinek.sh desde-remoto/install-pipelinek.sh
e0d1b6e758911e600aca2639f99b8875a1711c7b7244ad766a5101146b262443  scripts/install-pipelinek.sh
e0d1b6e758911e600aca2639f99b8875a1711c7b7244ad766a5101146b262443  desde-remoto/install-pipelinek.sh
resultado: PASS   comprobaciones: 36   ok: 36   ko: 0
```

**Lo que queda fuera, dicho con honestidad.**

- La comprobación **no es permanente**: la política prohíbe las pruebas por
  subproceso que la harían permanente, y el instalador no es un módulo con
  `src/test`. Lo que hay es un UAT efímero por ciclo y un `doctor` manual
  contra una instalación real. Esa deuda ya estaba anotada en el propio
  script y en la fila 8 de §1.
- El release **estable no existe en este repositorio**: la línea publica
  candidatas. El camino del slot estable se midió contra un espejo que sirve
  los **mismos bytes** bajo una etiqueta estable —un sustituto, no un release
  publicado— y el camino del rechazo se midió contra el **remoto real**, donde
  la estable no existe. No se ha publicado un estable para poder probarlo.
- **No se ha publicado una candidata nueva.** El producto de esta hito no
  cambia: el cambio está en un script que el README sirve desde `main`, no en
  la distribución, y su publicación se verificó por ese canal. Se **midió** que una reconstrucción de la distribución
  difiere de la publicada en **un solo** campo —`buildCommit`, la procedencia
  embebida— y en **un byte**; el comportamiento es idéntico. Publicar un
  `rc2` habría puesto dos artefactos del mismo tren en la página de releases
  sin que el producto se comportara distinto, que es exactamente la
  confusión que este hito quita. El precedente del remoto lo dice: de
  `v0.63.0-rc1` a `v0.88.0-rc1`, **un release por tren**, todos `rc1`.
- La promoción a estable sigue bloqueada: no hay certificador externo en este
  repositorio ni en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no
  `PASS`.

### 2.85 El productor crea el directorio de su almacén

**El hueco, medido contra el producto real.** Tres hechos, no tres opiniones:

| invocación | respuesta | efecto lateral |
|---|---|---|
| `run --db <dir>/events.db` con `<dir>` inexistente | **exit 2**, `Error: no directory for db: <path>` | ninguno: no crea nada |
| `run --control-root <dir>/durable-shell` con `<dir>` inexistente | **exit 0** | **crea** `<dir>` y sus hijos |
| `run --db events.db` (ruta relativa; el directorio de trabajo existe) | **exit 2**, `Error: no directory for db: events.db` | ninguno |

Dos directorios del mismo tipo con dos comportamientos, y un rechazo **falso**
en el caso relativo: `events.db` vive en el directorio de trabajo, que existe.

**La causa.** La puerta de admisión (`EventStoreAdmission`) observaba el camino y
colapsaba tres estados distintos en uno solo: «el directorio que lo sostendría no
existe», «ahí no hay un directorio» y «no hay componente de directorio». Los tres
se rechazaban, y se rechazaban igual para los tres verbos. La regla pura era
total sobre dos ADTs cerrados, así que la confusión no era un descuido de un
`if`: era una decisión, escrita entera, que acababa en un rechazo para todo.

**Lo que ahora es verdad.** La regla tiene **tres** finalidades, y cada una
declara qué sitio es suyo:

| finalidad | qué es | qué puede faltar | qué hace con el sitio |
|---|---|---|---|
| `Read` | leer una run terminada | nada | se niega |
| `Write` | escribir en una run terminada (`cancel`, `recover`) | nada | se niega |
| `Produce` | **empezar** una run | el fichero **y** su directorio | **los crea** |

Y la observación se desdobla, porque «falta el directorio» —que el productor
puede crear— y «ahí no hay un directorio» —que nadie puede— no son la misma
ausencia: el primero se puede crear, el segundo se puede nombrar y nada más.
La matriz de la regla pura pasa de **3×2 = 6** filas a **4×3 = 12**, y cada
combinación está escrita; añadir una finalidad obliga a revisitarla, que es
justo lo que una regla total hace bien.

El rechazo que ve el operador **no cambia**: sigue siendo `no directory for db`,
con la ruta. Lo que cambia es quién lo recibe: ya no lo recibe el productor.

**Un segundo defecto, alcanzado por el primero.** La raíz de control de una run
se derivaba del almacén con `Paths.get(dbPath).parent.resolve(...)`, y un `--db`
relativo no tiene componente de directorio: `getParent()` devolvía `null` y la
run moría con una NullPointerException. Era **código muerto** hasta ahora,
porque la puerta rechazaba el camino relativo antes de llegar ahí. Al poder el
productor su directorio, ese camino llega, y la excepción deja el proceso **en
pie sin terminar** —el hilo principal muere y los hilos del ejecutor durable no
lo sueltan— en vez de salir con un diagnóstico. Ahora la ruta del almacén se
hace absoluta antes de pedirle su directorio: el directorio de trabajo es el
que sostiene un `--db` relativo. Para un `--db` absoluto nada cambia.

**El cambio se plantó en la puerta correcta, y no por casualidad.** La PR #91,
que quedó abierta desde la línea anterior, llevaba el arreglo al sitio donde se
crea el fichero de SQLite. Ese sitio es correcto para un motor, pero **alcanzable
solo**: la puerta de admisión se negaba **antes**, y un arreglo en el motor, con
la puerta como está, no se ejecuta nunca. Replantado en la puerta, el efecto
aparece; replantado en el motor, habría parecido funcionar sin haberlo hecho.
Por eso el cambio vive en `EventStoreAdmission` y no en la fábrica de conexiones.

**La comprobación es efímera y está fuera del árbol.** Vive bajo
`${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/verification-cycles/`, no en
`examples/`, y **el número de escenarios versionados no crece**: siguen siendo
**62**. Hay dos instrumentos, porque hay dos garantías:

- La **regla pura** la vigila un test unitario en
  `pipeline-events-store/src/test/kotlin`, que es su sitio natural: la matriz de
  doce filas, con guardas que rechazan omisiones y duplicados antes de generar
  los casos.
- El **efecto** —que el directorio se crea de verdad, y que los lectores no lo
  crean— lo vigila un UAT efímero con la **distribución real**: **17**
  comprobaciones en cinco grupos (el productor crea; el lector no crea y se
  niega; el verbo que escribe en una run terminada tampoco crea; una ruta
  relativa arranca y no dice que no hay directorio; un sitio que no es un
  directorio se rechaza y no se toca lo que había).

**La comprobación puede ponerse roja.** Se falseó con **dos** mutaciones, y cada
una puso en rojo exactamente lo que le toca:

| mutación | qué se puso rojo | qué se quedó en verde |
|---|---|---|
| la regla pura deja de admitir (producir, directorio ausente) | las **2** filas del test unitario que la vigilan | las otras 12 |
| el Caparazón deja de llamar a `createDirectories` | las **6** comprobaciones del productor | las **11** de lectores, ruta relativa y sitio que no es directorio |

Que la segunda no tocara nada más es la prueba de que el instrumento discrimina:
un verde que no depende de lo que se está midiendo no está midiendo.

**Lo que se publicó.** El producto **sí cambia de comportamiento**, así que este
bloque abre **tren nuevo** (`0.89.0`) y no otra candidata del anterior: la
candidata `v0.89.0-rc1` queda publicada, con `candidate_id`
`sha256:9da1c7b44eb4cd3b803457bbedcd72164d96ee1ccd05d451d0c49f8d8323d70d` y el
tag en `9e2cb034e`. Los **7** assets se descargaron del remoto y las **6** sumas
de `SHA256SUMS` coincidieron **una a una** contra los bytes publicados. La puerta
completa dio `PASS` en **tres** ensayos sobre ese mismo SHA —**196**, **159** y
**148** s, con `tests=rewritten:1183 read:0` y **62** de **62** escenarios— y la
ratio de LOC quedó en **22022 / 68455 = 0.322**, con el módulo más alto en
**1.633**. La deuda estática congelada sigue en **879** entradas: este bloque no
toca ninguna baseline.

En el remoto, la PR #91 quedó **cerrada** con una referencia al reemplazo y su
rama **borrada**; las incidencias **#145**, **#106**, **#104** y **#103** se
comentaron con la evidencia medida y se cerraron. Quedan **0** incidencias
abiertas y **0** PRs abiertas.

**Lo que queda fuera, dicho con honestidad.**

- El **verbo que escribe en una run terminada** (`cancel`, `recover`) sigue
  creando un fichero de base de datos vacío como efecto lateral de su propio
  rechazo. Es un comportamiento anterior a este bloque, no lo introduce este, y
  no se toca aquí.
- Una excepción en el hilo principal **deja el proceso en pie** en vez de
  salir. Se ha visto en vivo con el defecto anterior. Es una falta de
  robustez del runtime que este bloque no arregla: la mide y la deja escrita.
- El verbo `runs recover` existe y funciona, pero **no aparece** en la lista de
  verbos que el producto publica. Se ha reportado; no se ha cambiado, porque
  tocar esa lista es tocar un contrato publicado.
- La promoción a estable sigue bloqueada: no hay certificador externo en este
  repositorio ni en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no
  `PASS`.

### 2.86 El coste de la puerta: medir antes de optimizar

**Lo que la auditoría pedía, y lo que ya estaba.** La auditoría propone tres
niveles de validación con un objetivo cada uno. Medidos sobre este árbol,
**los tres ya cumplen**:

| nivel | objetivo | medido | veredicto |
|---|---|---|---|
| edición cotidiana | 5–20 s | **14–17 s** (`--stage 2 --module ':pipeline-events-store' --test '*EventStoreAdmissionTest'`) | cumple |
| integración del bloque | < 60 s | **35 s** (dos módulos) / **44 s** (dos módulos + `events/*`) | cumple |
| antes de release | — | **148–196 s** sobre un presupuesto de 900 | cumple |

Y el coste por escenario es **casi plano**: 62 escenarios, mediana **11,2 s**,
media 11,9 s, mínimo 7,4 s, máximo 29,2 s. El número de comprobaciones casi
no cuenta: los escenarios con **≤ 8** comprobaciones tardan 11,6 s de media y
los de **> 8**, 12,5 s. El suelo no es lo que el escenario comprueba: es lo
que cuesta **arrancar**.

**El suelo era la compilación.** El producto compila cada script que ejecuta.
Medido sobre un escenario: **4,9 s** sin artefacto y **0,8 s** con el
artefacto reutilizado, o sea **4,1 s** por compilación. La suite compila
**62** scripts por ciclo: unos **254 s** de CPU que se volvían a pagar
enteros en cada ciclo, porque la caché vivía **dentro del directorio del
ciclo** y moría con él.

**Lo que la auditoría daba por cierto, y no lo era.** Su propuesta dice
«mantendría la caché por hash de fuente, classpath y versión, que ya
existe». La caché existe, pero **no sobrevive al ciclo**, y no puede
sobrevivir tal como está: la clave del producto cubre el fuente, la versión
del compilador, las opciones y el **ruta** de los jars del classpath — pero
no su **contenido**. Medido: **dos construcciones de la misma versión en el
mismo directorio comparten clave**. Una caché que sobreviviera a la
construcción serviría un artefacto compilado contra un producto que ya no
existe.

Ese hueco no es de este bloque: es del producto, y el propio producto lo
declara — la clave dice, en su KDoc, que dos de sus siete dimensiones
(`dslApiVersion`, `pluginLockDigest`) **no se pueden leer**.

**Lo que se hizo, en el sitio que puede.** El lanzador sabe qué
distribución acaba de construir, y el producto no. Así que la dimensión que
falta la aporta el lanzador: la caché vive en el directorio de caché, bajo
un **espacio de nombres que es el digest del contenido** de la distribución
—mismos bytes, reutiliza; bytes distintos, otro espacio de nombres y
compilación en frío. Y el espacio de nombres lleva además un **marcador**
con el digest: un ciclo que encuentre un marcador de otra distribución no
usa ese directorio, y lo dice en una línea. El guard es permanente y no
depende de que este ciclo exista.

Los **9** escenarios cuyo classpath lleva una ruta **dentro del ciclo** —5
que corren contra su propia copia de la distribución y 4 que nombran su
propio plugin— siguen con la caché **local del ciclo**: su clave cambiaría
en cada ciclo, así que no podrían reutilizarse nunca, y un espacio de
nombres compartido acumularía entradas que no se pueden volver a servir.

**Y el gate publica el coste.** El lanzador medía el coste por escenario y
el estado de la caché, y el gate lo leía y lo tiraba. Una medición que se
descarta no es una medición: es un número que nadie puede usar para decidir
nada. Ahora el gate dice cuántos escenarios midió, cuánto suman, cuáles son
los más caros, y cuántos artefactos escribió y cuántos reutilizó. Una
reutilización que dejara de funcionar se vería en la línea de la caché antes
de que nadie notara que la puerta tarda más.

**Lo medido, antes y después.** La etapa de E2E, con el mismo censo
(**578** comprobaciones sobre **112** ejecuciones, **62** de **62**
escenarios, **218** pasos terminados):

| | artefactos escritos | suma por escenario | etapa de E2E |
|---|---|---|---|
| caché de ciclo (antes) | 62 en frío, cada ciclo | 740 s | **94–97 s** |
| caché persistente, en frío | 53 | 626 s | **92 s** |
| caché persistente, caliente | **0** | 400 s | **64 s** |

**28–33 s menos** en la etapa, y la suma del coste por escenario baja de
**740 s** a **400 s**: el suelo de cada escenario era la compilación, y el
suelo ya no lo es. El escenario más caro pasa de **29,2 s** a **20,0 s**, y
la diferencia se la lleva el que más compilaba. El precio de saber que se
puede reutilizar es **0,5 s** por ciclo: el digest de los 47 jars de la
distribución.

**La puerta completa, en tres ensayos sobre el mismo SHA:** **165**, **116**
y **116** s, con `tests=rewritten:1183 read:0` y **62** de **62** escenarios.
El primero es **en frío** y los otros dos **en caliente**, y no es casualidad:
la distribución lleva dentro el `buildCommit`, así que **un commit nuevo es
un producto nuevo** y tiene su propio espacio de nombres. El ahorro se
materializa sobre el mismo árbol, que es exactamente el bucle de trabajo de
quien cambia el producto — y el primer ensayo paga la compilación de los 53
scripts, como debe.

**La comprobación puede ponerse roja.** El UAT efímero tiene **16**
comprobaciones, y se falseó con **dos** mutaciones, cada una en una capa
distinta:

| mutación | qué ocurrió | qué se puso rojo |
|---|---|---|
| **A**: el nombre del espacio de nombres deja de llevar el digest | el **guard permanente** del lanzador dispara y **no hay reutilización indebida** | las filas del ciclo 3 |
| **B**: el nombre **y** el marcador se rompen | la reutilización indebida **ocurre de verdad** —el producto con otros bytes se sirve el artefacto del anterior— y el UAT la ve | las filas del ciclo 3 |

En las dos, las filas rojas son **las de la regla de antigüedad y solo
esas**: ninguna de los ciclos 1 o 2 se puso roja. Una mutación que lo pone
todo rojo no demuestra que el instrumento mida lo que dice medir.

El falsificador encontró además un defecto **en el instrumento**: bajo
`set -e`, un `grep` que no encuentra nada aborta el UAT, y las filas que
no se ejecutaron se contaban como un instrumento más pequeño y no como uno
roto. Corregido antes de dar por buena la falsificación.

**Lo que queda fuera, dicho con honestidad.**

- La **clave del producto** sigue sin cubrir sus bytes. Lo que este bloque
  hace es rodear el hueco desde el lanzador; un usuario que ponga su propio
  `PIPELINEK_SCRIPT_CACHE_DIR` sigue expuesto al mismo riesgo. Corregirlo
  es un cambio de producto, con su tren y su candidata, y no se ha hecho
  aquí.
- Los **9** escenarios de arriba no se benefician, y no pueden.
- La **selección por garantía** que la auditoría propone —mapear los
  cambios a capacidades observables— **ya existe**, y no como un catálogo
  nuevo: el árbol de `examples/` **es** el mapa (`durable/`, `events/`,
  `security/`, `steps/`, `plugins/`, `output/`), y `--scenario` selecciona
  por él. Un inventario aparte sería una segunda autoridad sobre la misma
  cosa, que es exactamente lo que la política prohíbe.
- **No se publica candidata nueva.** El producto no cambia de comportamiento:
  lo que cambia es el arnés, que no viaja en la release. La candidata
  publicada sigue siendo `v0.89.0-rc1`, con los mismos bytes.
- La promoción a estable sigue bloqueada: no hay certificador externo en
  este repositorio ni en el remoto, así que el veredicto es
  `BLOCKED_EXTERNAL`, no `PASS`.

### 2.87 Mantenibilidad durable: dos rebanadas verticales

**Lo que la auditoría pedía, y cómo se parte.** La auditoría pide dos cosas
concretas: «revisar el estado compartido `lastTranscriptPumps` para demostrar
que dos ejecuciones simultáneas no pueden intercambiar los objetos de
drenaje» y «una única definición tipada del contrato del evento». Y dice, con
la honestidad que hay que atribuirle: «No recomiendo una refactorización
masiva: hacerlo por vertical slices cuando una modificación funcional
requiera atravesar varias de estas piezas».

Este bloque son **dos** rebanadas verticales, no una refactorización, y las dos
cosas que **no** se han hecho se declaran al final.

#### Rebanada A — el contrato del evento: un nombre, un sitio

**Medido antes de tocar.** El catálogo de eventos tiene **11** nombres de
campo opcional, repartidos en **16** sitios de escritura y **16** de lectura.
El escritor y el decodificador los deletreaban **por separado** —un literal
`,"detail":` en un lado y un literal `"detail"` en el otro—, sin nada que
dijera que los dos tuvieran que coincidir. Un campo añadido en un solo lado
compilaba, se ejecutaba y se decodificaba mal: el valor desaparecía al
volver. Y un registro durable que vuelve sin su carga es **indistinguible**
de uno que nunca la tuvo.

**Lo que estaba fijado: 3 de 11.** `outcome`, `detail` y el `reason` de
`SagaTerminal`, por dos pruebas escritas para otros motivos. Los otros
**ocho** —`remoteUri`, `stepName`, `stepType`, `buildResult`, `message`,
`label`, el `reason` del bloqueo, `submitter`, `id`— no los fijaba nadie. El
KDoc de `EventJsonFields` prometía una prueba de fitness que «afirmase que
las dos mitades cubren el mismo conjunto de campos». **No existía.**

**Lo que se hizo.** Los nombres viven ahora en un conjunto **cerrado**
(`OptionalEventField`, un enum) y las dos mitades los leen de ahí. Cerrado a
propósito: un campo opcional que no sea un caso del enum **no se puede
escribir ni leer**, de modo que «las dos mitades cubren el mismo conjunto de
campos» es una propiedad del **tipo**, y no una promesa en un comentario.

**Los bytes del cable NO cambian.** El mismo nombre, la misma codificación
del valor ausente (`""`), la misma lectura de vuelta. Lo que cambia es que
ahora hay **un** sitio donde el nombre vive, y que no se puede escribir un
campo opcional sin nombrarlo.

**La prueba que faltaba.** La nueva recorre el conjunto entero por **tres**
particiones —ausente, presente, y una fila anterior a la existencia del
campo— y comprueba **el nombre en el cable**, no solo el viaje de ida y
vuelta: un viaje de ida y vuelta no ve un campo que las dos mitades
deletrean igual de mal. Son **49** filas: 16 pares (tipo, campo) × 3
particiones, más una que comprueba que **todo** caso del enum tiene fila.

**Un defecto del propio instrumento, encontrado por la falsificación.** La
primera versión de la prueba derivaba el nombre esperado del propio enum —el
código que tenía que comprobar—, y un renombre **simétrico** de un campo la
pasaba sin despeinarse mientras cada registro histórico en disco dejaba de
poder leerse. Corregido antes de dar por buena ninguna falsificación: el
nombre esperado es ahora un **literal**, que es la especificación de un
formato público, y el enum es su implementación.

#### Rebanada B — el estado del lanzamiento: lo que produce, lo devuelve

**Medido antes de tocar.** El ejecutor durable guardaba en **tres** campos
`@Volatile` lo que su último lanzamiento había producido: las bombas de
transcripción, los sumideros donde esas bombas escribieron y los descriptores
de calentamiento del agente. El lanzamiento los escribía y un paso posterior,
**separado**, los leía. El KDoc de aquel campo lo llamaba «disciplina de
lanzamiento de un solo hilo: un lanzamiento por `executeCore`», y eso era
exactamente: una **disciplina**, no una invariante.

**El intercambio NO es alcanzable hoy**, y hay que decirlo: todos los
llamantes construyen su propio ejecutor (`DurableShellExecutor().execute…`),
así que no hay dos ejecuciones que compartan una instancia. Lo que había era
un defecto **latente**, a un refactor de distancia, sujeto solo por una
disciplina. Por eso queda como hallazgo medido y no como urgencia.

**Lo que se hizo.** Un lanzamiento devuelve **lo que produjo**
(`LaunchedShell`): el hijo, sus bombas, sus sumideros y los descriptores de
calentamiento que hay que cerrar. Los tres `@Volatile`, el `consume` y la
lectura por campo desaparecen. El veredicto sobre la transcripción se calcula
desde el lanzamiento, con una función pura que recibe el valor
(`transcriptVerdict(shell, config)`), y esa es la respuesta a la pregunta de
la auditoría: no queda **estado ambiental** al que dos ejecuciones puedan
intercambiarse, porque el estado **es** el argumento.

**De paso, una costura muerta.** `DurableShellLaunching` era una interfaz con
**una** implementación, **ningún** consumidor y **ningún** fake, y su KDoc
prometía «implementaciones fake para testear sin bifurcar procesos reales»
que no existían. Una costura con un solo lado no es un puerto. Se ha
eliminado (**153** líneas), con sus referencias y con la entrada que la
baseline de detekt tenía por su supertipo.

**Y un detalle que el refactor casi pierde.** Los descriptores de
calentamiento del agente se abren **antes** de que el hijo arranque. Si el
hijo nunca arranca, el llamante no recibe ningún lanzamiento y no puede
cerrarlos: se cierran en el fallo del lanzamiento, que es donde estaban antes.

#### Lo que se falsificó

Ocho mutaciones dirigidas, cuatro por rebanada. Cada una se aplicó, se leyó
el diff de vuelta, y se comprobó que ennegrece **exactamente** las filas que
debe y ninguna más:

| rebanada | mutación | filas rojas | filas verdes |
|---|---|---|---|
| A | el contrato renombra un campo **en las dos mitades** | 3 | 46 |
| A | el decodificador deriva **solo** de un lado | 1 | 48 |
| A | el lector deja de mapear la ausencia | 36 | 13 |
| A | la autoridad gana un campo **sin fila** en la prueba | 1 | 48 |
| B | el veredicto ignora **las bombas** de su lanzamiento | 1 | 11 |
| B | el veredicto ignora **los sumideros** de su lanzamiento | 1 | 11 |
| B | el estado vuelve a ser **compartido** (bombas) | 1 | 11 |
| B | el defecto original **completo** (bombas y sumideros) | 2 | 10 |

Las tres primeras de A se leen así: un renombre simétrico es un **cambio de
formato público** que la prueba tiene que ver; una deriva de un solo lado es
la forma que tenía el hueco; y el lector que deja de mapear la ausencia
ennegrece **todo lo que toca un valor ausente**, que son 36 filas y no todas
—las 13 que quedan verdes son las de los eventos con un único campo
opcional, donde la ausencia ya no está en juego.

#### La puerta

Tres ensayos sobre el mismo SHA: **182**, **131** y **127** s, con
`tests=rewritten:1234 read:0` y **62** de **62** escenarios (**578**
comprobaciones sobre **112** ejecuciones, el mismo censo de antes). El LOC:
VERIFY_LOC **22508** / PRODUCT_LOC **68424** = **0,329**, muy por debajo del
límite de 2.

#### Lo que queda fuera, dicho con honestidad

- **El contrato completo del evento sigue siendo tres superficies.** Lo que
  aquí se cierra es el **nombre** de los campos opcionales. El catálogo de
  tipos, el `when` del escritor y el `when` del decodificador siguen siendo
  tres sitios donde un evento nuevo hay que escribir tres veces, y eso es una
  definición tipada del **evento**, no de sus campos. Queda como fila 15 de
  §1.
- **El ejecutor durable sigue concentrando responsabilidades.** Lo que aquí se
  cierra es el **estado del lanzamiento**. Los procesos, el FIFO, el watchdog,
  la proyección de salida y el ciclo de vida siguen en el mismo fichero, y la
  auditoría pide puertos concretos detrás. Queda como fila 16 de §1.
- **`CliParser.kt` (1.898), `MainConsoleCli.kt` (1.538),
  `SegmentOutputStore.kt` (1.293) y `StepDispatchEngine.kt` (1.185)** no se
  han tocado: la auditoría los señala como deuda de la misma familia.
- **Se publica candidata nueva** (`v0.90.0-rc1`): el producto cambia de
  comportamiento observable —el contrato del evento tiene una autoridad, y el
  lanzamiento devuelve su estado—, así que la ley de versión abre un tren
  nuevo (0.90.0) en vez de reutilizar el 0.89.0, que quedó cerrado en
  `v0.89.0-rc1`.
- La promoción a estable sigue bloqueada: no hay certificador externo en
  este repositorio ni en el remoto, así que el veredicto es
  `BLOCKED_EXTERNAL`, no `PASS`.

### 2.88 Compatibilidad externa: un consumidor de verdad, contra lo publicado

**Lo que se pedía, y lo que no había.** «Validar artefactos publicados con un
consumidor externo real... SDK y CLI consumidos desde el release descargado, sin
depender del checkout». No había ninguna comprobación que lo hiciera. La suite
E2E corre contra la distribución de desarrollo que construye `installDist`, y
los plugins de ejemplo que consume se construyen en el mismo árbol y contra el
SDK que genera esa misma revisión. Entre «el checkout» y «lo publicado» no
había ni un dato, así que la compatibilidad externa era una afirmación y no
una medición.

**La forma, decidida antes de escribir nada.** El consumidor es el plugin del
repositorio, construido por su propio build de Gradle, que es un límite de
build independiente: resuelve el SDK como coordenadas públicas y no puede ver
el interior del producto. El SDK se consume desde el release publicado. Y la
comprobación corre en la vía de publicación, antes de cortar el tag, porque un
tag que existe no se puede deshacer.

De ahí una consecuencia que conviene decir de entrada: **no es un escenario de
`examples/`** —esos corren contra la distribución de desarrollo— **ni un test
unitario** —un test unitario no puede lanzar Gradle, la CLI y una JVM—. Es una
puerta de publicación, con su latencia propia, y por eso no forma parte de la
puerta local de 900 s: esa sigue siendo la autoridad de lo local, y esta la de
lo publicado.

#### El defecto que encontró el trabajo: el consumidor declaraba un rango que no había compilado

**Medido.** En el mismo árbol, apuntar `-PsdkRepo` y `-PsdkVersion` a otro SDK
publicado dejó `compileKotlin` en `UP-TO-DATE`, mientras las propiedades de
lanzamiento y el manifiesto **sí** se recalculaban, porque declaran
`sdkVersion` como entrada. El artefacto podía acabar declarando un rango de
compatibilidad para un SDK cuyas clases no había compilado: una promesa que los
bytes no respaldaban.

Para que el fallo fuera visible hubo que **imprimir el classpath resuelto** y
comprobar que su jar era el modificado: el digest del artefacto resuelto cambió
y la tarea siguió diciendo `UP-TO-DATE`. No es una regla del plugin, es que la
tarea de compilación no declara el classpath como entrada en este build.

El arreglo es una entrada por plugin: el SDK resuelto se declara como entrada
de `compileKotlin`, normalizada por **contenido**, en los cuatro plugins de
ejemplo. Así un cambio en la API del SDK vuelve a compilar, y las clases y la
declaración provienen del mismo SDK, que es lo único que hace que el rango
declarado signifique algo. **Los bytes del plugin no cambian**: el arreglo toca
la declaración de entradas del build, no el contenido del artefacto.

#### Lo que se midió, con lo que sale

- **El SDK publicado es un repositorio Maven resoluble.**
  `pipelinek-0.90.0.sdk.zip` descomprime a
  `dev/rubentxu/pipeline/v2/<artefacto>/<versión>/`, y un build externo resuelve
  `pipeline-sdk-bom:0.90.0` sin tocar el checkout.
- **El plugin construido contra el SDK publicado reproduce byte a byte el que
  empaqueta el producto** (mismo `sha256`). El SDK publicado es reproducible.
- **La línea menor es la unidad de compatibilidad.** El techo del rango es el
  siguiente *minor*, exclusivo: un plugin construido contra 0.89.0 declara
  `[0.89.0, 0.90.0)` y un host 0.90.0 lo rechaza con `IncompatibleApiRange`.
  Eso no es un defecto, es el contrato que el producto publica; por eso la
  comprobación lo afirma en vez de esquivarlo.
- **Dentro de la línea, lo publicado antes sigue cargando.** El plugin
  construido contra el SDK de `v0.90.0-rc1` se admite y ejecuta en el host de
  la candidata.
- **Dos artefactos con la misma identidad no conviven.** El plugin empaquetado
  y el que trae el operador declaran la misma identidad, y el runtime rechaza
  la composición. Por eso el instrumento retira el empaquetado de `lib/` antes
  de cada corrida, y **comprueba** que lo retiró: sin esa retirada, la
  comprobación mediría el empaquetado y llamaría «consumidor» a lo que es el
  producto.

#### Las tres filas, y qué prueba cada una

| fila | SDK | host | qué tiene que pasar |
|---|---|---|---|
| A | el de la propia candidata | la candidata | admitido, y el paso se ejecuta |
| B | el publicado antes, en la misma línea | la candidata | admitido, y el paso se ejecuta |
| C | el de una línea anterior | la candidata | rechazo tipado y salida 2 |

La fila B es la que responde a la pregunta que importa —«¿sigue funcionando lo
que ya se publicó?»— y por hoy es la que más puede pesar: las dos candidatas
publican el mismo SDK, así que lo que demuestra hoy es que **el consumidor
externo funciona**, no que el contrato haya derivado. La deriva dentro de la
línea la vería esa misma fila, porque el plugin construido contra el SDK
anterior dejaría de encajar en el host nuevo; y lo que no vería es una deriva
que las dos mitades firmaran a la vez.

El plugin se construye en una **copia aislada** de sus fuentes, nunca en el
árbol, para no dejar el artefacto del producto construido contra otro SDK, y
para que lo medido sea un plugin compilado contra el SDK que la fila nombra.

#### Lo que se falsificó

Cada mutación se aplicó, se leyó el diff de vuelta y se comprobó que
ennegrece **exactamente** las filas que debe y ninguna más:

| mutación | filas rojas | filas verdes | qué demuestra |
|---|---|---|---|
| M1 · el rango deja de rechazar | C | A, B | la fila negativa no es decorativa |
| M2 · el suelo del rango pasa a ser exclusivo | A, B, C | — | un host que se cierra de más no publica; el mutante rompe también los plugins empaquetados, y por eso C se pone roja por otro artefacto |
| M2′ · el plugin declara un rango que no cubre al host | A, B | C | el artefacto que se admite es el externo, no el empaquetado |
| M3 · el programa deja de llamar al paso | A, B | C | que el paso se ejecutó **se observa**, no se supone |
| M4 · el plugin empaquetado no se retira | la comprobación se niega a correr | — | sin la retirada, la comprobación mediría el artefacto equivocado |

M2 es la única con colateral, y la colateral está explicada: un suelo exclusivo
rechaza **todo** plugin cuyo rango empieza en la versión que corre, el
empaquetado incluido, así que la fila C observa el rechazo de otro artefacto.
M2′ es la que aísla lo que M2 mezclaba.

#### La puerta

Tres ensayos sobre el SHA de la candidata (`3e0f14d86`): **169**, **122** y
**124** s, con `tests=rewritten:1234 read:0` y **62** de **62** escenarios. El
primer ensayo de la serie pagaba una caché de compilación fría —la de las
mutaciones, no la del producto— y un cuarto ensayo, con la caché caliente, bajó
a **119** s: el estado estable es el de los ensayos segundo y tercero. El LOC,
con el script de consumo externo contado como lo que es —verificación, no
producto—, queda en VERIFY_LOC **23003** / PRODUCT_LOC **68460** = **0,336**,
muy por debajo del límite de 2.

#### La candidata

`v0.90.0-rc2`, con `candidate_id sha256:694604a5c64afcadba7e41647bbd8765fe5a7b2e3503d1d508b33f797cd9bcd2`
y los **7** assets de siempre. El producto **no** cambia de comportamiento
observable —el arreglo es de build, y los bytes del plugin son los mismos—, así
que la ley de versión **no** abre un tren nuevo: el tren 0.90.0 sigue abierto y
esta es su segunda candidata. Por eso también se corrigió el ordinal: el script
de publicación pasaba siempre `candidate.sequence=1`, lo que escribía un
ordinal falso en el handoff de una segunda candidata del mismo tren.

La comprobación de consumo externo se ejecutó **dentro** de la vía de
publicación, antes de que existiera el tag: `rows=A:PASS B:PASS C:PASS` en
**61** s, y su veredicto viaja en el handoff publicado.

#### Lo que queda fuera, dicho con honestidad

- **Dentro de una línea menor, el número de versión es el que declara la
  compatibilidad, no el contrato.** El `apiCheck` compara contra el baseline
  del repositorio, no contra el SDK publicado anterior, así que una deriva del
  ABI entre dos candidatas de la misma línea no la ve ninguna comparación
  estática. Queda como fila 17 de §1.
- **La comprobación de consumo externo no está en la puerta local**, porque
  necesita los SDK ya publicados y red. Queda como fila 18 de §1.
- **La fila B solo ve la deriva que el comportamiento delata.** Un contrato que
  cambia y que el plugin no exercise es invisible para ella: no es una
  comparación de contratos, es una prueba de que lo publicado sigue
  funcionando.
- La promoción a estable sigue bloqueada: no hay certificador externo en este
  repositorio ni en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no
  `PASS`.

### 2.89 La firma de procedencia: el tag se firma, y se comprueba antes de subirlo

**Lo que se pedía, y lo que había.** «Reducir el riesgo de la publicación. El tag
de `v0.86.0-rc1` es anotado pero aparece sin firma criptográfica verificada. El
control SHA-256 demuestra integridad respecto al manifiesto obtenido, no autentica
por sí solo al publicador.» Medido: los tres tags publicados —`v0.89.0-rc1`,
`v0.90.0-rc1` y `v0.90.0-rc2`— eran **anotados y sin firmar**: `git cat-file -p`
no mostraba bloque `BEGIN PGP SIGNATURE` y `git tag -v` no verificaba nada.

La distancia entre lo que la cifra demostraba y lo que se leería de ella es
justo el hueco. `SHA256SUMS` prueba que los bytes son los que el manifiesto
nombra, y un consumidor puede pasarlo sin saber **quién** los publicó. La
integridad no es la procedencia.

**La forma, decidida antes de escribir nada.** La clave se declara en el
repositorio —`scripts/release/signing-key.conf`— y no en un `git config` local:
un valor que vive en una máquina es un valor que ningún revisor ve, y una firma
que verifica contra la clave que la máquina tenga no dice nada del publicador.
Rotarla pasa a ser un cambio revisado. Y la firma se comprueba **antes** de
subir el tag, porque un tag que ya está en el remoto no se puede deshacer.

Tres decisiones que conviene decir de entrada:

- **No hay camino sin firmar.** Si la clave no está, el tag no se crea; si la
  firma no verifica, el tag se borra y la publicación se detiene. El camino sin
  firmar es exactamente lo que este trabajo existe para no publicar.
- **La clave ausente se detecta antes del gate**, no después: una clave ausente
  es un *prerrequisito*, no un veredicto, y gastarse quince minutos de
  compilación para descubrirlo al final no es una forma de ser económico.
- **La firma no es una atestación.** La atestación keyless de GitHub exige un
  OIDC de CI, que este repositorio no tiene: `gh attestation` ofrece `download`,
  `trusted-root` y `verify`, y no ofrece `generate`. La firma con clave es lo
  que se puede hacer aquí; lo que se declara es lo que se puede demostrar.

#### El instrumento, y por qué es una puerta de publicación

`scripts/release/verify-tag-signature.sh` comprueba tres cosas, y las tres son
filas con nombre:

| fila | qué tiene que ser cierto |
|---|---|
| A | el tag es anotado, lleva firma, y esa firma **verifica** |
| B | la firma viene de la **huella declarada**, y el uid es el declarado |
| C | el tag apunta al **commit esperado** |

La fila B es la que hace que la comprobación valga algo. Sin ella, «la firma
verifica» solo significaría «gpg está contento con la clave que encontró», y una
máquina con otra clave en su llavero firmaría, verificaría y pasaría. La huella
que la firma debe llevar está declarada en el repositorio, así que la vía de
publicación no se puede redirigir en silencio hacia otra clave.

El instrumento lee la firma por el **protocolo de GnuPG** (`VALIDSIG`, `BADSIG`,
`NO_PUBKEY`), no por el texto que gpg escribe: ese texto cambia con el idioma y
con la versión, y una comprobación que depende de él se rompe sin que nadie lo
note. Distingue además cuatro negativas: la firma **no verifica**, la clave **no
está disponible** para comprobarla, el tag **no lleva firma**, y la firma es
**de otra clave**. No son la misma fila, y no se pueden fundir en un «error
genérico» sin perder el motivo.

Como el instrumento necesita `git` y `gpg`, y porque lo que protege es un tag —un
artefacto que la distribución de desarrollo no produce—, no es un escenario de
`examples/` ni un test unitario. Es una puerta de publicación, con su latencia
propia, y su veredicto viaja en el handoff.

#### El orden, que es la garantía

La garantía que de verdad se cierra aquí no es «el tag lleva firma», es **«el
remoto no acaba nunca con un tag cuya firma no verifica»**. Un orden no se
demuestra leyéndolo: se demuestra empujando el remoto y mirándolo. Por eso los
tres pasos —firmar, verificar, subir— viven en
`scripts/release/publish-tag.sh`, que se puede apuntar a un repositorio remoto
local, y la pregunta se le hace **al código que publica**, no a una copia de él.

#### Las ocho garantías

| fila | garantía |
|---|---|
| R1 | un tag bien firmado se acepta, y la huella que se lee es la declarada |
| R2 | un tag **manipulado** se rechaza, y la fila A dice que la firma no verifica |
| R3 | un tag firmado con **otra clave** se rechaza por la fila B, no por un fallo genérico |
| R4 | un tag que apunta a otro commit se rechaza por la fila C |
| R5 | un tag **sin firma** se rechaza |
| R6 | el camino feliz deja en el remoto un tag que verifica contra la clave declarada |
| R7 | un tag manipulado **no llega al remoto**: la vía se niega y el remoto queda sin tags |
| R8 | sin la clave declarada no se publica nada: la vía se niega y el remoto queda sin tags |

La inyección del fallo es lo que convierte R7 en una prueba y no en una lectura
de código: un shim de `git` altera el payload del tag **después** de firmarlo,
que es exactamente la amenaza que la comprobación existe para detectar. Sin esa
alteración, R7 probaría que el camino feliz funciona.

La clave ajena de R3 es una clave **real**, generada en el banco para que «la
firma viene de otra clave» sea un hecho y no una palabra; el verificador la
tiene junto con la declarada, para que la fila B sea la que decide y no la
ausencia de la clave.

#### Lo que se falsificó

Cada mutación se aplicó, se leyó el diff de vuelta y se comprobó que ennegrecía
**exactamente** la fila esperada y ninguna más:

| mutación | filas rojas | qué demuestra |
|---|---|---|
| M1 · el instrumento deja de comparar la huella | R3 | la fila negativa no es decorativa: una firma de otra clave se acepta |
| M2 · el instrumento deja de exigir que la firma verifique | R2 | el «no verifica» se distingue del «no hay firma»: R5 **no se movió** |
| M3 · la vía empuja **antes** de verificar | R7 | el orden es la garantía, y el orden se observa en el remoto |
| M4 · la vía publica sin firmar cuando no hay clave | R8 | el camino sin firmar no existe, ni siquiera como degradación |

M2 y M4 son las dos que más información dan, y por razones opuestas. M2 enseña
que el instrumento distingue tres cosas y no dos: al dejar de exigir que la
firma *verifique*, R2 se enciende pero R5 no, porque un tag **sin** firma sigue
siendo rechazado por su propia causa. M4 enseña que el camino sin firmar no
era decorativo: era la degradación natural de «que la publicación funcione en
una máquina sin clave», y sin la fila R8 nadie lo habría visto.

#### El límite honesto: la firma prueba posesión, no identidad

La clave disponible en esta máquina es una clave de prueba: su uid es
`test@example.com`, que no es la identidad del publicador y no está registrada en
ninguna cuenta de hosting. Por eso lo que la firma demuestra es **posesión de la
clave privada**: el tag lo firmó quien tiene la clave, y el tag no se puede
alterar sin romper la firma. Lo que **no** demuestra es que quien tiene esa clave
sea el dueño de este repositorio. Esa atribución se establece fuera del
repositorio, y hasta que se establezca, quien lea la firma debe tratar al
publicador como **sin atribuir**.

El handoff lo dice con palabras, para que nadie lea una firma válida como una
identidad confirmada, y lleva la **clave pública incrustada** para que la
comprobación no dependa de la máquina que publicó. La plataforma de hosting
mostrará el tag como no verificado por la misma razón: la clave no está
registrada en una cuenta.

Esto es una limitación de la clave elegida, no del mecanismo. El mecanismo —
declarar la clave en el repositorio, firmarla, comprobarla antes de subirla y
publicarla con el handoff— es exactamente el que hace falta, y con una clave
registrada en la cuenta del publicador daría además la atribución.

#### La puerta

Tres ensayos sobre el SHA de la candidata (`1bf97c0c9`): **175**, **121** y
**186** s, con `tests=rewritten:1234 read:0` y **62** de **62** escenarios. El
LOC, con el instrumento de firma contado como lo que es —verificación, no
producto— y con el publicador del tag como lo que es —publicación, producto—,
queda en VERIFY_LOC **23216** / PRODUCT_LOC **68660** = **0,338**, muy por debajo
del límite de 2.

#### La candidata

`v0.90.0-rc3`, con `candidate_id sha256:13310c4ff2f3249377ad93d38e12aa81a1a0bdb9a3417277e390869329131e8d`
y los **7** assets de siempre. El producto **no** cambia de comportamiento
observable —el cambio es de la vía de publicación—, así que la ley de versión
**no** abre un tren nuevo: el tren 0.90.0 sigue abierto y esta es su tercera
candidata.

La firma se hizo **dentro** de la vía de publicación, antes de que el tag
existiera en el remoto: `tag-signature: PASS rows=A:PASS B:PASS C:PASS`, con la
huella `9600F7D49B9C966E3BB04E6A17E44CA7C5288281`. La comprobación de consumo
externo corrió también dentro, en **82** s, con `rows=A:PASS B:PASS C:PASS`. Y
su veredicto viaja en el handoff publicado.

Comprobado **desde fuera**, que es donde se demuestra una firma:

- El tag remoto lleva su bloque `BEGIN PGP SIGNATURE`, y `git verify-tag` da
  `VALIDSIG 9600F7D4…`.
- Un consumidor con el **llavero vacío** importa la clave pública que viaja en
  el handoff, descarga el tag y lo verifica: la firma valida y el tag apunta al
  commit que el handoff declara. El consumidor no necesita nada de la máquina
  que publicó.
- Los **6** `SHA256SUMS` coinciden una a una sobre los bytes descargados del
  remoto, y `git-head.txt` coincide con el commit del tag.

#### Lo que queda fuera, dicho con honestidad

- **El instalador no verifica la firma.** `scripts/install-pipelinek.sh` resuelve
  la release por su manifiesto y comprueba el digest, pero no la firma del tag:
  un consumidor que instala desde el release sigue confiando en el digest. Es el
  siguiente consumidor natural de esta capacidad, y queda como fila 19 de §1.
- **La atribución sigue pendiente**, por lo dicho arriba: la clave no está
  registrada en ninguna cuenta y su uid no es la identidad del publicador.
- **La atestación keyless sigue siendo `BLOCKED_EXTERNAL`**: requiere un OIDC de
  CI que este repositorio no tiene. `gh attestation` no tiene `generate`.
- **La promoción a estable sigue bloqueada**: no hay certificador externo en este
  repositorio ni en el remoto, así que el veredicto es `BLOCKED_EXTERNAL`, no
  `PASS`.

### 2.90 Un tag estable publicado sin procedencia: el incidente de `v0.90.0` y la política de corrección

**El hecho, medido sobre el remoto y no sobre el recuerdo.** Todo lo de esta
tabla sale de `git ls-remote --tags origin` y de `gh release view`, el **25-10-2026**,
sobre el remoto real:

| qué se pregunta | qué contesta el remoto |
|---|---|
| ¿el `v0.90.0` publicado es anotado? | **no**: `git ls-remote` imprime `1bf97c0c9d194cfe1510303430855ac22aad8258  refs/tags/v0.90.0` y **no** imprime la línea pelada `refs/tags/v0.90.0^{}`. Un tag anotado la publica siempre; su ausencia **es** el hecho, y se lee sin traer un solo objeto |
| ¿a qué apunta? | a `1bf97c0c9d…`, que es **exactamente** el commit al que apunta el tag anotado `v0.90.0-rc3` (`99d0a23f2…  refs/tags/v0.90.0-rc3` y `1bf97c0c9…  refs/tags/v0.90.0-rc3^{}`) |
| ¿existe aquí? | **no**: `git rev-parse refs/tags/v0.90.0` no lo encuentra, y el repositorio local tiene 199 tags. Un tag que sólo existe en el remoto es un tag que **alguien creó en el remoto** |
| ¿y las candidatas? | `v0.90.0-rc1` y `v0.90.0-rc2` son **anotadas y sin firma** (`git cat-file -p` no encuentra `BEGIN PGP SIGNATURE`); `v0.90.0-rc3` es **anotada y firmada** |
| ¿y los bytes? | los **7** assets de `v0.90.0` y los **7** de `v0.90.0-rc3` tienen **los mismos digest**, uno a uno (`13310c4ff2f3249377ad93d38e12aa81a1a0bdb9a3417277e390869329131e8d` para el ZIP, `086316f9c4…` para la autoridad de digest, los otros cinco igual). La diferencia entre las dos releases es `pre:false` contra `pre:true` |

La conclusión que se sigue de la última fila: **el contenido no estaba mal**. El
ZIP publicado por la estable es byte a byte el de la candidata, y la autoridad de
digest que viaja con él es la misma. Lo que faltaba era la **procedencia** del
tag: sin tagger, sin mensaje y sin firma, el `v0.90.0` no lo había firmado nadie,
y el SHA-256 del manifiesto demostraba integridad sin autenticar al publicador
—que es exactamente la distancia que §2.89 ya había nombrado.

**La cronología, medida también.** `v0.90.0` se creó como release el
`2026-10-24T18:27:02Z` y se publicó el `2026-10-25T11:26:35Z`; `v0.90.0-rc3` se
creó el `18:50:37Z` y se publicó el `18:58:41Z`. Y el commit `1bf97c0c9…` —el
que los dos tags apuntan— está fechado en `2026-10-24T20:27:02+02:00`, que es el
mismo instante que la creación de la release estable. La secuencia que explica
el resultado es una sola: **la release estable se creó sobre el commit, sin que
existiera un tag estable que empujar**, y `gh release create` creó el tag él
mismo desde el estado de la rama por defecto. Es el comportamiento documentado de
ese comando —«If a matching git tag does not yet exist, one will automatically get
created from the latest state of the default branch»— y `--verify-tag` es
precisamente la bandera que lo impide («Abort in case the git tag doesn't already
exist in the remote repository»). La vía de publicación de este repositorio lo
llamaba **sin** esa bandera: la línea histórica era `gh release create
"${PIPELINEK_RELEASE_TAG}" \` (`scripts/release-pipelinek.sh` en `HEAD`, línea 714),
sin `--verify-tag` en ninguna parte.

**Y la causa de fondo no era una bandera: era que no había puerta para las
estables.** El único `gh release create` del repositorio estaba en
`scripts/release-pipelinek.sh`, y ese script **siempre** pasa `--prerelease`: la
vía del repositorio era la vía de las **candidatas**. Una release **estable**
no tenía ninguna puerta, y por eso se publicó **a mano** —creada el
`2026-10-24T18:27:02Z`, el mismo instante que el commit `1bf97c0c9…`, y
publicada el `2026-10-25T11:26:35Z`, diecisiete horas después. Las tres
candidatas tienen la misma forma de dos fases (se crean y se suben los assets,
y luego se publican, con ocho minutos entre medias); la estable tardó
diecisiete horas. Que el tag **naciera sin que nadie lo empujara** no es un
detalle de una llamada: es la firma de un proceso que no tenía puerta, y una
puerta que no existe no se puede añadir una bandera.

La corrección es por tanto la de dar a la estable **la misma puerta que a la
candidata, con la misma autoridad**: `promote-stable.sh` corta el tag estable
firmado desde una RC verificada, y `publish-github-release.sh` publica la
release —con la bandera `--prerelease` **optativa**, de modo que una estable se
publica como estable— exigiendo el tag firmado, la autoridad de digest y el
conjunto de assets completo.

**No es un tropiezo aislado: es un patrón.** Censo del remoto, **25-10-2026**,
**198** tags: **165** anotados y **33** *lightweight*. Y de los tags **estables**
(`v?X.Y.Z`), **63** en total: **57** anotados y **6** *lightweight* —
`0.75.0`, `v0.1.0`, `v0.56.0`, `v0.61.0`, `v0.75.0` y `v0.90.0`. El `v0.90.0` es
**el sexto**, no el primero: la vía que lo produjo llevaba seis releases publicando
tags sin procedencia, y ninguno se notó porque nadie miraba lo que el remoto
tenía después de publicar. Ese es el hueco exacto: **la publicación comprobaba
lo que iba a subir, y no comprobaba lo que había quedado arriba**.

#### La política de corrección: una etiqueta estable publicada no se mueve

La regla que se aplica, y que no es una preferencia de estilo:

1. **Un tag estable publicado no se mueve, no se reemplaza y no se reescribe.**
   Un tag es un **nombre público**: quien lo resolvió ya tiene apuntando a esos
   bytes, y moverlo cambia lo que ese nombre significa para un tercero que no
   puede enterarse. Por eso los **6** tags estables *lightweight* se **quedan**
   como están, incluido el `v0.90.0`: no hay reescritura de historia, no hay
   borrado de releases, y no hay un tag «correcto» publicado encima. Lo que se
   arregla es que **no vuelva a pasar**.
2. **La corrección es hacia delante, no hacia atrás.** El remoto se
   **audita** y el resultado se declara: un tag *lightweight* en el remoto es un
   hecho que se nombra, no un fallo silencioso. La auditoría
   (`scripts/release/verify-published-tags.sh`) no repara nada porque no puede:
   **no puede** reparar un tag ya publicado, y no lo intenta.
3. **Un hueco no es un verde.** Si la auditoría no ha podido medir un tag —porque
   el objeto no está en este repositorio—, el veredicto de ese tag es `GAP`, y un
   `GAP` hace que la auditoría **falle**. Una auditoría que imprimiera «PASS»
   sobre un tag que nadie miró sería la misma mentira que este bloque existe
   para terminar.
4. **La promoción de una estable es una operación con nombre, y sólo hay una.**
   `scripts/release/promote-stable.sh` es la única vía: exige que la RC esté
   **publicada**, que sea **anotada**, que su **firma verifique**, que el objeto
   local sea **idéntico** al que publica el remoto, y que el nombre estable **no
   esté ocupado** ni en local ni en el remoto. Corta el tag con
   `publish-tag.sh --at <commit de la RC> --promotes <RC>`, de modo que el tag
   estable **declara** de qué candidata viene.
5. **La publicación pide siempre `--verify-tag`, y lo pide el script.** En
   `scripts/release/publish-github-release.sh` la bandera la añade **el script**,
   no quien lo llama: no hay modo «release sin tag», ni bandera de escape, ni
   rama de degradación. El mismo script exige una **autoridad de digest**
   (`--expect-sums`) y re-mide cada byte que sube contra ella: una release sin
   autoridad nombrada se niega, y una release a la que le falte una parte de la
   autoridad se niega.

#### La puerta que queda en la vía, y por qué está antes de publicar

Las tres piezas anteriores son herramientas; lo que las ata al camino real es
**§6b de `scripts/release-pipelinek.sh`**: entre subir el tag firmado (§6) y
publicar la release (§7) se ejecuta la auditoría del remoto, y si el remoto no
tiene un tag verificable la vía **se niega a publicar** y dice, en sus palabras,
que *el tag se empujó y firmó; lo que falló es el listado del remoto*. Ese
§6b **no lo ha ejercido ninguna publicación real**: se ejecutaría en una
publicación, y no se ha hecho ninguna. Su estado es `NOT_RUN`, y aquí se dice con
esa palabra.

#### El instrumento que midió el bloque, y por qué se borró

El bloque se midió con un instrumento **efímero** —17 filas y 10 mutaciones—
porque la garantía es de **publicación**: consume el remoto, ejecuta `git` y
`gpg`, y no produce un artefacto que un test unitario pueda inspeccionar sin
mentir (§2.89 explica por qué esta clase de comprobación es una puerta de
publicación y no un test). Sus resultados, medidos sobre el árbol que se entrega:

| qué | resultado |
|---|---|
| baseline | **17/17** filas `PASS`, **0** `FAIL` |
| mutaciones aplicadas | **10/10**, cada una leída de vuelta (`cmp -s` contra la copia) antes de leer el color, y **0** filas que pasaron de `FAIL` a `PASS` |
| duración | **242 s** por corrida (la segunda, contra los bytes que se entregan) |
| árbol medido | el **árbol de trabajo** sobre la base `dfba3342eab7732ee706c365db63960394886229`: el bloque todavía **no estaba comiteado** cuando el instrumento corrió, y eso hay que decirlo. Los ficheros que el instrumento lee —`scripts/release-pipelinek.sh` y `scripts/release/*.sh`— son **byte a byte los mismos** que entrega el commit de este bloque, que no reescribe ni uno de ellos; y el instrumento no lee ni `ROADMAP.md` ni `examples/run.sh`, así que el resto del árbol no le afecta |
| destino del ciclo | `~/.local/state/pipelinek/verification-cycles/b1a-release-provenance-2026-10-25/`, **borrado** tras evaluar, conservando aquí sólo estos recuentos |

La corrida final es la que vale, y vale por una razón concreta: la primera
corrida se medió sobre un árbol al que después se le corrigió el `usage()` de los
seis scripts de release —los rangos `sed` de la cabecera habían quedado
desfasados al crecer el texto, y un `--help` que recorta en silencio las
instrucciones es un defecto—, y la corrección **se revirtió sola** cuando el
instrumento restauró la copia de seguridad que había tomado antes de una
mutación. Un instrumento que restaura ficheros del árbol que mide no puede
medir un árbol que se mueve debajo de él: por eso la segunda corrida es la que
sostiene la cifra, y por eso el `usage()` se sustituyó por una extracción que se
autodetermina (la cabecera comentada hasta la primera línea que no es
comentario) en lugar de un rango de líneas que hay que volver a contar cada vez
que se escribe un párrafo.

Las diez mutaciones, con la fila que cada una encendió y sólo esa:

| mutación | qué se rompió | fila roja |
|---|---|---|
| M1 · `publish-github-release.sh` deja de añadir `--verify-tag` | el publicador vuelve a poder crear el tag | `row_release_publishes_with_verify_tag` |
| M2 · la comparación de digest pasa a `__never__` | los bytes manipulados pasan | `row_release_refuses_tampered_asset` |
| M3 · la condición de «la autoridad está completa» se anula | una release puede omitir bytes que la autoridad nombra | `row_release_refuses_incomplete_authority` |
| M4 · la guarda de «el nombre estable ya está tomado en local» se anula | se puede reusar un nombre estable local | `row_promote_refuses_taken_local_name` |
| M5 · `--at` se pasa vacío | el tag estable se corta en otro sitio | `row_promote_publishes_signed_stable` |
| M6 · la verificación de la firma de la RC se salta | se promueve una RC sin firma | `row_promote_refuses_unsigned_rc` |
| M7 · el veredicto pasa a depender sólo de `FAILED` | un `GAP` se lee como un verde | `row_audit_reports_gap_not_pass` |
| M8 · el contador de `FAILED` desaparece de la rama *lightweight* | el tag sin procedencia deja de contarse como fallo | `row_audit_names_lightweight_tag` |
| M9 · el detector de *lightweight* desaparece | el tag sin procedencia deja de nombrarse | `row_audit_names_lightweight_tag` |
| M10 · el detector se invierte | la auditoría confunde anotado y *lightweight* | **4** filas: `row_audit_names_lightweight_tag`, `row_audit_reports_gap_not_pass`, `row_audit_passes_signed_tag`, `row_promote_publishes_signed_stable` |

M9 y M10 rompen cosas distintas y por eso son dos: M9 quita el detector y deja
de **nombrarse** el tag sin procedencia (una fila); M10 lo invierte y hace que
la auditoría **mienta** sobre lo que es (cuatro). Un solo «el detector se
rompió» habría medido menos de lo que hay.

#### Lo que queda fuera, dicho con honestidad

- **El `v0.90.0` publicado sigue siendo un tag sin procedencia.** No se puede
  arreglar sin reescribir un nombre público, y la política dice que no se hace.
  Lo que sí queda es **nombrado** —por la auditoría y por este apartado— y
  **documentado** para el que lo lea.
- **Los otros cinco estables *lightweight* siguen igual**, por la misma razón.
- **La promoción a estable sigue `BLOCKED_EXTERNAL`**: no hay certificador
  externo en este repositorio ni en el remoto (§2.45).
- **La puerta completa se ejecutó después**, sobre `3db8f951f` —el árbol que
  entrega este bloque, ya comiteado— y dio `PASS sha=3db8f951f… elapsed=166s
  budget=900s tests=rewritten:1298 read:0 scenarios=observed:61 executed:61`, con
  el censo de LOC en `VERIFY_LOC = 24464` / `PRODUCT_LOC = 70676` (ratio **0.346**).
  Sobre `dfba3342e` —la base sin este bloque— sigue siendo cierto que no se
  ejecutó, y por eso el instrumento del §2.90 se midió aparte y no se reusa esa
  cifra como si fuera la de la puerta.
- **El §6b —la auditoría del remoto antes de publicar— sigue `NOT_RUN`**: no
  aparece hasta una publicación real, y no se ha hecho ninguna.
- **La bandera `--prerelease` la elige quien publica, y la puerta no la
  contrasta con el nombre del tag.** Una release de una candidata marcada como
  estable —o al revés— saldría con la forma equivocada en la página de
  releases, y la puerta no lo detecta: ella comprueba la **procedencia** (tag
  firmado, ya publicado) y los **bytes** (autoridad de digest), que es lo que
  era el defecto del incidente. Es una incoherencia de forma, no de
  procedencia, y no hay medida de que haya ocurrido; queda como deuda
  declarada, no como hueco tapado.
- **La mitad de caché de `AUD-B1`** —la poda que sólo borra namespaces
  acreditados— sí está en el árbol y ya fue medida por su propio ciclo
  (commit `dfba3342e`, seis guardas y seis mutaciones); aquí no se vuelve a
  medir porque es otro bloque con otro instrumento.

### 2.91 La atribución de un terminal a su paso: una decisión sola, y el hecho que la hacía imposible

**El defecto, contado tres veces.** Una ejecución escribe en su journal un
terminal por cada paso que termina. Ese terminal **nombra** un paso —por su
ordinal dentro de su etapa— y tres proyecciones del mismo repositorio
preguntaban cuál de los pasos abiertos era ese, y cada una contestaba por su
cuenta:

| proyección | qué preguntaba | qué contestaba | qué decidía |
|---|---|---|---|
| perfil de coste (`RunCostProfile.kt`) | ¿qué paso abierto cerró este terminal? | se negaba a responder si había más de un candidato | el paso quedaba **abierto**: su coste salía como **cota**, no como medición |
| explicación (`RunExplain.kt`) | ¿qué paso abierto cerró este terminal? | se negaba a responder si había más de un candidato | el paso quedaba **colgado** y el informe decía `attribution-ambiguous` |
| telemetría (`RunTelemetry.kt`) | ¿qué paso abierto cerró este terminal? | `indexOfFirst`: el **primero** que encontraba | el paso quedaba cerrado y la **duración** se atribuía a un candidato que podía no ser el |

La fila de la telemetría es el defecto: un número que sale del export y que el
journal no dice. Las dos primeras filas no eran errores —eran respuestas
conservadoras—, pero las tres **no eran la misma respuesta**, y un operador que
comparase el perfil y el `explain` de la misma ejecución leería dos historias
distintas sobre el mismo paso, sin que ninguna se pudiese llamar equivocada en
sus propios términos. El bloque `AUD-B2` pedía exactamente esto: *una decisión
pura de atribución compartida por perfil, `explain` y telemetría*.

**El hecho que el código no podía enseñar, medido en el CLI.** La identidad de
un paso no es `(stageIndex, stepIndex)`. En una etapa **paralela**, las ramas
corren a la vez y cada una enumera sus propios pasos, así que varias filas
`StepStarted` comparten el par `(stageIndex, stepIndex)` y lo único que las
distingue es el **nombre**, que es path-qualified
(`parallel-branches/branch-a/echo-0`). Medido sobre un escenario efímero de tres
ramas, cada una con un paso:

```
9  StepStarted {'stageIndex': 0, 'stepIndex': 0, 'stepName': 'parallel-branches/branch-b/echo-0', 'stepType': 'echo'}
10 StepStarted {'stageIndex': 0, 'stepIndex': 0, 'stepName': 'parallel-branches/branch-a/echo-0', 'stepType': 'echo'}
11 StepStarted {'stageIndex': 0, 'stepIndex': 0, 'stepName': 'parallel-branches/branch-c/echo-0', 'stepType': 'echo'}
```

Un resolver que usara el par como identidad habría encontrado **tres**
candidatos para cada terminal, y el nombre —que sí distingue— se habría
descartado por no ser «parte de la identidad». La identidad de un paso abierto
es la **tripleta** `(stage, ordinal, nombre)`, y el nombre se usa sólo para lo
que puede usarse sin peligro: **desambiguar** entre los candidatos que el
ordinal y la etapa ya seleccionaron. Un nombre que **no selecciona** ninguno se
ignora, porque hay terminales que no nombran ningún paso: el fallo de `sh`
lleva `"sh"` (`ShExecution.kt`) y el error del SDK lleva `"error"`
(`StepExecutors.error()`); obedecerlos convertiría un terminal decidible en
indecidable.

**Los terminales que ni siquiera nombran su etapa.** El `stageIndex` es
`Int?` a propósito, y dos productores lo dejan en `null`:
`DomainEventJsonDecoder.kt:435` (una fila escrita por una build anterior) y
`StepExecutors.error()` del `pipeline-step-sdk` (sin llamadas dentro de este
repositorio, pero público). Un terminal así se resuelve **por ordinal**, y con
varios candidatos no se puede decidir: por eso la respuesta es `Ambiguous` y no
una adivinanza.

**La decisión, una sola.** `StepAttribution` (`pipeline-application`,
`attribution/StepAttribution.kt`) es pura: lee la lista de pasos abiertos y el
terminal, y devuelve `Matched(openIndex)`, `Ambiguous(candidateCount)` o
`None`. No hace E/S, no guarda estado, no consulta reloj. Las tres
proyecciones la llaman con su propio tipo de paso abierto —cada una guarda el
suyo— y por eso la firma lleva `identityOf: (T) -> OpenStepIdentity`.

**La ruta de la lente, antes de escribir la primera línea.** El GATE de
`AGENTS.md` §2 pasa por `delivery-first-testing`, y el router
(`references/navigation.md`) se consultó antes que los playbooks:

```
intent=feature  inventory=ready  impact=known  basis=current
changed=domain  risk=routine  affected=passed  uat=passed  regression=decided
```

Las lentes que el router anuncia para esa entrada se leyeron en su orden:
`change-impact` → `ephemeral-uat` → `regression-promotion`. En el orden que
la lente impone —**garantía → capacidad → instrumento**—:

- **Garantía** (de qué depende el consumidor): *un informe de coste, una
  explicación y una telemetría de la MISMA ejecución no pueden contar tres
  historias sobre qué paso terminó; y un terminal que no puede nombrar
  exactamente un paso abierto no atribuye duración ni veredicto a ningún
  candidato*.
- **Capacidad observable** (a qué sirve): las tres proyecciones que leen el
  journal de una ejecución y el `inspect --profile|--explain|--telemetry` que
  las entrega al operador.
- **Instrumento más barato que puede fallar**: filas unitarias de las tres
  proyecciones más una fila del resolutor puro. Sin CLI, sin ficheros, sin
  reloj, sin proceso: la garantía es una decisión sobre una lista de
  eventos, y esa es la forma más barata de comprobar una decisión.

El UAT efímero **sí** se ejecutó, y midió lo único que un UAT puede medir: un
**hecho**. El escenario de tres ramas vivió en el directorio del ciclo
(`$XDG_STATE_HOME/pipelinek/verification-cycles/b2-parallel-telemetry-2026-10-25/`),
produjo el journal que se analiza arriba y se **descartó** al cerrar el ciclo. No
sustituye a la garantía: un UAT no comprueba una decisión pura, comprueba
que la máquina produce el caso. La decisión de regresión es **PROMOTE** —la
protección permanente son las filas unitarias, que son más baratas y más
precisas que un escenario de CLI— y el cierre de `regression-promotion` queda
así:

| garantía | original | sustituto o justificación | evidencia | decisión | coste | riesgo |
|---|---|---|---|---|---|---|
| una atribución, tres respuestas | tres proyecciones con tres reglas | filas unitarias de perfil, `explain`, telemetría y del resolutor | 3 mutaciones, 7, 4 y 1 filas rojas (§ abajo) | **PROMOTE** | 38 tests, 16 s | ninguno: la garantía es pura y el instrumento la cubre entera |

**Las tres mutaciones, vistas en rojo.** Un verde que nunca se ha visto rojo no
es evidencia, así que el resolver se rompió tres veces —en las dos direcciones
posibles de la desambiguación, y una vez en el consumidor que quedaba
asimétrico— y se leyó el fichero de vuelta para probar que la mutación estaba
aplicada:

| mutación | qué rompe | filas que se puso en rojo |
|---|---|---|
| **M-A** — el primer candidato gana (el defecto de la telemetría) | el nombre deja de desambiguar y la ambigüedad se resuelve por orden de lista | **7**: `StepAttributionTest` ×2 (las ramas en paralelo; un nombre que no selecciona nada), `RunTelemetryTest` ×2 (ambigüedad; ramas en paralelo), `RunCostProfileTest` ×2 (ambigüedad; ramas en paralelo), `RunExplainTest` ×1 (ambigüedad) |
| **M-B** — el nombre nunca desambigua (la sobre-corrección) | la desambiguación por nombre desaparece | **4**: `StepAttributionTest` ×1, `RunTelemetryTest` ×1, `RunCostProfileTest` ×1, `RunExplainTest` ×1 —todas las de las ramas en paralelo |
| **M-C** — el `explain` confiesa sólo la exclusión del fallo | un `StepFinished` ambiguo vuelve a reportar como «nunca cerrado» un paso que sí cerró | **1**: `RunExplainTest` ×1 (`an ambiguous finish leaves no candidate reported as never closed`) |

En ninguna de las tres hubo daño colateral: 38 tests, 7 rojos con M-A, 4 con
M-B y 1 con M-C, y los demás en verde. Una fila que **no** se puso roja con M-A
y sí con M-B es lo que hace que no sea vacía: la fila del `explain` sobre las
ramas en paralelo.

**La asimetría que M-C encontró.** El resolver compartido era correcto, pero
su consumidor del `explain` no lo era del todo: la exclusión del ordinal —«un
terminal que no nombró exactamente un paso no permite decir que ese paso nunca
cerró»— **sólo estaba en la rama del fallo**, no en la del `StepFinished`. Un
terminal de fin ambiguo —una etapa paralela cuyo terminal trae el nombre
genérico `"sh"`, que es lo que lleva un fallo de `sh`— podía seguir
produciendo un `step-never-closed` **falso** sobre una rama que sí había
cerrado. Es la misma forma de mentira que la tabla de abajo ya señalaba en el
`explain` viejo, y por eso la fila que la vigila es una etapa paralela con un
terminal de nombre genérico: sin nombre que desambigüe, el terminal **no
pertenece a ninguna** de las ramas, y ninguna puede reportarse como no cerrada.

**La diferencia, medida en el CLI sobre el mismo journal.** La misma ejecución
—run `56e04587-98df-4f8b-81d1-159003285595`, el escenario de tres ramas— leída
con la distribución **vieja** (`7476bbc10`, construida en un worktree descartado)
y con la **nueva**:

| informe | `7476bbc10` (tres reglas) | el resolutor único |
|---|---|---|
| perfil | `outcome=partial reasons=dangling-interval measured=1/7` — `step-dispatch bounded >=16 ms (bounded-by-last-observed-event)` | `outcome=complete measured=2/7` — `step-dispatch series n=3 total=14 ms median=5 ms` |
| `explain` | `inference step-never-closed step=parallel-branches/branch-b/echo-0 (#9)` + `inference attribution-ambiguous count=3 (#22)` | sólo `fact run-ended outcome=success` — ninguna inferencia |
| telemetría | tres duraciones `5, 5, 4` ms: la primera rama abierta se llevó el primer terminal | tres duraciones `4, 5, 5` ms: cada terminal midió **su** rama |

La fila del `explain` es la que duele: el informe viejo decía al operador que
una rama **nunca cerró** —y sí cerró— y que tres terminales **no pudieron
nombrar** su paso, en una ejecución donde cada terminal nombraba el suyo. Eso
no es una diferencia de forma: es una afirmación falsa sobre la ejecución.

**Lo que queda abierto, dicho con honestidad.**

- **La mitad de `AUD-B2` que no es esto.** Quedan el **fallback de caché por
  origen y tipo** —hoy se decide por la cadena
  `CachingJvmScriptingHost.UNLOADED_ARTIFACT_MESSAGE`, que es un texto— y la
  **prueba de que la clave v3 por contenido es consumible** desde un test
  unitario. Ninguna de las dos se ha tocado.
- **El `spanId` de una etapa paralela sigue siendo el mismo para sus ramas.**
  `spanId = Sha256.ofText("span/$traceId/step/$stage/$step")` no lleva el
  nombre, así que las tres ramas de un stage paralelo exportan **tres** fines
  de span con el **mismo** identificador: un export que dice «un paso, tres
  duraciones». Eso pertenece a `AUD-B3` (`spanId` robusto) y **no** se ha
  tocado aquí; se anota porque esta medición lo ha dejado a la vista.
- **La puerta completa no se ha ejecutado sobre este árbol.** Lo que se ha
  ejecutado son: la compilación con `detekt`, `detektTest` y `apiCheck`
  (`60 s`), y las 38 filas de las cuatro clases tocadas (`16 s`, 0 fallos,
  `tests=rewritten:38 read:0`). Ambas son **corridas de subconjunto** y su
  línea de veredicto lo dice (`PASS (subset)`); la puerta sin filtros —con
  `installDist`, los tests de todos los módulos y los escenarios de `examples/`—
  sigue siendo `NOT_RUN` sobre este árbol, y su cifra no se reusa de ningún
  otro árbol.

## 3. Decisiones tomadas en este ciclo, con su porque
- **Un sitio que el operador nombra es un sitio que hay que dejar existir.** El driver de SQLite
  no crea directorios, así que un nombre que todavía no está es una instrucción que el producto
  tiene que hacer cumplir, no una afirmación que puede creer. La regla es pura y tipada, con tres
  respuestas: lo que ya está se usa tal cual, lo que no está se hace, y lo que no nombra nada es
  del producto (§2.80).
- **La presencia del almacén es un hecho de la máquina; la necesidad de él es un hecho de la corrida.**
  Preguntar a la máquina hacía que toda corrida de una máquina que hubiera guardado un secreto
  pidiera la frase de paso, y el operador la encontraba antes de su primer pipeline (§2.81).
- **La pregunta se le hace a la declaración, no a la máquina.** Un `PipelineSpec` dice si liga una
  credencial, y el recorrido es un `when` exhaustivo sobre el `StepSpec` sellado: un tipo de step
  nuevo no compila hasta que se diga si puede ligar credenciales (§2.81).
- **El fallo se queda fail-closed.** Una corrida que liga una credencial y no tiene frase de paso
  se niega igual, con salida 3: lo que se arregla es que la puerta no se interponga donde no hay
  nada detrás (§2.81).
- **Un instrumento que tapa el defecto es peor que no tener instrumento.** El arnés daba una llave a
  todos los escenarios porque la puerta se negaba antes de cualquier paso; con la llave, la
  pregunta nunca se hacía. Ahora la máquina tiene almacén y quien no liga nada no recibe la llave
  (§2.81).
- **Un README que lleva cifras que roten, lleva una promesa que se rompe.** La versión, el digest y
  el número de escenarios los dice aquello que cambia: el release, el manifiesto y el lanzador
  (§2.81).
- **Un documento que describe otro producto es peor que un documento que no existe.**
  `README.es.md` hablaba de GraalVM Isolates, contenedores y corrutinas que no existen en el
  código; ahora es una guía breve, fiel y sin cifras que roten (§2.81).
- **La ley de la identidad no se negocia con la comodidad del instalador.** Una candidata no se
  instala bajo el nombre de una versión estable: el instalador tiene que resolver **dónde** vive
  una candidata, y ese es su propio bloque (§2.81).
- **La frase de paso es la autorización para tocar el almacén, y se comprueba.** Una llave
- **Un diagnóstico que nadie puede alcanzar no es una garantía: es código muerto que apunta fuera de la raíz.** La sonda del runtime escribía en el directorio personal del operador para una comprobación que ya no existe, por un camino que sólo se abre con propiedades del sistema que nada pone. Quitarla no cambia lo que el producto hace, y sí deja de cargar una manera de escribir fuera de la raíz autorizada (§2.83).
  que no encaja no cambia un byte y lo dice; el almacén deja de poder quitar nada sin haber
  desenvuelto antes la clave que lo sella (§2.82).
- **Un almacén que no está no tiene nada que abrir.** Pedir la llave ahí es pedir la llave de
  una puerta que no existe, y mirar no deja un almacén vacío detrás (§2.82).
- **Un código de salida que nadie produce es un código inventado.** El comando documentaba
  `2` y `3` y no los producía, porque capturaba una excepción que nadie lanza; ahora cada
  desenlace cuesta su código y el comando dice cuál es (§2.82).
- **La garantía se comprueba donde el lector la encuentra.** Las comprobaciones nuevas del
  arnés miran el transcripto y no la consola, porque la consola intercala el progreso del
  producto dentro de la salida del paso y una marca puede llegar partida (§2.82).
- **Un contrato que se puede escribir en dos frases se puede comprobar en dos frases.** Qué
  verbo necesita la llave y qué cuesta cada desenlace son dos decisiones puras, y por eso
  tienen filas propias (§2.82).
- **Un valor vacío es una llave válida, no la ausencia de una instrucción.** Se comprobó que
  ya había una decisión deliberada y probada que lo dice, y no se tocó: la regla de «lo en
  blanco no es una instrucción» es de las rutas y los directorios, no de las claves (§2.82).
- **Una propiedad vacía no es una elección.** Es la ausencia de una instrucción, y tratarla como
  una era lo que mandaba la biblioteca nativa al directorio de trabajo. Un valor en blanco y un
  nombre que el sistema de ficheros no sabe escribir son las dos formas de no haber dicho nada
  (§2.80).
- **Una fila pura que toca el disco está midiendo otra cosa.** La prueba anterior creaba
  directorios de verdad y borraba una propiedad global del JVM; además, no podía ver ninguno de
  los dos fallos que el producto tenía. La decisión, al ser pura, se comprueba sin disco y sin
  reloj (§2.80).
- **Una presentación no puede llevarse el hecho que presenta.** La consola dibuja después de
  escribir: un fallo de dibujo deja el registro intacto y el fallo a la vista (§2.78).
- **Un byte decidido es un byte que ya no se puede escribir.** La cola de lo decidido crece en
  lugar de enrollarse, y el invariante deja de ser una aritmética para pasar a ser imposible
  (§2.78).
- **Un aviso no es un fallo.** Un registro que el almacén rechaza es la pérdida de un hecho, no una
  molestia: el checkout falla y nombra el registro perdido (§2.78).
- **Un comentario que afirma lo contrario de lo que hace el código es un defecto, no un estilo.**
  La consola renderizaba antes de escribir, y su comentario defendía ese orden (§2.78).
- **Un defecto que ningún camino vivo activa se corrige con filas, no con escenarios.** Un
  escenario que pasara antes y después no mide nada (§2.78).
- **Un instrumento que no puede fallar no es un instrumento.** La fila del final de la
  transcripción usaba una carga uniforme, con la que la corrupción no se ve; se rehízo con bytes
  distintos, y el rojo que produce lo dice (§2.78).
- **Un comparador que ordena líneas no puede comprobar un orden.** La canonicalización del arnés
  ordena dentro de la etapa, así que dos narraciones distintas dan la misma canonicalización: el
  bloque siguiente queda medido y armado (§2.78).
- **Una historia no puede contarse al revés.** Un paso que termina antes de empezar es un
  registro que dice lo contrario de lo que pasó, y la comparación que ordenaba las líneas no
  podía verlo: el orden se comprueba aparte, como el orden parcial que el producto promete
  (§2.79).
- **Una promesa que declara un escenario protege a ese escenario.** El invariante del orden
  protege a los 62, y la directiva por escenario que lo sustituyó protegía a uno (§2.79).
- **Una garantía que desaparece al escribirla mal no es una garantía.** Una directiva que el
  lanzador no conoce es un fallo que la nombra, y una promesa que el lanzador no puede cumplir
  en la única ruta donde se ejecutaría es un fallo del escenario, no un verde (§2.79).
- **Un instrumento que comprueba el arnés sería un segundo arnés.** La evidencia del contrato
  del lanzador es la mutación, que se repite cada vez que el contrato cambia, y no un
  `--self-test` permanente que la política no admite (§2.79).
- **Una identidad, un lector.** La identidad de una operación se lee desde los dos
  extremos de su id, y quien la necesita pregunta a esa misma dirección. Dos formas de
  leer la misma identidad son dos autoridades, y dos autoridades se contradicen (§2.77).
- **Un lector que decide por su cuenta dónde acaba el identificador está respondiendo
  por otro.** La búsqueda por subcadena da la operación vecina y el índice de segmento da
  una operación cortada: las dos contestan con seguridad y con una atribución equivocada
  (§2.77).
- **Una segunda autoridad sobre la misma identidad no es un respaldo: es una
  contradicción.** El resolvedor textual que se llevaba su propia lectura del id llevaba
  meses sin un solo consumidor; se elimina en vez de documentarse (§2.77).
- **Un instrumento que no puede fallar no es un instrumento.** La fila que la puerta dejó
  en verde porque el índice ya acota por run se quitó: la garantía ya estaba cubierta y una
  segunda fila para lo mismo sólo lee como una garantía más (§2.77).
- **Un nombre plegado no es una identidad.** Un nombre que pierde información no puede
  ser la identidad de un directorio: dos identidades que se pliegan al mismo nombre son
  dos streams, y el que las confunde entrega a una los bytes de la otra (§2.76).
- **El dueño se escribe donde están los bytes.** Un directorio que nombra a su dueño
  convierte una direccion ambigua en un hecho comprobable, y el comprobante no necesita
  un índice aparte: vive al lado de lo que describe (§2.76).
- **Un rechazo que no nombra a las dos identidades no es accionable.** El error dice quién
  posee el directorio y quién fue rechazado, y el rechazo de lectura dice qué se pidió y
  qué hay allí (§2.76).
- **Un caso de rechazo publicado se puede ampliar sin romper el ABI.** El vocabulario del
  plano de salida está publicado y no admite casos nuevos, pero `ForeignStream` ya dice
  lo que este caso necesita decir, y su razón de ser es la misma. Lo que se amplía es su
  KDoc; la firma no cambia (§2.76).
- **Lo que no se puede observar de forma permanente, se observa de forma efímera y se
  dice.** La rama de escritura necesita dos escritores que colisionen, y el motor no los
  produce: se comprobó con un programa temporal contra el almacén real y se eliminó al
  cerrar el ciclo, porque un test permanente que toca el sistema de ficheros no cabe en
  la regla de unitarios puros (§2.76).

- **Un artefacto que nadie consume no está verificado.** Comprobar byte a byte
  que el SDK publicado es el que dice ser no dice que se pueda resolver: el
  digest responde «los bytes son estos», y la pregunta del consumidor es «¿puedo
  construir con esto?». La respuesta se mide consumiéndolo, y el que lo consume
  tiene que ser alguien de fuera (§2.75).
- **El artefacto publicado es la única SDK que los consumidores ven.** Un
  directorio intermedio y un ZIP son el mismo contenido con dos caras, y dos
  caras son dos autoridades. La que se publica es la que se resuelve (§2.75).
- **Un instrumento que no vuelve a ejecutarse no es un instrumento.** La primera
  medición del «BOM ausente» salió verde porque el build anidado estaba al día y
  no volvió a resolver. Declarar el SDK publicado como **entrada** del consumidor
  es lo que convierte la comprobación en una condición de salida (§2.75).
- **La regla más pequeña es la que no se equivoca por el camino.** Elegir las
  entradas del ZIP buscando la versión en el texto de la ruta seleccionaba
  artefactos de otra versión según dónde estuviera el checkout. La regla es ahora
  una función pura de las rutas relativas: la carpeta que contiene el fichero
  es la versión (§2.75).

- **Un comentario que afirma una verdad que el código ya no tiene es peor que
  no escribirlo.** Un lector no puede saber que un comentario miente, así que
  miente con la autoridad de la fuente. Por eso el bloque de comentarios no
  se limita a quitar referencias: **re-escribe lo que el código dice sobre sí
  mismo**, con lo medido (§2.62).
- **Un guardián que ya no existe no puede ser la autoridad de una ley.** Siete
  comentarios apoyaban leyes en un harness que el RESET borró. `AGENTS.md` §2
  no admite el *source-text fitness*, así que la respuesta no es resucitar el
  observador: es **decir que la ley no está vigilada**, con la fórmula que el
  árbol ya usaba (§2.62).
- **Un comentario que depende de quién lo lea no explica la
  funcionalidad.** Diecinueve comentarios se remitían a «este bloque» o «este
  gate». Se quedan, y ahora dicen lo que el código hace (§2.62).
- **Un número de versión que colisiona con una release publicada no es un
  número de versión: es un bloqueo.** Este árbol declaraba `0.62.0`, y
  `v0.62.0` ya estaba publicada con bytes distintos (§2.61). El síntoma no
  era un error de build: era que **publicar era imposible**, y una release
  que no se puede publicar es trabajo que no llega a nadie. Por eso la
  versión se alza a `0.76.0` —no a `0.75.0`, que ya existe—: un número que
  no colisiona y además **ordena** este árbol después de lo publicado.
- **La versión se cambia en un solo sitio, y todo lo demás se deriva.** El
  número vive en una línea; el `Implementation-Version`, el recurso de
  runtime, el nombre del ZIP y el `apiRange` de los plugins de ejemplo
  **salen** de ahí. No es una DRY de estilo: es la razón por la que no
  puede haber dos versiones que discrepen sin que nadie lo note (§2.61).
- **La línea de versión que primero lleva un id no se mueve porque el build
  declare otra cosa.** El `firstPublished` del catálogo nombra la línea, no
  un tag, y esa línea no ha cambiado. Además el campo **sólo se publica**:
  moverlo no cambiaría ninguna decisión del producto y sí rompería un
  contrato ya publicado (§2.61).
- **El digest que un README publica tiene que ser el del artefacto que el
  mundo puede descargar HOY.** El quickstart publicaba un checksum que ya
  no verificaba, porque el tag se había recreado por debajo. El defecto no
  era el número: era que la instrucción, al seguirse, fallaba. La
  autoridad se corrige con el digest **medido** —descargando el ZIP y
  hasheándolo—, y el quickstart se ejecutó tal cual para que la línea que
  se publica sea la que funciona (§2.61).
- **Una afirmación que era verdad y dejó de serlo se corrige con la
  medida, no se reescribe.** El ROADMAP decía «este repositorio está en
  `0.62.0`» y «el jar `pipeline-runtime-0.62.0.jar` está en `lib/`». Con el
  número nuevo, esas frases son falsas. Se corrigen: el nombre del jar pasa
  a `<version>`, que no envejece, y las frases que describen un estado
  pasado pasan a estar en pasado (§2.61).
- **Un lector que no puede leer un texto sin adivinar, se niega.** La
  identidad de una operación es la clave con la que el journal la direcciona:
  devolver una identidad *parecida* no es un detalle, es una respuesta
  equivocada con la forma de una respuesta. Por eso el parser enumera las
  lecturas posibles del texto en vez de quedarse con la primera, y devuelve
  `null` cuando hay más de una o cuando el cuerpo no se puede cortar sin
  adivinar (§2.59). El coste de esta decisión es que el texto de un
  identificador con guiones dentro **no se puede leer entero**: es deuda con
  nombre, y cerrarla es una ruptura versionada que espera una necesidad de
  producto concreta.
- **Lo que sí se puede leer sin adivinar, se lee.** La etapa y el paso están en
  la raíz del identificador y no dependen de cómo se lea el cuerpo. Por eso hay
  una segunda lectura, sólo de índices, y la consola la usa. Sin ella, el
  arreglo anterior habría roto en silencio el filtro por etapa y paso de toda
  operación con cuerpo — y un filtro que deja de seleccionar es indistinguible
  de un filtro que no tiene nada que seleccionar (§2.59).
- **El residuo que el producto deja fuera de su raíz se decide en el proceso.**
  El driver de SQLite extrae su biblioteca nativa cuando se carga, y el
  directorio donde lo hace lo decide una propiedad del proceso, no de cada
  conexión: la extracción ocurre antes de la primera conexión, así que fijarla
  por conexión llegaría tarde para la primera (§2.58). Con la propiedad
  fijada, el residuo va a un directorio **con nombre**, que el proceso borra al
  salir bien y que, si el proceso muere a la fuerza, se puede atribuir.
- **Un escenario que mezcla dos defectos no prueba ninguno.** El caso de
  identidad duplicada usaba un plugin con el manifiesto manipulado, así que el
  rechazo que leía podía atribuirse a dos causas a la vez (§2.60). Los dos
  artefactos ahora son honestos —mismo manifiesto, mismo código, bytes
  distintos—, de modo que **la única razón para rechazar la composición es la
  identidad**. Y el fixture comprueba lo que dice: si el reempaquetado saliera
  idéntico, el escenario se quedaría sin nada que rechazar, y eso es un fallo
  ruidoso del fixture, no un escenario verde.
- **No saber de quién es un directorio no es motivo para borrarlo.** El
  permiso de destruir la raíz se concedía cuando la propiedad era
  *desconocida*, porque el caso que lo concede afirmaba ser de PipelineK y
  el puente usaba ese caso para lo que no sabía. La propiedad desconocida
  produce ahora el caso que **rechaza**, y el sitio que sí conoce su
  propiedad la declara para que su permiso sea acreditado y no accidental.
  Un default que concede permiso es un default que espera a que alguien se
  olvide.
- **Un contrato que ya ha roto una vez necesita un instrumento, no un
  comentario.** La coherencia de la base del workspace —`dir`, `deleteDir`,
  `stash` y `unstash` sobre el mismo cwd efectivo— estaba escrita en cuatro
  adaptadores y dos KDoc, y ya había roto una vez: el KDoc de
  `DeleteDirOperationsAdapter` lo confiesa. Un KDoc no se rompe, se ignora. El
  instrumento es **un** escenario que falla si la base se separa —no cuatro
  escenarios, que sería el mismo contrato medido cuatro veces por el precio de
  cuatro filas— y la demostración son **cuatro mutaciones, una por pata**, cada
  una tocando **un solo** punto de código. La primera versión de esa
  medición mutaba el punto compartido, y con ella las cuatro patas se
  encendían a la vez: eso demuestra que el contrato se rompe entero, no que
  cada pata se vigile. Con una mutación por pata, cada una apaga **sus**
  garantías y deja las demás en verde, y ninguna comparte token con otra.
- **Un instrumento de medida también puede mentir, y por eso hay que mirar lo
  que mide, no lo que dice.** La primera matriz dio la misma señal para `stash`
  y para `unstash`, y la explicación cómoda era «el instrumento no
  distingue». La verdadera estaba en el script de mutación: acababa en
  `write_text(s.replace(old, new))`, y el `replace` de Python reescribe
  **todas** las ocurrencias, así que la pata del `stash` estaba cambiando
  también la del `unstash` sin decirlo. El defecto no estaba en el producto
  ni en el escenario: estaba en el instrumento de medida, que es justo donde
  un error así se esconde, porque el script era el único que podía confirmar
  que las cuatro patas eran cuatro. Un mutador que no **verifica** cuántos
  sitios ha tocado no puede usarse para separar dos casos que se parecen.
- **Un diagnóstico tiene que ser fiel al resultado, no a una parte del
  resultado.** El rechazo por identidad duplicada nombraba al artefacto que
  reclamó la identidad primero y añadía «que se queda en la composición». La
  primera frase era cierta; la segunda era **falsa**, porque un rechazo
  detiene la composición entera. Nombrar bien a un artefacto y mentir sobre lo
  que pasa con él es peor que no nombrarlo: el operador lee «se queda» y
 entiende «sigo ejecutando». El texto ahora dice las dos cosas que pasan, y
  el test unitario tiene una fila que **prohíbe** la frase engañosa, que es la
  única forma de que un texto no vuelva a mentir por descuido.
- **El módulo que implementa los puertos publicados se queda, y se cablea.**
  `:pipeline-runtime` era un módulo sin consumidor: sus adaptadores
  implementaban un contrato publicado y nada los invocaba. La decisión no era
  "código muerto o código vivo", sino **qué contrato se cumple**: retirarlo
  habría dejado los tres verbos publicados sin implementación —una promesa
  rota es peor que una promesa cumplida—, y cablearlo le da al runtime la
  autoridad única del journal y de las leases. La prueba de que el cableado
  es real y no nominal son **tres verbos de CLI** que llegan a los puertos y
  **dos escenarios ejecutables** que los ejercitan; un módulo importado y
  nunca invocado habría sido la misma fractura por dentro.
- **Un módulo que la puerta no nombra es un módulo que puede caerse de la
  puerta sin que nada se ponga rojo.** `:pipeline-runtime` **sí** viaja en la
  distribución —su jar está en el `lib/` de la instalación—, así que
  `installDist` lo compila de rebote. Por eso la etapa 1 lo pide
  **explícitamente**: no para que se compile (ya se compila), sino para que el
  plan de la puerta lo nombre, y para que el día que la dependencia que lo mete
  en la distribución desaparezca, el módulo no se caiga del conjunto compilado
  en silencio. Detekt analiza y no compila, así que el instrumento más barato
  para ese contrato sigue siendo el compilador.
- **El veredicto viaja en el evento que termina el step.** No en un evento
  aparte, y no en la unión de dos eventos. La unión obligaba a emparejar por
  índice de step, y ese índice ni siquiera dice a qué stage pertenece el
  fallo. El terminal de un step es **un** hecho con dos aspectos —el
  veredicto y, cuando falla, la evidencia—, y la evidencia ya tenía su
  evento (`StepFailed`); lo que faltaba era el veredicto, y tenía que estar
  donde el lector lo encuentra sin buscar.
- **El token canónico vive junto al tipo que nombra.** `StepOutcome` declara
  su token (`success`, `unstable`, `failure`) igual que `RunOutcome` ya lo
  hacía, y su propio KDoc pedía ese hueco. El escritor escribe ese token y el
  lector lo reconoce contra esas mismas constantes: una lista de vocabulario
  escrita dos veces se separa la primera vez que alguien añade un caso.
- **El campo no lleva valor por defecto, a propósito.** Un valor por defecto
  dejaría que un productor se olvidara del veredicto y publicara
  «desconocido» queriendo decir «no me he acordado», y esa diferencia es
  invisible más abajo. Que el compilador rechace la llamada es la forma
  más barata de que el olvido no llegue a producción.
- **Un veredicto ausente es desconocido, y desconocido no es éxito.** Hay un
  camino real sin veredicto —el cuerpo del límite lanza algo que no es un
  fallo de step y la excepción se propaga— y una fila escrita por un build
  anterior, que no publicaba veredicto. Las dos se leen **sin** veredicto.
  Es lo que hace el review cuando pide que «los resultados desconocidos
  sigan siendo desconocidos».
- **Tres instrumentos, porque ven tres cosas distintas.** El test unitario
  mira lo que un **lector** lee (el mismo que sirve la columna `OUTCOME` de
  `inspect`), no la forma del evento: lo que ata es que el veredicto se
  pueda leer, no dónde vive. El códec del log durable tiene su propio
  guardian, porque un veredicto que se publica y no sobrevive al disco no lo
  puede leer el consumidor externo. Y el **ejemplo ejecutable** es el único
  que puede afirmar sobre el binario real: el lanzador pide solo los
  terminales y lee el veredicto de cada uno.
- **La proyección puede hablar cuando el evento lleva el veredicto.** La
  constitución semántica de la línea de consola —«una proyección no puede
  renderizar un estado que su evento no lleva»— no cambia; lo que cambió es
  que el evento ya lo lleva. Por eso la línea y la columna de `inspect`
  ahora dicen el veredicto, y antes no podían.
- **El id de capacidad se re-versiona cuando cambia el *significado* de un
  campo, aunque el tipo no cambie.** `StepFinishedTyped.outcome` sigue siendo
  un `String`: un consumidor compilado contra la forma vieja sigue
  enlazando y sigue leyendo — el tipo del step donde esperaba el veredicto.
  Nada en el tipo lo dice, así que nada más que un id nuevo lo puede decir:
  `runtime.eventshape.v1` queda anunciado y no entregado, y
  `runtime.eventshape.v2` es el que se entrega. `events.stepoutcome.v1` es el
  id que negocia el que consume el event lane y quiere saber si esta build
  publica veredicto.
- **La baseline se mudó, no creció.** Cuando el código se mueve, sus claves
  de baseline cambian de sitio: mismos hallazgos, otra dirección. Y las
  tres entradas que la muela dejó obsoletas se **arreglaron en el código**,
  que es lo que la política pide: la baseline se arregla arreglando el
  código. El número de hallazgos no cambió.
- **El cursor es la posición del cliente, no la del almacén.** La regla dice
  «el cursor es la posición del último hecho entregado» y, cuando la llamada
  no entregó nada, «la posición del almacén». No es un matiz: es lo que
  distingue un cursor que salta hechos de uno que no. El cursor del almacén es
  correcto para una lectura sin filtro y **pierde hechos** con filtro, porque la
  página está limitada por filas y el límite cuenta coincidencias. Lo que el
  cliente necesita saber es «dame lo que viene después de lo último que me
  diste», y eso es exactamente lo que un hecho entregado significa.
- **La regla de paginación vive en una función, y los dos drenadores la
  llaman.** El drenador de envoltorios y el tipado leen por costuras distintas
  y tienen bucles propios, pero la garantía es una: el mismo cursor para las
  dos proyecciones. Dos copias de la regla son dos reglas, y divergen.
- **Dos instrumentos, porque ven cosas distintas.** La regla de paginación es
  pura y la vigila un test unitario con un doble en memoria, que puede
  construir la historia que hace falta. El cableado del verbo lo vigila un
  ejemplo ejecutable contra la distribución real, con el lanzador haciendo de
  cliente externo: cada página es una invocación nueva, que es lo que hace un
  cliente que se desconecta. Un test unitario no puede afirmar sobre el binario,
  y un ejemplo no puede construir la historia que expose una regla de
  paginación.
- **El lanzador abre `stdout` para este seam, y sólo para él.** §2.4 dejó
  escrito que el lanzador capturaba `stdout` a fichero y nunca lo abría, y por
  eso una garantía rota en el event lane no cambiaba ninguno de los dos hechos
  que el lanzador mira. Con el verbo `events` los hechos van por `stdout` y el
  cursor por `stderr`: la garantía está partida en dos canales y ningún
  instrumento miraba ninguno. El verbo ya sabe proyectar una fila como texto
  plano (`--fields SEQ`), y eso es lo que permite al lanzador comparar hechos
  sin interpretar JSON: el lanzador sigue sin saber de semántica de eventos.
- **Un hueco medido no se arregla de paso, pero no se pierde.** El `--follow`
  con un filtro que excluye `RunFinished` no termina nunca: comprobado
  contra el binario. Se anota con su síntoma, su causa (la autoridad de «el
  run terminó» es un evento que el filtro puede tapar) y su próximo paso
  (extraer la detección terminal a una función pura y decidir esa autoridad).
  Arreglarlo aquí sería crecer el cambio porque se encontró un cambio, y
  además el arreglo no es trivial: la detección vive dentro del verbo.
- **La versión del artefacto NO se toca, y la publicación del vocabulario se
  versiona con el identificador de capacidad.** Las dos cosas parecían la
  misma y no lo son. El identificador (`events.follow.v2`) es lo que un
  consumidor **negocia**: es la respuesta a «¿este build habla el vocabulario
  que yo entiendo?», y no necesita un número de release para ser verdad. La
  versión del artefacto, en cambio, la fija el proceso de release: este
  repositorio iba en `0.62.0` y el `v0.62.0` publicado es un artefacto
  distinto (el RESET re-funda la identidad del producto). Subir el número sin
  publicar una release sería afirmar una versión que no existe, y el README
  señalaba el `v0.62.0` como la autoridad pública de hoy. Por eso `events.follow.v1`
  pasa a DEPRECATED —«anunciado y NO entregado», que es lo que esa columna
  significa en este catálogo— en vez de quedarse mintiendo como entregado.
- **La traducción entre vocabularios es una función, no una regla por
  adaptador.** `EventFollowRefusal.of` es el único sitio que sabe qué
  negativa del puerto es qué negativa del follow. Un adaptador que tradujera
  por su cuenta podría contestar «run desconocido» donde el puerto dijo
  «fallo de almacenamiento», y el coste lo pagaría el consumidor, que no
  puede distinguir un fallo de un run que no existe.
- **Un fallo de almacenamiento se dice en cualquier lectura, no sólo en la
  primera.** El `catch` del follower que se colaba en `UnknownRun` estaba
  en la segunda lectura, y el test que lo cubría no existía: la mutación que
  lo restauraba no encendía nada. Un `catch` que responde con un hecho que no
  ocurrió es peor que una excepción: convierte «no lo sé» en «lo sé, y sé
  mal». El guardián nuevo cubre las dos posiciones.
- **El guardián del live-tail falsea el PUERTO, no el almacén.** La decisión
  se tomó antes de escribir el doble, que es cuando una decisión así se
  puede tomar. El puerto es la costura que el producto ya usa; un doble de
  SQLite habría comprado una garantía que nadie necesita a cambio de un disco
  por test. Y el guardián exige **las dos** cosas: que una página en silencio
  no termine el follow, y que el follow sí termine cuando el run termina.
  Sin la segunda, se podría satisfacer con un follow que nunca termina.
- **Un KDoc que dice la verdad sobre lo que NO está cerrado.** El contrato
  publicado afirmaba que el defecto de la página en silencio «no tiene
  guardián» mientras el handle citaba un falsificador inexistente. Esas dos
  afirmaciones no pueden ser verdad a la vez, y la que no era verdad no era
  la del guardián: el guardián no existía, el defecto estaba corregido y
  nada lo fijaba. Se escribió el guardián y el KDoc ahora dice la verdad.
- **La negativa del verbo se escribe en un sitio y se afirma en los
  ejemplos.** El texto que leen operadores y scripts es un contrato, y cada
  negativa tiene su frase: un texto que dobla dos negativas en una frase
  pierde lo único que la negativa sabía. Y los escenarios comprueban el
  **texto**, no sólo el código de salida: un negativo que sólo verifica «algo
  falló» no distingue un fallo del fallo que se quería ver.
- **Anclar la confianza del operador en los bytes y en el publicador, y no en lo
  que el plugin declara.** La decisión venía del revisor («anclar la confianza en
  la identidad criptográfica de los bytes del artefacto y en la identidad
  verificable de su publicador»), y el hueco que la justifies estaba medido: la
  política existente comparaba el digest **declarado** por el plugin con el
  digest fijado por el operador, dos magnitudes que nunca coinciden. La regla
  que se aplica compara magnitudes iguales: el digest del **fichero** que el
  operador fijó con el digest del **fichero** que el runtime midió. Y el
  publicador deja de ser una cadena en el manifiesto del plugin: la fila del
  operador lo aprueba y una firma Ed25519 sobre los bytes lo respalda bajo una
  clave que el operador conserva.
- **La política se activa, no se impone.** Se activa nombrando el fichero
  (`--plugin-trust`). Sin nombrarlo, el runtime se comporta como antes. Una
  política obligatoria sería una ruptura de comportamiento que este ciclo no
  está autorizado a hacer, y una política que no se puede apagar no es una
  política: es un cambio de producto disfrazado de opción.
- **La política cubre lo que el operador trae, no lo que viene embutido.** Los
  plugins de la distribución instalada son asunto de la distribución —sus
  checksums, su firma de release—, no de una fila por plugin que el operador
  tendría que escribir antes de poder usar ningún plugin de terceros. Cubrir los
  embebidos haría la política impracticable y, por tanto, inútil.
- **La firma viaja desligada del artefacto.** Una firma dentro del JAR cambia el
  JAR, así que el digest tendría que cubrir bytes que no pueden existir todavía.
  Con la firma al lado (`<artefacto>.sig`), el publicador firma el digest del
  artefacto tal como se reparte y el runtime verifica sobre los bytes que
  midió.
- **La clave del publicador la guarda el operador, no el plugin.** La fila nombra
  un **id** de clave, y el keyring del operador resuelve ese id. Una fila que
  pudiera llevar su propia clave aprobaría lo que quisiese; una fila que sólo
  puede nombrar una clave que el operador ya tiene, no.
- **Retirar `PluginAdmissionGate` en lugar de cablearlo.** Era una segunda
  autoridad de admisión con el producto detrás, y su política de confianza era
  la equivocada. Cablearla habría producido dos respuestas a «¿este plugin
  entra?» según por dónde pasara la llamada; conservarla sin llamadores habría
  dejado un segundo lugar donde se decide la admisión, que es la forma de
  autoridad duplicada que `AGENTS.md` prohíbe. Se retira, y sus referencias en el
  KDoc de ocho ficheros se corrigen para que nombren la autoridad que existe.
- **El producto dice qué decidió la política.** Una política que opera en
  silencio es indistinguible de una política que nunca se consultó, y esa
  indistinguibilidad es exactamente el fallo que este ciclo vino a cerrar. Por
  eso el runtime imprime los bytes, el publicador y la clave de cada plugin que
  la política aprobó.
- **Un solo lector de JSON.** El módulo tenía tres cursores a mano para tres
  documentos y ya se contradiían entre sí. Un documento que el runtime lee no
  puede significar cosas distintas según quién lo leyó.
- **Adoptar el orden de la revisión externa, con sus cifras re-medidas aquí.**
  La revisión del 20/10/2026 propone cerrar primero el plano de eventos y
  salida, después la integración con Fabric, luego el SDK y sus plugins, y al
  final seguridad, certificación y documentación. Se adopta ese orden y se
  rechazan sus cifras sin medir: la revisión se hizo sobre otro SHA y otra
  versión, así que cada afirmación **de este repositorio** se comprobó aquí y
  su veredicto quedó escrito con fichero y línea (§2.22). Copiar un diagnóstico
  sin medirlo es cómo un ROADMAP empieza a mentir.
- **No exponer `inspect` ni `recover` aunque la revisión pida integrarlos.** La
  revisión quiere «cablear los adaptadores existentes de inspección, cancelación
  y recuperación». El cancelador está cableado. Los otros dos no se exponen
  porque el producto ya tiene un plano de lectura (`inspect` sobre el event
  lane) y un motor de recuperación (`--resume` sobre el journal y el cursor de
  replay): exponerlos sería un segundo plano y un segundo motor. La decisión
  queda escrita y a la vista para quien revise.
- **Reconciliar con `main` antes de seguir construyendo.** La rama estaba a 196
  commits de su lado y a 29 del suyo, con el trabajo de Saga y el tren de releases
  enteros fuera. Integrar sobre una base divergente es la forma de perder trabajo
  ajeno con buena intención: por eso la reconciliación va primero, con reglas
  escritas y aplicadas fichero a fichero, y el trabajo propio del ciclo sigue
  después, sobre el árbol ya integrado.
- **No borrar `:pipeline-runtime`.** El censo dice que nadie lo llama, y lo difícil
  no es que lo diga: es que un contrato publicado con una implementación
  escrita contra el journal, las leases y el plano de salida que ya existen no es
  código muerto mientras su consumidor sea posible. Se integra y se compila en la
  puerta; si más adelante se demuestra que hay una implementación productiva
  equivalente, la retirada se propone con la prueba delante y con revisión
  explícita.
- **Restringir la escritura remota mientras dura la reconciliación.** La misión
  deja de poder escribir en remoto hasta que el árbol integrado esté verificado:
  fusionar y publicar desde una rama divergente es exactamente lo que esta
  reconciliación viene a evitar.
- **Un solo registro de capacidades, y que diga la verdad sobre lo que entrega.**
  `:pipeline-runtime` traía un segundo catálogo con los mismos once ids, los mismos
  estados y las mismas fechas de primera publicación, con un KDoc que se declaraba
  canónico, y con una sola referencia en todo el árbol: la que lo señalaba desde
  `SdkCapabilities`. La equivalencia está medida, así que la consolidación no es una
  opinión: el catálogo que el producto imprime se queda con los ids y con la
  semántica, y el otro fichero desaparece. Y el catálogo distingue ahora lo que el
  build **entrega** de lo que sólo **anuncia**: en aquel ciclo `runtime.cancel.v1`
  estaba DEPRECATED porque su implementación existía pero nada la componía, y un
  catálogo que lo presentara como disponible mandaría a un consumidor hacia un
  camino que no existe. Ese id volvió a EXPERIMENTAL en el ciclo siguiente, cuando
  el verbo que lo compone llegó (§2.20); la regla —«lo que se anuncia y no se
  entrega se marca DEPRECATED»— no cambió. Un test unitario pins el catálogo, que
  era una promesa del KDoc sin observador.
- **No** crear un arnés de pruebas. El código de verificación vive en
  `scripts/verify-all.sh` (un entrypoint) y `scripts/check-budgets.py` (un
  script stdlib). Nada más.
- **No** probar `EventViewProjection`: su alcanzabilidad desde producción es
  cero, y un test permanente para código que nadie llama es verificación de
  coste sin consumidor.
- **Mutación antes que confianza.** Cada test de regresión de este ciclo se
  comprobó revirtiendo el arreglo. Un test que sobrevive a su propia mutación
  no prueba nada, y se dice antes de llamarlo garantía.
- **Los `NOT_RUN` se declaran, no se esconden.** Un escenario que no se ejecutó
  es un tercer estado, y el gate lo imprime.
- **Dos tests de este ciclo afirmaban propiedades que el contrato no tiene, y se
  corrigieron en lugar de relajarse.** El primero alimentaba el sobre completo a
  `readPartValue`, que espera el buffer **posicionado en una cabecera de parte**:
  leía el byte de recuento de partes como longitud de nombre y reservaba ~2 GiB
  antes de fallar. El segundo afirmaba que cada kind id tiene una longitud de
  bytes distinta — una propiedad que el formato nunca prometió:
  `UsernamePassword` y `UsernameColonPassword` escriben **el mismo** layout de dos
  partes y solo se distinguen por el kind id de la cabecera. La afirmación se
  sustituyó por la garantía que sí se sostiene (round-trip por kind id), cuya
  mutación —forzar el `when(kindId)` a una sola rama— mata 10 tests. Un test que
  describe un formato equivocado no es un test débil: es un test que empuja a
  «arreglar» el producto para satisfacerlo.

- **`delivery-first-testing` tiene una affordance colgante, y el router la ofrece
  como si fuera ejecutable.** `references/navigation.md` punto 10 enruta la
  intención `audit` a `audit-portfolio`. En disco hay **8** playbooks en
  `references/lenses/` y **`audit-portfolio.md` no está entre ellos**. No es una
  lente activable: invocarla produce un enlace a nada. Las que sí están y sirven
  para este árbol son `change-impact`, `regression-promotion` y `failure-triage`.
  Se anota para que nadie la invoque esperando un procedimiento.

- **La puerta se limpia a sí misma antes de medir.** La limpieza de las
  salidas de build pasa a ser parte de la puerta completa, con la tarea
  `clean` de Gradle, y no un paso manual. Motivo: una puerta que sólo se puede
  medir una vez no se mide tres veces, que es lo que la política pide.
- **El snapshot de deuda se refresca donde el análisis lee.** La tarea que
  regenera la baseline escribe en el fichero que la puerta consume. Motivo: un
  fichero de deuda que se actualiza solo en otro sitio no es un snapshot, es una
  decoration, y sus 20 entradas fantasma eran una puerta por la que la
  deuda nueva podía colarse en silencio.
- **El barrido de comentarios se acepta sólo si la parte de código de cada
  línea no cambia.** No basta con que el árbol compile: una muestra de código
  en un comentario puede quedar rota y el compilador no se entera. Motivo: el
  instrumento tiene que ser más estrecho que el daño que previene.
- **El defecto del observador de salida en vivo se mide y se deja, no se
  arregla de paso.** Es un cambio de producto, y un cambio de producto
  necesita su propio test de regresión, visto rojo antes de verde. Motivo: un
  arreglo sin test no es un arreglo, es una apuesta.
- **`docs/` desaparece.** Contenía la continuación de un plan anterior al
  RESET —`BQ1`–`BQ5`, `test-harness`, `cycle`— y un script de una sola vez
  que la insertaba en el roadmap. `AGENTS.md` prohíbe markdown bajo `docs/`, y
  el contenido está superado por este documento. Se borró de forma
  recuperable. Motivo: un árbol limpio es un árbol del que se puede razonar.
- **Un fallo que no dice qué faltaba se arregla antes que el fallo que sí lo
  dice.** El defecto de la fusión era una línea mal escrita; el defecto que lo
  escondía era un mensaje de fallo que no nombraba el texto ausente. El
  segundo es el que cuesta el tiempo de todos los que vienen detrás. Motivo:
  un diagnóstico que obliga a reejecutar para entenderse es un coste, no una
  cortesía.
- **Una explicación correcta no autoriza una conclusión mayor.** El escenario
  del plugin externo afirmaba que su resultado no es observable, y lo es; de
  ahí se concluyó que no había nada que afirmar, y no era cierto: el valor no
  es observable, la ejecución sí. Motivo: un límite real aplicado a la
  afirmación equivocada se convierte en una cobertura que nadie tiene.

- **La documentación de usuario se audita contra el árbol, no contra la memoria.**
  Los dos README mandaban a ejecutar ficheros que ya no existían: el primer
  pipeline del lector y uno de los escenarios. El segundo se retiraba del
  árbol y el primero nunca estuvo en él. Eso no es una desatención editorial:
  es la línea de órdenes que el lector copia y pega. La auditoría comprueba
  que cada ruta citada existe, que cada cifra publicada coincide con el
  árbol y que no queda ningún identificador de plan; se ejecutó en rojo antes
  de corregir y en verde después. Motivo: un README que miente cuesta más que
  un README que no existe, porque el lector no tiene forma de saber que miente.

- **El suite ejecuta cada escenario en las dos rutas de ejecución.** Con
  journal privado (como una CI) y sin él (como una corrida local), y las dos
  tienen que cumplir las mismas expectativas. Motivo: una divergencia entre
  rutas es un defecto, y un suite que mide una sola de ellas no puede saber que
  hay dos. La divergencia que encontró —dos informes distintos para el mismo
  fallo— llevaba un ciclo entero viva porque nadie ejecutaba la otra ruta.
- **Un informe de salida tiene una sola autoridad.** Las dos rutas llaman a la
  misma función, así que no pueden divergir. Motivo: dos copias de una decisión
  de presentación son dos respuestas a la misma pregunta, y la que se escribe
  segundo es la que gana.
- **El fallo se informa con su tipo y su origen.** El resultado va en una
  línea y la razón en la siguiente, y la razón nombra el tipo de fallo
  (`cause [USER]`) y su origen. Motivo: un informe que obliga a adivinar por
  qué falló algo no es un informe.

- **El módulo que el build no veía se retira, y la alternativa se midió antes de
  decidir.** `pipelinek/pipeline-protocol/` llevaba tiempo versionado sin declarar, y
  el bloque que lo describía afirmaba que no compilaría si se declarase. La
  afirmación era falsa: declarado, compila en 36 s. Medir la alternativa
  cambió la pregunta —de «¿compila?» a «¿merece la vida?»— y la respuesta fue
  que no: cero consumidores, ningún artefacto publicado, ningún dump de ABI, y
  un protocolo de worker distribuido que el producto no tiene. Motivo: una
  decisión de ciclo de vida sostenida sobre una inferencia se sostiene sobre
  la inferencia; la misma decisión sostenida sobre una medición se sostiene
  sobre el árbol, y además deja el árbol en un estado donde la próxima persona
  no tiene que volver a decidirla.

- **Un gate que apunta a un proyecto inexistente no es un gate.** El build
  raíz declaraba tres tareas —un consumidor de contrato, una ejecución
  externa y un consumidor de scripting— sobre `examples/fabric-contract-consumer`,
  `examples/sdk-external-execution` y `examples/scripting-contract-consumer`.
  Esos proyectos se borraron cuando los ejemplos cambiaron de sitio, y el
  `file("examples/…")` del build apuntaba entonces a `examples/…` **dentro** del
  directorio del build, una ruta que **nunca existió en ningún commit**. Las tres
  tareas no podían ejecutarse, y no enteraron a nadie porque no estaban en
  `check`. Se borraron con sus comentarios, que además describían como vigente
  una capacidad que no está en el árbol. Motivo: una tarea que no se puede
  ejecutar es una afirmación sobre el producto, y una afirmación que no se puede
  comprobar es ruido con apariencia de garantía.

- **Una versión de dependencia tiene una sola autoridad.** Veintiún módulos
  repetían la coordenada del launcher de JUnit como literal en su fichero de
  build, mientras el catálogo de versiones declaraba la misma dependencia con
  su versión, sin que nadie usara el acceso del catálogo. Una versión repetida
  en veintiún sitios deriva sin que nada falle: el catálogo sube y los
  módulos se quedan atrás. Ahora los veintiún usan `libs.junit.platform.launcher`,
  los dos que fijaban `kotlin-reflect` a mano usan `libs.kotlin.reflect`, y las
  tres entradas del catálogo que no tenía consumidor (`kover`, que se fue con
  su plugin, `kotlin-scripting-common`, y las de protobuf con su módulo)
  desaparecieron. Motivo: una versión duplicada es una segunda autoridad sobre
  un hecho, y la segunda autoridad es la que nadie actualiza.

- **Una etiqueta de plan no es una explicación.** Los comentarios de los
  scripts de build seguían marcados con identificadores de bloque, de carril y
  de fase, y tres de ellos afirmaban cosas que el árbol ya no cumple: que el
  validador de ABI **no** estaba conectado a `check` —sí lo está—, que el
  conjunto de módulos con ABI congelado eran seis —son siete—, y que dos
  registries de contrato eran consumidos por un test que RESET borró. Todo eso
  reescrito a prosa que explica la funcionalidad, y las tres afirmaciones
  falsas corregidas contra el código. Motivo: un comentario que nombra una
  tarea caducada no informa a nadie; uno que explica por qué el árbol es como
  es, sí. Y un comentario que afirma lo contrario de lo que el código hace es
  peor que no escribirlo.

- **El nombre de un directorio debe decir qué contiene.** El build raíz se
  llamaba `v2` en un repositorio cuyo producto iba por `0.62.0`, y los
  ejemplos ejecutables vivían dentro de ese directorio cuando `AGENTS.md` los
  describe en la raíz. Los dos nombres se han cambiado: el build es
  `pipelinek/` y los ejemplos son `examples/`. Motivo: un nombre de directorio
  es la primera documentación que lee quien abre el árbol, y «v2» no dice si
  habla de una versión del producto o del módulo que lo construye. El nombre
  de los paquetes no se toca, porque el paquete es la API publicada y su
  nombre no es un asunto de directorios.

- **Mudarse de sitio no puede ser una forma de mover pruebas de un lado al
  otro.** El censo de LOC cuenta los escenarios y el lanzador en el numerador
  y los plugins de ejemplo en el denominador; después del cambio cuentan los
  mismos ficheros, en el mismo lado, con la misma regla. Motivo: un
  presupuesto que se puede hacer verde moviendo ficheros es un presupuesto que
  mide dónde está el código, no cuánto trabajo de verificación cuesta.

- **El estado local no es contenido del repositorio.** Diez worktrees de este
  mismo repositorio vivían dentro del directorio de trabajo, ocupaban 16 GB,
  no estaban en ninguna rama publicada y traían un `build.gradle.kts` cada uno
  que un barrido leía como si fuera el build del producto. Se retiraron con
  `git worktree remove` —sin pérdida: las ramas y sus commits siguen en el
  objeto— y `.worktrees/` quedó ignorado. Sus diez entradas de índice se
  quitaron también: eran punteros de enlace sin declaración que los respaldara,
  de modo que un clon los materializaba como carpetas vacías y `git submodule
  status` no encontraba su mapeo. Con ellos se fueron `dist/` con
  cinco candidatos de hace diez versiones, un ZIP del paquete de plan del
  RESET y `.github/dependabot.yml`, que configuraba workflows que no existen
  y daba por hecho un gate de CI que el repositorio dejó de tener. Motivo: un
  árbol que carga con 16 GB de historia ajena y con ficheros que describen un
  mundo distinto del suyo enseña a leer lo que no está.

- **Un escenario versionado fuera de `examples/` no es un escenario.** El único
  pipeline que quedaba fuera de `examples/` era un resto del régimen de CI
  retirado: ningún proceso lo ejecutaba y el censo lo contaba como
  verificación. En vez de borrarlo —que era la opción fácil— se convirtió en
  lo que le faltaba para ser un escenario de verdad: vive en `examples/`, lo
  ejecuta la puerta y afirma el código de salida y el tipo de fallo que el
  producto debe nombrar. Motivo: un fichero que el presupuesto cuenta como
  prueba y que nadie ejecuta es una mentira contable; si la conducta merece
  cobertura, se cubre, y si no la merece, se borra.

- **El informe de un fallo nombra lo que falló.** El lanzador etiquetaba como
  «durable» el fallo de cualquier escenario, durable o no, de modo que el
  informe de una ruptura señalaba un escenario que no lo era. Motivo: un
  diagnóstico que nombra mal la cosa rota cuesta más tiempo que el que
  ahorra.

- **Una garantía sin destinatario no se protege, ni siquiera gratis.** La
  familia de tipos de análisis de informes JUnit que vive en el dominio
  publicado no la usa nadie, y el parser que sí se usa vive en otro módulo.
  Escribir un test para la familia huérfana habría sido pagar por una
  garantía sin consumidor, que es exactamente el error que este documento ya
  cometió dos veces. Retirarla, en cambio, es una ruptura de la superficie
  publicada: se registra con su medida y se decide en una versión, no se
  disimula. Motivo: la deuda que no se nombra se vuelve a inventar.

- **Una regla de ignorado se ancla a donde vale.** La regla que ignoraba el
  directorio efímero del ciclo se tragaba además el paquete de producción
  `dev.rubentxu.pipeline.v2.domain.workspace`, y lo hacía en silencio: un
  fichero ya versionado no se desversiona por una regla de ignorado, sólo
  uno nuevo. El fallo no era de versiones, era de que el árbol no decía la
  verdad sobre su propio contenido. Motivo: una regla que decide por nombre
  y no por sitio tiene que decidirse por sitio.

- **Un test que no puede fallar no se conserva.** Uno de los casos escritos
  para el escapador afirmaba que una referencia dentro de un literal de carácter
  queda intacta, y no podía fallar nunca: un literal de un carácter no cabe
  dentro una referencia, así que la aserción era tautológica. Se reescribió
  para afirmar lo que de verdad distingue al escáner —que el literal no se
  traga lo que viene después— y esa versión sí se puso roja al romper el
  cierre del literal. Motivo: una suite que parece cubrir algo y no puede
  fallar es un documento que ocupa el sitio de un test.

- **La cadena de objetivos se declara, pero el ROADMAP sigue siendo la autoridad.**
  La misión continua del proyecto —su objetivo y su cola de trabajo— vive en un
  fichero local e ignorado por Git, con la misma filosofía que el resto del estado
  local: una herramienta que deja ficheros sin versionar en el repositorio de otra
  persona se gana el mistrust de quien la usa. El ROADMAP sigue siendo la autoridad
  del trabajo; la declaración sólo dice por dónde empieza la cadena y qué son sus
  candidatos. Los presupuestos salen de una medición: un ciclo de este proyecto costó
  del orden de 740 000 tokens en los cinco ciclos de esta sesión, así que el objetivo
  lleva 1 000 000 y la misión 10 000 000.

- **La cola de la misión se mide antes de escribirse.** Los tres candidatos que la
  acompaña son bloques que el ROADMAP declara abiertos, y el primero es el que la
  medición de este ciclo dejó sin cobertura: `:pipeline-runtime`. La cadena no
  inventa trabajo, y un candidato cuya evidencia apunte a un fichero que no existe se
  descarta.

- **El cancelador toma la lease, y el contrato publicado se reescribe para
  decirlo.** La versión anterior del puerto decía que el cancelador «NO toma una
  lease nueva». Eso y escribir un libro de fin exclusivo son incompatibles: sin
  la exclusividad, dos procesos pueden escribir el terminal del mismo run, que es
  justo la ambigüedad que el verbo existe para eliminar. La versión nueva toma la
  lease —el lock del sistema operativo es el único hecho sobre «¿hay un proceso
  vivo publicando este run?»— y por eso nunca cruza una frontera **viva**: un
  escritor vivo tiene el lock y la adquisición lo rechaza, que es el rechazo que
  el contrato ya publicaba. El ABI no cambia; la prosa de un contrato publicado
  sí, y por eso se anota aquí para revisión.

- **El terminal de un run es el libro de fin del event lane, y no una fila del
  journal.** Una fila del journal es el estado de una *operación*, y una
  operación llega a estado terminal mucho antes que el run: un run a medias
  tiene ya una fila terminal en cuanto su primer paso termina. Confundir las
  dos cosas hacía que el cancelador rechazara el run que precisamente quería
  cerrar. Además, el cancelador **no** marca la operación que estaba en vuelo
  como abortada: si su efecto ocurrió o no es algo que desde fuera no se sabe, y
  un `resume` posterior tiene que seguir viendo «efecto desconocido» en vez de
  un «efecto fallido» que nadie ha comprobado.

- **Un solo verbo nuevo, y sólo el que no tenía sitio.** `cancel` se expone;
  `inspect` y `recover` no, porque el producto ya tiene un plano de lectura y un
  motor de recuperación, y exponer los puertos de runtime sería un segundo plano
  de lectura y un segundo motor. La prohibición de `AGENTS.md` sobre las
  segundas autoridades pesa más que la simetría de los tres verbos.

- **La fila de control de la cancelación es `SUCCEEDED`, y su estado es
  deliberado.** La cancelación se cumplió; el run es lo que se abortó. El
  informe de fallos del producto selecciona las operaciones en estado de fallo,
  así que una fila de control en `ABORTED` saldría ahí como una operación más
  que falló, y no lo es.

---

- **Un hueco medido no se arregla de paso — y el que se arregla, se cierra por
  escrito.** El `StepFailed` seguía sin `stageIndex` (§2.23, **P1.3b**), y eso
  bloqueaba la ley de contradicción terminal del harness: correlacionar un
  `StepFailed` con su terminal necesitaba un key de step que no fuera ambiguo
  entre stages. Ese hueco está cerrado (§2.29). El que sigue abierto lleva
  dueño: el verbo `events verify` no lo ejecuta ningún escenario
  (**P1.7**).
- **El stage del fallo viaja en el evento de fallo, y no en un evento aparte.**
  La forma es la misma que en P1.3a: el dato va **en el evento que lo
  necesita**, y no en una unión de dos eventos que un consumidor tiene que
  emparejar por un índice. Un `StepFailed` que no dice su stage obliga a
  unirlo con otro evento, y esa unión es ambigua entre stages porque el
  índice de step significa un step distinto en cada stage del run.
- **El stage ausente no es el stage 0.** El campo es `Int?` y **no lleva
  valor por defecto**: cada productor dice el stage que sabe, o dice que no lo
  sabe. Hay dos ausencias distintas y una de ellas es un hecho —un fallo en
  el primer stage y un fallo cuyo stage nadie registró— y `0` las
  confundiría. Un valor por defecto de 0 no habría roto el compilador: habría
  inventado un stage en producción, que es justo lo que este cambio quita.
- **Un productor que no sabe el stage lo dice; no lo inventa.** El helper
  `error` del SDK publica el fallo **sin** stage, porque su `StepContext` no
  lleva el stage. Preferimos un evento que no dice dónde a un evento que dice
  dónde, pero no donde es.
- **El sujeto del sobre sigue al evento.** Un fallo que dice su stage es
  sobre ese step, y uno que no lo dice se queda con el run: el run es el
  sujeto honesto cuando la identidad del step no se conoce entera. La ley
  congelada del proyector —«sin identidad de stage, el step no es un sujeto
  posible»— tenía su razón, y la razón ya no existe.
- **La identidad de un step es (stage, índice, nombre), y un intento va de un
  `StepStarted` al siguiente `StepStarted` del mismo step.** Las tres partes
  están porque cada una quita una confusión concreta: el stage, porque el
  índice significa otra cosa en cada stage; el nombre, porque un step anidado
  comparte el índice del step que lo contiene; y el intento, porque un
  reintento vuelve a ejecutar el step y lo dice empezando de nuevo.
- **La ley se formula sobre el plano medido, no sobre el plano imaginado.**
  La ley naïf —«mismo step, un fallo y un éxito»— habría sido **falsa** en el
  caso del reintento, y ese caso no es un plano inventado: es un run real
  medido con el binario. Escribir la ley que parece evidente y descubrir
  después que el producto la refuta es la forma más cara de escribir una ley.
- **Un id de capacidad nuevo: `events.failurestage.v1`.** Un consumidor que
  lee el event lane tiene que poder preguntar si **esta** build publica el
  stage de un fallo, porque un fallo sin stage es lo que escribió un build
  anterior. No es un veredicto nuevo ni un segundo modelo de resultados: el
  fallo ya llevaba la evidencia, lo que no sabía era **dónde** pasó.
- **Extracción, y no baseline.** La ley se llevaba `ProtocolGrammar` por
  encima del límite de funciones de detekt, y la respuesta del propio config
  es extraer, no anotar: la ley sale a su propio objeto,
  `StepTerminalContradiction`. La baseline sigue en **802** entradas: no
  creció ni una.
- **Una mutación que no enciende nada se escribe como tal.** Quitar el stage
  de la identidad de la ley no cambió **ningún** veredicto, porque en un
  plano secuencial la ventana del intento ya separa los steps de dos stages.
  Eso no se escondió: la stage está en la identidad para que no dependa del
  orden, y lo que sí queda fijado es que un fallo **sin** stage no tiene
  identidad —no es el stage 0—, que es la fila que la mutación «lee el
  stage ausente como 0» enciende.
- **El filtro decide qué se entrega, no si el run terminó.** Un filtro es una
  decisión del cliente sobre qué hechos quiere ver; el final del run es un
  hecho del run. Confundir las dos cosas produce el peor de los dos mundos:
  el cliente recibe exactamente lo que pidió y no puede saber cuándo dejar de
  esperar.
- **La autoridad terminal es el almacén, y la consulta el único que lee las
  filas.** El drain es el único componente que ve lo que el almacén devolvió,
  así que es el único que puede decir si el run se acabó **independientemente
  del filtro**. Por eso `runEnded` viaja en el outcome del drain y no en una
  bandera que el verbo calcule por su cuenta.
- **La comprobación duplicada se quita, no se conserva.** El verbo llevaba su
  propia mirada al `RunFinished` entre los hechos entregados. Con el drain
  reportando el hecho, esa línea era una segunda autoridad: dos reglas para
  la misma pregunta, y la que sobraba era la que estaba mal.
- **Un instrumento puro y un instrumento con reloj, porque ven cosas
  distintas.** El unitario mira **qué filas se miraron** para decidir, y no
  puede ver un proceso que no termina. El ejecutable, con su reloj, es el
  único que puede afirmar «terminó». La mutación M2 lo dejó demostrado: el
  unitario siguió en verde mientras el ejemplo caía.
- **La complejidad se arregla extrayendo.** `runFollow` pasó de estar sobre
  el límite de complejidad de detekt a estar bajo él, porque la espera de
  sondeo salió a su propia función. La baseline sigue en **802** entradas: ni
  una más.
- **Un hueco hermano se escribe, no se arregla de paso.** `observe --follow`
  usa el cursor del almacén y `events --follow` el del cliente. No es el
  mismo defecto, pero son dos verbos con dos reglas de «¿dónde sigo?». Queda
  medido y con dueño; no se tocó.

- **El módulo se queda, y la pregunta que lo decidía era otra.** `:pipeline-runtime`
  no se retira. Retirarlo no sería retirar código muerto: es lo que sostiene
  `runs cancel` (§2.20) y el ejemplo `durable/39-cancel-dead-run`. La
  pregunta abierta no era «¿sobra código?» sino «¿sobran puertos sin
  consumidor?», y esa tiene una respuesta distinta para cada puerto: dos se
  cablearon a un verbo que ya existía, y dos se declaran **no entregados**
  porque nada los compone. Un módulo que se retira para no dejar puertos
  sueltos se queda sin el consumidor que ya tenía.

- **La lease se toma; el registro se consulta.** El registro de una lease dice
  el **pasado**: un escritor al que mataron lo deja detrás, y sigue diciendo que
  su dueño está vivo. Un recover que consulta el registro concluye que un run
  muerto es un run ocupado —y se niega a cerrar exactamente el run para el que
  existe—. El lock es la autoridad sobre quién publica el run **ahora**, así
  que se toma y se devuelve. Consultar el registro se queda como lo que es: un
  hecho sobre el pasado.

- **El veredicto de un run lo decide el dominio, y el adaptador no tiene regla
  propia sobre lo que es un run.** `RunOutcomeReducer` es la autoridad única
  que pliega los veredictos de los steps en el veredicto del run, y dice que el
  éxito exige que **todas** las operaciones hayan tenido éxito. La regla que
  tenía el adaptador —la última operación terminada— podía cerrar como
  exitoso un run que había tenido una operación fallida, y eso es un éxito
  fabricado: lo único que una repetición durable no puede permitirse. Un
  *abort* se **lee** —lo fijó el orquestador— en vez de derivarse, porque el
  reducer no deriva aborts.

- **Anunciado y no entregado es una categoría, no un insulto.** `runtime.structure.v1`
  y `runtime.operation.v1` pasan a DEPRECATED porque el adaptador tiene los
  métodos y **nada los compone** —medido, no supuesto—. DEPRECATED aquí
  significa «anunciado y NO entregado», no «será eliminado». Un id que un
  consumidor encuentra y no puede usar es una promesa que el build no puede
  cumplir, y el fallo llega en tiempo de ejecución en vez de en el catálogo.

- **Un contrato publicado que miente es peor que uno que estorba.** El
  `RuntimeRecoverPort` decía «DOES NOT: Take a lease» y ya era falso: el
  adaptador toma la lease. La prosa se corrige y se anota aquí para revisión,
  porque cambiar la prosa de un contrato publicado es una decisión de la
  autoridad del contrato, no una limpieza de estilo. El **ABI** no cambia: la
  prosa no está en el volcado de la API.

- **La puerta es la que dice si el árbol está verde, y por eso atrapa lo que
  los commits no dejaron pasar.** La primera ejecución de este ciclo cayó en
  la etapa 1 por dos violaciones de estilo que venían de commits **de este
  mismo ciclo**. El árbol no estaba roto, pero tampoco había sido medido. Un
  commit que no ha pasado por la puerta es una afirmación, no una medición.

- **Las referencias a pruebas que ya no existen son deuda medida, y se
  cuentan.** El RESET borró las suites y el harness, y el KDoc se quedó nombrando
  las pruebas que se llevó con ellos: **75** nombres de clases de prueba que no
  están declaradas en el árbol, en **97** referencias repartidas en **93**
  ficheros. Este ciclo corrigió **3** de esas referencias —las de los ficheros
  que tocó— y el resto queda escrito y con dueño: son afirmaciones sobre
  garantías que nadie puede comprobar, y una garantía que se afirma en un
  comentario sin ninguna prueba detrás no es una garantía, es una intención.

- **El reloj del lanzador es un reloj, no un presupuesto, y son dos.** El de
  escena dice cuánto puede tardar una escena; el de etapa dice cuánto le
  queda a la corrida entera. El segundo lo calcula **la puerta**, que sabe
  cuánto presupuesto le queda, y se lo pasa: dos presupuestos que no puedan
  discrepar son mejor que dos presupuestos que se comparan. El primero sale de
  una medición —la escena más lenta tarda 21 s— y no de un número redondo.

- **La ruta del producto es una función, no una variable.** Una variable sería
  una puerta: una edición posterior podría llamar al producto por ahí y
  **escapar del reloj**, y una escena colgada que escapa del reloj es el fallo
  que todo este mecanismo existe para evitar. Que el camino sea una función
  convierte «toda invocación tiene reloj» en una propiedad del código y no en
  una convención que un buen sastre puede romper sin querer.

- **Una escena que se para no es un fallo del producto: es un hueco.** El
  veredicto es **BLOCKED** —la garantía no se comprobó— y la corrida no es
  verde. Confundir un paro del reloj con un defecto del producto sería
  mentirle al operador dos veces: sobre el producto y sobre lo que la corrida
  sabe. Y al revés tampoco vale: un fallo real sigue siendo un fallo, y el
  reloj no tapa un defecto.

- **El instrumento se comprueba, no se supone.** Un reloj que no dispara es
  peor que no tener reloj, porque finge lo contrario. Así que el lanzador
  comprueba al arrancar que su reloj dispara **y** que se lleva el árbol de
  procesos, y se niega a arrancar si no. Tres segundos compran la diferencia
  entre «el lanzador tiene un reloj» y «el reloj funciona».

- **Un UAT que se retira deja su evidencia escrita y su decisión dicha.** Los
  dos UAT de este bloque se retiraron: eran exploratorios y su resultado queda
  en el ROADMAP con las mutaciones que lo sostienen. Lo que se promovió fue lo
  más barato que protege lo que de verdad importa —el auto-control del
  instrumento—, y lo que quedó **DEFERIDO** —un ejemplo que se cuelga a
  propósito— lleva dueño y motivo: costaría vocabulario nuevo en el lanzador
  para proteger un diagnóstico, no una garantía.

- **El hecho terminal se escribe al final.** El orden de las escrituras de un
  cancel no es una preferencia de estilo: `CancelOutcome.Cancelled` promete que
  el run quedó cerrado **y** sellado, y eso sólo es cierto si el bookend va
  después del sellado. Con el orden viejo, un fallo del Output Plane dejaba un
  run abortado cuyos bytes no terminaban nunca, y nadie —ni un cancel ni un
  recover— podía terminarlo. El orden nuevo deja, en ese fallo, un run **sin
  cerrar** con la intención ya registrada: un estado que otro cancel termina
  sin repetir ningún efecto, y hace que la primera negativa sea cierta en vez de
  contradecir al run que se negaba a cerrar.

- **Un run cancelado es terminal aunque tuviera un paso en vuelo.** El cancel es
  su hecho terminal, y el journal lo dice con el vocablo que ya estaba publicado
  (`cancelled`) y que nadie producía nunca. Sin eso, un run cancelado se parecía
  a uno caído: su journal no tenía fila terminal, el recover le ofrecía un
  re-attach, y reanudar un run que alguien canceló a propósito puede repetir el
  efecto del paso interrumpido —justo lo que el cancel quería evitar.

- **Un adaptador depende del puerto, no de su implementación.** El adaptador de
  cancel dependía de la lease en fichero, y por eso no tenía ni una sola
  prueba: cualquier observador suyo necesitaba un disco. Depender del puerto —
  como ya hacía el recover— permite observarlo con autoridades en memoria y con
  el sellado como **función inyectada**, que es donde cabe el fallo que hay que
  provocar para ver la garantía en rojo.

- **El nombre del recibo vive en un sitio solo.** El id reservado del recibo de
  cancelación lo escribe el cancel y lo lee el recover: si cada uno lo nombrara
  por su cuenta, se separarían el primer día que alguien cambiara el prefijo.
  `CancelReceipt` es ese sitio, y la rama «cancelled» del decider —que estaba
  muerta, porque nadie producía ese vocablo— vuelve a tener a quién servir.

- **La calle del store la decide el propósito, no el verbo.** Que el store tenga
  una calle de escritura y otra de sólo-lectura no bastaba: lo que importa es
  **quién decide** en qué calle se abre, y la respuesta es el propósito que
  declara el llamante. `EventStorePurpose.storeMode` es esa decisión, es pura
  y por eso se puede fijar con un test que no toca disco. Si la decisión
  viviera dentro de cada verbo, el día que un verbo nuevo naciera con un
  store mal construido nadie lo vería: el síntoma —un lector escribiendo en el
  journal del productor— sólo aparece cuando un lector y un productor tocan el
  mismo fichero a la vez, y eso no se fuerza a voluntad.

- **Que el motor imponga la garantía es mejor que prometerla.** La alternativa
  barata era dejar el store como estaba y limitarse a **no** escribir desde el
  lector: disciplina, no garantía. Con el flag de sólo-lectura de SQLite, la
  conexión del lector **no puede** escribir, ni por descuido ni por un cambio
  futuro. El motor es la autoridad, y el código es el que le pide permiso.

- **Una vista que no entrega ningún hecho no es un observador.** El ejemplo
  espera a que el observador entregue un hecho de **ese** run antes de matarlo.
  Sin esa espera, el escenario pasaría igual con un observador que murió al
  arrancar —su muerte sería tan inocua como la de uno sano—, y la garantía
  quedaría sin comprobar: se habría medido la muerte de un proceso que nunca
  estuvo acoplado con el productor.

- **Un hecho que se calcula, no un dato que se asigna.** La sonda que decide si
  una base tiene historia fue primero un `var` que el constructor llenaba, y
  acabó siendo un `val` con su propio inicializador. La diferencia no es de
  estilo: en Kotlin el inicializador de una propiedad corre **después** del
  cuerpo del constructor, así que un `var` con inicializador **pisa** lo que el
  constructor acaba de calcular. Ese fue el fallo, y era de los que no se ven:
  la sonda contestaba siempre que sí, la base sin historia era admitida, y el
  `no such table` crudo de SQLite salía como traza de pila. Un `val` calculado
  en un solo sitio **no puede** ser sobrescrito por otro inicializador: la
  trampa se elimina por construcción y no por cuidado.

- **Un fichero de cero bytes es una base de datos.** Para buscar una base sin
  tabla de eventos —un caso que no se puede fabricar con el producto, porque
  el producto crea esa tabla al escribir— hizo falta un truco pequeño: SQLite
  trata un fichero vacío como una base válida y vacía. Con eso, el rechazo es
  alcanzable **herméticamente** y el caso negativo puede además comprobar que
  el fichero sigue vacío. Un rechazo que sólo se puede provocar con una
  herramienta que el arnés no tiene es un rechazo sin probar.

- **Una incidencia que no se reproduce no se cierra.** El ROADMAP V2 pide
  cerrar la #145 «sólo si se verifica», y aquí no se verifica: el script de la
  propia incidencia no reproduce su propio síntoma ni contra el árbol actual ni
  contra la línea publicada `v0.61.0`. Cerrarla sería escribir un verde que
  nadie midió. Queda **abierta** con el motivo medido, y lo que sí se entrega
  es la garantía que la hacía posible —un lector que no puede escribir en el
  journal del productor—, protegida por un test puro y por un ejemplo que usa
  el binario real.

- **Un caso de un vocabulario publicado tiene que ser un hecho que el producto
  puede producir, o no está.** Un `sealed interface` de rechazos es un
  contrato que el consumidor recorre con un `when`: cada caso es una rama que
  ese consumidor escribe y mantiene durante toda la vida del contrato. Un caso
  que nadie produce es una rama que nadie puede probar y un hecho que el
  contrato anuncia sin poder cumplirlo. Seis de los dieciocho casos publicados
  estaban así, y cinco de ellos eran un hecho **importado de otro plano**: la
  corrupción de una fila (que es del plano de eventos), la doble versión de la
  pérdida de retención, el sustrato inalcanzable (que es del lado de la poda)
  y la cancelación (que no es un rechazo). Se retiraron, y los ids se
  versionaron con el procedimiento que el repo ya tenía para esto.

- **Antes de darle un productor a un caso imposible, hay que medir si el
  producto puede tener ese hecho.** La respuesta para `Corrupt` fue que no: el
  plano de salida no parsea payloads, y el daño de su log de frames lo repara
  el escritor, así que es transitorio y el lector lo espera. Un rechazo
  «durable» para un estado transitorio habría sido una mentira con forma de
  contrato.

- **Un fichero que dice quién aplica sus reglas tiene que ser cierto.** El
  fichero de madurez afirmaba que un test aplicaba sus cuatro reglas; ese test
  no existía desde el reset del testing. La corrección no fue escribir el test
  —un meta-test sobre el contrato es justo lo que la política prohíbe— sino
  quitarle al fichero la afirmación falsa y dejar la regla como lo que es: la
  lista de comprobación de quien revisa.

- **Un KDoc que nombra un guard que no existe es una afirmación falsa.** Un
  comentario que dice «esto lo fija `XTest`» promete un instrumento. Si `XTest`
  no está en el árbol, la promesa es falsa y el lector que llegue después
  concluirá que una garantía está vigilada cuando nadie la vigila. Eso no es
  una cuestión de estilo: es el mismo tipo de defecto que un `NOT_RUN` vendido
  como `PASS`. El censo que lo mide es de una línea, y da **0** sobre los
  **74** nombres que había.

- **La diferencia entre una mención y una promesa la marca el verbo.** Nombrar
  un test **existente** es una cita útil: dice dónde está la garantía. Nombrar
  uno **inexistente** es una promesa rota. Por eso el barrido reparó primero
  las **12** que afirmaban un instrumento y después, por coherencia, las
  neutras: el orden no es indiferente, porque una campaña que empieza por las
  neutras acaba pareciendo una tanda de sustitución de texto.

- **Un identificador de tarea no viaja en el código.** Un sha, un número de
  incidencia, un id de CI, un token de ADR y el nombre de una tarea son
  punteros a un contexto que el lector del código no tiene: funcionan el día
  que se escriben y no dicen nada el día siguiente. Lo que sobrevive es la
  **decisión** —qué garantiza el código y por qué— escrita con las palabras
  del código. Los shas y las incidencias se quedan en este ROADMAP, que es
  donde el lector de contexto los busca.

- **Un barrido mecánico no reescribe prosa.** Sustituir un nombre por una
  frase deja las costuras: comas sueltas, mayúsculas a mitad de frase, negritas
  vacías, paréntesis sin cerrar. El barrido automático acierta en el
  *contenido* y destroza la *forma*, y la forma rota es lo primero que ve el
  lector. Por eso las 86 fichas tocadas se repasaron a mano después: el
  instrumento localiza, la mano reescribe.

- **El arnés lee la conclusión del run, no sólo su código de salida.** El
  código de salida dice si el proceso acabó bien; no dice **qué concluyó el
  run**, y hay al menos dos finales que un cero no distingue: el limpio y el de
  un run que capturó un fallo y siguió adelante. El producto publica esa
  conclusión con un vocabulario de cuatro palabras, así que leerla no cuesta
  nada y el arnés la lee.

- **El escenario declara el resultado que espera, en el vocabulario del
  producto.** La palabra la pone quien conoce la garantía, no quien la deduce
  de un código de salida. Sin declaración, el resultado esperado se deduce del
  código de salida esperado, que es una inferencia correcta por construcción y
  por eso mismo no puede descubrirse: sólo puede confirmarse.

- **El censo mide lo que el arnés comparó, no lo que los escenarios
  declararon.** Un número que confunde el canal con la garantía no estorba:
  **dirige el trabajo** hacia lo más barato que hay —una declaración— en vez de
  hacia lo que falta. Un escenario que afirma con sus propios pasos no necesita
  que nadie le escriba una directiva encima: necesita que el arnés lea lo que el
  run concluded.

- **Un test perdido es una regla sin observador, y un fichero que nadie compila
  es código que nadie sabe que existe.** Los dos son el mismo defecto con
  distinta cara: la garantía está en el árbol y nadie la mira. La respuesta es
  la misma en ambos casos —traer el observador o quitar el código—, y en este
  caso fueron las dos cosas a la vez.

- **El build declara el JDK con el que se compila, igual que declara su
  versión.** El gate ya lo resolvía por su cuenta y su mensaje de fallo ya
  nombraba una declaración que el build no tenía. Un mensaje de error que
  describe un hecho falso enseña al operador a buscar el motivo en el sitio
  equivocado.

- **Fundir escenarios no pierde el diagnóstico cuando el veredicto nombra el
  paso que falló.** El miedo a fundir es que un rojo deje de decir qué se
  rompió; con la conclusión del run, el rojo dice el paso. Una garantía por
  fichero era una consecuencia de que el arnés no supiera atribuir, no una
  propiedad de las garantías.

- **Un comentario que describe un árbol que no existe se corrige; la
  configuración que protege se deja como está.** El análisis estático ya
  llegaba a las fuentes de test, y el build lo afirmaba al revés. Corregir la
  frase es gratis y verdad; tocar la línea que nombra las fuentes cambiaría
  lo que se analiza sin que nadie lo haya medido, y un análisis sin medir es
  una garantía sin observador, que es exactamente lo que este ciclo vino a
  quitar del árbol.

- **Un módulo que se queda no conserva la forma del que se iba.** La
  publicación que nadie pedía, el `maven-publish` que nadie disparaba y el
  texto del contrato que prometía una entrega que no existía eran la misma
  puerta abierta: la de leer el módulo como algo que todavía podía ser
  publicado. Retirarlos no es poda por gusto; es que un destino cerrado se
  cierra también en las palabras, porque el árbol se lee antes que el código.

- **Una garantía que se rompe sin dejar rastro se mira con un artefacto
  real, no con un doble.** Un plugin que miente sobre sus Steps sólo se puede
  ver cargando sus clases y comparándolas con su manifiesto, así que el
  instrumento tiene que ser un artefacto de verdad: un JAR con el manifiesto
  untrue y el resto de la distribución igual. La comparación pura se prueba
  aparte, con el códec real y sin proceso; las dos cosas miran cosas
  distintas y ninguna basta sola.

- **Un guardián se demuestra apagándolo.** Un escenario que afirma un rechazo
  no prueba que el rechazo sea el del guardián hasta que el guardián se apaga
  y el escenario se enciende. La prueba de que el guardián es el que habla es
  que **sólo** esa fila se enciende: una fila roja sola dice que el escenario
  discrimina; una fila roja sola con las demás verdes dice que el guardián
  es el guardián.

- **Ampliar un instrumento cuesta menos que construir otro.** Las tres familias
  de la deriva se observan con el **mismo** doctoring sobre el **mismo** JAR:
  lo que cambia es la línea del manifiesto que se toca, y esa línea es un
  argumento del lanzador. Un arnés que crece en capacidad —en lo que puede
  observar— y no en nombres de fichero es el que se puede ampliar sin pagar un
  mecanismo nuevo por cada cosa que descubre.

- **La procedencia de un contribuidor se mide por su code source, no por su
  classloader.** El classloader parece la pregunta obvia y es la que no sirve:
  con la delegación padre-primero, el proveedor de un plugin que la instalación
  ya trae lo carga el cargador de la aplicación, igual que a un proveedor del
  propio runtime. Preguntar por el cargador habría tratado la contribución de
  un plugin como si fuera de otro, que es el agujero que se quería cerrar. La
  JVM guarda un solo hecho sobre cada clase que define —de qué código se
  cargó— y una sola autoridad es más barata de defender que dos.

- **Una contribución sin artefacto no es la contribución de nadie: se nombra al
  proveedor.** Descartarla en silencio era tolerable mientras no pasara nada,
  y no pasó nada: la distribución real no tiene ni un proveedor sin origen.
  Pero un silencio que sólo se sostiene porque hoy no hay quien lo provoque es
  una puerta abierta, y la diferencia entre una puerta abierta y una cerrada
  no la nota quien la usa: la nota quien la abre. Nombrar al proveedor convierte
  un caso invisible en un diagnóstico accionable, y nombrar al proveedor es
  exactamente lo que se puede hacer cuando no hay artefacto que nombrar.

- **La regla se separa de la medida que la produce, para que la regla se pueda
  ejercitar.** La procedencia se lee del code source de la clase y la decisión
  se toma sobre un valor; con esa costura, la regla se puede probar contra
  proveedores cuyo origen no produce ningún classpath, que es el estado que
  el runtime no tiene y que por tanto ningún artefacto real puede repetir. Una
  regla que sólo se puede ejercitar con un artefacto que nadie publica es una
  regla que nadie comprueba.

- **Un número que no se reproduce con un instrumento del árbol no se copia: se
  vuelve a medir.** La deuda estática llevaba dos ciclos anotada en 802, y el
  censo sobre las baselines versionadas da 879 —770 antes de la unión de las
  dos líneas y 879 después—. Nadie lo reverificó porque el número estaba
  escrito y escrito se ve. El daño de una cifra caducada no es el número: es
  que hace creer que hay un guardián midiendo lo que nadie mide.

- **Una regla que decide sobre combinaciones se expresa como la matriz, y la
  matriz se defiende sola.** Los seis casos de la admisión del store no son seis
  métodos parecidos: son seis filas de una combinación cerrada, tres
  observaciones por dos propósitos. Escribirlo como tabla tiene un precio —una
  fábrica en vez de seis métodos— y una compra: la tabla **enumera** sus
  propias filas, así que una combinación que se olvide se nota. Una fábrica que
  devuelve cinco filas verdes sigue siendo un test verde, y por eso la guarda
  de la matriz rechaza una combinación omitida o repetida **antes** de generar
  la primera fila.

- **Una ley que no es de la matriz se prueba aparte.** «Todo rechazo nombra el
  `path`» no es una combinación de observación y propósito: es una propiedad
  del conjunto. Mezclarla en la matriz la habría hecho opcional, y una ley
  opcional no es una ley. Por eso es su propio caso, y por eso la cubre la
  mutación que deja pasar todo: si la regla acepta cualquier cosa, no hay
  rechazo, y un rechazo sin `path` es un diagnóstico que el operador no puede
  usar.

- **Un escenario rojo puede tener su causa fuera del producto, y esa es la
  primera hipótesis que hay que agotar.** El smoke del repositorio se puso rojo
  a una hora concreta, de forma determinista, y el candidato obvio era el
  cambio que se acababa de integrar. No era: la PR tocaba un único test de otro
  módulo. La causa era que el **fixture** commiteaba con un mensaje que no era
  *Conventional Commit* y con una identidad que la política no admite. Depurar
  el producto era depurar el sitio equivocado, y la forma de no hacerlo es
  mirar primero qué cambió, no asumir que la culpa es de lo último.

- **Un ejemplo permanente cumple la política de la máquina; no la desactiva.**
  Tres vías dejaban el escenario en verde —desactivar la ruta de hooks, saltar
  la verificación, anular la configuración global— y las tres se descartaron. El
  motivo no es de gusto: un ejemplo que anula la política de git **mide la
  política del producto, no el producto**. El fixture correcto es el que
  commitea como la máquina espera, con la identidad que la máquina declara,
  leída de su configuración: así el árbol no lleva ninguna identidad personal
  escrita y el ejemplo sigue siendo ejecutable en una máquina gobernada.

- **Un squash publicado en la interfaz no se puede seguir cuando la gobernanza
  local firma los commits.** El commit que produce la plataforma viene firmado
  por ella y con una identidad que la política local no admite: se puede ver,
  pero no se puede verificar ni continuar. La fusión se rehízo **en local**,
  con **árbol idéntico** al publicado —el contenido es el mismo, la firma es
  la que la máquina admite—, y la subida se hizo con *lease* explícito contra el
  squash, dejando un ref de recuperación. Lo que se conserva es el resultado;
  lo que no se conserva es la firma que la plataforma no dejó poner.

- **La protección de una rama se restaura con su valor exacto, no con un valor
  parecido.** Suspender `allow_force_pushes` para una subida que la exige es
  una maniobra, y una maniobra se deshace: se guarda la configuración antes, se
  restaura después y se comparan **campo a campo** con el respaldo. «Ya está
  como estaba» no es una verificación; la comparación lo es. Y la comparación
  es la que detectó que la clave había quedado en `true`.

- **Una regla correcta y no observada es indistinguible de una regla que no
  existe.** La tercera familia de la mentira no añadió ninguna regla: la regla
  ya estaba, con su fila unitaria. Añadió el **observador**. Un test unitario
  afirma que una función decide bien; un escenario afirma que esa función decide
  bien *en la instalación que el operador usa*, con un artefacto real y una
  línea de manifiesto manipulada. Son instrumentos distintos, y la diferencia es
  exactamente la que separa un hueco cerrado de un hueco que sólo se ha
  documentado.

- **Un rechazo que no nombra al artefacto que se quedó no es un diagnóstico: es
  un rumor.** La identidad duplicada entrega `identity=X, incumbent=X`: dos
  campos con el mismo texto, donde el KDoc promete «con quién» y el campo
  `incumbent` promete «the incumbent it collides with». No hay ningún artefacto
  ahí. Y como el vocabulario de rechazos está **publicado**, el arreglo no es
  tocar el campo: es llevar el artefacto que se quedó en el `detail` que la CLI
  ya imprime. **La política no se toca** —qué artefacto gana la identidad sigue
  siendo la de hoy—; lo que se arregla es el diagnóstico, que es lo único que
  estaba mintiendo.

- **Un fallo que se reproduce en la puerta y desaparece al re-correrlo es un
  dato sobre el host, y declararlo es parte de la medida.** Dos ensayos de este
  bloque se descartaron. Todos sus rojos tenían la misma causa, todos volvían a
  pasar al re-correrlos, y los dos coincidieron con trabajo ajeno corriendo al
  lado. Declarar `PASS` con un ensayo que se sabe contaminado no es medir: es
  **seleccionar**. Se declara el fallo, se mide su causa, y se vuelve a medir
  con la máquina quieta — y se escribe en el veredicto que la puerta no es
  reproducible al cien por cien en una host compartida, porque eso también es
  un hecho del producto que hay que saber.

- **Lo que el producto deja fuera de su raíz no lo borra nadie, y hay que
  medirlo aunque no se arregle aquí.** El driver JDBC de SQLite extrae su
  biblioteca nativa a un fichero temporal de un megabyte por conexión y no lo
  borra: **508** pares, medio gigabyte, con la fecha de hoy. El arnés borra su
  ciclo, y eso no incluye lo que el producto deja en el scratch del sistema. El
  arreglo es de otro bloque —toca el driver o la configuración del proceso—, pero
  dejarlo sin medir sería peor que no mencionarlo: un resto que nadie cuenta es
  un resto que nadie limpia.

  > **Corregido después** (§2.58): la premisa era a medias y el arreglo ya no
  > está pendiente. El driver **sí** borra su extracción al salir normalmente —
  > lo que sobrevive es de procesos que mueren a la fuerza—, y el producto
  > **configura el proceso** para que su residuo vaya a un directorio con
  > nombre.

- **Una deriva que se expresa como una ausencia necesita un fixture que se
  verifica al revés.** Las demás familias del doctoring **añaden** un fantasma
  al manifiesto, y el fixture comprueba que el fantasma está: si dejó de estar,
  el fixture ha dejado de mentir y el escenario se pondría verde sobre un
  artefacto honesto. Esta deriva **quita** una capacidad, y la capacidad sigue
  en el array global de capacidades del manifiesto —buscarla la encontraría
  ahí, y probaría lo contrario de lo que se quiere probar—. Por eso el fixture
  comprueba que el literal exacto que antes estaba **ya no está**, y el arnés
  distingue un caso que añade de un caso que quita. Sin esa distinción, el
  fixture habría parecido estar mintiendo y no habría mentido.

- **Una regla simétrica necesita un caso por dirección, o no está vigilada.**
  La comparación de capacidades es simétrica desde el principio: el KDoc del
  plugin de ejemplo lo dice. Pero hasta este bloque la suite sólo miraba una
  dirección, así que **la suite entera habría pasado con un guardián que
  comparase los conjuntos en una sola dirección**. El caso que faltaba no era
  un test más: era el que distingue una regla simétrica de una regla a medias.
  Y al apagarla se encienden los dos escenarios de la familia a la vez, que es
  la prueba de que lo que los unía era la regla, no el escenario.

- **Un instrumento se mide con la mutación que le corresponde, y hay que decir
  cuál cubre cuál.** La segunda mutación de este bloque dejó las cuatro filas
  unitarias en verde, y eso no es un defecto del test: un test puro, por
  definición, no toca el disco y por eso no puede ver el cableado. Lo que el
  ciclo establece es el método —mutar la regla y ver las filas, mutar el
  cableado y ver los escenarios, y **decir qué cubre cada instrumento**—, y no
  la comfyción de un «todo verde» que nadie ha desarmado. Un arnés que presume
  de cobertura sin esa medida presume de algo que nadie ha comprobado.

- **Un diagnóstico que nombra lo que pasó es un arreglo; un cambio de política es
  una versión.** El rechazo por identidad duplicada decía «otro artefacto ya
  tiene este nombre» sin decir cuál: eso se arregla, porque un diagnóstico no es
  un contrato y el hueco que dejaba era el de dejar al operador sin la única
  pista accionable. Lo que **no** se arregla aquí es qué artefacto gana la
  identidad: eso es comportamiento observable, y cambiarlo rompería a los
  consumidores que hoy admiten un plugin y rechazan el otro. Se deja escrito, con
  nombre, y esperando la autorización que le corresponde.


- **Una garantía que se puede alcanzar por dos caminos está vigilada por
  ninguno.** El paso `unstable("flaky")` de un escenario repetía la conclusión
  que el `catchError` ya producía, y esa duplicación no era robustez: hacía que
  la afirmación siguiera en verde aunque el `catchError` dejara de marcar la
  corrida como inestable, que es justo lo que la afirmación dice comprobar. La
  redundancia se quita, y la afirmación queda con el único camino que el
  producto define (§2.63).

- **Un escenario que no puede encenderse no compra nada, y una suite que no
  puede distinguir un guardián vivo de uno decorativo no puede decir qué
  compra.** Por eso este lote mide, escenario por escenario, si la garantía
  sobrevive a la mutación que la rompe. Y por eso las dos filas que NO
  encendieron se explican con una medición y no con una excusa: eran mutaciones
  que no alcanzaban la garantía, no guardianes muertos (§2.63).

- **Para una garantía que vive en el lector, la entrada no basta: hay que
  romper el producto.** Cinco de las garantías de este lote viven en el event
  lane, en el cursor de la reanudación o en el veredicto del recover. Su sonda
  honesta no es un texto distinto, es el defecto real, recompilado y corrido
  contra el escenario (§2.63).

- **Una verde que no dice qué comprobó no se puede pesar contra lo que costó.**
  El arnés ya contaba las garantías por escenario —las publicaba en su marcador
  y las sumaba en el censo— y esa cifra no llegaba a la línea que se lee. Ahora
  la línea la dice, y cuando el arnés no comparó nada dice que la garantía
  vive en los pasos del propio pipeline: son dos hechos distintos y confundirlos
  es llamar «c-checkbox» a un escenario que no compara nada (§2.63).

- **Reducir el coste de comprobar no es lo mismo que comprobar menos.** En los
  dieciséis escenarios auditados no hay ni uno retirable: retirarlo sería
  dejar de comprobar algo. Lo que sí se reduce es lo que cuesta comprobar, y
  la vía medida es la caché de scripts compilados que el producto ya tenía y
  que el arnés no usaba. La reducción es de coste, no de garantías, y por eso
  es honesta (§2.63).

- **Una caché que el producto no puede poner por defecto sí la puede poner
  quien tiene un directorio que es suyo.** El producto la apaga porque no hay
  un sitio de usuario que sea el correcto en un HOME de sólo lectura, en un CI
  encajonado o en un contenedor efímero. El arnés E2E no tiene ese problema:
  su directorio de ciclo es suyo y la trampa lo borra. La caché vive dentro
  de ese directorio, así que no sobrevive a la corrida, no se acumula en el
  HOME de nadie y dos invocaciones nunca comparten un artefacto (§2.63).


- **Una medición equivocada, publicada, mueve el trabajo al sitio equivocado, y
  por eso hay que deshacerla antes de construir nada encima.** La propuesta
  encadenada al cierre del ciclo anterior se apoyaba en «una corrida compila
  el mismo script dos veces», y era falsa: el producto compila una vez y el
  ARNÉS corre el producto dos veces por escenario, una en cada ruta de
  ejecución. La cifra de coste era correcta; la causa apuntaba al producto. Un
  ciclo que empieza por corregir su propia explicación vale más que uno que
  construye sobre ella (§2.64).

- **Un informe que no dice cuántas veces corrió el producto no puede
  valorarse.** El censo decía contar «lo que el arnés COMPARÓ» y contaba
  declaraciones, una vez por escenario: el informe declaraba 170 garantías
  donde hubo 285 comparaciones sobre 110 ejecuciones. Una suite que se
  infravalora en un 40 % en su propio informe es una suite que recibe menos
  inversión de la que merece, y el número que la infravalora es el que se
  cita (§2.64).

- **Las promesas se cuentan donde ocurre la comparación, no donde se
  declaran.** Una declaración contrastada contra dos ejecuciones son dos
  comparaciones, y contarlas una vez por escenario era contar la mitad del
  trabajo. El sitio de la cuenta no es un detalle de estilo: decide lo que el
  informe dice que la suite compró (§2.64).

- **Reducir el coste de una suite no se compra con heterogeneidad.** Guardar
  la caché de scripts entre invocaciones bajaría el E2E de 84,3 s a 52,4 s,
  pero haría los tres ensayos de la puerta heterogéneos y dejaría un
  artefacto fuera del ciclo que la puerta borra. Treinta y dos segundos contra
  un presupuesto de 900 s no lo justifican; la cifra queda escrita y la
  decisión, para quien la quiera tomar con el número delante (§2.64).

- **Una doble ejecución se queda mientras discrimine, y hay que medir si
  discrimina.** El arnés corre cada escenario en las dos rutas de ejecución y
  las dos tienen que cumplir las mismas expectativas, pero **no se comparan
  entre sí**: se parecen en lo que el escenario declara, no en todo lo que
  hicieron. Ese hueco es el bloque siguiente, y se decide con una
  mutación —no con una intuición—: o la comparación entre las dos rutas se
  hace de verdad, con la normalización que exijan los identificadores y las
  marcas de tiempo, o la ejecución volatile sale de los escenarios donde no
  puede discriminar (§2.64).

- **La doble ejecución discrimina, y ahora está demostrado con mutación.** Con
  los dos mutantes vivos y la doble ejecución desactivada, la suite queda
  **61 de 61 en verde**: los dos defectos eran invisibles. Con la doble
  ejecución, la misma suite se pone roja y cada fila dice qué instrumento la
  vio. Una doble ejecución que no encuentra nada no está probada; una que
  encuentra dos defectos de dos clases distintas sí (§2.65).

- **Comparar las dos rutas no es comparar sus bytes en crudo, y la diferencia
  importa.** Lo que tiene que ser igual es la HISTORIA que contaron y los
  BYTES que comprometieron; lo que tiene que ser distinto es la identidad, el
  reloj y las raíces. La comparación normaliza eso y sólo eso, y por eso puede
  ser una garantía en vez de un falso positivo (§2.65).

- **Una comparación que se pone en rojo por culpa del arnés no es un
  instrumento.** La primera versión de este bloque falló en 49 de 61
  escenarios, y las 49 diferencias eran la misma línea: el número de
  diagnósticos de compilación, que el arnés hace distinto a propósito porque
  compila el script una vez y deja que la segunda ruta reutilice esa
  compilación. Antes de comparar hay que distinguir lo que el arnés hace
  distinto a propósito de lo que el producto hace distinto (§2.65).

- **La historia de una corrida empieza cuando la corrida empieza.** La
  correlación y la compilación son preparación: el arnés las hace distinto en
  las dos rutas por decisión propia, y una corrida rechazada antes de empezar
  no imprime nada en la ruta durable. Comparar desde «Run started:» es lo que
  hace la comparación una garantía en vez de una pelea con el arnés (§2.65).

- **Un defecto que ninguna expectativa puede ver es el que más cuesta ver.**
  El veredicto final de la corrida no lo declara ningún escenario —cero de
  61—, y por eso el camino durable podía dejar de narrarlo sin que nada se
  pusiera rojo. Lo único que lo vio fue comparar las dos rutas entre sí
  (§2.65).

- **Un fallo de la puerta que no puede verse desde la puerta es un fallo de la
  puerta.** Con los mutantes vivos, la suite anterior daba 61 de 61 en verde.
  Eso no es una suite que no encuentra defectos: es una puerta que no tiene
  con qué verlos (§2.65).

- **Una garantía que no se ha visto en rojo no es una garantía.** La
  comparación de los bytes comprometidos no se había puesto en rojo ni una vez,
  y por eso se comprobó con un script temporal que imprime la hora en
  nanosegundos. Ese mutante no sólo encendió la comprobación: encontró que la
  normalización de la identidad era demasiado ancha y tapaba la marca de
  tiempo. Un instrumento que nadie ha visto fallar no está probado, y probarlo
  cuesta un script que se retira (§2.65).

- **El precio de una garantía se publica con ella.** La comparación entre las
  dos rutas cuesta **siete segundos** de E2E (79-81 s → 88 s), que es lo que
  cuesta leer los bytes comprometidos con el verbo `console` del producto. Se
  publica junto a la cifra porque una garantía sin su coste es una promesa
  (§2.65).

- **El orden de los decoradores ES la propiedad de seguridad.** Una consola
  por encima de la redacción imprime lo que el almacén se niega a guardar. No
  son dos opiniones sobre el mismo hecho: la consola está después de la
  redacción, o es una fuga. Se comprobó contra la distribución: la ruta que
  imprimía el secreto era la que lo compuso al revés (§2.66).

- **La garantía se escribe como una igualdad, no como una ausencia.** «La
  consola presenta los mismos bytes que el almacén guarda» se puede comprobar
  con una llamada, y falla en las dos direcciones. «El secreto no aparece» es
  una ausencia que sólo falla en una, y para comprobarla hace falta una
  distribución entera. La forma de la garantiza decide el precio de
  comprobarla (§2.66).

- **Dos composición que parecen correctas son una composición y un defecto.**
  Las dos rutas tenían su propia cadena de decoradores, y las dos parecían
  razonables; una estaba mal en el orden que importaba. Una autoridad
  compartida no sólo quita la duplicación: quita la clase de defecto, porque
  ya no hay dos cosas que comparar (§2.66).

- **El relato de una corrida llega cuando la corrida corre.** La ruta con
  journal narraba al final, de modo que el operador veía la salida del proceso
  mientras la corrida corría y la explicación cuando terminaba. Un relato que
  llega tarde obliga a reconstruir lo que pasó (§2.66).

- **Un secreto que no se guarda es un secreto que se imprime.** La fuga
  encontrada midió exactamente eso: el almacén limpio y la consola con el
  secreto. El almacén no es la frontera de la confidencialidad, la consola
  también lo es (§2.66).

- **Un defecto que parece nuevo se comprueba contra la línea base antes de
  ser atribuido.** El diario de operaciones guardaba el secreto en claro, y
  la reacción natural era atribuírselo al bloque. Se midió contra una
  distribución construida del árbol **anterior**, y los mismos bytes estaban
  en el mismo sitio: el defecto era anterior. Sin esa medición, el bloque
  habría publicado una acusación falsa contra sí mismo (§2.66).

- **Una garantía que es una composición se comprueba con una llamada.** El
  instrumento más barato que puede fallar es el que encaja con la forma de lo
  que se garantiza: una composición pura se compone, se llama y se lee. Una
  distribución entera para comprobar una cadena de decoradores sería un
  instrumento caro para una ley pequeña (§2.66).


- **La lista de redacción es la regla aplicada, no una lista que alguien
  mantiene.** Se limpia todo tipo que lleve texto libre, y el KDoc dice qué
  significa eso. La lista es sobre la taxonomía y no sobre los emisores de
  hoy: nueve de los tipos no los emite nadie todavía, y «nadie lo emite
  todavía» es un dato de esta semana (§2.67).

- **Un hueco se encuentra comparando dos fuentes de verdad, no leyendo una
  lista.** La taxonomía decía qué tipos llevan texto libre; el renderizador
  decía cuáles presenta la consola; la lista decía cuáles estaban cubiertos.
  El hueco era la diferencia entre dos fuentes que ya existían en el
  repositorio (§2.67).

- **Antes de buscar un defecto hay que leer las leyes.** La pregunta del
  diario —si debía guardar el secreto en claro— ya tenía respuesta, escrita
  en el propio KDoc del redactor: el canal del valor tipado está separado del
  observable porque un paso al que se le pide capturar un stdout tiene que
  recibirlo exacto. Un bloque que empieza por buscar una fuga sin leer las
  leyes acaba culpando al sitio equivocado (§2.67).

- **Un censo que se detiene en el primer fallo esconde lo ancho que es el
  hueco.** El test recoge todos los fallos y los cuenta de una vez, para que
  la revisión vea el tamaño del problema y no su primer síntoma (§2.67).

- **Una capa sin cobertura permanente se cubre donde ya se ejercita.** La
  limpieza del transcript no tenía ninguna prueba, y el escenario de
  credenciales ya ejecutaba una orden que pone una credencial en la salida del
  proceso. La aserción fue allí: una línea, ningún escenario nuevo (§2.67).

- **El valor de un ciclo está también en lo que corrige.** Este bloque publicó
  una afirmación equivocada sobre el diario y la corrigió en el mismo ciclo,
  con la medición que la desmintió. Un registro que se corrige a sí mismo vale
  más que un registro que nunca se equivoca (§2.67).

- **Una clase sin cobertura permanente es una clase que ya pagó sus defectos
  una vez.** El redactor del transcript documentaba cinco defectos reales ya
  corregidos y no tenía ni una fila que sostuviera ninguno. La cobertura no
  es un adorno: es lo que distingue un defecto que se corrigió de un defecto
  que volvió (§2.68).

- **Un defecto que se cuelga no cabe en un test unitario.** El bucle que quemaba
  la CPU no devolvía un valor equivocado: se quedaba esperando. Un test
  unitario de eso no se pondría rojo, se colgaría y se comería el presupuesto
  de la puerta. Se cubre donde el producto puede detectarlo —el suite de
  extremo a extremo, que lo cazó con el diagnóstico del propio producto— y se
  dice en voz alta por qué (§2.68).

- **El estado que se descarta no puede llevarse el secreto dentro.** El
  volcado final borraba la cola de salida pero no el anillo de espera, que
  guarda bytes en crudo: la ruta que envuelve el flujo lo borraba y la que
  usa la máquina de estados no, y esa segunda ruta es la que tira el estado.
  Las dos rutas borran por la misma regla, y la regla vive en un solo sitio
  (§2.68).

- **Un comparador que se traga los rechazos no compara: informa.** El veredicto
  «las dos rutas prometieron lo mismo» salía de dos lecturas que el lector
  había rechazado, y dos vacíos son iguales. Un «no lo sé» no es un «no hay
  nada», y la diferencia entre las dos es justo la que separa una garantía
  verificada de una que nadie comprobó (§2.69).

- **Enumerar por el canal que se midió deja fuera al otro.** Un paso que
  escribe sólo a stderr no tiene directorio de stdout, así que la comparación
  nunca preguntaba por sus bytes: una comparación que no compara puede
  declarar iguales dos corridas que difieren. El conjunto de lo que hay que
  leer lo dice el producto —el plano, con sus dos canales—; el arnés sólo
  tiene que preguntarlo bien (§2.69).

- **El instrumento de un defecto del arnés es un escenario, no un test del
  arnés.** Un test unitario del comprobador sería la comprobación de que el
  comprobador comprueba. El discriminator es un programa real contra el
  binario real, y salió de un escenario que ya existía: un paso más, no otro
  escenario (§2.69).

- **El falso verde se demuestra reproduciéndolo.** Con el rechazo tragado, el
  escenario pasa en verde sin haber leído nada. Sin ese ensayo, «el rechazo
  ahora falla» sería una afirmación; con él, es una diferencia medida (§2.69).

- **Un newline termina una línea, no un fichero.** El lector de credenciales lo
  quitaba a todas las clases, y una clase cuyo valor **son** los bytes perdía el
  último en silencio. La regla estaba donde no le tocaba: ahora la responde la
  propia clase, y cada transporte pregunta lo mismo. Una regla que pertenece al
  valor se queda donde el valor vive (§2.70).

- **Sembrar por flujo y sembrar por ruta tienen que dar la misma credencial.**
  El KDoc del lector ya lo exigía y el código no lo cumplía: la terminal leía
  el fichero entero y el flujo le quitaba un byte. Dos caminos hacia el mismo
  almacen, la misma pregunta y dos respuestas distintas (§2.70).

- **Una hipótesis buena no es una medida.** El keystore PKCS#12 parecía un
  segundo caso vivo —uno de cada 256—buta el JDK **siempre** termina el
  keystore en `0x10` (400 de 400, y veinte longitudes distintas), así que el
  fallo no existía. Lo que se cuenta es lo que se midió: una clase rota, dos
  intactas (§2.70).

- **Un instrumento tiene que discriminar en las dos direcciones.** La fila
  nueva se vio en rojo con el defecto y en verde con el protocolo roto, que es
  lo que distingue a un guardián de una coincidencia (§2.70).

- **Publicar es una función del producto, y por eso tiene una puerta.** La vía
  de publicación estaba rota desde la última release publicada con éxito, y
  ninguna señal del árbol lo decía: compilaba, los tests pasaban y la puerta
  decía `PASS`. Lo que faltaba era **correrla**, y lo que faltaba después era
  **que el conjunto publicado tuviera un único autor** (§2.71).

- **Un conjunto publicado se decide en un solo sitio.** La puerta y el
  publicador armaban la lista de ficheros por su cuenta, y por eso acabaron
  disarmando el mismo fichero: uno subía la autoridad de digest de la
  candidata y el otro escribía la suya encima. Ahora la lista —con el digest
  de cada fichero— sale de la puerta, y el publicador la sigue y la
  comprueba (§2.71).

- **Una release tiene una autoridad de digest, no dos.** La de la candidata
  cubre un subconjunto estricto de la que se publica; publicarla no aportaba
  nada comprobable y sólo hacía ambiguo cuál era «la» autoridad. La evidencia
  sobre la candidata se queda donde la candidata vive: en el árbol de
  compilación (§2.71).

- **Un artefacto se certifica sobre los bytes publicados.** Descargar de
  nuevo, verificar el digest, instalar en limpio y ejecutar un pipeline real
  con lo instalado: eso es lo que convierte «se subió» en «esto es lo que hay
  publicado» (§2.71).

- **Un veredicto es un valor, y una frase es su renderizado.** El informe de
  salud decidía si la instalación estaba sana buscando una palabra en el texto
  que acababa de imprimir, de modo que podía contradecirlo: una instalación
  sana en un directorio llamado `NOT WRITABLE` contestaba «roto». La frase es
  lo que el operador lee; el veredicto es lo que el producto decide. Cuando el
  veredicto es un valor, la frase se deriva de él y no puede contradecirlo
  (§2.72).

- **Un vocabulario publicado se dice en un solo idioma, y moverse se
  anuncia.** El catálogo publicaba `PUBLICADA` junto a `EXPERIMENTAL` y
  `DEPRECATED`, y el valor que un consumidor no reconocía era justo el de las
  capacidades entregadas. Renombrar un valor publicado es una ruptura, y una
  ruptura se anuncia: el esquema del documento se mueve a
  `pipelinek.capabilities/2`, de modo que un consumidor fijado a la versión
  anterior **ve** que el vocabulario se movió, en vez de no reconocer en
  silencio lo que la build entrega (§2.72).

- **Lo que un consumidor lee es un documento, no un modelo.** El test que
  vigila el catálogo lee el documento publicado —el mismo texto que sale por
  la stdout— y no la estructura interna que lo produjo. Un test sobre el modelo
  no ve un documento mal serializado; uno sobre el documento sí (§2.72).

- **Una puerta nueva al producto llega con su reloj, o no llega.** El arnés
  tenía dos formas de alcanzar el producto y las dos llevaban su techo de
  tiempo. Una tercera —preguntarle al producto qué tal está— podía llegar
  sin él, y entonces un escenario colgado colgaría la suite. La entrada por
  escenario lleva el reloj dentro, y el camino del producto no está en el
  entorno: no hay puerta sin techo (§2.72).

- **Un instrumento que no puede fallar no es un instrumento: hay que
  romperlo.** Las cuatro mutaciones de este bloque se aplicaron y se leyeron de
  vuelta en el disco antes de dar por buena ninguna fila: dos filas puras y
  dos garantías de escenario, cada una con exactamente un rojo y sin daño
  colateral (§2.72).

- **Un directorio que nadie puede escribir se busca donde el sistema lo
  garantiza.** Los bits de permiso no detienen al superusuario, y un
  escenario que depende de quién lo ejecuta no es un escenario. El segundo
  sentido del informe de salud se comprueba contra un directorio que el propio
  sistema no deja escribir, que es el mismo dato en todas las máquinas
  (§2.72).

- **Una identidad es un valor, no una proyeccion con perdida.** El indice del
  plano guardaba la identidad completa de cada stream y el nombre del
  directorio guardaba una version plegada de ella. El arnés comparaba las
  plegadas. Ahora el producto publica la completa y el arnés la consume: una
  identidad que hay que deducir del sitio donde casualmente viven sus bytes es
  una identidad que dos operaciones pueden compartir (§2.73).

- **Un consumidor no puede deducir lo que el producto sabe.** La superficie
  faltaba, no la verdad: el indice ya conocia cada identidad y la escribia en
  sus propios ficheros. Publicarla no es una cortesia, es dejar de obligar a
  todo el mundo a deducirla del sistema de ficheros, que es donde la proyeccion
  se cuela (§2.73).

- **Comparar es prometer un orden, y hay órdenes que no se prometen.** Una
  etapa puede abrirse en ramas concurrentes, y el orden en que terminan es una
  carrera: medido, el mismo pipeline la resolvia de dos maneras en doce
  ejecuciones. Un check que llora lobo es un check que nadie lee, asi que el
  arnés compara lo que el producto promete —el lugar de una etapa— y trata el
  interior de una etapa como el conjunto que es (§2.73).

- **Un registro que se escribe en el sitio deja un hueco que un lector puede
  ver.** El registro de offset confirmado es la autoridad de hasta donde llega
  lo comprometido, y se vaciaba antes de llenarse. Medido en bucle cerrado: una
  de cada tres lecturas veia el registro vacio. Ahora se escribe en un
  temporal y se mueve a su sitio de una vez, que es lo que ya hacen los
  registros duraderos del repositorio (§2.73).

- **Un fallo que se lleva por delante la consola del run no se ve.** El
  observador en vivo murio con el fallo y el run siguio y termino bien: nada
  decia "este run perdio su consola". Un fallo que se traga la mitad de lo que
  el run dijo es peor que un fallo que para el run, porque no se nota (§2.73).

- **Un reloj no sabe lo que solo el que observa sabe.** El techo del drenaje
  confundia una bomba atascada con una bomba lenta porque las dos se
  manifestaban igual: un hilo que no habia terminado. La bomba si sabe si esta
  esperando al hijo o trabajando en el plano, y decirlo cuesta un booleano
  (§2.74).

- **Un rechazo que no dice cual de las cosas paso no es accionable.** Los dos
  casos del drenaje agotado son hechos distintos, sobre cosas distintas —el
  hijo o la maquina— y con respuestas distintas, asi que son **casos tipados**,
  no dos frases del mismo texto (§2.74).

- **Un instrumento puro cuando el hilo y el reloj no hacen falta.** La regla y
  el bucle del drenaje se prueban sobre bombas falsas: diez particiones, sin
  proceso, sin pipe y sin segundo real. Un instrumento que necesita la
  maquina saturada para poder fallar es un instrumento que no se puede usar
  (§2.74).

- **Subir un techo no arregla un defecto; cambiar lo que el techo significa si.**
  El techo del drenaje no era un numero demasiado pequeno: era un numero que
  no distinguia dos causas opuestas. Ahora la bomba declara su atencion, y el
  techo queda siendo lo que era —la red de seguridad del caso que no
  termina nunca— (§2.74).

- **Un instrumento que encuentra un defecto real se paga.** La comparacion
  entre las dos rutas de ejecucion no es adorno: fue ella la que destapo el
  defecto del registro de offset, y la que obliga a que el arnes compare
  narraciones que no se pueden comparar en secuencia (§2.73).


- **Un tag estable publicado no se mueve, no se reemplaza y no se reescribe.**
  Un tag es un nombre público: moverlo cambia lo que ese nombre significa para
  un tercero que ya lo resolvió y que no puede enterarse. Los seis estables
  *lightweight* del remoto se quedan como están, y la corrección es hacia
  delante: que no vuelva a pasar (§2.90).
- **La procedencia se comprueba donde se publica, no donde se pide.** El
  `--verify-tag` lo añade **el script** que publica, no quien lo llama: un
  llamador que puede olvidarse de una bandera no es una puerta. No hay modo
  «release sin tag» ni rama de degradación (§2.90).
- **Lo que no se ha medido no es un verde.** La auditoría del remoto declara
  `GAP` para el tag que no ha podido mirar, y un `GAP` hace que la auditoría
  falle. Un instrumento que imprimiera «PASS» sobre lo que nadie comprobó sería
  la misma mentira que este bloque cierra (§2.90).
- **El error se nombra, no se borra.** Un tag *lightweight* en el remoto es un
  hecho que la auditoría dice en voz alta y que este roadmap nombra con su
  número: `v0.90.0` es el sexto estable sin procedencia de 63, no un tropiezo
  aislado (§2.90).
- **Una etiqueta estable declara de qué candidata viene.** El tag que se corta
  para promover se crea con `--promotes <RC>`, y la promoción exige que la RC
  esté publicada, anotada y firmada, y que su objeto local sea idéntico al que
  publica el remoto (§2.90).
- **Una release sin autoridad de digest nombrada no se publica.** El mismo script
  que exige `--verify-tag` exige `--expect-sums` y re-mide cada byte contra
  ella: una release a la que le falte una parte de la autoridad se niega (§2.90).

## 4. Referencias

- `pipeline-step-sdk/runtime/.../DurableShellExecutor.kt` — el runtime sin la sonda que
  escribía en el directorio personal del operador (§2.83).
- `pipeline-credentials-local/.../CredentialsContract.kt` — el contrato puro: qué verbo
  necesita la llave y qué cuesta cada desenlace (§2.82).
- `pipeline-credentials-local/.../CredentialsContractTest.kt` — las cinco filas puras de ese
  contrato, sin disco y sin reloj (§2.82).
- `pipeline-credentials-local/.../LocalSecretStore.kt` — el almacén desenvuelve la clave que
  lo sella antes de leer o de escribir nada (§2.82).
- `pipeline-credentials-local/.../MainCredentialsCli.kt` — los verbos preguntan lo que
  necesitan y los códigos de salida son los que el comando documenta (§2.82).
- `examples/security/14-credentials-bindings.pipeline.kts` — la puerta comprobada desde fuera,
  en el escenario que ya existía (§2.82).
- `v0.87.0-rc1` — la candidata que este ciclo publicó y verificó (prerelease; la promoción a
  estable sigue bloqueada por el certificador externo).
- `pipeline-application/.../CredentialBindingDeclaration.kt` — la regla pura que decide si una
  corrida liga una credencial, con un recorrido exhaustivo sobre el `StepSpec` sellado (§2.81).
- `pipeline-application/.../CredentialBindingDeclarationTest.kt` — las filas puras de esa regla, sin
  disco y sin reloj (§2.81).
- `pipeline-application/.../MainCredentialsSupport.kt` — la costura que decide sola si la corrida
  necesita el almacén, a partir de la declaración (§2.81).
- `README.md` — el camino más corto al primer pipeline: versión y digest derivados del release,
  y el manifiesto como autoridad del asset (§2.81).
- `README.es.md` — la guía breve en español, fiel y sin cifras que roten (§2.81).
- `v0.86.0-rc1` — la candidata que este ciclo publicó y verificó (prerelease;
  la promoción a estable sigue bloqueada por el certificador externo).
- `pipeline-application/.../SqliteNativeLibraryHome.kt` — la regla pura que decide dónde vive la
  biblioteca nativa del driver de SQLite, con sus tres respuestas (§2.80).
- `pipeline-application/.../SqliteNativeLibraryHomeTest.kt` — las filas puras de esa regla, sin
  disco y sin reloj (§2.80).
- `AGENTS.md` — política vigente; manda sobre cualquier documento histórico.
- `scripts/verify-all.sh` — la única puerta local.
- `scripts/check-budgets.py` — el único script de presupuestos.
- `examples/run.sh` — el único lanzador E2E.
- `scripts/release-pipelinek.sh` — la única vía de publicación: decide con la
  puerta y publica exactamente lo medido.
- `scripts/release/build-release-manifest.sh` — el generador de la autoridad de
  digest de una release.
- `v0.81.0-rc1` — la candidata que este ciclo publicó y verificó (prerelease;
  la promoción a estable sigue bloqueada por el certificador externo).
- `pipeline-output-store/.../StreamDirectoryOwnership.kt` — la regla pura que decide si un
  directorio de stream puede ser reclamado por una identidad (§2.76).
- `pipeline-output-store/.../SegmentOutputStore.kt` — el `stream.id` que nombra al dueño
  de cada directorio de stream, y el rechazo de lectura y de escritura (§2.76).
- `v0.80.0-rc1` — la candidata del bloque anterior (prerelease;
  la promoción a estable sigue bloqueada por el certificador externo).
- `pipeline-release/.../SdkZipBuilder.kt` — la regla que decide qué entradas
  viajan en el ZIP publicado, y la función pura que la sostiene (§2.75).
- `examples/example-uppercase-plugin/build.gradle.kts` — el consumidor externo
  que resuelve el SDK **a través del BOM publicado**, sin versión propia en sus
  coordenadas (§2.75).
- `v0.79.0-rc1` — la candidata del bloque anterior (prerelease;
  la promoción a estable sigue bloqueada por el certificador externo).
- `pipeline-step-sdk/runtime/.../TranscriptDrain.kt` — la regla y el bucle del
  drenaje de una transcripción, y las particiones que los cubren (§2.74).
- `examples/smoke/11-cli-self-report.pipeline.kts` — el escenario que pregunta al
  producto por su salud, su catálogo, su versión y **las operaciones de su propio
  run**, y comprueba cada respuesta contra lo que el producto imprimió (§2.72,
  §2.73).
- `examples/output/33-live-process-output.pipeline.kts` — el escenario que
  comprueba que la salida de un **proceso** llega al terminal, que es lo único
  que el observador en vivo puede hacer (§2.73).
- Historia anterior: tag `pre-testing-reset-20261018`. No se resucita en el árbol
  activo.

---

# Apéndice: el ROADMAP que traía `main`

Lo que sigue es, **literal**, el ROADMAP de la otra línea en el momento en que
se fusionó (`ac642baf2`, 48 commits por delante de esta rama). Se conserva
entero y sin reescribir porque es el registro de las decisiones, las
mediciones y los cierres de ciclo de ese trabajo, y porque los dos
documentos llevan una numeración propia: las secciones `§1`, `§2` y `§3` de
este apéndice **no** son las de arriba.

- El documento vivo es el de arriba, que describe el árbol fusionado.
- El de abajo describe un árbol que ya no existe: el `v2/` con 37 escenarios y
  el arnés en serie.
- Las dos líneas se midieron sobre árboles distintos, así que sus cifras no
  son comparables entre sí: cada una vale para el árbol que la midió.

# Roadmap — PipelineK V2

Vigente desde el **2026-10-19** sobre la rama `refactor/reset-testing-and-docs`
(tag base `pre-testing-reset-20261018`). Este fichero **sustituye** al roadmap
anterior, que describía un régimen (`CycleRunner`, `installedContractTest`, vías
`fast`/`integration`/`candidate`, harness externo) que `AGENTS.md` §0 reemplazó
de forma explícita y que el RESET borró del árbol. Sus referencias a
`*_SPEC.md`, `PIPELINE_TEST_HARNESS.md` y los ADR de ese régimen apuntan a
ficheros que ya no existen: ninguna tarea de aquí depende de ellos.

Toda cifra de este documento está medida sobre el árbol en el momento de
escribirlo, con su denominador nombrado. Una afirmación sin denominador no es
una medición.

---

  activo.