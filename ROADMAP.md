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
| SHA | `cfb590cc4` medido en los tres ensayos; el commit posterior de documentación sólo toca este ROADMAP y los README, así que el código medido es el de `cfb590cc4` | `git rev-parse HEAD` |
| Módulos declarados | 27 | bloque `include(` de `pipelinek/settings.gradle.kts` |
| Módulos con test unitario permanente | **26** | etapa 2 del gate, que imprime cada módulo por nombre |
| Módulos sin test | 1 (`:pipeline-sdk-bom`, que no tiene código) | ídem |
| Tests unitarios ejecutados | **889** (119 informes en disco, 0 fallos, 0 errores, 0 skips) | etapa 2 del gate, confirmado con un censo independiente de los XML |
| Escenarios E2E | **49 (49 pasan, 0 fallan)**, 2 reruns durables, 7 credenciales sembradas | etapa 3 del gate |
| Escenarios que sólo comprueban el código de salida | **0**: cada uno afirma, con una directiva del lanzador o con una comprobación dentro del propio pipeline | censo por directiva y por cuerpo, sobre los 47 escenarios |
| `VERIFY_LOC / PRODUCT_LOC` | **14139 / 64950 = 0.218** (límite 2) | `scripts/check-budgets.py --json` |
| Ratio máximo por módulo | **1.633** (`pipeline-step-sdk:workflow-control`) | ídem |
| `VERIFY_ALL_WALL_CLOCK` | **608 / 605 / 623** en los tres ensayos, los tres sobre `cfb590cc4`, peor 623 s, límite 900 s | etapa 5 del gate, `§4` de `AGENTS.md` |
| Veredicto del gate | **`PASS`** en los tres ensayos, sin `NOT_RUN` | línea final de `verify-all.sh` |
| Deuda estática congelada | **802** entradas de baseline: **sin cambio**, porque la ley que este ciclo añadió salió a su propio objeto en vez de empujar a `ProtocolGrammar` por encima del límite de detekt | `detektBaselineMain` y `detektMain` |
| Certificador externo | **`BLOCKED_EXTERNAL`** | no existe en este repositorio ni en remoto |

Las cifras de este ciclo suben en lo que este ciclo añadió y sólo en eso:
**889** tests (18 más que los 871 del ciclo anterior) y **49** escenarios (1
más). Los 18 tests nuevos son las **3** filas de `StepFailureAttributionTest`
en `:pipeline-events` (el sujeto del fallo, el sujeto cuando el fallo no dice
stage, y el fallo compartiendo sujeto con el terminal de su step), las **3** de
`StepFailureAttributionTest` en `:pipeline-application` (el productor canónico
que declara el stage, el que lo declara por excepción, y el que lo declara
igual que su terminal), las **3** del códec (`StepFailedStageCodecTest`: el
stage sobrevive al log durable, un stage ausente se relee como ausente y una
fila de un build anterior se lee sin stage), las **7** de
`TerminalContradictionLawTest` (la contradicción, el reintento, el
`catchError`, el fallo simple, el step anidado, dos stages con el mismo nombre
de step, y el fallo que no dice su stage), **1** fila más en la proyección del
run (que lee el veredicto igual con stage y sin stage) y **1** fila más en el
registro de capacidades (la que impide que dos ids publiquen el mismo
vocabulario). El escenario nuevo es `events/03-failure-stage`, el lector
externo que pide sólo los eventos de fallo y lee el stage de cada uno. El
ratio de LOC sube de 0.211 a **0.218**, todavía muy por debajo del límite 2, y
el peor módulo sigue siendo el mismo de siempre
(`pipeline-step-sdk:workflow-control`, 1.633) porque este ciclo no lo tocó.

Lo que este ciclo **no** tocó, y conviene que no se confunda con un avance:
el número de módulos con test sigue siendo 26 de 27. La deuda estática
congelada **no se movió**: sigue en **802** entradas, ni una más ni una menos.
La razón es la respuesta que da el propio config de detekt cuando una clase
acumula lógica —**extraer, no anotar**—: la ley de contradicción terminal que
este ciclo añadió se llevaba `ProtocolGrammar` por encima del límite de
funciones, así que salió a su propio objeto, `StepTerminalContradiction`. Un
cambio que crece y no crece la baseline a la vez no es un cambio: es una
negociación con el guardián.

**Las mutaciones de este ciclo, vistas en rojo.** Un verde que nunca se ha
visto rojo no es evidencia, así que cada instrumento se rompió a propósito, se
leyó de vuelta y se deshizo: **M1** (el productor canónico deja de decir el
stage) encendió **3 de 3** filas del productor y el ejemplo ejecutable, con el
mensaje «delivered: second/sh-0=-1»; **M2** (el escritor se come el stage)
encendió **1 de 3** filas del códec —la del viaje, y dejó verdes la del stage
ausente y la de la fila de un build anterior— y el ejemplo, con «delivered:
second/sh-0=»; **M3** (la ley nunca dispara) encendió **1 de 7** filas de la
ley; **M4** (la ley pierde la ventana del intento) encendió **1 de 7**, la del
reintento; **M5** (la ley pierde el nombre de la identidad) encendió **1 de
7**, la del step anidado; **M6** (el stage ausente se lee como stage 0)
encendió **1 de 7**, la del stage ausente; y **M7** (el sujeto del fallo se
queda en el run) encendió **2 de 3** filas del sujeto. Una mutación **no**
encendió nada y queda escrita por eso: quitar el stage de la identidad de la
ley no cambia ningún veredicto, porque en un plano secuencial la ventana del
intento ya separa los steps de dos stages (§2.29).

Los ensayos se ejecutaron desde salidas de build limpias y cada uno reescribió
los **119** informes JUnit, que es la condición que hace que la cifra de reloj
mida el gate y no una restauración de caché. Un total correcto con el verbo
equivocado ya no puede colarse: la etapa 2 separa `rewritten` de `read` y
**falla** si no reescribe ninguno.

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
nunca había corrido. Hoy lo declara uno de los 30, y es
`durable/22-wait-until.pipeline.kts`, que
es el escenario que puede hablar del assunto y ningún otro puede — un cuerpo que
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

**j) ~295 líneas de código en 126 ficheros citan documentos ADR que no existen.**
La misma clase que 2.2 e, pero **diez veces más grande**. Medido con
`git grep -cE 'ADR-0'` sobre todo lo trackeado: **309 líneas / 126 ficheros**, de
las cuales **14 están en este mismo ROADMAP.md** —el censo contándose a sí mismo—
y **295 en código**. Reparto por módulo: `pipeline-application` 136,
`pipeline-domain` 79, `pipeline-step-sdk` 50, `pipeline-events-store` 7, y once
módulos más con 1–3 cada uno. Los tres primeros son el **90 %** del total.

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


| Token | Líneas | Ficheros |
|---|---|---|
| `ADR-0…` | 295 (309 con este ROADMAP) | 126 |
| `INV-L…` | 63 | 22 |
| `docs/` | 23 | 10 |
| `FArchL` | 2 | 1 |
| `WCL-S` | 2 | 2 |
| `FIL-ALL` | 1 | 1 |
| `design.md §` | 0 en código (2 son las de este ROADMAP) | 1 |

Todas las filas de esta tabla se remedieron con **un solo instrumento**,
`git grep -n <token> -- .` sobre todo lo trackeado, que **incluye este
ROADMAP.md**. Las cifras anteriores mezclaban instrumentos: por eso las de
`ADR-0…` no cuadraban con su propia prosa.


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

### 2.5 Las promesas colgantes que quedan — medido, no estimado

El patrón anterior no era de `pipeline-events`. Se extendía.

**Instrumento** (reproducible, sobre el árbol commiteado):

```
tracked=$(git ls-files '*src/test/*' | sed 's|.*/||; s|\.kt$||' | sort -u)
grep -rhoE '\b[A-Z][A-Za-z0-9]*(Test|Tests|FitnessTest|FalsifierTest|LawsTest)\b' \
     --include='*.kt' pipelinek/*/src/main | sort -u   # menos los que $tracked contiene
```

**Cifras, con su denominador:**

| magnitud | valor |
|---|---|
| referencias a clases de test citadas en `src/main` **sin** fichero que las declare | **73** |
| ficheros de producción que contienen al menos una | **70** |
| módulos afectados | **11** |
| de esas 73, las que además **afirman** un instrumento (`pins`/`guarded`/`falsifier`/`asserts`) | **13** |

Dos advertencias sobre el propio instrumento, porque sin ellas la cifra engaña:

1. Con el sufijo `*Spec` el mismo barrido da **102**, no 73. La diferencia no son
   tests faltantes: `ToolSpec`, `TaskSpec`, `TimeoutSpec` son ADTs de dominio. Un
   censo que no conoce el vocabulario del repositorio cuenta su propia
   exclamación como deuda.
2. El censo cuenta **menciones**, no promesas. Las 2 referencias de
   `pipeline-events` que sobreviven al filtro fuerte son menciones en pasado que
   escribí yo al reescribir (`used to pin`). Quedan **11 abiertas de verdad**.

**Lo que la clasificación cambia.** Casi todas las 73 prometían un instrumento que
la política ya no permite: eran *source-text fitness*, tests que grepeaban el
árbol de fuentes, y AGENTS.md §2 los prohíbe dentro de tests unitarios. No se
pueden «reescribir» como test; solo honestarse o cerrarse. De las 13 fuertes, dos
prometían un **negativo** —«ningún sitio de producción construye `OptionSpec`»,
«nadie vuelve a llamar a `outcomeOf`»— que ningún test unitario puede sostener sin
convertirse en un escaneo de fuentes. Esas dos se reescribieron esta misma pasada
(`CompiledPipeline.kt`, `PostPlan.kt`): la verdad es que la única línea que las
sostiene es la propia `@Deprecated` y la propia totality fail-closed.

**Decisión: NO se reparan las 73.** Repararlas todas es la auditoría interminable
que este encargo prohíbe explícitamente. Se registran aquí con su cifra y su
instrumento. La regla que sí se adopta, para el trabajo que venga: **una promesa en
KDoc se reescribe cuando se toca el fichero, no en una campaña.**

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
| **P0.2** Seal externo de streaming | Core | Un cliente externo lee la historia por cursor, se desconecta y vuelve sin saltarse ni duplicar hechos | Ejemplo ejecutable en `examples/`: un lector externo (el verbo `events` con cursor) que lee, corta y reanuda; y el caso del cursor inválido, que debe salir con su negativa propia | **Hecho** (§2.26): el ejemplo `events/01-external-reader-resumes` falla si el cursor se resetea al reconectar (probado: en rojo nombrando el hecho perdido) y si la negativa degrada a «run desconocido» (ciclo anterior). 4 filas de test unitario sobre la regla de paginación; 2 mutaciones vistas en rojo |
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

## 3. Decisiones tomadas en este ciclo, con su porque
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

## 4. Referencias

- `AGENTS.md` — política vigente; manda sobre cualquier documento histórico.
- `scripts/verify-all.sh` — la única puerta local.
- `scripts/check-budgets.py` — el único script de presupuestos.
- `examples/run.sh` — el único lanzador E2E.
- Historia anterior: tag `pre-testing-reset-20261018`. No se resucita en el árbol
  activo.