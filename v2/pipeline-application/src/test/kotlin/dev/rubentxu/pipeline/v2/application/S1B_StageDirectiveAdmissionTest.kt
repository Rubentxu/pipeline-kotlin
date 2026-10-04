package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
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
 * S1-B RED — the canonical durable coordinator MUST admit stage directives
 * against the [DirectiveRegistry] BEFORE a stage starts, and a denial MUST be
 * fail-closed: the stage body never dispatches, the run fails with a typed
 * USER failure naming the offending key.
 *
 * These tests are the behavioural gate the kernel needed from the engine:
 * without this wiring, a stage carrying an unknown directive would run as if
 * the directive did not exist — the silent-lie failure mode.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class S1B_StageDirectiveAdmissionTest {

    private fun pipeline(vararg directives: StageDirective): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("s1b-directives"),
        source = SourceDescriptor("S1B.pipeline.kts", Digest("s1b")),
        pluginLockDigest = Digest("s1b-lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("build/sh"),
                            pluginStepId = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.sh"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"sh","command":"echo directive-ran","isScriptBlock":false,"returnStdout":false}""",
                            ),
                        ),
                    ),
                ),
                directives = directives.toList(),
            ),
        ),
    )

    private fun coordinator(directiveRegistry: DirectiveRegistry?): CanonicalDurableRunCoordinator {
        val clock = SystemClock()
        val controlRoot = Files.createTempDirectory("s1b-control")
        return CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = controlRoot,
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = directiveRegistry,
        )
    }

    @Test
    fun `an unknown directive denies the stage before any step dispatches`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val marker = tempDir.resolve("effect.txt")
        val unknown = pipeline(StageDirective("acme.missing"))
        // Rewrite the shell command to touch the marker so we can prove the
        // stage body never executed.
        val stage = unknown.stages.single()
        val body = stage.body as StageBody.Steps
        val touching = body.steps.map {
            (it as OpaqueStepNode).copy(
                payload = VersionedStepPayload(
                    "dsl-v1",
                    """{"kind":"sh","command":"echo directive-ran > '${marker}'","isScriptBlock":false,"returnStdout":false}""",
                ),
            )
        }
        val pipeline = unknown.copy(
            stages = listOf(stage.copy(body = StageBody.Steps(touching))),
        )

        val outcome = runBlocking { coordinator(registryWithNoDirectives()).run(pipeline, RunId("s1b-denied")) }

        assertTrue(
            outcome is RunOutcome.Failure,
            "a stage with an unresolved directive MUST fail the run, got $outcome",
        )
        val failure = (outcome as RunOutcome.Failure).failure
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
            failure.kind,
            "an unresolvable directive is a user/contract failure, not infrastructure: ${failure.kind}",
        )
        assertTrue(
            failure.message.contains("acme.missing"),
            "the failure must name the offending key, got '${failure.message}'",
        )
        assertTrue(
            !Files.exists(marker),
            "FAIL-CLOSED: the stage body MUST NOT run when admission denies; the effect was observed",
        )
    }

    @Test
    fun `a registered directive admits the stage and the body runs`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val marker = tempDir.resolve("effect.txt")
        val base = pipeline(StageDirective("acme.echo", """{"message":"hi"}"""))
        val stage = base.stages.single()
        val body = stage.body as StageBody.Steps
        val touching = body.steps.map {
            (it as OpaqueStepNode).copy(
                payload = VersionedStepPayload(
                    "dsl-v1",
                    """{"kind":"sh","command":"echo directive-ran > '${marker}'","isScriptBlock":false,"returnStdout":false}""",
                ),
            )
        }
        val pipeline = base.copy(
            stages = listOf(stage.copy(body = StageBody.Steps(touching))),
        )

        val outcome = runBlocking {
            coordinator(
                DirectiveRegistry.Builder()
                    .addAll(listOf(echoDefinition()))
                    .build(),
            ).run(pipeline, RunId("s1b-admitted"))
        }

        assertEquals(
            RunOutcome.Success,
            outcome,
            "a registered directive MUST admit the stage; the body then runs normally",
        )
        assertTrue(
            Files.exists(marker),
            "the stage body must have executed after successful admission",
        )
    }

    @Test
    fun `a stage without directives runs unchanged when no registry is wired`() {
        // Backwards compatibility: the directive seam is additive. Existing
        // pipelines and the ~25 coordinator call-sites MUST NOT change behaviour.
        val outcome = runBlocking { coordinator(null).run(pipeline(), RunId("s1b-legacy")) }

        assertEquals(
            RunOutcome.Success,
            outcome,
            "the directive seam MUST be additive: no directives + no registry = legacy behaviour",
        )
    }

    @Test
    fun `a registry without the declared key also denies`() {
        val outcome = runBlocking {
            coordinator(registryWithNoDirectives()).run(pipeline(StageDirective("other.thing")), RunId("s1b-other"))
        }

        assertTrue(
            outcome is RunOutcome.Failure && (outcome as RunOutcome.Failure).failure.message.contains("other.thing"),
            "any unregistered declared key denies: $outcome",
        )
    }

    private fun registryWithNoDirectives(): DirectiveRegistry = DirectiveRegistry.Builder().build()

    /** Minimal directive definition whose decode accepts anything. */
    private fun echoDefinition() = dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition(
        object : dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition<String, Unit> {
            override val key = DirectiveKey("acme.echo")
            override val phase = dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase.BEFORE_STAGE
            override val policy = dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Evaluate
            override fun decode(encodedArguments: String) =
                dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Decoded(encodedArguments)
        },
    )
}
