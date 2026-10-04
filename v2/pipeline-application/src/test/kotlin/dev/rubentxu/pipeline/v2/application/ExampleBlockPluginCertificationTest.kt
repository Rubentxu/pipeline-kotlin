package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import example.block.RepeatBodyStepDefinition
import example.block.RepeatInput
import example.block.RepeatInputCodec
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WU-RP-035 / slice D: the TIMES laws over the REAL external JAR.
 *
 * Everything here runs against `example.repeat` exactly as a third-party plugin would
 * enter the system: the definition is resolved by SERVICELOADER discovery from the JAR
 * built by `buildExampleBlockPlugin` from THIS revision's public SDK — never manually
 * registered. If the engine special-cased the key anywhere, these rows would still pass;
 * what they prove is that the open registry is sufficient.
 *
 * The five blocks of law (operator contract, slice D):
 *
 *  - `times = 0`: the handler runs, the continuation is invoked zero times, and the body
 *    produces zero child effects. THE handler decides whether the body runs.
 *  - `times = 3`: three distinct per-iteration body paths, each with its own journal rows.
 *    THE handler decides how many times, and the engine gives each iteration durable
 *    identity — the plugin never builds a path.
 *  - stop-and-propagate: a failure in iteration N stops the loop and surfaces as a typed
 *    `USER` failure, not N+1 execution and not an ENGINE misclassification.
 *  - replay: the same program on the same runId is durable reuse — the handler is NOT
 *    invoked a second time.
 *  - determinism: repeated runs of the same program produce the same operation ids; nothing
 *    in the identity derives from a clock, a UUID or a global counter.
 */
@Timeout(60)
class ExampleBlockPluginCertificationTest {

    private fun registry() = dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry().apply {
        // The REAL discovery path: ServiceLoader over the external plugin JARs on the test
        // classpath. `example.repeat` enters exactly as a third-party contribution.
        ExternalStepPluginDiscovery.registerInto(this)
        CoreEchoStep.registerInto(this)
        CoreErrorStep.registerInto(this)
    }

    private fun harness() = run {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("example.repeat certification stub"),
                )
            },
            controlDirRoot = Files.createTempDirectory("repeat-cert-").resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
        )
        coordinator to journal
    }

    private fun stage(block: BlockStepNode) = CompiledPipeline(
        id = DefinitionId("repeat-cert"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(id = StageId("B"), name = "B", body = StageBody.Steps(listOf(block))),
        ),
    )

    private fun repeatBlock(times: Int, vararg children: StepNode) = BlockStepNode(
        id = StepId("external/repeat-body-1"),
        pluginStepId = RepeatBodyStepDefinition.KEY,
        payload = VersionedStepPayload(
            "dsl-v1",
            RepeatInputCodec.encode(RepeatInput(times)).value,
        ),
        body = children.toList(),
    )

    private fun echoChild(text: String) = OpaqueStepNode(
        id = StepId("external/repeat-body-1/echo-1"),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload("dsl-v1", "{\"kind\":\"echo\",\"text\":\"$text\"}"),
    )

    private fun errorChild(message: String) = OpaqueStepNode(
        id = StepId("external/repeat-body-1/error-1"),
        pluginStepId = PluginStepId("core.error"),
        payload = VersionedStepPayload(
            "dsl-v1",
            "{\"kind\":\"error\",\"message\":\"$message\",\"failureKind\":\"USER\"}",
        ),
    )

    private fun childRowIds(journal: InMemoryOperationJournal, runId: String) =
        journal.listForRun(runId).map { it.id }.filter { it.contains("-bp") }

    @Test
    fun `times 0 - handler runs and the body produces zero child effects`() = runBlocking {
        val (coordinator, journal) = harness()

        val outcome = coordinator.run(stage(repeatBlock(0, echoChild("never"))), RunId("rc0"))

        assertEquals(RunOutcome.Success, outcome)
        assertTrue(
            childRowIds(journal, "rc0").isEmpty(),
            "times=0 must run the continuation ZERO times; observed " +
                "${childRowIds(journal, "rc0")}",
        )
        assertTrue(
            journal.listForRun("rc0").isNotEmpty(),
            "the parent itself is still journaled: the handler ran",
        )
    }

    @Test
    fun `times 3 - three distinct per-iteration body paths with their own journal rows`() = runBlocking {
        val (coordinator, journal) = harness()

        val outcome = coordinator.run(stage(repeatBlock(3, echoChild("tick"))), RunId("rc3"))

        assertEquals(RunOutcome.Success, outcome)
        val ids = childRowIds(journal, "rc3")
        assertEquals(
            3,
            ids.size,
            "each iteration must have its own durable row; observed $ids",
        )
        val repeatKey = RepeatBodyStepDefinition.REPEAT_ATTEMPT_KEY.value
        assertEquals(
            listOf("1", "2", "3"),
            ids.map { id -> Regex("-bp2-(\\d+):$repeatKey").find(id)?.groupValues?.get(1) },
            "iterations must be identified by the PLUGIN-OWNED repeat attempt key in order",
        )
    }

    @Test
    fun `failure in iteration N stops the loop and propagates the typed failure`() = runBlocking {
        val (coordinator, journal) = harness()

        val outcome = coordinator.run(
            stage(repeatBlock(3, errorChild("boom-on-first"), echoChild("never"))),
            RunId("rcf"),
        )

        assertTrue(outcome is RunOutcome.Failure, "a body failure must fail the run, got $outcome")
        val ids = childRowIds(journal, "rcf")
        assertEquals(
            1,
            ids.size,
            "the loop must stop at the first failing iteration; executed $ids",
        )
        assertTrue(
            outcome is RunOutcome.Failure &&
                (outcome as RunOutcome.Failure).failure.message.contains("boom-on-first"),
            "the typed USER failure must carry the body's message, got " +
                "${(outcome as RunOutcome.Failure).failure}",
        )
    }

    @Test
    fun `replay - the same program on the same runId does not invoke the handler again`() = runBlocking {
        val (coordinator, journal) = harness()

        val first = coordinator.run(stage(repeatBlock(2, echoChild("tick"))), RunId("rcr"))
        assertEquals(RunOutcome.Success, first)

        val rowsAfterFirst = journal.listForRun("rcr").size

        val second = coordinator.run(stage(repeatBlock(2, echoChild("tick"))), RunId("rcr"))

        assertEquals(RunOutcome.Success, second)
        assertEquals(
            rowsAfterFirst,
            journal.listForRun("rcr").size,
            "a MEMOIZED handler-driven Step must replay from the journal without re-executing " +
                "the handler or the body",
        )
    }

    @Test
    fun `determinism - the same program yields the same operation ids across harnesses`() = runBlocking {
        val (firstCoordinator, firstJournal) = harness()
        firstCoordinator.run(stage(repeatBlock(2, echoChild("tick"))), RunId("rcd"))
        val (secondCoordinator, secondJournal) = harness()
        secondCoordinator.run(stage(repeatBlock(2, echoChild("tick"))), RunId("rcd"))

        assertEquals(
            childRowIds(firstJournal, "rcd"),
            childRowIds(secondJournal, "rcd"),
            "identity must be a pure function of the program: same run, same ids, no clock, " +
                "no UUID, no global counter",
        )
    }
}
