package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DefinitionId
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
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirementCodec
import dev.rubentxu.pipeline.v2.domain.directive.TargetLeaseResult
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.ExecutionTargetResolved
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
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
 * S3-R1-C — two `agent` declarations on one stage are a CONFLICT, not two requests.
 *
 * ## The state that made this a defect
 *
 * Every `agent*` builder appends, so this is representable today:
 *
 * ```
 * agentAny()
 * agent(label = "linux")
 * ```
 *
 * and on the local profile it looked harmless — both resolve to the same host and the
 * body runs. It is not harmless, and the harm is structural rather than local:
 *
 *  - a stage runs on ONE target, so two target requests describe a state the model does
 *    not have, and the engine resolved them SEQUENTIALLY, emitting two
 *    `ExecutionTargetResolved` events for one stage;
 *  - the second resolution could refuse for a reason that has nothing to do with the
 *    first, so which requirement "won" depended on declaration order;
 *  - and in RP-8, when a target is a real lease, this becomes acquire-acquire on the
 *    same resource with no defined composition.
 *
 * The existing duplicate protection was `GateCompositionPlanner`'s, and it applied only
 * to `GatePredicate`. The protection was never absent; it was never extended to the
 * policy that needs it.
 *
 * ## The law, and what it must NOT be
 *
 * ```
 * same DirectiveKey + Resource policy + same stage  ->  conflict
 * different keys, both Resource                      ->  legitimate
 * ```
 *
 * The tempting fix is `if (key == "core.agent")`, which would be a central switch on a
 * concrete key — precisely the defect class STEP CONSTITUTION §7 forbids, and precisely
 * what makes the law wrong the moment a second Resource directive exists. The counter-test
 * below contributes one and proves the law is structural.
 */
@Timeout(60, unit = TimeUnit.SECONDS)
class S3R1ResourceMultiplicityTest {

    /**
     * A second Resource-policy directive under a DIFFERENT key.
     *
     * Exists so the "different Resource keys are legitimate" half of the law is a real
     * executed path rather than a claim. It is contributed through the same open registry
     * a vendor would use, which is the only way to show the rule reads the POLICY and not
     * the name.
     */
    private class SecondaryResourceDefinition : DirectiveDefinition<ExecutionTargetRequirement, TargetLeaseResult> {
        override val key = DirectiveKey("acme.secondary-resource")
        override val phase = DirectivePhase.BEFORE_STAGE
        override val policy = DirectiveExecutionPolicy.Resource(DirectivePhase.BEFORE_STAGE)

        // Decodes through the shared requirement codec, as a vendor resource
        // directive would. The point of this class is only that a SECOND key can
        // carry the Resource policy, not anything about its payload.
        override fun decode(encodedArguments: String): DirectiveDecodeResult<ExecutionTargetRequirement> =
            ExecutionTargetRequirementCodec.decode(encodedArguments)
    }

    private fun registry(vararg definitions: DirectiveDefinitionAny): DirectiveRegistry =
        DirectiveRegistry.Builder().addAll(definitions.toList()).build()

    private fun compiled(
        id: String,
        bodyMarker: Path,
        vararg directives: StageDirective,
    ): CompiledPipeline = CompiledPipeline(
        id = DefinitionId(id),
        source = SourceDescriptor("S3R1C.pipeline.kts", Digest(id)),
        pluginLockDigest = Digest("$id-lock"),
        stages = listOf(
            StageNode(
                id = StageId("s3r1c"),
                name = "s3r1c",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("s3r1c/sh"),
                            pluginStepId = PluginStepId("core.sh"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"sh","command":"echo body-ran > '${bodyMarker}'",""" +
                                    """"isScriptBlock":false,"returnStdout":false}""",
                            ),
                        ),
                    ),
                ),
                directives = directives.toList(),
            ),
        ),
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
            controlDirRoot = Files.createTempDirectory("s3r1c-control"),
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = registry,
        )
        return Wiring(coordinator, events)
    }

    private fun run(wiring: Wiring, compiled: CompiledPipeline, runId: String): RunOutcome =
        runBlocking { wiring.coordinator.run(compiled, RunId(runId)) }

    // ------------------------------------------------------------------
    // 1. The defect: two agent declarations are representable and accepted
    // ------------------------------------------------------------------

    @Test
    fun `two agent declarations on one stage conflict before anything is resolved`() {
        val wiring = coordinator(registry(ErasedDirectiveDefinition(AgentDirectiveDefinition())))
        val marker = Files.createTempDirectory("s3r1c-any").resolve("body.txt")
        val runId = "s3r1c-any-label"
        val compiled = compiled(
            "s3r1c-any",
            marker,
            StageDirective("core.agent", "A"),
            StageDirective("core.agent", "L 1 5:linux"),
        )

        val outcome = run(wiring, compiled, runId)

        assertTrue(
            outcome is RunOutcome.Failure,
            "two agent declarations on one stage must be a conflict, not a run: $outcome",
        )
        val message = (outcome as RunOutcome.Failure).failure.message
        // Asserts the SUBSTANCE of the diagnosis — the offending key and the fact
        // that it was declared more than once — rather than grepping for a fixed
        // English word. The gate twin says "may be declared at most once" and never
        // uses the word "conflict" either, and a test that pins vocabulary instead of
        // meaning breaks the next time someone improves the wording.
        assertTrue(
            message.contains("core.agent"),
            "the denial must name the offending key: $message",
        )
        assertTrue(
            Regex("declared\\s+\\d+\\s+times").containsMatchIn(message),
            "the denial must state that the key was declared more than once: $message",
        )

        val stream = wiring.events.eventsFor(RunId(runId).value).toList()
        assertTrue(stream.none { it is StageStarted }, "the stage must never start")
        assertTrue(!Files.exists(marker), "the body must not have run")
        assertTrue(
            stream.none { it is ExecutionTargetResolved },
            "a CONFLICT must be detected before any target is resolved, or the engine " +
                "resolves one of two contradictory requests and reports success for the other: " +
                "${stream.map { it::class.simpleName }}",
        )
        assertTrue(
            stream.any { it is DirectiveDenied },
            "the conflict must be observable as a DirectiveDenied event",
        )
    }

    @Test
    fun `a label and a remote selector on one stage conflict rather than the second silently winning`() {
        // The ordering trap: before the fix, the local label resolved and GRANTED, and
        // only then did the remote selector refuse. The stage failed, so the outcome
        // looked fail-closed, but the diagnosis named the wrong thing — the author was
        // told "no remote allocator until RP-8" when the real problem was that they
        // wrote two targets.
        val wiring = coordinator(registry(ErasedDirectiveDefinition(AgentDirectiveDefinition())))
        val marker = Files.createTempDirectory("s3r1c-mix").resolve("body.txt")
        val runId = "s3r1c-label-remote"
        val compiled = compiled(
            "s3r1c-mix",
            marker,
            StageDirective("core.agent", "L 1 5:linux"),
            StageDirective("core.agent", "R 10:tcp://work"),
        )

        val message = (run(wiring, compiled, runId) as RunOutcome.Failure).failure.message

        assertTrue(
            Regex("declared\\s+\\d+\\s+times").containsMatchIn(message),
            "the denial must name the duplicate declaration: $message",
        )
        assertTrue(
            !message.contains("RP-8") && !message.contains("remote"),
            "the diagnosis must not blame the remote allocator for an authoring conflict: $message",
        )
        val stream = wiring.events.eventsFor(RunId(runId).value).toList()
        assertTrue(
            stream.none { it is ExecutionTargetResolved },
            "no target may be resolved when the declarations conflict: ${stream.map { it::class.simpleName }}",
        )
    }

    // ------------------------------------------------------------------
    // 2. The counter-test that forbids the concrete-key fix
    // ------------------------------------------------------------------

    @Test
    fun `two DIFFERENT resource keys on one stage are not a conflict`() {
        // This is the test that a `if (key == "core.agent")` fix fails. Two
        // Resource-policy directives under different keys describe different
        // resources, and refusing them would be the central-switch bug wearing a
        // safety hat. There is no composition law for two distinct resources yet, so
        // the correct behaviour is that neither is refused for being a duplicate.
        val wiring = coordinator(
            registry(
                ErasedDirectiveDefinition(AgentDirectiveDefinition()),
                ErasedDirectiveDefinition(SecondaryResourceDefinition()),
            ),
        )
        val marker = Files.createTempDirectory("s3r1c-two").resolve("body.txt")
        val runId = "s3r1c-two-keys"
        val compiled = compiled(
            "s3r1c-two",
            marker,
            StageDirective("core.agent", "L 1 5:linux"),
            StageDirective("acme.secondary-resource", "A"),
        )

        val outcome = run(wiring, compiled, runId)

        assertTrue(
            outcome !is RunOutcome.Failure,
            "two Resource directives under different keys must not be refused as duplicates: $outcome",
        )
        assertTrue(Files.exists(marker), "the body must have run")
        val stream = wiring.events.eventsFor(RunId(runId).value).toList()
        assertEquals(
            2,
            stream.filterIsInstance<ExecutionTargetResolved>().size,
            "both resources resolve, and each is observable: ${stream.map { it::class.simpleName }}",
        )
    }

    @Test
    fun `a duplicate of a SECONDARY resource key is also a conflict`() {
        // The test that a concrete-key fix fails, and the reason the previous two
        // were not enough.
        //
        // "two different Resource keys are legitimate" cannot tell a structural law
        // from `if (key == "core.agent")`, because in that scenario NEITHER key is
        // duplicated — both readings accept it. The discriminating case is a
        // duplicate of a key the engine has never heard of:
        //
        // ```
        // if (key == "core.agent")     ->  accepted, resolved twice, silently wrong
        // policy is Resource           ->  conflict
        // ```
        //
        // The forbidden fix is not merely inelegant; it is observably wrong on this
        // input, and a vendor shipping a second resource directive would be the one
        // to discover it.
        val wiring = coordinator(
        	registry(
        		ErasedDirectiveDefinition(AgentDirectiveDefinition()),
        		ErasedDirectiveDefinition(SecondaryResourceDefinition()),
        	),
        )
        val marker = Files.createTempDirectory("s3r1c-dup").resolve("body.txt")
        val runId = "s3r1c-dup-secondary"
        val compiled = compiled(
        	"s3r1c-dup",
        	marker,
        	StageDirective("acme.secondary-resource", "A"),
        	StageDirective("acme.secondary-resource", "L 1 5:linux"),
        )

        val outcome = run(wiring, compiled, runId)

        assertTrue(
        	outcome is RunOutcome.Failure,
        	"a duplicate of a second Resource key must be a conflict too: $outcome",
        )
        val message = (outcome as RunOutcome.Failure).failure.message
        assertTrue(
        	message.contains("acme.secondary-resource"),
        	"the denial must name the duplicated key: $message",
        )
        assertTrue(
        	Regex("declared\\s+\\d+\\s+times").containsMatchIn(message),
        	"the denial must state that the key was declared more than once: $message",
        )
        assertTrue(
        	wiring.events.eventsFor(RunId(runId).value).toList().none { it is ExecutionTargetResolved },
        	"no target may be resolved when a secondary key is duplicated either",
        )
    }

    // ------------------------------------------------------------------
    // 3. The counter-test that forbids a fix which refuses everything
    // ------------------------------------------------------------------

    @Test
    fun `a single agent declaration is unaffected`() {
        val wiring = coordinator(registry(ErasedDirectiveDefinition(AgentDirectiveDefinition())))
        val marker = Files.createTempDirectory("s3r1c-one").resolve("body.txt")
        val runId = "s3r1c-single"
        val compiled = compiled("s3r1c-one", marker, StageDirective("core.agent", "L 1 5:linux"))

        val outcome = run(wiring, compiled, runId)

        assertTrue(outcome !is RunOutcome.Failure, "one declaration must still run: $outcome")
        assertTrue(Files.exists(marker), "the body must have run")
        assertEquals(
            1,
            wiring.events.eventsFor(RunId(runId).value).toList()
                .filterIsInstance<ExecutionTargetResolved>().size,
            "exactly one resolution for exactly one declaration",
        )
    }

    @Test
    fun `a stage with no agent declaration is unaffected`() {
        val wiring = coordinator(registry(ErasedDirectiveDefinition(AgentDirectiveDefinition())))
        val marker = Files.createTempDirectory("s3r1c-none").resolve("body.txt")
        val runId = "s3r1c-none"

        val outcome = run(wiring, compiled("s3r1c-none", marker), runId)

        assertTrue(outcome !is RunOutcome.Failure, "a stage with no directives must run: $outcome")
        assertTrue(Files.exists(marker), "the body must have run")
    }
}
