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
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinitionAny
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.TargetLeaseResult

import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * S3-R1-A — the BEFORE_STAGE decode seam holds the boundary a definition was
 * admitted through.
 *
 * ## What the codec canaries could not cover
 *
 * [dev.rubentxu.pipeline.v2.domain.directive.S3R1AgentCodecTotalityTest] proves the bundled
 * codecs are total. It cannot prove that TOTALITY is enforced, because it exercises the codec
 * directly. The property the run actually depends on is one level out:
 *
 * ```
 * a payload that the codec cannot read
 *     -> a value the engine denies on, BEFORE StageStarted, with no body effect
 *     -> NEVER an exception crossing the seam
 * ```
 *
 * That path went through `definition.decodeAny(encoded)` with nothing around it. A definition
 * is contributed through the open [DirectiveContributor] SPI, so it is third-party code, and
 * [DirectiveRegistry.decodeAny] claims the engine "fails closed on it without
 * exception-based control flow" while nothing enforced the claim.
 *
 * ## The properties pinned here
 *
 *  1. A definition that THROWS is fail-closed: the run fails, `StageStarted` never fires, and
 *     the body never runs. No exception reaches the run loop.
 *  2. The fault is reported DIFFERENTLY from a malformed payload — in the reason AND in
 *     [FailureKind]. A broken definition is `PLUGIN`; a payload the author got wrong is `USER`.
 *     Collapsing them would send every operator to their pipeline to fix our bug.
 *  3. An [Error] is NOT converted. Genuine JVM resource exhaustion is not a directive defect,
 *     and relabelling it as one would be a false diagnosis pointed at the wrong owner.
 *  4. The fault diagnostic does not echo the encoded arguments, which are author-supplied and
 *     may carry sensitive values.
 *  5. The real [AgentDirectiveDefinition] under hostile payloads is denied as a VALUE through
 *     this seam — the end-to-end proof that the codec fix reaches the run, not just the codec.
 */
@Timeout(60, unit = TimeUnit.SECONDS)
class S3R1DirectiveDecodeBoundaryTest {

    // ------------------------------------------------------------------
    // Definitions under test
    // ------------------------------------------------------------------

    /** A definition that returns a typed Malformed — the author's payload is wrong. */
    private class MalformedDefinition : DirectiveDefinition<Any, TargetLeaseResult> {
        override val key = DirectiveKey("acme.s3r1-malformed")
        override val phase = DirectivePhase.BEFORE_STAGE
        override val policy = DirectiveExecutionPolicy.Resource(DirectivePhase.BEFORE_STAGE)
        override fun decode(encodedArguments: String): DirectiveDecodeResult<Any> =
            DirectiveDecodeResult.Malformed("codec cannot read these arguments")
    }

    /** A definition that THROWS — the plugin is broken, whatever the payload was. */
    private class ThrowingDefinition(
        private val failure: () -> Throwable,
    ) : DirectiveDefinition<ExecutionTargetRequirement, TargetLeaseResult> {
        override val key = DirectiveKey("acme.s3r1-throwing")
        override val phase = DirectivePhase.BEFORE_STAGE
        override val policy = DirectiveExecutionPolicy.Resource(DirectivePhase.BEFORE_STAGE)

        @Suppress("TooGenericExceptionCaught")
        override fun decode(encodedArguments: String): DirectiveDecodeResult<ExecutionTargetRequirement> {
            throw failure()
        }
    }

    private fun registry(vararg definitions: DirectiveDefinitionAny): DirectiveRegistry =
        DirectiveRegistry.Builder().addAll(definitions.toList()).build()

    // ------------------------------------------------------------------
    // Fixture — the same shape S2D uses, so the two suites are comparable
    // ------------------------------------------------------------------

    private fun compiled(
        id: String,
        bodyMarker: Path,
        vararg directives: StageDirective,
    ): CompiledPipeline = CompiledPipeline(
        id = DefinitionId(id),
        source = SourceDescriptor("S3R1.pipeline.kts", Digest(id)),
        pluginLockDigest = Digest("$id-lock"),
        stages = listOf(
            StageNode(
                id = StageId("s3r1"),
                name = "s3r1",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("s3r1/sh"),
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
            controlDirRoot = Files.createTempDirectory("s3r1-control"),
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = registry,
        )
        return Wiring(coordinator, events)
    }

    /** Run a single-stage pipeline and assert nothing about the outcome. */
    private fun run(
        wiring: Wiring,
        compiled: CompiledPipeline,
        runId: String,
    ): RunOutcome = runBlocking { wiring.coordinator.run(compiled, RunId(runId)) }

    private fun assertFailClosed(
        outcome: RunOutcome,
        wiring: Wiring,
        runId: String,
        bodyMarker: Path,
        because: String,
    ): String {
        assertTrue(outcome is RunOutcome.Failure, "$because: expected a failure, got $outcome")
        val message = (outcome as RunOutcome.Failure).failure.message
        val stream = wiring.events.eventsFor(RunId(runId).value).toList()
        assertTrue(stream.none { it is StageStarted }, "$because: the stage must never start")
        assertTrue(
            !Files.exists(bodyMarker),
            "$because: the body must not have run (marker $bodyMarker exists)",
        )
        assertTrue(
            stream.any { it is DirectiveDenied },
            "$because: the denial must be observable as a DirectiveDenied event",
        )
        return message
    }

    // ------------------------------------------------------------------
    // 1 + 2: a throwing definition is fail-closed AND classified as a plugin defect
    // ------------------------------------------------------------------

    @Test
    fun `a definition that throws fails closed instead of throwing through the run loop`() {
        val wiring = coordinator(
            registry(ErasedDirectiveDefinition(ThrowingDefinition { IllegalStateException("decoder blew up") })),
        )
        val marker = Files.createTempDirectory("s3r1-marker").resolve("body.txt")
        val compiled = compiled(
            "s3r1-throw",
            marker,
            StageDirective("acme.s3r1-throwing", "A"),
        )

        val runId = "s3r1-throw"
        val outcome = run(wiring, compiled, runId)
        val message = assertFailClosed(
            outcome,
            wiring,
            runId,
            marker,
            "a faulting definition must be fail-closed",
        )

        assertEquals(
            FailureKind.PLUGIN,
            (outcome as RunOutcome.Failure).failure.kind,
            "a broken definition is a PLUGIN defect, never a USER error",
        )
        assertTrue(
            message.contains("acme.s3r1-throwing"),
            "the denial must name the definition that faulted: $message",
        )
        assertTrue(
            message.contains("IllegalStateException"),
            "the denial must carry the failure type so the bug stays diagnosable: $message",
        )
    }

    @Test
    fun `a definition fault is not reported the same way as a malformed payload`() {
        // The discrimination is mechanical, not a matter of wording taste: the
        // two cases differ in FailureKind AND in reason, so neither a dashboard
        // nor an operator can confuse them.
        val throwingWiring = coordinator(
            registry(ErasedDirectiveDefinition(ThrowingDefinition { IllegalArgumentException("boom") })),
        )
        val malformedWiring = coordinator(registry(ErasedDirectiveDefinition(MalformedDefinition())))

        val throwingMarker = Files.createTempDirectory("s3r1-t").resolve("body.txt")
        val malformedMarker = Files.createTempDirectory("s3r1-m").resolve("body.txt")

        val faultOutcome = run(
            throwingWiring,
            compiled("s3r1-fault", throwingMarker, StageDirective("acme.s3r1-throwing", "A")),
            "s3r1-fault",
        )
        val malformedOutcome = run(
            malformedWiring,
            compiled("s3r1-bad", malformedMarker, StageDirective("acme.s3r1-malformed", "A")),
            "s3r1-bad",
        )

        val faultMessage = assertFailClosed(faultOutcome, throwingWiring, "s3r1-fault", throwingMarker, "fault")
        val malformedMessage = assertFailClosed(
            malformedOutcome,
            malformedWiring,
            "s3r1-bad",
            malformedMarker,
            "malformed",
        )

        assertEquals(
            FailureKind.PLUGIN,
            (faultOutcome as RunOutcome.Failure).failure.kind,
            "a faulting definition is a plugin defect",
        )
        assertEquals(
            FailureKind.USER,
            (malformedOutcome as RunOutcome.Failure).failure.kind,
            "an unreadable payload is the author's to fix",
        )
        assertTrue(
            !malformedMessage.contains("faulted"),
            "a malformed payload must never read as a plugin defect: $malformedMessage",
        )
        assertTrue(
            !faultMessage.contains("could not be decoded"),
            "a plugin fault must never read as an author error: $faultMessage",
        )
    }

    // ------------------------------------------------------------------
    // 3: an Error is the JVM's business, not the definition's
    // ------------------------------------------------------------------

    @Test
    fun `an Error is propagated rather than relabelled as a definition fault`() {
        // Catching Throwable would be the lazy way to make this test pass. It
        // would also report genuine memory exhaustion as "this directive is
        // broken", pointing a maintainer at a plugin that is fine. The boundary
        // holds Exceptions — the failure modes a codec can actually have — and
        // lets Errors through.
        val wiring = coordinator(
            registry(
                ErasedDirectiveDefinition(
                    ThrowingDefinition { StackOverflowError("simulated exhaustion, not a plugin defect") },
                ),
            ),
        )
        val marker = Files.createTempDirectory("s3r1-e").resolve("body.txt")

        val thrown = assertThrows(StackOverflowError::class.java) {
            run(wiring, compiled("s3r1-error", marker, StageDirective("acme.s3r1-throwing", "A")), "s3r1-error")
        }
        assertTrue(
            thrown.message!!.contains("not a plugin defect"),
            "the Error must reach the caller unchanged",
        )
        assertTrue(
            !Files.exists(marker),
            "the body must not have run",
        )
    }

    // ------------------------------------------------------------------
    // 4: the fault diagnostic must not echo author-supplied arguments
    // ------------------------------------------------------------------

    @Test
    fun `a definition fault does not echo the encoded arguments`() {
        val secret = "s3r1-canary-secret-value"
        val wiring = coordinator(
            registry(
                ErasedDirectiveDefinition(
                    ThrowingDefinition { IllegalStateException("decoder read $secret and failed") },
                ),
            ),
        )
        val marker = Files.createTempDirectory("s3r1-s").resolve("body.txt")
        val message = assertFailClosed(
            run(wiring, compiled("s3r1-secret", marker, StageDirective("acme.s3r1-throwing", secret)), "s3r1-secret"),
            wiring,
            "s3r1-secret",
            marker,
            "fault",
        )
        // The throwable's own message is included, so the payload must not be
        // reachable from it. This asserts the denial text; the throwable's message
        // is authored by the plugin, and a plugin that interpolates its arguments
        // into a message is responsible for its own leak — the seam must not add
        // the payload on top.
        assertTrue(
            !message.contains("arguments could not be decoded"),
            "the denial must not append the raw payload: $message",
        )
        assertEquals(
            1,
            message.split(secret).size - 1,
            "the encoded payload must appear at most once (only if the throwable message names it): $message",
        )
    }

    // ------------------------------------------------------------------
    // 5: the real agent definition, end to end, under hostile payloads
    // ------------------------------------------------------------------

    @Test
    fun `a hostile agent payload is denied as a value, with zero body effects`() {
        // The end-to-end half. The codec suite proves the codec is total; this
        // proves the totality REACHES THE RUN. These three payloads each threw
        // before S3-R1-A (arity 0 -> IllegalArgumentException, huge arity ->
        // OutOfMemoryError) and each crossed this seam as an exception rather
        // than a denial.
        val hostile = listOf(
            "L 0" to "empty label set",
            "C 0" to "empty capability set",
            "L 2147483647 " to "untrusted arity",
            "L 1 2147483647:x" to "declared length overflow",
        )

        for ((payload, why) in hostile) {
            val wiring = coordinator(registry(ErasedDirectiveDefinition(AgentDirectiveDefinition())))
            val marker = Files.createTempDirectory("s3r1-hostile").resolve("body.txt")
            val runId = "s3r1-hostile-${why.replace(' ', '-')}"

            val message = assertFailClosed(
                run(wiring, compiled("s3r1-hostile", marker, StageDirective("core.agent", payload)), runId),
                wiring,
                runId,
                marker,
                "hostile agent payload ($why) must be a denial, not an exception",
            )

            assertTrue(
                message.contains("could not be decoded"),
                "a malformed payload must read as an author error ($why): $message",
            )
            assertTrue(
                !message.contains("faulted"),
                "a malformed payload must never be reported as a plugin defect ($why): $message",
            )
        }
    }

    @Test
    fun `a valid agent payload still resolves the target and runs the body`() {
        // The counter-test, and the reason a narrower fix would have been wrong:
        // making the decoder total must not make it refuse everything.
        val wiring = coordinator(registry(ErasedDirectiveDefinition(AgentDirectiveDefinition())))
        val marker = Files.createTempDirectory("s3r1-valid").resolve("body.txt")
        val compiled = compiled(
            "s3r1-valid",
            marker,
            StageDirective("core.agent", "L 1 5:linux"),
        )

        val outcome = run(wiring, compiled, "s3r1-valid")

        assertTrue(
            outcome !is RunOutcome.Failure,
            "a single satisfiable label must still run: $outcome",
        )
        assertTrue(Files.exists(marker), "the body must have run for a satisfiable target")
        val stream = wiring.events.eventsFor(RunId("s3r1-valid").value).toList()
        assertTrue(
            stream.any { it is dev.rubentxu.pipeline.v2.events.ExecutionTargetResolved },
            "the resolved target must be observable, or 'the body ran' would not prove the " +
                "requirement was interpreted: ${stream.map { it::class.simpleName }}",
        )
    }
}
