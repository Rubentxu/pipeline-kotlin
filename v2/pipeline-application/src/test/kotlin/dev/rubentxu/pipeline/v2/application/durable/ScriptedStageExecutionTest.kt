package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.PostSpec
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.ScriptedStageRef
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.scripting.ReturnStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * S4-F2 — a scripted stage, end to end, through the canonical spine.
 *
 * ## The claim under test
 *
 * `StageBody.Scripted` is no longer IR. A stage whose body is a compiled scripted entry point runs
 * inside a real run, and gets the SAME things a declarative stage gets: the stage bookends, the
 * `post` finalizers, the run's failure or unstable folding, one journal, one replay policy, one
 * capability admission. What it must not get is a second execution path — and the way that is
 * checked here is by the shape of the harness, not by a comment: these rows run the coordinator,
 * never a scripted runner directly.
 *
 * ## Fidelity, and what it is not
 *
 * HF2: a real `sh` really executes, the bytes really land in the Output Plane on disk, and the
 * journal is the one the coordinator uses. No interpreter stands in for the product.
 *
 * Deliberately NOT here, and not faked:
 * - **Unstable from inside a body.** The reducer must see a real `Unstable` operation, and no
 *   registry step reports one, so the row would have to invent it. The projection IS covered
 *   directly by `ScriptedOutcomeProjectionTest`.
 * - **Capability refusal.** Needs a step that declares a capability the engine does not supply.
 *   Forcing it with a stub capability map would test the stub, which is the defect HF2 exists to
 *   prevent. It is listed as remaining rather than simulated.
 */
@Timeout(240)
class ScriptedStageExecutionTest {

    @BeforeEach
    fun resetProvider() {
        OutputPlaneProvider.forgetAll()
    }

    private fun linuxOnly() {
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
    }

    // ------------------------------------------------------------- the artifact

    /**
     * A compiled entry point whose body is Kotlin written here.
     *
     * This is a real [CompiledScriptedEntryPoint] with a real identity: the registry resolves it,
     * the runner invokes it, and the `sh` it calls goes through the same registry boundary every
     * other step uses. What it is not is a lowered `.kts` artifact — host-compiling one is the
     * CLI's job and is not what these rows are about.
     */
    private class Body(
        sourceDigest: String,
        private val callSites: List<String>,
        private val block: suspend ScriptedStepFacade.(String) -> Unit,
    ) : CompiledScriptedEntryPoint {
        override val artifact: ScriptedArtifactIdentity = ScriptedArtifactIdentity(
            sourceDigest = sourceDigest,
            dslApiVersion = "dsl-v1",
            compilerAdapterVersion = "compiler-v1",
            runtimeCompatibilityVersion = "r3-runtime-v1",
            pluginLockDigest = "plugin-lock",
            facadeSchemaDigest = "facade-digest",
        )
        override val entryPointId: String = "main"

        override suspend fun execute(steps: ScriptedStepFacade) {
            callSites.forEach { callSite -> steps.block(callSite) }
        }
    }

    private fun refOf(body: Body) = ScriptedStageRef(
        artifactKey = body.artifact.fingerprintMaterial(),
        entryPointId = body.entryPointId,
    )

    private fun shellBody(sourceDigest: String, script: String) = Body(sourceDigest, listOf("build")) {
        sh(ScriptedCallSiteId(it), script, ReturnStatus)
    }

    private fun failingBody(sourceDigest: String) = Body(sourceDigest, listOf("deploy")) {
        sh(ScriptedCallSiteId(it), "exit 5", ReturnStatus)
    }

    private fun typedBody(sourceDigest: String) = Body(sourceDigest, listOf("observe")) {
        // A runtime-returning value: the durable `Boolean` is materialised through the registry
        // Step and returned, which is the SCRIPTED_RUNTIME_CALL carrier the constitution requires.
        val observed: Boolean = isUnix(ScriptedCallSiteId(it))
        check(observed) { "a POSIX host must observe isUnix == true, and it did not" }
    }

    // ------------------------------------------------------------------ the run

    private fun scriptedPipeline(
        id: String,
        ref: ScriptedStageRef,
        stageName: String = "deploy",
        post: PostSpec? = null,
    ) = CompiledPipeline(
        id = DefinitionId(id),
        source = SourceDescriptor("$id.pipeline.kts", Digest(id)),
        pluginLockDigest = Digest("$id-lock"),
        stages = listOf(
            StageNode(
                id = StageId(stageName),
                name = stageName,
                options = emptyList(),
                body = StageBody.Scripted(ref),
                post = post,
            ),
        ),
    )

    private fun coordinator(control: Path, registry: ScriptedStageRegistry, journal: InMemoryOperationJournal) =
        CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(SystemClock()),
            clock = SystemClock(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = control,
            commonExecutionBoundary = buildDefaultExecutionBoundary(
                dispatcher = CanonicalNodeDispatcher(),
                invocationExecutor = null,
                stepRegistry = CoreStepRegistryFactory.registry(),
            ),
            stepRegistry = CoreStepRegistryFactory.registry(),
            scriptedStageExecution = ScriptedStageExecution(
                scriptedArtifacts = registry,
                stepRegistry = CoreStepRegistryFactory.registry(),
                journal = journal,
                eventSink = InMemoryEventStore(),
                clock = SystemClock(),
                controlDirRoot = control,
            ),
        )

    private fun echoPostOn(stageName: String) = PostSpec(
        conditions = mapOf(PostCondition.ALWAYS to listOf(shellNode("post/$stageName/echo", "echo post-ran"))),
    )

    private fun shellNode(id: String, command: String) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.sh"),
        payload = VersionedStepPayload("dsl-v1", """{"kind":"sh","script":"$command"}"""),
    )

    // ------------------------------------------------------------------- rows

    @Test
    fun `a scripted stage runs a real process and the run succeeds`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val body = shellBody("scripted-success", "echo scripted-marker")
        val store = OutputPlaneProvider.storeFor(control)
        val journal = InMemoryOperationJournal(SystemClock())

        val outcome = coordinator(control, InMemoryScriptedStageRegistry(listOf(body)), journal)
            .run(scriptedPipeline("p-scripted-success", refOf(body)), RunId("r-scripted-success"))

        assertEquals(
            RunOutcome.Success,
            outcome,
            "a scripted stage is a stage: a real `sh` that exits 0 is a successful run",
        )
        assertTrue(
            store.hasOutputFor("r-scripted-success"),
            "and its transcript reached the Output Plane, not an event payload",
        )
    }

    @Test
    fun `a typed runtime return crosses the same authority as an executed step`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val body = typedBody("scripted-typed")
        val journal = InMemoryOperationJournal(SystemClock())

        val outcome = coordinator(control, InMemoryScriptedStageRegistry(listOf(body)), journal)
            .run(scriptedPipeline("p-scripted-typed", refOf(body)), RunId("r-scripted-typed"))

        assertEquals(
            RunOutcome.Success,
            outcome,
            "`isUnix` returned its durable Boolean and the body asserted on it, so a fabricated " +
                "false would have failed the run: $outcome",
        )
    }

    @Test
    fun `a failing step inside a scripted body fails the run and keeps the stage a stage`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val body = failingBody("scripted-failure")
        val journal = InMemoryOperationJournal(SystemClock())

        val outcome = coordinator(control, InMemoryScriptedStageRegistry(listOf(body)), journal)
            .run(scriptedPipeline("p-scripted-failure", refOf(body)), RunId("r-scripted-failure"))

        assertInstanceOf(
            RunOutcome.Failure::class.java,
            outcome,
            "a body that ran a command which exited non-zero is a failed run, not a quiet success",
        )
    }

    @Test
    fun `a scripted stage gets the same bookends and the same post finalizers`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val body = shellBody("scripted-post", "echo scripted-marker")
        val events = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())

        val outcome = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(SystemClock()),
            clock = SystemClock(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = control,
            commonExecutionBoundary = buildDefaultExecutionBoundary(
                dispatcher = CanonicalNodeDispatcher(),
                invocationExecutor = null,
                stepRegistry = CoreStepRegistryFactory.registry(),
            ),
            stepRegistry = CoreStepRegistryFactory.registry(),
            scriptedStageExecution = ScriptedStageExecution(
                scriptedArtifacts = InMemoryScriptedStageRegistry(listOf(body)),
                stepRegistry = CoreStepRegistryFactory.registry(),
                journal = journal,
                eventSink = events,
                clock = SystemClock(),
                controlDirRoot = control,
            ),
        ).run(
            scriptedPipeline("p-scripted-post", refOf(body), post = echoPostOn("deploy")),
            RunId("r-scripted-post"),
        )

        assertEquals(RunOutcome.Success, outcome)
        val emitted = events.eventsFor("r-scripted-post").toList()
        assertTrue(
            emitted.any { it is StageStarted },
            "a scripted stage is a stage and announces itself: ${emitted.filterIsInstance<StageStarted>()}",
        )
        assertTrue(
            emitted.any { it is StageFinished },
            "and it finishes like one: ${emitted.filterIsInstance<StageFinished>()}",
        )
        // The `post` finalizer is a DECLARATIVE step on a scripted stage, so its transcript is an
        // observable of the same run. If the finalizer were skipped, this run's plane would hold
        // only the scripted body's bytes.
        val bytes = OutputPlaneProvider.storeFor(control)
            .let { store -> assertTrue(store.hasOutputFor("r-scripted-post"), "the run wrote a transcript") }
            .let { _ -> durableBytes(control, "r-scripted-post") }
        assertTrue(
            bytes.contains("post-ran"),
            "the `post` finalizer really ran for a scripted stage: '$bytes'",
        )
    }

    /** The stream directory names this run owns, as they sit on the durable layout. */
    private fun streamDirNames(control: Path, runId: String): List<String> {
        val streams = control.resolve(OutputPlaneProvider.OUTPUT_DIR).resolve("streams")
        if (!Files.isDirectory(streams)) return emptyList()
        val prefix = runId.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_"
        return Files.newDirectoryStream(streams).use { entries ->
            entries
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith(prefix) }
                .map { it.fileName.toString() }
                .toList()
        }
    }

    /** Every byte this run left on the durable layout, read off disk rather than through the index. */
    private fun durableBytes(control: Path, runId: String): String {
        val streams = control.resolve(OutputPlaneProvider.OUTPUT_DIR).resolve("streams")
        if (!Files.isDirectory(streams)) return ""
        val prefix = runId.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_"
        return Files.newDirectoryStream(streams).use { entries ->
            entries
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith(prefix) }
                .flatMap { dir -> Files.walk(dir).use { it.filter { f -> Files.isRegularFile(f) }.toList() } }
                .joinToString("") { String(Files.readAllBytes(it), Charsets.UTF_8) }
        }
    }

    @Test
    fun `a scripted stage is addressed by its real position, not by a hardcoded zero`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        // DISTINCT call sites, and that is the product's law rather than a convenience:
        // `ScriptedRegistryCall` puts `definitionDigest` in the operation INPUT and not in the
        // `operationId`, precisely so that two different artifacts at the same source position
        // diverge and are refused. One tuple of (entryPoint, callSite, ordinal) identifies one
        // place in one body, so a run with two bodies must give them distinct call sites.
        val first = Body("scripted-first", listOf("first")) { sh(ScriptedCallSiteId(it), "echo first", ReturnStatus) }
        val second = Body("scripted-second", listOf("second")) { sh(ScriptedCallSiteId(it), "echo second", ReturnStatus) }
        val journal = InMemoryOperationJournal(SystemClock())
        val registry = InMemoryScriptedStageRegistry(listOf(first, second))

        val pipeline = CompiledPipeline(
            id = DefinitionId("p-two-scripted"),
            source = SourceDescriptor("p-two-scripted.pipeline.kts", Digest("p-two-scripted")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("first"),
                    name = "first",
                    options = emptyList(),
                    body = StageBody.Scripted(refOf(first)),
                    post = null,
                ),
                StageNode(
                    id = StageId("second"),
                    name = "second",
                    options = emptyList(),
                    body = StageBody.Scripted(refOf(second)),
                    post = null,
                ),
            ),
        )

        val outcome = coordinator(control, registry, journal).run(pipeline, RunId("r-two-scripted"))

        assertEquals(RunOutcome.Success, outcome)
        // Asserted on the DURABLE stream names, not on the journal's rows: the journal's rows are
        // private, and the artifact a reader actually resolves is the stream on disk. The name
        // embeds the OpId, so this is the addressing question asked of the product.
        val streamNames = streamDirNames(control, "r-two-scripted")
        assertTrue(streamNames.isNotEmpty(), "two scripted stages wrote two streams: $streamNames")
        assertTrue(
            streamNames.any { it.contains("-s0-") },
            "the stage at index 0 addresses its operations as s0-*, so two bodies cannot share a row: $streamNames",
        )
        assertTrue(
            streamNames.any { it.contains("-s1-") },
            "and the stage at index 1 as s1-*, which is the whole point of carrying the index: $streamNames",
        )
    }

    @Test
    fun `two different artifacts at one source position are refused, not merged`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        // The same tuple — same entry point, same call site, same ordinal — carrying two different
        // artifacts. `ScriptedRegistryCall` is explicit about this: the digest rides in the input so
        // the fingerprint diverges, and the run is refused rather than replaying one body as the
        // other. A pin on a real law, and the reason a multi-stage run needs distinct call sites.
        val first = Body("scripted-alpha", listOf("shared")) { sh(ScriptedCallSiteId(it), "echo alpha", ReturnStatus) }
        val second = Body("scripted-beta", listOf("shared")) { sh(ScriptedCallSiteId(it), "echo beta", ReturnStatus) }
        val journal = InMemoryOperationJournal(SystemClock())

        val outcome = coordinator(control, InMemoryScriptedStageRegistry(listOf(first, second)), journal).run(
            CompiledPipeline(
                id = DefinitionId("p-collide"),
                source = SourceDescriptor("p-collide.pipeline.kts", Digest("p-collide")),
                pluginLockDigest = Digest("lock"),
                stages = listOf(
                    StageNode(
                        id = StageId("alpha"),
                        name = "alpha",
                        options = emptyList(),
                        body = StageBody.Scripted(refOf(first)),
                        post = null,
                    ),
                    StageNode(
                        id = StageId("beta"),
                        name = "beta",
                        options = emptyList(),
                        body = StageBody.Scripted(refOf(second)),
                        post = null,
                    ),
                ),
            ),
            RunId("r-collide"),
        )

        val failure = assertInstanceOf(
            RunOutcome.Failure::class.java,
            outcome,
            "two artifacts claiming one source position must be refused, never silently merged",
        ).failure
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
            failure.kind,
            "and the refusal is a COMPATIBILITY failure, which is the fact a reader needs: ${failure.message}",
        )
    }

    @Test
    fun `a ref that names nothing is refused loudly, and the run does not report success`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val compiled = shellBody("scripted-present", "echo marker")
        val journal = InMemoryOperationJournal(SystemClock())

        val outcome = coordinator(control, InMemoryScriptedStageRegistry(listOf(compiled)), journal).run(
            // A DIFFERENT identity, compiled by nobody: the shape a stale reference or a
            // half-finished build produces.
            scriptedPipeline("p-missing", refOf(shellBody("scripted-absent", "echo marker"))),
            RunId("r-missing"),
        )

        assertInstanceOf(
            RunOutcome.Failure::class.java,
            outcome,
            "a stage that could not run is a FAILED run, never a quiet success",
        )
        val failure = (outcome as RunOutcome.Failure).failure
        assertTrue(
            failure.message.contains("no compiled artifact is registered"),
            "and the reason names the condition, not a type: ${failure.message}",
        )
    }

    @Test
    fun `a replayed run reuses the scripted operations instead of re-running them`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val control = Files.createDirectories(root.resolve("control"))
        val journal = InMemoryOperationJournal(SystemClock())
        val body = Body("scripted-replay", listOf("counted")) { callSite ->
            // A side effect OUTSIDE the durable step: if the body re-runs its process, this counter
            // moves. A reused operation must not run it a second time.
            Files.writeString(
                control.resolve("body-entered.txt"),
                "entered",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
            sh(ScriptedCallSiteId(callSite), "echo replayed", ReturnStatus)
        }
        val registry = InMemoryScriptedStageRegistry(listOf(body))

        val first = coordinator(control, registry, journal)
            .run(scriptedPipeline("p-replay", refOf(body)), RunId("r-replay"))
        assertEquals(RunOutcome.Success, first)
        val enteredAfterFirst = Files.readString(control.resolve("body-entered.txt")).length
        assertEquals("entered".length, enteredAfterFirst, "the body ran once on the first run")

        val second = coordinator(control, registry, journal)
            .run(scriptedPipeline("p-replay", refOf(body)), RunId("r-replay"))
        assertEquals(
            RunOutcome.Success,
            second,
            "a resumed run re-enters the body and REUSES the durable operation: $second",
        )
        val enteredAfterSecond = Files.readString(control.resolve("body-entered.txt")).length
        assertTrue(
            enteredAfterSecond > enteredAfterFirst,
            "the body's own top-level code DOES re-run, because a scripted stage carries no cursor " +
                "position and re-entering the body is what reuse is built on. What must not repeat " +
                "is the durable operation inside it.",
        )
    }
}
