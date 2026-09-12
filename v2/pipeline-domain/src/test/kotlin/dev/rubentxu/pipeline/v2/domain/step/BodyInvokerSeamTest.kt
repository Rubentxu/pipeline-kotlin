package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HF0 pure-contract tests for the ADR-0081 BodyInvoker typed seam:
 * ref determinism, closed outcome algebra, context derivation invariants and
 * capability-key wiring.
 */
class BodyInvokerSeamTest {

    @Test
    fun `BodyRef is deterministic for the same bodyPath`() {
        val path = listOf(BlockSegment(0, PluginStepId("core.retry")), BlockSegment(1, PluginStepId("core.sh")))
        assertEquals(BodyRefs.childBody(path), BodyRefs.childBody(path))
        assertTrue(BodyRefs.childBody(path).encoded.startsWith("body/"))
    }

    @Test
    fun `BodyRef rejects blank encoding`() {
        assertThrows(IllegalArgumentException::class.java) { BodyRef(" ") }
    }

    @Test
    fun `branch and named refs derive distinct deterministic refs`() {
        val parent = listOf(BlockSegment(2, PluginStepId("core.parallel")))
        val a = BodyRefs.branchBody(parent, 0, PluginStepId("core.echo"))
        val b = BodyRefs.branchBody(parent, 1, PluginStepId("core.echo"))
        val n = BodyRefs.namedBody(parent, "left")
        assertTrue(a != b)
        assertTrue(a.encoded.contains("b0:core.echo"))
        assertTrue(n.encoded.contains("n:left"))
        assertEquals(BodyRefs.namedBody(parent, "left"), n)
        assertThrows(IllegalArgumentException::class.java) { BodyRefs.namedBody(parent, " ") }
    }

    @Test
    fun `BodyOutcome is a closed algebra with distinct semantics`() {
        val ok: BodyOutcome = BodyOutcome.Completed(StepOutcome.Success)
        val failed: BodyOutcome = BodyOutcome.Completed(StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "x")))
        val cancelled: BodyOutcome = BodyOutcome.Cancelled(CancellationReason.Deadline)
        assertEquals(ok, BodyOutcome.Completed(StepOutcome.Success))
        assertTrue(failed != ok)
        assertTrue(cancelled !is BodyOutcome.Completed)
    }

    @Test
    fun `AttemptSegment index must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            BodyInvocationContext(attempt = AttemptSegment(0))
        }
        BodyInvocationContext(attempt = AttemptSegment(1)) // valid
    }

    @Test
    fun `BodyRef round-trips through serialization`() {
        val ref = BodyRefs.childBody(listOf(BlockSegment(3, PluginStepId("core.timeout"))))
        val json = Json.encodeToString(BodyRef.serializer(), ref)
        assertEquals(ref, Json.decodeFromString(BodyRef.serializer(), json))
    }

    @Test
    fun `capability key is the neutral bodyInvoker token`() {
        assertEquals(StepCapability("bodyInvoker"), BODY_INVOKER_CAPABILITY)
    }

    @Test
    fun `handler re-enters through the port and folds typed outcomes`() = runBlocking {
        val invocations = mutableListOf<BodyRef>()
        val invoker = BodyInvoker { body, _ ->
            invocations += body
            BodyOutcome.Completed(StepOutcome.Success)
        }
        val ref = BodyRefs.namedBody(emptyList(), "body")
        val outcome = invoker.invoke(ref, BodyInvocationContext(attempt = AttemptSegment(2)))
        assertEquals(BodyOutcome.Completed(StepOutcome.Success), outcome)
        assertEquals(listOf(ref), invocations)
    }
}
