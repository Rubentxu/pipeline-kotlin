# S4-SAST — `StepDispatchEngine.dispatch` es un artefacto de métrica, no un defecto estructural

**Estado:** `PROPOSAL` · decisión de ownership pendiente · **cero cambios de código**
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818`
**Base:** `40a4d3c7cb20fc547bcd7b797182f0c6b0867f7b`
**Objeto:** el único issue de SAST que bloquea el gate de bloque

```text
v2/pipeline-application/src/main/kotlin/…/durable/StepDispatchEngine.kt:279:17
  LongMethod — dispatch is too long (125). The maximum length is 120.
  introducido por 5fcaf2f1 — Nomeacuerdo, 2026-10-02 — PR-020 slice 2b
```

---

## 0. La conclusión

He leído el cuerpo entero de `dispatch` (líneas 279-482) y medido su composición. **El
`LongMethod` no describe complejidad: describe longitud de llamada.** El método es una espina
lineal que delega en **nueve colaboradores ya extraídos** y no contiene casi lógica propia.

Por eso la recomendación es **no refactorizar**: la extracción posible sería cosmética o
peor, contradictoria. Y hay un precedente ya establecido en este mismo módulo.

---

## 1. La medida

| Magnitud | Valor |
|---|---|
| Líneas físicas en `dispatch` | 204 |
| De ellas, comentarios | 64 |
| Vacías | 6 |
| **Código** | **~134** (detekt cuenta 125) |
| Puntos de `return Dispatched` tempranos | 5 |
| Bloques `when` sobre ADTs cerrados | 4 |
| **Colaboradores ya extraídos que invoca** | **9** |

Los nueve: `prepareDispatch`, `dispatchBody`, `reconcileInvocation`,
`recoveryInterpretation.interpret`, `typedInputPreparation.prepare`,
`stepExecutor.executeAndJournal`, `journal.get`, `cursorStore.advance`, `runtimeContext`.

### 1.1 Por qué pesan las líneas

```text
líneas de código en dispatch                      134
de las que son sólo argumentos con nombre          40   (30%)

ejemplo: stepExecutor.executeAndJournal(...)        9 líneas
         de las que son argumentos con nombre       7
```

La llamada a `executeAndJournal` ocupa nueve líneas y **siete son `nombre = valor,`**. Lo mismo
con `runtimeContext` (9), `recoveryInterpretation.interpret` (8) y `typedInputPreparation.prepare`.

**Una de cada tres líneas de código de `dispatch` es formato de argumentos.**

### 1.2 Y ese formato es deliberado, no descuido

El proyecto usa argumentos con nombre de forma sistemática en la columna durable, y eso tiene
dos justificaciones normativas, no estilísticas:

1. `StepLifecycleContext(...)`, `Request(...)`, `Fingerprint.compute(...)` construyen **identidad
   durable y la precondición de la que dependen las leyes**. Un argumento posicional que se mueve
   es un cambio silencioso de identidad.
2. ADR-0103 **D7** existe precisamente para que el cursor vea *las dos mitades del hecho* —la
   resolución y el outcome interpretado— sin que ningún brazo del interpretation engine lleve un
   flag de traversal. Los `val` nombrados de las líneas 378-384 (`opId`, `operationId`, `input`,
   `contextAfterOverlay`, `metadata`, `fingerprint`, `lifecycleContext`) **son** esa nominación.
   Compactarlos para ganar líneas deshace el trabajo que D7 hizo explícito.

Traducir: **cualquier extracción que baje de 120 líneas aquí tiene que tocar o la nominación que
D7 hizo deliberada, o el formato que la ley de identidad exige.** Ese es el coste, y es alto
para un beneficio que es puramente cosmético.

---

## 2. El precedente ya está establecido en este módulo

`v2/pipeline-application/detekt-baseline.xml` contiene **cinco `LongMethod` congelados**:

| Entrada | Qué es |
|---|---|
| `CanonicalDurableRunCoordinator.kt$suspend fun run: RunOutcome` | **el método `run` del coordinador canónico** |
| `WaitUntilEngine.kt$suspend fun execute: StepOutcome` | el ejecutor de `waitUntil` |
| `Main.kt$fun main` | el entrypoint de CLI |
| `PublishHtmlOperationsAdapter.kt$override fun publish` | un adapter |
| `UatLocal002ResumeAfterKillTest.kt$…resume after kill…` | un test de UAT |

Y en `pipeline-domain` y `pipeline-events` hay más entradas del mismo tipo.

**El proyecto ya decidió este mismo canje para sus dos métodos estructuralmente más centrales**,
que son más centrales que `dispatch`. Elegieron baselinar, no refactorizar. `dispatch` es la misma
especie: una espina de decisiones delegadas, con el grueso del cuerpo en comentarios que
explican *por qué* cada rama es la que es.

### 2.1 Y el mecanismo está diseñado exactamente para este caso

El propio wiring de detekt (`v2/build.gradle.kts:285-292`) declara la intención:

> Baseline freezes pre-existing findings; **the gate fails on ANY new issue.**
> Debt burn-down tracked in the slice receipt.

Este finding es **deuda preexistente descubierta tarde**: nació el 2 de octubre, la job de SAST
que debía detectarlo murió con `754ddda0`, y por eso no estaba congelado. Es el caso para el que
existe la baseline, no el caso para relajar la regla.

---

## 3. Las tres salidas

### Opción 1 — Baselinar `StepDispatchEngine.dispatch` **(recomendada)**

Añadir una entrada a `v2/pipeline-application/detekt-baseline.xml`, junto a las cinco
existentes. El gate vuelve a fallar **sólo ante deuda nueva**, que es su diseño.

- Coherente con 5 precedents en el mismo módulo, 2 de ellos más centrales.
- Cero cambio de comportamiento, cero refactor, riesgo nulo.
- Convierte el gate de "ROJO por un issue que nadie va a mirar" en "verde con la puerta
  realmente vigilada: deuda nueva".
- Coste: `dispatch` sigue creciendo sin que la métrica avise. Aceptable mientras su lógica viva en
  los colaboradores, que es lo que hoy ocurre.

### Opción 2 — Extraer una costura

La costura natural existe: el bloque 307-349 (derivación de `bodyContinuation`) o el 404-429
(resolución + interpretación + cursor). Pero el bloque 307-349 contiene un `return` temprano, así
que extraerlo exige **cambiar su forma a ADT** (`ShortCircuited` / `Continuation`) — es decir, un
cambio de estructura, no una limpieza, en el punto que decide el enrutado de cuerpos por owner
declarado (WU-RP-035). Y el 404-429 es la lógica de D7, que ya está extraída en
`recoveryInterpretation` y named a propósito.

Recomiendo **no** hacerlo: paga riesgo arquitectónico por una métrica.

### Opción 3 — Subir el umbral de `maxLineLength` en `detekt.yml`

Descartada. Relaja la regla para todos los módulos para acomodar un caso, y deja el
`LongMethod` sin vigilar en ninguna parte. Es exactamente el "gate verde sin resolver nada" que
esta corrección del SAST acaba de evitar.

---

## 4. Lo que este documento NO hace

- **No toca `StepDispatchEngine`.** Ni una línea. La decisión es del owner.
- **No toca la baseline.** Añadir una entrada a una baseline de producción es un acto de
  gobernanza, no una limpieza: lo hace quien responde, no quien investiga.
- **No afirma que `dispatch` sea perfecto.** Si algún día crece de verdad —más ramas, más lógica
  propia, no más argumentos— la métrica tendrá razón y habrá que extraer. Lo que digo es que hoy
  no es el caso, y que la evidencia está medida arriba.
- **No reabre la discusión de las otras 5 baselines.** Son otra decisión.

## 5. Ficheros de referencia

| fichero | qué aporta |
|---|---|
| `…/durable/StepDispatchEngine.kt:279-482` | el cuerpo medido de `dispatch` |
| `v2/pipeline-application/detekt-baseline.xml` | los 5 `LongMethod` ya congelados |
| `v2/build.gradle.kts:269-292` | política de SAST y propósito declarado de la baseline |
| `v2/config/detekt/detekt.yml:83` | `maxLineLength: 160`; el de métodos, 120 |

## 6. Estado del gate tras esta propuesta

`cd v2 && ./gradlew check --rerun-tasks` sobre `40a4d3c7` → **ROJO**, log
`s4focal-gate3.log` sha256 `fbdcd38f0dff1e7f199d897dddd7c20080a8a6ce955664ef05a5fcacef838c9f`,
`EXIT=1`, una sola línea de issue: la de `StepDispatchEngine`.

Con la opción 1, el gate quedaría verde **y** habría quemado antes las 8 unidades de deuda SAST
propia que sí eran nuevas (`0a34243` y `40a4d3c`). Ese es el intercambio que se decide aquí: no
es "no hacer nada", es congelar lo viejo después de haber quemado lo nuevo.
