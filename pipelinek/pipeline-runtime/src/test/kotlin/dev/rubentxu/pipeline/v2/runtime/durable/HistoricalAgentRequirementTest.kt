package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.EnvironmentSpec
import dev.rubentxu.pipeline.v2.domain.MapRuntimeConfig
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.directive.AgentLabel
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirementCodec
import dev.rubentxu.pipeline.v2.domain.directive.GateEnvironmentPrecedence
import dev.rubentxu.pipeline.v2.domain.directive.RemoteSelector
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEvaluator
import dev.rubentxu.pipeline.v2.domain.directivekey.AGENT_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.runtime.AgentDirectiveDefinition
import dev.rubentxu.pipeline.v2.runtime.LocalExecutionTargetResolver
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * A plan that carries the agent requirement, and what the run does with it.
 *
 * ## The guarantee under trial
 *
 * That the requirement the agent carried is STILL A CONSTRAINT: a plan that
 * carries it is granted only when the machine the run is on satisfies it, and
 * is refused otherwise — in closed, with a reason that names what was asked
 * and what the host announces.
 *
 * ## Why this row exists at all
 *
 * Because the language stopped offering a way to WRITE the requirement, and
 * that is not the same thing as the requirement going away. A plan composed
 * before the removal — a journal, a plan on disk, a plan a plugin composed
 * through the open seam — still carries `core.agent`, and the run that
 * resumes it still has to HONOUR it. Treating the directive as ignorable
 * would let a stage run on a machine the author excluded.
 *
 * The removal of the authoring surface and the survival of the directive are
 * therefore two facts about the same change, and this file measures the second
 * one.
 *
 * ## What this file does NOT freeze
 *
 * The events. The seam emits the resolution or the denial before the verdict
 * reaches the coordinator, and that order is the engine's own contract; this
 * file reads the DECISION, which is what the coordinator acts on.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HistoricalAgentRequirementTest {

    /**
     * A requirement the host satisfies is granted.
     */
    @Test
    @DisplayName("a requirement the host satisfies is granted")
    fun aRequirementTheHostSatisfiesIsGranted() {
        val verdict = verdictOf(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("unix"))),
        )

        assertEquals(
            BeforeStageDirectiveEngine.Verdict.Admitted,
            verdict,
            "the host advertises the label the requirement asks for, so the stage runs: the " +
                "requirement is still checked and it is still satisfiable",
        )
    }

    /**
     * A requirement the host does not satisfy is refused in closed.
     *
     * The row that names the reason: the refusal names the label that was
     * asked for AND the labels the host advertises, because an author who
     * only learns that the requirement failed cannot fix it.
     */
    @Test
    @DisplayName("a requirement the host does not satisfy is refused in closed")
    fun aRequirementTheHostDoesNotSatisfyIsRefusedInClosed() {
        val verdict = verdictOf(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("gpu"))),
        )

        val denied = assertInstanceOf(
            BeforeStageDirectiveEngine.Verdict.Denied::class.java,
            verdict,
            "a requirement the host cannot satisfy fails the stage closed: a stage that runs on a " +
                "machine the author excluded is the exact silent semantic drop this product exists " +
                "to remove",
        )
        assertTrue(
            denied.reason.contains("gpu"),
            "the refusal names the label that was asked for: ${denied.reason}",
        )
        assertTrue(
            denied.reason.contains("unix"),
            "the refusal names the labels the host advertises, so the author can see what it " +
                "does have: ${denied.reason}",
        )
    }

    /**
     * A remote requirement is refused, and the refusal says why.
     */
    @Test
    @DisplayName("a remote requirement is refused, and the refusal says why")
    fun aRemoteRequirementIsRefusedAndTheRefusalSaysWhy() {
        val verdict = verdictOf(
            ExecutionTargetRequirement.Remote(RemoteSelector("worker-1")),
        )

        val denied = assertInstanceOf(
            BeforeStageDirectiveEngine.Verdict.Denied::class.java,
            verdict,
            "a remote requirement is refused: this runtime is single-host, and running the stage " +
                "on the local host would silently ignore the selector",
        )
        assertTrue(
            denied.reason.contains("worker-1"),
            "the refusal names the selector that was asked for: ${denied.reason}",
        )
        assertTrue(
            denied.reason.contains("single-host"),
            "the refusal says WHY it cannot be acquired: ${denied.reason}",
        )
    }

    /**
     * The verdict of a stage that carries the requirement.
     *
     * The stage, the registry and the resolver are the ones the product
     * composes: the row exercises the seam, it does not rewrite it. The host
     * is a fixed one — a machine that advertises `linux` and `unix` — so the
     * rows do not depend on the machine the suite runs on.
     */
    private fun verdictOf(requirement: ExecutionTargetRequirement): BeforeStageDirectiveEngine.Verdict {
        val stage = StageNode(
            id = StageId("stage-0"),
            name = "build",
            environment = EnvironmentSpec(emptyMap()),
            body = StageBody.Steps(emptyList()),
            directives = listOf(
                StageDirective(
                    key = AGENT_DIRECTIVE_KEY.value,
                    encodedArguments = ExecutionTargetRequirementCodec.encode(requirement),
                ),
            ),
        )
        val engine = BeforeStageDirectiveEngine(
            eventSink = NullEventSink,
            clock = FixedClock,
            idGenerator = SequentialIdGenerator(),
            gateContext = { environment ->
                GateEnvironmentPrecedence.DEFAULT.resolve(environment.values, emptyMap())
            },
            gateEvaluator = WhenPredicateEvaluator::evaluate,
            targetResolver = LocalExecutionTargetResolver(
                runtimeConfig = MapRuntimeConfig(
                    env = emptyMap(),
                    properties = mapOf("os.name" to HOST_PLATFORM),
                ),
            ),
        )
        return engine.interpret(
            stage = stage,
            stageIndex = 0,
            runId = RunId("run-1"),
            directiveRegistry = DirectiveRegistry.Builder()
                .add(ErasedDirectiveDefinition(AgentDirectiveDefinition()))
                .build(),
        )
    }

    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        /** The platform the fixed host advertises; its labels are `linux` and `unix`. */
        const val HOST_PLATFORM = "Linux"
    }
}
