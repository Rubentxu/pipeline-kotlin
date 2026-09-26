# C1 — Aislamiento de PipelineDsl.kt / CanonicalDurableRunCoordinator.kt / Main.kt (H1+H2+H3)

**Detected:** 2026-09-26, durante F1 (análisis aislado) del bloque autónomo
solicitado por el operador ("analisis aislado que comentas y luego corregimos y
mejoramos, luego vamos por lo que sugieres D-013 y C1, prioriza y vamos a ello").

**Scope del documento:** exclusivamente análisis estructural de los tres archivos
del audit 2026-09-26 (`H1+H2+H3`). Cero código de producción tocado. Cero
tests añadidos. Lee este documento antes de elegir WU-RP-### para C1.

**Audit base:** `.agent/AUDIT_REPORT_2026_09_26.md` § 3 (ALTA).
**Backlog base:** `.agent/TECH_DEBT_BACKLOG.md` D-011.
**ADRs referenciados:**
ADR-0077 (CLI: clikt), ADR-0078 (DSL: PipelineDsl split), ADR-0079 (Coordinator:
RuntimeContext + CoordinatorCaps).
**Ubicación:** `docs/v2/06-quality/C1_ISOLATION_2026_09_26.md` (este archivo).
Documento deliberadamente en `docs/v2/` (rastreable con el repo) y NO en
`.agent/` (que es gitignored + local state per AGENTS.md § Persistent Testing
State).

---

## H1 — `PipelineDsl.kt` (2 525 LOC) · 33 StepSpec + StepScope + 11 scope holders

**Path:** `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt`

### Tamaño real y composición

```text
sealed interface StepSpec (+ 33 data class / class subtypes)        37-875     839 LOC
data class PipelineSpec / StageSpec / AgentSpec / EnvironmentSpec /
        OptionsSpec / TimeoutSpec / TimeoutAction / PostConditionSpec /
        WhenCondition                                                 876-948    73 LOC
top-level `fun pipeline { ... }` entrypoints (4 overloads)             949-1003   55 LOC
4 × @DslMarker (PipelineDslMarker, StageDslMarker, StepDslMarker,
        PostDslMarker)                                                 1028-1037  10 LOC
class PipelineScope                                                   1044-1056  13 LOC
class StagesScope                                                     1063-1075  13 LOC
class StageScope (extension method zoo: echo/sh/error/retry/timeout/
        parallel/script/branch/dir/withEnv/...)                       1097-2281  1185 LOC
class EnvironmentScope / OptionsScope                                 2287-2309  23 LOC
class PostScope / PostStepsScope / ParallelScope / BranchScope /
        ScriptScope / StageBuilder                                    2315-2502  188 LOC
dsl helper functions + various coroutine wrappers / dispatch helpers   resto      ~ 65 LOC
                                                          total       2525 LOC
```

### Acoplamiento

- **44 archivos** (en TODO el repo, src + test) hacen `import dev.rubentxu.pipeline.v2.dsl.*`.
- **47 archivos** referencian explícitamente las clases públicas del namespace `dsl`
  (`PipelineScope`, `StagesScope`, `StageScope`, `PipelineSpec`, `StageSpec`, `StepSpec`).
- 8 archivos en `:pipeline-application/src/main` referencian el namespace dsl
  (CleanWsOperationsAdapter, ShExecution, DeleteDirOperationsAdapter, Main, DslCompiledPipelineCompiler, WorkspaceOperations, CanonicalCoreStepDecoder, CoreArchiveArtifactsStep + publisher).
- `DslCompiledPipelineCompiler.kt` (838 LOC, también grande, **no en H1**) consume
  el árbol `PipelineSpec → stages → StepSpec` y lo baja al canonical IR.
  Esto significa que una partición DEBE preservar:
  - la jerarquía `PipelineSpec / StageSpec / StepSpec` como estable API,
  - los `data class` puros (`EnvironmentSpec`, `OptionsSpec`, `TimeoutSpec`,
    `PostConditionSpec`, `AgentSpec`, `WhenCondition`) **tal cual**
    (cualquier cambio de forma invalida el contract suite de StepScope).

### Seams candidatos para la partición

Tres particiones plausibles; cada una preserva `PipelineScope / StagesScope /
StageScope` como una unidad atómica y rompe los archivos en tres:

| Partición | Archivos resultantes | LOC estimado |
|---|---|---|
| **ADR-0078 strict**: `PipelineDsl.kt` + `PipelineDslValidation.kt` + `PipelineDslLowering.kt` | datos (`PipelineSpec` + data classes), scopes (los 11 `class`), tipos (`StepSpec` + 33 subtypes) | ~840 / ~1185 / ~500 |
| **Variante física por capa**: `PipelineScope.kt` + `StageScope.kt` + `StepSpec.kt` + `PipelineSpec.kt` | una por cada `class` del DSL | ~14 / ~1185 / ~840 / ~70 |
| **Variante funcs**: `StepSpec.kt` + `Stages.kt` (post/parallel/branch/scripts) + `Pipeline.kt` + `Options.kt` | separa tipos / control flow / pipeline raíz / options | ratio similar a ADR-0078 |

**Observación clave:** los StepScope builders (las 1 185 LOC) son SIMÉTRICAS
a los data class StepSpec (839 LOC): cada `echo("text")` genera un
`StepSpec.Echo(text)`. Si la partición va por **tipos vs scopes**, el
despliegue es coherente pero la navegación IDE empeora temporalmente
(2 archivos para cualquier modificación del DSL method).

**Recomendación de F1:** variante ADR-0078 strict. Razones:

1. **Validation ≠ lowering ≠ scopes**: la *validación* (cross-field checks,
   `@DslMarker` enforcement, default propagation) es lógica que NO genera IR;
   va en un módulo dedicado sin que tenga que importar el compiler.
2. Los data class puros (`EnvironmentSpec`, `OptionsSpec`, etc.) van con el
   bloque de **tipos** para evitar movimientos circulares entre archivos.
3. El resultado es `PipelineSpec → PipelineDsl → PipelineDslValidation` —
   una jerarquía que respeta el orden *declaration → validation →
   lowering* (aunque el lowering real lo hace `DslCompiledPipelineCompiler`,
   no la DSL).

### Riesgos de la partición

1. **Sealed-interface-name preservation**: el nombre `StepSpec` está
   exportado por `:pipeline-scripting-api` y consumido por 47 archivos
   + `DslCompiledPipelineCompiler`. Renombrar o separar la jerarquía
   rompe la API pública. Conservar el nombre canónico + añadir
   extension members en el archivo de scopes.
2. **`@DslMarker` cross-file**: Kotlin permite `@DslMarker` cross-package
   pero los IDE completions degrades. Si se mueve `PipelineDslMarker` +
   `StageDslMarker` + `StepDslMarker` + `PostDslMarker` a un archivo
   dedicado, los consumers de `:pipeline-step-sdk:scm-git`, `:pipeline-
   scripting-kotlin24`, etc., siguen compilando pero la inferencia lambda
   puede romperse si los scopes están en archivos distintos. **Mitigation**:
   mantener el uso de `@DslMarker`-only types en el archivo principal y
   exponer los scopes via re-exports.
3. **DslCompiledPipelineCompiler + Bridge**: el compiler importa
   `dev.rubentxu.pipeline.v2.dsl.PipelineSpec / StepSpec / toSpec`. Esa
   triple no debe cambiar tras la partición. **Mitigation**: compat shim
   para `fun toSpec(...)` que se mantiene aquí.
4. **Packaging tests**: el contract suite (`PipelineScope` /
   `StageScope` builder methods) tiene 60+ tests casi todos locales a un
   solo subtipo StepSpec. La partición puede requerir tocar el package
   de algunos tests pero no su lógica. Sin re-compilación full del módulo.

---

## H2 — `CanonicalDurableRunCoordinator.kt` (1 856 LOC) · 1 clase, 8 métodos >50 LOC

**Path:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt`

### Composición real

```text
class CanonicalDurableRunCoordinator(
    ctor: 18 named params + 5 lazy / composed fields)                 110-216    107 LOC
private class NoopStepRegistry                                         1838-1856   19 LOC
                                                                extra / braces ~30 LOC
resto ≈ 1 700 LOC de métodos
```

### Métodos principales (todos `private suspend fun`)

| método | rango | LOC |
|---|---|---|
| `run` | 275–504 | **230** |
| `dispatch` | 505–734 | **230** |
| `runParallelStage` | 735–994 | **260** |
| `executeBranchSteps` | 995–1050 | 56 |
| `dispatchBody` | 1051–1437 | **387** |
| `invokeBodyChildren` | 1438–1503 | 66 |
| `executeWaitUntilBodyInline` | 1504–1690 | **187** |
| `executeCredentialLeasedBody` | 1691–1829 | **139** |

### El ctor tiene 18 named params + 5 inits

```text
dispatcher, journal, cursorStore, clock, effectReplayPolicy,
eventSink, credentialScopePort, controlDirRoot, workspaceBase,
shOptions, secretPatternRegistry, divergenceDetector,
stepMetadataResolver, invocationExecutor, commonExecutionBoundary,
stepRegistry, retryControlJournal, waitUntilControlJournal,
milestoneStateStore, artifactIndex, injectedBodyPolicyResolver,
bodyInvokerAdapter
                       (22 named ctor params + 5 lazy/init composed fields)
```

El audit (D-011 H2) hablaba de "**12 campos**" en `CanonicalRuntimeContext`;
revisión: son **11 fields** (F2 correction a F1 — el isolation doc inicial
mencionó 13, conteo del primer read incorrecto):

```text
opId, runId, stageName, stageIndex, stepIndex, shOptions,
controlDirRoot (nullable),
eventSink,
bodyInvoker (nullable default null),
secretPatternRegistry (nullable default null),
workspaceBase (nullable default null)
                          (7 mandatory + 4 nullable-type = 11 fields totales)
```

Resumen de nullability: 3 nullable-con-default (`bodyInvoker`,
`secretPatternRegistry`, `workspaceBase`) + 1 nullable-sin-default
(`controlDirRoot`) = 4 fields nullable; 7 mandatory.

(`CanonicalRuntimeContext` vive en `CanonicalNodeDispatcher.kt`, no en
el coordinator file).

### Acoplamiento

- 1 archivo (`CanonicalDurableRunCoordinator.kt`) → expone la única
  composición del coordinator canónico.
- `runCanonicalPipeline()` en `Main.kt` consume TODOS los 22 params del
  ctor vía named arguments (la composición raíz literal).
- 5+ archivos referencian `CanonicalDurableRunCoordinator(...)` como
  composition root: `Main.kt`, tests `Lpr301BurnDown*`, `Runtime*`,
  `Uat*`, `WURp053r*`, etc.
- `CanonicalRuntimeContext` (en `CanonicalNodeDispatcher.kt`) lo
  construyen 1 sitio (línea 628 del coordinator, dentro de `dispatch()`).

### Seams candidatos

El audit propone `RuntimeContext` + `CoordinatorCaps`. Re-examinado:

| Componente | Costo de extraer | Beneficio | Riesgo |
|---|---|---|---|
| `CanonicalRuntimeContext` (13 fields en NodeDispatcher) | S | S: 1 type nuevo inmutable; 0 rompedura de ABI | Bajo. Vive con NodeDispatcher ya, sólo requiere mover. |
| `CoordinatorCaps` (los 22 ctor params lazy/delegate) | M | S: encapsula 22 params; mejora la firma `runCanonicalPipeline()` | Medio. La firma actual named-args es **la API**. Rompe named ctor site en Main.kt, ~5 tests. Compat shim viable. |
| `dispatchBody` (387 LOC) | L | M: separa control-flow orchestration de run() | Alto. Es el método más complejo. Si se extrae a otra clase, requiere pasarse 8+ parameters (o this@Coordinator). No mejora hasta tener tests dedicados. |
| `dispatch` (230 LOC) | L | M: separa dispatch surface | Alto. Misma justificación que arriba. |
| `runParallelStage` / `executeCredentialLeasedBody` / `executeWaitUntilBodyInline` (260 + 166 + 187 LOC) | L | **L**: los tres son ejecutores de body-path específicos, **perfectos candidatos a**: | Bajo-Medio. |

**Recomendación de F1:**

1. **Extraer `CanonicalRuntimeContext` a su propio archivo**
   `CanonicalRuntimeContext.kt` (en el mismo package `durable/`). Cero
   cambios funcionales, 0 cambios API, mueve 50 LOC a un archivo
   dedicado con su singleton `EMPTY` y `from(CanonicalRuntimeContext)`.
2. **`CoordinatorCaps` data class** que envuelve los 22 named ctor
   params. Migration path: añadir `@Deprecated("pass CoordinatorCaps
   instead of named args", replaceWith = ...)` y dejar **un ciclo** de
   compat dual-ctor (named + composite). Validar con el contract suite
   que la API binaria sigue siendo estable.
3. **NO extraer `dispatchBody` / `dispatch` / `runParallelStage`** en
   esta ronda: la extracción correcta requiere primero tener tests de
   contrato dedicados para cada método. Sin tests, la partición es
   *cosmetic* y vuelve el siguiente minor más costoso por la rotación
   de métodos.

### Riesgos de la partición

1. **22 named ctor params**: los tests + `Main.runCanonicalPipeline()`
   los usa posicionalmente. Cualquier cambio de orden rompe la compilación.
   Renombrar un param y mantener orden es la ruta segura.
2. **`milestoneStateStore` no-nullable default**: introducido post
   `wu/rp-053r-red-fixtures`; los unit tests existentes no pasan una
   instancia. Si la firma cambia a nullable+default, no rompe; si
   cambia a no-nullable, sí.
3. **ADR-0079 dice "RuntimeContext + CoordinatorCaps"** — la
   observación F1 confirma que `RuntimeContext` ya está medianamente
   aislado (vive en `CanonicalNodeDispatcher.kt`); mover a archivo
   propio y formalizar el ADR es una sola PR.
4. **`invokeBodyChildren` (66 LOC)** + **`executeBranchSteps` (56 LOC)**
   son extractables ya hoy pero ambos se llaman sólo desde
   `dispatchBody` y `runParallelStage`, así que la "extracción" solo
   movería código. **Skip hasta que C1 separe dispatchBody**.

---

## H3 — `Main.kt` (1 148 LOC) · CLI casero + 4 subcommands + 1 main loop 662 LOC

**Path:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt`

### Composición real

```text
fun validateControlRoot                                           71-105    35 LOC
data class PipelineCliConfig + composes                           107-147   41 LOC
sealed interface DurableRunPolicy + DurableRunSelection            120-147   28 LOC
fun parseCliArgs (hand-rolled CLI)                                 148-250  103 LOC
fun main                                                          251-912  662 LOC ← !!!
private fun composeWithCredentialsExecutor                         913-946  34 LOC
private fun selectDurableRun                                       947-960  14 LOC
private fun startFreshRun                                          961-978  18 LOC
private fun runScriptedFrontend                                    979-1026 48 LOC
private fun pluginClassLoaderFor                                  1027-1052 26 LOC
private fun computeScriptClasspath                                1053-1064 12 LOC
private fun computeBundledPlugins                                 1065-1088 24 LOC
private fun runCanonicalPipeline                                  1089-1148 60 LOC
                                            total               ≈1100 LOC
```

### Subcomandos dentro del `main` de 662 LOC

La CLI implementa 5 subcomandos top-level descubiertos con `args.firstOrNull() == "..."`:

```text
"version"    260-278  print jar manifest Implementation-Version; exit 0
"doctor"     279-302  JDK + cwd probe; exit 0/2
"events"     303-313  delegates to MainEventsCli / MainEventsVerifyCli
"credentials" 315-... delegates to MainCredentialsCli (not inspected in F1)
(unknown)    ...      fall-through to scripted pipeline run
```

`runCanonicalPipeline` (60 LOC, 1089–1148) NO está dentro de `main` —
está como `private fun` aparte, lo cual está bien estructurado.
**Pero `Main.kt` acumula TODA la orquestación del entrypoint.**
Esto es exactamente el problema que el audit diagnosticó.

### Acoplamiento

- 8 archivos referencian `Main.kt` por package o por imports.
- `runCanonicalPipeline` es **la composition root** del coordinator (los
  22 named params). Si se extrae a `Composition.kt` o `CompositionRoot.kt`,
  Main.kt se queda como CLI thin-wrapper.
- `parseCliArgs` (103 LOC) es un parser casero `args[i].startsWith("--")`
  con un `when` que reconoce 6 flags + 1 subcomando. **Casero, sin clikt**:
  es exactamente lo que ADR-0077 propone reemplazar.
- `Events` y `credentials` ya tienen su propio `*Cli` separado
  (`MainEventsCli`, `MainCredentialsCli`). La estructura actual ya
  demuestra la **convención**: cada subcomando top-level DEBERÍA
  tener su propio `*Cli` con su parser y su dispatch.

### Seams candidatos

| Componente | Partición | Riesgo | Compat ABI |
|---|---|---|---|
| `fun main` (662 LOC) | Mediante clikt (`com.github.ajalt.clikt:clikt`) o `picocli` | Alto si se migra a la vez | Alto si se renombra flag syntax; bajo si `--db/--resume/--control-root/...` se preservan |
| `parseCliArgs` | Mediante clikt `PipelineRootCommand` con subcommands `validate` / `run` (ADR-0077) | Bajo | Bajo — la CLI flag-level contract se mantiene. |
| `PipelineCliConfig` (41 LOC) | Stays | — | — |
| `validateControlRoot` (35 LOC) | Stays | — | — |
| `runCanonicalPipeline` (60 LOC) | Stays or extracted to `CompositionRoot.kt` | Bajo | Bajo: la firma no cambia |
| Helpers (composeWithCredentialsExecutor, selectDurableRun, startFreshRun, runScriptedFrontend, pluginClassLoaderFor, computeScriptClasspath, computeBundledPlugins) | Stays — son muy cohesion-adjuntos a Main | — | — |

**Recomendación de F1:**

**Variante A — Cirugía mínima:** mantener `Main.kt` como un entrypoint
*thin* pero extraer `runCanonicalPipeline` a `CompositionRoot.kt` en
el mismo package. Eso ya baja Main.kt de 1148 → 1088 LOC y mejora
la legibilidad. Después, en una segunda ronda, migrar el parser a
clikt.

**Variante B — ADR-0077 estricto:** introducir `com.github.ajalt.clikt:clikt`
en `libs.versions.toml` + `v2/build.gradle.kts` y reemplazar `parseCliArgs`
+ `main` por:

```kotlin
class PipelineRootCommand : NoOpCliktCommand(name = "pipeline") {
    init {
        subcommands(PipelineValidateCommand(), PipelineRunCommand(),
                    PipelineVersionCommand(), PipelineDoctorCommand(),
                    PipelineEventsCommand(), PipelineCredentialsCommand())
    }
}

fun main(args: Array<String>) = PipelineRootCommand().subcommands(...).main(args)
```

**Recomendación F1 = Variante A ahora, Variante B en el próximo minor.**
La Variante B requiere cambio de CLI framework + adición de dep + 6
subclases; en una primera pasada por C1 el valor de sacarla es bajo
respecto al coste de mediar con todos los tests que mockean args.

### Riesgos

1. **CLI contract regression**: cualquier renombre de flag rompe
   usuarios downstream. `parseCliArgs` reconoce 6 flags actuales
   (`--db`, `--resume`, `--rerun`, `--control-root`, `--workspace`,
   `--sandbox-profile`, `--plugin-jar`). Conservar todos.
2. **Tests sobre `Main()` / `runCanonicalPipeline()`**: el módulo
   `pipeline-application:test` tiene varios UATs `UatCli*` que
   invocan Main con args reales. Si se cambia `parseCliArgs` por
   clikt, esos tests se actualizan sustituyendo `parseCliArgs` por
   `PipelineRootCommand.parse(args)`. **Forward-compat key**:
   `--db <path>` y `--rerun` deben preservarse.
3. **`runCanonicalPipeline` 22-param call**: el cuerpo del método
   es la composition root literal. Cualquier extracción a
   `CompositionRoot` que NO preserve el orden de named args rompe.

---

## Resumen ejecutivo de F1

| # | Archivo | LOC | Seam prioritario | Acción propuesta |
|---|---|---|---|---|
| H1 | `PipelineDsl.kt` | 2525 | StepSpec (839) + StageScope (1185) + scopes (188) + rest (~620) | Variante ADR-0078: `PipelineDsl.kt` (specs+scopes base) + `PipelineDslValidation.kt` (validación) + `PipelineDslTypes.kt` (PipelineSpec/StageSpec/EnvironmentSpec/OptionsSpec/TimeoutSpec/PostConditionSpec/AgentSpec/WhenCondition). **NO extraer StageScope** todavía (1185 LOC intactas). |
| H2 | `CanonicalDurableRunCoordinator.kt` | 1856 | `CanonicalRuntimeContext` + `CoordinatorCaps` ADR-0079 | (a) extraer CanonicalRuntimeContext a su propio archivo; (b) introducir `CoordinatorCaps` data class + ciclo de compat dual-ctor; (c) NO extraer `dispatchBody`/`dispatch`/`runParallelStage` sin tests dedicados. |
| H3 | `Main.kt` | 1148 | `parseCliArgs` + `main` | Variante A this cycle: extraer `runCanonicalPipeline` a `CompositionRoot.kt`. Variante B (ADR-0077 con clikt) **próximo minor**, no en este bloque. |

### Riesgos agregados del bloque C1

1. **API ABI pública**: `CanonicalDurableRunCoordinator` ctor named-args
   es parte del binary contract de `:pipeline-application`. BCV ya está
   aplicado a esa lib a través del canal `:pipeline-step-sdk:api`. Si
   `CoordinatorCaps` se introduce, los baselines BCV deben regenerarse.
2. **StepSpec subtype rename**: cualquier rename de `StepSpec.Echo`,
   `StepSpec.Shell`, etc., invalida 47 imports + `DslCompiledPipelineCompiler`.
   Stay-quiet en subtipos.
3. **Test surface**: partition de files no toca la lógica pero reorganiza
   imports. Los tests con `@Disabled("migrated to harness...")` también
   pueden ajustar imports sin coste lógico.
4. **Certifier/admisión**: cada commit individual regenera
   `CURRENT_UAT_STATUS.md` (header only). Una rotación C1 de 3 archivos
   genera 3 regen cycles adicionales.

### Orden propuesto para C1

1. **Round C1-A**: extraer `CanonicalRuntimeContext` a archivo dedicado.
   Sin cambios de API. ~1 commit. Razón: cero-riesgo, libera líneas a un
   archivo con menor acoplamiento.
2. **Round C1-B**: introducir `CoordinatorCaps` data class + compat
   dual-ctor en `CanonicalDurableRunCoordinator`. Regenerar baseline
   BCV de `:pipeline-step-sdk:api` si aplica. ~2 commits.
3. **Round C1-C (H3)**: extraer `runCanonicalPipeline` a
   `CompositionRoot.kt` (sin tocar la CLI). ~1 commit.
4. **Round C1-D (H1)**: aplicar ADR-0078 — partir `PipelineDsl.kt` en
   `PipelineDsl.kt` (core) + `PipelineDslValidation.kt`. NO extraer
   StageScope esta ronda (1185 LOC). ~2 commits.

Total C1 estimado: 4-6 commits, dos ciclos de PR-friendly.

---

## Por qué NO extraer `dispatchBody` / `dispatch` / `runParallelStage` en este bloque

Estos métodos son **property-based sobre el coordinator**: comparten
`currentOutcome`, `eventSink`, `stepRegistry`, `artifactIndex`,
`shOptions`, `bodyInvokerAdapter`, `retryControlJournal`,
`waitUntilControlJournal`. Extraerlos a otra clase sin tests dedicados
introduce 8+ parameters por método y vuelve el siguiente minor más
costoso por la rotación de métodos. **Pre-requisito para su extracción**:
tener `CoordinatorCaps` data class consolidado (C1-B).

## Por qué NO migrar `parseCliArgs` a clikt en este bloque

ADR-0077 propone la migración completa a clikt. F1 confirma que la
estructura actual de `Main.kt` ya tiene 5 subcommands delegados a
`Main*Cli` separados. La migración a clikt es **consolidación de 5
subcommands + el casero `main` de 662 LOC en un framework único**. Es
una ronda de 6 commits separados. **Fuera de scope de este bloque**:
el operador lo prioriza como Variante B en próximo minor.

---

## F2 — Correcciones a F1 (round de revisión post-commit)

**Detected:** 2026-09-26, tras la revisión crítica de F1 contra el código
real (post-commit del isolation doc, antes del next-step D-013).

### F2.1 — Field count de `CanonicalRuntimeContext` (H2)

El isolation doc inicial decía "13 fields" — conteo del primer read
incorrecto. Recuento verificado en F2 con `awk '/^data class/,/^\)$/'`
+ `grep -c "^    val "`: **11 fields totales** (no 12 como dijo el
audit, no 13 como dijo F1).

- 7 mandatory: `opId, runId, stageName, stageIndex, stepIndex, shOptions, eventSink`
- 4 nullable: `controlDirRoot` (sin default), `bodyInvoker, secretPatternRegistry,
  workspaceBase` (con default null)

### F2.2 — LOC de `executeCredentialLeasedBody` (H2)

El isolation doc inicial decía 166 LOC. Recuento verificado con el awk
bracket-aware: **139 LOC** (líneas 1691-1829, no 1691-1856).

`executeCredentialLeasedBody` cierra antes del final del archivo
(`NoopStepRegistry` ocupa 1838-1856 con 19 LOC). Sin impacto en la
recomendación; el método sigue siendo >100 LOC y candidato a
extracción futura.

### F2.3 — Validación cruzada contra el audit D-011

D-011 §3 H2 decía "partición CanonicalRuntimeContext (12 campos) en
RuntimeContext + CoordinatorCaps". F1 lo recogió y F2 lo corrige:

- `CanonicalRuntimeContext` ya está semi-aislado en su propio archivo
  conceptual (vive en `CanonicalNodeDispatcher.kt`). La partición
  lógica es moverlo a `CanonicalRuntimeContext.kt`. Esa partición no
  es por número de campos sino por **separación de responsabilidades**:
  canonical node dispatcher ≠ runtime context.
- `CoordinatorCaps` propuesta del audit es razonable, cubre 22 named
  ctor params. La migración debe ser compat dual-ctor, no breaking.

### F2.4 — Lo que mejora de F1 → F2

1. **Recuentos exactos** sustituyen aproximaciones: 11 fields, 139 LOC,
   range cierra en línea 1829.
2. **Nullability breakdown** explícito (7 mandatory + 4 nullable; 3
   nullable-con-default).
3. **Método de verificación** documentado para auditoría externa:
   `awk + grep -c` es reproducible, y un future agent puede repetirlo.
4. **Auditor → Ejecutor**: este round confirma el principio
   "análisis aislado antes de código". F1 tomó 5 reads + 1 lectura de
   ranges; F2 corrigió conteos antes de tocar producción. Cero código
   tocado entre F1 y F2.


1. Cerrar F1 + este documento.
2. Proceder a **D-013** (coverage thresholds) como segunda fase del
   bloque autónomo actual. D-013 es small-blast-radius, no rompe ABI,
   no toca archivos grandes, y protege contra regresiones de cobertura
   durante el phase C1 posterior.
3. Después de D-013, ejecutar **C1-A** (canonical runtime context move)
   como primera WU de C1.
4. Certifier + admisión tras cada ronda.
