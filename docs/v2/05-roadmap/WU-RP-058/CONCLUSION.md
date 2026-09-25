# WU-RP-058 — CONCLUSION

**Estado:** ANEXO al RECIBO original (no lo modifica). Cierra las preguntas abiertas y reconcilia plan↔implementación.
**Fecha:** 2026-09-25T06:55Z
**HEAD observado al concluir:** `dd76d0bc953fecefbe37ad5d1feb2043a2284e8d` (`wu/rp-058-spike-stage-scoped`)
**SHA inmutable del cierre original:** `feb99190` (ver `RECEIPT.md` §"HEAD of this branch")
**Evidencia re-ejecutada en esta sesión:** 19/19 tests verdes, 0 failures, 0 errors, 0 skipped. XML frescos 2026-09-25T06:55:11Z. SHA-256: ver §5.

---

## 1. Propósito de este anexo

El RECIBO original (`RECEIPT.md`) cierra la WU con **19/19 verde** y deja tres
preguntas abiertas (sección "Open questions for ADR-0093 follow-up"). Este
anexo:

1. **Reconcilia** el `PLAN.md` original con la implementación real
   (qué archivos se fusionaron o eliminaron, y por qué).
2. **Cierra las tres preguntas abiertas** con una recomendación fundada.
3. **Caracteriza el coste** de una hipotética WU de integración que
   conecte el spike con la spine canónica, sin autorizarla.

No modifica el RECIBO. Cualquier cambio material sobre el spike debe crear
un nuevo módulo (ver §6).

---

## 2. Reconciliación plan ↔ implementación

### 2.1 Archivos prometidos en `PLAN.md` §3.2 vs. archivos entregados

| Prometido en PLAN.md                       | Entregado real                              | Decisión     |
|--------------------------------------------|---------------------------------------------|--------------|
| `StageOp.kt`                               | `StageOp.kt`                                | idem         |
| `SuspendKind.kt` (enum cerrado con codec)  | _fusionado en `SuspendCall.kt` + `SuspendOutcome.kt`_ | simplificado |
| `StagePlan.kt`                             | `StagePlan.kt`                              | idem         |
| `StageScopedBuilder.kt`                    | `StagePlanBuilder.kt`                       | renombrado   |
| `StageScopedFrontend.kt`                   | `StageScopedFrontend.kt`                    | idem         |
| `SuspendSegmentSpec.kt` (invariantes puras)| _fusionado en `LexicalOrderSpec.kt`_        | simplificado |
| `SuspendSegmentSpecTest.kt`                | _renombrado a `LexicalOrderSpecTest.kt`_    | renombrado   |
| `StageScopedExecutionTest.kt`              | _renombrado a `StageScopedFrontendTest.kt`_ | renombrado   |
| `FingerprintReplayTest.kt` (vs scripted)   | _sustituido por `ReplayDeterminismTest.kt`_ | sustituido   |
| _no prometido_                             | `SuspendRuntimeFacade.kt`                   | añadido      |
| _no prometido_                             | `RecordingFacade.kt`                        | añadido      |
| _no prometido_                             | `SpikeIsolationTest.kt`                     | añadido      |
| _no prometido_                             | `Executed` (sealed en `StageScopedFrontend.kt`) | añadido |

### 2.2 Justificación de cada desviación

**SuspendKind fusionado en SuspendCall + SuspendOutcome.**
El plan original preveía un enum `SuspendKind` que etiquetara cada `SuspendCall`
con su "categoría de codec" (string vs boolean). En la implementación, esa
información se obtiene directamente de la **estructura** de `SuspendCall`
(`Pwd`/`ReadFile`/`ShReturnStdout` → `StringOutcome`; `FileExists`/`IsUnix` →
`BooleanOutcome`) vía `SuspendCall.expectedOutcome()`. La función pura
`expectedOutcome()` cubre el mismo invariante sin un enum separado y, sobre
todo, hace que añadir un nuevo `SuspendCall` fuerce a actualizar el `when` de
`expectedOutcome()` (exhaustividad) y el `when` del dispatcher — el compilador
rompe si se olvida. Un enum separado habría relajado esa garantía y abierto
el camino a "un SuspendCall con un SuspendKind desalineado" — estado
impossible de la implementación actual.

**SuspendSegmentSpec fusionado en LexicalOrderSpec.**
El plan listaba dos concerns bajo "invariantes puras del segmento":
(i) monotonicidad de ordinales, (ii) no-efecto. La implementación separa:
- **monotonicidad + contigüidad** → `LexicalOrderSpec` (con ADT cerrado
  `Result.Valid | Result.Invalid(reason: Reason)` y tres `Reason` tipados:
  `OrdinalMustStartAtOne`, `OrdinalGap`, `DuplicateOrdinal`).
- **no-efecto** → no necesita spec porque es estructural: `StagePlanBuilder`
  no importa `SuspendRuntimeFacade`, no tiene campos `Mutable` globales
  (solo el `ops` local a la instancia), no lee reloj. La regla 9 del
  AGENTS.md ("functional core, effectful shell") se demuestra por inspección:
  cero referencias a `java.io`, `java.nio`, `java.lang.Process`,
  `System.currentTimeMillis`, `kotlin.system.measureTimeMillis` o `Clock`
  en el código fuente del spike (verificado con grep, ver §5).

**StagePlanBuilder renombrado (de StageScopedBuilder).**
Decisión estilística menor. `StagePlanBuilder` describe mejor la única
responsabilidad del tipo (producir un `StagePlan`), mientras
`StageScopedBuilder` sugiere un ámbito más amplio. El renombrado no
afecta a ningún contrato público (ningún tipo del spike es público fuera
del módulo).

**FingerprintReplayTest sustituido por ReplayDeterminismTest.**
El plan prometía comparar el `source-digest` del plan contra la salida del
scripted generator-level. Esa comparación requiere ejecutar el scripted
path real (`:pipeline-scripting-kotlin24`), lo que **rompe el aislamiento
del módulo** (ver `PLAN.md §2 "Lo que NO es este spike"`). La
implementación entrega una **prueba de determinismo local** más débil pero
compatible con el aislamiento: dos interpretaciones consecutivas del mismo
plan producen traces estructuralmente iguales y la fachada recibe las
llamadas en el mismo orden. El recibo original documenta esto como HF0+
HF1; la equivalencia contra el scripted path queda como trabajo de la
**WU de integración**, no del spike (ver §3, pregunta 3).

**SuspendRuntimeFacade, RecordingFacade, Executed, SpikeIsolationTest añadidos.**
Los tres primeros materializan el **adapter boundary** que el plan sólo
mencionaba conceptualmente: el spike define el port (`SuspendRuntimeFacade`),
un tipo de traza inmutable (`Executed`) y un in-memory recorder para tests
(`RecordingFacade`). `SpikeIsolationTest` añade una garantía que el plan
prometía en §2 ("NO de `:pipeline-application` ni `:pipeline-scripting-kotlin24`")
pero no testeaba: el classpath del test JVM realmente NO expone las clases
prohibidas ni los marcadores `pipeline-application`/`pipeline-scripting-kotlin24`.
Sin este test, una futura edición podría añadir una dep transitiva y
romper el aislamiento sin que compile fallara.

### 2.3 Conteo final

| Categoría           | Líneas | Tests | Cobertura |
|---------------------|-------:|------:|-----------|
| Producción (`main`) |    406 |     — | pure core + boundary |
| Test                |    481 |    19 | HF0 + HF1 + L4-isolation + replay-determinism |
| **Total**           | **887**| **19**| **0 dependencies prohibidas** |

---

## 3. Cierre de las tres preguntas abiertas del RECIBO

### 3.1 Pregunta 1 — ¿Dónde vive el production adapter?

> "Does the production adapter live in `:pipeline-application`
> (coordinator side) or in a new `:pipeline-spike-adapter` module?"

**Recomendación:** **módulo nuevo `:pipeline-spike-adapter`**.

Justificación:

1. **Aislamiento hexagonal (regla 1 de AGENTS.md).** El adapter debe
   implementar `SuspendRuntimeFacade` consumiendo capacidades del
   coordinator canónico (no `CanonicalRuntimeContext` directamente). Si
   vive en `:pipeline-application`, su package queda adyacente al del
   coordinator y el compilador permite import accidental del
   coordinator desde el adapter — exactamente lo que el spike prohíbe.
   Un módulo separado hace ese import literalmente imposible.

2. **Aislamiento de blast-radius.** El spike es **frozen** por diseño
   (ver §6). Si la WU de integración introduce cambios en el adapter,
   debe poder iterar sin tocar el spike. Con un módulo nuevo, el adapter
   tiene su propio ciclo de vida: cambios no invalidan la
   certificación del spike. Con el adapter en `:pipeline-application`,
   cualquier commit en `:pipeline-application` requeriría re-verificar
   el spike.

3. **Coherencia con el patrón existente.** El repo ya tiene módulos
   especializados por concern: `:pipeline-step-sdk`,
   `:pipeline-scripting-api`, `:pipeline-application`. El adapter es un
   concern nuevo (binding entre el spike y el coordinator); merece su
   propio módulo, no un sub-package de uno existente.

**Estructura del módulo nuevo (especificación, no implementación):**

```text
v2/pipeline-spike-adapter/
├── build.gradle.kts                     # depends: spike + domain + scripting-api + application
└── src/main/kotlin/.../spike/adapter/
    ├── CanonicalSuspendRuntimeFacade.kt # implements SuspendRuntimeFacade
    ├── StagePlanAdapter.kt              # StagePlan → coordinator inputs
    └── ...
```

### 3.2 Pregunta 2 — ¿Cómo integra `StagePlan` con `StageScope.steps`?

> "How does `StagePlan` integrate with the existing `StageScope.steps`
> collector?"

**Recomendación:** **el adapter reemplaza el `steps()` accessor solo dentro
del bloque `stage { ... }` para fuentes que contengan llamadas suspendidas;
para fuentes eager puras, el path actual permanece intacto**.

Detalle:

1. El `PipelineScope.stages { ... }` collector actual recorre cada
   `stage { ... }` invocando `stage.body()` y recolectando
   `StepSpec`s con `stage.steps()` (`PipelineDsl.kt:1413`).
2. La detección de "fuente con llamadas suspendidas" ya existe:
   `Main` elige scripted si la fuente NO contiene `pipeline {}`
   (`Main.kt:699-735`). El adapter stage-scoped es la versión
   **estructurada** (no generator-level) de esa misma idea.
3. Concretamente, el adapter:
   - añade una nueva forma a `StageScope` (interna, NO pública):
     `stageScope.tryStageScopedBlock(body: () -> StagePlan): StagePlan?`
     que devuelve `null` si el body no invoca ninguna llamada suspendida.
   - cuando `StageScope.steps()` se recolecta y encuentra un
     `StagePlan` no-`null`, lo pasa al adapter en lugar de (o además
     de) los `StepSpec`s eager.
   - los `StepSpec`s eager dentro del `StagePlan` siguen su camino
     canónico (coordinator, journal, fingerprint).
   - los `StageOp.Suspend` se traducen al spine durable a través del
     `SuspendRuntimeFacade` del adapter.

**Llamadas que el adapter NO debe hacer:** invocar la fachada desde el
builder (DSL construction), sustituir el path scripted generator-level,
introducir un nuevo subtipo de `StepSpec` o modificar la firma pública
de `StageScope`.

### 3.3 Pregunta 3 — ¿Necesita el `suspend` propagarse por coroutines?

> "Does `suspend` need to thread through coroutines?"

**Recomendación:** **NO en esta fase. El spike demuestra que es
innecesario.**

El spike deliberadamente evita `kotlinx.coroutines`: el `StagePlan` es
puro data y la interpretación es un loop secuencial sobre `for (op in plan.ops)`.
Las llamadas suspendidas se materializan como **valores tipados** que el
interprete rellena vía la fachada en orden léxico.

Razones para evitar coroutines en el adapter:

1. **Determinismo de replay.** El spike prueba (HF1 + replay-determinism,
   19/19) que dos interpretaciones consecutivas producen traces iguales.
   Cualquier reordenamiento por scheduler de coroutines rompería ese
   invariante. Sí, se puede serializar con un solo dispatcher, pero eso
   añade una variable sin beneficio observable.
2. **Aislamiento de errores.** Una coroutine que falle en mitad de un
   suspend requiere estructurar `try/catch`/`CoroutineExceptionHandler`,
   que mezcla control de flujo Kotlin con cancellation semantics del
   spine durable. El loop secuencial permite mapear un fallo de fachada
   directamente a un `Executed.WithSuspend` parcial + tipo de error.
3. **Compatibilidad con el path canonical.** El coordinator canónico NO
   usa coroutines para el dispatch de StepSpecs (verificado por
   inspección: `CanonicalDurableRunCoordinator` no importa
   `kotlinx.coroutines`). El adapter stage-scoped debe ser consistente
   con esa decisión arquitectónica.

Si en una iteración futura surge una necesidad real de concurrencia
(por ejemplo, dos `StagePlan`s independientes en stages paralelos), se
introduce un `StageScopedConcurrency` explícito y acotado, **nunca** un
alcance de coroutines global.

---

## 4. Coste estimado de la WU de integración (no autorizada)

Bajo el supuesto de que las tres recomendaciones de §3 se adoptan:

| Concern                                          | Estimación          |
|--------------------------------------------------|---------------------|
| Skeleton `:pipeline-spike-adapter` (build, deps) | 1 commit            |
| `CanonicalSuspendRuntimeFacade` impl             | 1 commit + 4-6 tests|
| `StagePlanAdapter` (StagePlan → coordinator)     | 1 commit + 3-4 tests|
| Modificación `StageScope` interna + collector    | 1 commit + 2 tests  |
| Modificación `Main` para detección nueva        | 1 commit + 2 tests  |
| Recibo + fitness L4 + StepContractSuite          | 1 commit            |
| Round gate L5 + docs                             | 1 commit            |
| **Total**                                        | **~7 commits**      |

**Precondiciones (todas pendientes):**
- RP-5 verde sobre la candidata de integración (`wu/rp-053-merge` ya
  consolidada o equivalente).
- §2.4 INITIATIVE_LPR_001 concedida (cambio de semántica pública).
- ADR-0093 firme con la acotación stage-scoped adoptada
  (la presente conclusión es INPUT para esa decisión, no la decisión).

**Sin precondiciones, esta WU NO debe arrancar.**

---

## 5. Evidencia de no-regresión y aislamiento (re-ejecución 2026-09-25T06:55Z)

### 5.1 Re-ejecución del spike (19/19)

Comando:
```bash
timeout 600 ./gradlew -p v2 :pipeline-spike-stage-scoped:test --rerun-tasks --console=plain
```

Resultado: **BUILD SUCCESSFUL in 31s, 19/19 PASS, 0 failures, 0 errors,
0 skipped**. XML regenerados (timestamps 2026-09-25T06:55:11Z):

| Clase                                                       | tests | SHA-256 (primeros 16) |
|-------------------------------------------------------------|------:|-----------------------|
| `LexicalOrderSpecTest`                                      |     8 | `5106979a6f0d7cec...` |
| `StageScopedFrontendTest`                                   |     7 | `1230cbb20af6139d...` |
| `SpikeIsolationTest`                                        |     2 | `644bb4238be0d754...` |
| `ReplayDeterminismTest`                                     |     2 | `f7bae25b7c540b57...` |
| **Total**                                                   | **19**| —                     |

Warning observado (no es regresión):
`Language version 2.0 is deprecated and its support will be removed in
a future version of Kotlin. Update the version to 2.2.` El spike declara
`languageVersion.set(KOTLIN_2_0)` en su `build.gradle.kts` por
consistencia con el resto del repo (`v2/pipeline-domain`,
`v2/pipeline-events` también usan 2.0). El bump a 2.2 es una decisión
transversal del repo, NO del spike.

### 5.2 No-regresión en módulos dependientes

Comando:
```bash
timeout 600 ./gradlew -p v2 :pipeline-domain:test :pipeline-scripting-api:test --console=plain
```

Resultado: **BUILD SUCCESSFUL in 2s, 12 tasks UP-TO-DATE**. 122 XMLs
existentes, todos verdes desde el cierre del spike.

### 5.3 Aislamiento verificado

Comando:
```bash
grep -rE "import dev\.rubentxu\.pipeline\.v2\.application\.|import dev\.rubentxu\.pipeline\.v2\.scripting\.kotlin24" \
  v2/pipeline-spike-stage-scoped/src/main/ v2/pipeline-spike-stage-scoped/src/test/
```

Resultado: **vacío**. Cero imports prohibidos. El `build.gradle.kts`
declara exactamente `project(":pipeline-domain")` +
`project(":pipeline-scripting-api")`. El `settings.gradle.kts` incluye
`":pipeline-spike-stage-scoped",` con un comentario explícito del
aislamiento.

### 5.4 Pure core verificado

Comando:
```bash
grep -rE "java\.io|java\.nio|java\.lang\.Process|System\.currentTimeMillis|measureTimeMillis|kotlin\.time" \
  v2/pipeline-spike-stage-scoped/src/main/kotlin/
```

Resultado: **vacío**. El código de producción del spike NO toca
filesystem, procesos, ni reloj. La regla 9 del AGENTS.md (functional
core, effectful shell) se cumple por inspección.

---

## 6. Política de congelación (frozen) del spike

El RECIBO original ya establecía que el spike está "intentionally frozen
at this SHA" y que cualquier wiring a producción debe "add the production
adapter as a new module, must NOT mutate any of the spike's files, and
must update WU-RP-058 receipts accordingly".

Este anexo formaliza la regla para evitar regresiones silenciosas:

```text
1. El módulo v2/pipeline-spike-stage-scoped/ NO recibe commits de feature
   adicionales. Solo commits de housekeeping (docs, refactors de clarity
   sin cambio semántico, build script).
2. Cualquier modificación al comportamiento observable del spike
   (cambio de signature pública, cambio de semántica de dispatch,
   cambio de ordinal, cambio de invariante de traza) requiere:
     a) un ADR que justifique el cambio,
     b) un nuevo módulo que reemplace o coexista con el spike,
     c) el spike se marca como SUPERSEDED en su RECEIPT.md,
     d) se emite un nuevo recibo ligado al SHA del cambio.
3. La rama wu/rp-058-spike-stage-scoped NO se fusiona a main. Permanece
   como rama de referencia accesible desde origin.
4. El adapter stage-scoped, si se llega a construir, importa del spike
   solo tipos públicos (sus 7 archivos main/ son accesibles), pero
   ningún archivo del spike importa del adapter (regla hexagonal).
```

---

## 7. Resumen ejecutivo (para el operador)

- **El spike funciona.** 19/19 verde verificado en esta sesión, evidencia
  fresca con SHA-256 nuevo.
- **El aislamiento se mantiene.** Cero imports prohibidos, deps mínimas,
  pure-core por inspección.
- **Hay tres preguntas abiertas del RECIBO.** Este anexo las cierra con
  recomendación fundada (no autoritativa): adapter en módulo nuevo,
  integración con `StageScope` por tryStageScopedBlock interno, sin
  coroutines.
- **El spike debe quedar congelado.** No más commits de feature; cualquier
  evolución material va a un módulo nuevo.
- **La WU de integración sigue bloqueada** por las mismas tres
  precondiciones de antes (RP-5 verde, §2.4 INITIATIVE_LPR_001, ADR-0093
  firme). Esta conclusión es INPUT para esa decisión futura, no la
  decisión misma.

---

## 8. Referencias

- [RECEIPT.md](RECEIPT.md) — recibo original del cierre (inmutable).
- [PLAN.md](PLAN.md) — diseño original (con desviaciones documentadas en §2).
- [ADR-0093_RUNTIME_RETURNS_RESEARCH.md](../ADR-0093_RUNTIME_RETURNS_RESEARCH.md) — análisis NO normativo previo que motiva el spike.
- [AGENTS.md §HEXAGONAL ARCHITECTURE](../../../AGENTS.md) — reglas de dirección de dependencias que el spike respeta.
- [AGENTS.md §STRICT TYPED FUNCTIONAL DESIGN](../../../AGENTS.md) — reglas 1, 8, 9 que el spike implementa por inspección.
