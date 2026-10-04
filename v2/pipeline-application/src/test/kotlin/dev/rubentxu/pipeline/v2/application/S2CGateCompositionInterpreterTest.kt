package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.PostSpec
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinitionAny
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.GateContext
import dev.rubentxu.pipeline.v2.domain.directive.GateVerdict
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateCodec
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEncoder
import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.GateEvaluated
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.PostConditionSelected
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * S2-C — the coordinator INTERPRETS the composed gate decision: one evaluation
 * per stage, one observable verdict, declaration-order composition, and a
 * duplicate gate key denied fail-closed.
 */
@Timeout(60, unit = TimeUnit.SECONDS)
class S2CGateCompositionInterpreterTest {

    // A SECOND gate directive, registered under its own key: proves the engine
    // reads the POLICY, never the key.
    private class LockDirectiveDefinition : DirectiveDefinition<WhenPredicate, GateVerdict> {
        override val key = DirectiveKey("acme.lock")
        override val phase = DirectivePhase.BEFORE_STAGE
        override val policy = DirectiveExecutionPolicy.Gate("core.when")
        override fun decode(encodedArguments: String) = WhenPredicateCodec.decode(encodedArguments)
    }

    private fun registry(): DirectiveRegistry = DirectiveRegistry.Builder()
        .addAll(
            listOf(
                ErasedDirectiveDefinition(WhenDirectiveDefinition()) as DirectiveDefinitionAny,
                ErasedDirectiveDefinition(LockDirectiveDefinition()) as DirectiveDefinitionAny,
            ),
        )
        .build()

    private fun stageNode(marker: Path, vararg directives: StageDirective): StageNode = StageNode(
        id = StageId("gated"),
        name = "gated",
        body = StageBody.Steps(
            listOf(
                OpaqueStepNode(
                    id = StepId("gated/sh"),
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
        id = DefinitionId("s2c-compose"),
        source = SourceDescriptor("S2C.pipeline.kts", Digest("s2c")),
        pluginLockDigest = Digest("s2c-lock"),
        stages = listOf(stageNode(Path.of("/dev/null"), *directives)),
    )

    private class Wiring(
        val coordinator: CanonicalDurableRunCoordinator,
        val events: InMemoryEventStore,
    )

    private fun coordinator(context: GateContext): Wiring {
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
            controlDirRoot = Files.createTempDirectory("s2c-control"),
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = registry(),
            gateContext = { context },
        )
        return Wiring(coordinator, events)
    }

    private fun whenDirective(predicate: WhenPredicate) = StageDirective(
        WHEN_DIRECTIVE_KEY.value,
        WhenPredicateEncoder.encode(predicate),
    )

    @Test
    fun `two satisfied gates run the body and emit ONE verdict naming both keys`() {
        val marker = Files.createTempFile("s2c-both", ".txt")
        Files.deleteIfExists(marker)
        val wiring = coordinator(GateContext(values = mapOf("ENV" to "prod", "APPROVAL" to "yes")))

        val compiled = CompiledPipeline(
            id = DefinitionId("s2c-both"),
            source = SourceDescriptor("S2C.pipeline.kts", Digest("s2c")),
            pluginLockDigest = Digest("s2c-lock"),
            stages = listOf(
                stageNode(
                    marker,
                    whenDirective(WhenPredicate.VariableEquals("ENV", "prod")),
                    StageDirective(
                        "acme.lock",
                        WhenPredicateEncoder.encode(WhenPredicate.VariablePresent("APPROVAL")),
                    ),
                ),
            ),
        )

        val runId = RunId("s2c-both")
        val outcome = runBlocking { wiring.coordinator.run(compiled, runId) }

        assertTrue(outcome is RunOutcome.Success, "composed satisfied gates run the stage, got $outcome")
        assertTrue(Files.exists(marker), "the body must run when every gate holds")

        val evaluated = wiring.events.eventsFor(runId.value).filterIsInstance<GateEvaluated>().toList()
        assertEquals(1, evaluated.size, "exactly one composed verdict per stage: $evaluated")
        val verdict = evaluated.single()
        assertEquals(listOf("core.when", "acme.lock"), verdict.directiveKeys, "declaration order, both keys")
        assertTrue(verdict.satisfied, "verdict must be satisfied")
        assertEquals("", verdict.reason, "no reason when satisfied")
        assertTrue(
            wiring.events.eventsFor(runId.value).none { it is StageSkipped },
            "a satisfied composition never skips",
        )
    }

    @Test
    fun `a decided-negative in the composition skips the stage with the observable verdict`() {
        val marker = Files.createTempFile("s2c-negative", ".txt")
        Files.deleteIfExists(marker)
        val wiring = coordinator(GateContext(values = mapOf("ENV" to "prod")))

        val compiled = CompiledPipeline(
            id = DefinitionId("s2c-negative"),
            source = SourceDescriptor("S2C.pipeline.kts", Digest("s2c")),
            pluginLockDigest = Digest("s2c-lock"),
            stages = listOf(
                stageNode(
                    marker,
                    whenDirective(WhenPredicate.VariableEquals("ENV", "prod")),
                    StageDirective(
                        "acme.lock",
                        WhenPredicateEncoder.encode(WhenPredicate.VariablePresent("APPROVAL")),
                    ),
                ),
            ),
        )

        val runId = RunId("s2c-negative")
        val outcome = runBlocking { wiring.coordinator.run(compiled, runId) }

        assertTrue(outcome is RunOutcome.Success, "a skipped stage is a successful run, got $outcome")
        assertTrue(!Files.exists(marker), "the body must not run when any gate is negative")

        val stream = wiring.events.eventsFor(runId.value).toList()
        val verdict = stream.filterIsInstance<GateEvaluated>().single()
        assertEquals(false, verdict.satisfied, "the composed verdict is negative")
        assertTrue(verdict.reason.contains("APPROVAL"), "reason names the failed condition: ${verdict.reason}")
        val skip = stream.filterIsInstance<StageSkipped>().single()
        // The verdict is observable BEFORE the skip it causes.
        assertTrue(
            stream.indexOf(verdict) < stream.indexOf(skip),
            "GateEvaluated must precede StageSkipped",
        )
    }

    @Test
    fun `a duplicate gate key is denied fail-closed before the stage starts`() {
        val wiring = coordinator(GateContext(values = mapOf("ENV" to "prod")))
        val compiled = CompiledPipeline(
            id = DefinitionId("s2c-dupe"),
            source = SourceDescriptor("S2C.pipeline.kts", Digest("s2c")),
            pluginLockDigest = Digest("s2c-lock"),
            stages = listOf(
                stageNode(
                    Path.of("/dev/null"),
                    whenDirective(WhenPredicate.VariableEquals("ENV", "prod")),
                    whenDirective(WhenPredicate.VariableEquals("ENV", "dev")),
                ),
            ),
        )

        val runId = RunId("s2c-dupe")
        val outcome = runBlocking { wiring.coordinator.run(compiled, runId) }

        assertTrue(outcome is RunOutcome.Failure, "a duplicate gate key fails closed, got $outcome")
        val failure = (outcome as RunOutcome.Failure).failure
        assertEquals(FailureKind.USER, failure.kind, "an author error is a USER failure")
        assertTrue(
            failure.message.contains("core.when") && failure.message.contains("2 times"),
            "diagnostic names the key and the defect: ${failure.message}",
        )
        val stream = wiring.events.eventsFor(runId.value).toList()
        assertTrue(
            stream.filterIsInstance<DirectiveDenied>().any { it.reason.contains("core.when") },
            "the conflict is observable as a denial: ${stream.map { it.kind }}",
        )
        assertTrue(
            stream.none { it is GateEvaluated },
            "no verdict is emitted for a conflicted composition",
        )
        // Admission events for the first declaration DID fire (the S1-C seam is
        // admission-scoped); the conflict is the composition-scoped defect and
        // denies AFTER admission but BEFORE any stage effect.
        assertTrue(
            stream.none { it is StageStarted },
            "a conflicted stage never starts",
        )
    }

    @Test
    fun `an unverifiable gate inside a composition fails the run closed with its verdict`() {
        val marker = Files.createTempFile("s2c-unver", ".txt")
        Files.deleteIfExists(marker)
        val wiring = coordinator(GateContext(values = emptyMap(), unresolvable = setOf("ENV")))

        val compiled = CompiledPipeline(
            id = DefinitionId("s2c-unver"),
            source = SourceDescriptor("S2C.pipeline.kts", Digest("s2c")),
            pluginLockDigest = Digest("s2c-lock"),
            stages = listOf(stageNode(marker, whenDirective(WhenPredicate.VariableEquals("ENV", "prod")))),
        )

        val runId = RunId("s2c-unver")
        val outcome = runBlocking { wiring.coordinator.run(compiled, runId) }

        assertTrue(outcome is RunOutcome.Failure, "an unverifiable gate fails closed, got $outcome")
        assertTrue(!Files.exists(marker), "the body must not run")
        val verdict = wiring.events.eventsFor(runId.value).filterIsInstance<GateEvaluated>().single()
        assertEquals(false, verdict.satisfied, "unverifiable is a negative verdict, never an admission")
    }

    /**
     * S2-C hardening: a definition may declare policy Gate while its own codec
     * produces something that is NOT a WhenPredicate. The engine cannot know that
     * statically (each definition owns its codec), so the erasure must be CHECKED
     * at the seam. An unchecked cast here would let a ClassCastException escape
     * the run loop; the contract is a typed USER failure, fail-closed, no body.
     */
    @Test
    fun `a gate definition that decodes to a non-predicate is denied fail-closed, never cast`() {
        val clock = SystemClock()
        val events = InMemoryEventStore()
        // Same policy as a real gate, but a codec that decodes a non-predicate shape.
        val incoherent = object : DirectiveDefinition<Any, GateVerdict> {
            override val key = DirectiveKey("acme.incoherent")
            override val phase = DirectivePhase.BEFORE_STAGE
            override val policy = DirectiveExecutionPolicy.Gate("core.when")
            override fun decode(encodedArguments: String): DirectiveDecodeResult<Any> =
                DirectiveDecodeResult.Decoded(42)
        }
        val incoherentRegistry = DirectiveRegistry.Builder()
            .addAll(listOf(ErasedDirectiveDefinition(incoherent) as DirectiveDefinitionAny))
            .build()

        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = Files.createTempDirectory("s2c-incoherent"),
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = incoherentRegistry,
        )

        val marker = Files.createTempFile("s2c-incoherent", ".txt")
        Files.deleteIfExists(marker)
        val compiled = CompiledPipeline(
            id = DefinitionId("s2c-incoherent"),
            source = SourceDescriptor("S2C.pipeline.kts", Digest("s2c")),
            pluginLockDigest = Digest("s2c-lock"),
            stages = listOf(
                stageNode(
                    marker,
                    StageDirective(key = "acme.incoherent", encodedArguments = "ignored"),
                ),
            ),
        )

        val runId = RunId("s2c-incoherent")
        val outcome = runBlocking { coordinator.run(compiled, runId) }

        assertTrue(
            outcome is RunOutcome.Failure,
            "a self-contradictory gate definition fails closed instead of crashing, got $outcome",
        )
        val failure = (outcome as RunOutcome.Failure).failure
        assertEquals(FailureKind.USER, failure.kind, "a definition contract violation is a USER failure")
        assertTrue(
            failure.message.contains("acme.incoherent") && failure.message.contains("not a WhenPredicate"),
            "the diagnostic names the definition and the contradiction: ${failure.message}",
        )
        assertTrue(!Files.exists(marker), "the body must not run")
        assertTrue(
            events.eventsFor(runId.value).filterIsInstance<DirectiveDenied>().any {
                it.directiveKey == "acme.incoherent"
            },
            "the contradiction is observable as a denial",
        )
        assertTrue(
            events.eventsFor(runId.value).none { it is GateEvaluated },
            "an undecodable gate emits no verdict",
        )
    }

    /**
     * S2-C closes the seam S2-B had to leave open. S2B proved post-finalizer
     * dispatch on a skipped stage but explicitly noted it could not reach the
     * gate-driven skip ("not possible without a gate directive"). Now that
     * composition exists, the INTEGRATED path is reachable and is pinned here:
     * a decided-negative composed gate skips the stage, and the ALWAYS finalizer
     * still runs - with the stage-projected environment, on real shell options,
     * even though the stage body never executed and its workspace never existed.
     */
    @Test
    fun `a composed negative gate skips the stage and still runs the ALWAYS finalizer`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("body-ran.txt")
        val cleanup = tempDir.resolve("cleanup-ran.txt")
        val wiring = coordinator(GateContext(values = mapOf("ENV" to "dev")))

        val compiled = CompiledPipeline(
            id = DefinitionId("s2c-skip-post"),
            source = SourceDescriptor("S2C.pipeline.kts", Digest("s2c")),
            pluginLockDigest = Digest("s2c-lock"),
            stages = listOf(
                StageNode(
                    id = StageId("gated"),
                    name = "gated",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("gated/sh"),
                                pluginStepId = PluginStepId("core.sh"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"sh","command":"echo body > '${marker}'",""" +
                                        """"isScriptBlock":false,"returnStdout":false}""",
                                ),
                            ),
                        ),
                    ),
                    directives = listOf(
                        whenDirective(WhenPredicate.VariableEquals("ENV", "prod")),
                    ),
                    post = PostSpec(
                        conditions = mapOf(
                            PostCondition.ALWAYS to listOf(
                                OpaqueStepNode(
                                    id = StepId("post/cleanup"),
                                    pluginStepId = PluginStepId("core.sh"),
                                    payload = VersionedStepPayload(
                                        "dsl-v1",
                                        """{"kind":"sh","command":"echo cleanup > '${cleanup}'",""" +
                                            """"isScriptBlock":false,"returnStdout":false}""",
                                    ),
                                ),
                            ),
                            PostCondition.SUCCESS to listOf(
                                OpaqueStepNode(
                                    id = StepId("post/success-only"),
                                    pluginStepId = PluginStepId("core.sh"),
                                    payload = VersionedStepPayload(
                                        "dsl-v1",
                                        """{"kind":"sh","command":"echo never > '${tempDir.resolve("never.txt")}'",""" +
                                            """"isScriptBlock":false,"returnStdout":false}""",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val runId = RunId("s2c-skip-post")
        val outcome = runBlocking { wiring.coordinator.run(compiled, runId) }

        assertEquals(RunOutcome.Success, outcome, "a skipped stage is a successful run")
        assertTrue(!marker.toFile().exists(), "the gated body never ran")
        assertTrue(cleanup.toFile().exists(), "ALWAYS fires for a gate-skipped stage")
        assertTrue(!tempDir.resolve("never.txt").toFile().exists(), "SUCCESS never fires on a skip")

        val stream = wiring.events.eventsFor(runId.value).toList()
        assertTrue(
            stream.any { it is StageSkipped && it.reason.contains("ENV") },
            "the skip is observable: ${stream.map { it.kind }}",
        )
        val selected = stream.filterIsInstance<PostConditionSelected>().single()
        assertEquals(
            listOf("ALWAYS"),
            selected.selectedConditions,
            "a skip selects exactly the declared always-family conditions (CLEANUP was not declared here)",
        )
        assertTrue(
            selected.skippedConditions.contains("SUCCESS"),
            "SUCCESS is not selected for a skip: ${selected.skippedConditions}",
        )
    }
}
