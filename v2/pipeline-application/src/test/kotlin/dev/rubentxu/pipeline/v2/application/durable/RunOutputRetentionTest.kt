package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.PostSpec
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.output.OutputPruneIntent
import dev.rubentxu.pipeline.v2.output.OutputPruneReport
import dev.rubentxu.pipeline.v2.output.OutputRetentionPort
import dev.rubentxu.pipeline.v2.output.RetainUntil
import dev.rubentxu.pipeline.v2.output.SegmentOutputStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * S4 retention — the run's terminal state, connected to the Output Plane's release.
 *
 * ## The gap this covers
 *
 * `OutputRetentionPort` shipped with a complete closed vocabulary and **zero production callers**.
 * `M1_INTEGRATION_REGROUND_RECEIPT` recorded the consequence: the store is never pruned for the life
 * of a control root, and retention is "a product decision with no owner yet". A delete capability
 * with no owner is worse than none, because the first person to wire it must invent their own notion
 * of "has this run finished" — and any such notion re-derived from a scan or a journal query is a
 * second authority on run lifecycle.
 *
 * ## The shape, and what is deliberately NOT here
 *
 * ```text
 * RunOutcome / run terminal
 *   -> RunOutputRetention (the policy decision)
 *     -> OutputPruneIntent
 *       -> OutputRetentionPort.prune   (the store may delete)
 *         -> SegmentOutputStore       (deletes, and knows nothing about lifecycle)
 * ```
 *
 * The store is never told whether a run is alive. The **run's** terminal is, not a stage's: a
 * failing `post` finalizer calls `finalizeStage` on a stage that is aborting the whole run, so if
 * retention hung off stage finalization an aborting stage would release output that the run's own
 * failure message and post-mortem still need. `finalizeStage` appears in this file only as the thing
 * that must NOT decide — and the `post-failure` exit below is the row that proves it.
 *
 * ## Where the assertions are taken
 *
 * The effect boundary, not the policy object. [RunOutputRetention] folds a prune into a verdict the
 * coordinator interprets, so a test that watched the verdict would be watching a value the code
 * computed about itself. A recording [OutputRetentionPort] sees the decision as it crosses into the
 * only component that can act on it: which intent arrived, and what came back. The pure policy rows
 * (no release, the hold, the intent's shape) call [RunOutputRetention] directly, because there the
 * decision IS the subject.
 *
 * ## Fidelity
 *
 * HF2 with a real child process and the real on-disk store: the compiled pipeline runs through the
 * canonical coordinator, `sh` really executes, and the bytes really land in the plane that
 * [SegmentOutputStore] reads. Nothing here re-derives the decision under test, and no interpreter
 * stands in for the product.
 */
@Timeout(240)
class RunOutputRetentionTest {

    @BeforeEach
    fun resetProvider() {
        // One recovered store per control-dir root is the point of the provider; a test that
        // inherited another test's store would be measuring a store it did not write to.
        OutputPlaneProvider.forgetAll()
    }

    private fun linuxOnly() {
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
    }

    // ------------------------------------------------------------- fixtures

    private fun shNode(id: String, command: String) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.sh"),
        payload = VersionedStepPayload("dsl-v1", """{"kind":"sh","script":"$command"}"""),
    )

    private fun pipelineOf(id: String, stages: List<StageNode>): CompiledPipeline = CompiledPipeline(
        id = DefinitionId(id),
        source = SourceDescriptor("$id.pipeline.kts", Digest(id)),
        pluginLockDigest = Digest("$id-lock"),
        stages = stages,
    )

    private fun stageOf(name: String, command: String, post: PostSpec? = null) = StageNode(
        id = StageId(name),
        name = name,
        options = emptyList(),
        body = StageBody.Steps(listOf(shNode("$name/sh", command))),
        post = post,
    )

    private fun coordinator(
        controlRoot: Path,
        eventStore: InMemoryEventStore,
        retention: RunOutputRetention? = null,
    ) = CanonicalDurableRunCoordinator(
        dispatcher = CanonicalNodeDispatcher(),
        journal = InMemoryOperationJournal(SystemClock()),
        cursorStore = InMemoryReplayCursorStore(SystemClock()),
        clock = SystemClock(),
        effectReplayPolicy = DefaultEffectReplayPolicy(),
        eventSink = eventStore,
        credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
        controlDirRoot = controlRoot,
        commonExecutionBoundary = buildDefaultExecutionBoundary(
            dispatcher = CanonicalNodeDispatcher(),
            invocationExecutor = null,
            stepRegistry = CoreStepRegistryFactory.registry(),
        ),
        stepRegistry = CoreStepRegistryFactory.registry(),
        outputRetention = retention,
    )

    /** The production wiring: the very store the `sh` steps already wrote through, reached by supplier. */
    private fun retentionFor(controlRoot: Path, policy: RetainUntil) = RunOutputRetention(
        retention = { OutputPlaneProvider.storeFor(controlRoot) },
        policy = policy,
    )

    /**
     * A recording [OutputRetentionPort]: the observation point for every run-level row.
     *
     * It is a double for the SEAM, never for the policy. The policy is [RunOutputRetention]'s and is
     * not reimplemented here, so a row that passes is evidence about production's decision rather
     * than about this class's own arithmetic.
     */
    private class RecordingRetention(private val delegate: OutputRetentionPort) : OutputRetentionPort {
        val intents: MutableList<OutputPruneIntent> = mutableListOf()
        val reports: MutableList<OutputPruneReport> = mutableListOf()
        override fun hasOutputFor(runId: String): Boolean = delegate.hasOutputFor(runId)
        override fun prune(intent: OutputPruneIntent): OutputPruneReport {
            intents += intent
            return delegate.prune(intent).also { reports += it }
        }
    }

    private class NoopRetention : OutputRetentionPort {
        override fun hasOutputFor(runId: String): Boolean = false
        override fun prune(intent: OutputPruneIntent): OutputPruneReport = OutputPruneReport(0, 0L, 0)
    }

    /**
     * Stream directories this run owns, read off the durable layout.
     *
     * Deliberately NOT a parse of [OutputPlaneProvider.streamId]: the on-disk name comes from
     * `safe()`, which is lossy by design (B2), so a directory can be *found* but never reconstructed.
     * Asserting on the layout is the stronger claim anyway — it is the bytes a reader would find.
     */
    private fun streamDirsOnDisk(controlRoot: Path, runId: String): List<Path> {
        // "streams" is the store's private layout constant, spelled literally on purpose: these rows
        // read the DURABLE state directly rather than through the store's own index, so "the bytes
        // are gone / the bytes are still there" is a fact about the filesystem and not a fact about
        // a lookup the store would perform for us.
        val streams = controlRoot.resolve(OutputPlaneProvider.OUTPUT_DIR).resolve("streams")
        if (!Files.isDirectory(streams)) return emptyList()
        val prefix = runId.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_"
        return Files.newDirectoryStream(streams).use { entries ->
            entries.filter { Files.isDirectory(it) && it.fileName.toString().startsWith(prefix) }.toList()
        }
    }

    private fun durableTree(controlRoot: Path): List<String> =
        Files.walk(controlRoot).use { paths ->
            paths.map { controlRoot.relativize(it).toString() }.sorted().toList()
        }

    private fun bytesUnder(dirs: List<Path>): String =
        dirs.flatMap { dir -> Files.walk(dir).use { it.filter { f -> Files.isRegularFile(f) }.toList() } }
            .map { String(Files.readAllBytes(it), Charsets.UTF_8) }
            .joinToString("")

    /** Runs a pipeline with no retention at all, leaving a finished run's output in the plane. */
    private suspend fun runWithoutRetention(control: Path, pipeline: CompiledPipeline, runIdValue: String): RunOutcome =
        coordinator(control, InMemoryEventStore(), null).run(pipeline, RunId(runIdValue))

    // ------------------------------------------- 1. characterizing the terminal

    @Test
    fun `every run exit reaches the terminal hook exactly once and the stage never does`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))

        // Three DIFFERENT exits, because "the run ended" has to be a property of the run rather than
        // of any one exit path: a completed run, a run aborted by a step failure, and a run aborted
        // from inside finalizeStage by a failing post finalizer. The third is the one that matters
        // most for this wiring — finalizeStage runs for a stage that is killing the run, and it must
        // not be the thing that releases the output.
        val exits = listOf(
            Triple(
                "completed",
                "r-terminal-completed",
                pipelineOf("p-completed", listOf(stageOf("build", "echo completed-marker"))),
            ),
            Triple(
                "step-failure",
                "r-terminal-step-failure",
                pipelineOf("p-step-failure", listOf(stageOf("build", "exit 7"))),
            ),
            Triple(
                "post-failure",
                "r-terminal-post-failure",
                pipelineOf(
                    "p-post-failure",
                    listOf(
                        stageOf(
                            "build",
                            "echo post-marker",
                            post = PostSpec(
                                conditions = mapOf(PostCondition.ALWAYS to listOf(shNode("post/always-fail", "exit 3"))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        for ((label, runIdValue, pipeline) in exits) {
            val recording = RecordingRetention(OutputPlaneProvider.storeFor(control))

            val outcome = coordinator(
                controlRoot = control,
                eventStore = InMemoryEventStore(),
                retention = RunOutputRetention({ recording }, RetainUntil.RunTerminalPlus),
            ).run(pipeline, RunId(runIdValue))

            assertEquals(
                1,
                recording.intents.size,
                "the '$label' exit must authorise exactly one release, and only at the run terminal: " +
                    recording.intents,
            )
            val intent = recording.intents.single()
            assertInstanceOf(
                OutputPruneIntent.RunReachedTerminalState::class.java,
                intent,
                "the run's terminality is the only reason that may be claimed, got $intent",
            )
            assertEquals(runIdValue, intent.runId, "the intent must name the run it speaks about")
            assertTrue(
                outcome is RunOutcome.Success || outcome is RunOutcome.Failure,
                "the exit under study produced a terminal outcome: $outcome",
            )
        }
    }

    @Test
    fun `a failed run still releases under the declared policy and keeps its own outcome`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val runIdValue = "r-failed-release"
        val store = OutputPlaneProvider.storeFor(control)

        val outcome = coordinator(
            controlRoot = control,
            eventStore = InMemoryEventStore(),
            retention = retentionFor(control, RetainUntil.RunTerminalPlus),
        ).run(
            pipelineOf("p-failed", listOf(stageOf("build", "echo failed-marker; exit 7"))),
            RunId(runIdValue),
        )

        // Retention asks "has the run ended", never "did it succeed". A policy that released only
        // successful runs would leave the transcripts of failed runs behind forever, which are
        // exactly the ones a post-mortem needs.
        assertInstanceOf(
            RunOutcome.Failure::class.java,
            outcome,
            "the run must still report its own failure, got $outcome",
        )
        assertFalse(
            store.hasOutputFor(runIdValue),
            "a failed run's output is still the run's output and the same policy releases it",
        )
    }

    // ------------------------------------------------- 2. the release is real

    @Test
    fun `a finished run releases every stream its stages wrote in one pass`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val runIdValue = "r-one-pass"
        val recording = RecordingRetention(OutputPlaneProvider.storeFor(control))

        val outcome = coordinator(
            controlRoot = control,
            eventStore = InMemoryEventStore(),
            retention = RunOutputRetention({ recording }, RetainUntil.RunTerminalPlus),
        ).run(
            pipelineOf(
                "p-two-stages",
                listOf(stageOf("first", "echo first-marker"), stageOf("second", "echo second-marker")),
            ),
            RunId(runIdValue),
        )

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(1, recording.intents.size, "one pass, at the run terminal: ${recording.intents}")
        val report = recording.reports.single()
        assertEquals(
            2,
            report.streamsRemoved,
            "BOTH stages' output is released in the run's single pass; a per-stage prune would report 1",
        )
        assertTrue(report.bytesReleased > 0, "the release must account for real committed bytes")
        assertEquals(0, report.streamsRetained, "nothing resisted the release on a healthy filesystem")
        assertTrue(
            streamDirsOnDisk(control, runIdValue).isEmpty(),
            "the stream directories must be gone from the durable layout, not merely unreferenced",
        )
    }

    @Test
    fun `the declared policy keeps a finished run's console readable`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val runIdValue = "r-retained"
        val store = OutputPlaneProvider.storeFor(control)

        val outcome = coordinator(
            controlRoot = control,
            eventStore = InMemoryEventStore(),
            retention = retentionFor(control, RetainUntil.ExplicitReleaseOnly),
        ).run(
            pipelineOf("p-retained", listOf(stageOf("build", "echo retained-marker"))),
            RunId(runIdValue),
        )

        assertEquals(RunOutcome.Success, outcome)
        assertTrue(store.hasOutputFor(runIdValue), "the product's own console reads a FINISHED run's transcript")
        val bytes = bytesUnder(streamDirsOnDisk(control, runIdValue))
        assertTrue(
            bytes.contains("retained-marker"),
            "the bytes must still be there, not merely accounted for: '$bytes'",
        )
    }

    // ------------------------------------------- 3. the policy, in isolation

    @Test
    fun `a retaining policy never opens the plane at all`() {
        // Recovery is not free (ADR-M1 D4/O3) and a configuration that retains everything must not pay
        // for — nor be perturbed by — a store it never uses. Falsable: a supplier that would be
        // observable is the assertion.
        var resolved = 0
        val retention = RunOutputRetention(
            retention = {
                resolved++
                NoopRetention()
            },
            policy = RetainUntil.ExplicitReleaseOnly,
        )

        assertEquals(
            RunOutputDisposition.Retained,
            retention.onRunTerminal(RunId("r-never-opened")),
        )
        assertEquals(0, resolved, "the store must not even be resolved when no release is authorised")
    }

    @Test
    fun `Forever never authorises an automatic release`() {
        // Forever is the retention HOLD. If the terminal could authorise it, a hold would be
        // unenforceable exactly when it is needed: at the end of a run.
        val retention = RunOutputRetention(
            retention = { NoopRetention() },
            policy = RetainUntil.Forever,
        )

        assertNull(retention.intentFor(RunId("r-held")), "a hold produces no intent to interpret")
        assertEquals(RunOutputDisposition.Retained, retention.onRunTerminal(RunId("r-held")))
    }

    @Test
    fun `the release intent carries the run and nothing else`() {
        // `RunOutcome` has exactly one owner (:pipeline-domain) and a prune authorisation is not a
        // report on how the run went. Re-spelling the outcome inside the intent would make this
        // seam a second place where "unstable" and "failed" could each be written down, and the two
        // would drift with no compiler or fitness able to see it.
        val type = OutputPruneIntent.RunReachedTerminalState::class.java

        assertEquals(
            listOf(String::class.java.name),
            type.declaredFields.map { it.type.name },
            "the terminal-state intent carries the run id and nothing else",
        )
        assertFalse(
            type.declaredMethods.map { it.name }.any { it.contains("utcome", ignoreCase = true) },
            "no accessor may re-spell the run's outcome",
        )
    }

    // ------------------------------------------------------- 4. adversarial

    @Test
    fun `a repeated release is idempotent`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val runIdValue = "r-idempotent"
        runWithoutRetention(control, pipelineOf("p-idem", listOf(stageOf("build", "echo idem-marker"))), runIdValue)

        val store = OutputPlaneProvider.storeFor(control)
        val retention = retentionFor(control, RetainUntil.RunTerminalPlus)

        val first = assertInstanceOf(
            RunOutputDisposition.Released::class.java,
            retention.onRunTerminal(RunId(runIdValue)),
            "the first release does the work",
        )
        assertTrue(first.report.streamsRemoved > 0, "the first release removed real streams")

        val second = assertInstanceOf(
            RunOutputDisposition.Released::class.java,
            retention.onRunTerminal(RunId(runIdValue)),
            "a repeated release is not an error",
        )
        assertEquals(0, second.report.streamsRemoved, "there is nothing left the second time")
        assertEquals(0L, second.report.bytesReleased, "and no bytes to account for")
        assertFalse(store.hasOutputFor(runIdValue), "still released")
    }

    @Test
    fun `a restart between the terminal and the release reaches the same decision`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val runIdValue = "r-restart"
        runWithoutRetention(control, pipelineOf("p-restart", listOf(stageOf("build", "echo restart-marker"))), runIdValue)

        // Process 1 releases.
        val first = retentionFor(control, RetainUntil.RunTerminalPlus).onRunTerminal(RunId(runIdValue))
        assertInstanceOf(RunOutputDisposition.Released::class.java, first, "the first process releases: $first")

        // Process 2: forgetAll() drops the cached store, so the next access recovers a NEW instance
        // from the same durable directory. That is the crash boundary this provider is built for.
        OutputPlaneProvider.forgetAll()
        val retention = retentionFor(control, RetainUntil.RunTerminalPlus)
        OutputPlaneProvider.storeFor(control)
        val afterRestart = durableTree(control)

        val second = retention.onRunTerminal(RunId(runIdValue))

        assertEquals(
            first::class,
            second::class,
            "the restarted process reaches the same verdict: first=$first second=$second",
        )
        assertEquals(
            afterRestart,
            durableTree(control),
            "a second process must not write a cleanup record of its own: the decision is reconstructed " +
                "from the run's terminal state and the declared policy, not from a durable queue",
        )
    }

    @Test
    fun `a release that cannot happen does not change the run outcome`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val recording = RecordingRetention(
            // An UNRECOVERED store is a real condition, not a mock: the store refuses to act until
            // recovery has run, and inside a `finally` a throw from here would replace the run's own
            // result — a successful build reported as failed because a post-run deletion tripped.
            SegmentOutputStore(control.resolve(OutputPlaneProvider.OUTPUT_DIR)),
        )

        val outcome = coordinator(
            controlRoot = control,
            eventStore = InMemoryEventStore(),
            retention = RunOutputRetention({ recording }, RetainUntil.RunTerminalPlus),
        ).run(pipelineOf("p-unrecovered", listOf(stageOf("build", "echo unrecovered-marker"))), RunId("r-unrecovered"))

        assertEquals(
            RunOutcome.Success,
            outcome,
            "a build is not failed because a post-run deletion could not complete",
        )
        assertEquals(
            1,
            recording.intents.size,
            "the release was still authorised — the failure is in the effect, not the decision: ${recording.intents}",
        )
        assertTrue(
            recording.reports.isEmpty(),
            "an unrecovered store returns no report, because it refused rather than tried",
        )
    }
}
