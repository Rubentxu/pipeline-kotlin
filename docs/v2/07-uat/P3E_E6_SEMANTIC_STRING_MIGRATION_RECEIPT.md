# P3-E E6 — el vocabulario semántico que viaja como `String` deja de autorizarse a mano

**Estado:** `STEP-CERT` para E6a, E6b, E6c **y D3**. **`P3-E` queda `CLOSED`** (§1, §5, §6).
**Work item:** `c2d7f818-3260-41d0-8e46-e9fa300b9f59`
**Base:** `4700f23db8e38bb5677af56f7aa2e5084c4cd197`
**SHA E6:** `88a2747accef15c04fc4c7fd339070978de31b18` · árbol `e6c1ddc05ef722e6e70b804155cdd6b29da5470e`
**SHA D3:** `072f9a31006cfbba63d7aac9c7c59d9e401f28fc` · árbol `93a646ce3b1657dd28c0f9ad7a612d4efa61c6d1`
**SHA gobernanza:** `cea3e85c72321d500130b1f51c8eb076d9aa0c41`

> **Cómo se cierra D3 y qué cambió respecto a la primera versión de este recibo.** Este documento
> se escribió cuando D3 seguía abierta y su §1 era, deliberadamente, una explicación de por qué
> **no** se declaraba `P3-E = CLOSED`. Esa sección y el §5 se han reescrito porque su premisa ya
> no es cierta. Se conserva el texto original donde sigue siendo verdadero —el hallazgo de §5.1,
> los obstáculos de §5.2 a §5.4— porque son la razón por la que D3 fue una unidad de otro tamaño
> y no el final de E6b. Lo que cambió no fue el análisis sino su veredicto.

---

## 1. Qué certifica este recibo, y por qué ahora sí cierra P3-E

E6 tenía tres unidades abiertas en el inventario, más una cuarta que las desbloqueaba:

> `D1, D2 y D3 bloquean E4 y por tanto E6.` — `P3E_EVENT_SEMANTIC_STRING_INVENTORY.md` §7

**Las cuatro están cerradas.** D1 y D2 lo estaban desde E4b.4 y `RunLifecycleEngine`; **D3 se
cierra en `072f9a31`**, y §6 recoge la evidencia con la que se verificó y §5 explica por qué era
una unidad distinta y no el final de E6b.

El inventario se reconcilia en el mismo commit que este recibo: §7 pasa a registrar D1, D2 y D3
como cerradas con su SHA, y mantiene D4 y D5 como abiertas. Un inventario que dijera lo contrario
sería el documento que desmiente al propio cierre, que es exactamente el fallo que este recibo
existía para evitar.

**Lo que cierra `P3-E` y lo que no.** Cierran E6a, E6b, E6c y D3. **No** se cierra el
`PRODUCT-GATE`, que sigue `BLOCKED_EXTERNAL` desde `754ddda0`: no hay CI remota en este
repositorio, y un `STEP-CERT` nuevo tampoco lo vuelve verde. Ver §4.

---

## 2. Las tres unidades cerradas

| commit | unidad | qué cambió |
|---|---|---|
| `43297752` | **E6a** — madurez por superficie | La madurez deja de declararse por módulo. `published-contract-maturity.json` gana una capa `surfaces`: módulo → familia → declaración, con `covers` que debe resolver en **dos autoridades independientes** |
| `93029b6f` + `0ee8c75a` | **E6b** — `failureKind` tipado | `StepSpec.Error.failureKind: String → FailureKind`. Un token inválido deja de compilar |
| `88a2747a` | **E6c** — codec de campo opcional | La convención «este campo no viaja si está vacío» pasa de 26 literales sueltos a **una autoridad única** (`EventJsonFields`), con las dos mitades atadas **por conjunto** |

### 2.1 E6a — una superficie tiene que ganarse su madurez

El módulo `pipeline-scripting-api` es `EXPERIMENTAL`. Eso es cierto como agregado y falso como
detalle: dentro de él hay cuatro constructos —`load`, `node`, `ansiColor`, `retry (retrofit)`—
que son **sintaxis aceptada que siempre falla cerrado**, y son los únicos de todo el
repositorio con esa propiedad. Un `STABLE` inventado habría sido peor que no declarar nada; un
`UNSUPPORTED_FAIL_CLOSED` con evidencia es una afirmación que se puede falsar.

La ley que hace el trabajo no es la de taxonomía, es la de **`covers` con dos authorities**:
cada nombre declarado tiene que resolver a la vez en el dump `.api` del módulo y en
`docs/v2/surface/DSL_SURFACE_MANIFEST.md`. Un `.api` y un manifiesto pueden divergir; una
superficie que sólo aparece en uno no es una superficie, es un error de transcripción. Esa ley
atrapó, de hecho, una transcripción mía de 39 caracteres donde el SHA tenía 40.

Ocho leyes nuevas, **8/8 mutaciones RED** restauradas con sha256 verificado.

### 2.2 E6b — el error de autoría se mueve a compilación

`error("boom", "USER")` sigue compilando con `failureKind: String`; `"USR"` también. El
runtime era el primer —y único— punto donde se validaba una decisión que el autor había
escrito. Con `FailureKind`, `"USR"` no compila.

Lo que **no** se hizo, deliberadamente:

- **No** se restauró un overload `String`. Dos grafías para una decisión, de las que sólo una
  está comprobada, es exactamente la ambigüedad que la migración elimina.
- **No** se normalizó el cable. `FailureKind.name` produce los tokens históricos exactos, y
  `ErrorFailureKindWireCompatibilityTest` lo comprueba en las cuatro dimensiones: proyección
  `.name` → cable histórico → decoder de producción → mismo `FailureKind`. Un token
  desconocido sigue fallando cerrado.

**Dos rupturas, declaradas como dos**, porque son dos cosas distintas: la ruptura *binary* por
cambio de descriptor, y la ruptura *source* para quien escribía la cadena. El caso habitual
`error("boom")` sigue siendo compatible, y eso se dice explícitamente en vez de dejar que se
adivine.

**La publicación Gradle también cambió, y se dice así.** `implementation` → `api` no es «no
cambia superficie»: escribe `pipeline-domain` en el `apiElements` del Gradle Module Metadata.
La afirmación no se sostiene; se prueba desde fuera. `examples/scripting-contract-consumer/`
declara **una sola coordenada** (`pipeline-scripting-api`) y nunca nombra `pipeline-domain`, y
el control negativo está **medido**: con el publisher degradado a `implementation`, ese build
falla con

```text
Cannot access class 'dev.rubentxu.pipeline.v2.domain.FailureKind'. Check your module classpath
```

La primera versión de esa prueba era **vacía** —un source set nuevo hereda la `implementation`
del proyecto, así que compilaba con cuatro coordenadas y no probaba nada—. Sólo el control
negativo lo delató. Una consumer proof que no puede fallar no es una prueba.

### 2.3 E6c — una autoridad para la ausencia

`EventJsonWriter` escribía `buildResult ?: ""` y `JsonEventLog` lo leía con
`takeIf { it.isNotEmpty() }`. Trece veces en el escritor, trece en el lector, cada literal
escrito por separado y ninguno declarado.

El número coincidía, y por eso nadie miró: **13 == 13 no detecta nada**. Si el lector hubiera
añadido un campo que el escritor no produce, el recuento seguiría dando verde y el campo
ausente se decodificaría como cadena vacía —un valor fabricado—. La garantía que importa no es
el recuento sino que **los conjuntos coincidan**, y por eso `FArchE6OptionalFieldCodecFitnessTest`
compara conjuntos y no números. Tres leyes, **3/3 mutaciones RED**.

Lo que este cambio **no** abrevia: `stageResult` sigue fallando cerrado en
`JsonEventLog.kt:901` (`?: return null`). Tipar la ausencia no autoriza degradarla a `null`.

---

## 3. Evidencia del gate

### 3.1 Gate completo, sobre el árbol exacto

```text
./v2/gradlew -p v2 --no-daemon check --rerun-tasks
BUILD SUCCESSFUL in 30m 59s
329 actionable tasks: 329 executed
EXIT=0
```

- **0 errores de compilación** (`grep -c '^e: '` = 0). Un fallo de compilación no es un RED de
  mutación, y se leyó antes de creer cualquier resultado.
- **Recuento acotado por `mtime >= 2026-10-06 11:47:03`** (el arranque): 755 clases,
  **5010 tests, 0 failures, 0 errors**, 140 skipped.
- **Delta +3** sobre `93029b6f` (5007): exactamente las tres leyes de
  `FArchE6OptionalFieldCodecFitnessTest`, las tres `ran` y sin skip. La contabilidad cierra.

**Fallo de medición propio, registrado.** El primer recuento usó el glob
`v2/*/build/test-results/**`, que omite los diez módulos anidados de `v2/pipeline-step-sdk/`, y
reportó 4452. El árbol completo `v2/**` da 5010. Un recuento global mezcla XML de corridas
anteriores; uno con el glob incompleto subestima. Las dos cifras están aquí para que la
discrepancia no se reinterprete como una regresión.

### 3.2 Cobertura dentro de `check`

| requisito | evidencia |
|---|---|
| módulos (`domain`, `scripting-api`, `events`, `events-store`, `application`, `architecture-tests`) | 5010 tests, 0 fallos |
| detekt | **27 tareas**, incluidos `:pipeline-domain:detekt`, `:pipeline-events-store:detekt`, `:pipeline-architecture-tests:detekt` |
| BCV / apiCheck | **6 módulos**: `pipeline-output`, `pipeline-domain`, `pipeline-scripting-api`, `pipeline-step-sdk:api`, `pipeline-events`, `pipeline-credentials-api` |
| corpus de compatibilidad | `CompatibilityCorpusTest` ejecutado; `v2/compatibility/15-error.pipeline.kts` migrado |

`grep -c 'Task :detekt'` devuelve **0** y es una mentira: las tareas se llaman `:<módulo>:detekt`.
El primer grep estaba mal, no el resultado. Queda escrito porque el mismo error volvería a
confundir a quien lo lea.

### 3.3 Consumidores externos — fuera de `check`, por decisión

Los consumidores externos **no forman parte de `check`**: incluirlos publicaría al sdk-repo y
bifurcaría un segundo Gradle en cada build. Son un paso de gate explícito.

```text
:verifyFabricContractConsumer          -> 16 tests, 0 fallos   (3 clases)
:verifyScriptingContractConsumer      ->  3 tests, 0 fallos   (1 clase)
```

Ambos se ejecutaron **directamente** con `--rerun-tasks`, porque las tareas `Exec` reported
`UP-TO-DATE` y `--rerun` no las forzó. **Un `UP-TO-DATE` no es una corrida** y no se cuenta como
evidencia de nada.

### 3.4 Procedencia de los artefactos publicados

Un consumidor que compila contra un `sdk-repo` viejo no prueba este árbol. Los cuatro módulos
publicados se compararon por sha256 contra los jars recién construidos:

```text
pipeline-domain          IDENTICO  577ee6243e89380590f0afab2bb4194f8774f9769b905f1ecf40ebb33285d1a2
pipeline-scripting-api   IDENTICO  9fae4fec6b3c6ec3b2ff1472b851781716aa6807fe3dedf154238de2acf20b02
pipeline-events          IDENTICO  db7ca42b6168a773e10b18775957694f5175645d17ef95f73544e3c09943a538
pipeline-output          IDENTICO  36afbcd514e177981deecac826f88bd8ebb01e3089ab1853f593427dc31b1d7b
```

### 3.5 Identidad del árbol

```text
88a2747a^{tree} = e6c1ddc05ef722e6e70b804155cdd6b29da5470e
recibo de gate  = e6c1ddc05ef722e6e70b804155cdd6b29da5470e
```

El gate corrió sobre el **mismo árbol** que el commit certifica. La entrada del gate es el
árbol, no el SHA, así que el resultado transfiere —y esa es la razón por la que se comprueba el
hash en lugar de reejecutarse 31 minutos para producir el mismo número.

### 3.6 Mutaciones

```text
E6a   8/8 RED      E6b  2/2 RED      E6c  3/3 RED      total  13/13
```

Cada una atribuida 1:1 a las afirmaciones que tumba, restaurada después con sha256 verificado.
Una atribución honesta que no se ablanda: la primera mutación de E6b tumba **tres**
afirmaciones a la vez, porque las tres son la misma garantía vista desde tres lados; está
registrada así en vez de repartida en tres mutaciones que habrían Parecido independientes.

### 3.7 Rupturas registradas

```text
1d892a4b  pipeline-domain         WaitUntilReconciliationDecision.Aborted
93029b6f  pipeline-scripting-api  StepSpec.Error.failureKind
```

El commit de gobernanza sigue **inmediatamente después** del commit de ruptura, nunca antes: un
SHA futuro no se inventa para poder registrar la excepción.

---

## 4. Lo que este recibo NO hace

- **Cierra `P3-E`** (E6a, E6b, E6c y D3), y lo cierra **con su SHA y su árbol** (§6).
- **No certifica el PRODUCT-GATE**, que sigue `BLOCKED_EXTERNAL` desde `754ddda0`: no hay CI
  remota en este repositorio y «CI verde» no es una evidencia disponible aquí. Un
  `STEP-CERT` nuevo tampoco vuelve verde el `PRODUCT-GATE`.
- **No cierra D4** (`CoreWaitUntilStep` emite hechos falsos, `Allowlisted` por fichero y línea)
  ni el hallazgo de `EventViewProjection.kt:85-86`, que filtra por `kind` literal y devuelve
  vacío en silencio — fail-open de la misma clase que cerró E4c.
- **No corrige `AGENTS.md`**, que afirma 3 constructos `UNSUPPORTED_FAIL_CLOSED` cuando el
  manifiesto lleva 6 y `agent` ya está promovido. Es instrucción del usuario, no un artefacto.

---

## 5. D3 — el hallazgo que la abrió, y por qué fue una unidad de otro tamaño

> Esta sección se escribió cuando D3 **no** estaba implementada, y su entonces §5.5 se titulaba
> «por qué no se decide aquí». Ese texto se conserva abajo como §5.5 *bis* porque describe una
> decisión de producto que efectivamente se tomó después: **interpretar, no retirar**. Lo que ya
> no está es el veredicto de «abierta».

### 5.1 El hallazgo

`CatchErrorTriggered.stageResult` es `String` y no lo lee **ningún** consumidor: la decisión de
`§4.2` usa exclusivamente `buildResult`. Viaja cuatro capas sin ser interpretado —el
`dead semantic parameter` de la Semantic Constitution Law §2—.

### 5.2 Por qué tipar sólo el evento sería un retroceso

Hoy el `String` llega al evento **sin validar**, y un token fuera de vocabulario se decodifica
sin protesta. Si se tipa el evento y el decode pasa a fallar cerrado —que es lo correcto—, una
errata escrita en el DSL, que sigue siendo `String` en las tres capas de arriba, produciría un
registro **que este runtime no puede volver a leer**. Texto mal escrito convertido en pérdida de
historia: el mismo defecto que E4c cerró en el otro extremo.

### 5.3 El dato que obliga a tipar la cadena completa

```text
StageScope.catchError              stageResult: String?      @Deprecated(LFC1-007)
StepSpec.CatchError                stageResult: String?      ABI publicada
ContextOverlay.CatchErrorOverlay   stageResult: String       @Serializable
StructuralOverlay.CatchErrorEntered stageResult: String
CatchErrorTriggered                stageResult: String       ABI publicada
```

**Y hay un acoplamiento que la caracterización no recogía.** El productor es:

```kotlin
val effectiveStageResult = stageResult?.uppercase() ?: effectiveBuildResult
```

De ahí se siguen dos cosas que cambian el tamaño de la unidad:

1. **El vocabulario de `stageResult` no es más pequeño que el de `buildResult`.** Sin
   `stageResult`, el valor **es** el `buildResult`, que admite `SUCCESS`. Declarar un ADT de dos
   valores para `stageResult` haría `stageResult = SUCCESS` inexpresable —una ruptura real, no
   una limpieza—. La autoridad correcta es la que ya existe y ya decide en
   `RunLifecycleEngine`: `CatchErrorBuildResult`.
2. **`.name` no sirve para el cable.** Los casos son `Success`/`Unstable`/`Failure` y el cable
   histórico es `SUCCESS`/`UNSTABLE`/`FAILURE` en mayúsculas. La grafía debe declararse en el
   caso, no derivarse.

### 5.4 El obstáculo que convierte D3 en una unidad de otro tamaño

`ContextOverlay.CatchErrorOverlay` es **`@Serializable`**, y `stageResult` viaja ahí como
**cadena JSON desnuda**. Un ADT sellado sin serializador propio no compila; con el serializador
polimórfico por defecto de kotlinx emitirá `{"type":"SUCCESS"}` en lugar de `"SUCCESS"`, que
**cambia la representación histórica del IR**.

Cerrar D3 preservingando el cable exige, por tanto: el ADT con su token declarado, **un
`KSerializer` que emita y consuma la cadena desnuda**, las cuatro capas migradas, dos rupturas
de ABI publicadas registradas, y un gate completo. Eso es una unidad propia —como dice el propio
§4.3— y no el final de E6b.

### 5.5 bis La decisión que sí se tomó: interpretar, no retirar

Retirar el campo es una **decisión de producto con consecuencias de historia**: está en el `.api`
publicado, viaja en **4/4** registros históricos (`FAILURE` ×2, `UNSTABLE` ×2, evidencia en
`pipeline-events-store/src/test/resources/fixtures/`) y un observador externo de Jenkins lo
espera. Interpretarlo es la lectura compatible con «conservar el cable histórico exacto», pero
cuesta una ruptura de ABI en dos módulos publicados sobre una superficie que ya está
`@Deprecated` y siendo reescrita hacia `try/catch`.

Un ADT ceremonial no es la respuesta: `stageResult` ya tiene autoridad, y lo que falta es que
esa autoridad llegue hasta el cable.

**Lo que se decidió en `072f9a31` es la segunda de las dos lecturas: `stageResult` se
interpreta.** No se retira, no se marca `UNSUPPORTED_FAIL_CLOSED`, y no se deja como `String`. Las
tres lecturas eran defendibles; la que se descartó fue dejar el campo como texto, porque un
`String` que nadie lee es el `dead semantic parameter` que abrió la unidad.

Y aparece una cuarta que el §5.5 original no contemplaba, y que resultó ser la que hacía el
trabajo: **la superficie de autoría no se rompe**. `catchError(buildResult = "FAILURE", …)` sigue
compilando, con puente, y un token mal escrito muere en construcción. El detalle está en §6.

---

## 6. D3 — evidencia del cierre

**SHA `072f9a31006cfbba63d7aac9c7c59d9e401f28fc` · árbol
`93a646ce3b1657dd28c0f9ad7a612d4efa61c6d1`** — gobernanza en `cea3e85c72321d500130b1f51c8eb076d9aa0c41`.

### 6.1 Gate final sobre el árbol exacto

```text
./v2/gradlew -p v2 --no-daemon check --rerun-tasks
BUILD SUCCESSFUL in 30m 54s
329 actionable tasks: 329 executed
```

- **Huella del árbol congelada antes de lanzar:** `2c317f0a…` sobre 44 ficheros. **Idéntica
  después de la corrida.** Es la comprobación que evita repetir el precedente de certificar un
  árbol que se había movido durante la puerta: aquí la huella se toma antes y se vuelve a tomar
  después, y ambas se comparan.
- **Recuento acotado por `mtime >= 2026-10-06 13:56:17` (el arranque), sobre el árbol completo
  `v2/**`:** 760 clases, **5049 tests, 0 failures, 0 errors**, 140 skipped.
- `apiCheck` en **6 módulos** y `detekt` en **27**, dentro de esa misma corrida.

### 6.2 La puerta anterior fue ROJA, y no se declaró verde por diagnóstico

A las `13:18:15Z` se lanzó una puerta sobre el mismo árbol y falló en
`UatDurableDefaultReuseCliTest` con «CLI did not finish». Lo que se comprobó, en orden:

| comprobación | resultado |
|---|---|
| ¿el fichero lo modifica esta unidad? | **no** |
| ¿el script del test usa `catchError`/`error`? | **0 llamadas** |
| ¿qué imprimió el CLI? | **nada** — un fallo de producto imprime diagnóstico |
| carga de la máquina | **16.6 / 61.4 / 23.4**, con **dos sesiones ajenas** lanzando Gradle en `pkf-b2` y `PipelineFabrickServer` |
| reejecuciones aisladas | **3 de 3 verdes** |

Se relanzó la puerta sobre el árbol sin tocar un solo fichero y salió verde. **El hallazgo que
queda abierto, y que no se corrige aquí:** ese test afirma con
`process.waitFor(45, TimeUnit.SECONDS)`, es decir asienta sobre **duración**, que es justo lo que
la Ley de Fidelidad de Harness prohíbe —un umbral en milisegundos es una propiedad de la máquina
y acabará fallando en una caja cargada, donde se leerá como un defecto del producto. Es un
defecto real del harness, no del producto, y está fuera de P3-E.

### 6.3 Un falso rojo que era del propio instrumento

`FArchE6OptionalFieldCodecFitnessTest` falló en la primera puerta con **2 RED**, y ninguna era
un defecto del producto:

- su regex del escritor exigía que el argumento terminase en el nombre del campo, y al tiparse
  la llamada quedó `event.buildResult?.wireToken`; el escáner dejó de **ver** la mitad del
  escritor que existe para comparar, y `onlyRead` salió no vacío;
- su pin de `stageResult` exigía la línea literal `… ?: return null`, y la lectura son ahora tres
  líneas con el parseo del token y el cierre **intacto** detrás.

Lo que se corrigió fue el instrumento, no la ley. **Y la prueba de que no se relajó son tres
mutaciones que matan las tres degradaciones que las leyes existen para detectar:**

| | qué muta | resultado |
|---|---|---|
| **M5** | `stageResult` de campo requerido a opcional | **2 leyes rojas** |
| **M6** | el cierre fail-closed sustituido por un default inventado | **1 ley roja** |
| **M7** | el lector pierde un campo que el escritor sí codifica | **1 ley roja** |

Restauradas con sha256 verificado (`JsonEventLog.kt` = `aa15429d…`).

### 6.4 Mutaciones de D3

| | qué muta | resultado |
|---|---|---|
| **M1** | overload `String` con todos los parámetros opcionales | **NO compila** (ambigüedad) — **no es un RED y no se cuenta como uno**; es lo que destapó el criterio de no-ambigüedad |
| **M2** | overload `String` con `buildResult` obligatorio | 16 tests, **1 rojo**: el de autoría, con `exitCode=0` — el defecto original reproducido |
| **M3** | restaurar `?: "UNSTABLE"` / `?: buildResult` | 7 tests, **1 rojo**: la ley de no-overlay, mostrando el `Unstable` inventado |
| **M4** | borrar el adaptador legacy | **3 leyes rojas**, incluida la principal de superficie `STABLE` |

### 6.5 Consumidores externos, y por qué están fuera del número de arriba

```text
:scripting-contract-consumer   12 tests, 0 fallos   (9 legacy + 3 tipado)
:fabric-contract-consumer      16 tests, 0 fallos   (3 clases)
```

Ejecutados **directamente** con `--rerun-tasks` contra los artifacts publicados en `sdk-repo`,
porque las tareas `Exec` dan `UP-TO-DATE` y **`UP-TO-DATE` no es una corrida**. Por eso el §6.1
no los suma: no entran dentro de `check`.

Procedencia de los artifacts publicados tras `publishSdkForExternalPlugin`:

```text
pipeline-domain          21d236f2…   cambia — FailureKind.parse / supportedTokens
pipeline-scripting-api   a757646b…   cambia — el puente
pipeline-events          ab07c773…   cambia — CatchErrorTriggered tipado
pipeline-output          36afbcd5…   IDÉNTICO — esta unidad no lo toca
```

`pipeline-output` byte-idéntico al registrado en la puerta de E6 es la comprobación de que la
publicación es reproducible y de que el delta está donde el cambio dice y no en otro sitio.

### 6.6 Rupturas registradas

```text
072f9a31  pipeline-domain          ContextOverlay.CatchErrorOverlay.buildResult/.stageResult
072f9a31  pipeline-events          CatchErrorTriggered.buildResult/.stageResult
072f9a31  pipeline-scripting-api   StepSpec.CatchError.buildResult/.stageResult
```

**Lo que no se registró, y por qué importa que no esté:** la superficie de autoría.
`catchError(String, String, String, Function1)` **no aparece en las líneas eliminadas del dump**:
sigue byte-idéntica, y `error(String, String)` se **añadió** en `PostStepsScope` y
`BranchScope`. Registrar esa superficie como rota sería mentir sobre lo que salió, y haría creer
al siguiente lector que el puente es prescindible cuando su único propósito es sobrevivir hasta
la frontera de retirada declarada.

`apiDump` registra una decisión ya tomada. Estas tres entradas son el recibo de una decisión que
ya se había tomado, y por eso el commit de gobernanza va **inmediatamente después** del de
ruptura: un SHA futuro no se inventa para poder registrar la excepción.

### 6.7 Corpus que no se reescribió

Los **53** tests que escriben tokens en su grafía `String` original siguen compilando **sin
migrarlos**: `ErrorHandlingTest` 7, `UatLocal012ErrorHandlingTest` 8, `CompatibilityCorpusTest`
30. `examples/07-catch-error.pipeline.kts` y los 7 escenarios UAT siguen usando literales
`String`. Un puente que obliga a reescribir el corpus no es un puente: es una migración con
nombre más amable.

---

## 7. Referencias

- `docs/v2/06-design/P3E_EVENT_SEMANTIC_STRING_INVENTORY.md` — §2.3 tabla maestra, §4.3
  caracterización de D3, §4.5 decisión implicada, §7 decisiones abiertas
- `docs/v2/surface/DSL_SURFACE_MANIFEST.md` — segunda autoridad para `covers`
- `v2/contract/published-contract-maturity.json` — capa `surfaces`, familia
  `self-refusing-constructs`
- `v2/contract/published-contract-exceptions.json` — 5 entradas (3 de ellas de `072f9a31`)
- `examples/scripting-contract-consumer/` — consumidor de una sola coordenada
- `v2/pipeline-events-store/src/test/resources/fixtures/07-catch-error.out.json` — evidencia
  real de `stageResult` en 4/4 registros
- `docs/v2/07-uat/S4_D2_SCRIPTED_UNSTABLE_SEMANTICS_RECEIPT.md` — precedente de formato y de
  la distinción STEP-CERT / PRODUCT-GATE