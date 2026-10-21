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
| SHA del censo | `ad61e9b2c` | el SHA sobre el que se midió; el `HEAD` avanza al commit que actualiza esta tabla |
| Módulos declarados | 27 | bloque `include(` de `v2/settings.gradle.kts` (líneas 33-106) |
| Módulos con test unitario permanente | **22** | etapa 2 del gate, que imprime cada módulo por nombre |
| Módulos sin test | 5 | ídem |
| Tests unitarios ejecutados | **774** (95 informes JUnit, **95** reescritos por la corrida, 0 fallos, 0 skips) | etapa 2 del gate, y la línea de veredicto: `tests=rewritten:774 read:0` |
| Escenarios E2E | 46 (**45 pasan, 0 fallan, 1 `NOT_RUN`**) | `v2/examples/**/*.pipeline.kts` |
| `VERIFY_LOC / PRODUCT_LOC` | **12139 / 66328 = 0.183** (límite 2) | `scripts/check-budgets.py --json` |
| Ratio máximo por módulo | **1.633** (`pipeline-step-sdk:workflow-control`) | ídem |
| `VERIFY_ALL_WALL_CLOCK` | **95 s** (corridas de este bloque: 95 s sobre el SHA publicado, 97 s y 80 s sobre el mismo árbol antes de fusionar), límite 900 s | etapa 5 del gate, `§4` de `AGENTS.md` |
| Coste por escenario E2E | mediana **6,7 s**, p95 **14,9 s**, máximo **15,5 s** | el lanzador publica ahora el tiempo de cada escenario en su línea |
| Certificador externo | **`BLOCKED_EXTERNAL`** | no existe en este repositorio ni en remoto |

La cifra de reloj de este ciclo es de **una** corrida con las cachés de Gradle
calientes, y por eso **no** se compara con la de 2026-10-19 (226 s, la peor de
tres ensayos tras `rm -rf` de las salidas de build reales, que es la condición
que hace que la cifra mida el gate y no una restauración de caché). Un total
correcto con el verbo equivocado ya no puede colarse: la etapa 2 separa
`rewritten` de `read` y **falla** si no reescribe ninguno, y aquí reescribió 95
de los 95 informes —se limpiaron las salidas de test antes de correr, así que
ninguno pudo venir de la caché—.

**Y el total viaja ahora en la línea de veredicto**, que es la única que alguien
pega en un informe: `tests=rewritten:774 read:0`. Antes esa línea decía
`PASS  sha=…  elapsed=345s  budget=900s  gaps=1 not_run` y no decía **nada** de
los tests: el dato estaba en el log de la etapa 2, y el log lo lee quien corrió la
puerta, no quien la cita. Una cifra con el verbo equivocado ya no puede colarse
ni por el lado del log ni por el lado de la línea (§2.2 u).

La corrida que se publica es la de `ad61e9b2c` (**95 s**). El reloj de este gate
tiene ruido, y publicarlo sin decirlo vuelve a ser una afirmación que nadie
puede reproducir: el ciclo anterior (`4b0a63415`) publicó **88 s**, el de
`main-partido` (`03bdc2a3a`) midió **107 s**, y otra corrida sobre ese **mismo**
contenido, con los módulos tocados compilando de cero, midió 98 s. Y el ruido de
este bloque es el habitual: sobre este mismo árbol, tres corridas dieron
**80 s**, **97 s** y **95 s** —las dos últimas, la segunda antes de la fusión y la
última sobre el SHA que quedó publicado—, y una anterior, con el lanzador aún
sin el arreglo del escenario storage-agnostic, dio **98 s**. Se publica la del **SHA final**, que es
la que corresponde al árbol que se publica, y se dicen las otras para que nadie
la lea como la típica. Este bloque **añade 22 tests** —los **774**— y **no añade
ningún escenario E2E**: los **46** son los mismos, porque la capacidad nueva se
prueba con pruebas deterministas y con un UAT que no se queda.

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
| 4 | E2E solo como `.pipeline.kts`, un lanzador | **PASS** | 35 escenarios en 8 categorías bajo `v2/examples/`; 0 `src/test` dentro de `examples/*`; lanzador único `v2/examples/run.sh` (186 líneas) invocado por `scripts/verify-all.sh:246` |
| 5 | `VERIFY_LOC <= 2 * PRODUCT_LOC`, global y por módulo | **PASS** | 11389 / 65556 = 0.174; ningún módulo declara `breach`; el más alto es `workflow-control` con 1.633 |
| 6 | `verify-all` local ≤ 900 s | **PENDIENTE** | requiere los tres ensayos sobre el SHA de la tabla |
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
- **Presupuesto de tiempo: MEDIDO, `CERRADO`, y MEJORADO.** El ensayo de este
  ciclo dio **98 s** sobre `8d4a49be8` (el árbol que cierra el glob), con el límite
  en 900 s, leído de la línea de veredicto de la etapa 5
  (`PASS  sha=…  elapsed=97s  budget=900s`) y no del código de salida: margen de
  **9,3×**. El ensayo anterior dio 91 s sobre `bc2acec7c` (árbol idéntico a
  `96220fdad`). El reloj **varía ±10 s**
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
- `v2/examples/run.sh` — el único lanzador E2E.
- Historia anterior: tag `pre-testing-reset-20261018`. No se resucita en el árbol
  activo.