package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.EnvironmentSpec
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.GateEnvironmentPrecedence
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEncoder
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEvaluator
import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.runtime.WhenDirectiveDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the BEFORE_STAGE seam does with the verdict of a gate.
 *
 * ## The guarantee under trial
 *
 * That the three answers of the gate reach the run as three DIFFERENT
 * decisions, and not as one:
 *
 * ```
 * satisfied    -> the stage runs          (Verdict.Admitted)
 * negative     -> the stage does NOT run   (Verdict.SkipStage)
 * unverifiable -> the run fails closed     (Verdict.Denied)
 * ```
 *
 * ## Why the middle one is the dangerous one
 *
 * Because a negative is not a failure. A stage that is skipped is a stage
 * outcome, the run carries on, and the run reports success for work that never
 * ran. A reader who writes a gate on a variable that nobody declares gets
 * exactly that — and the published text has to say so, because the text is
 * what a migrating author reads.
 *
 * ## What this instrument does not freeze
 *
 * The events. The seam emits the verdict before the skip it causes, and that
 * order is its own contract; this row reads the DECISION, which is what the
 * coordinator acts on.
 */
class DeclaredGateVerdictTest {

    /**
     * A gate on a variable that only the agent has leaves the stage out.
     *
     * The host carries `BRANCH_NAME=main` and the script declares nothing, so
     * the gate cannot see the variable: the verdict is a decided negative and
     * the stage does not run. It is NOT a denial — the run carries on.
     */
    @Test
    @DisplayName("a gate on a variable that nobody declares leaves the stage out")
    fun aGateOnAVariableThatNobodyDeclaresLeavesTheStageOut() {
        val verdict = verdictOf(
            declared = emptyMap(),
            host = mapOf("BRANCH_NAME" to "main"),
            predicate = WhenPredicate.VariableEquals("BRANCH_NAME", "main"),
        )

        val skipped = assertInstanceOf(
            BeforeStageDirectiveEngine.Verdict.SkipStage::class.java,
            verdict,
            "a gate that names a variable nobody declares leaves the stage out",
        )
        assertTrue(
            skipped.reason.contains("BRANCH_NAME"),
            "the reason names the variable the gate could not see: ${skipped.reason}",
        )
    }

    /**
     * A gate on a variable that the script declares lets the stage run.
     *
     * The other side of the same rule: the declaration reaches the gate, and
     * the stage runs.
     */
    @Test
    @DisplayName("a gate on a declared variable lets the stage run")
    fun aGateOnADeclaredVariableLetsTheStageRun() {
        val verdict = verdictOf(
            declared = mapOf("BRANCH_NAME" to "main"),
            host = emptyMap(),
            predicate = WhenPredicate.VariableEquals("BRANCH_NAME", "main"),
        )

        assertEquals(
            BeforeStageDirectiveEngine.Verdict.Admitted,
            verdict,
            "a declared variable reaches the gate, and the stage runs",
        )
    }

    /**
     * A gate that nobody could decide fails the run closed.
     *
     * The third value of the rule reaches the run as a DENIAL and not as a
     * skip: a stage that nobody could decide must not be reported as a stage
     * that was decided not to run.
     */
    @Test
    @DisplayName("a gate that nobody could decide fails the run closed")
    fun aGateThatNobodyCouldDecideFailsTheRunClosed() {
        val verdict = verdictOf(
            declared = emptyMap(),
            host = emptyMap(),
            predicate = WhenPredicate.VariableEquals("BRANCH_NAME", "main"),
            unresolvable = setOf("BRANCH_NAME"),
        )

        val denied = assertInstanceOf(
            BeforeStageDirectiveEngine.Verdict.Denied::class.java,
            verdict,
            "a gate that nobody could decide fails the run closed",
        )
        assertTrue(
            denied.reason.contains("could not be verified"),
            "the denial says that the gate could not be verified: ${denied.reason}",
        )
    }

    /**
     * The seam, composed with the rule the product composes it with.
     *
     * The gate's facts are resolved by [GateEnvironmentPrecedence], the same
     * function the composition root hands to the coordinator: the row does not
     * rewrite the rule, it exercises it.
     */
    private fun verdictOf(
        declared: Map<String, String>,
        host: Map<String, String>,
        predicate: WhenPredicate,
        unresolvable: Set<String> = emptySet(),
    ): BeforeStageDirectiveEngine.Verdict {
        val stage = StageNode(
            id = StageId("stage-0"),
            name = "build",
            environment = EnvironmentSpec(declared),
            body = StageBody.Steps(emptyList()),
            directives = listOf(
                StageDirective(
                    key = WHEN_DIRECTIVE_KEY.value,
                    encodedArguments = WhenPredicateEncoder.encode(predicate),
                ),
            ),
        )
        val engine = BeforeStageDirectiveEngine(
            eventSink = NullEventSink,
            clock = FixedClock,
            idGenerator = SequentialIdGenerator(),
            gateContext = { environment ->
                val resolved = GateEnvironmentPrecedence.DEFAULT.resolve(environment.values, host)
                if (unresolvable.isEmpty()) {
                    resolved
                } else {
                    resolved.copy(unresolvable = unresolvable)
                }
            },
            gateEvaluator = WhenPredicateEvaluator::evaluate,
        )
        return engine.interpret(
            stage = stage,
            stageIndex = 0,
            runId = RunId("run-1"),
            directiveRegistry = DirectiveRegistry.Builder()
                .add(ErasedDirectiveDefinition(WhenDirectiveDefinition()))
                .build(),
        )
    }

    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }
}
