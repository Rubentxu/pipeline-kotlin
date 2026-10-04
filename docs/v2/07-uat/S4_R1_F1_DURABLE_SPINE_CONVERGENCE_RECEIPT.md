# S4-R1 F1 — Durable Spine Convergence: cierre consolidado

**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818` · **ADR:** `ADR-S4-R1` (`conformance: SATISFIED`)
**Rama:** `s4-a1b-scripted-shell-spine` · **SHA del full gate:** `e0500bf8`
**Veredicto:** F1 **CERRADO**. Una sola durable spine; fresh, reuse y recover convergen en
`CommonExecutionResult` sin pérdida de valor tipado.

```text
UNA única durable spine

FRESH ───────┐
REUSE ───────┼─→ CommonExecutionResult ─→ dispatch ─→ consumidor
RECOVER ─────┘

sin: segunda autoridad scripted · pérdida de typed output · clasificación
     Step-specific en el observer · reejecución para fabricar valores · branches por StepKey
```

---

## 1. Los cinco commits que componen F1

| commit | bloque | qué cierra |
|---|---|---|
| `b1540033` · `39e8ff03` | groundwork, 3b | §2.2, §2.3 — la rama de reattach expirada pasa a ser observable y **se mide** el colapso |
| `9966b997` | 3c + 3d | §0.2, §2.4, §2.6 — el observer da el hecho, la autoridad el significado, el intérprete proyecta |
| `cdcf68c6` | D2 | `Unstable` conserva su significado; la asimetría de replay queda medida |
| `5c021496` | **F1-B** | D-4 **por eliminación**: 357 líneas de segunda autoridad fuera de `src/main` |
| `d0fa34e8` | **F1-C0** | la matriz de verdad congelada **antes** de tocar producción |
| `4429e4ca` | **F1-C** | las dos manifestaciones de `conformance: PARTIAL` |
| `ce551860` · `e0500bf8` | cierre | conformance a `SATISFIED`; dump ABI regenerado |

## 2. Lo que F1 entrega, y por qué cada línea costó lo que costó

**F1-B — D-4 cerrada por eliminación, no por shim.** Se midió antes de borrar:
`pipeline-application` no declara `maven-publish` y sus cuatro dependientes son internos, luego la
clase no era API/ABI publicada y **la regla aplicable era eliminar**. 357 líneas fuera: la segunda
autoridad durable con su fingerprint y su tabla de replay, su reconciliador, y el puerto
`RunningScriptedOperationReconciler` — que quedó con **cero** consumidores y por tanto era el gancho
por el que una segunda autoridad podía volver a enchufarse. Un puerto vivo en `src/main` con forma de
parte del contrato es una invitación con firma.

**F1-C0 — congelar antes de escribir.** 595 líneas de test, 16 tests, **cero** cambios en producción.
Dos mitades explícitas, HALF A (lo que el spine hace) y HALF B (lo que el contrato dice), porque un
solo valor esperado borra exactamente la distancia que el bloque existe para capturar. Congeló
**1 DEFECTO** (`STATUS` + exit 42 → `FAILED` sin valor) y **4 gaps** (los tres de `STDOUT` más el de
`STATUS` exit 0). Dos REDs iniciales eran **premisas mías, no defectos**: conté 4 gaps y afirmé 3, y
una fila asumía que un heartbeat fresco produciría terminal en mano cuando no hay proceso detrás. Se
corrigió el alcance del test, nunca la afirmación.

**F1-C1 — el observer da hechos.** `Observed(DurableTaskTerminal)` en vez de un terminal semántico.
`observedTerminal` lee `result.txt` → `exitCode` y `output.txt` → `capturedStdout` con `Files.exists`,
de modo que `null` (ausente) y `""` (presente-y-vacío) son hechos distintos. El console log **no** se
lee como stdout tipado: son dos canales y confundirlos sería fabricar.

**F1-C2 — la materialización es del Step.** `RecoveredStepProjection<I,O>` es una **capacidad aditiva**
—no rompe `StepContract` ni `StepDefinition`— y el engine pregunta por la **interfaz**, nunca por
`StepKey`. `RecoveredExecutionMaterializer` es puro y no es una quinta autoridad: resuelve definition,
decodifica, proyecta y recodifica usando exclusivamente codecs y proyección del Step. **Sin fallback**:
sin proyección se falla cerrado nombrando el Step.

La guarda de evidencia insuficiente vive **en la proyección**, no en `classifyShellTerminal`, porque
`orEmpty()` es correcto en el camino fresh (`null` = «no imprimió nada») y es una fabricación en el
recuperado (`null` = «nadie escribió el fichero»). Meterla en la autoridad compartida habría exigido
enseñarle una distinción que no es suya y la habría hecho código muerto en un camino.

**F1-C3 — R14.** `Settled(result: CommonExecutionResult)` y
`Dispatched(result: CommonExecutionResult, context)`, sin accesor de conveniencia: los consumidores
proyectan `.result.outcome` explícitamente. El `OperationOutput` de recovery usa **el mismo puente
que `DurableStepExecutor`**, de modo que una fila recuperada y una fresh se escriben con una sola forma.

**Regla del status durable, descubierta al implementar:** el outcome materializado gobierna el status
**sólo** para `Exited`; para `Lost`/`Cancelled` lo gobierna **el terminal**. Sin esa corrección un `Lost`
se journalizaba `FAILED`, destruyendo el vocabulario que dice «no se pudo determinar el resultado», del
que dependen D-1 y §2.3. Quedó fijada por una aserción, no sólo por un KDoc.

## 3. Evidencia del cierre

### 3.1 Full gate local — `e0500bf8`

```text
cd v2 && ./gradlew check --rerun-tasks
arranque 13:36:22 · fin 14:07:11 · BUILD SUCCESSFUL in 30m 48s
EXIT=0   ^e: 0   289 actionable tasks: 289 executed
tareas FAILED: 0
```

Verificado **por nombre y frescura de XML**, nunca por exit code:

| módulo | clases | tests | F | E | skips |
|---|---|---|---|---|---|
| `pipeline-application` | 303 | 2287 | 0 | 0 | 121 |
| `pipeline-domain` | 133 | 687 | 0 | 0 | 0 |
| `pipeline-architecture-tests` | 89 | 440 | 0 | 0 | 10 |
| `pipeline-step-sdk/runtime` | 20 | 201 | 0 | 0 | 0 |
| `pipeline-events` | 42 | 223 | 0 | 0 | 0 |
| `pipeline-step-sdk/http` | 10 | 115 | 0 | 0 | 0 |
| `pipeline-step-sdk/utilities` | 2 | 119 | 0 | 0 | 0 |
| `pipeline-scripting-api` | 16 | 89 | 0 | 0 | 0 |
| `pipeline-release` | 23 | 84 | 0 | 0 | 1 |
| `pipeline-credentials-local` | 12 | 71 | 0 | 0 | 0 |
| `pipeline-scripting-kotlin24` | 15 | 60 | 0 | 0 | 0 |
| `pipeline-step-sdk/scm-git` | 8 | 47 | 0 | 0 | 8 |
| *otros 13 módulos* | 30 | 227 | 0 | 0 | 0 |
| **TOTAL** | **708** | **4683** | **0** | **0** | **140** |

`stale = 0`: los 708 XML son posteriores al arranque del gate.

### 3.2 Las dos tareas que sólo el full gate ejecuta

```text
:pipeline-domain:apiCheck            :pipeline-step-sdk:api:apiCheck
:pipeline-events:apiCheck            :pipeline-credentials-api:apiCheck
:koverVerify  en 24 módulos + :koverVerify (raíz)
```

Ambas pasaron. La primera es la que destapó el desfase de §3.3.

### 3.3 API/ABI — aditivo, y un desfase que no era de F1

`apiDump` sobre `pipeline-domain`: **30 líneas añadidas, 0 eliminadas**. Estrictamente aditivo, luego
**no procede STOP (2)**: no hay breaking change.

Las 30 líneas son `RecoveredStepProjection`, `RecoveredProjection` y sus dos casos — la capacidad
aditiva de §2 — más una cuarta que **no es de F1**: `TypedStepOutputKt.outcomeOf`, que faltaba en el
dump desde `cdcf68c6`, el commit que añadió esa función al código sin regenerar el dump.

Eso merece su propia línea, porque es una lección sobre qué puede y qué no puede ver la evidencia:
`apiCheck` está cableado en `check`, así que **sólo el full gate lo detecta**. Los gates focales de
F1-A, F1-B, F1-C0 y F1-C no ejecutan `check`, de modo que cuatro bloques de evidencia completa no
podían verlo, y `apiCheck` habría estado rojo desde antes de que F1 empezara.

### 3.4 Mutaciones: cuatro, con atribución medida

| mutación | objetivo | resultado |
|---|---|---|
| **M-F1-B** | `ScriptedRegistryInvoker.kt:389`, `ReplayPolicy.MEMOIZED` hardcodeado | **KILLED** — ley 4 RED, las otras 3 GREEN, atribución 1:1 |
| **M-F1-C1** | exit no-cero clasificado Failure **antes** de `returnMode` | **KILLED** — 2 RED / 15 verdes (fila `STATUS+42` vista desde 2 ángulos) |
| **M-F1-C2** | `capturedStdout == null` eliminado | **KILLED** — 2 RED / 15 verdes (fila `STDOUT+0+ausente`, 2 ángulos) |
| **M-F1-C3** | carrier estrechado a `StepOutcome` en `Materialised` | **KILLED** — 1 RED / 37 verdes, sólo tras escribir la fila que faltaba |

Restauración por `cp --` + `sha256sum -c` en las cuatro, hash pre-mutación == post-restauración, 0
residuos. **No se usó `git restore` ni `git checkout`** para ninguna. Las tres de F1-C compilan por
construcción, porque un error de compilación no es un RED: Gradle ejecuta la clase vieja.

### 3.5 El resultado que más valor dio: M-F1-C3 sobrevivió

La primera pasada dio verde en las 17 filas de la matriz; la segunda, en las tres fronteras de
consumidor. **50 tests verdes con el carrier estrechado.** La causa es estructural y la matriz no
puede detectarla: lee `row?.output` — la fila del journal — y la mutación no toca el `append`. El
valor se journalea bien y simplemente nunca llega al programa.

R14 sólo podía certificarse en la frontera del consumidor. Se escribió la fila que cruzaba
`ScriptedRegistryInvoker` (`Exited(42)` en vez del `Lost` de la fila vecina), pasó 14/14 contra el
código real, y bajo la mutación cayó con `FailureKind.ENGINE` — la forma consumer-facing de la pérdida
de R14.

Sin ese desenlace, `conformance: SATISFIED` habría sido una afirmación sobre código que funcionaba y
que nadie había intentado romper.

## 4. Fitness: ocho leyes en dos ficheros

| fichero | leyes | validada con |
|---|---|---|
| `SingleDurableAuthorityFitnessTest` | 4 | M-F1-B, atribución 1:1 |
| `RecoveredValueSpineFitnessTest` | 4 | sonda temporal: Ley 1 RED con `file:line`, las otras 3 GREEN |

Ambas leen **código sin comentarios** vía `codeOnly()`, que **borra** el bloque de comentario. No es
hipotético: **cinco** ficheros de producción contienen hoy `when (stepKey)` dentro de KDoc, y los
cinco *describen la prohibición*. Un escaneo que contara prosa mediría la documentación.

La ley 1 de `RecoveredValueSpineFitnessTest` se estrechó **después de medir**, porque la versión obvia es
falsa: `BodyExecutionEngine` construye `PluginStepId("wait-until-poll")` y `PluginStepId("retry-attempt")`
como segmentos de **identidad** durable, y `LspMetadata` conmutan sobre enums sin relación. Una ley que
falla sobre código correcto es una ley que acaba «arreglándose» borrando la ley.

**Una ley mía era falsa y quedó reemplazada:** en F1-B quise capar los call sites de `Fingerprint.compute`
a ≤3 y falló con 6, todos legítimos (constructor puro, ninguno decide). La ley real es sobre
`effectReplayPolicy.decide(`, que sí tiene exactamente un call site.

## 5. Retiradas

| pieza | líneas | por qué |
|---|---|---|
| `JournaledScriptedOperationRuntime` + reconciler + puerto | 357 | segunda autoridad durable |
| `RecoveredTerminal` | 41 | **cero** consumidores, medido antes de borrar: los únicos usos como tipo eran las cuatro auto-referencias de la propia declaración, y el `import` del test quedaba sin uso |

Un tipo semántico **nominado** en `src/main` invita a que alguien vuelva a clasificar un terminal, que
es exactamente el defecto que F1 cerró. Se retiraron también el import muerto y dos referencias KDoc
convertidas a backticks, para no dejar links colgantes.

## 6. Estado de la integración con M1 — medido, no supuesto

```text
merge-base  d0fa34e8
S4  e0500bf8   3 commits   24 ficheros
M1  5e6b41d0   5 commits   31 ficheros
ficheros en común: NINGUNO
```

No habrá conflictos textuales. Pero hay **cuatro seams indirectas** que cambian la superficie del gate:

- `v2/settings.gradle.kts` — M1 añade el módulo `pipeline-output`, de modo que **el full gate de F1 no
  lo cubre** y habrá que re-ejecutarlo tras integrar.
- `v2/pipeline-application/build.gradle.kts`, `v2/pipeline-output/build.gradle.kts`.
- `EventViewProjection.kt`, la más cercana a la frontera de S5.

Dos observaciones sobre la ejecución de M1 que **no** son correcciones, sino comprobaciones:

1. M1 **no** modifica `DomainEvent.kt`. Eliminó el process transcript de `EchoOutputCaptured` en el
   *emisor*, no en el tipo del evento. Sin cambio de schema durable, sin envelope paralelo, y por tanto
   sin conflicto con S5.
2. Degradó el modo `console` de la vista a `DEPRECATED` con la razón escrita, en vez de dejarlo
   devolviendo un subconjunto silencioso, y documentó que esa proyección **no tiene ningún llamador de
   producción** y que su KDoc afirmaba una integración que el parser nunca aceptaba.

Sobre `Unstable`: `OperationStatus.UNSTABLE` **no existe**, y `UNSTABLE` aparece en `src/main` sólo
como discriminante de nivel Step (`StepOutcome.Unstable`). Ningún consumidor **puede** inferir `Unstable`
desde `OperationStatus` hoy. La restricción pasa a ser vinculante cuando M1 o S4 introduzcan la
proyección durable, que es D-2/D-3.

## 7. Lo que F1 NO cerró

| # | por qué no aquí |
|---|---|
| **D-1** reattach no-terminal | cambiaría el resultado observable de un caso ya certificado; `DEFERRED` |
| **D-2 / D-3** terminal semántico durable de `Unstable` | tocaría esquema y serialización; `DEFERRED` |
| cualquier `OperationStatus.UNSTABLE` | D-2/D-3 |
| formato / schema / protocolo durable | F1 no lo cambia; el envoltorio de identidad se conserva intacto |
| `StageBody.Scripted` | no existe todavía; es F2 |

## 8. El PRODUCT-GATE sigue `BLOCKED_EXTERNAL`

No hay superficie de CI remota desde `754ddda0`, que retiró los cuatro workflows tras 60 runs
cancelados. «CI verde» no es una evidencia disponible en este repositorio, y declararla sería un
falso verde por construcción. Lo que sustituye a esa superficie es exactamente lo que este recibo
cifra: **el full local `./gradlew check --rerun-tasks` sobre el SHA exacto, con 4683 tests, `apiCheck`
en los cuatro módulos publicados y `koverVerify` en veinticinco**, leído por nombre y frescura de XML.

Este recibo se commitea **después** del gate, luego el SHA de integración diferirá de `e0500bf8` en un
delta exclusivamente documental. La diferencia se declara en vez de absorberse: `git diff --stat`
entre ambos debe mostrar sólo ficheros `.md`, y nada del árbol compilado queda sin certificar.

## 9. Evidencia documental acumulada por F1

```text
S4_R1_3B_REATTACH_WINDOW_EXPIRED_RECEIPT.md
S4_R1_3C_OBSERVER_FACT_AUTHORITY_MEANING_RECEIPT.md
S4_R1_F1B_SINGLE_DURABLE_AUTHORITY_RECEIPT.md
S4_R1_F1C_RECOVERED_VALUE_SPINE_RECEIPT.md
S4_R1_F1_DURABLE_SPINE_CONVERGENCE_RECEIPT.md   ← este
```
