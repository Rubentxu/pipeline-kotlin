package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinitionAny
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.GateVerdict
import dev.rubentxu.pipeline.v2.events.DirectiveAdmitted
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.GateEvaluated
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * S2-D — the BEFORE_STAGE interpretation seam is ONE decode seam.
 *
 * Every admitted directive whose declared policy is `Gate` OR `Evaluate` is
 * decoded (its OWN codec) BEFORE the stage starts. The FIRST malformed decode
 * in DECLARATION ORDER denies the whole stage — the same law the pure planner
 * already applies to admission ("the FIRST directive that cannot be admitted
 * denies the whole stage"), now honoured by the interpreter for decoding.
 *
 * Pinned here:
 *  - D1/order: `[malformed gate, malformed evaluate]` and the reverse
 *    declaration diagnose DIFFERENTLY — the FIRST malformed declaration in the
 *    stage's declaration order names the denial. The diagnosis follows the
 *    author's declaration order, never the policy kind.
 *  - D5/regression: `ProvideContext` declared on BEFORE_STAGE stays
 *    admitted-observed WITHOUT interpretation, even with undecodable args — it
 *    is outside the seam by construction of the closed policy filter
 *    (Gate | Evaluate only).
 *
 * Mirror pattern: S2CGateCompositionInterpreterTest's "decodes to a
 * non-predicate" case (S2-C hardening).
 */
@Timeout(60, unit = TimeUnit.SECONDS)
class S2DDecodeBeforeStageInterpreterTest {

    /** A GATE whose own codec cannot read its arguments (typed Malformed). */
    private class MalformedGateDefinition : DirectiveDefinition<Any, GateVerdict> {
        override val key = DirectiveKey("acme.broken-gate")
        override val phase = DirectivePhase.BEFORE_STAGE
        override val policy = DirectiveExecutionPolicy.Gate("core.when")
        override fun decode(encodedArguments: String): DirectiveDecodeResult<Any> =
            DirectiveDecodeResult.Malformed("gate codec cannot read: $encodedArguments")
    }

    /** An EVALUATE whose own codec cannot read its arguments (typed Malformed). */
    private class MalformedEvaluateDefinition : DirectiveDefinition<Any, Unit> {
        override val key = DirectiveKey("acme.broken-evaluate")
        override val phase = DirectivePhase.BEFORE_STAGE
        override val policy = DirectiveExecutionPolicy.Evaluate
        override fun decode(encodedArguments: String): DirectiveDecodeResult<Any> =
            DirectiveDecodeResult.Malformed("evaluate codec cannot read: $encodedArguments")
    }

    /**
     * D5 pin subject: ProvideContext declared on BEFORE_STAGE. Its codec is
     * honest about garbage args (typed Malformed) — and that Malformed must
     * NEVER deny, because the seam never decodes this policy.
     */
    private class ProvideContextDefinition : DirectiveDefinition<Any, Unit> {
        override val key = DirectiveKey("acme.context")
        override val phase = DirectivePhase.BEFORE_STAGE
        override val policy = DirectiveExecutionPolicy.ProvideContext
        override fun decode(encodedArguments: String): DirectiveDecodeResult<Any> =
            DirectiveDecodeResult.Malformed("context codec cannot read: $encodedArguments")
    }

    private fun registry(vararg definitions: DirectiveDefinitionAny): DirectiveRegistry =
        DirectiveRegistry.Builder().addAll(definitions.toList()).build()

    private fun stageNode(marker: Path, vararg directives: StageDirective): StageNode = StageNode(
        id = StageId("s2d"),
        name = "s2d",
        body = StageBody.Steps(
            listOf(
                OpaqueStepNode(
                    id = StepId("s2d/sh"),
                    pluginStepId = PluginStepId("core.sh"),
                    payload = VersionedStepPayload(
                        "dsl-v1",
                        """{"kind":"sh","command":"echo body-ran > '${marker}'",""" +
                            """"isScriptBlock":false,"returnStdout":false}""",
                    ),
                ),
            ),
        ),
        directives = directives.toList(),
    )

    private fun compiled(vararg directives: StageDirective): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("s2d-decode"),
        source = SourceDescriptor("S2D.pipeline.kts", Digest("s2d")),
        pluginLockDigest = Digest("s2d-lock"),
        stages = listOf(stageNode(Path.of("/dev/null"), *directives)),
    )

    private class Wiring(
        val coordinator: CanonicalDurableRunCoordinator,
        val events: InMemoryEventStore,
    )

    private fun coordinator(registry: DirectiveRegistry): Wiring {
        val clock = SystemClock()
        val events = InMemoryEventStore()
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = Files.createTempDirectory("s2d-control"),
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = registry,
        )
        return Wiring(coordinator, events)
    }

    @Test
    fun `declaration order - a malformed gate declared first names the gate`() {
        val wiring = coordinator(
            registry(
                ErasedDirectiveDefinition(MalformedGateDefinition()),
                ErasedDirectiveDefinition(MalformedEvaluateDefinition()),
            ),
        )
        val compiled = compiled(
            StageDirective("acme.broken-gate", "{not-json"),
            StageDirective("acme.broken-evaluate", "{not-json"),
        )

        val runId = RunId("s2d-gate-first")
        val outcome = runBlocking { wiring.coordinator.run(compiled, runId) }

        assertTrue(outcome is RunOutcome.Failure, "a malformed decode fails closed, got $outcome")
        val failure = (outcome as RunOutcome.Failure).failure
        assertEquals(FailureKind.USER, failure.kind, "an author-side malformed arg is a USER failure")
        assertTrue(
            failure.message.contains("acme.broken-gate"),
            "the FIRST malformed declaration in declaration order names the denial: ${failure.message}",
        )

        val stream = wiring.events.eventsFor(runId.value).toList()
        assertTrue(
            stream.none { it is StageStarted },
            "fail-closed: the stage never starts",
        )
        assertTrue(
            stream.none { it is GateEvaluated },
            "no verdict is emitted for an undecodable seam",
        )
    }

    @Test
    fun `declaration order - a malformed evaluate declared first names the evaluate`() {
        val wiring = coordinator(
            registry(
                ErasedDirectiveDefinition(MalformedGateDefinition()),
                ErasedDirectiveDefinition(MalformedEvaluateDefinition()),
            ),
        )
        // Same two declarations, REVERSED order: the diagnosis must follow the
        // author's declaration order, not the policy kind.
        val compiled = compiled(
            StageDirective("acme.broken-evaluate", "{not-json"),
            StageDirective("acme.broken-gate", "{not-json"),
        )

        val runId = RunId("s2d-evaluate-first")
        val outcome = runBlocking { wiring.coordinator.run(compiled, runId) }

        assertTrue(outcome is RunOutcome.Failure, "a malformed decode fails closed, got $outcome")
        val failure = (outcome as RunOutcome.Failure).failure
        assertEquals(FailureKind.USER, failure.kind, "an author-side malformed arg is a USER failure")
        assertTrue(
            failure.message.contains("acme.broken-evaluate") &&
                failure.message.contains("could not be decoded"),
            "the FIRST malformed declaration in declaration order names the denial: ${failure.message}",
        )

        val stream = wiring.events.eventsFor(runId.value).toList()
        val denied = stream.filterIsInstance<DirectiveDenied>().single()
        assertTrue(
            denied.reason.contains("acme.broken-evaluate"),
            "the denial names the evaluate declared first: ${denied.reason}",
        )
        assertTrue(
            stream.none { it is StageStarted },
            "fail-closed: the stage never starts",
        )
        assertTrue(
            stream.none { it is GateEvaluated },
            "no verdict is emitted for an undecodable seam",
        )
    }

    /**
     * D5 pin (fail-closed DECLARED): ProvideContext on BEFORE_STAGE is
     * admitted-observed without interpretation — even with args its own codec
     * reports as malformed. It is outside the decode seam by construction
     * (the seam's policy filter admits only Gate | Evaluate), so the stage
     * runs and the body executes. Interpreting it would need a body context
     * projection seam that no product request has asked for.
     */
    @Test
    fun `provide context on BEFORE_STAGE stays admitted-observed even with undecodable args`() {
        val marker = Files.createTempFile("s2d-ctx", ".txt")
        Files.deleteIfExists(marker)
        val wiring = coordinator(registry(ErasedDirectiveDefinition(ProvideContextDefinition())))

        val runId = RunId("s2d-ctx")
        val outcome = runBlocking {
            wiring.coordinator.run(
                CompiledPipeline(
                    id = DefinitionId("s2d-ctx"),
                    source = SourceDescriptor("S2D.pipeline.kts", Digest("s2d")),
                    pluginLockDigest = Digest("s2d-lock"),
                    stages = listOf(
                        stageNode(marker, StageDirective("acme.context", "{not-json")),
                    ),
                ),
                runId,
            )
        }

        assertEquals(RunOutcome.Success, outcome, "ProvideContext never denies nor interprets: $outcome")
        assertTrue(Files.exists(marker), "the body must run")

        val stream = wiring.events.eventsFor(runId.value).toList()
        val admitted = stream.filterIsInstance<DirectiveAdmitted>().single()
        assertEquals("acme.context", admitted.directiveKey)
        assertEquals("provide-context", admitted.policy)
        assertTrue(
            stream.none { it is DirectiveDenied },
            "admitted-observed: no denial exists for ProvideContext in this phase: ${stream.map { it.kind }}",
        )
        assertTrue(
            stream.none { it is GateEvaluated },
            "ProvideContext is not a gate and emits no verdict",
        )
    }
}
