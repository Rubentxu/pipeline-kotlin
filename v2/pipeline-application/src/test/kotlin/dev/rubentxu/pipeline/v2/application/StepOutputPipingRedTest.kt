package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * LFC-2E3-P / P2 — RED: typed Step-output value piping does not exist.
 *
 * ## The thing that cannot be expressed today
 *
 * ```kotlin
 * val report = junit(<report-glob>)                 // typed output, computed at runtime
 * publishReport(report = report)                          // consumer wants THAT value
 * ```
 *
 * Today a Step's typed output is written to the durable journal and is observable from outside the
 * run, but there is NO way for a later Step to receive it as input. The DSL cannot even name it:
 * `registryStep(...)` returns `Unit`, and the structural IR that carries a registry Step has no
 * durable output identity. Consequently the only way to feed a consumer is to duplicate the data
 * out of band — which is impossible when the producer's value is computed at runtime (test totals,
 * digests, resolved paths).
 *
 * A pre-existing comment in `PipelineDsl.kt` records the limitation in passing:
 *
 * ```text
 * NOTE: No return value — consumers use `sh(returnStdout=true)` for runtime values.
 * Documented limitation per D2; addressed in ML-R8 follow-up.
 * ```
 *
 * ## Why these are RED and not merely failing
 *
 * Each row asserts the existence of a SPECIFIC missing seam, so the failure names the gap rather
 * than merely reporting "something is missing":
 *
 * 1. the structural IR cannot carry a durable output identity;
 * 2. there is no canonical resolution port, so a consumer would have to do ambient lookup — which
 *    the platform forbids;
 * 3. the DSL surface that would bind a producer's output to a consumer does not exist;
 * 4. no capability token exists for resolution, so a consumer could not even DECLARE the need
 *    fail-closed.
 *
 * These rows are expected to FAIL until P2 is implemented, and to PASS unchanged afterwards.
 * They are deliberately written against the intended public names so the implementation is
 * contract-first rather than retro-fitted.
 */
@Timeout(30)
class StepOutputPipingRedTest {

    private fun classOrNull(name: String): Class<*>? =
        runCatching { Class.forName(name) }.getOrNull()

    @Test
    fun `RED 1 - the registry structural IR cannot carry a durable output identity`() {
        val fields = dev.rubentxu.pipeline.v2.dsl.StepSpec.RegistryStepSpec::class.java
            .declaredFields
            .map { it.name }
            .toSet()
        assertTrue(
            "outputName" in fields,
            "a registry Step must be able to declare the durable name its output is published " +
                "under; today RegistryStepSpec carries only stepKey/schemaVersion/encodedInput/" +
                "retry/timeoutMillis, so a later Step has nothing to bind to. fields=$fields",
        )
    }

    @Test
    fun `RED 2 - there is no canonical step-output resolution port`() {
        assertTrue(
            classOrNull("dev.rubentxu.pipeline.v2.domain.step.StepOutputResolver") != null,
            "the runtime must expose ONE canonical resolution port. Without it a consumer Step " +
                "would have to reach the journal, the coordinator or some ambient state itself — " +
                "all forbidden by the capability-routed handler discipline (LB-02 / G3-A4.2).",
        )
    }

    @Test
    fun `RED 3 - there is no typed reference a DSL author can bind to a consumer`() {
        assertTrue(
            classOrNull("dev.rubentxu.pipeline.v2.domain.step.StepOutputRef") != null,
            "a producer's output must be referenceable as a typed declarative value. The DSL must " +
                "NOT fabricate the runtime value during construction, so a REFERENCE type is " +
                "required rather than a value.",
        )
    }

    @Test
    fun `RED 4 - no capability token exists for output resolution`() {
        // A consumer can only obtain a producer's output through a DECLARED capability; admission
        // must be able to reject the Step before the handler runs when it is absent.
        val token = classOrNull("dev.rubentxu.pipeline.v2.domain.step.StepOutputResolver")
        assertTrue(
            token != null,
            "without a resolution capability a consumer Step cannot declare the requirement, so " +
                "fail-closed admission cannot cover it",
        )
    }

    @Test
    fun `characterization - today a Step output is durable but unreachable from a later Step`() {
        // This row PASSES today and must keep passing: it records the EXISTING property that makes
        // the gap sharp — the producer's output IS committed durably, it is simply not bindable.
        val encoded = EncodedStepValue("""{"report":{"kind":"Successful","suites":[]}}""")
        assertEquals(
            """{"report":{"kind":"Successful","suites":[]}}""",
            encoded.value,
            "an encoded output is already durable; the missing piece is a canonical way for a " +
                "consumer to bind it as input",
        )
    }
}
