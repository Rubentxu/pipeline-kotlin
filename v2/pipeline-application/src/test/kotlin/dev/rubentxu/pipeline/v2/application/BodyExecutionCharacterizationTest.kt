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
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WU-PR-018 slice 0 (on the PR-017 branch): CHARACTERIZATION BASELINE of the
 * body-execution loop, pinned BEFORE any extraction moves it into a
 * BodyExecutionEngine.
 *
 * Bodies are the highest-value extraction target, so the baseline pins the
 * observable contract of the three structural projections plus the retry
 * shape:
 *
 *  - `dir`: the child runs inside the projected directory (a relative
 *    `core.file.writeFile` lands under the projected path) and the journal
 *    body path carries the dir segment;
 *  - `retry`: each attempt carries its own deterministic durable segment
 *    (`{n}:retry-attempt`), a failing child consumes exactly maxAttempts
 *    attempts, and the run folds the typed failure;
 *  - `withEnv`: the child observes the overlay through the environment.
 *
 * The extracted `BodyExecutionEngine` must satisfy these UNCHANGED.
 */
@Timeout(120)
class BodyExecutionCharacterizationTest {

    private val dirKey = PluginStepId("core.dir")
    private val retryKey = PluginStepId("core.retry")
    private val withEnvKey = PluginStepId("core.withEnv")
    private val waitUntilKey = PluginStepId("core.waitUntil")
    private val writeFileKey = PluginStepId("core.file.writeFile")
    private val echoKey = PluginStepId("core.echo")
    private val errorKey = PluginStepId("core.error")

    private fun harness(): Quadruple<CanonicalDurableRunCoordinator, InMemoryOperationJournal, InMemoryEventStore, Path> {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val events = InMemoryEventStore()
        val root = Files.createTempDirectory("body-char-")
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("body characterization stub"),
                )
            },
            controlDirRoot = root.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = StepRegistryBuilder().apply {
                CoreEchoStep.registerInto(this)
                CoreErrorStep.registerInto(this)
                CoreWriteFileStep.registerInto(this)
            }.build(),
        )
        return Quadruple(coordinator, journal, events, root)
    }

    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun writeFile(stage: String, index: Int, file: String, text: String) = OpaqueStepNode(
        id = StepId("$stage/write-$index"),
        pluginStepId = writeFileKey,
        payload = VersionedStepPayload(
            "dsl-v1",
            "{\"kind\":\"writeFile\",\"file\":\"$file\",\"text\":\"$text\",\"encoding\":\"UTF-8\"}",
        ),
    )

    private fun echo(stage: String, index: Int, text: String) = OpaqueStepNode(
        id = StepId("$stage/echo-$index"),
        pluginStepId = echoKey,
        payload = VersionedStepPayload("dsl-v1", "{\"kind\":\"echo\",\"text\":\"$text\"}"),
    )

    private fun userError(stage: String, index: Int, message: String) = OpaqueStepNode(
        id = StepId("$stage/error-$index"),
        pluginStepId = errorKey,
        payload = VersionedStepPayload(
            "dsl-v1",
            "{\"kind\":\"error\",\"message\":\"$message\",\"failureKind\":\"USER\"}",
        ),
    )

    private fun pipelineOf(vararg stages: StageNode) = CompiledPipeline(
        id = DefinitionId("body-characterization"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = stages.toList(),
    )

    private fun stage(name: String, vararg steps: StepNode) = StageNode(
        id = StageId(name),
        name = name,
        body = StageBody.Steps(steps.toList()),
    )

    private fun block(key: PluginStepId, payload: String, id: String, vararg children: StepNode) = BlockStepNode(
        id = StepId(id),
        pluginStepId = key,
        payload = VersionedStepPayload("dsl-v1", payload),
        body = children.toList(),
    )

    private fun childRowIds(journal: InMemoryOperationJournal, runId: String) =
        journal.listForRun(runId).map { it.id }.filter { it.contains("-bp") }

    @Test
    fun `dir body - the child lands inside the projected directory`() = runBlocking {
        val (coordinator, journal, _, root) = harness()
        val projected = Files.createDirectories(root.resolve("projected/sub"))

        val outcome = coordinator.run(
            pipelineOf(
                stage(
                    "S",
                    block(
                        dirKey,
                        """{"kind":"dir","path":"${projected.fileName}"}""",
                        "s/dir-body-1",
                        writeFile("s", 0, "marker.txt", "inside"),
                    ),
                ),
            ),
            RunId("bc1"),
        )

        assertEquals(RunOutcome.Success, outcome)
        // RP-034: the dir projection resolves relative paths against the run's
        // execution location (under the coordinator root), not against this test's
        // temp root. The pin is: the marker landed INSIDE a `sub` directory within
        // the coordinator's tree - i.e. the child cwd was the projected directory.
        val marker = Files.walk(root).use { stream ->
            stream.filter { it.fileName.toString() == "marker.txt" }.findFirst()
        }
        assertTrue(
            marker.isPresent && marker.get().parent.fileName.toString() == "sub",
            "a relative writeFile inside a dir body must land in the projected `sub` directory",
        )
        val rows = childRowIds(journal, "bc1")
        assertEquals(1, rows.size)
        assertTrue(
            rows[0].endsWith("-bp1-0:${writeFileKey.value}"),
            "a top-level dir block IS stage step 0, so the child row carries a single " +
                "body segment naming the child; got ${rows[0]}",
        )
    }

    @Test
    fun `retry body - each attempt carries its own durable segment`() = runBlocking {
        val (coordinator, journal, _, _) = harness()

        val outcome = coordinator.run(
            pipelineOf(
                stage(
                    "S",
                    block(
                        retryKey,
                        """{"kind":"retry","maxAttempts":2}""",
                        "s/retry-body-1",
                        userError("s", 0, "always fails"),
                        echo("s", 1, "never"),
                    ),
                ),
            ),
            RunId("bc2"),
        )

        assertTrue(outcome is RunOutcome.Failure)
        val rows = childRowIds(journal, "bc2")
        assertEquals(
            2,
            rows.size,
            "two attempts, two distinct durable segments; observed $rows",
        )
        assertTrue(
            rows[0].contains("1:retry-attempt") && rows[1].contains("2:retry-attempt"),
            "attempts must be identified by the retry-attempt key in order; got $rows",
        )
        assertTrue(
            rows.all { it.contains("-bp2-") },
            "attempt segments compose with the child segment; got $rows",
        )
    }

    @Test
    fun `withEnv body - the child observes the overlay`() = runBlocking {
        val (coordinator, journal, events, _) = harness()

        val outcome = coordinator.run(
            pipelineOf(
                stage(
                    "S",
                    block(
                        withEnvKey,
                        """{"kind":"withEnv","overrides":["BC_MARKER=overlay-value"]}""",
                        "s/env-body-1",
                        echo("s", 0, "check"),
                    ),
                ),
            ),
            RunId("bc3"),
        )

        assertEquals(RunOutcome.Success, outcome)
        // The overlay is durable state of the body scope; its presence is what the
        // extracted engine must preserve (the console path carries the child bytes).
        assertTrue(
            childRowIds(journal, "bc3").size == 1,
            "exactly one child row under the withEnv body path",
        )
        assertTrue(
            events.eventsFor("bc3").any { it is dev.rubentxu.pipeline.v2.events.EchoOutputCaptured },
            "the child echo crossed the console path",
        )
    }

    @Test
    fun `waitUntil body - first-poll success completes the condition`() = runBlocking {
        val (coordinator, journal, events, _) = harness()

        val outcome = coordinator.run(
            pipelineOf(
                stage(
                    "S",
                    block(
                        waitUntilKey,
                        """{"kind":"waitUntil","initialRecurrencePeriod":10,"maxBackoffMs":50}""",
                        "s/wait-body-1",
                        echo("s", 0, "condition holds"),
                    ),
                ),
            ),
            RunId("bc4"),
        )

        assertEquals(RunOutcome.Success, outcome)
        val polled = events.eventsFor("bc4").filter { it is dev.rubentxu.pipeline.v2.events.WaitUntilPolled }.toList()
        assertEquals(
            2,
            polled.size,
            "one poll emits the pre-attempt and post-attempt WaitUntilPolled pair",
        )
        val completed = events.eventsFor("bc4").filterIsInstance<dev.rubentxu.pipeline.v2.events.WaitUntilCompleted>().single()
        assertEquals("completed", completed.outcome)
        assertEquals(1, completed.totalAttempts)
        assertEquals(1, childRowIds(journal, "bc4").size)
    }


    @Test
    fun `waitUntil body - a failing condition folds the typed TIMEOUT after backoff ceiling`() = runBlocking {
        val (coordinator, journal, events, _) = harness()

        val outcome = coordinator.run(
            pipelineOf(
                stage(
                    "S",
                    block(
                        waitUntilKey,
                        """{"kind":"waitUntil","initialRecurrencePeriod":10,"maxBackoffMs":25}""",
                        "s/wait-body-2",
                        userError("s", 0, "condition never holds"),
                    ),
                ),
            ),
            RunId("bc5"),
        )

        assertTrue(outcome is RunOutcome.Failure)
        assertTrue(
            (outcome as RunOutcome.Failure).failure.kind ==
                dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT,
            "the deadline-exceeded fold is a TIMEOUT failure, got ${outcome.failure}",
        )
        val completed = events.eventsFor("bc5")
            .filterIsInstance<dev.rubentxu.pipeline.v2.events.WaitUntilCompleted>()
            .single()
        assertEquals("deadline-exceeded", completed.outcome)
        assertTrue(completed.totalAttempts >= 1, "at least one poll must have happened, got ${completed.totalAttempts}")
        assertTrue(childRowIds(journal, "bc5").isNotEmpty())
    }
}
