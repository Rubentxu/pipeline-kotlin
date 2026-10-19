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
| SHA | `8a823ca556` medido, gate completo sobre este árbol | `git rev-parse HEAD` |
| Módulos declarados | 27 | bloque `include(` de `v2/settings.gradle.kts` (líneas 33-106) |
| Módulos con test unitario permanente | **18** | etapa 2 del gate, que imprime cada módulo por nombre |
| Módulos sin test | 9 | ídem |
| Tests unitarios ejecutados | **571** (62 informes en disco, 53 reescritos por la corrida, 0 fallos, 0 errores, 0 skips) | etapa 2 del gate, confirmado con un censo independiente de los XML |
| Escenarios E2E | 36 (**35 pasan, 0 fallan, 1 `NOT_RUN`**, 1 rerun durable) | `v2/examples/**/*.pipeline.kts` |
| `VERIFY_LOC / PRODUCT_LOC` | **7408 / 61484 = 0.120** (límite 2) | `scripts/check-budgets.py --json` |
| Ratio máximo por módulo | **1.633** (`pipeline-step-sdk:workflow-control`) | ídem |
| `VERIFY_ALL_WALL_CLOCK` | **289 s** en el ensayo de `8a823ca556`; la serie del ciclo es 276 / 242 / 289, límite 900 s | etapa 5 del gate, `§4` de `AGENTS.md` |
| Veredicto del gate | **`INCOMPLETE`**, `gaps=1 not_run` — las cinco etapas corrieron y pasaron, pero el escenario de credenciales no llegó a ejecutarse | línea final de `verify-all.sh` |
| Certificador externo | **`BLOCKED_EXTERNAL`** | no existe en este repositorio ni en remoto |

Los tres ensayos se ejecutaron desde salidas de build limpias y los tres
reescribieron los **56** informes JUnit, que es la condición que hace que la
cifra de reloj mida el gate y no una restauración de caché. Un total correcto
con el verbo equivocado ya no puede colarse: la etapa 2 separa `rewritten` de
`read` y **falla** si no reescribe ninguno.

**Los 18 informes son 18 proyectos Gradle, no 18 directorios de fuentes.** Un
censo por `src/test/kotlin` devuelve 14, y la diferencia son los cinco
subproyectos de `pipeline-step-sdk` (`files`, `http`, `runtime`, `scm-git`,
`workflow-control`) más el directorio padre, que no produce XML propio. El
denominador que cuenta el gate es el proyecto Gradle, que es la unidad que
Gradle ejecuta; el nombre del directorio no lo es.

**Limpiar «los `build/`» es más peligroso de lo que parece, y por eso el
criterio importa.** Una primera tanda de ensayos limpió con
`find v2 -type d -name build` y midió **48 de 54** informes reescritos: ese
`build/` de un solo nivel deja fuera once directorios de módulos anidados, como
`v2/pipeline-step-sdk/*/build`, y el árbol queda medio tibio. Al corregirlo a
`find` sin `-maxdepth` se descubrió el peor fallo posible: ese mismo criterio
**borra `v2/buildSrc/src/main/java/dev/rubentxu/pipeline/build`, que es un
paquete Java, no una salida de construcción**, y con él las dos fuentes de las
que dependen los scripts de los módulos de plugin. El gate falla entonces
compilando un *script*, con `Unresolved reference: rubentxu`, que se lee como un
problema de entorno. Restaurado desde Git y verificado: es el único directorio
`build/` del árbol cuyo padre no declara `build.gradle.kts`. El criterio que se
usa ahora, y el que debe usarse, es **borrar un `build/` solo si su padre
declara `build.gradle.kts` o `build.gradle`**.

El escenario `NOT_RUN` es `security/14-credentials-bindings.pipeline.kts`, que
declara `requires-seeded-credentials`: el lanzador lo marca `NOT_RUN` en vez de
fabricar credenciales.

**El bloqueo se investigó y se comprobaron las cuatro afirmaciones siguientes,
una a una contra el fichero antes de darlas por buenas:**

1. `credentials add` solo acepta `--kind` y un id posicional
   (`MainCredentialsCli.kt:115-128`). El secreto sale de
   `System.console()?.readPassword() ?: error("no TTY available")`
   (`CredentialPrompts.kt:27-30`) — cableado a TTY, y con un pipe `console()` es
   nulo, así que ni se intenta. Un barrido de `--stdin`, `--value`, `--from-file`
   y `--secret` en `v2/`, `scripts/` y `examples/` devuelve **cero**.
2. Ese TTY **no es una invariante de dominio**: vive entero en
   `internal object CredentialPrompts`, un ayudante de prompts separables.
3. **La API de abajo sí es pública y no tiene TTY**:
   `SecretStore.add(id: CredentialsId, credential: Credential)`
   (`SecretStore.kt:39`, implementada en `LocalSecretStore.kt:353`), clase pública
   con constructor público. Sin lectura de consola, sin comprobación de TTY, sin
   variables de entorno: va directo a la criptografía. No hay invariante que
   saltar; el TTY es la elección de fuente de entrada de **un** call site.
4. **El passphrase ya es headless**: `PIPELINE_STORE_PASSPHRASE` se mira primero
   y su KDoc dice literalmente «no TTY prompt, preferred for automation»
   (`PassphraseResolver.kt:20,41-45`).

O sea: el hueco es **de un solo lado**. El almacén ya se abre sin TTY; lo único
puenteado es *el material del secreto*, y la API soportada que lo resuelve está
una capa más abajo, alcanzable desde código pero **expuesta por ningún verbo**.

Y hay un dato que decide el caso: **0 de los 50 ficheros de test del árbol
instancian `LocalSecretStore`, y 0 llaman a `store.add` o `store.put`.** Los ocho
tests de `credentials-local` se quedan una capa más abajo
(`CredentialSerializer`, `AeadCipher`) y el test del ejecutor usa un fake en
memoria. El hueco que documenta el escenario es real y está bien medido.

**Lo que desbloquearía, y por qué NO se ha hecho en este ciclo:** añadir un
`--stdin` a `credentials add` que lea el secreto de la entrada estándar, y que
el lanzador siembre el almacén con ese verbo antes de correr el escenario. Son
unos veinte líneas y convierten el veredicto del gate. No se ha hecho por dos
motivos, y los dos importan:

- `--value` o cualquier bandera con el secreto **violaría la invariante P2 del
  propio repositorio** —el contenido nunca aparece en un `argv`— así que solo
  `--stdin` es admisible. Y `--stdin` es superficie de CLI nueva **sobre una
  frontera de seguridad**: hace posible escribir secretos sin humano, que es un
  cambio de capacidad y no un refactor.
- El encabezado del escenario dice que el lanzador «no tiene negocio» con un
  almacen sembrado, y el motivo que da es que un seeder sería «exactamente el
  harness que este árbol borró». Un `--stdin` alimentado por el propio escritor
  del producto no es ni un seeder de TTY ni un fichero cifrado hecho a mano, así
  que la prohibición **no** cubre este caso —pero la decisión de si lo cubre es
  de producto, y la lista de autorizaciones pendientes ya lo tiene marcado.

`NOT_RUN` no es un aprobado, y desde `5240feac` tampoco se
confunde con uno: la línea de veredicto lleva el hueco **dentro**. Antes el
conteo se imprimía y no se leía —la etapa 3 solo hacía `grep` del resumen—, así
que el peor caso (`--scenario 'security/*'`: 0 pasan, 0 fallan, 1 no ejecutado)
salía vestido de `PASS`. La primera forma fue un sufijo sobre la misma palabra,
`PASS  sha=…  budget=900s  gaps=1 not_run`, que se contradecía a sí misma y
cuya contradicción era justo lo que se acababa citando. Hoy la palabra del
veredicto sigue a la evidencia:

```
INCOMPLETE  sha=…  elapsed=220s  budget=900s  gaps=1 not_run
```

`INCOMPLETE` y no `PASS` porque `AGENTS.md` §4 dice que una prueba ausente o
omitida nunca es un aprobado. El código de salida sigue siendo 0: las etapas que
sí corrieron son evidencia real, y un escenario que el producto no puede lanzar
sin TTY es un hueco que hay que nombrar, no un defecto que deba poner el gate
entero en rojo. Un tercer estado no se propaga porque la capa de abajo lo
imprima: si el hueco necesita el log de la etapa para entenderse, no ha viajado.

La misma regla se aplicó a `--no-budget`: ponía `skip_budget=1` y nada más, así
que saltaba la etapa 4 y caía igualmente en la línea `PASS … budget=900s` del
gate completo — una línea que nombraba un presupuesto de LOC que esa corrida no
midió. Ahora también marca `subset=1` y sale como `PASS (subset)`. El veredicto
pertenece a la corrida que lo observó todo; quitar una etapa quita la
afirmación con ella.

### La rama de replay que nadie ejecutaba

`run.sh` admitía `//@ durable` desde su cabecera, pero **ninguno** de los 36
escenarios lo declaraba: la segunda ejecución sobre el mismo `--control-root`
nunca había corrido. `durable/22-wait-until.pipeline.kts` lo declara ahora, que
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
midió. Desde la medición, `HEAD` avanzó a `d47e10ad`, que solo toca este
fichero: `check-budgets.py` devuelve los mismos **6805 / 61618** antes y después
y ninguna tarea del gate lee `ROADMAP.md`, así que la traslación está medida y
no supuesta. En cuanto entre un commit que toque código de producto o de
verificación, esta tabla vuelve a estar caducada y hay que repetir los tres
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
| 1 | 0 Markdown bajo `docs/` y `v2/` | **PASS** | `git ls-files ':(glob)docs/**/*.md'` = 0; `:(glob)v2/**/*.md` = 0. En la raíz solo quedan `AGENTS.md`, `README*.md`, `ROADMAP.md` |
| 2 | 0 `@Test` UAT/E2E/AAT/harness | **PASS** | 0 en `pluginContractTest`/`installedContractTest`/`integrationTest`/`functionalTest`; 0 `@Disabled` |
| 3 | 0 clases legacy trasladadas | **PASS** | `v2/test-harness`, `v2/pipeline-testkit`, `v2/pipeline-architecture-tests` y `scripts/validation-gate.py` ausentes |
| 4 | E2E solo como `.pipeline.kts`, un lanzador | **PASS** | 36 escenarios en 8 categorías bajo `v2/examples/`; 0 `src/test` dentro de `examples/*`; lanzador único `v2/examples/run.sh` (186 líneas) invocado por `scripts/verify-all.sh:246` |
| 5 | `VERIFY_LOC <= 2 * PRODUCT_LOC`, global y por módulo | **PASS** | 6981 / 61644 = 0.113; ningún módulo declara `breach`; el más alto es `workflow-control` con 1.633 |
| 6 | `verify-all` local ≤ 900 s | **PASS** | peor de tres ensayos sobre `12eea7a89` + este bloque: 228 / 227 / **228 s**, y **241 s** en la corrida de cierre sobre `f4dd21a47`, contra el límite de 900. Todas con 56/56 informes reescritos, 531 tests, 0 fallos, 0 errores, 0 skips |
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
  `dev.rubentxu.pipeline.v2.protocol.Version`, que solo produce `protoc`. El
  Kotlin que `protoc` generaría **no está versionado** —`ProtocolCore.kt` sí lo
  está— y no existe `v2/pipeline-protocol/build/`, o sea que `protoc` nunca se
  ejecutó y el tipo del que depende nunca existió.
- **Cero consumidores** de `ProtocolModules`, `ProtocolGovernance` y
  `ProtocolVersion` fuera del propio módulo, y cero referencias a `v2.protocol`.
- **Diez ficheros versionados**: un `.kt` (44 líneas) y ocho `.proto` (513
  líneas). Son 557 líneas que el gate nunca compila ni cuenta.
- **No publica y no está en el gate de ABI**: su `build.gradle.kts` declara
  `group` pero no aplica `maven-publish`, y el módulo no tiene dump `.api`. Por
  eso retirarlo no rompe ni un artefacto publicado ni el gate de compatibilidad.
- **La cuarentena que lo mantenía fuera del árbol ya no existe.** El test
  `` `V2 settings excludes pipeline-protocol` `` vivía en
  `pipeline-architecture-tests/Lfc0V1QuarantineFitnessTest.kt`, y RESET borró ese
  módulo. Nada en el árbol vigente comprueba hoy que `settings.gradle.kts` siga
  sin declararlo.
- **Sus únicas referencias restantes están fuera del árbol.** Un barrido desde la
  raíz las encuentra todas bajo `.worktrees/pr134-review/`, **dentro** del
  directorio de trabajo pero fuera del árbol versionado: `.worktrees/` contiene
  diez worktrees registrados de este mismo repositorio (`git worktree list`), y
  git los excluye de `status` porque cada uno tiene una rama ya comprobada, pero
  eso **no** los excluye de un `grep`. Quien mida consumidores desde la raíz sin
  excluir ese directorio concluirá que el módulo tiene dependientes que ya no
  existen: a este documento le pasó al medirlo.

No se borra en este ciclo: no figura en la lista del mandato y borrar ficheros
versionados es del usuario. La decisión pendiente es declararlo (y arreglarlo)
o retirarlo; los cinco puntos anteriores son la evidencia para decidir, y la
última es la razón por la que no se le pone un test —una comprobación de que
`settings.gradle.kts` no contiene una cadena es *source-text fitness*, que
`§2` prohíbe, y `§2` prohíbe también resucitar `:pipeline-architecture-tests`.

### Los 18 módulos con test

`pipeline-application`, `pipeline-domain`, `pipeline-events-store`,
`pipeline-step-sdk/runtime`, `pipeline-step-sdk/http`,
`pipeline-step-sdk/workflow-control`, `pipeline-step-sdk/files`,
`pipeline-step-sdk/scm-git`, `pipeline-credentials-api`,
`pipeline-credentials-local`, `pipeline-credentials-multipart`,
`pipeline-credentials-executor`, `pipeline-binding-factory`,
`pipeline-artefacts-local`, `pipeline-runtime-api`, `pipeline-output`,
`pipeline-output-store`, `pipeline-release`.

> **Censo remedido 2026-10-19 desde el disco.** Sobre los 27 módulos que declara
> el `include(` de `settings.gradle.kts`: **18 con `src/test/kotlin/**/*Test.kt`,
> 9 sin él**. La lista de arriba omitía `pipeline-application`, que ya tiene
> `ProjectWorkingDirectoryTest.kt`, y por eso se contradecía con la fila de §1.
> Una segunda omisión, `pipeline-step-sdk/scm-git`, salió al reconciliar los 18
> informes XML del gate contra un censo por directorio de fuentes: el gate cuenta
> proyectos Gradle y el censo contaba carpetas, y un proyecto compuesto con
> subproyectos tiene más carpetas que proyectos.
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

- Un `git grep` con **pathspec de comodín** (`'v2/*/src/main/kotlin'`) devuelve
  **NADA**, no cero. Leído como «cero consumidores» produce exactamente la
  conclusión que uno quiere oír. Hubo que repetirlo sin pathspec.
- El paquete raíz `dev.rubentxu.pipeline.v2.runtime` **no identifica el módulo**:
  `:pipeline-runtime-api` —que sí está publicado, en las tres listas de
  `v2/build.gradle.kts:349,413,436` y en el BOM— comparte ese mismo raíz. Sólo un
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

**Resultado: 71 nombres, 91 citas, todas bajo `v2/`.** Los más repetidos:

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

**Cerrado en este ciclo — y la limpieza dejó un residuo peor que el original.**
El commit `5a8124b29` quitó las 25 líneas de código; hoy
`git grep -n 'design\.md' -- v2` devuelve **cero**. Pero al borrar el *nombre*
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

Patrón **anclado** (`ADR-` seguido de exactamente cuatro dígitos), y el census
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
2. **6 apariciones de `Pre-ADR-0075`** en 4 ficheros de `v2` —más una en este
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

**k) La identidad declarada de un plugin: la premisa de este bloque era falsa.**
Un agente afirmó «los bytes nunca se miden en producción» y, corrigiendo eso, una
redacción anterior de este bloque concludes que «nada compara los bytes medidos
contra el `release.digest` declarado por el manifiesto». **Ambas son falsas**, y lo
comprobé abriendo el call site antes de aceptarlas:

- `PluginAdmissionGate` mide los bytes reales con
  `PluginManifestResourceReader.measureArtifactBytes` (línea 162) **cuando hay
  trust database**; y en ese mismo caso llama a `b32TrustRefusal` (línea 176),
  que compara `measuredDigest` contra `declaredDigest` **antes que cualquier otra
  cosa** y devuelve `AdmissionRefusal.RefusedDigestMismatch` (línea 354). El
  veredicto se consume en la línea 187 mapeándolo a `TrustMetadata.Mismatch`. La
  garantía de integridad está implementada, conectada y es el primer control de
  la cadena.
- Sin trust database no se mide ni se compara, y eso **no es un hueco**: no hay
  ningún digest afirmado por el operador contra el que un plugin pueda mentir. La
  rama sin ancla cae en el `measure` deprecado, que devuelve
  `measuredDigest = null` por diseño.
- El TOCTOU también está cubierto por otra vía: `PreResolvedComposition` mide con
  `digestOfArtifactAt`, rechaza `ArtifactNotMeasurable`, deduplica por bytes
  medidos y vuelve a medir al final rechazando `ArtifactChangedDuringAdmission`.

**Lo que sí queda abierto, y es más pequeño que el bloque que sustituye:**

- `PreLoadPluginAdmission.kt:98` llama al `measure` deprecado y recibe
  `measuredDigest = null`: pide una medición y tira el resultado. Es el residuo
  real, no la ausencia de la comparación.
- `ArtifactIdentityVerdict` (Verified / Mismatch / Unverified) tiene **cero
  lectores de producción**. Es una segunda forma de expresar una garantía que ya
  se expresa con un `if` en línea. No es un fallo de seguridad: es exactamente el
  «second authority» que AGENTS.md §1 prohíbe. Retirarlo tocaría
  `pipeline-domain.api`, así que espera al mismo break versionado que (h) e (i).
- **Ningún test cubre `RefusedDigestMismatch`**: `git grep` sobre `*/src/test/*`
  no devuelve ni una línea. Una comprobación de integridad de artefactos que sí
  funciona carece de regresión permanente. Este es el único punto de este bloque
  que es un defecto material y **no** está bloqueado por ABI: es un test que
  falta, no una firma que quitar.

**Por qué importa la corrección:**Taken al pie de la letra, este bloque pedía
añadir un caso de discrepancia a `PluginManifestRejection`. Eso habría creado una
segunda autoridad sobre un control que ya funciona — el error caro — mientras
dejaba sin cubrir el control que sí importa.

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

**d) 13 de 36 escenarios E2E sólo comprueban el código de salida.** Era 24 al
abrirse este bloque; queda en 13.

Censo reproducible sobre los 36 `.pipeline.kts` del árbol. El lanzador sólo
conoce dos aserciones, `//@ expect-exit` y `//@ expect-stderr`, y las dos
comparan un valor de salida o una subcadena de stderr. Todo lo demás tiene que
afirmarlo el propio escenario, y no todos lo hacen:

| | Qué afirma | N |
|---|---|---|
| A | `//@ expect-stderr`: el lanzador exige **este** texto de negativa, no un código cualquiera | 4 |
| B | Una comprobación dentro del cuerpo (`test`, `grep -q`, `cat`, `find`, `wc -l`, `test "$X" =`) que falla si el efecto no ocurrió | 19 |
| C | **Nada** que compruebe el efecto; el lanzador compara el código de salida y ya está | **13** |

Los trece que pasaron de C a B en este ciclo: `steps/18-cleanWs`,
`smoke/02-environment`, `control-flow/11-workflow-control`, `steps/08-withEnv`,
`output/23-readfile`, `smoke/09-sh-then-echo`, `output/30-artifact-query-bridge`,
`output/32-publish-html`, `security/14-credentials-bindings`, `smoke/10-smoke-e2e`,
`steps/17-writeFile`, `control-flow/06-loop` y `plugins/repeat-block`.

De los trece, **ocho llevan la mutación que los pone en rojo, leída de vuelta
antes de leer el resultado** —una aserción no vale si no se ha visto fallar—:
`18-cleanWs`, `02-environment`, `08-withEnv` y `11-workflow-control` (este
último por inversión de la propia aserción), más `smoke/10`, `17-writeFile`,
`06-loop` y `plugins/repeat-block`. Los cinco restantes se validaron en verde
pero **no** se vieron fallar: `23-readfile`, `09-sh-then-echo`,
`30-artifact-query-bridge`, `32-publish-html` y `14-credentials-bindings`. Este
último además **nunca se ejecuta** —está en `NOT_RUN` por
`requires-seeded-credentials`—, así que su aserción no ha corrido nunca.
Declararlo probado sería falso. Y hasta el commit de este ciclo tampoco
**compilaba**: el lanzador salía por el `NOT_RUN` antes de invocar al binario, de
modo que las siete fábricas `CredentialsBinding` no tenían ninguna cobertura de
compilación. Ver §2.6.

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
observarlo**. `v2/examples/run.sh` lee el exit code y, como mucho, una subcadena
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

- `v2/pipeline-events/build.gradle.kts` declara `tasks.test { inputs.dir(…
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
**los tres** en `v2/pipeline-events/api/pipeline-events.api`, el dump que
`binary-compatibility-validator` genera (declarado en `v2/build.gradle.kts:355`)
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

### 2.4b `v2/pipeline-protocol/` — el build no lo ve, y el defecto es la desincronización

**Aviso de lectura: este bloque ya no es una novedad.** La tabla de la sección 1
(«Estado medido», fila 7) afirmaba desde antes que el módulo está versionado y no
declarado, y Gradle nunca lo compila. Lo que sigue **corrige** el bloque que este
roadmap escribió después como si lo hubiera descubierto, y **retira** el defecto
que le atribuía al instrumento del gate.

**Lo que sigue siendo cierto.** `v2/settings.gradle.kts` **no lo incluye** (sus
28 `include` no contienen `pipeline-protocol`) y no existe
`v2/pipeline-protocol/build/`, que Gradle habría creado al compilarlo. Verificado
por cuatro instrumentos independientes, porque el primero mintió: `grep -c` sobre
el `include` imprime el número *y* devuelve 0, así que una cadena `&&`/`||`
encadenada sobre él lee «ausente» justo después de imprimir «1».

**Lo que se retira: el defecto no era de `check-budgets.py`.** Este bloque afirmó
que sus globs de disco cuentan 511 líneas que el build nunca compila, y propuso
«derivar el denominador de los módulos que `settings.gradle.kts` declara». **Medido
y falso: ese filtro habría restado producto real.** Cinco directorios tienen
`src/main` sin figurar en el `include` raíz, y cuatro de ellos **son builds**:

| directorio | líneas | qué es |
|---|---:|---|
| `v2/buildSrc` | 302 | convention plugins que compilan el producto entero |
| `v2/examples/example-block-plugin` | 598 | build independiente, JAR en la distribución (`runtimeOnly`) |
| `v2/examples/example-uppercase-plugin` | 932 | ídem |
| `v2/examples/example-directive-plugin` | 410 | ídem |
| `v2/pipeline-protocol` | 511 | el único sin `include` **y** sin consumidor |

Los cuatro primeros suman **2.242** líneas de producto real. Un denominador
derivado del `include` habría hecho el gate **más verde por la razón
equivocada**, que es el peor modo de fallar un instrumento de presupuesto.

**El defecto real, y sí es de este módulo:** un `build.gradle.kts` de 42 líneas
escrito para un módulo que el `include` raíz no declara. El fichero de build y la
declaración están desincronizados; uno de los dos miente. Eso es lo que hay que
decidir, y la decisión sigue siendo de ciclo de vida: **borrar** (10 ficheros,
511 líneas, ratio 0.117 → **0.118** medido) **o incluirlo** (y entonces compila,
y sus 8 `.proto` entran en build). Ninguna de las dos es un autónomo.

**Instrumento del gate: sin cambio, y con motivo.** Los globs cuentan directorios
con fichero de build, que es exactamente la condición para que un directorio sea
construible. `pipeline-protocol` lo tiene; que su `include` falte es la
inconsistencia de ese módulo, no la del gate.

### 2.5 Las promesas colgantes que quedan — medido, no estimado

El patrón anterior no era de `pipeline-events`. Se extendía.

**Instrumento** (reproducible, sobre el árbol commiteado):

```
tracked=$(git ls-files '*src/test/*' | sed 's|.*/||; s|\.kt$||' | sort -u)
grep -rhoE '\b[A-Z][A-Za-z0-9]*(Test|Tests|FitnessTest|FalsifierTest|LawsTest)\b' \
     --include='*.kt' v2/*/src/main | sort -u   # menos los que $tracked contiene
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

El veredicto del gate local es **INCOMPLETE**, no `PASS`, y la diferencia es un
único escenario: `v2/examples/security/14-credentials-bindings.pipeline.kts`.
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
lectores de contraseña de `v2/pipeline-credentials-local/.../CredentialPrompts.kt`
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

- **`delivery-first-testing` tiene una affordance colgante, y el router la ofrece
  como si fuera ejecutable.** `references/navigation.md` punto 10 enruta la
  intención `audit` a `audit-portfolio`. En disco hay **8** playbooks en
  `references/lenses/` y **`audit-portfolio.md` no está entre ellos**. No es una
  lente activable: invocarla produce un enlace a nada. Las que sí están y sirven
  para este árbol son `change-impact`, `regression-promotion` y `failure-triage`.
  Se anota para que nadie la invoque esperando un procedimiento.

---

## 4. Referencias

- `AGENTS.md` — política vigente; manda sobre cualquier documento histórico.
- `scripts/verify-all.sh` — la única puerta local.
- `scripts/check-budgets.py` — el único script de presupuestos.
- `v2/examples/run.sh` — el único lanzador E2E.
- Historia anterior: tag `pre-testing-reset-20261018`. No se resucita en el árbol
  activo.