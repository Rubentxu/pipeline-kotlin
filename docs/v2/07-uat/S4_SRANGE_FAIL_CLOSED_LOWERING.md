# S4-SRANGE — Un rango de fuente que no se localiza es una negativa, no un salto

**Estado:** `IMPLEMENTED` · slice acotado a un fichero
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818` (RP7-SEM S4)
**Base:** `fa1cfb20027dac3156cde7770e13010932211a16`
**Alcance:** `ScriptedSourceLowering.rewrite`. No toca la columna de ejecución, ni el ADT
público, ni el runner.

---

## 0. Veredicto

`ScriptedSourceLowering` emitía `LoweringResult.Generated` **descartando silenciosamente** cualquier
mapped call cuyo rango no podía resolver. El autor recibía entonces `Unresolved reference 'sh'`
sobre **código generado que él nunca escribió**, cuando la información para explicarlo existía y
se tiraba a la basura.

Es el mismo shape que S4-A1 ya arregló en esta zona —consecuencia corregida, causa intacta— y por
eso pertenece a la lista de defectos del owner.

---

## 1. El defecto

```kotlin
// ANTES — ScriptedSourceLowering.rewrite
for (call in calls) {
    val start = offsetOf(text, call.location)
    if (start < 0 || start + call.sourceLength > text.length) continue   // ← fail-open
    edits += TextEdit(start, call.sourceLength, facadeCall(call))
}
```

`continue` deja el call **sin reescribir**, la reescritura sigue adelante, y `lower` devuelve
`Generated` como si nada. El programa generado contiene un `sh(...)` o `isUnix(...)` desnudo, sin
receiver, y el host lo rechaza en tiempo de compilación.

Eso no es una negativa fail-closed: es un **error desplazado**. Todo lo necesario para explicar lo
ocurrido estaba disponible —el mapper produjo una ubicación que este texto no tiene— y se
descartó en favor de un síntoma tres capas más abajo, sobre código que el autor no escribió.

### 1.1 Por qué la ubicación irresoluble es alcanzable

`ScriptedSourceMapper` es un **port** (`fun interface`). El mapper PSI de producción deriva las
ubicaciones del mismo texto que analiza, así que hoy no puede producir una que falle al resolver.
Pero el lowering es el componente que no debe descartar en silencio un mapped call, y el port está
explícitamente abierto.

Inyectar un mapper que devuelve una ubicación inexistente **no es un artificio**: es exactamente
la clase de entrada que el `continue` existía para tragar, y una defensa que sólo funciona para
la implementación que hoy está conectada no es una defensa.

---

## 2. El arreglo

`rewrite` devuelve un par cerrado en vez de un `String`:

```kotlin
private sealed interface RewriteResult {
    data class Rewritten(val text: String) : RewriteResult
    data class Unlocatable(val diagnostics: List<ScriptedSourceDiagnostic>) : RewriteResult
}
```

Ni `String?` ni excepción: el llamante necesita distinguir *"aquí tienes tu programa"* de *"esto es
lo que está mal en tu fuente"*, y la única forma honesta de llevar ambas es un par cerrado.

Los **dos** modos de fallo se distinguen, porque son dos errores de operador distintos:

| modo | síntoma | arreglo para el operador |
|---|---|---|
| `start < 0` | el mapper apuntó a una línea/columna que el texto no tiene | el mapper está leyendo otro texto |
| `start + sourceLength > text.length` | el mapper reclamó una extensión que el texto no tiene | el mapper calcula mal la extensión |

En un `continue` ambos se veían idénticos, y no tienen el mismo arreglo.

Se recogen **todos** los diagnósticos de todos los calls ofensivos, no sólo el primero: una pasada
le dice al operador todo lo que está mal, no lo primero que encuentra.

`ScriptedCallKind.describeForDiagnostic()` nombra el call en el vocabulario del autor
(`shell call \`sh(...)\``, `` `isUnix()` call ``, …). Un mensaje que dice "el call" es un mensaje
sobre el que el autor no puede actuar.

### 2.1 Por qué `Unlocatable` se proyecta sobre `LoweringResult.InvalidSyntax`

`Unlocatable` no afirma que la fuente del autor tenga sintaxis inválida: afirma que **el mapper y el
texto discrepan**. Puesto que el nombre de la variante dice otra cosa, conviene justificar por qué se
reutiliza en lugar de añadir un caso público nuevo.

El único consumidor de la variante (`Main.kt:593`) ya la lee exactamente así:

```kotlin
is LoweringResult.InvalidSyntax -> {
    // The source already compiled (host), so this is an internal
    // invariant violation: fail closed, never fall back to eager.
    System.err.println("Error: scripted source lowering failed: ${lowered.diagnostics}")
    System.exit(2)
```

Dos cosas verificadas ahí:

1. El comentario del consumidor **ya** interpreta esta variante como violación de invariante
   interna, no como "el script del autor no compila". Una llamada mapeada que no resuelve encaja
   exactamente en esa lectura: la fuente pasó el host, luego la ubicación *debería* resolver, y si no
   resuelve el mapper está mal.
2. El mensaje que ve el operador es genérico —`"scripted source lowering failed"`— y **no** le
   afirma que su script tiene un error de sintaxis. La distinción que de verdad importa (¿el texto
   del autor está mal, o el mapper está leyendo otro texto?) viaja dentro de cada diagnóstico, que
   es donde el autor puede actuar.

Añadir un caso público `LoweringResult.MappingInconsistent` sería una **forma pública nueva**, es
decir una decisión de ownership y no una limpieza de este slice. Se deja anotado para el owner en
§6 en vez de introducirlo aquí.

---

## 3. RED antes, GREEN después

| fase | log | sha256 | resultado |
|---|---|---|---|
| **RED** | `s4srange-red.log` | `fb4d67a6e9d86a058782db701319b0f5d86d40eae3894ea65b6699483d838e85` | EXIT=1, 4 tests, **2 RED**, 0 `^e: ` |
| **GREEN** | `s4srange-green.log` | `18460e933c17c77844c49e0cc60816e1c2467455f88a6e76d667b5b2cb88ae32` | EXIT=0, 4/0/0 |
| **M-SRANGE-M1** | `s4srange-mut1.log` | `73f6612f98dd46bc357db57bdcf8bfd55e30177d47f700c457aea89325c00d25` | EXIT=1, **2 RED**, 2 controles verdes |
| **post** | `s4srange-rerun.log` | `9686bf694b53fa8ee5bc7224146ed18efff20731b74d4c8c50b07fcb055d49a4` | EXIT=0, 4/0/0 |

### 3.1 Los dos controles, y por qué importan

Los cuatro tests se reparten en dos negaciones y **dos controles**:

```text
RED    a mapped call whose location does not exist in the text refuses the lowering
RED    a mapped call whose source length runs past the end of the text is refused
GREEN  CONTROL - a locatable call is still rewritten, so the harness is not what refuses
GREEN  CONTROL - a locatable call on a later line resolves, because offsetOf walks the text
```

Un lowering que se negase a **todo** pasaría las dos primeras. Los controles son lo que
demonstra que la negativa está keyed en la resolución y no en el harness. El segundo control en
particular distingue "no resuelve" de "sólo resuelve en la primera línea": `offsetOf` camina el
texto, y una llamada en la línea 2 de un source de 2 líneas es localizable.

### 3.2 La mutación que mata la afirmación

```text
S4-SRANGE-M1   restaurar el `continue` silencioso
log      s4srange-mut1.log
sha256   73f6612f98dd46bc357db57bdcf8bfd55e30177d47f700c457aea89325c00d25
EXIT=1   0 "^e: "
RED      las 2 negaciones
GREEN    los 2 controles
```

Testigos: **2**, atribución 1:1. Restaurada; `sha256sum -c` sobre
`20d112bab9f52c3621ba3a0b7083d0e758ae939ac164cf7cca685609bac638c2` OK, y run de confirmación
verde después de restaurar.

---

## 4. Ausencia de regresiones

```text
cmd   cd v2 && ./gradlew :pipeline-scripting-kotlin24:test :pipeline-application:test \
        --tests ...S4A1ScriptedShellReachabilityMeasurementTest \
        --tests ...S4A0ScriptedLoweringCharacterizationTest \
        --tests ...S4DataArgumentExpressionTest \
        --tests ...S4IdentityLoopScopeTest \
        --tests ...ScriptedIsUnixCompilerMappingTest \
        --tests ...UatComp*
log   s4srange-regress.log
sha256 8d8ddc3840a2d4bddf443f119aeff026842f004dca78762c73ceb809fb8b6fe9
EXIT=0 0 "^e: " · 1883 tests · 0 failures · 0 errors
```

El módulo completo de `pipeline-scripting-kotlin24` más los cinco tests que ejercitan el lowering
y los UAT de compilación de script.

> **Corrección posterior (2026-10-04):** el gate focal completo que esta sección declaraba
> `NOT_RUN` **se ha ejecutado**. Cierra la deuda, no la sustituye.

### 4.1 Gate focal completo del árbol — `NOT_RUN` → `RUN`

El `NOT_RUN` de arriba era real y estaba bien dicho: los 1883 tests de §4 son una comprobación
de regresión con filtros `--tests`, no un gate de bloque. Este es el que faltaba.

```text
SHA     5944ba8e9d244636aac01a8121149110e5ce499e
Árbol   f70cdd9c3015f635c452762abcda5ddfbfeec6c2
```

**Gate A** — el comando exacto del recibo de R1-E §5.2, cuatro módulos:

```text
cmd   cd v2 && ./gradlew :pipeline-step-sdk:runtime:test :pipeline-domain:test \
                   :pipeline-application:test :pipeline-architecture-tests:test --rerun-tasks
log   s4focal-test-gate.log
sha256 9ed784288fdbc4c06e388ee791c6855bf997a27a4d804d4958cfd173c9c6e3fa
EXIT=0  BUILD SUCCESSFUL in 27m 22s · 0 "^e: " · 0 detekt
```

| módulo | clases | tests | fallos | errores | skips |
|---|---|---|---|---|---|
| `pipeline-step-sdk/runtime` | 20 | 201 | 0 | 0 | 0 |
| `pipeline-domain` | 133 | 687 | 0 | 0 | 0 |
| `pipeline-application` | 300 | 2244 | 0 | 0 | 121 |
| `pipeline-architecture-tests` | 87 | 432 | 0 | 0 | 10 |
| **subtotal** | **540** | **3564** | **0** | **0** | **131** |

**Gate B** — el módulo que el comando de arriba **no cubre**, donde vive este slice:

```text
cmd   cd v2 && ./gradlew :pipeline-scripting-kotlin24:test --rerun-tasks
log   s4srange-module-gate.log
sha256 99fc8a2ff6766489b0c65f45cc9792e25ab06fabcb4bdabb14a3e49f852efd84
EXIT=0  BUILD SUCCESSFUL in 33s · 0 "^e: "
pipeline-scripting-kotlin24: 15 clases · 60 tests · 0 fallos · 0 errores · 0 skips
```

Y los cuatro tests de este slice confirmados por nombre en el XML de JUnit:

```text
TEST-dev.rubentxu.pipeline.v2.scripting.S4SourceRangeFailsClosedTest.xml
  tests="4" failures="0" errors="0"
```

**Combinado: 555 clases · 3624 tests · 0 fallos · 0 errores · 131 skips**, sobre el árbol
`f70cdd9c`. Los 4 tests nuevos están ejecutados y verdes en el árbol exacto.

### 4.2 Lo que este gate NO cubre

Es un gate **de tests**, no de `check`: no incluye **detekt (SAST)**. El gate de `check` sobre este
árbol sigue **ROJO** por un único issue que no es de este slice —
`StepDispatchEngine.kt:279` `LongMethod` (`s4focal-gate3.log` sha256 `fbdcd38f`, EXIT=1) — con
análisis y recomendación en
[S4_SAST_DISPATCH_LONGMETHOD_PROPOSAL.md](../06-design/S4_SAST_DISPATCH_LONGMETHOD_PROPOSAL.md).
PRODUCT-GATE sigue `BLOCKED_EXTERNAL`.

> **Nota de alcance, y es la lección de este gate:** el comando del recibo de R1-E §5.2 **no
> incluye `pipeline-scripting-kotlin24`**. Sus conteos (3564) son reproduciblemente correctos, pero
> no cubren este slice. Un gate heredado de otro no cubre lo que aquel no miró: por eso van dos
> gates aquí y no uno.

### 4.3 Procedencia de las cifras de este recibo

Honestidad sobre de dónde sale cada número, porque antes no estaba declarado:

| cifra | fuente | ¿está en el log hasheado? |
|---|---|---|
| `sha256` de cada log | el propio fichero | **sí** |
| `EXIT=0/1` | `$?` del shell en el momento de la corrida | **no** — se leyó directo, nunca se imprimió al log |
| `tests` / `fallos` / `errores` agregados | XML de JUnit (`build/test-results/test/*.xml`) | **no** — Gradle no imprime ese resumen cuando la tarea pasa |
| `4 tests completed, 2 failed` (fases RED) | **el log** | **sí** — Gradle lo imprime al fallar |

Las fases RED y la mutación son, por tanto, **verificables desde el artefacto citado**: los dos
métodos fallidos están nombrados, y la atribución 1:1 a las dos negaciones se puede comprobar
leyendo el log. Las cifras agregadas de la fase GREEN no se pueden comprobar desde el log, y su
árbol de XML queda sobrescrito por corridas posteriores.

---

## 5. Ficheros

| fichero | cambio |
|---|---|
| `…/scripting/ScriptedSourceLowering.kt` | `rewrite` devuelve `RewriteResult`; `continue` sustituido por diagnósticos tipados; `describeForDiagnostic()` |
| `…/scripting/S4SourceRangeFailsClosedTest.kt` | **nuevo** — 2 negaciones + 2 controles |

---

## 6. Lo que queda abierto

- **`StepOutcome.Unstable` sigue perdiéndose**, y por dos capas, no por una. La caracterización
  S4-A0 conoce la pequeña (`ScriptedRegistryResult` sólo tiene `Success` y `Failed`); la grande es
  que `ScriptedFrontendRunner.runBody` devuelve `StepOutcome.Success` **incondicional** y nunca
  agrega resultados por llamada, así que la rama `RunOutcome.Unstable` de `MainScriptedSupport` es
  **inalcanzable**. Arreglar sólo el ADT no arregla nada: sería ensanchar un tipo que nadie lee.
  El arreglo necesita además un acumulador de ámbito de run hilado por `RuntimeScriptedStepFacade`,
  y eso toca la columna certificada en `d0077253`. **Pendiente de decisión del owner.**
- **PRODUCT-GATE** sigue `BLOCKED_EXTERNAL` desde `754ddda0`.
- El defecto de tooling de `sddk-align` (override `--work-item` inalcanzable) sigue sin parchear.
- `consumersOf` de `SharedModelCompositionFitnessTest` sigue siendo un buscador textual.
- De la lista de cinco defectos del owner, **tres están ya cerrados** y verificados aquí:
  `sh(...)` normal (S4-A1), `restore` con `raw as JsonPrimitive` (S4-C4) y
  `readFile`/`fileExists` con `""` (`S4DataArgumentExpressionTest`).
- **Decisión de ownership que este slice no toma:** `Unlocatable` se proyecta sobre
  `LoweringResult.InvalidSyntax` (§2.1). Es honesto bajo la lectura que el consumidor ya hace, pero
  el nombre de la variante no describe el hecho. Si el owner prefiere un caso público dedicado
  (`MappingInconsistent`), es un caso nuevo en un ADT público del módulo de scripting y **debe
  entrar como decisión propia**, no como parte de este commit.
