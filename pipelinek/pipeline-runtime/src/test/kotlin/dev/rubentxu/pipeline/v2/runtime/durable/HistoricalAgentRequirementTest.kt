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
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.ExecutionTargetResolved
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
 * ## Why the rows read the OBSERVATION and not only the decision
 *
 * Because a decision alone cannot tell two cases apart. A stage that carries
 * no requirement at all is admitted, exactly like a stage whose requirement
 * the host satisfies: the verdict of the first is indistinguishable from the
 * verdict of the second, and the difference between them is the whole point —
 * a requirement that is DECLARED AND SATISFIED has to be visible as such, so
 * that nobody can read the run as one that never had the requirement.
 *
 * The engine's own contract says an observer can tell the three cases apart
 * (declared and satisfied, declared and refused, never declared), and this
 * file measures that: the event is appended BEFORE the verdict reaches the
 * coordinator, so its presence in the stream is proof that the requirement
 * was read and decided, not that the stage simply ran.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HistoricalAgentRequirementTest {

    /**
     * A requirement the host satisfies is granted, and the grant is OBSERVED.
     */
    @Test
    @DisplayName("a requirement the host satisfies is granted")
    fun aRequirementTheHostSatisfiesIsGranted() {
        val outcome = seamOf(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("unix"))),
        )

        assertEquals(
            BeforeStageDirectiveEngine.Verdict.Admitted,
            outcome.verdict,
            "the host advertises the label the requirement asks for, so the stage runs: the " +
                "requirement is still checked and it is still satisfiable",
        )

        val resolved = assertInstanceOf(
            ExecutionTargetResolved::class.java,
            outcome.events.filterIsInstance<ExecutionTargetResolved>().firstOrNull(),
            "the seam observed the resolution of the requirement: a requirement that is declared " +
                "and satisfied has to be distinguishable from a requirement that was never " +
                "declared, and only the observation tells the two apart",
        )
        assertEquals(
            AGENT_DIRECTIVE_KEY.value,
            resolved.directiveKey,
            "the observation names the directive it resolved: the requirement that the plan " +
                "carries is the one that was decided",
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
        val outcome = seamOf(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("gpu"))),
        )

        val denied = assertInstanceOf(
            BeforeStageDirectiveEngine.Verdict.Denied::class.java,
            outcome.verdict,
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

        val observed = assertInstanceOf(
            DirectiveDenied::class.java,
            outcome.events.filterIsInstance<DirectiveDenied>().firstOrNull(),
            "the refusal is observable: the observers of the run have to see that the " +
                "requirement was declared AND refused, and not only that the stage did not run",
        )
        assertEquals(
            AGENT_DIRECTIVE_KEY.value,
            observed.directiveKey,
            "the denial names the directive it denied: the requirement the plan carries is the " +
                "one that was refused",
        )
    }

    /**
     * A remote requirement is refused, and the refusal says why.
     */
    @Test
    @DisplayName("a remote requirement is refused, and the refusal says why")
    fun aRemoteRequirementIsRefusedAndTheRefusalSaysWhy() {
        val outcome = seamOf(
            ExecutionTargetRequirement.Remote(RemoteSelector("worker-1")),
        )

        val denied = assertInstanceOf(
            BeforeStageDirectiveEngine.Verdict.Denied::class.java,
            outcome.verdict,
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
     * What the seam decided, and what it observed while deciding it.
     */
    private data class SeamOutcome(
        val verdict: BeforeStageDirectiveEngine.Verdict,
        val events: List<DomainEvent>,
    )

    /**
     * The seam's answer for a stage that carries the requirement.
     *
     * The stage, the registry and the resolver are the ones the product
     * composes: the row exercises the seam, it does not rewrite it. The host
     * is a fixed one — a machine that advertises `linux` and `unix` — so the
     * rows do not depend on the machine the suite runs on.
     */
    private fun seamOf(requirement: ExecutionTargetRequirement): SeamOutcome {
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
        val sink = RecordingEventSink()
        val engine = BeforeStageDirectiveEngine(
            eventSink = sink,
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
        val verdict = engine.interpret(
            stage = stage,
            stageIndex = 0,
            runId = RunId("run-1"),
            directiveRegistry = DirectiveRegistry.Builder()
                .add(ErasedDirectiveDefinition(AgentDirectiveDefinition()))
                .build(),
        )
        return SeamOutcome(verdict = verdict, events = sink.events.toList())
    }

    /**
     * A sink that keeps what the seam observed.
     *
     * The seam appends synchronously, so what the sink holds when the verdict
     * arrives is what the seam observed BEFORE the verdict: the order of the
     * two is the engine's own contract.
     */
    private class RecordingEventSink : EventSink {
        val events = mutableListOf<DomainEvent>()
        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
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
