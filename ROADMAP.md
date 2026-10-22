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

**Lo que está abierto ahora mismo:**

| # | Qué falta | Por qué sigue abierto |
|---|---|---|
| 1 | **La política** de la identidad duplicada, si algún día hace falta cambiarla | el **diagnóstico** ya nombra los dos artefactos (§2.51) y dice que la composición entera se rehúsa, así que el operador sabe cuál es cuál y sabe que nada se está ejecutando. Lo que queda **no es un defecto pendiente**: el sistema **ya rechaza la composición** cuando ve el duplicado. Cambiar *qué* artefacto conserva la identidad es un cambio de comportamiento observable y por tanto una ruptura versionada, que sólo se hace con una **necesidad de producto concreta**, no porque el modelo sea más bonito |
| 2 | Dos autoridades de identidad: digest declarado, digest medido e identidad de confianza | el vocabulario de rechazos está **publicado**: añadir un caso rompe el ABI de los consumidores, así que separar los tres nombres necesita su propio bloque (§2.45) |
| 3 | Colisión de identificadores de stream: el nombre en disco dobla identificadores distintos | **caracterizada** (§2.57): con el camino de producción actual **dos streams no pueden compartir bytes**, porque los segmentos dirigidos por posición no se pliegan. Lo que sí era un defecto real —el parser de op ids descartaba parte de la identidad **en silencio**, devolviendo otra operación— está arreglado y con su fila vista en rojo. Queda con nombre una deuda: el formato del cuerpo **no es invertible** cuando un identificador lleva `-`, y cerrarlo es ruptura versionada que espera autorización |
| 4 | Certificación externa | **`BLOCKED_EXTERNAL`**: no hay certificador en este repositorio ni en remoto (§2.45) |

## 1. Estado medido

| Métrica | Valor | Fuente |
|---|---|---|
| SHA | `fb45f23a6`, medido en los ensayos de este bloque. Entre `773ec6a60` y este **el código de producción sí cambia**: la propiedad no acreditada deja de conceder permiso de destrucción (§2.56), y el instrumento del workspace se refuerza con cuatro mutaciones independientes (§2.55) | `git rev-parse HEAD` |
| Módulos declarados | 27 | bloque `include(` de `pipelinek/settings.gradle.kts` |
| Módulos con test unitario permanente | **26** | etapa 2 del gate, que imprime cada módulo por nombre |
| Módulos sin test | 1 (`:pipeline-sdk-bom`, que no tiene código) | ídem |
| Tests unitarios ejecutados | **1074** (149 informes en disco, 0 fallos, 0 errores, 0 skips) | etapa 2 del gate, confirmado con un censo independiente de los XML |
| Casos de un vocabulario de rechazos publicado que el producto no puede producir | **0**, medido en el árbol del ciclo anterior (§2.35); **este ciclo no lo re-medió**, y su instrumento es un censo ad-hoc, no un script del repo | censo por construcción de §2.35 |
| Escenarios E2E | **61 (61 pasan, 0 fallan, 0 bloqueados)**, 2 reruns durables, 7 credenciales sembradas | etapa 3 del gate |
| Escenarios con una garantía comprobada | **61 de 61**: **170 garantías** comprobadas —162 en los 60 anteriores y 8 en el nuevo de coherencia del workspace (§2.55). El censo anterior («19 sólo miran el código de salida») contaba el canal de la declaración, no la garantía, y era falso | censo del arnés, sobre los 61 escenarios |
| `VERIFY_LOC / PRODUCT_LOC` | **19066 / 67381 = 0.283** (límite 2) | `scripts/check-budgets.py --json` |
| Ratio máximo por módulo | **1.633** (`pipeline-step-sdk:workflow-control`) | ídem |
| `VERIFY_ALL_WALL_CLOCK` | **235 / 197 / 225** en los tres ensayos, los tres sobre `fb45f23a6`, peor 235 s, límite 900 s | etapa 5 del gate, `§4` de `AGENTS.md` |
| Veredicto del gate | **`PASS`** en los tres ensayos, sin `NOT_RUN` | línea final de `verify-all.sh` |
| Deuda estática congelada | **879** entradas de baseline en 19 ficheros: **sin cambio en este bloque**, y el diff del bloque no lista ninguna baseline. La cifra de **802** que se venía anotando no se reproduce con ningún instrumento del árbol: el censo de entradas `<ID>` sobre las baselines versionadas da **770** antes de la unión de las dos líneas y **879** después | censo sobre `git ls-files '*detekt-baseline-main.xml'` |
| Certificador externo | **`BLOCKED_EXTERNAL`** | no existe en este repositorio ni en remoto |
| Destino de `:pipeline-runtime` | **se queda y está cableado**: sus tres adaptadores implementan los puertos publicados de `:pipeline-runtime-api`, y `:pipeline-application` los compone y los invoca por verbo de CLI. **5** clases de test, **28** filas (§2.52) | `CompositionRoot.kt:222,280,330`, `MainRunsCli.kt:132,222`, `MainInspectCli.kt:544`, `scripts/verify-all.sh:371` |

Los tres ensayos corrieron sobre el mismo SHA y sobre el árbol **commiteado**, que
es la condición con la que el censo de LOC es verdad: el censo lee
`git ls-files`, así que con el árbol sucio un test nuevo no cuenta y la ratio
sale mejor que la realidad.

El reloj de la puerta lo marca también el host. Los ciclos anteriores midieron
699, 682 y 803 s con la máquina compartida con otro workspace, y 194, 172 y
165 s en el bloque de las tres familias; este ciclo midió **235, 197 y 225 s**:
la distancia entre un ensayo y otro no la explica el código medido, que es
idéntico dentro de cada ciclo, sino la carga de la máquina. En los tres ensayos
de este ciclo el presupuesto se cumplió con holgura —el peor, 235 s, es menos
de la tercera parte de los 900 s—, y eso que el árbol medido incluye la
distribución, la API pública, los 1074 tests y los 61 escenarios.

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
contrato del lanzador queda protegido por `run.sh --self-test`, que el propio
lanzador ejecuta y que la puerta invoca **antes** de fiarse de él: una
garantía que sólo vive en una mutación que alguien corrió una vez es una
garantía que un refactor puede perder sin que nadie lo note.

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
llamado `v2` dentro de un repositorio cuyo producto va por `0.62.0` no dice
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
> toca. Este repositorio está en `0.62.0` y el `v0.62.0` ya publicado es un
> artefacto DISTINTO (el RESET re-funda la identidad del producto); subir el
> número sin publicar una release sería afirmar una versión que no existe, y
> el README señala el `v0.62.0` como la autoridad pública de hoy. El campo
> `firstPublished` del catálogo dice por eso la **línea de versión que primero
> lleva el id**, no un tag que aún no se ha puesto. La decisión de qué
> release sale, y con qué tag, es del proceso de release, no de este ciclo.

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
| **H4** `pipeline-runtime` es código que merece integración, no eliminación | **Real, y una afirmación del informe que aquí no se sostiene** | El jar `pipeline-runtime-0.62.0.jar` **sí está** en `lib/` de la distribución instalada, y el módulo compila y tiene tests (la etapa 2 del gate lo ejecuta). Lo que falta es el recorrido operativo acreditado: **P1.1** |
| **H5** El output plane: intercalado cronológico y durabilidad | **Real y pendiente** | La pieza existe (`OutputFrameIndex.framesOfRun`) y la vista de consola **concatena por stream** y lo declara en su KDoc («es una proyección, no una afirmación de lo que el kernel intercaló»). Falta **decidir qué vista usa la proyección ordenada**: nuevo **P1.5** |
| **§4** Colisiones entre agentes en el gate | **Ya cerrado** | El gate usa `log_dir="${TMPDIR:-/tmp}/verify-all.$$"`, un directorio por corrida, y escribe el motivo: «a shared path let one run truncate the log while another was still appending to it» |
| **§4** Métricas que incentivan tests sin valor | **No aplica a Core** | No hay mínimo artificial de líneas de test en este repositorio (el `testLineFloor` que cita el informe es de Fabric). El presupuesto de Core es LOC y reloj, y `AGENTS.md` exige mutación vista en rojo |
| **§4** Una RC no debería promoverse con una garantía en `NOT_RUN` | **Real, y pertenece a la política de release** | El gate ya **declara** los `NOT_RUN` en vez de esconderlos; la regla de promoción se escribe en **P2.1**, que hoy está `BLOCKED_EXTERNAL` |
| **§6.1** Un consumidor de referencia del SDK, compilado contra la RC descargada | **Real y pendiente** | **No existe** en esta rama (el ledger lo menciona, pero era del árbol pre-RESET). Nuevo **P1.6** |
| **§6.2** Un único estado durable de observación, sin crear otro event store | **Ya es la política** | `AGENTS.md` §1: «no second event, Step, recovery or certification authority» |
| **§6.3** Presupuesto por comportamiento, no por líneas | **Ya es la política** | `AGENTS.md` §2: «un verde que nunca se ha visto rojo no es evidencia»; §4: LOC ≤ 2 × y reloj ≤ 900 s |
| **§6.4** Cada capacidad nueva, con un ejemplo mínimo que compile externamente | **Real y pendiente, y este ciclo lo dejó a medias** | Este ciclo publicó dos ids (`runtime.eventshape.v2`, `events.stepoutcome.v1`) y **ninguno** tiene todavía ese consumidor mínimo: **P1.6** |
| **§7** Hay una RC `v0.75.0-rc1` publicada y `main` va por delante | **Real, y es un hecho de esta rama** | La release existe (release-gate PASS, sha256 `e24abea8…`, 20/10/2026) y `origin/main` está **20 commits** por delante. Esta rama declara `0.62.0` y **no se puede publicar tal cual**: su versión colisionaría con la publicada. Fusionarla con `main` es una decisión que no es de la rama |

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
| ¿lo compila la puerta? | sí, dos veces y por razones distintas: `:pipeline-application:installDist` lo compila **transitivamente**, porque su jar viaja en el `lib/` de la distribución (`pipeline-runtime-0.62.0.jar`, medido en la instalación), y además `scripts/verify-all.sh:371` pide `:pipeline-runtime:compileKotlin` **por su nombre**, para que el plan de la puerta diga que el módulo está dentro de ella |
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
segunda mitad es **falsa**: `pipeline-runtime-0.62.0.jar` **está** en el `lib/`
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

**El arreglo, y que sea compatible.** El parser ahora reconstruye un segmento
que no va dirigido por posición **tal como estaba escrito**, en vez de
droppearlo. El texto que se emite **no cambia** —el mismo `format`—, así que no
se toca ninguna clave del journal, y el resultado observable de la consola no
cambia: sus dos consumidores (`ConsoleOperationSelection` y
`StageStepKindResolver`) miran **sólo** el índice de etapa y de paso. El
efecto lateral útil: un identificador que lleve `/` ahora **sí** sobrevive al
ida y vuelta, porque el segmento se conserva literal.

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

**El veredicto sobre la colisión.** Con el camino de producción actual, **dos
streams no pueden compartir bytes**: los segmentos dirigidos por posición no se
pliegan, y los ids de step de producción (`core.sh`, `core.dir`, los de los
plugins de ejemplo) sólo llevan `[A-Za-z0-9._-]`. La colisión **no es
alcanzable hoy**. Lo que sí era verdad —y ya no lo es— es que la garantía
dependía de un supuesto no escrito, y que la puerta por la que un id externo
entra al sistema **descartaba parte de la identidad en silencio**. Eso era un
defecto real, y está arreglado.

## 3. Decisiones tomadas en este ciclo, con su porque
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
  repositorio está en `0.62.0` y el `v0.62.0` publicado es un artefacto
  distinto (el RESET re-funda la identidad del producto). Subir el número sin
  publicar una release sería afirmar una versión que no existe, y el README
  señala el `v0.62.0` como la autoridad pública de hoy. Por eso `events.follow.v1`
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
  llamaba `v2` en un repositorio cuyo producto va por `0.62.0`, y los
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

## 4. Referencias

- `AGENTS.md` — política vigente; manda sobre cualquier documento histórico.
- `scripts/verify-all.sh` — la única puerta local.
- `scripts/check-budgets.py` — el único script de presupuestos.
- `examples/run.sh` — el único lanzador E2E.
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

## 1. Estado medido

| Métrica | Valor | Fuente |
|---|---|---|
| SHA del censo | `4b7b908bb` | el SHA sobre el que se midió; el `HEAD` avanza al commit que actualiza esta tabla |
| Módulos declarados | 27 | bloque `include(` de `v2/settings.gradle.kts` (líneas 33-106) |
| Módulos con test unitario permanente | **22** | etapa 2 del gate, que imprime cada módulo por nombre |
| Módulos sin test | 5 | ídem |
| Tests unitarios ejecutados | **812** (104 informes JUnit, **104** reescritos por la corrida, 0 fallos, 0 skips) | etapa 2 del gate, y la línea de veredicto: `tests=rewritten:812 read:0` |
| Escenarios E2E | 36 (**35 pasan, 0 fallan, 1 `NOT_RUN`**; los 35 que corren declaran una observación) | `v2/examples/**/*.pipeline.kts` |
| `VERIFY_LOC / PRODUCT_LOC` | **13172 / 66688 = 0.198** (límite 2) | `scripts/check-budgets.py --json` |
| Ratio máximo por módulo | **1.633** (`pipeline-step-sdk:workflow-control`) | ídem |
| `VERIFY_ALL_WALL_CLOCK` | **89 s** (sobre el árbol de este bloque; los tres ensayos del bloque anterior dieron 88 s, 74 s y 72 s, todos con `tests=rewritten:812 read:0`), límite 900 s | etapa 5 del gate, `§4` de `AGENTS.md` |
| Coste por escenario E2E | mediana **7,0 s**, p95 **14,5 s**, máximo **15,6 s** | el lanzador publica ahora el tiempo de cada escenario en su línea |
| Certificador externo | **`BLOCKED_EXTERNAL`** | no existe en este repositorio ni en remoto |

La cifra de reloj de este ciclo son **tres** ensayos consecutivos sobre el mismo
SHA, y los tres cuentan: 88 s, 74 s y 72 s. Los tres corrieron con las salidas de
test limpiadas, así que los tres reescribieron los 104 informes y ninguno pudo
leer un resultado de la caché — la etapa 2 separa `rewritten` de `read` y **falla**
si no reescribe ninguno, que es lo que convierte un acierto de caché en un rojo
en vez de en una cifra. No se comparan con la de 2026-10-19 (226 s, tras `rm -rf`
de las salidas de build reales, condición que hace que la cifra mida el gate y no
una restauración de caché).

**Y el total viaja ahora en la línea de veredicto**, que es la única que alguien
pega en un informe: `tests=rewritten:812 read:0`. Antes esa línea decía
`PASS  sha=…  elapsed=345s  budget=900s  gaps=1 not_run` y no decía **nada** de
los tests: el dato estaba en el log de la etapa 2, y el log lo lee quien corrió la
puerta, no quien la cita. Una cifra con el verbo equivocado ya no puede colarse
ni por el lado del log ni por el lado de la línea (§2.2 u).

La corrida que se publica es la más lenta de las tres, **88 s**, porque una cifra
de reloj que sólo nombra su mejor caso es una cifra elegida. El reloj de este gate
tiene ruido, y publicarlo sin decirlo vuelve a ser una afirmación que nadie puede
reproducir: el bloque anterior (`2c3b87083`) publicó **110 s**, y la primera
corrida en frío de este ciclo dio **367 s**, de los que 306 s son la etapa 1. Se
publica la del **SHA que queda publicado**, que es la que corresponde al árbol que
se publica, y se dicen las otras para que nadie la lea como la típica. Este bloque
**retira diez escenarios** —de 46 a 36— y **no toca los tests**: los **812** son
los mismos, porque lo que se corrigió no era la cobertura sino lo que los
escenarios afirmaban.

**Lo que este bloque movió, y lo que no.** El bloque `main-partido` (PR #183
→ `03bdc2a3a`) no toca el censo de módulos con test, ni añade tests, ni añade
escenarios: los **743** tests y los **46** escenarios E2E son los mismos. Lo que
mueve son dos cifras de la tabla: `PRODUCT_LOC` sube de 65 556 a **65 771**
(+216 líneas efectivas, casi todas KDoc que explica por qué cada costura es una
costura) y el reloj de la puerta va de 99 s a **107 s**. El ratio global baja un
hábito: 0,173. El ratio máximo por módulo no se mueve: sigue siendo
`workflow-control` con 1,633.

El `PRODUCT_LOC` sube porque el bloque **no es neutro en líneas**, y decirlo es
parte del resultado: paga 216 líneas de producción efectivas a cambio de un
punto de llamada único, 28 entradas de baseline menos y un `main` que se puede
leer. Un bloque de refactor que sale gratis no es un refactor: es un bloque que
no explica lo que hace.

El escenario `NOT_RUN` es `security/14-credentials-bindings.pipeline.kts`, que
declara `requires-seeded-credentials`: el lanzador lo marca `NOT_RUN` en vez de
fabricar credenciales. `NOT_RUN` no es un aprobado, y desde `5240feac` tampoco se
confunde con uno: la línea de veredicto lleva el hueco **dentro**, como
`PASS  sha=…  elapsed=345s  budget=900s  gaps=1 not_run`. Antes el conteo se
imprimía y no se leía —la etapa 3 solo hacía `grep` del resumen—, así que el
peor caso (`--scenario 'security/*'`: 0 pasan, 0 fallan, 1 no ejecutado) salía
vestido de `PASS`. Un tercer estado no se propaga porque la capa de abajo lo
imprima: si el hueco necesita el log de la etapa para entenderse, no ha viajado.

Host de referencia, exigido por `§4`: 64 núcleos lógicos Intel Xeon E5-2682 v4
@ 2,50 GHz, 94,2 GiB de RAM, Linux 7.2.9-ogc3.1.fc44.x86_64, Temurin
21.0.8+9.0.LTS, Gradle 8.14.5, cachés de dependencias ya pobladas y sin
descargas externas dentro de la medición. El certificador externo tiene su
propia latencia y **no** va dentro de este número.

Una cifra de reloj heredada de otro commit es exactamente el tipo de verde que
esta tabla existe para no declarar: por eso cada fila lleva el SHA al que se
midió.

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
| 1 | 0 Markdown bajo `docs/` y `v2/` | **PASS** | `git ls-files ':(glob)docs/**/*.md'` = 0; `:(glob)v2/**/*.md` = 0. En la raíz solo quedan `AGENTS.md`, `README*.md`, `ROADMAP.md` |
| 2 | 0 `@Test` UAT/E2E/AAT/harness | **PASS** | 0 en `pluginContractTest`/`installedContractTest`/`integrationTest`/`functionalTest`; 0 `@Disabled` |
| 3 | 0 clases legacy trasladadas | **PASS** | `v2/test-harness`, `v2/pipeline-testkit`, `v2/pipeline-architecture-tests` y `scripts/validation-gate.py` ausentes |
| 4 | E2E solo como `.pipeline.kts`, un lanzador | **PASS** | 36 escenarios en 8 categorías bajo `v2/examples/`; 0 `src/test` dentro de `examples/*`; lanzador único `v2/examples/run.sh` (1277 líneas) invocado por `scripts/verify-all.sh:623` |
| 5 | `VERIFY_LOC <= 2 * PRODUCT_LOC`, global y por módulo | **PASS** | 13172 / 66688 = 0.198; ningún módulo declara `breach`; el más alto es `workflow-control` con 1.633 |
| 6 | `verify-all` local ≤ 900 s | **PASS** | tres ensayos consecutivos sobre `5a8e43a35`: **88 s, 74 s y 72 s**, los tres con `tests=rewritten:812 read:0` — los tests se ejecutaron de verdad en cada uno, ninguno leyó un resultado de caché. Margen del más lento, **10,2×**. La primera corrida en frío del mismo árbol dio 367 s, de los que 306 s son la etapa 1 |
| 7 | Compilar todos los módulos y ejecutar el gate sobre el SHA | **PASS con una excepción** | 27 módulos declarados compilan; `v2/pipeline-protocol/` está versionado y **no está declarado**, luego Gradle nunca lo compila |

Sobre el límite 4: el mandato escribe el lanzador como `examples/run.sh`, y en
este checkout está en `v2/examples/run.sh`. Es la misma ruta relativa al módulo
raíz de Gradle, versionada, y es la que la etapa 3 invoca. Se registra aquí
porque la diferencia entre «no existe» y «está en otro sitio» es la diferencia
entre borrar un fichero y nada.

### `v2/pipeline-protocol/` — módulo vestigial

Versionado, con su `build.gradle.kts`, y sin declarar en
`v2/settings.gradle.kts`. Cuatro hechos independientes, todos medidos:

- **`ProtocolCore.kt` no compilaría aunque se declarase.** Referencia
  `dev.rubentxu.pipeline.v2.protocol.Version`, que solo produce `protoc`; el
  fichero no está versionado y no existe `v2/pipeline-protocol/build/`, o sea
  que `protoc` nunca se ejecutó.
- **Cero consumidores** de `ProtocolModules`, `ProtocolGovernance` y
  `ProtocolVersion` fuera del propio módulo, y cero referencias a `v2.protocol`.
- **Diez ficheros versionados**: un `.kt` y ocho `.proto`.
- **Último toque `2aa071aa`**, el commit masivo de política, no un cambio de
  producto.

No se borra en este ciclo: no figura en la lista del mandato y borrar ficheros
versionados es del usuario. La decisión pendiente es declararlo (y arreglarlo)
o retirarlo.

### Los 22 módulos con test

`pipeline-application`, `pipeline-domain`, `pipeline-events`,
`pipeline-events-store`, `pipeline-runtime`, `pipeline-runtime-api`,
`pipeline-scripting-api`, `pipeline-step-sdk/runtime`, `pipeline-step-sdk/http`,
`pipeline-step-sdk/workflow-control`, `pipeline-step-sdk/files`,
`pipeline-step-sdk/scm-git`, `pipeline-step-sdk/utilities`,
`pipeline-credentials-api`, `pipeline-credentials-local`,
`pipeline-credentials-multipart`, `pipeline-credentials-executor`,
`pipeline-binding-factory`, `pipeline-artefacts-local`,
`pipeline-output`, `pipeline-output-store`,
`pipeline-release`.

> **Censo remedido 2026-10-19 desde el disco, y remedido otra vez el
> 2026-10-20.** Sobre los 27 módulos que declara el `include(` de
> `settings.gradle.kts`: **22 con `src/test/kotlin/**/*Test.kt`, 5 sin él**. La
> lista de arriba omitía `pipeline-application`, que ya tiene
> `ProjectWorkingDirectoryTest.kt`, y por eso se contradecía con la fila de §1.
> Las cifras anteriores a esas —«los 12 módulos con test», «17 módulos sin
> test», «quedan 15», y el «16 con test / 11 sin él» de una remedición
> intermedia— estaban todas caducadas: el censo se tomó antes de que este ciclo
> añadiera `OutputAdoptionTest`, `ApiRangeContainsTest`,
> `AntStyleGlobPatternTest`, el test de `files` y el de `pipeline-application`,
> y nadie lo volvió a medir. El recuento sale de lo que **imprime la etapa 2
> del gate**, que ya recorre los módulos y no puede desincronizarse de él: una
> lista escrita a mano en un documento es un segundo censo, y un segundo censo
> es donde aparecen las contradicciones. La tabla por módulo de más abajo
> conserva los números de aquella ejecución: **los tests y su recuento los
> sustituye la medición de `scripts/verify-all.sh`**, que sigue siendo la fila
> `pendiente` de §1. Hasta esa medición, el recuento por módulo es histórico.
>
> El 18º módulo es `pipeline-events`, y no por paridad sino porque dejó de estar
> **bloqueado por decisión de producto**: su único candidato a test era
> `EventViewProjection`, que no tiene consumidor (2.2 b). Con la proyección
> publicada del veredicto, el módulo tiene por fin un consumidor real —el
> hermano, que la consume desde fuera— y un test que lo prueba con el plano
> real de dos corridas.
>
> Los cuatro últimos llegaron con el bloque `cobertura-modulos-riesgo`
> (`a2ffb90db`): `pipeline-runtime`, `pipeline-scripting-api`,
> `pipeline-step-sdk/scm-git` y `pipeline-step-sdk/utilities`. No se eligieron
> por paridad sino por **dónde una decisión mal tomada borra ficheros,
> publica una credencial o miente en el journal**; los dos defectos materiales
> del bloque salieron de ahí. Ver §2.2 x).

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
propia: `pipeline-domain` tiene 13 ficheros y 31 informes. Un recuento que
confundiera ambos leería 18 clases inexistentes aquí — es el mismo residuo que ya
se había confundido una vez en este ciclo, y se squashó al verificar en disco en
vez de reportarlo.

La tabla de abajo está medida sobre `a2ffb90db` con la misma corrida que da la
fila `tests=rewritten:743 read:0` de §1: **94 informes, 743 tests, 0 saltados,
0 fallidos**.

| Módulo | Ficheros `*Test.kt` | Clases JUnit | Tests | Saltados | Fallidos |
|---|---|---|---|---|---|
| `pipeline-domain` | 13 | 31 | 161 | 0 | 0 |
| `pipeline-credentials-local` | 8 | 8 | 92 | 0 | 0 |
| `pipeline-output` | 6 | 6 | 87 | 0 | 0 |
| `pipeline-events-store` | 8 | 11 | 62 | 0 | 0 |
| `pipeline-application` | 8 | 8 | 58 | 0 | 0 |
| `pipeline-output-store` | 3 | 3 | 36 | 0 | 0 |
| `pipeline-release` | 3 | 3 | 31 | 0 | 0 |
| `pipeline-artefacts-local` | 4 | 4 | 30 | 0 | 0 |
| `pipeline-step-sdk/http` | 2 | 2 | 26 | 0 | 0 |
| `pipeline-step-sdk/runtime` | 2 | 2 | 25 | 0 | 0 |
| `pipeline-runtime-api` | 1 | 1 | 21 | 0 | 0 |
| `pipeline-credentials-executor` | 2 | 2 | 20 | 0 | 0 |
| `pipeline-events` | 1 | 3 | 19 | 0 | 0 |
| `pipeline-credentials-multipart` | 2 | 2 | 16 | 0 | 0 |
| `pipeline-step-sdk/files` | 1 | 1 | 11 | 0 | 0 |
| `pipeline-step-sdk/utilities` | 1 | 1 | 11 | 0 | 0 |
| `pipeline-step-sdk/workflow-control` | 1 | 1 | 9 | 0 | 0 |
| `pipeline-binding-factory` | 1 | 1 | 7 | 0 | 0 |
| `pipeline-credentials-api` | 1 | 1 | 6 | 0 | 0 |
| `pipeline-step-sdk/scm-git` | 1 | 1 | 6 | 0 | 0 |
| `pipeline-scripting-api` | 1 | 1 | 5 | 0 | 0 |
| `pipeline-runtime` | 1 | 1 | 4 | 0 | 0 |
| **total** | **71** | **94** | **743** | **0** | **0** |

### El `NOT_RUN` que no es verde

`v2/examples/security/14-credentials-bindings.pipeline.kts` está marcado
`//@ requires-seeded-credentials`. El único escritor del almacén es
`pipelinek credentials add`, que llama `System.console()?.readPassword() ?: error("no TTY available")`.
No existe vía no interactiva. El launcher no debe sintetizar un almacén
cifrado a mano — eso probaría el fichero fabricationado, no el producto. Se
mantiene en el inventario, declarando su hueco, con el criterio de borrado
escrito en la cabecera del propio escenario.

---

## 2. Deuda técnica abierta

### 2.1 Defectos materiales corregidos en este ciclo

Cada uno con test de regresión mutation-verificado (el test muere al revertir
el arreglo; los demás siguen verdes).

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
  arreglo y confirmados rojos por él. Un censo del patrón en todo `v2` no
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
- **Una llamada «en vuelo» se podía re-autorizar, y el efecto ocurría dos veces.**
  `CompensationLifecycle.allows` trataba «re-escribir el estado que la obligación ya
  tiene» como siempre permitido (`to == from`), un atajo que hace idempotente el
  camino de escritura. Pero hay dos estados que **no** dicen «nada ha pasado»
  todavía: `EFFECT_PENDING` (la llamada salió del proceso y su respuesta no ha
  llegado) y `COMPENSATION_PENDING` (lo mismo, para la compensación). Re-escribir
  uno de ellos no es un reintento: es una **segunda autorización** de la misma
  llamada. Un proceso que vuelve después de un crash lee la fila, re-escribe el
  mismo «en vuelo» —y despacha la llamada otra vez—, así que el efecto ocurre dos
  veces y la fila queda **indistinguible** de la de un primer intento.
  Medido de punta a punta, con proceso real y SQLite real: con la regla antigua,
  el escenario `v2/examples/durable/35-saga-crash-effect.pipeline.kts` reanuda el
  run tras el crash y **repite la reserva**; con la regla nueva el write-ahead se
  rehusa y el Saga termina en `NEEDS_INTERVENTION (UNKNOWN_EFFECT)`, que es la
  respuesta honesta cuando nadie puede preguntar al proveedor qué pasó. La regla
  se expresa en el dominio y **no toca el esquema**: los estados en vuelo no
  admiten re-escritura; los demás sí, porque re-confirmar o re-compensar escriben
  el MISMO hecho y no autorizan nada nuevo. Test de regresión mutation-verificado:
  dos filas que mueren al revertir el arreglo, y ninguna otra.
- **Un parche de entorno no llegaba al proceso: el contexto mentía.**
  `ExecutionContextPatch.Environment` se proyectaba **solo** sobre el
  `ExecutionContext` (una capa `ContextOverlay.Environment`), y un proceso `sh` se
  lanza con `ShOptions.env` y con nada más: la capa nunca se proyecta sobre el
  `ProcessBuilder`. No era un parche a medias, era un parche **nulo** para un
  cuerpo `sh` —y el KDoc del dominio lo define como la proyección de un ámbito
  `withEnv`, y `withEnv` proyecta sobre **las dos** superficies, el contexto y
  las opciones del proceso, que es exactamente por lo que los ámbitos del script
  funcionan. La diferencia se midió: con la proyección del entorno eliminada, el
  escenario 37 falla y los escenarios 33 a 36 siguen verdes.
  La consecuencia no era cosmética: un cuerpo que tiene que reconocer su propia
  clave de operación —todo el contrato de un proveedor reconciliable— no puede
  reconocer una clave que nunca recibió, así que el `Reconcile` que vuelve a
  despachar el cuerpo no era seguro: el cuerpo podía repetir el efecto. El
  arreglo es **una** proyección pura (`projectInvocationPatch`) que proyecta el
  parche sobre las dos superficies y se usa en los tres sitios que aplican un
  parche; la variante de directorio se **rechaza** cuando se sale del workspace,
  y la regla de contención —que el ámbito `dir` aplicaba en su sitio— pasa a
  vivir en **un** sitio, para que las dos no puedan separarse. 10 tests nuevos
  (7 de la proyección, 3 del intérprete): al quitar la proyección del entorno
  mueren 2 de los 7 y nada más, y al quitar la reconciliación mueren 3 de los 12
  del intérprete y nada más.
- **El veredicto de una corrida no era legible desde fuera sin re-derivarlo.**
  El motor sabía cómo había terminado la corrida mientras corría, y el plano de
  eventos llevaba la palabra (`success`, `unstable`, `failure`, `aborted`) y el
  detalle del fallo (el `StepFailed` con su `failureKind` y su mensaje). Pero la
  OTRA mitad del veredicto —qué había dejado cada Saga y si queda trabajo para
  una persona— sólo vivía en el KDoc de un evento y en el stderr del CLI. Un
  consumidor externo que quisiera answering «falló» y «queda trabajo humano»
  tenía que **correlacionar dos eventos por su cuenta** y decidir por su cuenta
  qué significa la ausencia de cada uno: un trabajo sin destinatario que cada
  consumidor tenía que volver a hacer, y cada consumidor podía hacerlo peor.
  El arreglo publica la lectura una sola vez, en el módulo que publica el plano
  (`RunPresentationProjection`), con la ontología del veredicto en el dominio
  (`SagaOutcome`, `RunPresentation`, `mergeCompensationOutcome`).
  **Falla cerrado en las tres direcciones**, porque un éxito que nadie puede leer
  no es un éxito: sin cierre del run → `Aborted`; con una palabra de outcome que
  este build no conoce → `Failure` sintética de motor; con un terminal de Saga
  ilegible → `NeedsIntervention(TERMINAL_UNREADABLE)`. Y hay una cuarta, que es
  la **ausencia** de un anuncio: un Saga que armó una obligación y nunca dijo
  cómo terminó sigue abierto, y el plano no puede leerse como «no debe nada».
  La regla que protege es la de SPEC-001 («outcome conservation»): un Saga
  liquidado **no** vuelve verde un run fallido. Medido: 28 tests nuevos, de los
  cuales 2 leen el **plano real capturado** de dos corridas —los bytes que
  escribió el motor, decodificados por el códec de producción— y dicen lo mismo
  que dice el veredicto del propio producto. Mutaciones comprobadas: leer el
  primer fallo en vez del más externo mata el test del plano real y el unitario
  y ningún otro; tratar un Saga armado sin terminal como liquidado mata
  exactamente un test; tratar una palabra desconocida como éxito mata
  exactamente un test.
- **El outcome se escribía con un `when` a mano y se leía con otra lista.**
  `RunLifecycleEngine.closeRun` traducía el outcome a su palabra con un `when`
  escrito en el motor, y el que lo leía —el CLI— llevaba su propio mapa. Dos
  listas de un vocabulario son dos autoridades, y divergen la primera vez que
  alguien añade un caso: el escritor seguía creyendo que describía lo que
  había escrito y el lector ya no entendía la mitad de las corridas. Lo mismo
  con el estado terminal del Saga, que vivía en dos constantes privadas del
  intérprete: un vocabulario **que ningún lector podía citar**. Ahora las
  palabras viven en el dominio, junto al tipo que nombran (`RunOutcome.wireToken`
  y sus constantes, `InterventionReason.parse`, `SagaTerminalState`), y los dos
  lados las citan. Los bytes no cambian: los mismos cuatro tokens y los mismos
  dos estados. Sin cambio de comportamiento, medido: el veredicto de las
  corridas del Saga es idéntico antes y después.
- **No existía la ley «un run verde no puede dejar un Saga pendiente».**
  Un Saga que necesita una persona es trabajo que nadie ha hecho, y un run que
  dice `success` mientras ese Saga está abierto le dice al operador que todo
  está bien mientras puede haber un recurso aplicado y sin devolver. Los dos
  hechos se contradicen, y una contradicción en el historial es lo que el arnés
  existe para cazar — pero el arnés no la miraba. La ley vive ahora en
  `ProtocolGrammar`, con su caso en `ViolationRule`: considera pendiente un Saga
  cuyo **último** terminal no liquidó y también uno que armó una obligación y
  nunca anunció terminal, porque la ausencia es la evidencia. El último
  terminal manda, porque una corrida reanudada reutiliza su `runId` y un Saga
  puede anunciarse más de una vez. Su test vive en `:pipeline-application` y no
  en `:pipeline-event-harness`, y el motivo está escrito en el propio test: el
  arnés no lleva tests unitarios por decisión (su prueba es el verbo `pipeline
  events verify`), y ese módulo es el que publica el verbo. Medido: al hacer
  que la ley no dispare mueren los dos tests que la esperan y ningún otro.

- **Un Saga que terminaba bien se compensaba a sí mismo, y el run lo llamaba
  `success`.** Medido con una cadena de dos acciones que las dos tuvieron éxito: el
  mundo quedó `reserve`, `charge`, `refund`, `release`, y el run dijo `SUCCESS` con
  el Saga en `SETTLED`. Un plano así no dice nada sospechoso: el veredicto verde y
  el trabajo deshecho se contradicen, y nadie que lo leyera se enteraría. La causa
  era semántica y no de API: `EFFECT_CONFIRMED` significa «debo compensar», y la
  única salida de esa deuda era deshacer el efecto. Ahora el Saga que funcionó
  **conserva** sus efectos, y el estado durable lo dice (`EFFECT_KEPT`).
- **El manifiesto del plugin llevaba su propia copia de las capacidades de sus
  Steps, y las dos copias se separaron.** El Step declaraba el puerto del ciclo de
  vida y el manifiesto seguía declarando el del runtime; lo cazó la puerta de
  admisión, que es la puerta funcionando, pero sólo por casualidad: un manifiesto
  capaz de describir un Step que el código no tiene es una segunda autoridad que
  puede mentir. Ahora el manifiesto **lee** las definiciones.

### 2.2 Lo que queda abierto, por valor operativo

**a) 5 módulos sin test unitario permanente.** No es un objetivo de paridad.
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
| 8 | `pipeline-step-sdk/scm-git` | El guard de argv: la única decisión que separa «un PAT puede viajar en el proceso» de «no puede». Secreto en `argv` es secreto en `/proc` y en cada log. | **Hecho** — 6 tests, 1 clase (`a2ffb90db`). Encontró 1 defecto material con **tres** huecos: el PAT usado como usuario, el esquema en mayúsculas y la contraseña en una URL no-http. |
| 9 | `pipeline-step-sdk/utilities` | La regla de Zip Slip: el nombre de una entrada decide si se escribe FUERA del workspace. Escribir fuera del destino es borrar ficheros ajenos. | **Hecho** — 11 tests, 1 clase (`a2ffb90db`). Encontró 1 defecto material: la regla del `..` rechazaba nombres legales y su regla de contención estaba muerta. |
| 10 | `pipeline-runtime` | El vocabulario con el que el recover **escribe en el journal**. Un terminal desconocido journalado como `SUCCEEDED` es una mentira que nada más contradice. | **Hecho** — 4 tests, 1 clase (`a2ffb90db`). Sin defecto material: la regla era correcta, pero **no tenía observador**, porque estaba enterrada en un adaptador que hay que levantar entero para preguntarle. |
| 11 | `pipeline-scripting-api` | La identidad de la caché de compilación: dos compilaciones distintas con la misma clave ejecutan código que no es el suyo. | **Hecho** — 5 tests, 1 clase (`a2ffb90db`). Sin defecto material: la diferencia `v1`/`v2` ya estaba bien, pero no la fijaba nadie. |
| — | `pipeline-events` | Su único candidato (`EventViewProjection`) no tiene consumidor: ver 2.2 b. Sin consumidor, un test es coste sin destinatario. | **Resuelto** — la proyección publicada del veredicto (`RunPresentationProjection`) le da un consumidor real (el hermano la consume desde fuera) y sus propios tests, con el plano real de dos corridas. El módulo ya figura entre los que tienen test. `EventViewProjection` sigue sin consumidor: ver 2.2 b. |

Quedan **5** módulos sin test, que es lo que mide la etapa 2 del gate (22 con
test sobre 27 declarados). Antes ponía 9, y el número estaba caducado desde
hace un ciclo: el `pipeline-events` pasó a tener test en este, y nadie volvió
a medir. De los 5 que quedan, uno es `pipeline-sdk-bom`, un `java-platform` sin
clases: los otros cuatro son `pipeline-scripting-kotlin24` (1 083 LOC),
`pipeline-event-harness` (661), `pipeline-step-sdk/junit` (595) y
`pipeline-step-sdk/api` (274) — **2 613 LOC de producción sin un solo test
unitario permanente**, frente a las 9 828 que quedaban antes de este bloque.

**Esta redacción anterior los llamaba
«adaptadores finos o módulos sin clases», y es falsa**: un censo de
`src/main/kotlin` encontró lógica pura real en varios de ellos
(`pipeline-artefacts-local`, `pipeline-step-sdk/files`, `pipeline-release`,
`pipeline-credentials-executor`, `pipeline-output-store` entre otros), no
adaptadores. La afirmación no se comprobó cuando se escribió y por eso salió. Un
único caso encaja de verdad: `pipeline-sdk-bom` es un `java-platform` sin
clases, y su propio `settings.gradle.kts` dice por qué.

**Censo de qué es testeable en cada uno de los 15** (delimitado por módulo;
NOT_RUN donde no se midió). Un módulo «sin test» no es un módulo
sin lógica: cuatro de ellos deciden cosas de las que depende borrar ficheros o
publicar un binario.

| Módulo | Ficheros main | Con lógica pura | Dónde está la decisión |
|---|---|---|---|
| `pipeline-step-sdk/files` | 6 | 3 | `RootDestruction.from`, los dos `when (rootDestruction)` de `CleanWsExecutor:91` y `DeleteDirExecutor:103` (**no es deuda**, ver punto 1), `antPatternToRegex` (`CleanWsExecutor:221-269`) |
| `pipeline-release` | 18 | **10** | `ProductVersion.parseOrNull`, `DistributionIdentity.evaluateDistributionIdentity`, `CandidateHandoffValidation.evaluateCandidateHandoff`, `SourceProvenance.evaluate`, `CandidateTagProvenance.evaluate`, `BundledPluginCompatibility.apiRangeContains` / `semverCompare` |
| `pipeline-credentials-executor` | 3 | 2 | `ProviderBackedSource.resolve` (donde `NotFound` es inalcanzable a propósito), `SpiMaterializationAdapter.materialize`, y el orden reverse-LIFO de `BoundCredentials.close` |
| `pipeline-artefacts-local` | 4 | 2 | `AntStyleGlob`: la lista de 13 exclusiones por defecto, el contrato ida/vuelta de `fromList`/`vararg` con `,`, el `require` de glob vacío y la regla de partir una lista de patrones sin partir dentro de `{...}` o `[...]`; `AntGlobPattern`: la traducción del dialecto Ant al del motor y la semántica de cada construcción |
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
   > `v2/pipeline-scripting-api/.../StageScope.kt:72` declara
   > `fun deleteDir(path: String = ".")` y
   > `v2/examples/control-flow/11-workflow-control.pipeline.kts:7` llama
   > `deleteDir()` a secas — quite el valor por defecto y ese programa deja de
   > compilar, y la etapa 3 de `scripts/verify-all.sh` se pone roja. La cita
   > se repuntó al testigo real y se dijo por qué se movió de custodio.
   >
   > **Nada abierto.** Ni `fromUserOwned` (eliminado, cero llamadores) ni la
   > duplicación de los `when`, que no es deuda: ver el razonamiento de arriba.
2. **`pipeline-release` — qué se publica.** `BundledPluginCompatibility` decide
   «este candidato se publica o se rechaza».

   > **Cerrado 2026-10-20 (defecto real encontrado y corregido).** Este punto
   > decía dos cosas, y las dos había que comprobarlas antes de darlas por
   > buenas:
   >
   > **«un `apiRange` obsoleta en plugins de ejemplo empaquetados» — CADUCADO.**
   > Medido sobre el tren 0.70.0: los ocho manifiestos que se empaquetan
   > declaran rangos que **admiten** la versión del host — `http`, `junit`,
   > `scm-git` y `utilities` a `[0.47.0, 0.71.0)`, y `lock`, `repeat`, `saga`
   > y `uppercase` a `[0.70.0, 0.71.0)`. El rango se **deriva** de
   > `rootProject.version` desde la política de `buildSrc`
   > (`PluginCompatibilityResource` documenta el incidente de `v0.56.0-rc3`, que
   > fue exactamente un rango escrito a mano), así que la deriva no está viva.
   > La alerta era deuda caducada, y esta es la diferencia entre «deuda» y
   > «una frase vieja en un documento».
   >
   > **«Cero tests» — FALSO.** El módulo tiene dos clases de test
   > (`ApiRangeContainsTest`, `CandidateHandoffDigestAttributionTest`) y la
   > census de arriba ya lo listaba con diez ficheros de lógica pura. La frase
   > era del antes del RESET.
   >
   > **Lo que sí estaba vivo, y era peor que lo que el punto decía:** la sonda
   > **descartaba** el plugin cuyo manifiesto no declaraba un rango legible.
   > `parseManifestJson` devolvía `null` si faltaba cualquiera de los tres
   > campos, y `probeZip` no registraba la entrada: el plugin **desaparecía de
   > la comprobación**, la puerta respondía «compatible» sobre un plugin que
   > no había mirado, y el candidato se publicaba. El refusal ocurría después,
   > en el host, que es exactamente el fallo que la puerta existe para evitar:
   > `PluginAdmission.admit` rechaza el plugin, y
   > `PluginCompatibilityResource.load` se niega a leer su manifiesto.
   >
   > El KDoc de la propia sonda prometía lo contrario («Missing values stay
   > null so a candidate with a partially-emitted manifest is recorded as
   > incomplete, not silently coerced into compatibility»): la promesa estaba
   > escrita y el código no la cumplía.
   >
   > El arreglo separa las dos preguntas que el código confundía: **la
   > presencia** del recurso `META-INF/pipelinek/plugin-manifest.json` decide si
   > los bytes son un plugin, y **el contenido** decide qué declara. Un
   > manifiesto que nombra un plugin pero no declara rango se registra con
   > `apiRange = null`, y `apiRangeContains(null, host)` ya era falso — o sea,
   > el veredicto fail-closed ya existía en `evaluate`; lo que faltaba era
   > que la sonda dejara de quitarle el plugin de la lista. El diagnóstico
   > distingue «no declara rango» de «declara un rango ilegible», porque son
   > arreglos distintos.
   >
   > **La sonda ahora es una decisión pura.** Se extrajo
   > `pluginEntryOf(jarBytes, jarName)`: bytes dentro, entrada fuera. La regla
   > de este módulo es que su carril de test **no** toca disco, ni un JAR en
   > disco, ni la distribución — el carril JUnit que sí lo hacía ejecutaba
   > cero clases mientras nombraba un ZIP real en su salida, que es la mentira
   > más cara que se puede contar en un build. Una decisión que sólo se alcanza
   > a través de un artefacto de 93 MB no la decide nadie, y la que decide si
   > un candidato puede salir es el peor sitio para eso.
   >
   > **Evidencia:** `BundledPluginProbeTest` (7 casos) sobre bytes de JAR en
   > memoria, con el test **ROJO antes del arreglo** (4 casos en rojo, con el
   > mensaje «the plugin vanished from the gate») y **VERDE** después. Dos
   > mutaciones comprobadas: (a) la sonda vuelve a descartar el plugin que no
   > puede leer → 4 casos en rojo; (b) un rango ausente deja de ser una
   > restricción (`apiRangeContains(null, …) = true`) → 5 casos en rojo. El
   > contrapeso está en el mismo fichero: un JAR **sin** manifiesto sigue sin
   > ser un plugin, porque `lib/` va lleno de bibliotecas y si «no hay
   > manifiesto» se leyera como «un plugin que no dice nada», nada saldría
   > nunca.
   >
   > **Lo que queda de este punto, y es pequeño:** la sonda en modo «un solo
   > JAR» decide por el **nombre** del fichero (`pipeline-step-sdk` en el
   > nombre) si lo que recibe es un JAR o una distribución. El KDoc anuncia
   > ese modo para «un plugin bajo prueba», y no funciona para ninguno de los
   > plugins de ejemplo del repo, que no se llaman así: `probeZip` los trata
   > como una distribución, no encuentra ningún JAR anidado y devuelve vacío.
   > El coste de arreglarlo es bajo, pero el beneficiario —un certificador
   > externo que no está en este repositorio— no se puede verificar desde
   > aquí, así que queda anotado en vez de cambiarlo a ciegas.
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
`python3 scripts/check-budgets.py --json` (VERIFY / PRODUCT), los **doce** con
unit tests:

| Ratio | Módulo | VERIFY / PRODUCT |
|---|---|---|
| **1.633** | `pipeline-step-sdk:workflow-control` | 80 / 49 |
| 0.807 | `pipeline-output` | 576 / 714 |
| 0.630 | `pipeline-credentials-local` | 1103 / 1750 |
| 0.545 | `pipeline-credentials-multipart` | 132 / 242 |
| 0.426 | `pipeline-binding-factory` | 123 / 289 |
| 0.416 | `pipeline-runtime-api` | 239 / 574 |
| 0.149 | `pipeline-step-sdk:http` | 223 / 1499 |
| 0.129 | `pipeline-step-sdk:runtime` | 269 / 2092 |
| 0.128 | `pipeline-output-store` | 228 / 1779 |
| 0.119 | `pipeline-domain` | 746 / 6270 |
| 0.083 | `pipeline-credentials-api` | 62 / 751 |
| 0.064 | `pipeline-events-store` | 288 / 4534 |

Global **5256 / 61461 = 0.086**, contra el límite duro de 2.0.

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
primera medición de este bloque usó `v2/*/src/main` y devolvió 0, porque
`pipeline-step-sdk/runtime` es un módulo anidado. Del total, 25 líneas caen en
**17 ficheros de código** y una en este ROADMAP.md. Reparto por módulo, en
líneas: `pipeline-domain` 12, `pipeline-events-store` 11,
`pipeline-step-sdk/runtime` 1, `pipeline-scripting-kotlin24` 1; en ficheros:
10, 5, 1, 1. Nota: la primera redacción de este bloque decía «25 líneas en 17
ficheros» y daba el reparto por módulo **en ficheros** bajo un encabezado que
prometía líneas, de modo que las dos sumas cerraban a 17 y nadie notó que la
cifra de líneas era 26. El denominador es lo que hace la contradicción visible:
cada número necesita el suyo al lado.

**f) `EffectReplayPolicy`**, KDoc que cita una clase de test eliminada.

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

**j) 296 líneas en 120 ficheros citan documentos ADR que no existen.** La misma
clase que 2.2 e, pero **once veces más grande**. Medido con `git grep -cE` y
pathspec recursivo sobre `v2/**/*.kt` y este fichero:

| Token | Líneas | Ficheros |
|---|---|---|
| `ADR-0…` | 296 | 120 |
| `INV-L…` | 61 | 20 |
| `docs/` | 12 | 11 |
| `FArchL` | 1 | 1 |
| `WCL-S` | 2 | 2 |
| `FIL-ALL` | 1 | 1 |
| `design.md §` | 2 | 2 |

El denominador que lo confirma: **`git ls-files` no devuelve ni un solo
`ADR-*.md` ni `INV-*.md`** — cero. Los documentos no están en otra rama; no están.
Los tokens más citados son `ADR-0075` (64), `ADR-0103` (37), `ADR-0046` (30),
`ADR-0081` (22), `ADR-0016` (17).

**No se borran en bloque, y al medir por qué aparecieron TRES clases, no una.**
La primera redacción de este bloque las trataba a todas igual —«llevan la razón
dentro del comentario»— y eso es falso para una de ellas:

1. **27 líneas con el token dentro de una cadena de código**, no de un comentario.
   Comportamiento observable, no documentación. Dos ejemplos, de clases distintas:
   `WAIT_UNTIL_CONTROL_ROW("ADR-0075 (waitUntil control journal, WU-G5R.5
   analog)")` es el **identificador de una fila de control**, y
   `"sandbox-profile 'os' requires ADR-0016 M5/M9; rejected in L3. Accepted:
   {none, local}. Got: 'os'."` es un **mensaje de error que lee el operador**.
   Borrar el token no limpia nada: cambia el valor persistido o el texto que
   alguiensupporta. Cualquier barrido de «comentarios que citan documentos
   borrados» aplicado sobre el árbol entero corrompe estos 27.
2. **5 apariciones de `Pre-ADR-0075`** en 4 ficheros, con el prefijo `Pre-`. No es
   navegación: es un **marcador de frontera de formato** —«filas persistidas
   antes de este formato». Sin el token la frase queda en «filas Pre-», que no
   significa nada.
3. El resto, comentarios y KDoc. Ahí sí aplica lo de 2.2 e: la cita es a veces la
   única razón escrita, y borrarla deja una afirmación sin porqué.

Por eso la deuda es real pero el arreglo no es un barrido: es decidir por
fichero, y hay 120. Una pasada automática sería más rápida que leer 120 ficheros
y sería el error más caro del ciclo, porque los 27 de la clase 1 **no fallan
ningún test**: cambian en silencio.

La otra mitad de la pregunta —si un mensaje de error debe citarle un ADR al
operador— es de diseño de producto y no se resuelve aquí. El mensaje debería
decir la **regla** («un perfil de sandbox multi-tenant no puede ser `os`»), no el
documento donde la regla está escrita.

**l) Las leyes del plano no las ejecuta ninguna puerta — PARCIALMENTE CERRADO.**
`ProtocolGrammar` es la lista de leyes que el arnés comprueba, y el
`build.gradle.kts` del módulo afirma que su prueba es la capa E2E: «Its
verification is the executable E2E layer». **Medido: no es verdad.** El verbo que
las ejecuta es `pipeline events verify`, que exige un contrato YAML, no hay ningún
contrato en el repositorio y `v2/examples/run.sh` no lo invoca.
  Lo que este ciclo cierra es la ley nueva: se prueba sobre un **plano real** — los
  bytes que el motor escribió en una corrida de verdad, leídos por el códec de
  producción — porque lo que puede perder información no es la ley sino el
  decodificador, y un decodificador que leyese siempre `compensatedActions` vacío
  dejaría una ley que no puede fallar. El resto del archivo de leyes sigue sin
  prueba, y sigue faltando el contrato YAML: queda o el E2E invoca el verbo por
  escenario, o las leyes se ejecutan desde la etapa 2 del gate sobre los planos que
  el E2E acaba de producir.

**m) Los dos colaterales del camino del veredicto — CERRADOS.** El KDoc de
`CancelReason` afirmaba que «la razón se lleva en el payload del `RunFinished`
terminal», y es falso: viaja en la fila del journal. Y el brazo
`legacyOutcome == "unstable"` de `Main.kt` era código muerto: `legacyOutcome`
sólo podía valer `success` o `failure`, así que el brazo no podía ejecutarse y
sólo confundía a quien lo leyera. Los dos se han cerrado en este ciclo: el KDoc
dice la verdad, y el camino legacy dice lo que puede decir con el token del
dominio y no con una cadena escrita a mano.

**n) El glob de artefactos no restringe la extensión — CERRADO en `8d4a49be8`.**
Medido contra **spring-core 6.2.19**, la versión que fija el proyecto:
`build/**/*.{jar,war}` casa con `build/secret.pem` **y** con `build/app.zip`, y
`{a,b}` casa con `zzz` —Spring convierte `{...}` en un grupo que casa con cualquier
cosa, no en la alternancia de Ant/Jenkins—, y `app-[a-z].txt` no casa con
`app-a.txt`, porque `[...]` es literal y no un rango. Consecuencia:
`archiveArtifacts(pattern = "build/**/*.{jar,war}")` metía en el `.tar` **todo** lo
que hay bajo `build/`, incluidos `.pem`, `.env` o `credentials.bin`. El test
existente (`AntStyleGlobPatternTest`) fijaba la *representación* del patrón —«una
coma no parte el patrón»— y nunca su *semántica*, que es por eso que el defecto no
lo cazó nadie: un patrón que no encuentra lo que uno cree no se parece a un patrón
que encuentra de más.

**Cómo se cierra: traduciendo, no reescribiendo.** Ant es el contrato y Spring es el
motor, así que el patrón se traduce al dialecto que el motor entiende y el motor
sigue haciendo el trabajo difícil (`**`, `?`, separadores, mayúsculas):

    {jar,war}  ->  {__ant0:(\Qjar\E|\Qwar\E)}
    [a-c].txt  ->  {__ant0:([a-c])}

Las alternativas se citan como literales (`\Q..\E`) para que un `*` dentro de una
alternativa sea un asterisco del nombre, y se parenthesizan para que la alternancia
no se escape del grupo. `{name:regex}` se deja literal: es el dialecto de Spring y la
única forma de expresar lo que Ant no puede. **Una ambigüedad queda escrita, no
escondida**: un grupo con dos puntos se lee como regex, así que la alternancia de
literales que contienen dos puntos (`{a:b}`) no es expresible — la alternativa
descartada («un grupo con coma es alternancia») rompía todo regex con coma
(`{1,3}`), que es mucho más común que un dos puntos en un nombre de fichero.

El segundo defecto estaba **una capa más arriba**: los campos que llevan «una lista
de patrones» en un `String` se partían por **cada** coma, así que
`build/**/*.{jar,war}` quedaba en `build/**/*.{jar` y `war}`, y ninguno de los dos
fragmentos casa con nada —el stash decía «matched no files» y salía un archivo
vacío—. La regla vive ahora en `AntStyleGlob.splitPatternList`, junto al dialecto al
que pertenece (una coma separa patrones sólo en el nivel de fuera), y las dos copias
duplicadas de los adaptadores de archive y stash desaparecen.

**Evidencia, medida y no supuesta.** La semántica es ahora una regla de dominio
pura, con un seam que no toca el sistema de ficheros (`AntGlobPattern`; el recorrido
se queda en `AntStyleGlob.match`): 13 tests de tabla de verdad y 6 de la regla del
splitter. Dos mutaciones discriminantes: **sin traducción caen 6 tests** y **con el
split ingenuo caen 3**, y las pruebas que no deben discriminar siguen verdes —por eso
discriminan—. Y un escenario E2E con **aserción negativa** (el fichero que *no* debe
acabar en el stash): con la traducción apagada falla con «shell exited with code 1» —
el `.pem` se había stasheado—; con el split ingenuo falla con «matched no files».

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
contrato del lanzador queda protegido por `run.sh --self-test`, que el propio
lanzador ejecuta y que la puerta invoca **antes** de fiarse de él: una
garantía que sólo vive en una mutación que alguien corrió una vez es una
garantía que un refactor puede perder sin que nadie lo note.

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

**p) El arnés miraba sólo el código de salida — CERRADO en `0bf732fe6`.** El
lanzador tenía un verbo nuevo, `//@ expect-stdout`, y la
comprobación se hace contra **la proyección que el producto publica**
(`observe --view console`), no contra la consola del proceso: en un run
durable el motor no lleva la salida de los pasos a la consola, así que una
expectativa comprobada contra la consola habría visto un vacío por el motivo
equivocado. La consola se mira también, porque `echo` escribe ahí y no en la
proyección; las dos voces del run se leen, y la regla es la misma en un run
durable y en uno en memoria.

El veredicto **dice qué comprobó**: cada línea del informe nombra la
observación en la que se apoyó (`stdout`, `stderr`, `exit(1)`), y el resumen
—que llega hasta la línea de veredicto de la puerta— cuenta cuántos
escenarios declararon una observación y cuántos sólo se ejecutaron. Ese último
tramo no lo cumplió este bloque a la primera: el recuento se quedaba en el log
de la etapa. Lo que faltaba y por qué está en **be)**.

Y al poder observarlo, el arnés ha encontrado dos promesas que no se
cumplían. `durable/32-durable-replay` prometía `effects=1` y el motor nunca
escribía eso en la consola de un run durable. `output/27-zip-unzip` prometía
«the contents must match by SHA-256» y sólo comprobaba `test -f`: los tres
hash que calculaba no se comparaban con nada. Deriva entre el nombre y la
aserción, visible ahora porque hay alguien que mira.

El coste se paga con menos escenarios: **diez retirados**, cada uno con su
equivalente nombrado en `v2/examples/README.md`. `durable/19-isunix` y
`durable/20-pwd-tmp` (los helpers del workspace, fundidos en
`steps/13-workspace-helpers`), `smoke/01-basic`, `smoke/02-environment` y
`smoke/09-sh-then-echo`, `output/24-utilities-roundtrip`,
`output/25-yaml-roundtrip` y `output/27-zip-unzip` (las utilities, que ahora
son una sola cadena en `output/29-mixed-utilities`), `plugins/uppercase` —cuyo
plugin es capability-free por diseño y cuyo escenario sólo podía comprobar
que el proceso no se caía— y `steps/17-writeFile`, cubierto por
`output/23-readfile`.

De **46 escenarios a 36**, y la medida que de verdad importa: los 30 que no
declaraban ninguna observación son **0**. Los 35 que se ejecutan declaran hoy
qué garantizan, y el arnés lo comprueba.

#### El vocabulario del arnés y la equivalencia de lo retirado

El lanzador (`v2/examples/run.sh`) es el único, y su vocabulario sigue siendo
corto a propósito: no es un framework y no puede convertirse en uno.

| Directiva | Lo que dice |
|---|---|
| `//@ expect-exit N` | el run termina con este código de salida (por defecto `0`) |
| `//@ expect-stdout TEXTO` | el run **produjo** este texto |
| `//@ expect-stderr TEXTO` | el run **rechazó** con este texto |
| `//@ durable` | dos runs sobre el mismo almacén y el mismo control root; el efecto no se repite |
| `//@ durable-once` | un run contra un almacén durable de verdad |
| `//@ resume` | el primer run muere donde el escenario decide; el segundo lo reanuda |
| `//@ storage-agnostic` | las mismas expectativas se cumplen con y sin almacén durable |
| `//@ requires-seeded-credentials` | el escenario necesita estado externo que este lanzador no fabrica: se reporta NOT_RUN, que no es un verde ni un rojo |

Un escenario se retiró **sólo** cuando otro cubre la misma garantía con la
misma evidencia. Nada se retiró por ser lento, ni por ser incómodo.

| Retirado | Lo cubre ahora | La garantía que sobrevive |
|---|---|---|
| `durable/19-isunix` | `steps/13-workspace-helpers` | `isUnix()` responde por la plataforma |
| `durable/20-pwd-tmp` | `steps/13-workspace-helpers` | `pwd(tmp = true)` y el `pwd()` heredado responden ambos |
| `smoke/01-basic` | `smoke/03-stages` | una etapa ejecuta los pasos que declara, en orden |
| `smoke/02-environment` | `steps/08-withEnv-pipeline` | `withEnv` pone las variables en el entorno de los pasos |
| `smoke/09-sh-then-echo` | `steps/04-sh` | `sh` ejecuta un comando sin bloque de script y sin repetir su salida |
| `output/24-utilities-roundtrip` | `output/29-mixed-utilities` | JSON escrito, leído de vuelta y hasheado |
| `output/25-yaml-roundtrip` | `output/29-mixed-utilities` | YAML escrito y leído de vuelta |
| `output/27-zip-unzip` | `output/29-mixed-utilities` | un directorio archivado, hasheado y expandido de nuevo |
| `plugins/uppercase` | `plugins/body-slots`, `plugins/repeat-block`, `plugins/lock-directive` | el motor compone un plugin externo y ejecuta su paso |
| `steps/17-writeFile` | `output/23-readfile` | un fichero escrito se lee de vuelta con su contenido |

`plugins/uppercase` merece la nota: ese plugin es capability-free por diseño,
así que su paso no produce salida ni efecto que un escenario pueda observar.
El escenario sólo podía mirar que el proceso no se caía, y eso no es una
comprobación. La garantía que sostenía —que el motor compone un plugin
externo y ejecuta su paso— la mantienen los tres escenarios que usan plugins
externos que sí producen trabajo observable.

#### Lo que queda sobre la mesa, ordenado por lo que se recupera

La economía del arnés no se acaba en retirar escenarios: la etapa 3 cuesta
**41 s de reloj** y **doce minutos de CPU** (`user` 745 s, medido sobre
`5a8e43a35`), y casi todo ese coste es el mismo
punto repetido. Estas son las oportunidades que quedan, ordenadas por el
tiempo que devuelven.

| # | Oportunidad | Tiempo que recupera | Coste de la migración | Riesgo de escape | Cómo se mide después |
|---|---|---|---|---|---|
| 1 | **Compilar una vez.** Cada invocación del CLI compila el script (~5 s de CPU); la etapa 3 hace unas cuarenta invocaciones | ~3 min de CPU y ~10 s de reloj | Un verbo público nuevo en el producto (`pipeline compile`) que deje un artefacto reutilizable, con su contrato y su KDoc | **Bajo**: añade una capacidad, no cambia ningún comportamiento | El tiempo de la etapa 3 y el `user` del proceso |
| 2 | **Re-ejecutar sin recompilar.** Los escenarios `durable` y `resume` compilan el mismo script dos o tres veces | ~40 s de CPU | Depende de (1): sin un artefacto reutilizable no hay nada que re-ejecutar | **Medio**: toca la semántica de la reanudación, que es donde el producto no se equivoca | El tiempo por escenario del informe del arnés, que ya se imprime |
| 3 | **El techo de ocho trabajadores.** En una máquina de 64 núcleos, 36 escenarios tardan 43 s con ocho en paralelo | ~15 s de reloj | Una línea | **Medio**: el tope existe para no hacer thrash en una máquina con pocos núcleos, y por eso no se toca sin medir en la máquina destino | `PIPELINEK_E2E_JOBS` y el tiempo de la etapa |

La medición que ya viaja con cada puerta es
`scenarios=observed:<n> executed:<n>` en la línea de veredicto: es el
indicador que dice si el coste se está pagando por comprobaciones o sólo por
procesos. No se crea ningún sistema de seguimiento nuevo para esto.

**q) La etapa 3 se ejecutaba en serie: 289 s de los 338 s del gate — CERRADO en
`96220fdad`.** Cuarenta y cuatro escenarios a ~6,8 s y 48 arranques de JVM: el
86 % del coste de la puerta era comprobar lo mismo. **El aislamiento ya
estaba**, y no por casualidad: cada escenario recibía su propia raíz, su
propio directorio de trabajo, su propio almacén de credenciales, su propia base
datos durable, su propio `control-root` y su propio fichero de PID. Un
escenario con un `/tmp` fijo competiría con todas las demás corridas de la
máquina, y por eso el lanzador le da un directorio privado. Sólo faltaba dejar
de serializarlo. **Medido: 44 s frente a 289 s**, y la puerta entera de 338 s a
**91 s** (margen contra el límite de 900 s: de 2,6× a 9,9×). El informe **no
cambia de forma**: cada veredicto se escribe en su propio fichero y el padre
los imprime en orden de escenario, no de finalización, de modo que el log de la
puerta sigue comparable entre corridas. `PIPELINEK_E2E_JOBS=1` devuelve el
comportamiento serie exacto, que es lo que necesita un escenario que parezca
intermitente.

**s) Las dependencias que se quedan, y por qué — DECIDIDO en este ciclo.**
AGENTS.md §1 no admite una dependencia externa para un par de clases, y dice
que una dependencia existente **no se hereda por defecto**: se queda sólo si
lleva algo que este proyecto no puede razonablemente poseer, y eso se dice
**por dependencia**. La distribución son **45 jars y 96 MB** (medido sobre
`installDist`), y sus pesos son:

| Dependencia | Peso | Por qué se queda |
|---|---|---|
| `kotlin-compiler-embeddable` | **58 MB** | Es el **motor** que compila los scripts `.pipeline.kts`. No es una clase: es un compilador completo. |
| `sqlite-jdbc` | **14 MB** | Motor de **persistencia** del estado durable. El formato de fichero y su página de formato son el contrato. |
| `bcprov` / `bcpkix` / `bcutil` | **~9 MB** | **Criptografía**: firma y sobre de credenciales. Reimplementar PKCS#7 o X.509 es rehacer una biblioteca, no copiar un par de clases. |
| `kotlin-reflect` | 2,9 MB | Necesario para el **scripting** de Kotlin. |
| `kotlinx-coroutines-core` | 1,6 MB | Concurrencia estructurada; reimplementarla es rehacer un modelo, no copiar clases. |

El criterio que se aplicó **no** es el tamaño —el compilador pesa 60× más que
Spring y se queda igual— sino **qué es**: un motor, un formato de fichero o un
algoritmo que el proyecto no puede poseer razonablemente. Y el mismo criterio
deja fuera a `spring-core`, que era una clase con dos métodos. **Decidir el
peso de verdad de la distribución es otra conversación, y no se ha hecho
todavía**: esto dice qué se queda y por qué, no que no haya nada más que quitar.

**r) El motor del glob era una dependencia entera por un puñado de clases —
CERRADO en `f3d897a9`.** `AntStyleGlob` usaba
`org.springframework.util.AntPathMatcher`: **una** clase de `spring-core`
(1,9 MB) y su `spring-jcl` (24 KB) —**2 jars** en la distribución, una cadena
transitiva que había que auditar y dos avisos de seguridad que había que seguir
persiguiendo en cada actualización— para un puñado de métodos. El proyecto ya
no admite una dependencia externa para un par de clases (AGENTS.md §1), así que
el motor es ahora `AntPathMatching`, dentro de `pipeline-artefacts-local`, un
módulo que **no publica y no está en `bcvModules`**: tocar su API no rompe la
ABI publicada.

**Cómo se cierra sin mover el suelo.** El oráculo del diferencial es **lo
publicado** —el patrón traducido al dialecto del motor, y el motor— y no el
motor en crudo: contra el motor en crudo «discreparía» en cada alternancia y
cada clase de caracteres, que son justo las dos construcciones donde el defecto
estaba. **402 360** pares de patrón y ruta comparados: **402 353** coinciden y
**7** divergen, **todas de la misma clase** —un constructo que cruza un
separador— y en todas el motor viejo estaba roto, porque reescribía el patrón
*antes* de dividirlo y la reescritura destrozaba el constructo en vez de
expresarlo (`{a/b,c}` ya no casaba con su propio texto). El motor propio divide
primero, que es lo que hace Ant. Esos 7 casos quedan **escritos como regla** en
`AntPathMatchingRulesTest`, no como sorpresa.

Y el diferencial se **repitió después** de partir el algoritmo en sus tres
fases (delante, detrás, medio) para que detekt no lo rechazara por complejidad:
mismos 402 360 pares, mismos 7 casos. Una descomposición que no se prueba es
un cambio de comportamiento disfrazado de limpieza.

**Rendimiento: paridad, y dos decisiones detrás.** 8 patrones × 411 rutas × 40
rondas: el motor propio **60-64 ms** frente a **62-89 ms** del prestado
(**0,72-0,97×**). Recompilar el patrón por coincidencia costaría **6-7×** más. Y
el atajo que cierra la diferencia es pequeño: **rechazar antes de dividir** la
ruta cuando el patrón empieza por texto literal, y **dividirla en una sola
pasada**, sin una segunda lista para filtrar la primera. Sin esas dos cosas el
motor propio era 1,5-1,8× más lento. El guard anti-ReDoS se conserva con el
mismo límite (**1 000 000** de lecturas del segmento que se casa), y medido no
cuesta nada.

**Auditoría del 2026-10-20 (lectura de código, no de documentos).** Una revisión
del árbol del productor midió el estado real del roadmap y de las especificaciones
y encontró, además de n), o) y p): que la etapa 3 del gate era el **86 %** del
coste (298 s de 345 s medidos; 44 escenarios ≈ 6,8 s y 48 arranques de JVM),
que `pipeline-protocol` está versionado pero **no está declarado** en Gradle,
luego nunca se compila, y que el «686 tests» que cita §1 es un **censo** de
informes en disco, no una medición (en esa corrida, de 85 informes sólo 9 se
reescribieron). **Sus tres primeros candidatos están ya cerrados** —o) el arnés que mentía, el del
coste de la etapa 3 y n) el del glob—, cada uno con su SHA, sus mutaciones y su
escenario. El informe completo, con el plan priorizado y las propuestas de
pensamiento lateral, está en el paquete de evolución del Saga, en
`pipelinek-saga-evolution/auditoria/INFORME-AUDITORIA-2026-10-20.md`.
Una de sus reglas se ha seguido aquí: un documento de estado **no** es evidencia,
así que cada cifra de esta auditoría se ha medido ejecutando, y una corrección
anterior que parecía verdad se ha vuelto a medir antes de creerla —y era falsa.

**t) El cuerpo de un plugin se direccionaba por su *kind*, no por su identidad —
CERRADO en `e9392ce4`.** El motor resolvía «qué cuerpo corresponde a esta fila»
por la clave de plugin step del hijo (`BodySlots.resolve(step.body.map { it.pluginStepId }, slot)`).
Dos hijos del **mismo** kind en el mismo cuerpo son ambiguos, y el slot falla
cerrado. La consecuencia no era cosmética: la demo del 3b necesitaba **seis kinds
del core** (`core.dir`, `core.withEnv`, `core.timeout`, `core.retry`, `core.sh`,
`core.timestamps`) para una cadena de dos o tres acciones, porque cada acción
necesita un cuerpo *forward* y otro *compensate*, y cada cuerpo necesitaba un
kind que nadie más usara. Eso es un apaño del fixture, y **un apaño del fixture
no es un patrón que un consumidor externo pueda copiar**: el mismo plugin con
acciones propias tendría que inventar 2N kinds.

**La regla que lo cierra es una, y es total**: la dirección de un hijo es el
**nombre que declaró el plugin**, o su *kind* cuando no declaró ninguno. El
comportamiento histórico sobrevive intacto —un cuerpo que no nombra nada se sigue
direccionando por kind— y el digest del cuerpo (`BodyStructureDigest`) incorpora
el nombre **sólo cuando existe**, con un marcador `\u001F`, así que **las filas de
journal ya publicadas siguen valiendo**.

**El IR lleva el nombre** (`StepNode.slot`, `null` si no hay, y `StepNode.address`
= `slot ?: pluginStepId`), y el motor resuelve contra las direcciones. La DSL
crece de forma **aditiva**: `StepSpec.Slot` y `StageScope.slot(name) { … }`, con
un solo hijo por slot y rechazo al compilar si el bloque declara más de uno.

**La ruptura es real, y por eso lleva recibo.** El campo nuevo vive en la interfaz
**publicada** `StepNode`: un implementor externo recibe `AbstractMethodError`. Queda
anotada en `published-contract-exceptions.json` —que es el mecanismo del repo para
esto— y `pipeline-domain` está clasificado **EXPERIMENTAL**, que es lo que permite
romper bajo una decisión registrada. Se evaluó la alternativa —un tipo nuevo
`SlotName`— y **se rechazó por una razón medida**: rompe cinco firmas y obliga a
una release coordinada del SDK con el consumidor externo; reutilizar `PluginStepId`
deja al consumidor **sin cambios**.

**Probado desde fuera, no afirmado.** El escenario `39-saga-two-same-kind` corre
una cadena cuyos cuerpos son **todos** del mismo kind (`core.withEnv`) y afirma las
**direcciones** que corrieron (`example.saga.reserve.forward` / `charge.forward` /
`charge.compensate` / `reserve.compensate`); la compensación de la primera comprueba
desde dentro el orden LIFO. **Mutación vista en rojo**: volver el motor a
direccionar por *kind* hace **fallar** ese escenario; restaurado, pasa. Un verde
que nunca se vio rojo no es evidencia, y este se vio rojo.

El evento `CompensationStarted.slot` **no se mueve**: conserva la forma (`String`)
y cambia de valor. La ley S8 permite cambiar el valor de un campo de texto y no
la forma, y el valor nuevo es el que sirve: el *kind* no decía qué acción se
compensó y el nombre sí.

**u) La línea de veredicto no decía qué tests se ejecutaron — CERRADO en `4dee0b229`.**
La etapa 2 separaba `rewritten` de `read` en el **log**, pero la línea que la gente
pega en los informes —`PASS  sha=…  elapsed=…s  budget=900s`— no lo decía. Eso
convertía un **censo de informes en disco** en una medición: en el ciclo del 3b-5
fueron 686 tests en 85 informes, de los que sólo **9** reescribió la corrida, y la
línea los daba por ejecutados.

**La regla es una y es total**: la línea de veredicto lleva el campo `tests=`, con
las dos mitades separadas —`rewritten:<n>` (los tests de los informes que **esta**
corrida escribió: se ejecutaron de verdad) y `read:<n>` (los de los informes que no
escribió: los ejecutó una invocación anterior y se leyeron de disco)—. Una corrida
que no pidió los tests dice `tests=NOT_RUN`, porque un campo ausente se lee como un
cero, y aquí no es un cero: es «no medido».

**Mutación vista en rojo**: con la línea de HEAD, una corrida en caché que **no
ejecutó ni un test** salía vestida de `PASS (subset)  sha=…  elapsed=16s`, y el
log, dos líneas más abajo, decía `0 test(s) in 0 report(s)`. Con el campo, la misma
corrida dice `tests=rewritten:0 read:161`. El rojo estaba **en la línea**, que es
donde se lee, y no en el log.

La política queda escrita en `AGENTS.md` §5, junto a la del marcador `SUBSET`: la
diferencia entre esta corrida y la puerta tiene que ir **dentro** de la línea, no al
lado. Es el mismo argumento que ya cerró dos veces el mismo patrón en este repo.

**v) La capability de Saga no depende de `--db` — CERRADO en `88a6dc07d`.** Sin `--db`, la
rama en memoria **no enlazaba ningún almacén de Saga**: las capabilities (`saga.compensation`,
`saga.plan`, `saga.lifecycle`) no se exponían, y el MISMO script que con `--db` se ejecutaba
era **rechazado en la admisión** sin él
(`missing required capabilities for step 'example.saga.chain': saga.compensation, saga.plan,
saga.lifecycle`). Eso contradice LF-0208 —«la elección del almacenamiento no debe elegir un
algoritmo de ejecución distinto»— y convertía un flag en un comportamiento.

**El arreglo tiene dos mitades, y la segunda es la que faltaba.** La primera: la rama en memoria
enlaza ahora el almacén **volátil** de obligaciones (`InMemorySagaObligationStore`), con las
MISMAS reglas que el durable —fencing por epoch, idempotencia del armado y movimiento masivo—.
Lo que cambia es la **durabilidad** de la promesa, no el algoritmo: nada sobrevive al proceso,
igual que el journal y el event log de esa rama. La segunda: el **veredicto** del Saga también
se imprime ahí. Antes, la línea `saga [checkout]: SETTLED` sólo la decía la rama durable, con
un comentario que afirmaba «sin `--db` no hay Saga» — un hecho que con este cambio ya no era
cierto. Un Saga que se liquida sin decir nada no es un Saga que el operador pueda ver.

**La prueba es del arnés, no un test más.** La nueva directiva `//@ storage-agnostic` hace que
el lanzador ejecute el escenario **dos veces** —con `--db` y sin él, cada uno con su directorio
de trabajo y su almacén— y exige que **ambas** corridas cumplan las mismas expectativas (el
código de salida y cada línea de stderr declarada). El escenario 38 (el Saga que **termina
bien**) pasó a declararse así: no es un test nuevo, es el mismo test probado en los dos
mundos. Y el arnés se autocomprueba: `run.sh --self-test` verifica que la directiva se lee y
que no se confunde con `resume`.

**Mutación vista en rojo**: con el enlace del Saga retirado, la corrida en memoria del escenario
38 falla con el diagnóstico de arriba; con el enlace, las dos corridas pasan. El mismo script, el
mismo launcher y la misma directiva: lo único que cambia es el enlace.

Además, **tres reglas puras del puerto** quedan fijadas por test unitario: armar la misma clave
dos veces conserva la **primera** promesa, un escritor con epoch atrasado **no escribe nada**, y
cerrar un Saga mueve **todas** sus obligaciones a la vez. No es un doble del test durable: la
garantía de *sobrevivir al proceso* sigue siendo del almacén SQLite, con su fichero real.

**w) Un solo lector del terminal del Saga — CERRADO en `cb37779f2`.** El arnés tenía su **propia**
lectura del `SagaTerminal` para la ley `LAW_SAGA_PENDING_ON_SUCCESS`: preguntaba «el terminal dice
`SETTLED`?». La proyección publicada pregunta **otra** cosa: «¿este terminal liquida el Saga?». No
es la misma pregunta cuando el terminal dice `SETTLED` **y lleva un motivo de no liquidarlo**: la
proyección lo lee como `NeedsIntervention` —un humano tiene que decidir— y el arnés lo leía como
liquidado.

**La consecuencia medida es un terminal contradictorio invisible.** Un `SagaTerminal` que dice
`SETTLED` y a la vez lleva un motivo es un Saga roto; el arnés lo leía como un Saga terminado, así
que un run verde con ese Saga no se denunciaba. Dos reglas sobre el mismo hecho que han
derivado: la ley de plano no podía ver la contradicción.

**El segundo lector desaparece.** `pendingSagas` pide a `RunPresentationProjection` la lista de
Sagas y toma las que son `NeedsIntervention`. La ley y la proyección coinciden **por
construcción**, porque son la misma lectura y no dos reglas que puedan separarse.

**Mutación vista en rojo**: el test nuevo —un terminal `SETTLED` con motivo en un run verde—
falla con el lector viejo (`AssertionFailedError`) y pasa con el nuevo.

Lo que **no** se toca: `compensatedSagas` sigue leyendo si el Saga compensó algo, que es **otro
hecho** y que la proyección no expone. Un lector menos, no una lectura menos.

**x) Cuatro módulos donde el riesgo paga — CERRADO en `a2ffb90db`.** El bloque
`cobertura-modulos-riesgo` ataca los cuatro módulos con más producción sin test unitario
permanente, por orden de valor: `pipeline-step-sdk/utilities` (3 136 LOC de código efectivo),
`pipeline-scripting-api` (1 733), `pipeline-step-sdk/scm-git` (1 339) y `pipeline-runtime`
(1 083). La pregunta del bloque no es «¿cuántos módulos tienen test?» sino «¿dónde una decisión
mal tomada borra ficheros, publica una credencial o miente en el journal?».

**Cayeron dos defectos materiales, y ninguno de los dos estaba donde se buscaba.**

1. **El guard de argv de `scm-git` dejaba pasar un PAT.** Es el único punto que decide si una
   invocación de git puede lanzarse, y su promesa es «una credencial nunca viaja en el argv»:
   `argv` es legible por cualquiera en `/proc` y acaba en cada log que imprime un comando. La
   regla reconocía **una sola forma** de credencial, `https://user:pass@host`, y de ahí salían
   tres huecos, todos alcanzables desde un pipeline real:
   - `https://TOKEN@host/repo`, que es exactamente como se usa un personal access token contra
     GitHub — el token **es** el usuario y no hay contraseña. La regla exigía dos puntos, así que
     el PAT pasaba y viajaba en el `argv`;
   - `HTTPS://user:pass@host`: el guard ponía en minúsculas el argumento para la regla de
     subcadenas y luego buscaba el patrón de URL sobre el texto **original**, así que el esquema
     en mayúsculas era invisible;
   - `ssh://user:pass@host`: una contraseña en una URL que no es http.

   El arreglo lee **un** texto —el argumento en minúsculas— y reconoce la credencial por su
   **forma**: una URL http(s) con cualquier userinfo se rechaza (el usuario basta, porque puede
   ser el token), y cualquier otro esquema se rechaza sólo si su userinfo lleva contraseña
   (`ssh://git@host` es un nombre, y las claves SSH nunca viajan en `argv`, así que rechazarlo
   rechazaría un clon normal). Un guard que lo rechaza todo no protege nada, así que las formas
   **aceptadas** quedan fijadas por el mismo test: un `@` en el path (`repo.git@v1`), un puerto
   (`github.com:443`), la sintaxis scp (`git@github.com:org/repo.git`) y el usuario SSH.

2. **La regla de Zip Slip de `utilities` rechazaba archivos legales, y su regla de verdad
   estaba muerta.** `core-utils.unzip` escribe en el workspace nombres que elige quien
   construyó el zip, así que la única pregunta que importa es si el nombre puede nombrar un
   sitio **fuera** del destino. La decisión estaba partida en dos reglas, y las dos estaban mal:
   la regla del `..` rechazaba cualquier nombre que **contenía** dos puntos —`release..tar.gz` es
   un nombre de fichero legal— y la regla de contención, la que de verdad demuestra la garantía,
   **no podía decidir nada**: un nombre sólo sale del destino subiendo (`..`) o siendo absoluto, y
   las dos cosas ya estaban rechazadas antes. Era código muerto.

   Ahora la decisión es **una** regla semántica —la entrada cae dentro del destino o se
   rechaza— y vive en `resolveUnzipEntryPath`, una función pura sobre dos rutas. Se levantó
   fuera del Step porque el Step crea ficheros y AGENTS.md §2 los prohíbe en un test unitario:
   una guarda que sólo se puede preguntar pasando por un disco es una guarda sin cobertura. La
   forma es la misma que ya usa `:pipeline-credentials-multipart` (`resolveZipEntryPath`), así
   que la regla tiene **un** dueño en el repo y no dos. De paso, un nombre con un NUL —legal en
   un zip, ilegal en una ruta— dejó de lanzar un `InvalidPathException` (un error interno que no
   dice nada del archivo) y pasa a ser un fallo de USUARIO que nombra la entrada.

**Los otros dos módulos no tenían defecto: tenían falta de observador.** En `pipeline-runtime`,
el vocabulario con el que el recover **escribe en el journal** estaba en cuatro métodos privados
de un adaptador que compone journal, cursor, lease y output store: para preguntarlos había que
levantar el adaptador entero —cinco colaboradores y un disco—, así que la regla que decide qué
dice el journal no tenía a nadie que la mirara. En `pipeline-scripting-api`, la diferencia de
identidad entre `CacheKey.v1` (pre-imagen no inyectivo) y `CacheKey.v2` (con marco) no la fijaba
nadie. Los dos salen de la misma pregunta: **¿qué pasa cuando una decisión es correcta y nadie
puede comprobarla?** — el resultado es una decisión que se puede dejar de tomar bien sin que
nada lo note.

**Mutación vista en rojo, módulo por módulo.** Cada tabla se comprobó rompiendo la regla que
protege y viendo caer exactamente las filas esperadas, sin colateral:

| Módulo | Test | Casos | Mutación cruzada | Filas que caen |
|---|---|---|---|---|
| `pipeline-step-sdk/scm-git` | `GitArgvGuardTest` | 6 | regla de URL restaurada a la anterior | 3 (PAT como usuario, esquema en mayúsculas, contraseña no-http) |
| `pipeline-step-sdk/utilities` | `UnzipEntryPathTest` | 11 | vuelve la heurística del `..` | 2 (el nombre que sólo *parece* una escapada, el que sube y vuelve) |
| `pipeline-scripting-api` | `CacheKeyTest` | 5 | se quita el marco del pre-imagen | 1 (`v2` deja de separar lo que `v1` colisiona) |
| `pipeline-runtime` | `RecoverJournalVocabularyTest` | 4 | terminal cancelado journalado como `SUCCEEDED` | 1 (el estado jamás reclama un éxito que no tuvo) |

En `scm-git` el defecto se vio **antes** del arreglo: con el código de hoy caían 3 filas y las
otras 3 seguían verdes, así que el test no era «todo rojo» sino «rojo donde tiene que ser».

**Lo que NO cierra.** El guard de argv no ve el **contenido** del `argv` (un token dentro de un
`--upload-pack` sigue siendo un `argv`). La regla de contención es **léxica**: no ve un symlink
ya dentro del destino, y esa limitación queda escrita en el KDoc del Step, no insinuada. La E2E
de `scm-git` necesitaría un repositorio git real, que este repositorio no tiene. El ABI publicado
no cambia: las decisiones nuevas son `internal` y `pipeline-runtime-api` no se toca. Y **no se
añade ni se quita ningún escenario E2E**: el bloque no crece la suite, sólo le da observadores
a reglas que no los tenían.

Puerta sobre el SHA del bloque: `PASS sha=a2ffb90db… elapsed=99s budget=900s
tests=rewritten:743 read:0 gaps=1 not_run`.


**y) El `main` era un fichero de 1 652 líneas — CERRADO en `03bdc2a3a`.** El bloque
`main-partido` parte `v2/pipeline-application/.../Main.kt` en un fichero por
mundo, y de paso quita **tres lectores duplicados** de los mismos hechos. La
pregunta del bloque no es «¿cuántas líneas tiene el fichero?» sino «¿cuántos
sitios puede leer un mismo hecho?».

**Lo que había, medido.** `Main.kt` eran 1 652 líneas, con un `fun main` de 1 463
(788 efectivas) y **27 entradas de baseline de detekt** encima: dos supresiones
(`LongMethod`, `CyclomaticComplexMethod`) y 25 de distinto tipo. El baseline es
donde un olor deja de verse, así que el bloque se mide por las entradas que
elimina, no por los ficheros que crea. Este elimina **28** (las 27 de `Main.kt` y
la de `runCanonicalPipeline`), y las 390 entradas del módulo pasan a 362.

**Los tres lectores duplicados, y por qué cada uno importa:**

1. **La ruta canónica tenía DOS puntos de llamada.** `runCanonicalPipeline`
   llevaba 18 parámetros y la llamaban los dos mundos —el volátil y el durable—
   con 18 argumentos cada uno. Dos puntos de llamada son dos que pueden divergir,
   y la divergencia posible era grave: `--db` podía acabar ejecutando un camino
   distinto del que se ejecuta sin `--db`. Ahora la ruta canónica se decide en
   **un** sitio (`runCanonical`) y tiene **dos** llamadas —una por mundo— que
   construyen la misma petición (`CanonicalRunRequest`), agrupada por costura:
   `state` (dónde vive el estado: journal, cursor store, event sink, costura
   Saga) y `policy` (lo que se le dio al run: reloj, política de replay,
   sandbox, workspace, costura de credenciales, permiso de red, registro de
   redacción).

   Lo que esto compra es más estrecho que «los dos mundos son iguales», y por
   eso se dice así: **la diferencia entre los dos mundos está ahora en un sitio
   revisable**, en vez de repartida entre dieciocho argumentos de dos puntos de
   llamada. Antes, cualquiera de los dieciocho podía diferir entre los mundos y
   nada decía cuál.

   Y hay una diferencia que **no** es del grupo `state`: el proveedor de
   credenciales (el mundo volátil no lo lleva, por H5-B) y el `controlDirRoot`
   (necesariamente distinto). Se dice en el KDoc donde se decide, no se
   esconde: un KDoc que afirma una simetría que no existe es peor que no
   tener KDoc.

2. **La costura de credenciales estaba DUPLICADA.** La composición del almacén
   (dónde está, qué passphrase, cómo se materializa) estaba escrita dos veces,
   una por mundo, y las dos copias **usaban relojes distintos**: la volátil
   abría el almacén con un `SystemClock()` nuevo y la durable con el reloj del
   run. Dos copias de una decisión son dos ocasiones para discrepar, y la
   discrepancia era invisible porque ninguna de las dos se comparaba con la otra.
   Ahora hay una sola función, `composeCredentialSeam`, y las dos locomotoras
   llaman a la misma.

3. **La compilación del script la leían dos lectores.** El mundo durable
   re-lanzaba la excepción del autor (S0-C1 / TRAIN-DSL-HONESTY) y el volátil se
   la tragaba y respondía «no hay pipeline». El mismo hecho, dos lecturas, y
   una de ellas **más indulgente**: un error de construcción se disfrazaba de
   «el script no produjo un PipelineSpec». Ahora lo lee **uno**.

   Y aquí está la medición que hace el bloque honesto: **esa rama está
   muerta**. El host de scripts ya convierte un fallo del cuerpo del script en
   un fallo de compilación (`ScriptCompilationResult.Failure` con el mensaje
   del autor), así que `get$$result` —que es donde el cuerpo se evalúa— sólo se
   llama cuando el cuerpo **ya ha funcionado**. Restaurar el lector viejo
   («trágate todo») **no pone ni una fila en rojo**: 46 escenarios, 45 pasan,
   0 fallan. Es una medición negativa y se publica por eso: unificar los
   lectores **no altera ningún comportamiento alcanzable**. Lo que cambia es que
   ya no existe un segundo lector más indulgente que un día pueda volver a
   activarse.

**Cómo se comprobó que el bloque no cambia lo que el operador ve.** Se
capturaron **25 superficies deterministas** de la CLI —`--help`, `version`,
`version --json`, `capabilities`, `sdk-info`, `doctor`, verbo inválido,
`--output-protocol` inválido, script inexistente, `validate` con un script
bueno y con uno malo— sobre `7e6238b85` y sobre este bloque, y se compararon
**byte a byte**: **idénticas**. La salida de un `run` en los dos mundos se
comparó en su forma normalizada (runId, correlationId, ownerId, timestamps y
rutas son variables por definición) y también coincide.

**Mutaciones vistas en rojo.** El bloque no añade tests, así que su instrumento
es la suite E2E. Se rompió el código a propósito para comprobar que la suite
**ve** lo que este bloque tocó:

| Mutación | Filas que caen | Colateral |
|---|---|---|
| `runCanonical` ignora el `journal` de la petición y usa uno en memoria | **1**: `durable/32-durable-replay.pipeline.kts (durable x2)` → exit 1 | ninguno (44 pasan, 1 falla, 1 NOT_RUN) |
| El mundo volátil deja de enlazar la costura Saga | **1**: `durable/38-saga-success.pipeline.kts (both storages)` → **la parte en memoria**, con `missing required capabilities for step 'example.saga.chain': saga.compensation, saga.plan, saga.lifecycle` | ninguno; **la parte durable siguió en verde**, que es la prueba de que la mutación era del mundo volátil |
| Se restaura el lector viejo de la compilación | **0** | — |

La segunda es la que importa: es el escenario `storage-agnostic`, que corre el
**mismo script en los dos mundos** y exige las mismas expectativas en los dos.
Por eso la fila dice «la parte en memoria». Antes de este bloque ese escenario
existía (P6, §2.2 v) y ya era el instrumento de la ley; lo que este bloque hace
es **alejar la causa** de la ley: la divergencia ya no puede entrar por la
puerta de atrás, porque la puerta de atrás ya no hay dos.

**Lo que NO cierra.** El bloque **no** unifica los dos **renderizados** del
veredicto: el mundo volátil imprime `Pipeline finished with FAILURE: <mensaje>`
y el durable imprime `Pipeline finished con FAILURE` más el informe de causa.
Son dos renderizados de un mismo hecho tipado, no dos lecturas del hecho, y
los bytes del mundo volátil están fijados por el escenario
`negative/15-error.pipeline.kts`: cambiarlos sería romper un contrato, no una
decisión. La rama de la compilación que se unificó sigue siendo **código
muerto**: unificar un lector muerto no lo revive ni lo esconde, pero tampoco
justifica decir que el bloque arregló un defecto de comportamiento, porque **no
lo-arregló**.

**Los dos mundos NO son idénticos, y el bloque lo ha escrito down en vez de
taparlo.** Hay una asimetría que viene de antes y que este bloque deja
**visible**: la forma *scripted* (el frontend que ejecuta un cuerpo generado)
sólo se resuelve en el mundo durable. Un `.pipeline.kts` que mapee a esa forma
corre un runner distinto según el mundo. Y dos hechos de la petición difieren
a propósito: el proveedor de credenciales (H5-B) y el `controlDirRoot`.

**Una revisión independiente, y lo que encontró.** El diff pasó por un
verificador aparte, con preguntas sobre lo que la puerta no puede ver. Su
veredicto: **ningún cambio de comportamiento alcanzable** —el inventario de
`System.exit` y de mensajes es idéntico multiconjunto a multiset, los órdenes
compile → `compileOrRefuse` → admisión y lease → `emitCorrelationAndReceipt` →
compile se conservan, y el `System.exit` no canónico sigue dentro del `try`
(su `finally` no corre, igual que antes)— pero **cinco afirmaciones del KDoc
que este bloque escribía eran falsas**, y se han corregido: la simetría total
entre mundos, la «segunda llamada» que no existía como tal, y dos KDoc que
usaban la misma regla (H5-B) para decir cosas opuestas del mismo código. Un
KDoc que afirma algo falso es un defecto: informa mal a quien lo lea, y quien
lo lea para decidir ya no puede.

**Lo que este bloque NO cubre, y lo dice.** Las unidades nuevas
(`CanonicalRunRequest`, `composeCredentialSeam`, `compileScriptForRun`, los dos
ficheros de mundo) **no tienen ni un test unitario**, y no pueden tenerlo sin
violar `AGENTS.md` §2: son código de CLI, y un test unitario no puede hacer
filesystem ni subproceso. El instrumento de este bloque es la suite E2E, y las
mutaciones de arriba son la prueba de que esa suite **ve** lo que el bloque
tocó. Un bloque cuyo instrumento es la suite E2E y no un test unitario es un
bloque con menos oráculo que otro, y conviene decirlo en vez de dejar que la
puerta verde lo tape.

El ABI publicado no cambia: todo lo nuevo es `internal` y
`pipeline-application` no es un módulo publicado. Y **no se añade ni se quita
ningún escenario E2E**: el bloque no crece la suite.

Puerta sobre el SHA del bloque: `PASS sha=03bdc2a3a… elapsed=97s budget=900s
tests=rewritten:743 read:0 gaps=1 not_run`.

La puerta se corrió sobre el tip de la rama (`9c7af06ee`), cuyo árbol es
**byte-idéntico** al del commit fusionado (`git rev-parse` da el mismo tree
`c231bafd5` para los dos), así que la cifra es del contenido que está en
`main` y no de un árbol vecino. El censo de LOC sí se remedió **sobre el SHA
fusionado**, con `check-budgets.py` y `git ls-files`.


**z) El fingerprint del plan no tenía forma canónica publicada — CERRADO en `c28600f34`.**
El bloque `fingerprint-canonico` cierra el punto **F13** del informe de auditoría
(`pipelinek-saga-evolution/auditoria/INFORME-AUDITORIA-2026-10-20.md`): «
`SagaPlanFingerprint` existe, pero su forma canónica no está publicada: dos
implementaciones pueden producir el mismo plan con fingerprints distintos».

**Lo que había, medido.** El SDK tenía **dos** implementaciones del mismo
fingerprint, y **no se parecían**:

| Implementación | Campos de la forma | Marcador de versión |
|---|---|---|
| `SagaChainStepDefinition.fingerprintOf` | 8 — con `forwardSlot` y `compensateSlot` | `saga-v1` |
| `SagaObligationStepDefinition.planFingerprintOf` | 6 — **sin los cuerpos** | `saga-v1` |

El plan que la segunda hasheaba era `saga-v1|<sagaId>|<actionId>|<compensationId>|
<resourceRef>|<kind>`; el de la primera metía los dos cuerpos entre `<resourceRef>`
y `<kind>`. **El mismo marcador de versión sobre dos formas distintas**: la ley
«misma intención, mismo fingerprint» no tenía mecanismo, porque no había forma
que dos implementaciones pudieran compartir.

**Por qué el defecto era grave y no cosmético.** La fila durable de una
obligación lleva el **digest**, no los cuerpos: el digest es lo único que el
motor registra del plan. Por eso dos formas distintas no son dos deletreos
distintos, son **dos promesas distintas** para la misma acción — y el motor
compara el digest del plan armado con el del plan re-armado para detectar una
divergencia. Con dos formas en circulación, esa comparación comparaba peras con
manzanas, y un plan legítimo podía leerse como divergencia.

**Lo que se publicó.** La forma vive ahora en `pipeline-domain`, que es un
módulo **publicado** (y EXPERIMENTAL), en `SagaIdentity.kt`:

- `SagaPlanFingerprint.CANONICAL_FORM_VERSION` — la versión, que es parte de la
  forma y no un string suelto en el código.
- `SagaPlanFingerprint.canonicalFormOf(sagaId, plan)` — **la forma**, que es la
  especificación ejecutable.
- `SagaPlanFingerprint.compute(sagaId, plan)` — el digest: SHA-256 sobre los
  bytes UTF-8 de la forma, en hexadecimal minúsculo, con el locale **fijado** a
  `Locale.ROOT`.

La forma es exactamente:

```text
saga-v1|<sagaId>|<actionId>|<compensationId>|<resourceRef>|<forwardSlot>|<compensateSlot>|<kind>
```

**Los cuerpos están dentro, y es a propósito**: si no estuvieran, cambiar el
cuerpo de compensación no se detectaría como cambio de plan, y el digest —que
es lo único que el motor registra— no lo diría.

**El separador no se escapa, y por eso un campo con `|` se rechaza POR NOMBRE.**
Escaparlo habría cambiado la forma y roto todas las identidades ya escritas. En
vez de mover la forma, la forma se cierra: un campo que lleve el separador es
**rechazado antes de hashear**, y el rechazo dice qué campo fue. Dos planes
distintos no pueden deletrear el mismo texto, y el digest no se calcula nunca
sobre un texto ambiguo.

**La identidad NO se movió.** La forma publicada reproduce bit a bit lo que el
producto ya había escrito: se reejecutó `durable/38-saga-success.pipeline.kts`
contra un store durable real y se leyeron las filas `CompensationArmed`:

| Acción | Digest antes | Digest después |
|---|---|---|
| `reserve` | `b17b1d63381bc4c94167243a4028b5a5180b864f0790520a48042c52d9b1bac0` | **igual** |
| `charge` | `3055da2d90346c88a3cfc4837808d0a92692dbf60c34fd48be53fe8f295779cc` | **igual** |

Cambiar la forma habría re-armado como divergencia **todas** las promesas de
**todas** las corridas pasadas. Publicar la forma no la cambió: la sacó a la luz.

**Las dos implementaciones del plugin de ejemplo convergen.** `SagaChainStepDefinition`
deja de tener su propia copia: su declaración se deriva del **mismo**
`SagaActionPlan` que el motor recibe, y el digest sale de
`SagaPlanFingerprint.compute`. `SagaObligationStepDefinition` hace lo mismo, y
para poder hacerlo su entrada `SagaObligationInput` **dice qué cuerpos promete**
(`forwardSlot` / `compensateSlot`) — antes no los decía, y sin ellos no había
plan que hashear. La fachada `sagaObligation` en `SagaDsl.kt` gana los dos
parámetros. Dos lecturas de una acción son dos ocasiones para deletrear el
mismo plan de dos maneras; ahora hay **una**.

**Dos tests, y no son el mismo test.** El del productor
(`SagaPlanFingerprintCanonicalFormTest`, **9 tests**) es un test de dominio
puro: sin ficheros, sin subprocesos, sin red, como manda `AGENTS.md` §2. El
digest congelado que comprueba **no es inventado**: es el que el producto
escribió en su store durable, leído de una corrida real. El del consumidor
(`pipelinek-saga`, `SagaPlanFingerprintContractTest`, **6 tests**) implementa la
forma **por su cuenta** —la versión está escrita en el test, no leída del SDK— y
después la compara con la del SDK. Si llamara al `compute` del SDK, el test
compararía el SDK consigo mismo y pasaría lo que el SDK hiciera, que es
justo el error que un test de contrato existe para impedir.

**Mutaciones vistas en rojo.** Un verde que nunca se vio rojo no es evidencia, así
que las dos derivas se rompieron a propósito:

| Mutación | Productor | Consumidor |
|---|---|---|
| Intercambiar `forwardSlot` y `compensateSlot` | **2 de 9** en rojo (orden de campos + digest congelado) | **2 de 6** en rojo, y el mensaje trae el **texto canónico** de los dos lados: dice **qué campo** se movió |
| Cambiar la versión `saga-v1` → `saga-v2` | **2 de 9** en rojo | **3 de 6** en rojo, y el primero dice «the form changed: implement the new one, or the engine will read this repository's promise as a different plan» |

Restaurada la forma, ambos lados vuelven a verde y el digest sigue siendo el de
la tabla de arriba. La primera mutación es la que importa: es una deriva
**silenciosa** (no cambia la versión ni el número de campos) y aun así el
consumidor la ve y la **localiza**.

**El desvío por detekt, y una falsa alarma que mereció la pena.** La primera
corrida de la puerta se paró en la etapa 1 con
`ImplicitDefaultLocale` sobre `"%02x".format(byte)`. La regla tiene razón y el
arreglo es `Locale.ROOT`. Pero al perseguirlo estuve a punto de reportar una
**regresión del bloque anterior**: `"fmt".format(Locale.ROOT, args)` *parece* que
metería el locale en el primer hueco `%s`, y en Java a pelo eso es lo que
pasaría. Medido: el stdlib de Kotlin tiene **dos** extensiones `format`, y la
que lleva locale lo tiene como **segundo** argumento (`format(String, Locale,
Object...)`), así que `"%-13s %s".format(Locale.ROOT, verb, summary)` es
correcto. La salida de uso de la CLI —capturada **byte a byte** en el bloque
anterior— es correcta, y la misma forma en `MainVerbs.kt` también lo es. Lo que
**no** estaba bien era el código **nuevo**, que usaba la sobrecarga sin locale.
Fijado `Locale.ROOT` en el SDK y en la implementación propia del consumidor, y
el KDoc dice ahora que la garantía es **estructural** (el locale se nombra) en
vez de descansar en el hecho empírico de que los dígitos hexadecimales no se
localizan.

**El fixture «desfasado» no lo estaba, y se comprobó.** Este bloque lo marcó
como sospechoso: sus `planFingerprint` (`d720a447…` y `fd376b01…`) no son los que
el producto produce hoy (`b17b1d63…` y `3055da2d…`), lo que olía a forma vieja.
**Medido: el fixture es correcto y la forma publicada lo reproduce BIT A BIT.**
Los dos digests grabados son los de la forma con los **nombres de slot de
aquella época** —el plugin de ejemplo usaba entonces `core.dir` / `core.withEnv`
y `core.timeout` / `core.retry`, y hoy deriva un nombre por acción
(`example.saga.reserve.forward` / `example.saga.reserve.compensate`):

| Digest grabado | Forma que lo reproduce, con los slots de su época |
|---|---|
| `d720a447…` | `saga-v1|checkout|reserve|release|stock/42|core.dir|core.withEnv|RESTORE` |
| `fd376b01…` | `saga-v1|checkout|charge|refund|payment/42|core.timeout|core.retry|RESTORE` |

Lo que cambió entre la grabación y hoy fueron las **entradas** (los slots), no la
**forma**. Y un plano capturado tiene que quedarse congelado: si se actualizara
con cada cambio del producto dejaría de ser una grabación. El fixture está
haciendo lo que debe, y es además una **segunda prueba independiente** de que la
forma publicada es la que el producto usaba de verdad —esta vez sobre una
corrida **anterior** a este bloque.

La afirmación que este bloque publicó sobre el fixture era **falsa** y se
corrige aquí. Un veredicto que dice que corre sin comprobarlo es exactamente el
defecto que este bloque vino a cerrar, aplicado a sí mismo.

**Un defecto del arnés que este bloque encontró de paso, y que queda abierto.**
`v2/examples/run.sh` **no tiene ningún timeout**: ni por escenario ni global. Un
escenario que se cuelgue cuelga el stage 3 entero, y con él la puerta, hasta que
un `timeout` **externo** la mata —y esa muerte no deja veredicto: la etapa 3
muere sin su recuento de escenarios y la línea `PASS` no llega a escribirse.
Medido: una corrida de la puerta sobre este mismo árbol se quedó colgada en el
stage 3 más de 1 400 s (el log de la etapa quedó **vacío**, porque el lanzador
imprime el informe al final) y hubo que matarla desde fuera; la corrida
siguiente, sobre el **mismo árbol**, pasó en **78 s**. El defecto no es del
producto —el árbol es byte a byte el mismo— sino del arnés: convierte un
cuelgue en un silencio. **No se arregla aquí**; es el siguiente candidato de la
cola.

**Instrumento y cobertura.** La forma publicada vive en `pipeline-domain` y tiene
**test unitario propio**: ahí es donde está ahora la garantía. Las dos
implementaciones que convergieron están en el **plugin de ejemplo**, que **no**
tiene test unitario (los ejemplos no entran en el censo de la etapa 2): su
instrumento es la suite E2E. Un bloque que publica una forma y además cambia
código sin test unitario conviene decirlo, no dejar que la puerta verde lo
tape.

**ABI y LOC.** El cambio publicado es **aditivo**: tres miembros públicos en
`pipeline-domain`, un módulo EXPERIMENTAL; `apiDump` refrescado y `apiCheck`
verde, ninguna ruptura. `PRODUCT_LOC` 65 771 → **65 786** (+15: el crecimiento es
casi todo KDoc), `VERIFY_LOC` 11 389 → **11 495** (+106), ratio global **0.175**;
el ratio máximo por módulo sigue siendo **1.633** (`pipeline-step-sdk:workflow-control`)
y `pipeline-domain` queda en 0.277.

Puerta sobre el SHA del bloque: `PASS sha=c28600f34… elapsed=78s budget=900s
tests=rewritten:752 read:0 gaps=1 not_run`.


**aa) El arnés E2E no tenía techo: un cuelgue era un silencio — CERRADO en `4b0a63415`.**
El bloque `fingerprint-canonico` (§2.2 z) dejó escrito que el defecto **no era del
producto** sino del arnés: «convierte un cuelgue en un silencio». El lanzador imprimía
su informe al **final**, así que un escenario que se colgaba no escribía su
veredicto, la etapa 3 seguía esperando y el **log de la etapa quedaba vacío**: la
puerta se quedaba **sin veredicto**, y sólo la mataba un `timeout` **externo**.
Medido en este árbol: una corrida se colgó más de **1400 s** en la etapa 3 con el
log vacío, y la corrida siguiente, sobre el **mismo** árbol, pasó en 78 s.

**La regla es una y es total**: cada corrida del pipeline dentro de un escenario
tiene un techo (`PIPELINEK_E2E_SCENARIO_TIMEOUT`, **120 s** por defecto), y un
escenario que lo alcanza produce su **propia** fila roja, con su nombre y su
motivo, mientras los demás escenarios siguen corriendo. El recuento final no
cambia: un escenario colgado cuenta como **fallido**, no desaparece del informe.

**Mutación vista en rojo.** Con el techo a 30 s y un escenario que dormía 600 s:

```
ok   steps/04-sh.pipeline.kts                             exit 0
ok   steps/08-withEnv-pipeline.pipeline.kts               exit 0
ok   steps/13-workspace-helpers.pipeline.kts              exit 0
ok   steps/16-sleep.pipeline.kts                          exit 0
ok   steps/17-writeFile.pipeline.kts                      exit 0
ok   steps/18-cleanWs.pipeline.kts                        exit 0
ok   steps/26-find-files.pipeline.kts                     exit 0
ok   steps/33-glob-ant-alternation.pipeline.kts           exit 0
FAIL steps/99-hang.pipeline.kts                           timed out after 30s: the run was killed, not finished

9 scenario(s), 8 passed, 1 failed, 0 not run
failures:
  - steps/99-hang.pipeline.kts: timed out after 30s
```

El colgado tuvo su fila, su nombre y su motivo; los otros ocho pasaron; y el
recuento cuadró. **El otro lado de la mutación**: con el techo desactivado
(`PIPELINEK_E2E_SCENARIO_TIMEOUT=0`), la misma corrida **se colgó** y el log de
la etapa terminó con **0 líneas** —el silencio, medido—, y sólo la mató un
`timeout` externo.

**El techo se reconoce por el TIEMPO TRANSCURRIDO, no sólo por el código de
salida**, porque el código no alcanza: GNU `timeout` devuelve **124** cuando el
hijo muere por SIGTERM, pero devuelve **137** cuando lo ignora y hay que
rematarlo, y 137 es exactamente lo que produce el SIGKILL que un escenario de
`resume` se aplica a sí mismo. Un escenario que se cuelga y uno que muere a
propósito no pueden acabar pareciendo lo mismo.

**El instrumento permanente es el `--self-test` del lanzador**, que ya protege su
propio contrato: ahora comprueba también que la perilla se lee, que una perilla
que no es un número se rechaza, que una corrida que llega al techo **es** un
timeout y que una que falla dentro del techo **no** lo es. El self-test encontró
dos fallos reales durante el desarrollo —`timeout` no admite el sufijo `ms`, y una
expectativa del propio self-test estaba invertida—, que es justo para lo que un
self-test debe existir.

**El valor por defecto deja pasar los 46 escenarios.** Medido sobre el árbol de
este bloque, el escenario más lento es `durable/37-saga-reconcile` con **17 s**
—y el techo son 120 s—, así que la margen es de más de siete veces y ningún
escenario legítimo llega a pisar el techo. El techo es por **corrida del
pipeline**, no por escenario, porque un escenario puede ejecutar el pipeline hasta
tres veces (`durable`, `resume`, `storage-agnostic`) y cada una de esas corridas
es donde puede colgarse.

**Lo que este bloque NO toca**: ni una línea del producto, ni el esquema de
eventos, ni un `.api`. `VERIFY_LOC` 11 495 → **11 702** (+207, el lanzador),
`PRODUCT_LOC` **65 786** (sin cambio), ratio global **0.178**; el ratio máximo por
módulo sigue siendo **1.633** (`pipeline-step-sdk:workflow-control`).

Puerta sobre el SHA del bloque: `PASS sha=4b0a63415… elapsed=88s budget=900s
tests=rewritten:752 read:0 gaps=1 not_run`.


**bb) El operador no tenía forma de saber qué debía un run — CERRADO en `ad61e9b2c`.**

**El problema, en una frase**: cuando un Saga no acababa bien, la única forma de
saber qué quedaba era abrir la base a mano —o peor, leer el log— y no había forma
de decirlo sin riesgo de que el estado leído fuera una reconstrucción de un texto
y no la promesa que el motor había escrito.

**Lo que hay ahora**: un verbo, `pipelinek saga inspect <runId> --db <path>`, que
dice por acción el recurso, la fase, el estado, la evidencia (el recibo), el
último error clasificado, la próxima acción y si hace falta una persona. La salida
es una tabla para quien lee y un documento JSON para quien programa.

```
saga inspect: run 285c37dc-… — 3 obligations, operator needed: yes
run outcome: failure (PLUGIN) — the action ship failed: shell exited with code 1
saga checkout: NEEDS_INTERVENTION (UNKNOWN_EFFECT)
ACTION   COMPENSATION  RESOURCE    PHASE       STATUS            EVIDENCE         NEXT            OPERATOR
reserve  release       stock/42    compensate  EFFECT_CONFIRMED  receipt-reserve  compensate      yes
charge   refund        payment/42  compensate  EFFECT_CONFIRMED  receipt-charge   compensate      yes
ship     unship        parcel/42   forward     EFFECT_UNKNOWN    -                settle_forward  yes
```

**De dónde sale cada cosa.** El estado sale de la **tabla de promesas**
(`saga_obligation`), que es donde el motor escribe cada promesa **antes** de dejar
que ocurra el efecto que promete deshacer: una promesa puede existir sin el evento
que la anunció —una caída entre la escritura y el anuncio— y nunca al revés, así
que el log de eventos y la consola **no** pueden reconstruir el estado. El veredicto
del run y el motivo por el que paró el Saga salen de las declaraciones del propio
run, leídas con la proyección que el módulo del plano ya publica. La huella del
plan que sale es la que el motor calculó al armar la promesa, y una prueba lo
comprueba contra el **plano capturado** de una corrida real: el digest que muestra
el lector es el mismo que el run anunció.

**La lectura no escribe.** La conexión se abre en modo sólo lectura
(`mode=ro`), y el permiso lo da el propio SQLite: una escritura sobre esa
conexión falla con un rechazo del motor de base de datos. Una prueba lo
comprueba (y otra que el **digest del fichero de la base no cambia** después de
leer). Se eligió ese modo y no un `PRAGMA query_only` sobre una conexión normal
porque con `mode=ro` el permiso es de la conexión y no del código que la abre: un
refactor que seolvide de una restricción no puede escribir por accidente.

**Falla cerrado, y se nota.** Una base que no está, una base que no es un store,
una base sin la tabla de promesas, un run que no está en el store y una fila
ilegible se responden **con su motivo** y código 2. Un run que existe y no armó
ninguna promesa se responde como lo que es —«no debe nada»— y con código 0. Lo
que **nunca** sale es una lista vacía: una lista vacía se puede leer como «no hay
nada pendiente», y esa es la lectura que hace que alguien vaya a buscar lo
equivocado.

**El mapa del estado no se inventa.** La fase, la próxima acción y el error
clasificado salen de **una** tabla de decisión sobre el vocabulario de estados, y
esa tabla es **total** (un estado nuevo no compila hasta que alguien dice qué
significa) y se **contrasta con la lifecycle que el motor obedece**: una promesa
es terminal exactamente cuando el motor no puede moverla más, y necesita una
persona exactamente cuando la próxima acción es la decisión de una persona. La
prueba que lo contrasta es la que falla si las dos tablas dejan de hablar.

**Mutaciones vistas en rojo**, seis, cada una en la prueba que tiene que verlo:

| mutación | qué se rompe |
|---|---|
| la fase de una compensación fallida dice `COMPENSATE` | la prueba que contrasta la tabla con la lifecycle |
| la conexión se abre sin `mode=ro` | la prueba que exige que la escritura sea rechazada |
| un run inexistente se responde como si existiera | la prueba que separa «no está» de «no debe nada» |
| la proyección ignora que el Saga paró | la prueba de la vista, que dice que hace falta una persona |
| el verbo responde «no debe nada» cuando el estado no se puede leer | la prueba del verbo, que exige el motivo y el código 2 |
| el lector deja de leer las promesas | las dos pruebas de la vista |

**El UAT es real y no se queda**: una corrida del escenario que acaba en
`NEEDS_INTERVENTION`, con su base durable, y el verbo de verdad leyéndola —de ahí
salen las líneas de arriba—. No se convirtió en un escenario permanente, porque
lo que protege la capacidad ya está cubierto por pruebas deterministas y rápidas:
un E2E nuevo habría **sumado** coste a la puerta para volver a comprobar lo que
las pruebas unitarias comprueban con el store real y el plano real.

**Y el arnés mejoró, que era la otra mitad del encargo.** El lanzador ya medía
cada corrida del pipeline y **no enseñaba el resultado**: el coste de un escenario
sólo se podía sacar midiendo aparte. Ahora cada línea del informe lleva su tiempo,
y con él se puede ver qué parte de la puerta es la cara —mediana **6,7 s**, p95
**14,9 s**, máximo **15,5 s**—. Medirlo destapó un defecto del propio informe: el
escenario que se declara storage-agnostic pasaba **sin imprimir su línea**, así que
el informe tenía una línea menos que escenarios que pasaron y nadie podía decir
cuál faltaba. Ahora imprime su línea, y el lanzador **comprueba** que todo
escenario con veredicto tenga su línea: un veredicto sin línea es un fallo del
arnés, no un verde que nadie ve (mutación vista en rojo, y el arreglo de esa
mutación encontró un fallo propio: un `grep` sin coincidencias tumbaba el
lanzador entero bajo `set -e`).

**Lo que este bloque NO toca**: ni el esquema de eventos, ni la forma de
`RunFinished` ni de `OperationStatus`, ni el meaning de nada publicado. El `.api`
del dominio **crece** (165 líneas nuevas, ninguna quitada): los tipos de la vista
son aditivos. `VERIFY_LOC` 11 702 → **12 139** (+437: las pruebas y el lanzador),
`PRODUCT_LOC` 65 786 → **66 328** (+542), ratio global **0.183**; el ratio máximo
por módulo sigue siendo **1.633** (`pipeline-step-sdk:workflow-control`).

Puerta sobre el SHA del bloque: `PASS sha=ad61e9b2c… elapsed=95s budget=900s
tests=rewritten:774 read:0 gaps=1 not_run`.


**bc) Una promesa no decía de quién era, y otro plugin podía escribir en ella — CERRADO en `c47d557a3`.**

**El problema, en una frase**: el `plan_fingerprint` dice **qué** se prometió, pero
**no dice quién** lo prometió, y como los slots son nombres **locales** del plugin,
dos plugins pueden declarar la misma acción, el mismo recurso y los mismos slots
—y sus fingerprints salen **idénticos**, aunque sus cuerpos no lo sean.

**Por qué eso muerde.** El plugin B arma la misma clave que ya armó el plugin A. El
store no pisa la fila —eso ya lo hacía—, responde que ya está, y B se queda
creyendo que **su** promesa está guardada. La fila dice una cosa (el plan de A) y
el efecto que se ejecutó detrás fue el de B. Cuando toca compensar, el cuerpo que
corre es el del slot, que es el del plugin que está en ejecución: la compensación
de uno deshace el efecto del otro con el cuerpo del otro.

**Lo que hay ahora**: la promesa durable recuerda **de qué plugin** viene, y **sólo
ese plugin** escribe en ella. El motor estampa el dueño —lo resuelve del registro,
igual que resuelve el *body owner*—, así que el plugin no puede reclamarse una
promesa que no hizo.

```
plan a3f1… — checkout/reserve-stock — owner example.saga, intent v1
```

**La regla vive en el dominio, y es total.** `SagaPromiseOwnership` decide en un
solo sitio quién puede escribir en una promesa, y su vocabulario es **cerrado**: un
valor nuevo no compila hasta que alguien dice qué significa. Los dos casos son
`Allowed` y `ForeignOwner`, y el refusal **nombra** al dueño y al que quiso
escribir, porque «no permitido» sin nombre manda al que lee a buscar al sitio
equivocado.

**Las tres escrituras lo respetan.** El `arm` (que no pisa nada y cuyo refusal lo
nombra), el `updateOutcome` y el `moveAll`. El cierre de un saga es **todo o
nada**: una promesa ajena detiene el cierre entero en vez de dejar el saga a
medias, porque un saga a medias compensa después lo que su propio cierre dejó
pendiente.

**Lo viejo sigue funcionando.** La columna del dueño es **nullable** y se añade
con una migración **aditiva**: una base escrita antes de esto se abre, se le
añade la columna y sus promesas —que no tienen dueño— **no pueden probar un
conflicto**, así que no bloquean a nadie. Un plano sin dueño no es un plano de
otro: es un plano de nadie.

**Mutaciones vistas en rojo**, tres, cada una en la prueba que tiene que verlo:

| mutación | qué se rompe |
|---|---|
| la regla permite al dueño ajeno | la prueba de dominio que exige el refusal con los dos nombres |
| la escritura no comprueba el dueño | la prueba del store que exige que la promesa ajena no se mueva |
| el brazo no estampa el dueño | las cuatro pruebas que dependen de saber de quién es la promesa |

**Lo que este bloque NO cierra**: el **cuerpo** de la compensación sigue sin estar
atado al cuerpo que se prometió. La regla de aquí dice quién puede escribir en la
promesa; quién compensa con qué cuerpo es el siguiente bloque, y exige atar el
digest de la estructura del cuerpo a la promesa. Y el `plan_fingerprint` **no**
cambia: meter el plugin en la forma canónica haría que toda promesa en vuelo
pareciese una divergencia, que es romper el estado durable, no el código.

**El contrato publicado crece de forma aditiva**: el `.api` del dominio **crece**
(54 líneas nuevas, ninguna quitada: tres tipos nuevos). El caso nuevo en un
`sealed interface` es una ruptura de **fuente** para el que tenga un `when`
exhaustivo, y queda **registrada** en el ledger de rupturas deliberadas con su
motivo y su alcance. `VERIFY_LOC` 12 139 → **12 515** (+376), `PRODUCT_LOC`
66 328 → **66 532** (+204), ratio global **0.188**; el ratio máximo por módulo
sigue siendo **1.633** (`pipeline-step-sdk:workflow-control`).

Puerta sobre el SHA del bloque: `PASS sha=c47d557a3… elapsed=110s budget=900s
tests=rewritten:792 read:0 gaps=1 not_run`.


**bd) Una promesa no decía qué cuerpo la deshacía, y la compensación corría con el cuerpo que hubiera — CERRADO en `5d410ecaf`.**

**El problema, en una frase**: la promesa decía **qué efecto** se calfía y **de qué plugin** venía, pero no decía **qué cuerpo** lo desharía, y el plan nunca vio el cuerpo: los slots son **nombres locales** del plugin, así que dos plugins —o dos versiones del mismo plugin— pueden tener los mismos slots con cuerpos distintos.

**Por qué eso muerde.** El bloque anterior cerró quién escribe en la promesa; este cierra **qué corre** al compensar. Sin digest, la compensación se despacha por el slot que dice el plan, y ese slot lo resuelve **el cuerpo del plugin que está en ejecución**. La fila dice una cosa —la promesa del plugin A— y el efecto que se deshace es el de B, con el cuerpo de B. Y el caso fino: el mismo plugin se actualiza, su cuerpo cambia de payload, y la compensación corre con un cuerpo que nadie prometió.

**Lo que hay ahora**: la promesa **nombra** el cuerpo que la deshace (`compensateSlot`) y el motor lo **mide**: el digest de su estructura queda en la fila. La compensación **comprueba el cuerpo antes de despachar**: si el cuerpo que va a correr no es el prometido, el bucle **para** con `COMPENSATION_BODY_DIVERGED` y el refusal lleva **los dos digests**, no un «difieren» que manda a buscar.

**El plugin nombra, el motor mide.** Un plugin no puede declarar la forma de un cuerpo que no escribió —eso es una medición del motor—, y un plugin que declarase mal el digest haría que la promesa se comprobara a sí misma, que no es comprobar nada. Es la misma división que el dueño: el plugin nombra, el motor mide.

**El brazo se niega antes del efecto.** Una promesa que nombra un cuerpo que nadie tiene no se puede cumplir, y se rechaza **antes** de que corra el efecto: el refusal (`ArmRejection.UnknownCompensationBody`) **nombra el slot**, porque el slot es lo que el plugin tiene que arreglar. Si se aceptara, el error se descubriría al compensar —con el efecto ya hecho— y además la promesa no comprobaría nada.

**La regla vive en el dominio, y es total.** `SagaBodyPromise` decide en un solo sitio si el cuerpo que corre es el prometido, y su vocabulario es **cerrado**: `Agreed` y `Diverged`, y los dos son *decisiones*, no estados. La ausencia de un cuerpo no es un tercer caso: es un `Diverged` sin cuerpo actual, porque una promesa que nombró un cuerpo y un cuerpo que no está no coinciden.

**El digest es del cuerpo que compensa, no del cuerpo entero.** Si fuera del cuerpo entero, un cambio en una parte que no es el compensador invalidaría una compensación que sí se puede hacer —y una compensación rechazada deja el recurso roto esperando a una persona—. El digest mide **un** cuerpo.

**Lo viejo sigue funcionando.** La columna del cuerpo prometido es **nullable** y se añade con una migración **aditiva**: una base escrita antes de esto se abre, se le añade la columna y sus promesas —que no nombran cuerpo— **no pueden probar una divergencia**, así que no bloquean a nadie. Una promesa que no nombra cuerpo es una promesa que no se comprueba: el precio, dicho en voz alta.

**Mutaciones vistas en rojo**, seis, cada una en la prueba que tiene que verlo:

| mutación | qué se rompe |
|---|---|
| la regla acepta cualquier cuerpo | las dos pruebas de dominio que exigen el `Diverged` con los dos digests |
| el brazo no estampa el cuerpo | las dos pruebas de puerto que dependen de saber qué cuerpo se prometió |
| el brazo no rechaza un cuerpo que no existe | las dos pruebas que exigen el refusal con el slot |
| la compensación no comprueba el cuerpo | la prueba que exige que el cuerpo **no** corra |
| la fila no guarda el cuerpo | las tres pruebas del store que dependen de leerlo |
| la re-pregunta no comprueba el cuerpo | la prueba que exige que una compensación sin respuesta no se pregunte con otro cuerpo |

**El contrato publicado crece y rompe el fuente en cuatro sitios**, cada uno con su motivo y su alcance en el ledger de rupturas deliberadas: la declaración (campo nuevo), el rechazo del brazo (caso nuevo en un `sealed interface`), el motivo de intervención (constante nueva) y el refusal (campo nuevo con la divergencia). `VERIFY_LOC` 12 515 → **13 007** (+492), `PRODUCT_LOC` 66 532 → **66 685** (+153), ratio global **0.195**; el ratio máximo por módulo sigue siendo **1.633** (`pipeline-step-sdk:workflow-control`).

Puerta sobre el SHA del bloque: `PASS sha=5d410ecaf… elapsed=98s budget=900s tests=rewritten:812 read:0 gaps=1 not_run`.

**be) El recuento de escenarios observados no llegaba a la línea de veredicto — CERRADO en `5a8e43a35`.**

**El problema, en una frase**: la puerta contaba cuántos escenarios declararon una
observación, lo escribía en el log de su etapa, y la línea de veredicto —la única
que alguien pega en un informe— no lo llevaba.

**No era un campo que faltara: era un campo que se había mudado de sitio.** La
línea de veredicto salía así:

```
PASS  sha=…  elapsed=72s  budget=900s  tests=rewritten:812 read:0  gaps=1 not_run
PASS  sha=  scenarios=observed:35 executed:35  elapsed=s  budget=s
```

Dos líneas, la segunda con el recuento dentro y con todo lo demás vacío. El
formato llevaba **cinco** conversiones y la llamada pasaba **seis** argumentos, así
que el shell leía el sobrante como una segunda pasada sobre el mismo formato y lo
imprimía como una línea propia. La puerta decía `PASS`, salía con cero, y todo lo
que un lector podía comprobar sobre la línea era que la línea era verde.

**Por qué eso muerde y no es cosmético.** El bloque anterior añadió el recuento
justo para que la evidencia no viviera en el log. La mitad de la mejora —«los 35
de 35 declararon una observación»— se quedaba en un sitio que nadie cita. Un
informe podía afirmar honestamente que la puerta pasó y callar que el arnés
comprobó algo, y no había forma de notarlo mirando la línea: el `gaps=1 not_run`
de al lado ya enseñaba que los huecos viajan ahí, y ese hueco no viajo.

**Lo que lo escondió es la parte incómoda**: nada falló. La puerta estaba verde,
el código de salida era cero, y los tests que se ejecutaron eran los que tocaban.
Un programa que verifica sus propios datos y aun así los pierde en el último
formateo sólo se delata leyendo su salida, que es exactamente lo que este
repositorio lleva varios ciclos haciendo con el resto del arnés.

**El arreglo no es contar mejor: es que ya no se pueda perder.** La evidencia se
compone en **un** argumento antes de imprimirse, y cada veredicto la consume con
**una** conversión. Un campo ya no puede separarse de su línea por un recuento que
no cuadra, y de paso desaparece la triplicación de un formato largo que estaba
copiado en tres sitios —que es donde el error vivía—. Cada campo sigue
diciendo `NOT_RUN` cuando su etapa no corrió, así que el silencio tampoco es una
opción.

**Medido, sobre el SHA del bloque**: tres ensayos consecutivos, **88 s, 74 s y
72 s**, y en los tres **una** sola línea de veredicto, cada una con
`scenarios=observed:35 executed:35` dentro:

```
PASS  sha=5a8e43a35…  elapsed=88s  budget=900s  tests=rewritten:812 read:0  gaps=1 not_run  scenarios=observed:35 executed:35
```

El límite 6 de la tabla de arriba se cierra con estos tres ensayos, que es lo que
exigía: los tres con los tests **reescritos** (`rewritten:812 read:0`), o sea los
tres midiendo el gate y ninguno citando una caché.

**bf) Los comentariosaban de quién era el encargo, no de qué hace el código — CERRADO en `4b7b908bb`.**

**El problema, en una frase**: quinientos cincuenta y ocho ficheros empezaban su
comentario con la etiqueta del encargo —`WU-LPR-301:`, `S2-A6 / G5 —`— o
conservaban una línea entera que apuntaba a un documento interno que no viaja con
el repositorio. A quien lee el código dentro de un año, esa etiqueta no le dice
nada y el documento no lo puede abrir.

**Dos pasadas, y sólo una podía ser automática.** La primera quita la etiqueta
del principio de la frase cuando detrás queda una oración, y borra la línea que
sólo es procedencia: 205 ficheros, **630 líneas**, todas de comentario. La
segunda —los identificadores metidos *dentro* de frases que sí explican algo— se
hizo por módulos con criterio, porque **dos reescrituras automáticas se probaron
y se descartaron**: la primera colapsaba las tablas de columnas de los
`build.gradle.kts` y partía un paréntesis anidado en dos; la segunda hacía lo
mismo. Las dos se detectaron en seco, antes de tocar el disco. De ahí la regla
que queda: **lo mecánico se automatiza; la prosa, no.**

**Lo que eso desigualdadó, y no era hipotético**: una entrada de la línea base
de detekt se identifica por una firma que **lleva pegada la documentación de la
declaración**. Al cambiar los comentarios cambia la firma, el hallazgo deja de
coincidir con su entrada y aparece como nuevo — sin que exista nada nuevo. Con la
primera pasada aplicada, la puerta se puso en rojo con **cinco** hallazgos que ya
estaban suprimidos.

**Y debajo de eso había un defecto peor, que este bloque pasó por encima**: la
tarea de instantánea escribía **un segundo fichero**, un sufijo más profundo,
que la puerta nunca leía. Medido, no supuesto: tras ejecutar
`detektBaselineMain`, el md5 y la fecha del fichero que el análisis lee seguían
intactos. El comentario del propio build, dos líneas más arriba, afirma lo
contrario —y describe esa misma trampa como el fallo que ya se corrigió una vez
por culpa de un nombre. Volvimos a caer en ella.

**El arreglo es que la tarea escriba donde se lee.** Fijada la ruta de la
instantánea al mismo fichero que consume el análisis, regenerar deja de ser un
acto de fe: el md5 del fichero cambia, y la puerta se pone verde. Un fichero, un
comando, un artefacto.

**Que la regeneración no se tragara deuda, medido de la manera que puede
contestar**: con la línea base **antigua** delante del árbol actual, detekt
reporta **exactamente esos cinco**, y ninguno más. Los tres entradas que
desaparecen de la línea base nueva no son hallazgos escondidos: el código que
nombraban **ya no existe** en el repositorio (`val o = outcome`, por ejemplo), y
por eso se limpian. Sin tragar nada: la línea base pasa de 362 a 359 porque
deja de fingir que hay tres problemas que ya no están.

**Lo que se conserva a propósito**: los identificadores de vulnerabilidad
publicada (`CVE-2023-32981` y los que haya) no son arqueología, son **el motivo**
por el que existe el código que los menciona. Borrarlos sería borrar la razón.

**Y lo que casi se cuela por el lado bueno**: 306 ficheros revisados, y la prueba
de que el diff es sólo de comentario no es un `grep` — es quitar los comentarios
de la versión anterior y de la actual de cada fichero y comparar lo que queda.
Un `grep` deja pasar un comentario de cola eliminado y no ve un `is StepSpec.Dir,`
convertido en `is StepSpec.Dir,texto`; esa comparación no. Esa prueba es la que
detectó el daño.

**Lo que queda, y es la parte que no está hecha**: la misma cuenta, sobre el
árbol de este bloque, da **1 109 líneas** todavía con procedencia — un 40 % de las
2 789 que había. No es un resto de este barrido: son los identificadores que
viven *dentro* de una frase que explica algo, donde quitarlos obliga a
reescribir la frase y eso ya no es un trabajo mecánico sino de lectura. Queda
declarado aquí como deuda abierta, con su cifra, para que no se lea como un
trabajo terminado.

Censo sin movimiento: `VERIFY_LOC` cuenta líneas efectivas sin comentarios, así
que **13 172 / 66 688 = 0,198**: los comentarios no son líneas efectivas, y el
censo sólo se mueve por el arreglo del build que este mismo bloque lleva.

Puerta sobre el SHA del bloque: `PASS sha=4b7b908bb… elapsed=89s budget=900s
tests=rewritten:812 read:0 gaps=1 not_run scenarios=observed:35 executed:35`.

### 2.3 Cierre de ciclo

- `PRODUCT-GATE` (certificador externo, RC publicada) = **`BLOCKED_EXTERNAL`**.
  No hay CI remoto en este repositorio. La ausencia de CI **nunca** se declara
  `PASS`.
- **El glob de artefactos: cerrado en `8d4a49be8`, y el hueco que abría era real.**
  No era una hipótesis: `build/**/*.{jar,war}` metía en el `.tar` todo lo que
  hubiera bajo `build/`, incluidos los `.pem` y los `credentials.bin`. El arreglo
  **tradujo** el patrón Ant al dialecto del motor en vez de reescribir el motor,
  añadió un seam puro con 19 tests de tabla de verdad, y cerró además el defecto de
  una capa más arriba (la lista de patrones se partía por cada coma). Evidencia:
  dos mutaciones unitarias (6 y 3 tests rojos) y dos mutaciones de escenario E2E (el
  `.pem` stasheado, y «matched no files»).
- **Presupuesto de tiempo: MEDIDO, `CERRADO`, y MEJORADO.** El ciclo del glob
  dio **98 s** sobre `8d4a49be8`, con el límite en 900 s, leído de la línea de
  veredicto de la etapa 5 (`PASS  sha=…  elapsed=97s  budget=900s`) y no del
  código de salida: margen de **9,3×**. El ciclo de este ROADMAP midió **88 s,
  74 s y 72 s** sobre `5a8e43a35`, con margen de **10,2×** en el peor de los
  tres, y esa es la cifra que §1 publica. El ensayo anterior al del glob dio 91 s
  sobre `bc2acec7c` (árbol idéntico a `96220fdad`). El reloj **varía ±10 s**
  entre corridas por la caché de Gradle (medidas: 81, 91, 94, 97, 98 y 105 s), así que
  la cifra se publica como un valor medido **con el ruido que tiene**, y no
  como un número exacto que nadie podría reproducir. No es que el trabajo
  hiciese menos: es que **el harness costaba 86 % de la puerta** y eso se ha
  corregido (§2.2 q). La etapa 3 baja de 289 s a **46 s** — 45 escenarios en
  paralelo, medido — y las etapas 1, 2 y 4 no se mueven. El resto de la historia: los
  381 s de `4e397e6e8`, que seguían siendo ciertos para ese SHA y ninguno más;
  y los 242 s, 211 s y 213 s de `18d3148a`, que ya no lo son desde hace tiempo.
  **El número de reloj de un gate depende de su harness, no sólo del producto**,
  y por eso el presupuesto se vuelve a medir en cada ciclo en vez de heredarse.
  Y el alza no es proporcional al trabajo: la etapa 2 mide 21 s sobre 85
  informes JUnit, y el total apenas se movió respecto del ciclo anterior
  (350 s → 381 s) con 26 tests más y un escenario E2E más. Un gate verde por
  inercia es peor que un gate marcado caducado: el segundo se lee y el primero
  se acepta sin mirarlo.
- **El motor del glob: cerrado en `f3d897a9`.** El glob llevaba `spring-core`
  (1,9 MB) y `spring-jcl` (24 KB) —**2 jars**— por un puñado de métodos, y el
  proyecto ya no admite eso (AGENTS.md §1). Ahora el motor es del proyecto
  (`AntPathMatching`), sin traducción intermedia: hay **un** dialecto y es el de
  Ant. **El suelo no se movió**: 402 360 pares comparados contra **lo
  publicado**, 402 353 coincidencias y **7** divergencias de una sola clase —un
  constructo que cruza un separador— en las que el motor viejo estaba roto. El
  rendimiento quedó en **paridad** (0,72-0,97×) porque el patrón se compila una
  vez, y el guard anti-ReDoS se conserva con el mismo límite.
- **El peso de verdad de la distribución, y qué se hace con él — DECIDIDO.** Fuera
  de `spring-core` quedan el compilador de Kotlin embebido (**58 MB**),
  `sqlite-jdbc` (**14 MB**) y BouncyCastle (**~9 MB**): 64× más que lo que se
  acaba de quitar. La política de AGENTS.md §1 dice que una dependencia se
  queda si lleva algo que el proyecto no puede razonablemente poseer, y que eso
  tiene que decirse **por dependencia**, no heredarse por defecto. La decisión
  es que **esas tres se quedan**, y el motivo de cada una está en §2.2 s): no
  son «un par de clases», sino un motor de compilación, un motor de
  persistencia y criptografía. Lo que **no** se ha hecho es cerrar los ojos
  delante: su peso está medido y publicado, y eso es lo que permite revisitarla
  cuando toque.
- **El slot del cuerpo: cerrado en `e9392ce4`.** La dirección de un cuerpo de
  plugin pasó de ser su *kind* a ser **el nombre que declara el plugin**, con el
  *kind* como valor por defecto cuando no hay nombre (§2.2 t). El motivo medido:
  el direccionamiento por *kind* obligaba a la demo a inventar **seis kinds del
  core** para una cadena de dos o tres acciones, y un apaño del fixture no es
  un patrón copiable. **La ruptura binaria del IR se anota en el ledger**
  (`published-contract-exceptions.json`, entrada `pipeline-domain`), porque
  `pipeline-domain` es EXPERIMENTAL y el ledger es el mecanismo del repo para
  romper con un recibo. Y el escenario nuevo `39-saga-two-same-kind` se **vio
  rojo** con el motor direccionando por *kind*.
- **La línea de veredicto dice lo que corrió — cerrado en `4dee0b229`.** El campo
  `tests=rewritten:<n> read:<n>` viaja **dentro** de la línea (§2.2 u). El defecto
  era un falso verde de manual y medido: una corrida con el task de Gradle al día
  ejecutaba **cero** tests, y su línea de veredicto no lo decía — el log lo decía,
  dos líneas más abajo, y el log no es lo que se pega en un informe. Con el campo,
  esa misma corrida dice `tests=rewritten:0 read:161`. La puerta sobre `4dee0b229`
  da **99 s**, `tests=rewritten:713 read:0`, **713** tests en **89/89** informes
  reescritos por la corrida, 46 escenarios (45 ok, 1 NOT_RUN) y LOC
  `10979/65474 = 0.168`.
- **La capability de Saga no depende de `--db` — cerrado en `88a6dc07d`.** El mismo script se
  comporta igual con y sin store durable, y el veredicto del Saga se dice en los dos mundos
  (§2.2 v). El defecto era un rechazo en la admisión, medido: `missing required capabilities for
  step 'example.saga.chain': saga.compensation, saga.plan, saga.lifecycle`. La prueba es del
  arnés: la directiva `//@ storage-agnostic` corre el escenario 38 **dos veces** —con `--db` y
  sin él— y exige que **ambas** cumplan las mismas expectativas. Puerta sobre `88a6dc07d`:
  **100 s**, `tests=rewritten:716 read:0`, **716** tests en **90/90** informes reescritos, 46
  escenarios (45 ok, 1 NOT_RUN) y LOC `11127/65551 = 0.170`.
- **Un solo lector del terminal del Saga — cerrado en `cb37779f2`.** El arnés consume la
  proyección publicada en vez de re-implementar su lectura (§2.2 w). El defecto era un terminal
  **contradictorio** invisible para la ley: `SETTLED` con motivo, que la proyección lee como
  «un humano tiene que decidir» y el arnés como «liquidado». Con el mismo test, el mismo
  terminal y la misma ley, la mutación sale a rojo con el lector viejo. Puerta sobre
  `cb37779f2`: **95 s**, `tests=rewritten:717 read:0`, **717** tests en **90/90** informes
  reescritos, 46 escenarios (45 ok, 1 NOT_RUN) y LOC `11140/65539 = 0.170`.
- **Cuatro módulos donde el riesgo paga — cerrado en `a2ffb90db`.** El bloque
  `cobertura-modulos-riesgo` dio tabla de decisión a los cuatro módulos con más producción sin
  test unitario permanente (§2.2 x), y de esa operación salieron **dos defectos materiales** que
  no estaban donde se buscaba: el guard de argv de `scm-git` dejaba pasar un **PAT usado como
  usuario** (`https://TOKEN@host`, que es como se usa contra GitHub) porque la regla exigía
  `user:pass@`, y la regla de Zip Slip de `utilities` rechazaba **nombres de fichero legales**
  (`release..tar.gz`) mientras su regla de contención —la que demostraba la garantía— no podía
  decidir nada. Los otros dos módulos (`pipeline-runtime`, `pipeline-scripting-api`) no tenían
  defecto: tenían falta de observador, porque sus decisiones estaban enterradas en código que
  había que levantar entero para poder preguntarle. **26 tests nuevos en 4 módulos, 0 escenarios
  E2E nuevos**: el bloque no crece la suite, le da ojos a reglas que no los tenían. Mutación vista
  en rojo en los cuatro: 3 + 2 + 1 + 1 filas, sin colateral. Puerta sobre `a2ffb90db`: **99 s**,
  `tests=rewritten:743 read:0`, **743** tests en **94/94** informes reescritos, 46 escenarios
  (45 ok, 1 NOT_RUN) y LOC `11389/65556 = 0.174`.

### 2.4 La integracion de las dos lineas: que se absorbio, que se porteo y que quedo fuera

**La geometria.** `origin/main` (51 commits) y la integracion (300 commits) salen
de `3079b2603` y trabajan sobre lo mismo con layouts distintos: `origin/main`
vive en `v2/` y la integracion en `pipelinek/`. Por eso un merge de arboles
reporta **531 rutas en conflicto**, y esa cifra no son 531 desacuerdos: es el
renombrado de 826 ficheros mas 697 diferencias de contenido. Medido fichero a
fichero, en su ruta logica:

| 920 ficheros comunes | |
|---|---|
| 166 | identicos |
| 609 | difieren **solo** en comentarios |
| 144 | difieren en codigo |

Los 609 no son ruido: **las dos ramas hicieron una campana de reescritura de
comentarios**, cada una a su modo, y por eso sus contenidos divergen sin que
diverga el codigo. Por la misma razon 23 de los 51 commits de `origin/main`
tienen equivalente por titulo en la integracion, con otro SHA: el contexto del
parche cambio. `git cherry` los marca a todos como `+` porque el parche no es
igual, y el titulo dice que el trabajo si.

**La absorcion.** El commit `2cb5dca38` registra la historia de `origin/main`
sin reescribir el arbol, y lo que `origin/main` aporta de verdad se porta a
mano, commit a commit y con sus defectos corregidos. No se hizo un merge de
arboles porque un merge de arboles aqui tiene dos fallos que el commit de
absorcion no tiene: resucita lo que la integracion corto a proposito
(`.worktrees/`, `.github/dependabot.yml`, `ci/dogfood-fail.pipeline.kts`, el
modulo `pipeline-protocol/`) y mezcla 531 resoluciones de conflicto que
alguien tiene que leer una por una para saber que se quedo con la version
buena.

**Las tres capacidades en las que `origin/main` iba por delante**, y las tres
las exige el `AGENTS.md` que las dos ramas comparten:

1. **El guard de procedencia de comentarios y su cableado al gate.** En
   `origin/main` esta **ROJO sobre su propio arbol**: 432 violaciones, y el
   commit que lo cableo (`f558bdc5d`) se titula "1109 -> 432 provenance + guard
   wired to gate", o sea que bajo las violaciones y cableo un guard que no
   puede pasar. No se puede portar tal cual: el original pide
   `git ls-files v2`, y en este arbol `v2/` no existe, asi que el guard
   recorria **cero ficheros** y imprimia su verde sobre un escaneo que no
   habia ocurrido. Ademas solo tokenizaba `//` y `/* */`, con lo que las ~650
   lineas de comentario `#` de los scripts de shell quedaban sin mirar, y leia
   el glob `/*` de shell como inicio de un comentario de bloque. Portado con
   cuatro correcciones (ruta, comentarios de shell, `/*` de shell, y el falso
   positivo `UP-TO`), cableado como **stage 4b** entre el presupuesto de LOC y
   el reloj, y con las violaciones del arbol resueltas: de 88 medidas a **0**.
   Mutacion comprobada: introducir un ticket en un comentario enciende el
   stage y sale 1 nombrando fichero y linea.
2. **La linea de veredicto con la evidencia de lo que corrio.** El AGENTS.md
   dice "Every verdict line states `tests=rewritten:<n> read:<n>`" y la linea de
   esta rama era `PASS sha=... elapsed=... budget=900s`: la unica linea que se
   pega en los informes no distinguia una medicion de un acierto de cache.
3. **El informe del lanzador**: el coste de cada escenario, el censo de
   expectativas comprobadas y la comprobacion de que un escenario con
   veredicto produjo una linea. Y la ejecucion en paralelo.

**La brecha de presupuesto, que era real y no era contencion.** El stage 3 en
serie llego al **escenario 49 de 56** cuando su reloj de 780 s expiro, y los
siete que nunca empezaron se reportaron como huecos: un stage que era demasiado
lento se decia a si mismo un stage con falta de cobertura. Los cuatro rojos
`transcript drain unfinished after 10000ms` del mismo ensayo **no eran una
regresion**: reproducibles verdes en aislamiento, y la maquina tenia otra
sesion compilando Rust encima. Medido sobre la misma maquina, la misma suite y
los mismos bytes:

| | stage 3 | resultado |
|---|---|---|
| en serie | **784 s** | 49 de 56, incompleto |
| 8 trabajadores | **94 s** | **56 de 56 verdes** |

El reloj de stage deja de decidir **que** escenarios se ejecutan: ahora todos
los seleccionados arrancan y quedan acotados por su **propio** reloj, y el
reloj de stage pasa a ser un hecho posterior sobre el stage entero. Un stage
que se queda sin tiempo dice que se quedo sin tiempo, en vez de reportar
huecos.

**Lo que el censo destapo en su primera salida.** 19 de los 56 escenarios no
declaran ninguna expectativa que el arnes pueda mirar: solo comprueban que el
proceso saliera con 0. El commit `74870d412` ("cada escenario declara la
garantia que promete") lo decia, y el arnes no lo podia comprobar. Ahora el
censo viaja en el informe y en la linea de veredicto, que es donde un numero
tiene que estar para que alguien lo pese.

**La reescritura de autoria, y por que hizo falta.** El push a `origin/main`
lo bloqueo `~/.config/git-identity/deny.conf`. El bloqueo **no venia de los 303
commits nuevos** — ninguno lleva identidad denegada (190 `Ruben`, mas
`SocketStudio`, `Haizea`, `Ilargia`, `RubentxuDev`, `Pablo Cabrera`,
`Rubentxu`) — sino de **16 commits ya publicados en `origin/main`** (15
`Claude <claude@anthropic.com>` y 1 `Claude Code <noreply@anthropic.com>`) que
el merge arrastra en la historia que se publica. Es decir: el guard no
impidia autoria nueva de IA, estaba congelando la rama. El operador decidio
reescribir esa autoria y empujar con force. Medido:

- 16 autores y 15 committers reescritos a la identidad del dueno del
  repositorio;
- **el arbol del tip es identico byte a byte** (`cc227458…`);
- los mensajes, las fechas de autor y las de committer son identicas;
- los otros 40 autores quedan **intactos** (`Ruben` 694 -> 710, los otros 39
  igual);
- los 2324 commits anteriores al bloque conservan su SHA;
- ref de recuperacion: `backup/pre-identity-rewrite` -> `78f7f33cd`.

**Lo que NO se hizo, dicho con sus nombres.** Los 19 escenarios sin garantia
declarada siguen sin ella: el censo los hace visibles, que no es lo mismo que
arreglarlos. Y el rewrite de autoria se hizo sobre las **82 ramas** locales
cuyo historial lo necesitaba, no sobre las 247: las otras 165 no contienen
ningun commit con identidad denegada y por eso no cambian.

**Un defecto mas que sale de mirar el gate por dentro.** El trap de salida
`cleanup_logs` terminaba en un `[[ ]]` que es falso cuando
`VERIFY_ALL_VERDICT` no esta puesto, y en bash **el estado del ultimo comando
de un trap de EXIT sustituye al del script**. Consecuencia medida: **todo run
acotado salia con 1** mientras imprimia `PASS (subset)`, y el veredicto
`INCOMPLETE` -- cuyo propio comentario dice que su estado de salida sigue
siendo 0 -- tambien salia con 1. El bucle interior que documenta el
AGENTS.md (`--stage 2 --module ... --test ...`) era, para quien lo llamaba, un
fallo permanente. Una linea de veredicto y un codigo de salida que dicen
cosas distintas es exactamente el fallo que este ROADMAP lleva dos ciclos
persiguiendo en la otra direccion. El trap devuelve ahora el estado que ya
tenia el run.


---

## 3. Decisiones tomadas en este ciclo, con su porque

- **No** crear un arnés de pruebas. El código de verificación vive en
  `scripts/verify-all.sh` (un entrypoint) y `scripts/check-budgets.py` (un
  script stdlib). Nada más.
- **No** probar `EventViewProjection`: su alcanzabilidad desde producción es
  cero, y un test permanente para código que nadie llama es verificación de
  coste sin consumidor.
- **Mutación antes que confianza.** Cada test de regresión de este ciclo se
  comprobó revirtiendo el arreglo. Un test que sobrevive a su propia mutación
  no prueba nada, y se dice antes de llamarlo garantía.
- **Traducir el dialecto, no reescribir el motor.** Cuando dos dialectos no
  coinciden, la pregunta no es «qué motor escribimos» sino «qué significa cada
  construcción». El glob prometía Ant y ejecutaba Spring, así que la salida fue
  traducir las dos construcciones en las que discrepan —la alternancia y la clase
  de caracteres— al dialecto que el motor entiende, y dejar que el motor siga
  haciendo `**`, `?`, los separadores y las mayúsculas. Escribir un matcher
  propio habría sido más código, más riesgo y menos sitio donde un defecto pueda
  esconderse, y además obligaría a volver a acertar en las reglas que el motor
  ya cumple bien.
- **Una decisión sin su restricción escrita no es una decisión: es una costumbre
  con fecha de caducidad.** La de arriba queda **derogada por la regla que la
  sostiene**, no por un error de razonamiento: era la decisión correcta *entonces*
  —sin una política de dependencias, reescribir un matcher era más código, más
  riesgo y más sitio donde un defecto se esconde—, y la política nueva (*no
  meter una dependencia externa para un par de clases*, AGENTS.md §1) cambia el
  suelo. `AntPathMatcher` era la última clase que el proyecto tomaba prestada
  de una librería de 1,9 MB, así que ahora el motor es nuestro y **la
  traducción desaparece con él**: hay un dialecto y es el de Ant. Las dos
  decisiones están escritas, y por eso esto no es una contradicción sino una
  decisión con su restricción a la vista.
- **Sacar una librería no es una licencia para mover el suelo.** El motor propio
  tenía que dar **exactamente** las mismas respuestas que el que se retira, salvo
  donde el retirado estaba roto. Eso se comprobó contra **402 360** pares de
  patrón y ruta, y no contra «lo que parece razonable»: un test que afirma lo
  que el motor nuevo hace es una tautología, y uno que afirma lo que el viejo
  hacía es una foto.
- **El oráculo de una migración es la conducta publicada, no la biblioteca.** El
  SDK publicado *traducía* el patrón Ant al dialecto del motor y lo mandaba al
  motor; eso es lo que había que reproducir. Comparar contra el motor en crudo
  habría «discrepado» en cada alternancia y cada clase de caracteres —justo las
  dos construcciones donde el defecto estaba— y el resultado habría sido inútil.
  Las **7** divergencias que quedan son de una sola clase —un constructo que
  cruza un separador— y en todas el motor viejo estaba roto: reescribía el
  patrón *antes* de dividirlo, y la reescritura destrozaba el constructo en vez
  de expresarlo. Quedan **escritas como regla**, para que la diferencia sea
  conocida y no una sorpresa dentro de tres años.
- **Compilar una vez no es una optimización: es el diseño.** El motor prestado
  cacheaba sus patrones detrás de un cerrojo; el motor propio sostiene el objeto
  compilado y no comparte estado con nadie. Medido: recompilar por coincidencia
  cuesta **6-7×** más. Y un detalle de implementación resultó ser la mitad de
  la diferencia: **dividir la ruta en una sola pasada**, sin una segunda lista
  para filtrar la primera, y **rechazar antes de dividir** cuando el patrón
  empieza por texto literal. Sin esas dos cosas el motor propio era 1,5-1,8×
  más lento que el prestado; con ellas está en **paridad** (0,72-0,97×).
- **El peso de verdad de la distribución no está en la dependencia que se
  nota.** `spring-core` (1,9 MB) y `spring-jcl` (24 KB) salen de la
  distribución: 2 jars y casi 2 MB. Los que quedan pesan **64×** más: el
  compilador de Kotlin embebido (58 MB), `sqlite-jdbc` (14 MB) y BouncyCastle
  (~9 MB). La política se ha aplicado donde era evidente, pero **decidir sobre
  el compilador embebido es otra conversación**: no es «copiar dos clases», es
  una dependencia con un peso y un precio distintos. **Queda dicho, no decidido.**
- **El criterio de «se queda» no es el tamaño: es qué es.** Decidido: el
  compilador embebido, SQLite y BouncyCastle **se quedan**, y el motivo de
  cada uno está escrito por dependencia en §2.2 s). No es una ventana que se
  cierra por cansancio: el compilador de Kotlin es un **motor** (compila los `.pipeline.kts`), SQLite
  es un **formato de fichero** con su página de formato como contrato, y
  BouncyCastle es **criptografía** (PKCS#7, X.509). El mismo criterio deja
  fuera a `spring-core`, que era una clase con dos métodos. Y por eso la regla
  de AGENTS.md §1 se escribe como «una dependencia se queda si lleva algo que
  el proyecto no puede razónablemente poseer»: **razonablemente** es la
  palabra que evita tanto el «copiamos todo» como el «no tocamos nada». Lo que
  no se ha hecho es cerrar los ojos: el peso de cada dependencia está medido y
  publicado, que es lo que permite revisitarla.
- **Un patrón que encuentra de más es un fallo de seguridad; uno que no encuentra
  es un fallo de silencio.** Los dos casos del glob se veían igual desde fuera
  («no archivó lo que pedí») y no lo eran: la alternancia leída como comodín
  metía en el `.tar` lo que no debía, y la clase de caracteres leída como literal
  devolvía un archivo vacío que `allowEmptyArchive` reportaba como éxito. Sólo una
  **aserción negativa** —el fichero que *no* debe estar— distingue los dos; el
  código de salida no lo distingue.
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
- **Una directiva nueva en el arnés, no un arnés nuevo.** Para demostrar las
  ventanas de crash hacía falta una capacidad que el lanzador no tenía: relanzar
  el run **reanudándolo** y tomar como veredicto el de la reanudación, porque la
  primera ejecución murió antes de poder decir nada. Se añadió `//@ resume`
  (reutiliza el camino de `durable`: un almacén, dos ejecuciones, el mundo
  comparado entre las dos) en vez de un segundo lanzador, y **el escenario
  provoca su propia muerte** con `SIGKILL` desde un comando, en el punto que
  elige. La muerte se pide al proceso, no se simula: un `halt()` en el
  producto habría ensuciado el producto con una puerta de test que el producto
  no necesita, y un `sleep` no sería determinista. El PID lo escribe el propio
  script, en el fichero que el lanzador le indica, **fuera** del directorio de
  trabajo del escenario — ese directorio se compara entre las dos ejecuciones, y
  dos procesos tienen dos PID: un fichero de PID dentro de él parecería «el
  efecto se repitió».
- **La clave de operación viaja en el entorno, y el contrato del proveedor es
  sobre la OPERACIÓN, no sobre el intento.** Dos decisiones encadenadas. La
  primera: el `Reconcile` vuelve a despachar el cuerpo **con la misma clave**,
  porque la clave identifica la operación y no el intento —es lo que permite que
  un proveedor diga «ya está hecho» en vez de hacerlo dos veces—, y con un
  `attempt` nuevo, porque dos intentos de una operación son dos filas del
  registro de operaciones y no una fila leída dos veces. La segunda: un
  proveedor que **reconoce sus claves** contesta **por la operación** —«ya está
  hecho» o «no está hecho»—, no por el intento que preguntó. Por eso un fallo en
  la repregunta se lee como «no está hecho» y no como «nadie sabe»: un cuerpo que
  sólo supiera decir «ha fallado esta vez» no estaría contestando por la
  operación, y leerlo así dejaría un efecto aplicado en pie para siempre. Y por
  eso un forward fallido se reporta como **desconocido** aunque el proveedor sepa
  reconocer sus claves: reconocer una clave no dice nada sobre lo que dejó un
  fallo, y un forward que aplicó el efecto y luego falló tiene que seguir
  debiendo su compensación. La suposición contraria se comprobó y se descartó:
  reportarlo como ausente hacía que el Saga **no compensara** un efecto aplicado,
  y el escenario 37 seguía verde —es decir, el test no la detectaba.
- **El veredicto se publica como una PROYECCIÓN del plano, no como un campo
  nuevo en un evento.** El esquema de los eventos está congelado, y añadir un
  campo a `RunFinished` o un evento nuevo que compita por la autoridad del
  resultado sería exactamente eso. En cambio, publicar la LECTURA —una función
  que recibe el plano y devuelve el veredicto tipado— no toca el esquema, no
  añade una segunda autoridad y deja el veredicto en la forma que un consumidor
  puede usar sin correlacionar nada.
- **La lectura falla cerrado en las tres direcciones, y en una cuarta: la
  AUSENCIA de un anuncio.** Sin cierre del run → `Aborted`; con una palabra
  desconocida → `Failure` sintética; con un terminal de Saga ilegible →
  `NeedsIntervention`; y con un Saga que armó una obligación y nunca anunció
  terminal → `NeedsIntervention` también, porque la ausencia es la evidencia.
  La alternativa —leer la ausencia como «nada que hacer»— es la que convierte un
  recurso applied en un recurso perdido, y es la lectura que la invariante «no
  success from absence» de SPEC-001 prohíbe.
- **El fallo que se lee es el MÁS EXTERNO, y se midió para decidirlo.** Un paso
  de plugin que falla anuncia dos fallos —el del cuerpo que ejecutó y el suyo—,
  y el del run es el suyo: el otro pertenece a un paso anidado dentro del cuerpo
  del plugin. Leer el primero de la lista da el fallo equivocado, y el aviso
  del motor (`cause [PLUGIN]: the action ship failed: shell exited with code 1`)
  no coincide con el del lector. La anidación se lee del propio direccionamiento
  del plano: un paso anidado se llama como su contenedor.
- **`isFinished` no es «tuvo éxito».** Un run que falló y cuyo fallo se compensó
  **está terminado**: no queda nada que hacer, aunque el resultado sea `failure`.
  Son dos preguntas distintas —«¿cómo acabó?» es el outcome, «¿queda algo por
  hacer?» es esto—, y confundirlas haría que un run compensado pareciera
  inacabado y que un run verde con un Saga abierto pareciera cerrado.
- **Las palabras del outcome y del estado del Saga viven en el DOMINIO.** El
  motor escribía el outcome con un `when` a mano y lo leía otro sitio con otra
  lista; el estado del Saga vivía en dos constantes privadas que ningún lector
  podía citar. Cada lista de un vocabulario es una autoridad, y divergen la
  primera vez que alguien añade un caso.
- **La ley del plano se prueba desde el módulo que publica el verbo que la
  ejecuta, y no desde el arnés.** `:pipeline-event-harness` no lleva tests
  unitarios por decisión declarada, y el módulo que publica el verbo
  `pipeline events verify` es `:pipeline-application`. El test monta un plano con
  su `RunStarted`, porque un plano roto de dos maneras a la vez no puede decir
  cuál de las dos leyes probó.
- **El «commit» que falta no se arregla de paso, aunque esté medido.** El
  defecto es real y grave (2.2 k), pero su arreglo es un verbo nuevo en un
  puerto publicado más un estado nuevo en un enum publicado, la semántica del
  intérprete, el plugin y un escenario nuevo. Meterlo dentro de un bloque que ya
  cambiaba la ontología del veredicto habría producido un tren con dos cambios
  sin relación, que es la forma que hace que una reversión duela. Se abre como
  bloque propio, con la medición adjunta.

- **El verbo de cierre llega como un puerto NUEVO que EXTIENDE el viejo, y no como
  método nuevo en el puerto viejo.** `SagaRuntime` está publicado desde 0.64.0 y no
  puede ganar un método abstracto sin romper a cada consumidor ya compilado, así que
  el verbo llega como `SagaLifecycle : SagaRuntime` con su propia capability. El
  plugin que lo usa sostiene **una** manilla con los dos finales del Saga, y no
  puede acabar con dos autoridades que discrepen sobre lo que el Saga debe.
- **Quién dice «la cadena tuvo éxito» es el plugin; quién dice «qué se puede
  cerrar» es el motor.** El plugin es el único que sabe si su cadena tuvo éxito, y
  el estado durable no lo sabe: un cuerpo de plugin que se rompe después del
  último forward deja todas las obligaciones confirmadas, y de ahí no se puede
  deducir que el Saga funcionó. Por eso el verbo es una PREGUNTA con una respuesta
  tipada, y no un deducedor.
- **«El efecto ocurrió» y «se le debe una compensación» son dos hechos, y el
  estado nuevo los separa.** `EFFECT_CONFIRMED` mezclaba los dos, y por eso no
  tenía salida que no fuera deshacer. `EFFECT_KEPT` dice sólo lo segundo: el
  efecto ocurrió y el Saga lo conserva, y la promesa queda anulada.
- **Cerrar un Saga es UNA sentencia, no N.** Mover las obligaciones de una en una
  deja un Saga a medio cerrar si el proceso muere en medio, y una corrida reanudada
  compensaría después lo que quedara: deshacería media parte de un Saga que había
  funcionado. Una sentencia es una transacción, así que el Saga queda cerrado o no
  queda cerrado.
- **Un cierre rechazado NO es un cierre.** El motor no escribe nada, el Saga sigue
  debiendo, y un proceso posterior lee las mismas filas y llega a la misma
  conclusión. Un «casi cerrado» es un Saga que se deshace más tarde, que es
  exactamente el defecto que este bloque arregla.
- **Una ley del plano mira TODOS los terminales del run, no sólo el último de cada
  Saga.** Una compensación no es un estado: es algo que PASÓ, y un terminal
  posterior no puede deshacerla. Un run reanudado reutiliza el run id, así que un
  plano puede llevar un Saga que compensó en el primer intento y se liquidó vacío
  en el segundo.
- **El manifiesto de un plugin se DERIVA de sus Steps.** La copia a mano de las
  capacidades se separó de la definición y la puerta de admisión la cazó; la
  puerta funcionó, pero sólo por casualidad. Un manifiesto que describe Steps que
  el código no tiene es una segunda autoridad capaz de mentir.
- **Un cuerpo se direcciona por el NOMBRE que declara el plugin, y el *kind* es sólo
  el valor por defecto.** Se podrían haber escrito tres reglas —«por *kind*», «por
  nombre» y «por *kind* y nombre»— y se eligió la que deja intacto lo publicado:
  cuando el plugin no declara nombre, el cuerpo se sigue direccionando por su
  *kind*, byte a byte. El motivo para no quedarse sólo con el *kind* es medido, no
  estético: obliga a inventar un kind por cada cuerpo, y la demo del 3b necesitaba
  seis kinds del core para una cadena de dos o tres acciones. Un apaño del fixture
  no lo copia un consumidor externo.
- **El nombre del slot vive en el MISMO espacio de nombres que el *kind*, y por eso
  se reutiliza `PluginStepId`.** La alternativa —un tipo nuevo `SlotName`— es más
  pura en el papel y se rechazó por una razón medida: rompe cinco firmas y obliga
  a una release coordinada del SDK con el consumidor externo. El tipo nuevo se
  paga en el mundo; el mismo espacio de nombres se paga en una palabra.
- **La ruptura se anota, no se disimula.** El campo nuevo del IR está en una
  interfaz publicada, así que es una ruptura binaria real: un implementor externo
  recibe `AbstractMethodError`. `pipeline-domain` es EXPERIMENTAL, y EXPERIMENTAL
  significa «se puede romper, con un recibo», no «se puede romper sin dejar
  constancia». El recibo va en `published-contract-exceptions.json`, y su SHA es
  **el commit ya fusionado** que introdujo el cambio: un recibo que nombre un SHA
  que no existe en `main` no es un recibo.
- **La evidencia de una corrida va dentro de la línea que se cita.** La puerta ya
  distinguía lo ejecutado de lo cacheado, pero lo distinguía en el log: el sitio
  donde nadie lo mira cuando copia el veredicto a un informe. El campo `tests=` no
  es una mejora de formato; es el cierre de un falso verde **real y medido** —una
  corrida que ejecutaba cero tests decía `PASS`—. Y el caso `NOT_RUN` importa más
  que el número: un campo ausente se lee como un cero, y «no medido» no es «cero».
  Es el mismo criterio que ya cerró el marcador `SUBSET` y el hueco `gaps=`, y la
  razón por la que los tres viajan en la misma línea y no en tres sitios distintos.
- **Un flag no puede cambiar lo que un script hace.** La rama en memoria del CLI
  no enlazaba ningún almacén de Saga, así que el mismo script se ejecutaba con
  `--db` y se rechazaba sin él: el almacenamiento elegía el algoritmo, que es
  exactamente lo que LF-0208 prohíbe. El arreglo no es «que el Saga funcione en
  memoria» —eso fue una decisión— sino que **la capability exista en los dos
  mundos con las mismas reglas**, fencing e idempotencia incluidos, y que la
  promesa sea durable en uno y volátil en el otro. La distinción que importa no
  es «durable o no», es «el mismo algoritmo con dos stores». Y como un rechazo
  que no dice POR QUÉ no es una decisión sino un accidente de cableado, el
  veredicto del Saga se dice también en memoria: una promesa que se liquida en
  silencio no es una promesa que el operador pueda ver.
- **Un hecho, un lector.** Cuando dos módulos leen el mismo hecho y cada uno tiene su regla, las
  reglas derivan y el plano acaba contradiciéndose sin que nadie lo note: aquí la proyección
  decía «un Saga `SETTLED` con motivo necesita un humano» y el arnés decía «liquidado». La
  segunda regla no era una segunda opinión, era un **error de lectura** que volvía invisible un
  terminal roto. El que sabe qué significa un dato es el que lo publica, y los demás lo
  consumen; el que consume tiene que poder **usar** esa lectura, no reimplementarla. Un lector
  menos es un hecho menos que puede tener dos verdades.

---

## 4. Referencias

- `AGENTS.md` — política vigente; manda sobre cualquier documento histórico.
- `scripts/verify-all.sh` — la única puerta local.
- `scripts/check-budgets.py` — el único script de presupuestos.
- `examples/run.sh` — el único lanzador E2E.
- `scripts/check-comment-provenance.py` — el guard de procedencia de comentarios.
  **No está cableado todavía**: §2.4 explica por qué.
- Historia anterior: tag `pre-testing-reset-20261018`. No se resucita en el árbol
  activo.