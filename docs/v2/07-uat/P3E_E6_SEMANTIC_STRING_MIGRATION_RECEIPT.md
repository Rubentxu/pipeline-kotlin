# P3-E E6 — el vocabulario semántico que viaja como `String` deja de autorizarse a mano

**Estado:** `STEP-CERT` para E6a, E6b y E6c. **`P3-E` NO queda cerrado**: D3 sigue abierto (§5).
**Work item:** `c2d7f818-3260-41d0-8e46-e9fa300b9f59`
**Base:** `4700f23db8e38bb5677af56f7aa2e5084c4cd197`
**SHA final:** `88a2747accef15c04fc4c7fd339070978de31b18`
**Árbol:** `e6c1ddc05ef722e6e70b804155cdd6b29da5470e`

---

## 1. Qué certify este recibo, y por qué no cierra P3-E

E6 tenía tres unidades abiertas en el inventario. Las tres están implementadas y verificadas
sobre el mismo árbol. La cuarta —**D3**— no lo está, y el propio inventario la pone como
bloqueante:

> `D1, D2 y D3 bloquean E4 y por tanto E6.` — `P3E_EVENT_SEMANTIC_STRING_INVENTORY.md` §7

D1 y D2 están cerradas (E4b.4 y `RunLifecycleEngine.kt:138-148`). **D3 no.** Un recibo que
declarara `P3-E = CLOSED` sobre un bloqueo abierto sería un verde falso por construcción: el
documento que autoriza el cierre sería la primera evidencia en contra.

Lo que este recibo hace es **bancar la evidencia de lo que sí está verificado**, de modo que
D3 se implemente sobre un estado conocido y no sobre una suposición.

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

- **No cierra `P3-E`.** D3 sigue abierto (§5).
- **No certifica el PRODUCT-GATE**, que sigue `BLOCKED_EXTERNAL` desde `754ddda0`: no hay CI
  remota en este repositorio y «CI verde» no es una evidencia disponible aquí. Un
  `STEP-CERT` nuevo tampoco vuelve verde el `PRODUCT-GATE`.
- **No cierra D4** (`CoreWaitUntilStep` emite hechos falsos, `Allowlisted` por fichero y línea)
  ni el hallazgo de `EventViewProjection.kt:85-86`, que filtra por `kind` literal y devuelve
  vacío en silencio — fail-open de la misma clase que cerró E4c.
- **No corrige `AGENTS.md`**, que afirma 3 constructos `UNSUPPORTED_FAIL_CLOSED` cuando el
  manifiesto lleva 6 y `agent` ya está promovido. Es instrucción del usuario, no un artefacto.

---

## 5. D3 — por qué sigue abierta, y por qué no se cerró a medias

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

### 5.5 Por qué no se decide aquí

Retirar el campo es una **decisión de producto con consecuencias de historia**: está en el `.api`
publicado, viaja en **4/4** registros históricos (`FAILURE` ×2, `UNSTABLE` ×2, evidencia en
`pipeline-events-store/src/test/resources/fixtures/`) y un observador externo de Jenkins lo
espera. Interpretarlo es la lectura compatible con «conservar el cable histórico exacto», pero
cuesta una ruptura de ABI en dos módulos publicados sobre una superficie que ya está
`@Deprecated` y siendo reescrita hacia `try/catch`.

Un ADT ceremonial no es la respuesta: `stageResult` ya tiene autoridad, y lo que falta es que
esa autoridad llegue hasta el cable.

---

## 6. Referencias

- `docs/v2/06-design/P3E_EVENT_SEMANTIC_STRING_INVENTORY.md` — §2.3 tabla maestra, §4.3
  caracterización de D3, §4.5 decisión implicada, §7 decisiones abiertas
- `docs/v2/surface/DSL_SURFACE_MANIFEST.md` — segunda autoridad para `covers`
- `v2/contract/published-contract-maturity.json` — capa `surfaces`, familia
  `self-refusing-constructs`
- `v2/contract/published-contract-exceptions.json` — 2 entradas
- `examples/scripting-contract-consumer/` — consumidor de una sola coordenada
- `v2/pipeline-events-store/src/test/resources/fixtures/07-catch-error.out.json` — evidencia
  real de `stageResult` en 4/4 registros
- `docs/v2/07-uat/S4_D2_SCRIPTED_UNSTABLE_SEMANTICS_RECEIPT.md` — precedente de formato y de
  la distinción STEP-CERT / PRODUCT-GATE