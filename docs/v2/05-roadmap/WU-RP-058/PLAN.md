# WU-RP-058 — Spike stage-scoped runtime returns (ADR-0093 §9)

**Estado:** PLAN propuesto. NO implementación hasta firma del operador.
**Rama:** `wu/rp-058-spike-stage-scoped` (desde `wu/rp-053-merge @ 64865c9c`).
**ADR autoridad:** ADR-0093 (B / suspend structured DSL). ADR-0006 (rechazo de CPS).
**Iniciativa:** LPR-001.

## 1. Objetivo

Demostrar que el segmento `suspend` dentro de un `stage { ... }` puede:
1. Construir pasos eager (`StepSpec` declarativos) ANTES y DESPUÉS de llamadas suspendidas
   conservando el **orden léxico** de la fuente.
2. Recibir valores runtime tipados (`String`, `Boolean`, etc.) a través del
   `ScriptedStepFacade` ya certificado (`pwd`, `readFile`, `fileExists`, `shReturnStdout`,
   `isUnix`) sin tocar el seam público.
3. Producir una **única** secuencia estructural que el canonical coordinator pueda
   ejecutar de forma durable (journal, fingerprint, replay), exactamente igual que
   si cada elemento hubiera sido emitido por la frontend scripted generator-level.

## 2. Lo que NO es este spike

- **No** toca `PipelineDsl.kt` ni `Main.kt`. La DSL pública sigue siendo eager.
- **No** añade `StepSpec` externa ni un subtipo concreto.
- **No** introduce un switch por `StepKey` en ningún dispatcher central.
- **No** modifica contratos públicos (mismas firmas Jenkins, misma autoridad).
- **No** mueve ni reescribe el corpus de `v2/compatibility/*.pipeline.kts`.
- **No** reemplaza el path scripted generator-level; lo complementa con un módulo
  separado que puede compararse contra él.

## 3. Forma del spike

### 3.1 Módulo aislado

- `v2/pipeline-spike-stage-scoped/` (nuevo).
- Depende **solo** de `:pipeline-domain` y `:pipeline-scripting-api`. NO de
  `:pipeline-application` ni `:pipeline-scripting-kotlin24`.
- Anadido en `v2/settings.gradle.kts` (línea 47-50) con comentario explicando el
  aislamiento.

### 3.2 Componentes internos

```text
v2/pipeline-spike-stage-scoped/
├── build.gradle.kts
├── src/main/kotlin/dev/rubentxu/pipeline/v2/spike/stagescoped/
│   ├── StageOp.kt                   // sealed ADT: Eager | Suspend (payload tipado)
│   ├── SuspendKind.kt               // enum cerrado con significado del codec
│   ├── StagePlan.kt                 // data class total; init valida invariantes puras
│   ├── StageScopedBuilder.kt        // pure: input → StagePlan (sin I/O, sin clock, sin fs)
│   ├── StageScopedFrontend.kt       // intérprete: StagePlan × ScriptedStepFacade → outcome
│   └── SuspendSegmentSpec.kt        // invariantes puras del segmento (no-efecto)
└── src/test/kotlin/.../stagescoped/
    ├── SuspendSegmentSpecTest.kt    // invariantes puras (pure core)
    ├── StageScopedExecutionTest.kt  // intérprete contra ScriptedStepFacade fake
    └── FingerprintReplayTest.kt     // source-digest del plan vs scripted generator-level
```

### 3.3 Tipo de dato central (estilo Haskell)

```kotlin
// sealed ADT — payload por caso, sin boolean+nullable, sin String mode.
sealed interface StageOp {
    data class Eager(val spec: StepSpec) : StageOp
    data class Suspend(
        val ordinal: Int,                         // monotono, contiguo, dentro del stage
        val call: SuspendCall,                    // payload tipado, no Map<String,Any?>
    ) : StageOp
}

// Haskell-inspired: cada caso lleva lo que necesita, nada más.
sealed interface SuspendCall {
    data class Pwd(val tmp: Boolean) : SuspendCall
    data class ReadFile(val file: String) : SuspendCall
    data class FileExists(val file: String) : SuspendCall
    data class ShReturnStdout(val script: String, val encoding: String?) : SuspendCall
    data object IsUnix : SuspendCall
}

// total: si añades un caso, el compilador rompe los whens exhaustivos.
data class StagePlan(
    val stageName: String,
    val ops: List<StageOp>,
) {
    init {
        // Funciones puras de validación; SIN lanzar excepciones para control de flujo.
        require(stageName.isNotBlank()) { "stageName must not be blank" }
        require(ops.isNotEmpty())      { "StagePlan must contain at least one op" }
        // Invariante 1: ordinales monotonos y contiguos para los Suspend.
        // Invariante 2: orden léxico preservado (Eager antes/después de Suspend
        //                respeta la posición en la fuente).
        // Invariante 3: ningún Suspend con (callSite, ordinal) duplicado.
        LexicalOrderSpec.check(ops)               // total: devuelve Result, no throw
    }
}
```

### 3.4 Frontend stage-scoped (intérprete; pure decision → impure interpreter)

```kotlin
// Decisión pura: dado un SuspendCall, devuelve el valor tipado esperado.
// CERO side effects, CERO facade. Sólo valida totalness y aridad.
sealed interface SuspendOutcome {
    data class StringOutcome(val value: String) : SuspendOutcome
    data class BooleanOutcome(val value: Boolean) : SuspendOutcome
}

fun SuspendCall.expectedOutcome(): SuspendOutcome = when (this) {
    is SuspendCall.Pwd           -> SuspendOutcome.StringOutcome("")     // tipado; valor en runtime
    is SuspendCall.ReadFile      -> SuspendOutcome.StringOutcome("")
    is SuspendCall.ShReturnStdout -> SuspendOutcome.StringOutcome("")
    is SuspendCall.FileExists    -> SuspendOutcome.BooleanOutcome(false)
    is SuspendCall.IsUnix        -> SuspendOutcome.BooleanOutcome(false)
}

// Intérprete: pure decision + facade (única fuente de I/O).
// when exhaustivo: añadir un SuspendCall sin actualizar este when es error de compilación.
suspend fun ScriptedStepFacade.invoke(call: SuspendCall, callSite: ScriptedCallSiteId): Any = when (call) {
    is SuspendCall.Pwd            -> if (call.tmp) pwd(callSite, tmp = true) else pwd(callSite)
    is SuspendCall.ReadFile       -> readFile(callSite, call.file)
    is SuspendCall.FileExists     -> fileExists(callSite, call.file)
    is SuspendCall.ShReturnStdout -> shReturnStdout(callSite, call.script, call.encoding)
    is SuspendCall.IsUnix         -> isUnix(callSite)
}

class StageScopedFrontend {
    // output ADT — sin MutableList<Any> para acumular tipos imposibles.
    sealed interface ExecuteOutcome {
        data class Completed(val items: List<Executed>) : ExecuteOutcome
        // Reject tipado (no excepción) cuando el plan viola una invariante.
        data class Rejected(val reason: RejectReason) : ExecuteOutcome
    }
    sealed interface Executed {
        data class Eager(val spec: StepSpec) : Executed
        data class SuspendCallDone(val ordinal: Int, val value: Any) : Executed
    }
    sealed interface RejectReason {
        data object PlanRejected : RejectReason          // invariante rota (fail-closed)
        // añadir motivo nuevo requiere actualizar el when exhaustivo aguas abajo
    }

    suspend fun execute(plan: StagePlan, facade: ScriptedStepFacade): ExecuteOutcome {
        // Fail-closed: revalidamos invariantes aunque el builder ya lo haya hecho.
        val v = LexicalOrderSpec.check(plan.ops)
        if (v is LexicalOrderSpec.Invalid) return ExecuteOutcome.Rejected(RejectReason.PlanRejected)
        val items = mutableListOf<Executed>()
        for (op in plan.ops) when (op) {
            is StageOp.Eager   -> items += Executed.Eager(op.spec)
            is StageOp.Suspend -> items += Executed.SuspendCallDone(
                ordinal = op.ordinal,
                value   = facade.invoke(op.call, callSiteFromOrdinal(op.ordinal)),
            )
        }
        return ExecuteOutcome.Completed(items)
    }
}
```

El **módulo** no ejecuta efectos: el `ScriptedStepFacade` es el único punto donde
ocurre I/O, y se inyecta. La parte pura (`StagePlan`, `LexicalOrderSpec`,
`SuspendCall.expectedOutcome()`) es testeable sin construir nada del coordinator.

## 4. Tres patrones Groovy que el spike debe ejecutar

El spike expone un builder declarativo tipado. La **construcción** no ejecuta
I/O (puro); el **intérprete** ejecuta contra el facade inyectado. Los tests
comprueban `ExecuteOutcome` (ADT) y `SuspendOutcome`, no strings.

```kotlin
@Test fun pattern_a_pwd_shStdout_branch() {
    // given: plan con eager, suspend, eager — orden léxico preservado.
    val plan = StageScopedBuilder.stage("build") {
        StageOp.Eager(StepSpec.Shell("setup"))
        suspendScope {
            // la rama `if` se evalúa en el builder; sólo se materializa el
            // SuspendCall que efectivamente se ejecute. (builder = pure.)
        }
        StageOp.Suspend(ordinal = 1, call = SuspendCall.Pwd(tmp = false))
        StageOp.Suspend(ordinal = 2, call = SuspendCall.ShReturnStdout(
            script = "git describe --always", encoding = null,
        ))
        StageOp.Eager(StepSpec.Shell("teardown"))
    }
    // when: intérprete contra facade fake que registra invocaciones.
    val facade = RecordingFacade().apply { pwdReturns = "/work"; shStdoutReturns = "v1" }
    val outcome = StageScopedFrontend().execute(plan, facade)
    // then: ADT de resultado con items en orden léxico.
    assertEquals(
        ExecuteOutcome.Completed(items = listOf(
            Executed.Eager(StepSpec.Shell("setup")),
            Executed.SuspendCallDone(ordinal = 1, value = "/work"),
            Executed.SuspendCallDone(ordinal = 2, value = "v1"),
            Executed.Eager(StepSpec.Shell("teardown")),
        )),
        outcome,
    )
}
```

(Forma equivalente para los patrones (b) `readFile + fileExists` y (c) `shStdout`
dentro de un `withEnv` lógico; el spike los cubre con la misma técnica.)

## 5. Invariantes verificables

| # | Invariante | Test |
|---|---|---|
| 1 | Orden léxico eager/suspend dentro de un stage preservado | `LexicalOrderTest` |
| 2 | Ordinales monotonos, contiguos, únicos por (callSite, ordinal) | `LexicalOrderTest` |
| 3 | Patrones (a/b/c) ejecutables en orden esperado | `StageScopedExecutionTest` |
| 4 | Fingerprint material de un plan == fingerprint del scripted generator-level equivalente (mismo sourceDigest, mismo facade schema) | `FingerprintReplayTest` |
| 5 | Cualquier reordenación del cuerpo produce un `sourceDigest` distinto | `FingerprintReplayTest` |
| 6 | Módulo aislado: 0 imports de `:pipeline-application`, 0 imports de `:pipeline-scripting-kotlin24` | `ArchitectureIsolationTest` (assertEquals sobre el classpath resuelto) |

## 6. Verificación mínima

- L0: `./gradlew :pipeline-spike-stage-scoped:compileKotlin` PASS.
- L1: `./gradlew :pipeline-spike-stage-scoped:test --tests '*LexicalOrder*' --tests '*StageScopedExecution*' --tests '*FingerprintReplay*'` PASS.
- L2: `./gradlew :pipeline-spike-stage-scoped:test` PASS completo.
- **Prohibido**: ejecutar `:pipeline-application:test`, `:pipeline-architecture-tests:test`,
  o `./gradlew -p v2 check` dentro del spike. Si necesitamos esa cobertura,
  pertenece a la WU de integración posterior, NO al spike.

## 7. Gates y autorización

- Este spike NO requiere §2.4 porque NO cambia semántica pública: el módulo es
  nuevo, sin DSL nueva, sin StepKey nuevo. Lo que mide es si el seam existente
  aguanta la integración stage-scoped. Si aguanta, la propuesta de ampliar
  superficie se somete a §2.4 en una WU posterior con su propia autorización.
- RP-5 sigue bloqueado por rule 6 y NO se desbloquea con este spike.
- CI del SHA del spike: obligatorio antes de reclamar PASS. `gh run list --commit <SHA>`.

## 8. Entregables del cierre (RECEIPT)

- `docs/v2/07-uat/WU_RP_058_SPIKE_RECEIPT.md` con:
  - G0 baseline (sin spike, fresh run de los módulos afectados)
  - G1..G5 cubriendo las 6 invariantes
  - SHA base / head, XML, exit codes
  - Honestidad sobre qué se ejecutó y qué no
  - Decisión: si el spike valida la superficie, abrir WU-RP-059 para integrar
    con §2.4; si NO valida, documentar por qué y volver a ADR-0093.

## 9. Riesgos abiertos (heredados)

- ADR-0093 §9 sigue sin resolver: orden léxico eager/suspend es hipótesis del
  spike, no hecho. Si la invariante 1 falla en código real, hay que reabrir §9
  antes de cualquier integración.
- El canonical coordinator real (`:pipeline-application:CanonicalDurableRunCoordinator`)
  no se ejercita en este spike por diseño. La verificación contra journal/fingerprint
  reales queda fuera hasta WU-RP-059.

## 10. Plan de commits

1. `chore(spike): WU-RP-058 module skeleton + settings registration` (este archivo).
2. `feat(spike): StagePlan + StageOp + LexicalOrderSpec`.
3. `feat(spike): StageScopedFrontend facade (fake)`.
4. `test(spike): LexicalOrderTest + StageScopedExecutionTest`.
5. `test(spike): FingerprintReplayTest + ArchitectureIsolationTest`.
6. `docs(spike): WU_RP_058_SPIKE_RECEIPT.md`.

Push operator-gated (rule 6). Sin PR hasta validación completa.
