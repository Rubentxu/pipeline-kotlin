# S4-SAST — `StepDispatchEngine.dispatch` baselined, con deuda propia y criterio de salida

**Estado:** `CERTIFIED` (step) · decisión de ownership registrada
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818`
**Base:** `6a4a28ac0500753f601221243a30680bd2466b6f`
**Objeto:** el único issue de SAST que bloqueaba el gate de `check`

```text
v2/pipeline-application/src/main/kotlin/…/durable/StepDispatchEngine.kt:279:17
  LongMethod — dispatch is too long (125). The maximum length is 120.
  introducido por 5fcaf2f1 — Nomeacuerdo, 2026-10-02 — PR-020 slice 2b
```

**Decisión de ownership:** *baselinar*, no refactorizar. Registrada con siete condiciones; las
cinco que exigían constancia en este recibo están en §2, el criterio de salida en §3. La decisión
es del owner y su razonamiento — *«una métrica no debe fabricar por sí sola un requisito de
diseño»* — se conserva aquí porque es la regla que hace Sustainable la entrada.

---

## 1. Qué cambió

Dos ficheros. Ningún cambio de comportamiento.

| fichero | cambio |
|---|---|
| `v2/pipeline-application/detekt-baseline.xml` | **una** entrada `<ID>LongMethod` añadida |
| `…/durable/RunningSubprocessRecovery.kt` | `@see` que apuntaba a un símbolo inexistente, corregido |

```xml
<ID>LongMethod:StepDispatchEngine.kt:StepDispatchEngine$suspend fun dispatch: Dispatched</ID>
```

Entradas `LongMethod` en la baseline: **5 → 6**. Exactamente una.

El ID no se dedujo: se validó contra detekt (§4.1). Un ID aproximado en una baseline no falla,
simplemente no casa, y el hallazgo sigue reportándose.

### 1.1 El `@see` colgante

`RunningSubprocessRecovery.kt:57` referenciaba `RecoveryObservation`, un símbolo que **no existe en
el repositorio**: la única aparición de esa cadena era la propia línea. El tipo real se llama
`RunningSubprocessObservation`.

No se renombró ningún tipo de producción. Un error de KDoc se corrige en el KDoc; renombrar
producción para satisfacer una referencia equivocada invierte el coste.

### 1.2 Lo que deliberadamente NO se tocó

| no tocado | por qué |
|---|---|
| límite global de `maxLineLength` / longitud de método | una métrica no se relaja para dejar de molestar |
| `@Suppress("LongMethod")` en producción | esconde el hallazgo donde nadie lo revisa |
| baseline completa | regenerarla reescribiría deuda ajena y propia sin decisión |
| `detekt.yml`, baselines de otros módulos, supresiones | son otra decisión |
| las 5 `LongMethod` ya congeladas | precedente, no deuda pendiente |
| indentación anómala de `detekt-baseline.xml:32` | es de `5fcaf2f1`, no propia; ver §5.2 |
| `StepDispatchEngine.dispatch` | ver §2 |

---

## 2. Las cinco registraciones exigidas

### 2.1 Método afectado

`StepDispatchEngine.dispatch` — `suspend fun dispatch(...): Dispatched`,
`StepDispatchEngine.kt:279-482`.

### 2.2 125 líneas frente al límite 120

| magnitud | valor |
|---|---|
| líneas físicas en `dispatch` | 204 |
| de ellas, comentarios | 64 |
| vacías | 6 |
| **código** | **~134** |
| **cuenta de detekt** | **125** |
| límite | 120 |

### 2.3 Una parte relevante es formato de argumentos

```text
líneas de código en dispatch                      134
de las que son sólo argumentos con nombre          40   (30%)

ejemplo: stepExecutor.executeAndJournal(...)        9 líneas
         de las que son argumentos con nombre       7
```

Una de cada tres líneas de código de `dispatch` es `nombre = valor,`. Lo mismo con
`runtimeContext` (9), `recoveryInterpretation.interpret` (8) y `typedInputPreparation.prepare`.
El formato es deliberado: la columna durable usa argumentos con nombre de forma sistemática.

### 2.4 No se encontró una responsabilidad independiente extraíble

`dispatch` es una espina lineal que delega en **nueve colaboradores ya extraídos**: `prepareDispatch`,
`dispatchBody`, `reconcileInvocation`, `recoveryInterpretation.interpret`,
`typedInputPreparation.prepare`, `stepExecutor.executeAndJournal`, `journal.get`,
`cursorStore.advance`, `runtimeContext`. Tiene 5 `return Dispatched` tempranos y 4 `when` sobre
ADTs cerrados, y casi lógica propia.

La extracción posible sería cosmética o peor: partir una secuencia lineal de fases en ayudantes
que se llaman igual no mejora cohesión, sólo reparte el mismo problema en más sitios.

### 2.5 Refactorizarlo dentro de S4 crecería el scope sin aportar valor

S4 es la autoridad durable de reconciliación y la identidad de iteración. Una refactorización
estructural de la columna de dispatch dentro de ese bloque mezclaría dos problemas no
relacionados, sin que exista una decisión arquitectónica que la exija, sobre código que ya está
certificado.

### 2.6 Precedente

Cinco `LongMethod` ya congelados en `pipeline-application`, incluidos
`CanonicalDurableRunCoordinator.run` y `WaitUntilEngine.execute` — ambos más centrales que
`dispatch`. Eligieron baselinar. `dispatch` es el mismo caso.

---

## 3. Criterio de salida de la deuda

**Se elimina la entrada de la baseline cuando una evolución natural de `StepDispatchEngine`
permita extraer una responsabilidad real** — es decir, cuando exista una fase con nombre propio que
no sea una mera continuación lineal, y extraerla mejore cohesión.

**No se abre una refactorización cuyo único objetivo sea satisfacer una métrica.** Convertir 125
en 119 sin cambiar la responsabilidad es optimizar para detekt, no mejorar la arquitectura.

Esto es deuda **propia**: el código está en este repositorio y bajo esta responsabilidad. No se
registra como deuda de terceros, aunque `dispatch` no se haya escrito en S4.

---

## 4. Gate

### 4.1 Validación del ID, antes de pagar el gate

Un ID de baseline aproximado no produce error: no casa, y el hallazgo se sigue reportando. Por eso
el ID se validó con el módulo solo.

```text
log      s4r1-detekt-idcheck.log
comando  v2/gradlew :pipeline-application:detekt
EXIT=0   BUILD SUCCESSFUL in 5s
         1 actionable task: 1 executed      ← ejecutado, no UP-TO-DATE ni FROM-CACHE
         0 hallazgos LongMethod residuales
```

La línea `1 actionable task: 1 executed` es la que hace falta. Sin ella, un `BUILD SUCCESSFUL`
rápido puede ser una tarea que no corrió.

### 4.2 Gate de bloque

```text
log      s4r1-check-gate.log
sha256   6aebeb5ebade83ccf9cfc96624504cf737616d4f65e03ec4d947a24fd07d3b95
comando  v2/gradlew check --rerun-tasks
EXIT=0   BUILD SUCCESSFUL in 30m 18s
         289 actionable tasks: 289 executed
         0 líneas "^e: "        (0 errores de compilación)
         0 hallazgos detekt
```

Árbol certificado: HEAD `6a4a28ac` más dos ficheros modificados.

```text
4d53918645f03d1d8994f65c7343a8a1be059288664aa6d3a3cbc2f92839e8f8  detekt-baseline.xml
9eeb4c3b0bd0566f4881a03f7daa19ad2b18c2dedbe1cfe768e66d1a208b10f6  RunningSubprocessRecovery.kt
```

### 4.3 Conteo de tests, y de dónde sale

| módulo | clases | tests | fallos | errores | skips |
|---|---|---|---|---|---|
| pipeline-application | 301 | 2257 | 0 | 0 | 121 |
| pipeline-step-sdk/runtime | 20 | 201 | 0 | 0 | 0 |
| pipeline-domain | 133 | 687 | 0 | 0 | 0 |
| pipeline-architecture-tests | 87 | 432 | 0 | 0 | 10 |
| pipeline-events | 42 | 223 | 0 | 0 | 0 |
| pipeline-step-sdk/utilities | 2 | 119 | 0 | 0 | 0 |
| pipeline-step-sdk/http | 10 | 115 | 0 | 0 | 0 |
| pipeline-scripting-api | 16 | 89 | 0 | 0 | 0 |
| pipeline-release | 23 | 84 | 0 | 0 | 1 |
| pipeline-credentials-local | 12 | 71 | 0 | 0 | 0 |
| pipeline-scripting-kotlin24 | 15 | 60 | 0 | 0 | 0 |
| pipeline-credentials-api | 10 | 52 | 0 | 0 | 0 |
| pipeline-step-sdk/scm-git | 8 | 47 | 0 | 0 | 8 |
| pipeline-binding-factory | 3 | 37 | 0 | 0 | 0 |
| pipeline-step-sdk/files | 4 | 36 | 0 | 0 | 0 |
| pipeline-artefacts-local | 3 | 32 | 0 | 0 | 0 |
| pipeline-credentials-multipart | 3 | 30 | 0 | 0 | 0 |
| pipeline-event-harness | 2 | 19 | 0 | 0 | 0 |
| pipeline-credentials-executor | 3 | 15 | 0 | 0 | 0 |
| pipeline-step-sdk/processor | 2 | 11 | 0 | 0 | 0 |
| pipeline-step-sdk/workflow-control | 1 | 10 | 0 | 0 | 0 |
| pipeline-step-sdk/api | 2 | 8 | 0 | 0 | 0 |
| pipeline-step-sdk/junit | 1 | 8 | 0 | 0 | 0 |
| pipeline-testkit | 1 | 2 | 0 | 0 | 0 |
| **TOTAL** | **704** | **4645** | **0** | **0** | **140** |

24 módulos, 704 clases.

### 4.4 Procedencia de cada dato — declarada, no supuesta

Este bloque existe porque en recibos anteriores de este repo se presentó como verificable algo que
no lo era.

| dato | fuente | ¿verificable del log? |
|---|---|---|
| `sha256 6aebeb5e…` | el propio log | **sí** — `sha256sum` |
| `BUILD SUCCESSFUL in 30m 18s` | el log | **sí** |
| `289 actionable tasks: 289 executed` | el log | **sí** |
| `0 "^e: "` | el log | **sí** |
| `EXIT=0` | `$?` del shell | **no** — no está en el log |
| 704 clases · 4645 tests · 140 skips | XML de JUnit | **no** — Gradle no lo imprime cuando la tarea pasa |

Por eso la tabla de §4.3 se reconstruye desde los XML y no desde el log: un total leído del log
sería inventado.

### 4.5 Alcance: este gate es más ancho que el focal

El gate focal de `8c52167e`_certificó 540 clases · 3624 tests. Este es `check` completo:
704 clases · 4645 tests. Cubre los 164 puntos que el focal no miraba, incluidos
`pipeline-scripting-kotlin24` (15 · 60), que es el slice de S4-SRANGE. Un gate heredado de otro no
cubre lo que aquel no miró.

---

## 5. Lo que este documento NO hace

### 5.1 No declara verde el PRODUCT-GATE

`check` con detekt era el único bloqueante propio. El PRODUCT-GATE sigue `BLOCKED_EXTERNAL` desde
`754ddda0` por ausencia de CI remota. Este receipt certifica un Step, no el gate de producto.

### 5.2 No toca la indentación anómala de `detekt-baseline.xml:32`

Esa línea tiene 20 espacios de indentación contra 4 de sus vecinas. `git blame` la atribuye a
`5fcaf2f1` — Nomeacuerdo, 2026-10-02 — el mismo commit que hizo crecer `dispatch`. No es deuda de
este slice y el bloque entero es `<CurrentIssues>`, generado por detekt. Se anota, no se corrige.

### 5.3 No abre una refactorización

Ni ahora ni en un work item derivado. Ver §3.

---

## 6. Referencias

- `docs/v2/06-design/S4_SAST_DISPATCH_LONGMETHOD_PROPOSAL.md` — el análisis medido, `5944ba8e`
- `v2/build.gradle.kts:269-292` — política de SAST y propósito declarado de la baseline
- `v2/config/detekt/detekt.yml:83` — `maxLineLength: 160`
- `docs/v2/07-uat/C10_DETEKT_RATCHET_REPAIR_RECEIPT.md` — el gate que desmentía el KDoc de SAST
- `docs/v2/07-uat/S4_SRANGE_FAIL_CLOSED_LOWERING.md` — el otro slice de S4 cerrado con dos gates
