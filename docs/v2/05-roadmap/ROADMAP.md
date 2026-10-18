# ROADMAP V2 — Production Ready verificable y evolución coherente

**Estado:** AUTORIDAD ACTIVA DE SECUENCIACIÓN para V2 desde 2026-09-21.
**Baseline de código auditado:** main @ a554fd5544f74f580bbd531c9b394cff1e073621 (2026-09-21).
**Estado de esta entrega:** SOLO DOCUMENTACIÓN; NO se ha recompilado ni recertificado HEAD.
**Estado de producto publicado:** v0.39.0, GitHub Release de 2026-09-19; su certificación NO se transmite a commits posteriores.

**Reconciliación de hechos 2026-10-18 (medida, no inferida). Supersede los bloques del
2026-10-08, 2026-10-09 y 2026-10-10 como referencia de estado; se conservan literalmente por
trazabilidad.** El bloque `a554fd55` (2026-09-21) se conserva igual por el mismo motivo: un
baseline fechado es el resultado de una auditoría, y sustituirlo por HEAD no lo actualiza, lo
falsifica.

```text
origin/main observado      f3260074a1375c1e0a7e82c21691bd7f9685c884 (2026-10-18)
rama                       main; working tree = origin/main, 0 commits sin pushear
versión de producto        0.62.0   (v2/build.gradle.kts rootProject.version, línea 105)
publicación más reciente   v0.62.0-rc6, Pre-release, 2026-10-16T23:00:53Z
superficie de CI           inexistente (.github/workflows/ -> 0 ficheros; solo Dependabot)
checks requeridos en main  ninguno (la carga de protección de main no contiene
                           required_status_checks)
ciclos SDDK                NO OBSERVABLE — el binario no está instalado (ver abajo)
```

**Sobre la fila `ciclos SDDK`.** Los bloques anteriores la publicaban como hecho observado
(`42 OPEN, 4 BLOCKED, 82 CLOSED, 1 RELEASE_PENDING`). Medido el 2026-10-18, `command -v sddk`
no devuelve nada y no existe en `~/.local/bin`, `/usr/local/bin` ni `/opt`. Una cifra sin
instrumento no es una cifra: es una afirmación que nadie puede volver a comprobar, y una fila
de estado que no se puede observar no debe figurar como observada. La fila se retira del bloque
vigente; el texto de los bloques anteriores se conserva sin editar. La decisión y su
razonamiento están en `AGENTS.md` (nota SDDK, 2026-10-18) y en `../00-governance/ACTIVE_DOCUMENTS.md`.

**Lo que este bloque NO afirma.** No afirma el veredicto de certificación de `v0.62.0-rc6`: ese
veredicto es del harness y vive en su recibo, no en este repositorio. Desde aquí es `NOT_RUN`.
Tampoco afirma nada sobre el estado de la batería UAT externa, que es del harness por contrato
de separación de responsabilidades.

**Siguiente bloque desbloqueado (medido 2026-10-18): portador tipado de `maxBackoffMs` en
`core.waitUntil`.** Hoy ese campo **no tiene portador tipado en el producto**. `StepDispatchEngine`
lo recupera con una expresión regular sobre el JSON crudo del payload, y la autoridad estructural
—que ya parsea ese mismo payload en `CanonicalStructuralDecisions.kt:491-497`— lo descarta. Es la
segunda lectura de un valor que la primera ya posee: la forma que DR-1 prohíbe.

Por qué el campo existe hoy y por qué no se puede simplemente borrar. En producción hay **dos**
escritores del payload de `waitUntil`, y **ninguno** emite `maxBackoffMs`: el códec del Step
(`CoreWaitUntilStep.kt:141-152`) y la proyección del bloque
(`DslCompiledPipelineCompiler.kt:374-378`), que escribe su propio JSON en vez de delegar en el
códec. Los dos emiten `kind`, `initialRecurrencePeriod` y `quiet`. Y no hay un tercer escritor:
`CompiledPipeline.kt` sólo declara el tipo `VersionedStepPayload`; el único sitio de producción que
lo construye para un bloque es `DslCompiledPipelineCompiler.kt:326` (`blockPayload(step)`), de donde
salen esas ramas. Denominador completo: 2 escritores, 0 con la clave.

Pero un test puede escribir el payload directamente, saltándose a ambos — y lo hace:
`BodyExecutionCharacterizationTest.kt:263,296`.
Las filas de `waitUntil` necesitan un techo de backoff de **25 ms** para terminar al instante, y esa
es la **única** vía para obtener un techo corto. Un intento previo de borrarlo (2026-10-18, revertido
en el mismo ciclo) rompió esa fila con `TimeoutException` a los 120 s. Registro, no hallazgo nuevo.

**DR-10 cerrado por medición, no por suposición.** `BodyStructureDigest.kt:67` anexa
`child.payload.encoded` — la **cadena**. Leer una clave más de esa misma cadena no cambia un byte de
la forma canónica, así que añadir el campo **no mueve la identidad del cuerpo**. Esta era la
pregunta que bloqueaba el bloque; está respondida.

**Denominador del cambio (6 puntos de contacto, no 3):**

```text
pipeline-scripting-api   StageScope.kt:436      añadir el parámetro al builder DSL
surface/                 DSL_SURFACE_MANIFEST.md:98   la firma de la fila cambia
pipeline-application     CoreWaitUntilStep.kt   WaitUntilInput + códec (emisión)
pipeline-application     DslCompiledPipelineCompiler.kt:374-378  proyección del payload de bloque
pipeline-application     CanonicalStructuralDecisions.kt:491-497  lectura estructural tipada
pipeline-application     StepDispatchEngine.kt:698-700  SE BORRA el regex
```

**Por qué no se cierra en un ciclo:** `core.waitUntil` está `CERTIFIED`
(`07-uat/S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md`), y cambiar su contrato público
exige STEP-CERT sobre el SHA nuevo, no que compile. Además `FArchS0SurfaceManifestTest` recalcula
el censo desde el manifiesto, de modo que tocar la firma de la línea 98 invalida su entrada. La Ley
de Certificación no admite "compila" como evidencia.

### Corrección registrada 2026-10-18 — el punto de contacto 5 estaba mal, y la rebanada 1 ya está

Decisión registrada: **rebanada 1 = dar nombre al códec y delegar**, sin tocar la firma del DSL ni
el portador de `maxBackoffMs`. Commit `cf459bd5`. El texto anterior se conserva arriba como
historia, incluidas las dos frases que este bloque corrige.

**Falsado por medición: «la autoridad estructural ya parsea ese mismo payload» (línea 41).** No lo
hace. `CanonicalStructuralDecisions.kt:485-497` lee `policy.waitUntil` de la **política declarada en
el descriptor**, y `BodyExecutionPolicy.WaitUntilShape` dice literalmente que *la forma es una
propiedad estructural del KIND del Step, no dato de ejecución*. El payload del bloque no se abre en
ese camino. Consecuencia, que es la corrección de fondo:

```text
punto de contacto 5, tal como estaba escrito   leer el payload en el decoder estructural
punto de contacto 5, medido                     ESE LECTOR NO EXISTE
```

El payload del bloque tiene **exactamente un escritor y ningún lector de producción**. Los únicos
que tocan su región son el regex de `StepDispatchEngine.kt:698-700` —que busca una clave que nadie
escribe— y las aserciones de los tests. Añadir ahí la lectura de `maxBackoffMs` no es «la segunda
lectura de un valor que la primera ya posee» (línea 42, y esa frase también estaba mal): es **la
primera**.

**El `kind` divergente no era un defecto.** `kind="waitUntil"` (portador del registro,
`encodedInput`, decodificado por el códec del Step) y `kind="waitUntilBlock"` (portador del bloque,
`payload`) son **dos formatos de dos portadores distintos**, no dos escritores de un mismo formato. Y
`kind="waitUntilBlock"` **no era un literal huérfano**:
`Lfc2WaitUntilDslCanonicalProjectionTest.kt:145` lo afirma cruzando `DslCompiledPipelineCompiler.compile`.
Por eso el códec extraído lo emite byte a byte, `DR-10` no dispara y no hace falta revisión de
compatibilidad.

**Censo de escritores, después de la rebanada 1** — leído rama por rama, no por clasificador:

| rama (`DslCompiledPipelineCompiler.kt:332-396`) | veredicto |
| --- | --- |
| `Dir` (333) · `Timestamps` (337) · `WithEnv` (340) · `TimeoutBlock` (355) · `RetryBlock` (367) | escriben el cable a mano |
| `WithCredentialsBlock` (344) → `CredentialBindingsPayload.encode` | delegan |
| `WaitUntilBlock` (376) → `CoreWaitUntilWireCodec.encode` | delegan (esta rebanada) |
| `Lock` (382) → `CoreLockWireCodec.encode` | delegan |
| `Input` (386) → `CoreInputWireCodec.encode` | delegan |

```text
9 Steps en blockPayload:  4 delegan en una autoridad nombrada   5 escriben a mano
antes de cf459bd5:        3 delegaban                           6 escribían a mano
```

Denominador: las 9 ramas nombradas de `blockPayload`; la décima cabecera es el `else -> "{}"`, y
`TimeoutBlock` contiene un `when` anidado cuyo `else` no es una rama de `blockPayload` (contarlo
como tal fue el error de dos instrumentos seguidos).

**Corrección de un censo ya publicado.** El bloque de `31e4b5df` afirmaba «9 ramas, 2 delegan
(`Input`, `Lock`)». Era falso por uno: `WithCredentialsBlock` delegaba desde antes, en
`CredentialBindingsPayload.encode`. El instrumento buscaba el sufijo `*WireCodec` y una autoridad
sin ese sufijo le era invisible. Es la cuarta vez esta semana que un instrumento con forma de nombre
no ve el sujeto que debía contar (la tercera fue el detector de códecs anónimos, la segunda un
clasificador de ramas, la primera un grupo de rutas escrito a mano). Regla, ya escrita: **si el
instrumento no puede ver una parte del denominador, el resultado es `NOT_RUN`, no un número**.

El censo **por formato** sigue `NOT_RUN`: ningún instrumento de este ciclo ha distinguido todavía un
`StepCodec` anónimo de la ausencia de uno.

**Hueco medido que la rebanada deja escrito:** `BodyStructureDigest` **no tiene clase de test
propia**. Sus únicas menciones bajo `src/test` son los dos ficheros añadidos en `cf459bd5`. Los
bytes congelados están garantizados por igualdad de cadena en el test de contrato; **el digest en sí
no está probado**. Es trabajo de otra rebanada, no un apéndice de esta.

**Lo que queda, y su orden real.** El punto 6 (borrar el regex) **no puede ir antes que el punto 3**:
borrarlo sin darle antes un lector tipado al campo deja el techo de backoff sin ninguna vía en
producción, que es exactamente el defecto que la reversión del 2026-10-18 ya pagó una vez. Orden
medido, no estimado:

```text
3  portador tipado de maxBackoffMs + lectura en el decoder estructural (crea el lector que falta)
6  SE BORRA el regex, sólo después, con STEP-CERT sobre el SHA nuevo
1  StageScope.kt:436              añadir el parámetro al builder DSL
2  DSL_SURFACE_MANIFEST.md:98      la firma de la fila cambia, y su censo se recalcula
```

Y por debajo de todo eso sigue la deuda horizontal: 6 ramas más escriben su cable a mano. La rebanada
1 no las arregla; las deja contadas.

**Lo que NO es aceptable como atajo:** enseñar a leer la clave al decodificador estructural sin
exponerla en la emisión. Eso consagra un campo que sólo los tests escriben, y la exposición
tipada lo reescribiría acto seguido sobre los mismos ficheros. Es trabajo doble.

### Corrección registrada 2026-10-18 — `maxBackoffMs` es un caso de `DEBT-WIRE-AUTHORITY`, no un hueco aislado

El texto anterior queda **en pie en su conclusión** (en producción la clave no aparece, y por tanto
no se puede simplemente borrar el override) pero **corrige su alcance** en dos puntos medidos después de
escribirlo. Se conservan las dos lecturas anteriores sin borrar, porque el error es la lección.

**Corrección 1 — no son «dos escritores del mismo formato».** Son dos *formas* con dos `kind`
distintos. El códec del Step emite `kind="waitUntil"` (`CoreWaitUntilStep.kt:148`); la proyección
del bloque emite `kind="waitUntilBlock"` (`DslCompiledPipelineCompiler.kt:375`), y **ningún lector
de producción compara contra `"waitUntilBlock"`**: ese literal aparece en un único fichero, el propio
compilador. El decodificador estructural no discrimina por `kind` en absoluto — lee
`shape.initialRecurrencePeriodMs` de un `WaitUntilShape` ya tipado. La convención del repositorio es
la contraria: cada códec hace `require(obj["kind"] == "<su kind>")` (`CoreDeleteDirStep.kt:116`,
`CoreReadFileStep.kt:92`, `CoreArchiveArtifactsStep.kt:85`, …). Es decir: el payload del bloque es
consumido por la vía estructural, y siOutlineara el códec fallaría cerrado. La conclusión de
producción sigue valiendo —ninguno de los dos `kind` emite `maxBackoffMs`— pero el mecanismo que
importa es la proyección del compilador, no un códec compartido.

**Corrección 2 — el bloque pertenece a una deuda ya registrada, con dueño y prioridad.**
`Lfc2LockWireAuthorityFitnessTest` declara la ley en su propio KDoc (líneas 21-25): *«`core.sh` is
the counter-example frozen by WU-091: its compiler producer and its codec self-encoding drifted
(`kind=sh|shell`, `command|script`, `returnStdout|returnMode`), and every alias is a compatibility
shim added after the divergence»*, y la divergencia es la forma exacta que sufre `waitUntil` hoy. Su
líneas 27-30 acotan el alcance y **nombran la deuda**: `DEBT-WIRE-AUTHORITY`, backlog
`bl-bl-01M3YGH3FE000387X13PG607G0`, P2, Triaged, registrada en
`docs/v2/07-uat/WU091_LOCK_RELEASE_RECEIPT.md:57` y `WU092_INPUT_RELEASE_RECEIPT.md:57`, y
deliberadamente NO plegada en este tren.

Lo medido sobre `blockPayload` (`DslCompiledPipelineCompiler.kt:332-396`): **9 ramas, 9 Steps, y
sólo 2 delegan** en un códec (`Input`, `Lock`); las otras 7 escriben su cable a mano — `Dir`,
`Timestamps`, `WithEnv`, `TimeoutBlock`, `RetryBlock`, `WaitUntilBlock`, `WithCredentialsBlock`. El
deuda registrada nombra 4 (`Dir`, `WithEnv`, `TimeoutBlock`, `RetryBlock`); **`Timestamps`,
`WaitUntilBlock` y `WithCredentialsBlock` nunca fueron nombrados**.

**Lo que NO se afirma, y por qué.** El censo *por formato* de «qué ramas tienen además un códec
propio» queda `NOT_RUN`. El instrumento que construí —buscar un `*Codec` cuyo nombre contenga el
nombre del Step— devolvió «no hay códec» para `WaitUntilBlock`, y es un **falso negativo**: su
códec existe y es anónimo (`CoreWaitUntilStep.kt:142`, un `object : StepCodec<WaitUntilInput>` sin
nombre). Un instrumento que no ve códecs anónimos no puede contarlos, y una cifra producida por él no
es citable. Éste es el tercer instrumento de este ciclo que hubo que descartar — junto con el regex de
clasificación de ramas, que marcó 8 de 9 como `DELEGA`— y por eso el censo se escribe `NOT_RUN` en
lugar de estimarse.

**Consecuencia para el bloque siguiente.** Parchear `maxBackoffMs` como si fuera un hueco aislado
convierte 1 caso de una deuda horizontal de 7 casos nominales en un caso especial permanente — que es
la forma que la misma nota de alcance del fitness advierte no de plegar. El orden correcto es tratar
`waitUntil` dentro de `DEBT-WIRE-AUTHORITY` (extraer su códec nombrado y dejar de hand-escribir el
cable del bloque), y el portador tipado de `maxBackoffMs` cae como consecuencia, no como objetivo.
Eso no cambia el diagnóstico del override —sigue siendo producción-muerto y sólo los tests lo
escriben— sino el **tamaño** del arreglo: uno horizontal, no uno local.

### Corrección registrada 2026-10-18 — `check` llevaba meses rojo, y por eso STEP-CERT no era exigible

Al cerrar `DEBT-WIRE-AUTHORITY` se lanzó el round gate y terminó en **BUILD FAILED in 13s**,
con 15 tareas `detekt` FAILED y **0 tareas de test ejecutadas**. La notificación de la tarea
anunciaba «exit code 0»: es falso, y ya había ocurrido una vez más en este mismo ciclo. Un
código de salida no es la verdad de un build; la verdad son las tareas.

**Atribución, por identidad de Git y no por sustracción.** De los 94 ficheros que `detekt`
señalaba, sólo 4 tienen blob distinto entre la base `63dbc3df` y HEAD — los cuatro que este
bloque introduce. Los otros 90 son byte-idénticos, luego sus hallazgos no pueden proceder de
este cambio. Los 4 propios eran `NewLineAtEndOfFile`: los ficheros nuevos terminaban sin salto
de línea final. Corregidos en `7ee80a98`; el Delta de SAST de este bloque pasa a ser 0.

**La causa del resto, medida.** `check` engancha `detekt` (`v2/build.gradle.kts:373`), y
`detekt` analiza `src/main/kotlin` **y** `src/test/kotlin` (líneas 349-355) con un baseline
**por módulo** que sólo se aplica si el fichero existe (358-362). Ese baseline se congeló en
`4b59bbc8` (2026-09-23, instantánea `6f7c445f`) y desde entonces **937 commits han tocado
`v2/`**. Los identificadores de detekt llevan la firma —`Regla:Fichero:Entidad$firma`—, de
modo que cualquier cambio de firma de una función señalada resucita su hallazgo, y los
ficheros creados después de la instantánea nunca se congelaron. En `pipeline-application` hay
110 entradas `MaxLineLength` congeladas y aun así 33 `MaxLineLength` vivos.

**Medición de la deuda heredada** (idéntica en la base y en HEAD, así que no es atribuible a
ningún cambio reciente): **104 hallazgos**, 15 tareas `detekt` FAILED. 25 en `src/main`, 79 en
`src/test`. Por regla: 40 `MaxLineLength`, 37 `NewLineAtEndOfFile`, 10 `UnusedParameter`,
6 `VariableNaming`, 4 `CyclomaticComplexMethod`, 2 `TooManyFunctions`, 2 `LongMethod`,
1 `UnusedPrivateClass`, 1 `PackageNaming`, 1 `InvalidPackageDeclaration`. Cinco módulos
—`pipeline-output`, `pipeline-output-store`, `pipeline-runtime-api`, `pipeline-runtime`,
`test-harness`— no tenían baseline, que es lo que explica por qué nunca quedaron cubiertos.

**Reparación y su prueba.** `detektBaseline` regenerado: 13 baselines modificados, 5 creados.
Un gate que vuelve a verde sin prueba de que discrimina es el mismo defecto con otro color,
así que la prueba es explícita: se introdujo una violación nueva (una línea de 201 caracteres)
en un módulo con baseline viejo (`pipeline-application`) y en otro con baseline nuevo
(`pipeline-runtime`); `detekt` FAILED y nombró el hallazgo en los dos; revertida; `detekt`
verde con 0 hallazgos y con los baselines sin modificarse al ejecutarlo. La puerta vuelve a
cumplir su contrato declarado —«congelar lo preexistente, fallar ante deuda nueva»— que es
justo lo que llevaba meses sin cumplir.

**Lo que esto NO es.** La deuda no desaparece: pasa a ser contable, y su conteo es el 104 de
arriba con ese reparto por regla. Re-baselinear sin publicar la cifra habría convertido una
puerta rota en una puerta verde y opaca; publicarla es lo que mantiene el compromiso. Quemar
104 hallazgos —79 de ellos cosméticos en tests— sigue siendo trabajo de reducción, no
justificación para dejar la puerta rota, y no forma parte de este bloque.

**Consecuencia operativa, y es la que importa.** Durante semanas, `./v2/gradlew -p v2 check`
ha sido ROJO con independencia del cambio, luego no podía certificar nada: un STEP-CERT
«verde» en ese periodo sólo podía significar que nadie lo ejecutó o que se leyó otra cosa. A
partir de ahora sí es exigible, y un rojo futuro sí será atribuible.

### Consecuencia medida de retirar `SDDK`: los estados de backlog de los recibos dejaron de ser verificables

Al reescribir el párrafo anterior salió, casi por accidente, lo que la retirada de `SDDK` dejó
detrás. No es una observación sobre `maxBackoffMs`; es sobre la autoridad que **esta misma sección**
deja de lado.

Medido:

```text
ids bl-bl-* distintos citados por recibos vivos en docs/v2/07-uat/   29
de esos, presentes en un único índice versionado                        12
índice: docs/v2/07-uat/BACKLOG_AUDIT_2026_10_09.md
  origen declarado del ledger:  tabla `backlog_items_v1`, proyecto `p-733fb505b5a6bd2d`
  es una foto:  base main@57774c92 (2026-10-09), 68 ítems, 46 ya `discarded`
  contiene `bl-bl-01M3YGH3FE000387X13PG607G0` (DEBT-WIRE-AUTHORITY)   NO
```

Es decir: cuando un recibo afirma «`bl-bl-…`, **P2, Triaged**», el estado no es observable dentro
de este repositorio. Viene de una tabla de una base de datos de una herramienta que **no está
instalada** (véase la nota SDDK de `AGENTS.md`, 2026-10-18). El único índice en Git es una foto de
hace nueve días que cubre 12 de 29, y **no incluye precisamente el id de `DEBT-WIRE-AUTHORITY`**, que
por eso es el primero queTfaltó descubrir al seguir el rastro del override.

Nada de esto invalida los recibos —un recibo es evidencia de su SHA y no se reescribe— pero sí
significa que **la prioridad y el estado de cada ítem citado deben leerse como la afirmación del
recibo, no como un hecho comprobable**. Es exactamente el peldaño que la cadena de autoridad de
`ACTIVE_DOCUMENTS.md` ya retiró: un peldaño que no se puede observar no puede gobernar, y esta es la
medida de lo que esa retirada costa en trazabilidad.

**Consecuencia operativa, acotada:** el backlog con estado vigente vive hoy en los recibos y en el
ROADMAP, no en un registro. `DEBT-WIRE-AUTHORITY` es la prueba del hueco: P2 y Triaged según el
recibo, ausente del único índice versionado, y con tres ofensores nunca nombrados (arriba). Cuando
se aborde, la primera tarea no es código: es dar a ese ítem una fila en una fuente de verdad del
repositorio, o llamarlo `NOT_VERIFIABLE` en el propio ROADMAP.

**Actualización de hechos 2026-10-08 (observada, no inferida). Supersede las tres líneas anteriores como referencia de estado; se conservan por trazabilidad.**

```text
origin/main observado      b66bf7c796db28dabf3e13df9784844e7a59aeda
rama de trabajo            s6-plugin-sdk @ acd12111 (33 commits por delante de main, 0 por detrás)
producto publicado         v0.47.0 (GitHub Release Latest, 2026-10-06); zip sha256:2fa2d272…d3301c
superficie de CI           inexistente (.github/workflows/ no existe; solo Dependabot activo)
checks requeridos en main  ninguno (required_status_checks 404, rulesets [])
ciclos SDDK                42 OPEN, 4 BLOCKED, 82 CLOSED, 1 RELEASE_PENDING
```

**Re-auditoría de hechos 2026-10-09 (observada, no inferida). Supersede el bloque del
2026-10-08 como referencia de estado; aquel se conserva por trazabilidad. El baseline auditado
de `a554fd55` (2026-09-21) **se conserva intacto**: un baseline fechado es el resultado de una
auditoría, y sustituirlo por el HEAD actual no lo actualiza, lo falsifica.

```text
origin/main observado      98f163c04d7cbcb2976236eaa1c4272bc74e1fda
producto publicado         v0.48.0-rc1 (Pre-release, 2026-10-09T13:52:57Z);
                           tag v0.48.0-rc1 -> 6e8e86bd; zip pipelinek-0.48.0.zip
                           92 119 743 bytes; sha256:a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93
candidata v0.48.0-rc2       CANDIDATE_PUBLISHED (2026-10-10, local; pendiente push);
                           tag v0.48.0-rc2 -> 74c5331e; zip pipelinek-0.48.0-rc2.zip
                           ~88.2 MB; sha256:0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7
                           gate v2 verde 5611 tests / 0 failures / 144 skipped
                           (recibos: ../07-uat/WIP11_GATE_FINAL_STATUS.md y
                            evidence/v0.48.0-rc2/release-receipt.md)
Latest (stable)            v0.47.0, GitHub Release de 2026-10-06
superficie de CI           inexistente (.github/workflows/ -> 404; solo Dependabot activo)
checks en HEAD             0 check runs sobre 98f163c0
checks requeridos en main  ninguno (required_status_checks ausente, rulesets 0)
ciclos SDDK                42 OPEN, 4 BLOCKED, 82 CLOSED, 1 RELEASE_PENDING
```

**Hechos del 2026-10-10 (v0.48.0-rc2, observado).** F-1/F-2/F-3 del gate anterior (sobre
`2f5ba9aa`) cerrados en commits `07ecd02` (F-2/F-3: ledgers S6-PRE y S6-PRE 2) y `b3e60c9`
(F-1: stream-id shape drift pre/post OBS-C2.3 en B1aSh > b; el test consultaba un stream
`.../transcript` que OBS-C2.3 eliminó, ahora consulta `.../stdout`). WIP-11 receipt enmendado
en `3f59c57` (causa real de F-1) y declarado verde en `98e25f3` (gate v2 BUILD SUCCESSFUL
in 31m 3s, 5611 tests, 0 failures, 144 skipped). B1 feature doc actualizado a CANDIDATE_PUBLISHED
en `0d2ee1a`. Version bump a `0.48.0-rc2` en `74c5331`. Tag anotado `v0.48.0-rc2` peel=
`74c5331e` (regla 9 de AGENTS.md). Release-receipt en
`evidence/v0.48.0-rc2/release-receipt.md`. Deuda residual priorizada: **OUT-01/OUT-02 HIGH**
(ambos `@Disabled` por requerir política de migración de formato del Output Plane;
`SegmentOutputStoreTest.kt` los define con la condición de habilitación documentada).

Cambios respecto del bloque del 2026-10-08, todos medidos:

```text
CERRADO   B0-F1 (P0): la PR #99 pasó a MERGED (2026-10-09T13:21:31Z) y 3ec99a4c es
          ancestro de origin/main conservando su SHA propio. Merge directo, sin squash.
          La release estable ya es reconstruible desde el trunk (ADR-0099).
AVANZADO  B0-F4 (P1): la capacidad de publicar el check de admisión EXISTE y está probada
          (harness/check_run.py, 53 tests verdes), pero en 74 commits sin pushear sobre un
          origin/main parado desde 2026-09-29. Detalle en B0_3_HARNESS_INVESTIGATION_RECEIPT.md.
EMPEORADO B0-F5 (P1): este drift es el que corrige el bloque de arriba.
SIN CAMBIO B0-F2 (P0): sigue sin check requerido ni superficie que lo produzca. G10 = NOT_RUN.
BRECHA    No existe certificación del harness para v0.48.0-rc1 (su evidence/ llega hasta
          v0.47.0). La promoción estable sigue bloqueada por ello, correctamente.
```

Hallazgos de esa reconciliación, con evidencia y dueño, en `../07-uat/B0_RECONCILIATION_RECEIPT.md`.
Los dos P0 de 2026-10-08 ya no lo son ambos: B0-F1 quedó resuelto, y B0-F2 sigue abierto con
remedio externo compartido con B0-F4.
**Autoridad operativa (TRAIN-0 cutover 2026-09-26; corregida 2026-10-18):** Git + ADRs/contratos publicados + evidencia externa. La mención a SDDK que este bloque hacía hasta 2026-10-18 se retiró porque la herramienta no está instalada y una autoridad que no se puede observar no puede gobernar (ver el bloque de hechos de 2026-10-18). `.agent/SESSION_POINTER.md` queda como proyección humana opcional / histórico (no autoridad). **Pruebas vinculantes:** ../07-uat/CERTIFICATION_PROTOCOL.md y ../07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Continuación auditada (2026-10-16):** [QUALITY_VALUE_ADDENDUM.md](QUALITY_VALUE_ADDENDUM.md) §«Continuación operativa auditada» conserva BQ1–BQ5 y agrega el plan operativo medido sobre `main@a66fe88c`, incluyendo PR #142, P2 Maven y la limpieza de UAT/E2E/AAT de sesión al final de cada bloque. **No altera el orden RP ni la autoridad del certifier**.

**Hechos del 2026-10-18 (observado, no inferido). Supersede el bloque del 2026-10-10
como referencia de estado; aquel se conserva por trazabilidad. El baseline auditado de
`a554fd55` se sigue conservando intacto, por el mismo motivo que en el bloque anterior.**

```text
origin/main observado      080ef7d7d839e0d359a3efb253167549a4d419b4
rama de trabajo            main, en sync con origin/main (sin trabajo sin commitear
                           salvo dos rutas sin seguimiento, pendientes de decisión)
producto publicado         v0.62.0-rc6 (tag v0.62.0-rc6); ProductVersion 0.62.0;
                           release-train 0.62.0 / candidateSequence 6;
                           candidate-id sha256:dd4508022a6d2e8313dc2c2982c138f3b7ea07ad6eecdd78d83e1bb3ab1b8111
                           (manifiesto y handoff en docs/v2/06-evidence/0-62-rc6/)
version declarada          v2/build.gradle.kts -> version = "0.62.0"
superficie de CI           inexistente (.github/workflows no existe)
ciclos SDDK                NOT_RUN — la herramienta no está instalada
```

**Corrección material sobre la línea `ciclos SDDK` de los tres bloques anteriores.** Los
bloques del 2026-10-08, 2026-10-09 y 2026-10-10 repiten la cifra
`42 OPEN, 4 BLOCKED, 82 CLOSED, 1 RELEASE_PENDING`. Medido hoy 2026-10-18:

```text
command -v sddk                              vacío
~/.local/bin/sddk                            no existe (tampoco en /usr/local/bin ni en /opt)
residuo en disco que SÍ existe               ~/.local/bin/sddk-tmp-purge
                                             ~/.local/bin/__pycache__/sddk-dashboard.cpython-314.pyc
                                             ~/.local/bin/skills/sddk-{archive,propose,cycle-resume}/SKILL.md
```

El residuo prueba que la herramienta existió, no que exista: los `SKILL.md` de `sddk-*`
consultan «authoritative CLI queries», es decir el CLI ausente, así que tampoco funcionan.
La cifra la produjo una herramienta que aquí no se puede ejecutar: **no es reproducible en
esta máquina** y no puede sostener una decisión de secuenciación. Por la regla ya escrita
en `AGENTS.md` —«un peldaño de autoridad que no se puede observar no puede gobernar», y
toda afirmación que dependa de un estado no observable se escribe `NOT_RUN`— se sustituye
por `NOT_RUN`.

Los tres bloques anteriores **no se borran**: su cifra era la lectura de quien las escribió
y la trazabilidad exige conservarla. Lo que no debe ocurrir es volver a citarla como estado
actual, porque parece medible y no lo es. Si SDDK vuelve a instalarse, la cifra se
reintroduce aquí por decisión registrada, no por inercia.

**Nota de método.** Esta actualización NO declara el estado de los bloques B0–B6, ni de los
ciclos individuales, ni reevalúa gates: no se ha ejecutado ningún gate sobre este SHA en esta
sesión, y el producto publicado se lee de su manifiesto, no de una reconstrucción. La
afirmación es sobre los hechos observables de arriba y nada más.

**Subroadmap derivado:** la consola progresiva y el streaming durable se evolucionan en [OBS_PROGRESSIVE_CONSOLE_ROADMAP.md](OBS_PROGRESSIVE_CONSOLE_ROADMAP.md) (OBS-1 … OBS-7). Ese documento **no** es una autoridad de producto paralela: la prioridad, la integración y el calendario siguen siendo los de este roadmap, y ningún bloque OBS se admite antes de cumplir su gate ni bloquea la publicación del SDK.

## 0. Autoridad, límites y significado de DONE

1. Respetar ADRs aceptados, especificaciones normativas, contratos públicos y garantías ya publicadas. Este plan unifica PRIORIDADES; no revoca decisiones técnicas aceptadas ni reescribe recibos.
2. El roadmap antiguo y la propuesta LPR de 2026-09-18 se preservan en docs/historico/; LOCAL_FOUNDATION_CONSOLIDATION, LFC2_STEP_ECOSYSTEM_EXPANSION y los ADRs vigentes son referencias técnicas, NO colas paralelas que puedan saltarse este orden.
3. LPR-001/auto-run puede gestionar WUs sucesivas, pero NO permite saltar gates, ocultar bloqueos, reetiquetar fallos ni eludir las excepciones de autorización de INITIATIVE_LPR_001 §2.4. Cambios de semántica pública, protocolo remoto, publicación inmutable o receipts históricos exigen autorización según esa iniciativa.
4. Fuentes de verdad separadas: (a) Git/registry/contratos/código, (b) CI e informes ejecutados en el SHA exacto, (c) recibos históricos, (d) estado operativo derivado. Nunca obtener el porcentaje global sumando tests, commits y WUs heterogéneas.
5. Todo hito produce una capacidad vertical verificable, exit criteria, UAT de distribución instalada, registro de fallos y decisión GO/STOP. No existe certificado implícito por disponer de código, PR cerrado, tag, test omitido o un recibo antiguo.
6. Prioridad obligatoria: corregir la línea de verificación y los defectos de integridad/seguridad antes de iniciar expansión funcional. No añadir un nuevo Step core mientras RP-0/RP-1 no cierren con pruebas actuales. No refactorizar en masa ni modificar decisiones aceptadas para hacer pasar tests.
7. Plataforma objetivo inicial: uso local seguro en un entorno de CI de confianza. Ejecutar pipelines ajenos en aislamiento fuerte requiere un gate separado de OS/container, no equivale al sandbox best-effort ni a un ClassLoader.

## 1. Línea temporal y precedencias

| Período | Estado factual a preservar | Consecuencia para la planificación |
|---|---|---|
| Hasta 2026-09-18 | M3/ML, EM y EVT tienen sus recibos, pero algunas líneas anteriores están supersedidas como secuencia. | Usar su código, ADRs y UAT como fundamento; NO reiniciar milestones históricos. |
| 2026-09-18 | ADR-0082 prioriza LPR; el plan anterior LFC-2E se difiere como expansión global. | LPR gobierna la línea local de producto; compatibilidad y contratos anteriores siguen vinculantes. |
| 2026-09-19 | v0.39.0 publicada y documentada sobre su commit certificado; canal SDKMAN pendiente según examen posterior. | Separar garantía del artefacto publicado de main y de canales pendientes. |
| 2026-09-20 a 21 | Iniciativa LPR-001, Step burn-down, stash/unstash y publishHTML incorporados a main; WU-090 Phase D en a554fd55. | Conservar los recibos; NO asumir que el estado de main pasa el gate global. |
| 2026-09-21: auditoría | CI de a554fd55 falla antes de compilar por ruta incorrecta de wrapper. Hay hallazgos reproducibles por inspección sobre informes, enlaces y evidencia contractual. | Abrir primero RP-0 y RP-1 y generar NUEVA evidencia de HEAD. |

**Orden obligatorio:** RP-0 → RP-1 → RP-2 → RP-3 → RP-4 → RP-5 (Gate local) → RP-6 (ecosistema) → RP-7 (local-first ampliado) → RP-8 (controller/worker remoto) → RP-9 (Jenkins y capacidades distribuidas). Los spikes sin cambios públicos pueden adelantarse; su implementación NO puede adelantar el gate del que depende.

**Iniciativa externa en evaluación, sin autoridad propia.** `docs/pipelinek_cd_harness_package/`
(sin trackear) propone una infraestructura de pruebas transversal con cuatro `ADR-CDH-*` en estado
`PROPOSED`. No abre una secuencia paralela ni adelanta este orden. Sus límites actuales están
registrados en `docs/v2/07-uat/WU_CDH_H0_RECONCILE_01_BOUNDARY_RECEIPT.md`: qué coincide con un
documento vigente y qué sigue sin ratificar. En particular, el techo de 900 s es **medición
pendiente**, no un gate aplicado, y la certificación de candidatas sigue siendo del harness externo.

## 2. RP-0 — Restaurar CI y verdad del repositorio (P0; primera WU)

**Objetivo:** un checkout limpio del SHA auditado ejecuta realmente todas las validaciones requeridas.
**WU-RP-000:** corregir .github/workflows/lpr0-ci.yml y v2-baseline.yml: el Gradle wrapper está en v2/gradlew, no en la raíz. Usar, por ejemplo, trabajo en directorio v2 y ./gradlew; reparar uploads/upload-artifact@v4; eliminar o sustituir build.yml vacío. Asegurar un job explícito para corpus completo en gate de release, separado del carril rápido si su coste lo requiere.
**WU-RP-001:** mapear checks obligatorios y protección de main; recoger resultados reales (no skipped) de compile, domain, events, architecture, application, compatibility, CLI instalada, release; comprobar GitHub Actions del SHA. Si faltan permisos para configurar protección, registrarlo como BLOQUEADO_EXTERNO sin afirmar protección activa.
**WU-RP-002:** regenerar inventario desde CoreStepRegistryFactory, plugins, suites y receipts. Resolver 16 frente a 20 Steps en registros, duplicidad de estados, WU-090 Phase D ya incluida y Tier B 094 TBD. El archivo generado incluye SHA/código fuente, fecha, comando de generación, alcance y distinción REGISTERED / CERTIFIED_AT_SHA / EXPERIMENTAL / REJECTED.
**Salida RP-0:** CI real verde y no omitido para el SHA candidato; workflow de release tiene validación de artefacto y corpus; estado operativo sin falsos pendientes; evidencia en receipt RP-0. Si main requiere una corrección para estar verde, se cierra sobre el nuevo commit, no mediante evidencia de a554fd55.

## 3. RP-1 — Integridad, seguridad y verdad de certificación (P0; sin nuevos Steps)

**WU-RP-010:** publishHTML: no sobrescribir index.html aportado por el usuario; el índice generado no debe colisionar; hash del contenido FINAL archivado, integridad de manifest y replay verificada. Respetar API/semántica publicada: si una corrección alterase un contrato público certificado, documentar el cambio y activar la autorización correspondiente.
**WU-RP-011:** escapar nombres de archivo de modo contextual en HTML/href; confinar reportDir por ruta real y no seguir symlinks; pruebas de traversal, symlinks intermedios y directos, Unicode y archivos maliciosos.
**WU-RP-012:** stash/unstash: no seguir symlinks fuera del workspace, validar árbol destino/entrada y cierres bajo error/interrupción. Revisar exposición de secretos y permisos. Comparar resultado frente a los recibos WU-089/090 sin reescribirlos.
**WU-RP-013:** reconciliar StepContractSuite de publishHTML con sus afirmaciones G7. Añadir pruebas de handler positivo/negativo, missing capability, replay/restart, contenido de eventos y distribución instalada. Regenerar XML/certificación vinculados al SHA. Revisar familias vecinas si comparten el mismo bug de serialización.
**Salida RP-1:** los escenarios UAT-SEC/ART incluidos en la matriz son verdes, sin escapes de ámbito ni pérdida de contenido; una certificación revisada demuestra cada dimensión obligatoria. No basta que el canario devuelva exit 0.

**Estado RP-1 al 2026-09-22 (HEAD `f4aa20dc`):**
- WU-RP-101 CLOSED (e95b3d41) — test determinism.
- WU-RP-010 r1 CLOSED (4b93a1eb) — 4 tests E2E (UAT-RP-005 inv 1, 2, 4).
- **WU-RP-010 r2 DEFERIDA → KNOWN_LIMITATION** (ADR-0095). UAT-RP-005 inv 3 (MANIFEST.json archivado) se reevaluará en WU-RP-042 antes de release.
- WU-RP-013 CLOSED (57a26d19) — StepContractSuite 11→23.
- WU-RP-011 CLOSED (ae6b334e + d3e9b9b6) — UAT-RP-006 + UAT-RP-007.
- WU-RP-012 CLOSED (b3f74e93) — UAT-RP-008 + UAT-RP-009.
- RP-1 cerrado con 1 KNOWN_LIMITATION documentada. **RP-2 arranca con WU-RP-020** (caracterización SqliteEventStore, test-side puro).

## 4. RP-2 — Baseline de arquitectura, determinismo y observación

**WU-RP-020:** caracterizar SqliteEventStore bajo concurrencia: sequence asignada frente a orden de inserción/lectura, flush/close con productores activos, reinicio, gap, error de writer, replay y arrays anidados. No modificar el contrato de secuencia hasta reproducir o descartar el riesgo; usar test determinista y criterios observables.
**WU-RP-021:** catalogar rutas de ejecución soportadas (declarative/scripted, in-memory/durable, restart, branch, cancellation), sin StepKey privilegiado. Pruebas golden de IR, eventos, journal, fingerprint, outcome y recovery. Registrar excepciones/legacy reales antes de eliminarlas.
**WU-RP-022:** medir baseline reproducible de startup, compilación/cache, salida de 200 MiB y soak de ≥1 GiB, consumidor lento y CPU/RSS; definir SLO y presupuesto numérico DESPUÉS de medir y aprobar baseline. Separar eventos semánticos, transcript, valores y journal; comprobar redacción de secretos en todos los modos.
**Salida RP-2:** UAT-OBS/PERF/REC verde sobre el mismo SHA y entorno documentado; métricas comparables y decisión explícita respecto a límites. Si una métrica no se ha medido, estado UNKNOWN y gate abierto.

## 5. RP-3 — Fortalecer arquitectura, DSL y ejecución durable

**WU-RP-030:** establecer fitness de dependencias hexagonales, cohesión, ownership de I/O, no globals/Any? en fronteras, no when(stepKey), contrato de capacidades, compatibilidad y connascence entre modelo-evento/codecs/sequence. Caracterizar consumidores de APIs antes del cambio.
**WU-RP-031:** separar por pequeñas extracciones StructuralPreparation → DurableResolution → TypedInputDecode → StepExecutor; coordinator solo lifecycle/run-stage, sin reescritura masiva. Cada extracción pasa golden journal/event/replay y UAT kill/resume antes de retirar su predecesor.
**WU-RP-032:** cerrar semánticas del DSL soportado: declaración vs ejecución, valores runtime tipados, block Steps/cancelación, contextos, failures, validación compile-negative/positive, plugin externo sin tocar motor. Rechazar explícitamente superficie no soportada; no publicar un fallback ficticio.
**Salida RP-3:** un Step externo con y sin cuerpo accede al mismo registro genérico (si forma parte del SDK soportado), con admission/replay/typed errors comprobados y cero nueva ramificación concreta en el coordinator. Los recorridos soportados tienen paridad demostrada.

### WU-RP-034 — Workspace & Execution Location Semantic Remediation (post-exit corrective WU)

**Trigger:** el dogfooding posterior a RP-3 demostró que `pipelinek run` sin
`--workspace` ejecuta proyectos locales en un scratch temporal donde no existen
los paths del checkout. La caracterización WU-RP-053R también mostró
coexistencia de WORKSPACE_ROOT/CURRENT_DIRECTORY y consumidores con anchors
distintos.

**Objetivo:** establecer una única semántica tipada para workspace root, current
working directory y control root; hacer local-first el default del CLI sin
perder el modo scratch Jenkins-like.

**Decisiones (ADR-0100/0101/0102, ACCEPTED 2026-10-01):**
- `WorkspaceLease` distingue Attached(user-owned) de Managed(PipelineK-owned).
- `ExecutionLocation = workspace + cwd`; `cwd` es no-null y comienza en root.
- `dir(...)` deriva sólo cwd; no redefine workspace root.
- `controlRoot` nunca resuelve rutas del usuario.
- no-flag `pipelinek run` => attach invocation directory.
- `--workspace <path>` => attach explícito (compatible).
- `--isolated` => scratch gestionado (comportamiento histórico explícito).
- `--workspace` y `--isolated` son mutuamente excluyentes.
- filesystem Steps resuelven mediante PathAnchor + ExecutionLocation.
- workspaces Attached protegen su root contra `deleteDir/cleanWs` por defecto.
- Ownership es estado tipado; ninguna heurística VCS es autoridad.

**Secuencia obligatoria:**
A characterization-only → B ADTs puros → C runtime capability bridge →
D core cwd Steps → E consumers externos/utilities → F root/differential Steps →
G destructive safety → H CLI default flip → I cleanup/certificación.
**G precede obligatoriamente a H.**

**Exit:** distribución instalada demuestra Gradle/Maven/Node + self-hosting sin
`--workspace`, `--workspace .` conserva compatibilidad, `--isolated` conserva
scratch, nested `dir` es coherente entre sh/pwd/files/stash/plugins, attached root
no puede destruirse por default, fresh/replay/concurrency/architecture/full gate
verdes en el mismo SHA.

**Gate:** esta WU reabre únicamente la semántica de workspace necesaria para
certificación futura; no invalida RP3_EXIT_REVIEW en su SHA. No certificar una
nueva candidata local-first hasta cerrar WU-RP-034.

**Estado (2026-10-01):** secuencia **A→I ejecutada**. Caracterización congelada en
`WorkspaceExecutionLocationCharacterizationTest` (4/4). Un intento previo de
activar el default local-first (`8e838e6d`) fue **revertido** (`aeae1e4c`)
porque dejaba el checkout del usuario alcanzable con la protección destructiva
dependiendo aún de la heurística VCS. Ver
[`P0_SHELL_WORKING_DIRECTORY_RECEIPT.md`](../07-uat/P0_SHELL_WORKING_DIRECTORY_RECEIPT.md).

**RP034-I (certificación) — hallazgo de seguridad y cierre del slice.**
Con el default local-first ya activo, `deleteDir()` **borró el proyecto del
llamante**. La causa era una pérdida de hecho en tránsito, no un fallo de tipo:
`WorkspaceIntent` exponía sólo la base (`Path?`), incapaz de expresar ownership,
así que el bridge re-derivaba `WorkspaceLease.Managed` para **toda** ejecución.
El guard ADR-0102 estaba vivo en el tipo y muerto en el cableado — el gate de
RP034-G no estaba satisfecho en producción aunque su matriz y sus unit tests
pasaran. Corregido en `9d2e999a` con un `RuntimeWorkspaceTransport` de ownership
no-nulable, y verificado contra la distribución instalada en los tres modos
(adjunta → rechazo con exit 1; aislada → wipe permitido; subruta → exit 0). La
corrección del registro histórico está en
[`RP034_G_OWNERSHIP_DESTRUCTIVE_SAFETY.md`](../07-uat/RP034_G_OWNERSHIP_DESTRUCTIVE_SAFETY.md).

**Exit criteria pendientes:** el gate de "fresh/replay/concurrency/architecture/
full gate verdes en el MISMO SHA" requiere un `./gradlew check` completo y verde
sobre el SHA candidato. Hasta ese punto RP-034 **no** se declara cerrado y no se
libera candidata local-first.

## 6. RP-4 — Calidad transversal y distribución reproducible

**WU-RP-040:** configurar cobertura por módulo y umbrales fundamentados por riesgo (branch/line) sobre partes críticas, mutación selectiva de codecs/políticas; registrar exclusiones y @Disabled clasificados, nunca contar tests omitidos como PASS. Crear informes SAST/dependency audit/secret scan/SBOM y fijación de acciones por SHA según política de suministro.
**WU-RP-041:** probar aislamiento del runner local, filesystem, proceso, límites de CPU/memoria/tiempo, egress y secretos; declarar claramente modelo de amenaza y diferenciar ejecución confiable de multi-tenant. Cualquier capacidad que aún sea best-effort queda fuera del perfil de ejecución no confiable.
**WU-RP-042:** construir distZip reproducible a partir de commit inmutable, manifiesto y hashes; instalar de cero; ejecutar Gradle/Maven/Node reales, fallos de compilación, rollback/restart, uso de credenciales, CLI validate/run/inspect y corpus. Publicar sólo el ZIP EXACTO probado. Congelar API/schema para el candidato; certificación caduca si cambian los bytes.
**WU-RP-043 (self-hosted CI / dogfooding progresivo):** convertir el CI en
tres niveles. N1 bootstrap independiente: checkout + JDK + build pipelinek +
canary de arranque, válido aunque el DSL/registro/ejecutor estén rotos. N2
dogfooding: el pipelinek del mismo SHA ejecuta un `.pipeline.kts` del repos
itorio con las pruebas de integración/acotadas (per-shard), dejando los
`--tests` actuales como fallback. N3 verificación externa: comprobar resultado
e informes desde fuera del motor. Criterios de salida: (a) pipelinek construido
desde checkout limpio; (b) un `.pipeline.kts` real del mismo SHA ejecutado;
(c) salida/artefactos verificados fuera del motor; (d) fallo intencional deja
CI en rojo; (e) un error de DSL no impide diagnósticos del bootstrap. GitHub
Actions queda como lanzador y publicador de checks; pipelinek define y ejecuta
la lógica de CI. El motor de selección por impacto (ADR-0094) será la fuente
común de la política de testing para local, Actions y pipelinek.
**Salida RP-4:** todos los checks de release vinculados a commit, artefacto, SO/JDK y logs. No declarar cobertura/seguridad/rendimiento verificados sin resultados fechados.

## 7. RP-5 — Gate Local Production Ready de main / candidata

Todos simultáneos en la MISMA candidata: RP-0..4 cerrados; checks obligatorios verdes; UAT obligatorias verdes; cero bugs críticos/altos abiertos dentro del perfil; cero tests obligatorios @Disabled contados; instalación limpia + reproducibilidad + hashes; seguridad y rendimiento conforme a presupuestos ratificados; compatibilidad verificada; manual rápido y runbook; uso repetido en al menos dos repositorios de naturaleza distinta, incluida una actualización y un fallo intencionado recuperable. El estado histórico v0.39.0 se mantiene como PUBLICADA_CERTIFICADA_EN_SU_SHA y no se sobreescribe.
**SDKMAN:** canal separado; sólo marcar SDKMAN_READY tras publicación real, instalación limpia, UAT y promoción verificadas. Un bloqueo externo no autoriza a afirmar que el canal está verde; una release ZIP local puede tener un estado distinto.
**GO:** release certificada por SHA/artefacto. **STOP:** preservar evidencias y reabrir la WU causante; no disminuir la batería para forzar verde.

## 8. RP-6 — Ecosistema LFC-2E por demanda, con un patrón de Steps

Tras RP-5, reconciliar Tier A y B existentes sin reimplementar capacidades certificadas. Cola provisional heredada: WU-091 core.lock → WU-092 core.input → WU-093 core.httpRequest → WU-094 por concretar mediante inventario y decisión de producto; Tier C (readTOML/writeTOML, tar/untar) sólo si necesidad real y clasificación vigente lo requiere. Mantener distinción entre 20 REGISTERED y CERTIFIED por SHA; junit.results es plugin externo, no un nuevo core universal. Cada Step: contrato tipado, registro sin bypass, capability + policy, semántica/replay/cancelación, compilación positiva/negativa, fitness, UAT instalada, Jenkins-diff donde aplique y recibo verificable. No sumar features a core por comodidad: vendor/toolchains/contenedores van a plugins independientes y versionados.

**Estado de RP-6: CLOSED en RP6-CLOSEOUT (2026-10-03).** La cola se ejecutó completa y sus tres
elementos llegaron a `CERTIFIED_AT_SHA`:

| WU | Step | Forma de entrega | Recibo |
|---|---|---|---|
| RP6-A / WU-091 | `core.lock` | core BlockStep, backend de fichero POSIX | `WU091_LOCK_RELEASE_RECEIPT.md` |
| RP6-B / WU-092 | `core.input` | core Step | `WU092_INPUT_RELEASE_RECEIPT.md` |
| RP6-C / WU-093 | `http.request` | **OFFICIAL_PLUGIN**, NO core Step | `WU093_HTTP_IMPLEMENTATION_RECEIPT.md` |

**WU-093 se deliveró como plugin, no como `core.httpRequest`.** La cola de arriba nombra la forma
superada y se conserva como registro de lo que se decidió; la vigente está en
`docs/v2/07-uat/WU093_HTTP_DELIVERY_RECONCILIATION.md` y en
`docs/v2/03-specifications/STEP_ECOSYSTEM_POLICY.md` (`pipeline-plugin-http`: typed
`httpRequest`). La clasificación no fue una preferencia de implementación: la política de
ecosistema ya situaba HTTP como concern de protocolo/vendor antes de que RP6-C empezara.

**WU-094 NO se abre.** Fue una *propuesta* (`markdown-toolkit-plugin`), no un requisito, y RP-6 no
convierte un TBD en criterio de salida. Pasa a un train posterior si aparece demanda real. Lo
mismo para Tier C: `NOT STARTED by decision`, no por falta de tiempo. Inventario regenerado:
`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`.

**PRODUCT-GATE sigue `BLOCKED_EXTERNAL`** mientras no exista superficie de CI (ver la política de
verificación en `AGENTS.md`). El STEP-CERT de los tres Steps no lo vuelve verde.

**WU-094 (proposed): plugin externo `markdown-toolkit-plugin` — multi-step library para procesar Markdown en pipelines.**
- **Motivación**: pipelines de libros (book-builder skill) y de docs-as-code necesitan renderizar Markdown a HTML, generar TOC y validar headings dentro del pipeline. Hoy esto se hace con `sh("markdownlint ...")` / `sh("md-to-pdf ...")` invocando binarios externos, lo que mezcla el transcript de consola con la salida tipada y depende de tooling presente en la imagen CI. Un plugin externo dedicado permite tipar la salida (`{ htmlPath, byteCount, sha256 }`), separar el canal tipado del transcript (mismo principio que `core.sh`), y declarar `ReplayPolicy.NEVER` cuando el render escribe a disco.
- **Forma**: nueva carpeta `examples/markdown-toolkit-plugin` siguiendo **exactamente** el patrón de `examples/example-uppercase-plugin` (mismo `build.gradle.kts`, mismo `StepDefinitionContributor` SPI, mismo `StepDefinitionContributor`/`StepCodec`/`StepContract` flujo, mismo burn-down G0..G8 hasta CERTIFIED). NO se toca `pipeline-application`, NO se añade StepKey en `CoreStepRegistryFactory`, NO se modifica coordinator.
- **Pasos incluidos (3 steps, single concern = markdown processing)**:
  1. `markdown.render(input: String, outputPath: String, flavor: RenderFlavor)` → typed `MarkdownRenderResult(htmlPath: String, byteCount: Long, sha256: String, headings: List<Heading>)`. Flavor enum: COMMONMARK | GFM. Usa `org.commonmark:commonmark` (BSD-2-Clause) como adapter inicial; puerta port para swap a flexmark si surge necesidad.
  2. `markdown.headings(input: String, minLevel: Int = 1, maxLevel: Int = 6)` → typed `List<Heading>(level: Int, text: String, line: Int)`. Parseo puro, sin I/O.
  3. `markdown.toc(input: String, minLevel: Int = 1, maxLevel: Int = 6, bullets: BulletStyle)` → typed `MarkdownTocResult(toc: String, headings: List<Heading>)`. BulletStyle enum: DASH | ASTERISK.
- **Modelo de datos (ADT, sellado)** — el siguiente bloque es Kotlin, no parte de la lista numerada anterior:

    ```kotlin
    sealed interface MarkdownNode {
        data class Heading(val level: Int, val text: String, val line: Int) : MarkdownNode
        data class Paragraph(val text: String, val line: Int) : MarkdownNode
        data class CodeBlock(val language: String?, val content: String) : MarkdownNode
        // ...extensible sin tocar el handler
    }
    ```
- **Capability**: declarar `MARKDOWN_OPERATIONS_CAPABILITY` en cada `StepContract.requiredCapabilities`. El adapter (no el handler) tiene acceso al port; el handler no toca fs ni red directamente.
- **Replay policies**: `NEVER` en `markdown.render` (cada invocación debe escribir; sin reutilización — análogo a `core.echo`); `ALWAYS` en `markdown.headings` y `markdown.toc` (puro, sin side-effects, idempotente).
- **Output channels**: typed `O` via `outputCodec.encode`; durable HTML se escribe a disco por el handler con un único `O_TRUNC` open; el transcript opcional emite un preview corto (primeras 5 líneas del HTML) por canal independiente — mismo principio de `core.sh` (typed ≠ console).
- **Neutral naming**: nada de "Jenkins markdown", "GitHub markdown", etc. en runtime. Adapter específico sí puede mencionarlo (p.ej. `GitHubFlavoredMarkdownAdapter`), pero los StepKeys son `markdown.render`, `markdown.headings`, `markdown.toc`.
- **DSL facade**: `StageScope.markdownRender(text, path)` / `.markdownHeadings(text)` / `.markdownToc(text)`. Cada una baja solo a `registryStep(...)`, igual que `uppercase(text)`.
- **Reference research (AGENTS.md §reference-implementation-research)**:
  - commonmark-java (`org.commonmark:commonmark:0.21.0`, BSD-2-Clause) — parser/renderer canónico, mismo formato que GitHub. Adoptado como adapter inicial.
  - flexmark-java (`com.vladsch.flexmark:flexmark-all:0.64.0`, BSD-2-Clause) — alternativo con tablas, footnotes, strikethrough. Considerado para Fase 2 si surge necesidad real.
  - markdownlint-cli (`npm`, MIT) — referencia para validar reglas de estilo; NO se adopta (sería acoplarse a npm); se documenta la diferencia para el usuario en el DSL doc.
- **Tests (StepContractSuite adaptado al plugin pattern)**:
  - identity, contract completeness, input codec, output codec, canonical envelope, registry resolution, capability admission, fresh / replay / divergence, typed rejection, missing capability, observability, real DSL, real external JAR, instalado-distribución execution, absence/isolation, zero-production-change.
  - UAT instalada con al menos 1 caso por step: `markdown.render` con un Markdown pequeño → HTML generado y validado por sha256 contra baseline; `markdown.headings` con un doc de 3 niveles → typed list correcta; `markdown.toc` con el mismo doc → string TOC contiene los 3 headings en orden.
- **NO_GO mientras RP-0/RP-1 abiertos**: esta WU solo se planifica y entra al backlog; la implementación arranca únicamente tras WU-RP-005 cerrar RP-0 y tras decisión explícita de abrir RP-6.
- **Estado**: PLANNED (NO STARTED). Plan-budget: 1 WU = bloque de 4-6 commits (plugin skeleton + 3 steps + tests + DSL + integración + receipt).

## 9. RP-7 — Local-first ampliado, sin dependencia prematura del control-plane

Plugins de reportes/testing/artifacts/toolchains/SCM/HTTP y coordinación local priorizados por dogfooding. Sandbox opcional OS/container y límites verificables, secreto/egress, almacenamiento local robusto, migraciones de schema, cobertura de plataforma Linux/macOS/Windows declarada por separado. Antes de extender el SDK: compatibilidad semántica/binary, identidad/digest/provenance/versión del plugin, cargas externas en instalación limpia y certificación idéntica a core. Estudiar Cedar/policy con un spike y ADR; no activar enforcement opaco sin pruebas de deny/allow/versioning.

### 9.1 Secuencia de RP-7 (integrada 2026-10-03, `RP7_INTEGRATION_DISPOSITION.md`)

Antes de §9 era Roadmap con una cola y dos paquetes completos esperando fuera de
él. Los tres son ahora una secuencia, y sólo esta sección es la autoridad.

```text
RP7-SEM-0  constitution patch: 07-AGENTS-patch.md → AGENTS.md      ← PRIMER PASO
  ↓
RP7-SEM    Convergencia semántica        docs/pipelinek-semantic-evolution/
  ├─ S3  agent / environment / options   02-directive-model.md
  ├─ S4  Scripted Runtime v2             03-scripted-runtime.md
  ├─ S5  Reactive Event Spine v2         04-events-reactivity.md
  ├─ S6  Plugin SDK v2                   05-step-plugin-sdk-v2.md
  ├─ S7  Certification Harness v2        06-certification-harness-v2.md
  └─ S8  Migración / compatibilidad      08-roadmap.md, 11-release-cut.md
  ↓
RP7-ASX    Agent-first / secretless      docs/proposals/pipelinek-agent-secretless/
  ASX-0 baseline · ASX-1 capability kernel · ASX-2 scopes · ASX-3 providers
  ASX-4 tool projections · ASX-5 inline CLI · ASX-6 MCP/skills · ASX-7 certificación
  ↓
RP7-LOCAL  sandbox, resource limits, policy/Cedar, storage, provenance  ← SIN PAQUETE
```

**S0–S2 de `pipelinek-semantic-evolution` están YA SATISFECHOS o PARCIALES y no se
reejecutan** — la tabla de reconciliación está en
`docs/v2/07-uat/RP7_INTEGRATION_DISPOSITION.md` §1. Reejecutarlos destruiría trabajo ya
certificado.

`ASX` va después de `SEM` y no en paralelo: **ASX-0 congela `core.sh`,
`withCredentials`, fingerprints, replay, events y payload antes de tocar secretos**, y
ese freeze no significa nada sobre una superficie semántica todavía inestable.

`http.request` es el reference plugin de S6 y el primer caso real de S7: ya probó el
seam sin rama de compilador y ya recoge la disciplina de certificación que S7 debe
industrializar.

**`RP7-LOCAL` no tiene paquete canónico.** La descripción de §9 es su única autoridad
y no basta para implementar. Abrirlo es decisión abierta, registrada como tal.
`RP8` (§10) y `RP9` (§11) conservan su autoridad actual y no se amplían aquí.

## 10. RP-8 — Control plane, ejecución remota y protocolo

### 10.1 Disposición vigente (BLOCK 2, 2026-10-04): el control plane NO es de `pipeline-kotlin`

**RP-8 deja de reclamar el control plane, los workers, el transporte remoto y el
protocolo distribuido.** Esas responsabilidades están delegadas en `pipelinek-fabric`, y
mantenerlas aquí sería una segunda autoridad sobre las mismas leyes, no una posición
de trabajo.

Lo que `pipeline-kotlin` conserva de RP-8 es exactamente lo que ya existe y ya tiene
dueño dentro del core: el spine durable de una ejecución, la identidad de run y step, el
journal, el replay, el recovery y el Output Plane. Eso no es "parte del control plane";
es el runtime que el control plane ejecuta. La frontera no es el features, es quién
posee la autoridad.

**Por qué el reparto y no una separación por capas.** Un control plane vive de
observar y dirigir: decide qué run arranca, qué run se cancela, cómo se presenta un
stage y qué hace un operador cuando algo se cuelga. Eso requiere autoridad sobre
ciclos de vida, sobre observadores y sobre identidades de run que son de Fabric. Si
`pipeline-kotlin` lo reclamara, habría dos autoridades decidiendo si un run está
terminal, y la ley de "una condición, una representación" deja de cumplirse por
separación de paquetes en lugar de por diseño.

**Lo que este repo debe entregar para que Fabric pueda cumplir RP-8**, y es la
secuencia que BLOCK 2, BLOCK 5 y BLOCK 6 ejecutan:

1. Un contrato publicado que un consumidor pueda resolver sin dependencia de fuente
   (BLOCK 2: `pipeline-domain`, `pipeline-events`, `pipeline-output`,
   `pipeline-scripting-api`).
2. Un spine de eventos con semántica, causation/correlation y contrato de
   replay/lectura estables (BLOCK 5 / S5).
3. Un SDK de plugins que aporte Step, Directive, Event y capability sin exigir
   cambios en el core (BLOCK 6 / S6).

**Lo que este repo NO debe construir**, aunque el texto histórico de §10.2 lo
pidiera leerse como suyo: handshake/protobuf versionado, leases/fencing de workers,
backpressure de transporte, mTLS, multi-worker, orquestación de contenedores,
provisionamiento de infraestructura, y cualquier backend de transporte distribuido.
Si alguno de esos hace falta dentro de `pipeline-kotlin`, es un defecto de diseño y
no una tarea pendiente.

### 10.2 Texto histórico (conservado, sin autoridad operativa)

Se conserva literal por trazabilidad. Fue la disposición vigente hasta el cierre de
BLOCK 2 y describía un control plane dentro de `pipeline-kotlin` que ya no se va a
construir aquí.

> **Dependencias:** RP-5 producto local estable, RP-7 contratos/persistencia/identidad,
> ADR de threat model y versionado. Recuperar M4 E5-02..10 como INPUT histórico, NO
> como código listo para integrar sin revalidar. Vertical: worker aislado →
> handshake/protobuf versionado → leases/fencing → ACK/replay/event ordering →
> reconexión → cancelación → multi-worker → resiliencia. Requerir mTLS/autorización,
> compatibilidad N/N-1, backpressure, límites, pruebas kill/network
> partition/duplicate y observabilidad. Seleccionar backend de transporte por spike,
> no por preferencia heredada. Remote storage/protocol/API incompatibles requieren
> autorización explícita.

## 11. RP-9 — Adaptadores Jenkins/Kubernetes y plataforma distribuida

### 11.1 Disposición vigente (BLOCK 2, 2026-10-04): Jenkins y Kubernetes son de `pipelinek-fabric`

**RP-9 deja de reclamar el adaptador Jenkins, los workers Kubernetes/OpenShift y la
plataforma distribuida.** Viven en `pipelinek-fabric`, junto con el control plane de
§10.1, y comparten con él la misma frontera de autoridad.

`pipeline-kotlin` no tendrá código de Jenkins, ni step/plugin de Jenkins, ni
operaciones de clúster. No es una omisión pendiente: es la posición correcta, porque un
núcleo de ejecución que conoce a su orquestador deja de poder ejecutar donde ese
orquestador no está, y la executabilidad local es una propiedad que RP-9 histórico ya
exigía y que la disposición vigente conserva ("mantener núcleo Kotlin local
independiente de Jenkins, Kubernetes y almacenamiento remoto").

**El contrato que RP-9 necesita de este repo** es de lectura y de eventos, no de
integración: `RunOutcome` para el estado del build, `PipelineEventEnvelope` con
causation/correlation para la proyección de FlowNodes y stages, y `OutputReadPort` con
su cursor para la consola. Eso es exactamente lo que BLOCK 2 publica y lo que
`examples/fabric-contract-consumer` ejercita desde fuera. Las leyes de RP-9 que sí
pertenecen a Fabric y se implementarán en BLOCK 3 y BLOCK 4: identidad de run,
causalidad, reconexión, replay sin efectos duplicados, autorización de credenciales,
at-least-once con identidad de reacción idempotente, y la distinción entre UAT de un
adaptador y certificación de todo el control plane.

**Consecuencia de gobernanza:** ninguna WU de `pipeline-kotlin` abre trabajo de
Jenkins, Kubernetes o control plane. Si aparece una, se clasifica como trabajo de
`pipelinek-fabric` o se rechaza.

### 11.2 Texto histórico (conservado, sin autoridad operativa)

> **Dependencias:** RP-8. Adaptador Jenkins como consumidor de eventos LIVE y
> proyección de FlowNodes/stages; identidad de run, causalidad, reconexión, replay
> sin efectos duplicados, autorización de credenciales. Kubernetes/OpenShift workers
> aislados y provisionamiento reproducible; pruebas en clusters reales y
> compatibilidad de versiones. Mantener núcleo Kotlin local independiente de Jenkins,
> Kubernetes y almacenamiento remoto. No confundir la UAT de un adaptador con
> certificación de todo el control plane.

## 12. Mecánica de ejecución y actualización

- Cada WU sigue: caracterización RED real o baseline → especificación/ADR cuando proceda → parche mínimo → pruebas T0 contrato, T1 módulo, T2 fitness/corpus, T3 instalada, T4 durable/fallos, T5 release/soak cuando el impacto lo exige → recibo y commit.
- La unidad de entrega es el **TRAIN** (un ciclo con sus WUs internas). **Cómo se recupera el estado operativo (corregido 2026-10-18):** no existe una herramienta de ciclo en este repositorio, así que el estado se lee de **Git + ADRs + este roadmap** — `git status --short`, `git rev-parse HEAD`, `git log -1`, rama, y el `status:`/`accepted` de cada ADR implicada. Hasta el 2026-10-18 este apartado mandaba ejecutar `sddk status --cycle <active-cycle>`; el binario no está instalado (`command -v sddk` vacío), de modo que esa instrucción no se podía cumplir y se retira en lugar de conservarse con una salvedad. Si una decisión depende de un estado que no se puede observar, se escribe `NOT_RUN`. Si la herramienta vuelve a instalarse, se reintroduce aquí por decisión registrada.
- `.agent/SESSION_POINTER.md`, `.agent/WORK_JOURNAL.md`, `.agent/TESTING-STATE.md` y `.agent/TECH_DEBT_BACKLOG.md` son **proyección humana opcional / histórico** y NO autoridad. Si contradicen Git/ADRs/evidencia externa, la resolución es a favor del estado real y se marca el fichero como obsoleto para esa sesión.
- Cada cierre registra fecha UTC, base SHA, HEAD SHA, TRAIN/WU, decisiones, paths, test argv/exit/XML, hashes de artefactos, errores abiertos, evidencia caducada, próximo primer comando y motivo. La validación histórica no se reescribe.
- Los porcentajes se publican sólo para cohortes cerradas con denominador verificable (p. ej. WUs 2/6); si una WU está TBD, el avance global es NO_CALCULABLE.
- La secuencia puede evolucionar por descubrimiento respaldado por un ADR/recibo, preservando trazabilidad y gates. Ni un TODO ni un comentario de código prevalecen sobre una prueba ejecutada.

## 13. Plan por bloques B0..B7 — integración 2026-10-08

**Qué es esto.** La secuencia única de trabajo pasa a expresarse en bloques de entrega de valor
`B0..B7`. **No es un segundo roadmap**: cada bloque es una agrupación con exit criteria sobre las
secciones RP-x ya existentes, y **no revoca, ni sustituye, ni reordena** ninguna decisión técnica
aceptada. Si un bloque y un ADR aceptado discrepan, el ADR gana y el bloque se corrige.

El bloque activo de esta sección (B0) está registrado como WorkItem `f8fc07e6` del ciclo
`rp7-sem-s6-plugin-sdk`; los bloques siguientes se abren como WorkItem al iniciarse, no antes. La
ejecución opera por la unidad de entrega del §12 (TRAIN + WU internas), y el orden
`B0 -> B1 -> ...` no autoriza a adelantar el gate del que depende cada bloque. La unidad de entrega (TRAIN + WU internas) se define en el §12, donde vive desde 2026-10-18 la forma de recuperar el estado sin SDDK.

### 13.1 Trazabilidad bloque → RP existente

```text
B0  verdad del repositorio, procedencia y admisión      RP-0, RP-1, ADR-0105 (G10)
B1  hardening de runtime y convergencia semántica       RP-3, RP-2, RP-034, AUD-02..AUD-08
B2  S6 plugin SDK v2 y plataforma extensible           RP-6, 05-step-plugin-sdk-v2
B3  S7 certification harness v2                        RP-4, RP-5, CERTIFICATION_PROTOCOL
B4  S8 compatibilidad, migración y release 0.49.x      RP-4, RP-5, RP-6
B5  agent-first y secretless (ASX-0..ASX-6)             RP-7
B6  hardening local y seguridad de producción           RP-7, RP-4, RP-5
B7  depuración final y retirada de V1                   MIGRATION_PLAN, IMPLEMENTATION_BACKLOG
```

### 13.2 Orden y dependencias

```text
B0 -> B1 -> B2 -> B3 -> B4 -> B5 -> B6 -> B7
```

Ningún bloque abre trabajo de otro. Los spikes sin cambio público pueden adelantarse; su
implementación no puede adelantar el gate del que depende. `RP-8` y `RP-9` **no pertenecen a este
repositorio** (§10.1, §11.1): son de `pipelinek-fabric`.

### 13.3 Exit criteria por bloque

| Bloque | Exit criteria | Estado observado 2026-10-08 |
|---|---|---|
| B0 | Procedencia reproducible (mismo digest para entradas idénticas en dos checkouts, un path con espacios no rompe) + autoridad de admisión inequívoca + dependencias externas registradas como bloqueo verificable | **PARCIAL**: procedencia corregida y probada (digest idéntico al base en worktree, exclusiones exactas, builds reales); autoridad resuelta por ADR-0105; hallazgos abiertos: B0-F1 (release fuera de `main`, PR #99) y B0-F2 (main sin checks requeridos), ambos requieren autorización para tocar el remoto |
| B1 | El perfil de ejecución soportado cumple sus contratos; sin defectos críticos/altos del bloque sin disposición explícita | **CERRADO con gate verde** (`check` 24m41s, 5195 tests, 0 fallos, 140 skipped declarados): AUD-02(c)(d) y su fuga de tmpdir, AUD-04, AUD-05, AUD-07 y el keying de AUD-08 cerrados con mutación; AUD-06 parcial con dueño y fila guarda; **AUD-03 CERRADO por B1.2** (`../07-uat/B1_2_WAITUNTIL_TERMINAL_ADT_RECEIPT.md`) — el terminal de `core.waitUntil` es el ADT `WaitUntilCompletion` que el motor durable ya proyectaba, el token de wire pasa a ser proyección derivada y el decoder **rechaza por nombre** lo que no puede nombrar en vez de coercionar a un timeout inexistente; allowlist de la ley ahora **vacía** y baseline de emisiones 3 → 2, con 2 mutaciones muertas. **B1.3 primera rebanada CERRADA** (`../07-uat/B1_3_CONTROL_JOURNAL_UNKNOWN_STATUS_RECEIPT.md`) — las filas de control de `retry` y `waitUntil` rechazaban un estado persistido desconocido fuera de su propio vocabulario (`Enum.valueOf` escapando como `IllegalArgumentException` en vez de `…DivergenceException`), lo que además perdía el fichero en el diagnóstico: un status desconocido **debe** rechazarse porque defaultear a `PENDING` re-ejecutaría un intento con efecto hijo ya ocurrido y a `SUCCEEDED` se tragaría un fallo; helper único `operationStatusOrThrow` con el rechazo como función, 2 mutaciones muertas (crudo y default). Gate: `check` 27m28s, **794 clases / 5225 tests**, 0 fallos, 140 skipped declarados. Las otras tres categorías de B1.3 se midieron y **no** se tocaron (83 `Instant.now()` en 32 ficheros con sustitución masiva prohibida; `StepOutcome.valueOf` inexistente; los `valueOf` de **codecs de salida** registrados como P2 con dueño y exit criterion). **B1.3 segunda rebanada CERRADA** (mismo recibo, §8) — el KDoc de `CoreWaitUntilStep` afirmaba que su handler stub se alcanzaba por el dispatcher legacy, premisa que dejó de ser cierta en G5 (2026-09-18) y que nadie volvió a mirar. Medido contra la autoridad productiva, no leído: (a) el cuerpo **sí** se ejecuta — su efecto `core.sh` deja el fichero marcador; (b) el handler **no** emite — se le dio un reloj absurdo (1970-01-01) y ningún `WaitUntilPolled` lo llevaba, luego los eventos salen de `WaitUntilEngine` vía el short-circuit `CANONICAL_ENGINE`. El handler es **código muerto en producción**; se conserva (StepDefinition lo exige) pero el KDoc ya no afirma una premisa falsa y advierte que una versión alcanzable sería el silent no-op que prohíbe Semantic Constitution §2. Cambio de producción **solo KDoc** (`git diff -U0` filtrado deja cero líneas de código). Mutación `CANONICAL_ENGINE → HANDLER_CONTINUATION` muerta en la aserción del marcador — después de invertir el orden, porque la versión previa moría por una precondición de `RunOutcome.Success` y por tanto no probaba lo que decía probar. Gate: `check` 27m11s, **795 clases / 5227 tests**, 0 fallos, 140 skipped. El resto es deuda P2 declarada en `../07-uat/B1_BLOCK_EXIT_RECEIPT.md` |
| B2 | La plataforma de plugins funciona desde fuera del monorepo y está certificada al nivel Step/SDK aplicable | **CERRADO en su sustancia, con dos residuos nombrados** (`../07-uat/B2_BLOCK_EXIT_RECEIPT.md`): **BOM** `:pipeline-sdk-bom` publicado (fuera de `publishedContractModules`, porque no tiene ABI que guardar) y **ejecución externa probada** — un consumidor independiente resuelve por `platform(...)` con coordenadas sin versión y EJECUTA la distribución instalada con `--plugin-jar`, afirmando el payload propio del handler. La lista B2.2 quedó **verificada fila por fila** (`../07-uat/B2_CHECKLIST_VERIFICATION_RECEIPT.md`): `apiRange`, plugins incompatibles, cargas duplicadas y errores de admisión ya estaban implementados y probados — el rechazo por rango prueba que el código del plugin NUNCA corrió, con control de no-vacuidad — y se citan en lugar de reimplementarse. Suites contractuales por plugin medidas (14 externo + 113 utilities + 20 scm-git, 0 fallos). Residuos: el arnés que convierta esas suites en veredictos con testigos exigidos por mutación (B2.4 → B3) y la disposición del puerto `BranchInvoker` |
| B3 | Testigos semánticos ejecutables, certificación externa coherente, veredictos que fallan ante mutaciones relevantes | **PARCIAL: precondiciones medidas** (`../07-uat/B3_PRECONDITION_STATE_RECEIPT.md`). El mecanismo de testigo ya existe y **discrimina**: `S0SemanticWitnessMatrixTest` (17) + `S3EnvironmentSemanticWitnessTest` (7), verdes con XML fresco. Kill/restart/recovery y seguridad destructiva tienen dueño y están verdes (15 tests: UatLocal001/002, S54, `DestructiveSafetyOwnershipTest`). **Sin dueño y declarado**: el vocabulario de veredicto (B3.7) no existe como tipo — es diseño con frontera decidida frente a ADR-0105, y no se creó aquí para no generar una segunda autoridad de admisión; extender el índice de testigos a los constructos restantes (el manifiesto tiene 50+3 y el índice nombra ~15); barrido de relojes (B3.5, B3a cubrió las dos sustituibles); escenarios S7 de plugin externo |
| B4 | Contratos clasificados y verificados, candidata `0.49.x` entregada al harness | **PARCIAL, sólo queda el handoff externo**. **B4.1/B4.3 verificados**: los 4 módulos publicados están clasificados EXPERIMENTAL con taxonomía cerrada, fitness que falla el build si falta una entrada y la regla de que una excepción no puede invocar madurez más laxa (16 verdes); el inventario de superficie está verificado **por reflexión contra el código** (11 verdes) y **se corrige contra el código**. **B4.2 CERRADO** (`../07-uat/B4_2_CONSUMER_COMPATIBILITY_CHARACTERIZATION.md`): la premisa de que hacía falta producir consumidores era **falsa** — los dumps `.api` versionados SON la superficie por revisión y se pueden diffear. Cinco ejes separados; el inventario medido por clase (2 clases y 12 miembros ausentes en contratos publicados) coincide **9/9** con `published-contract-exceptions.json`, medido antes de leerlo; eje durable verificado **negativo con mecanismo** (huella = `wireToken`, versiones idénticas, decisión no serializable); `pipeline-output` con delta cero; y los 6 tipos perdidos por `pipeline-step-sdk/api` **no son ruptura de consumidor** (módulo sólo-BCV, 0 `maven-publish`, verificado). **B4.4 CERRADO** (`../07-uat/B4_4_PLUGIN_AUTHOR_GUIDE_RECEIPT.md`): guía de autores junto al plugin de referencia, derivada **ejecutando** el ejemplo (par de aislamiento EXIT 0 / EXIT 1 con salida real, 380 tests 0 fallos), con 3 defectos de documentación corregidos — incluido que `StepContractSuite` **no existe como clase** y que `examples/README.md` documentaba una ruta de binario inexistente. Hueco de §4 **CERRADO**: `agentWithCapabilities` promovido a **STABLE** (`../07-uat/B4_AGENT_CAPABILITIES_PROMOTION_RECEIPT.md`) con testigo en ambas direcciones cruzando el coordinador de producción y mutación que aísla la positiva. Queda: B4.5 (candidata; autoridad del harness), `BLOCKED_EXTERNAL` mientras el harness no publique check-runs ni commit statuses |
| B5 | Agente y herramientas ejecutan con perfiles y secretos sin vía alternativa de ejecución | **OPEN** |
| B6 | Garantías anunciadas del perfil soportado verificadas por pruebas externas | **OPEN** |
| B7 | V1 retirada sin regresiones; V2 es la única ruta productiva | **OPEN**, último bloque |

### 13.4 Condiciones de STOP vigentes (no negociables)

No se declara un bloque terminado si: existe un defecto crítico/alto sin resolver dentro del alcance;
una UAT obligatoria está omitida; la semántica se conserva por stub o mock; un test verde no se
ejecutó realmente; se cambió un contrato público sin autorización; un efecto corre por ruta no
canónica; se perdió información durable; se debilitó una protección de credenciales; se cambió el
artefacto tras certificarlo; o falta la prueba externa exigida para el nivel anunciado. Un bloqueo
externo legítimo se documenta y se conserva: **nunca se convierte en PASS**.

### 13.5 Prohibiciones de frontera que este plan hereda

```text
- No implementar Jenkins, Kubernetes, control plane ni workers remotos aquí (son de pipelinek-fabric).
- No certificar ni promocionar *estabilidad*: la autoridad del veredicto y de la promoción estable es el
  harness externo (ADR-0105). ADR-0099 D6 mantiene aquí la construcción de la candidata, la verificación
  local, la integración en `main`, el tag/publicación del prerelease y sus recibos. La prohibición que
  sobrevive es la de NO declarar CERTIFIED ni STABLE_PROMOTED sin veredicto externo, y la de no publicar
  el artefacto Maven definitivo como efecto lateral de publicar el prerelease (ADR-0099, ROADMAP A P2.4).
- No reintroducir GitHub Actions para cerrar G10 (ADR-0105 D3).
- No mantener tests que dependan del binario instalado o del corpus externo: eso vive en el harness.
- No crear estado operativo nuevo dentro del repositorio.
- No eliminar V1 antes de demostrar equivalencia o sustitución de sus capacidades necesarias.
```

**Reconciliación 2026-10-08 (P0.3).** La redacción anterior de esta sección decía
"No publicar ni promocionar releases desde este repositorio: la autoridad es el harness
externo", lo que contradecía a la vez ADR-0099 D3/D4/D6 y el propio objetivo de este plan. La
ambigüedad era real y ya estaba reconocida: **ADR-0099 y ADR-0105 no chocan entre sí**, reparten
cosas distintas.

```text
                      producto (este repositorio)         harness externo
construcción candidata      SI (ADR-0099 D6)                     no
verificación local         SI (ADR-0099 D6)                     no
integración en main        SI (ADR-0099 D3, sin squash)          no
tag + prerelease           SI (ADR-0099 D4)                      no
publicación Maven rc       SI (ROADMAP A P2)                     no
publicación Maven GA       NO — exige autorización + G10         no
check de admisión G10      no — no hay superficie de CI          SI (ADR-0105 D3)
veredicto CERTIFIED        no                                   SI (ADR-0105)
promoción STABLE           no                                   SI (ADR-0099 D5)
```

Los cuatro estados que este plan debe distinguir sin ambigüedad:

| Estado | Qué lo autoriza | Dónde se declara |
|---|---|---|
| `CANDIDATE_PUBLISHED` | integración en `main` + tag alcanzable + artefactos con digest | recibo local de candidatas |
| `CERTIFIED` | veredicto externo sobre el `CandidateId` exacto | harness, este repositorio solo lo cita |
| `STABLE_PROMOTED` | `CERTIFIED` + gates de producto + G10 + autorización | este repositorio tras el veredicto |
| `BLOCKED_EXTERNAL` | cualquier estado cuyo veredicto externo aún no existe | se declara, nunca se convierte en `PASS` |

Ningún resultado local — `./gradlew check`, una UAT verde, un digest coincidente — simula un
veredicto del harness. Un `STEP-CERT` local nunca se escribe como `PRODUCT-GATE`.

### 13.6 GATE de integración local y su alcance

```bash
cd v2 && ./gradlew check --rerun-tasks   # presupuesto derivado según AGENTS.md rule 4
```

Es necesario para el cierre local cuando aplique y **no sustituye** la certificación externa ni
demuestra por sí solo el PRODUCT-GATE. Cada gate registra SHA y tree SHA, comando exacto, exit code,
tests/fallos/errores/skipped, tareas ejecutadas y `UP-TO-DATE`, detekt y API check, cobertura cuando
aplique, artefacto y SHA-256, UAT, mutaciones probadas, restricciones de plataforma, deuda abierta y
resultado `PASS`/`FAIL`/`BLOCKED`/`NOT_RUN`.

### 13.7 Plan `product-first` — propuesta ejecutora subordinada (2026-10-17)

**Un único enlace, no un segundo backlog.** La secuencia `B0..B7` de esta sección sigue siendo la
única autoritativa: este enlace no la reordena, no la sustituye y no crea un TODO paralelo. La
propuesta se ejecuta *dentro* de los bloques que ya existen, usando su backlog y la unidad de entrega del §12.

- Plan: [`PRODUCT_FIRST_PRODUCTION_READY_2026-10-17.md`](PRODUCT_FIRST_PRODUCTION_READY_2026-10-17.md) (H0..H5)
- Especificación: [`PRODUCT_FIRST_MINIMUM_VERIFICATION_SPEC.md`](../03-specifications/PRODUCT_FIRST_MINIMUM_VERIFICATION_SPEC.md)
- Decisión: [`ADR-PK-DF-002-bounded-validation-and-product-first.md`](../04-adrs/ADR-PK-DF-002-bounded-validation-and-product-first.md) — **`status: accepted`, ratificada 2026-10-18**

**Ratificación y su límite (2026-10-18).** El gate humano fue concedido por el product owner, así que
H0.1 pasa a `DONE` y el bloqueo `BLOCKED_POLICY` que pesaba sobre H0.4 desaparece. **Lo que no
desaparece es el trabajo.** La aceptación de una ADR no ejecuta sus enmiendas: siguen sin hacerse
`AGENTS.md`, este ROADMAP, `RESPONSIBILITY_MIGRATION_ROADMAP.md` §4 y las tres specs citadas, y cada
una necesita su propio diff revisable. En particular la decisión 4 (el `check` global deja de ser
condición de cada merge) **sigue sin aplicación**, porque la regla de `AGENTS.md` que la contradice
está intacta y vigente; y la decisión 2 se ha partido en dos cláusulas con estados distintos — la
binaria está **cerrada y probada** por `FArch021`, y el desmontaje de los 38 ficheros / 8.250 líneas de
`src/main` queda **abierto y condicionado** a un sustituto medido del clasificador de lanes.

**Corrección de un síntoma que semeasurable y era falso.** Este ROADMAP y el plan citaban «H0.4 →
su síntoma observable es FArchM3 ROJO, y es correcto». Medido el 2026-10-18: `FArchM3CanonicalTaskRuntimeTest`
está **7/7 verde**. El único `ProcessBuilder(` no declarado en `:test-harness` es el literal de cadena
que `retirement/TestClassifier.kt` usa para *nombrar* la API que busca, y `KotlinSourceSanitizer`
enmascara los literales, así que el escáner no lo ve. Ese symptom no existió nunca como bloqueo y no
debe volver a citarse. La causa real de H0.4 no era una fuga: la fuga se cerró en `5e816d84` y la
razón de bloqueo era de política, como ya decía la fila anterior.

**Lo que la propuesta NO autoriza por existir aquí.** Copiar un ADR a su ruta canónica no lo ratifica,
y ratificarlo tampoco ejecuta sus enmiendas. Si una SPEC exige `CycleManifest`/`DeletionGuard` y la
enmienda no está hecha, el resultado es **bloqueo declarado**, nunca simular que la garantía existe.

**Regla de poda con el nombre `harness` (medido, no supuesto).** `dev.rubentxu.pipeline.v2.harness` es
un prefijo compartido por **tres** módulos — `:pipeline-event-harness` (que **se conserva**, alimenta
`pipeline events verify`), `:test-harness` (que se corta) y el `harness/` propio de
`:pipeline-application`. Ninguna poda puede guiarse por el nombre del paquete: se discrimina por
**módulo** y por **sitio de import** (`MainEventsVerifyCli` pertenece al lado que se conserva).

**Condición de reversión.** Si la poda obliga a construir `Harness2`, más ADTs o una segunda matriz de
fidelidad, se revierte al contrato simple Gradle/JUnit. El objetivo declarado es menos infraestructura
de pruebas, no una infraestructura nueva.

**Documentos sombra — NO son autoridad (registrado 2026-10-18, corregido el mismo día).** La frase
«un único enlace» de esta sección necesita un cierre: existen cuatro artefactos de roadmap en el
árbol que una búsqueda por palabra clave puede confundir con la secuencia vigente. Ninguno es
autoridad y ninguno se ha ejecutado sobre ningún SHA.

**Criterio de disposición, medido: quién cita a quién.** La primera versión de este bloque
declaraba los cuatro paquetes «sin trackear» y los trataba por ello como residuo. Eso es
incorrecto, y la medición lo refuta en los dos sentidos:

```text
paquete                                     citas desde ficheros TRACKEADOS
docs/pipelinek-delivery-first-2026-10-16/          10   <- no es residuo
docs/pipelinek_delivery_first_radical/overlay/       1
docs/pipelinek_roadmap_integracion_2026-10-17/       0
ROADMAP.md (raíz del repo)                            0
```

Las 10 citas del primer paquete incluyen **una ADR ratificada** (`ADR-PK-DF-001`,
`status: accepted`, en `related:` y en dos párrafos) y **cuatro documentos de evidencia** de
`docs/v2/06-evidence/` que citan **números de línea** concretos (46, 59–69, 71, 79) como el
mandato que justificó una decisión de clasificación. Un lector en un clon limpio no puede
verificar ninguna de ellas. «Sin trackear» no significaba «muerto»; significaba **una referencia
rota que además parecía justificativa**.

**Las líneas citadas se verificaron una a una** y todas resuelven al mandato que se les
atribuye: 46 → heading de H2; 59–69 → cuerpo de H3 con sus cuatro TODO y su STOP; 71 → heading de
H4; 79 → STOP de H4. El contenido es el correcto, luego trackearlo sella la cita tal como está
escrita.

```text
ROADMAP.md (raíz del repo)                                  NO trackeado, 12.7K
  Distinto de docs/v2/05-roadmap/DISTRIBUTION_ROADMAP.md. Auditoría remota del 16-10.
  NO es este ROADMAP.md. Si algo cita "ROADMAP.md", el qualified path decide.
  Sin citas desde documentos trackeados: trackearlo sólo añadiría un `ROADMAP.md`
  competing en la raíz. Se queda fuera a propósito.

docs/pipelinek-delivery-first-2026-10-16/                   trackeado como PROCEDENCIA
  Paquete predecesor, superado por docs/pipelinek_delivery_first_radical/. Se declara él
  mismo subordinado y «propuesta operativa, no prueba de cierre». Trackeado porque 10
  documentos ya ratificados o publicados lo citan.

docs/pipelinek_delivery_first_radical/overlay/              trackeado como PROCEDENCIA
  Sus tres documentos viven ya en rutas canónicas, pero NO los tres byte-idénticos:
  2/3 idénticos, y PRODUCT_FIRST_PRODUCTION_READY_2026-10-17.md DIVERGE — el overlay es
  el input prístino y el canónico lleva la edición posterior. Es procedencia de una
  edición, no una copia muerta; borrarlo destruiría contra qué se editó.

docs/pipelinek_roadmap_integracion_2026-10-17/              NO trackeado
  Continuación BQ1–BQ5 sobre main@19d592e5. Se declara ella misma subordinada: «no sustituye
  ROADMAP.md». Útil para los TODO de BQ; no es secuencia, y nada lo cita. Queda fuera.
```

**Trackeado ≠ autorizado.** Estos paquetes son procedencia: el input que una ADR o un recibo
citan como origen. Entran en Git para que esas citas resuelvan, y **no** por eso se convierten
en secuencia, gate o autoridad. La precedencia de `ACTIVE_DOCUMENTS.md` sigue vigente: la
única secuencia es `B0..B7` más la de BQ.
