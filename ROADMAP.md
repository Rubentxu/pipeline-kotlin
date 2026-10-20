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
| SHA | `46205fb9` | `git rev-parse HEAD` |
| Módulos declarados | 27 | bloque `include(` de `v2/settings.gradle.kts` (líneas 33-106) |
| Módulos con test unitario permanente | **17** | etapa 2 del gate, que imprime cada módulo por nombre |
| Módulos sin test | 10 | ídem |
| Tests unitarios ejecutados | **519** (53/53 informes reescritos por la corrida, 0 fallos, 0 skips) | etapa 2 del gate |
| Escenarios E2E | 36 (**35 pasan, 0 fallan, 1 `NOT_RUN`**) | `v2/examples/**/*.pipeline.kts` |
| `VERIFY_LOC / PRODUCT_LOC` | **6692 / 61623 = 0.109** (límite 2) | `scripts/check-budgets.py --json` |
| Ratio máximo por módulo | **1.633** (`pipeline-step-sdk:workflow-control`) | ídem |
| `VERIFY_ALL_WALL_CLOCK` | **226 s**, peor de tres ensayos (225 / 223 / 226), límite 900 s | etapa 5 del gate, `§4` de `AGENTS.md` |
| Certificador externo | **`BLOCKED_EXTERNAL`** | no existe en este repositorio ni en remoto |

Los tres ensayos se ejecutaron tras `rm -rf` de las salidas de build reales —las
cuyo padre declara `build.gradle.kts`— y los tres reelectionaron los 53
informes JUnit, que es la condición que hace que la cifra de reloj mida el gate
y no una restauración de caché. Un total correcto con el verbo equivocado ya no
puede colarse: la etapa 2 separa `rewritten` de `read` y **falla** si no
reescribe ninguno.

El escenario `NOT_RUN` es `security/14-credentials-bindings.pipeline.kts`, que
declara `requires-seeded-credentials`: el lanzador lo marca `NOT_RUN` en vez de
fabricar credenciales. `NOT_RUN` no es un aprobado, y desde `5240feac` tampoco se
confunde con uno: la línea de veredicto lleva el hueco **dentro**, como
`PASS  sha=…  elapsed=226s  budget=900s  gaps=1 not_run`. Antes el conteo se
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
| 5 | `VERIFY_LOC <= 2 * PRODUCT_LOC`, global y por módulo | **PASS** | 5256 / 61461 = 0.086; ningún módulo declara `breach`; el más alto es `workflow-control` con 1.633 |
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

### Los 17 módulos con test

`pipeline-application`, `pipeline-domain`, `pipeline-events-store`,
`pipeline-step-sdk/runtime`, `pipeline-step-sdk/http`,
`pipeline-step-sdk/workflow-control`, `pipeline-step-sdk/files`,
`pipeline-credentials-api`, `pipeline-credentials-local`,
`pipeline-credentials-multipart`, `pipeline-credentials-executor`,
`pipeline-binding-factory`, `pipeline-artefacts-local`,
`pipeline-runtime-api`, `pipeline-output`, `pipeline-output-store`,
`pipeline-release`.

> **Censo remedido 2026-10-19 desde el disco.** Sobre los 27 módulos que declara
> el `include(` de `settings.gradle.kts`: **17 con `src/test/kotlin/**/*Test.kt`,
> 10 sin él**. La lista de arriba omitía `pipeline-application`, que ya tiene
> `ProjectWorkingDirectoryTest.kt`, y por eso se contradecía con la fila de §1.
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
> sustituye la medición de `scripts/verify-all.sh`**, que sigue siendo la fila
> `pendiente` de §1. Hasta esa medición, el recuento por módulo es histórico.

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
| `pipeline-credentials-local` | 8 | 8 | 92 | 0 | 0 |
| `pipeline-domain` | 6 | 15 | 82 | 0 | 0 |
| `pipeline-output` | 5 | 5 | 74 | 0 | 0 |
| `pipeline-output-store` | 3 | 3 | 36 | 0 | 0 |
| `pipeline-events-store` | 3 | 3 | 31 | 0 | 0 |
| `pipeline-step-sdk/http` | 2 | 2 | 26 | 0 | 0 |
| `pipeline-step-sdk/runtime` | 2 | 2 | 25 | 0 | 0 |
| `pipeline-runtime-api` | 1 | 1 | 21 | 0 | 0 |
| `pipeline-binding-factory` | 1 | 1 | 7 | 0 | 0 |
| `pipeline-credentials-api` | 1 | 1 | 6 | 0 | 0 |
| **total** | **32** | **41** | **400** | **0** | **0** |

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

### 2.2 Lo que queda abierto, por valor operativo

**a) 11 módulos sin test unitario permanente.** No es un objetivo de paridad.
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

Quedan **11** módulos sin test. **Esta redacción anterior los llamaba
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


### 2.3 Cierre de ciclo

- `PRODUCT-GATE` (certificador externo, RC publicada) = **`BLOCKED_EXTERNAL`**.
  No hay CI remoto en este repositorio. La ausencia de CI **nunca** se declara
  `PASS`.
- **Presupuesto de tiempo: MEDICIÓN CADUCADA, pendiente de repetir.** El
  ensayo anterior dio **242 s, 211 s, 213 s** sobre `18d3148a` — el peor, 242 s
  frente al límite de 900 s, leído del XML de JUnit (`executed=400 failed=0
  skipped=0`) y no del código de salida. Esos números **siguen siendo ciertos
  para ese SHA y ninguno más**: desde entonces `HEAD` ha avanzado cinco commits,
  dos de los cuales añaden 16 tests nuevos y compilan dos módulos que antes no
  compilaban para test. Repetir el ensayo es lo que devuelve esta línea a
  `CERRADO`; hasta entonces **no** se declara conformidad. Se conserva el
  desglose del ensayo antiguo porque explica el margen, no porque autorice el
  actual: `detektTest` mide 3 s sobre 26 subproyectos, y el alza de +9 s
  (233 → 242) correspondió a 54 tests, 13 ficheros de test y `detektTest` en la
  etapa 1 — es decir, el gate crece mucho más despacio que el trabajo. Un gate
  verde por inercia es peor que un gate marcado caducado: el segundo se lee y el
  primero se acepta sin mirarlo.

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

---

## 4. Referencias

- `AGENTS.md` — política vigente; manda sobre cualquier documento histórico.
- `scripts/verify-all.sh` — la única puerta local.
- `scripts/check-budgets.py` — el único script de presupuestos.
- `v2/examples/run.sh` — el único lanzador E2E.
- Historia anterior: tag `pre-testing-reset-20261018`. No se resucita en el árbol
  activo.