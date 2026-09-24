package dev.rubentxu.pipeline.v2.spike.stagescoped

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Replay-determinism tests for the spike.
 *
 * The canonical durable spine proves replay by re-executing against a
 * persisted journal; the spike proves it by structural equality of two
 * [Executed] traces produced from identical inputs. This is the
 * spike-local counterpart of "ReplayPolicy." and it is the property the
 * canonical adapter will need to preserve if it ever materialises.
 *
 * If this test ever fails, the spike has lost its determinism guarantee
 * and any future production wiring inheriting from it would silently
 * break replay semantics.
 */
class ReplayDeterminismTest {

    @Test
    @DisplayName("two interpretations of the same plan with the same facade produce equal traces")
    fun replayProducesEqualTrace() {
        val builder = StagePlanBuilder()
        builder.eager(StepSpec.Echo("a"))
        builder.pwd(tmp = false)
        builder.eager(StepSpec.Echo("b"))
        builder.isUnix()
        builder.readFile("version.txt")
        builder.shReturnStdout("git rev-parse HEAD", encoding = null)
        val plan = (builder.build() as StagePlanBuilder.Outcome.Plan).plan

        val facade = RecordingFacade(
            pwdResult = SuspendOutcome.StringOutcome(value = "/ws"),
            readFileResult = SuspendOutcome.StringOutcome(value = "1.0.0"),
            fileExistsResult = SuspendOutcome.BooleanOutcome(value = true),
            shResult = SuspendOutcome.StringOutcome(value = "abc123"),
            isUnixResult = SuspendOutcome.BooleanOutcome(value = true),
        )

        val first  = StageScopedFrontend.interpret(plan, facade)
        val second = StageScopedFrontend.interpret(plan, facade)

        // Structurally equal.
        assertEquals(first, second)
        // Distinct object identity — interpretation actually re-ran.
        assertNotSame(first, second)
        // And the facade was hit exactly twice per suspend call: 2 × 4 = 8.
        assertEquals(8, facade.callCount)
        // First run and second run must have hit the facade in the same
        // dispatch order — that's the replay invariant at the spike level.
        val half = facade.dispatchedNames.size / 2
        assertEquals(
            facade.dispatchedNames.subList(0, half),
            facade.dispatchedNames.subList(half, facade.dispatchedNames.size),
        )
    }

    @Test
    @DisplayName("trace.toString() is stable across replays — safe to compare as opaque blob")
    fun traceStringRepresentationIsStable() {
        val builder = StagePlanBuilder()
        builder.pwd(tmp = false)
        builder.fileExists("a.txt")
        val plan = (builder.build() as StagePlanBuilder.Outcome.Plan).plan

        val facade = RecordingFacade(
            pwdResult = SuspendOutcome.StringOutcome(value = "/ws"),
            fileExistsResult = SuspendOutcome.BooleanOutcome(value = true),
        )
        val s1 = StageScopedFrontend.interpret(plan, facade).toString()
        val s2 = StageScopedFrontend.interpret(plan, facade).toString()
        assertEquals(s1, s2)
    }
}
