package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalArchiveArtifactsDispatchContext
import dev.rubentxu.pipeline.v2.application.durable.CanonicalArchiveArtifactsNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * LFC-2E1 S2-B10 / G2 — Differential Contract Freeze for `core.archiveArtifacts`.
 *
 * Drives the SAME durable input envelope through BOTH authorities and freezes,
 * as executable evidence, the places where they agree and the places where the
 * candidate deliberately diverges.
 *
 * 1. LEGACY authority: `CanonicalArchiveArtifactsNodeDispatcher.dispatch`, fed by a
 *    `CanonicalArchiveArtifactsDispatchContext` wired exactly as production wires it
 *    (`CanonicalNodeDispatcher.archiveArtifactsContext()` → `shOptions.workspaceRoot`,
 *    which is `<controlDirRoot>/workspace` and therefore ABSOLUTE).
 * 2. REGISTRY candidate: `CoreArchiveArtifactsStep.definition.handler` over the typed
 *    `ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY` seam.
 *
 * ## The central frozen finding
 *
 * The legacy glob engine [CanonicalArchiveArtifactsNodeDispatcher] translates an Ant-ish
 * pattern to a regex and matches it against `Path.toString()` of `Files.walk(workspace)`.
 * Production supplies an ABSOLUTE workspace root, so the anchored regex
 * (`^build/libs/[^/]*\.jar$`) can never match an absolute path — the legacy step
 * therefore reports "no files matched" for every realistic pattern. This is not a
 * hypothesis: it is reproduced here (test 1) and independently observable end-to-end via
 * `v2/compatibility/10-smoke-e2e.pipeline.kts`, which exits 1 with
 * `ArtifactArchiveFailed: No files matched glob pattern` for the jar glob.
 *
 * Test 2 isolates the defect: the SAME legacy engine matches correctly when handed a
 * RELATIVE workspace root. The failure is therefore specifically "regex anchored against
 * an absolute path", not "glob engine is broken in general".
 *
 * ## Frozen contract surfaces (parity)
 *
 * - dsl-v1 wire envelope produced by the candidate codec is byte-identical to the legacy
 *   `DslCompiledPipelineCompiler.encodePayload` `StepSpec.ArchiveArtifacts` lowering, and
 *   the legacy decoder accepts the candidate payload verbatim;
 * - empty match + `allowEmptyArchive=false` → one `ArtifactArchiveFailed` + SCRIPT failure
 *   with the same message text on both authorities;
 * - empty match + `allowEmptyArchive=true` → success with exactly one EMPTY
 *   `ArtifactArchived` on both authorities;
 * - `ReplayPolicy.MEMOIZED` on both.
 *
 * ## Frozen deltas (deliberate divergence, recorded for G3/G4)
 *
 * D1 glob engine: legacy hand-rolled regex vs certified `AntStyleGlob`;
 * D2 `excludes`: ignored by legacy, applied by the candidate;
 * D3 effect classification: legacy row `Effect.READ_ONLY` vs candidate `WRITES_WORKSPACE`;
 * D4 retention directory spelling: legacy `<root>/artifacts/...` vs candidate `<root>/artefacts/...`;
 * D5 copy semantics: candidate uses `REPLACE_EXISTING` (idempotent re-run), legacy does not.
 *
 * This test intentionally ASSERTS the legacy behaviour it is freezing. When G4/G5 remove
 * the legacy authority these assertions become obsolete together with the authority they
 * describe (the sibling `CoreCleanWsDifferentialContractTest` was deleted at S2-A10/G5
 * for exactly this reason).
 */
@Timeout(60)
class CoreArchiveArtifactsDifferentialContractTest {

    private val temporaryRoots = mutableListOf<Path>()
    private val relativeRoots = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        temporaryRoots.forEach { runCatching { it.toFile().deleteRecursively() } }
        relativeRoots.forEach { runCatching { it.toFile().deleteRecursively() } }
        temporaryRoots.clear()
        relativeRoots.clear()
    }

    // ===== helpers =====

    private fun newRoot(prefix: String): Path =
        Files.createTempDirectory(prefix).toAbsolutePath().also { temporaryRoots.add(it) }

    /**
     * A workspace root expressed RELATIVE to the test working directory (under the
     * gitignored `build/` tree), used ONLY to isolate the legacy engine's dependence on
     * an absolute path. Production never wires a relative root.
     */
    private fun newRelativeRoot(prefix: String): Path {
        val dir = Path.of("build").resolve(prefix).toAbsolutePath()
        Files.createDirectories(dir)
        relativeRoots.add(dir)
        // The dispatcher matches against walked paths; handing it the build-directory-rooted
        // relative path keeps those strings free of a leading '/' while the directories
        // themselves live inside the module's gitignored build output.
        return Path.of("build").resolve(dir.fileName)
    }

    /**
     * Ant-ish pattern expressed so that the LEGACY regex form matches the strings produced
     * by `Files.walk(relativeRoot)`. The legacy engine matches the walked path string
     * verbatim (`Path.toString()`), so the pattern must carry the same prefix; production
     * never needs this because it supplies an absolute root and therefore never matches.
     */
    private fun relativePattern(relativeRoot: Path, sub: String = "build/libs/*.jar"): String =
        relativeRoot.resolve(sub).toString()

    /** Production wiring: absolute stage workspace (`WorkspaceResolver.resolve`). */
    private fun legacyContext(
        runId: String,
        controlDirRoot: Path,
        sink: InMemoryEventStore,
        workspaceRoot: Path = WorkspaceResolver(controlDirRoot).resolve("test", 0).toAbsolutePath(),
    ) = CanonicalArchiveArtifactsDispatchContext(
        runId = runId,
        stageName = "test",
        stageIndex = 0,
        stepIndex = 0,
        controlDirRoot = controlDirRoot,
        eventSink = sink,
        workspaceRoot = workspaceRoot,
    )

    private fun registryHandlerContext(
        runId: String,
        controlDirRoot: Path,
        sink: InMemoryEventStore,
    ): StepHandlerContext =
        StepHandlerContext(
            runId = RunId(runId),
            stepIndex = 0,
            capabilities = object : StepCapabilityAccess {
                override fun available(): Set<StepCapability> = setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: StepCapability): T = when (key) {
                    ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY -> ArchiveArtifactsOperationsAdapter(
                        runIdString = runId,
                        stageIdentity = StageIdentity(name = "test", index = 0),
                        controlDirRoot = controlDirRoot,
                        eventSink = sink,
                    ) as T

                    else -> throw IllegalArgumentException("unexpected capability $key")
                }
            },
        )

    private fun registryRun(
        input: ArchiveArtifactsInput,
        controlDirRoot: Path,
        sink: InMemoryEventStore,
        runId: String = "run-1",
    ): dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput =
        runBlocking {
            CoreArchiveArtifactsStep.definition.handler.execute(
                input,
                registryHandlerContext(runId, controlDirRoot, sink),
            )
        }

    private fun seedAbsoluteWorkspace(
        controlDirRoot: Path,
        files: Map<String, String> = mapOf("build/libs/smoke.jar" to "jar\n", "README.md" to "readme\n"),
    ): Path {
        val workspace = WorkspaceResolver(controlDirRoot).ensureCreated(
            WorkspaceResolver(controlDirRoot).resolve("test", 0),
        )
        files.forEach { (relative, content) ->
            val target = workspace.resolve(relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        return workspace
    }

    private fun seedRelativeWorkspace(root: Path, files: Map<String, String>): Path {
        files.forEach { (relative, content) ->
            val target = root.resolve(relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        return root
    }

    private fun events(sink: InMemoryEventStore, runId: String): List<DomainEvent> =
        sink.eventsFor(runId).toList()

    private fun archived(sink: InMemoryEventStore, runId: String): List<ArtifactArchived> =
        events(sink, runId).filterIsInstance<ArtifactArchived>()

    private fun archiveFailed(sink: InMemoryEventStore, runId: String): List<ArtifactArchiveFailed> =
        events(sink, runId).filterIsInstance<ArtifactArchiveFailed>()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun legacyCommand(
        artifacts: String,
        allowEmptyArchive: Boolean = false,
        excludes: String = "",
        fingerprint: Boolean = false,
    ) = CanonicalCoreStepCommand.ArchiveArtifacts(
        artifacts = artifacts,
        allowEmptyArchive = allowEmptyArchive,
        excludes = excludes,
        fingerprint = fingerprint,
    )

    // ===== 1. the frozen legacy defect on the production (absolute) wiring =====

    @Test
    fun `freeze — legacy dispatcher matches nothing for a production absolute workspace root`() {
        val root = newRoot("archiveartifacts-legacy-abs-")
        val sink = InMemoryEventStore()
        seedAbsoluteWorkspace(root)

        val outcome = CanonicalArchiveArtifactsNodeDispatcher().dispatch(
            legacyCommand(artifacts = "build/libs/*.jar"),
            legacyContext("run-1", root, sink),
        )

        val failure = assertInstanceOf(StepOutcome.Failure::class.java, outcome)
        assertEquals(FailureKind.SCRIPT, failure.failure.kind)
        assertEquals("archiveArtifacts: no files matched 'build/libs/*.jar'", failure.failure.message)

        // Observable channel: exactly one failure event, and NO success event. The step's
        // workspace effect is therefore "did nothing"; the failure is the only durable trace.
        assertEquals(1, archiveFailed(sink, "run-1").size)
        assertEquals(0, archived(sink, "run-1").size)
        assertTrue(
            archiveFailed(sink, "run-1").single().reason.contains("No files matched glob pattern"),
        )
    }

    // ===== 2. defect isolation: the same engine works on a relative root =====

    @Test
    fun `characterization — legacy engine DOES match when the workspace root is relative`() {
        val relativeRoot = newRelativeRoot("char-legacy-rel")
        val sink = InMemoryEventStore()
        seedRelativeWorkspace(relativeRoot, mapOf("build/libs/smoke.jar" to "jar\n"))

        val outcome = CanonicalArchiveArtifactsNodeDispatcher().dispatch(
            legacyCommand(artifacts = relativePattern(relativeRoot)),
            CanonicalArchiveArtifactsDispatchContext(
                runId = "run-1",
                stageName = "test",
                stageIndex = 0,
                stepIndex = 0,
                controlDirRoot = relativeRoot,
                eventSink = sink,
                workspaceRoot = relativeRoot,
            ),
        )

        assertEquals(StepOutcome.Success, outcome)
        val events = archived(sink, "run-1")
        assertEquals(1, events.size)
        assertEquals(listOf("build/libs/smoke.jar"), events.single().files.map { it.relPath })
    }

    // ===== 3. the registry candidate archives the production pattern =====

    @Test
    fun `differential — registry candidate archives build libs jar that the legacy authority cannot see`() {
        val root = newRoot("archiveartifacts-registry-")
        val sink = InMemoryEventStore()
        val workspace = seedAbsoluteWorkspace(root)

        val output = registryRun(ArchiveArtifactsInput(artifacts = "build/libs/*.jar"), root, sink)

        val success = assertInstanceOf(
            CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java,
            output,
        )
        assertEquals(1, success.archivedCount)
        assertEquals(0, archiveFailed(sink, "run-1").size)

        val event = archived(sink, "run-1").single()
        val entry = event.files.single()
        assertEquals("build/libs/smoke.jar", entry.relPath)
        assertEquals(sha256("jar\n"), entry.sha256)
        assertEquals(Files.size(workspace.resolve("build/libs/smoke.jar")), entry.size)
        assertEquals("test", entry.stageName)
        assertEquals("run-1", entry.runId)
    }

    // ===== 4. parity: empty match, allowEmptyArchive=false =====

    @Test
    fun `parity — empty match with allowEmptyArchive false fails SCRIPT with identical message on both authorities`() {
        val root = newRoot("archiveartifacts-empty-fail-")
        seedAbsoluteWorkspace(root)

        val legacySink = InMemoryEventStore()
        val legacyOutcome = CanonicalArchiveArtifactsNodeDispatcher().dispatch(
            legacyCommand(artifacts = "nope/*.jar", allowEmptyArchive = false),
            legacyContext("run-legacy", root, legacySink),
        )
        val legacyFailure = assertInstanceOf(StepOutcome.Failure::class.java, legacyOutcome)

        val registrySink = InMemoryEventStore()
        val registryOutcome = registryRun(
            ArchiveArtifactsInput(artifacts = "nope/*.jar", allowEmptyArchive = false),
            root,
            registrySink,
            runId = "run-registry",
        )
        val registryFailure = assertInstanceOf(
            CoreArchiveArtifactsStep.ArchiveArtifactsFailureOutput::class.java,
            registryOutcome,
        )

        // The typed-failure test law: the failure kind is the STEP's contractual kind
        // (SCRIPT), never INFRASTRUCTURE, and the message is the step's own message.
        assertEquals(FailureKind.SCRIPT, legacyFailure.failure.kind)
        assertEquals(FailureKind.SCRIPT, registryFailure.failureKind)
        assertEquals("archiveArtifacts: no files matched 'nope/*.jar'", legacyFailure.failure.message)
        assertEquals("archiveArtifacts: no files matched 'nope/*.jar'", registryFailure.message)

        assertEquals(1, archiveFailed(legacySink, "run-legacy").size)
        assertEquals(1, archiveFailed(registrySink, "run-registry").size)
        assertEquals(0, archived(legacySink, "run-legacy").size)
        assertEquals(0, archived(registrySink, "run-registry").size)
    }

    // ===== 5. parity: empty match, allowEmptyArchive=true =====

    @Test
    fun `parity — allowEmptyArchive true succeeds with an empty ArtifactArchived on both authorities`() {
        val root = newRoot("archiveartifacts-empty-ok-")
        seedAbsoluteWorkspace(root)

        val legacySink = InMemoryEventStore()
        val legacyOutcome = CanonicalArchiveArtifactsNodeDispatcher().dispatch(
            legacyCommand(artifacts = "nope/*.jar", allowEmptyArchive = true),
            legacyContext("run-legacy", root, legacySink),
        )
        assertEquals(StepOutcome.Success, legacyOutcome)

        val registrySink = InMemoryEventStore()
        val registryOutcome = registryRun(
            ArchiveArtifactsInput(artifacts = "nope/*.jar", allowEmptyArchive = true),
            root,
            registrySink,
            runId = "run-registry",
        )
        val success = assertInstanceOf(
            CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java,
            registryOutcome,
        )
        assertEquals(0, success.archivedCount)

        // Both authorities still emit the event, with an EMPTY files list (byte-parity).
        assertTrue(archived(legacySink, "run-legacy").single().files.isEmpty())
        assertTrue(archived(registrySink, "run-registry").single().files.isEmpty())
        assertEquals(0, archiveFailed(legacySink, "run-legacy").size)
        assertEquals(0, archiveFailed(registrySink, "run-registry").size)
    }

    // ===== 6. envelope freeze: candidate codec == legacy compiler lowering =====

    @Test
    fun `freeze — candidate input codec emits the dsl-v1 envelope byte-for-byte and the legacy decoder accepts it`() {
        val encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
            ArchiveArtifactsInput(
                artifacts = "build/libs/*.jar",
                allowEmptyArchive = false,
                excludes = "",
                fingerprint = false,
            ),
        )

        // The exact wire shape produced by DslCompiledPipelineCompiler.encodePayload for
        // `StepSpec.ArchiveArtifacts` (insertion order preserved by JsonObject).
        assertEquals(
            """{"kind":"archiveArtifacts","artifacts":"build/libs/*.jar","allowEmptyArchive":false,"excludes":"","fingerprint":false}""",
            encoded.value,
        )

        // Cross-acceptance: the legacy decoder consumes the candidate's payload verbatim.
        val node = OpaqueStepNode(
            id = StepId("archive-0"),
            pluginStepId = PluginStepId("core.archiveArtifacts"),
            payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = encoded.value),
        )
        val decoded = assertInstanceOf(
            CanonicalCoreStepCommand.ArchiveArtifacts::class.java,
            CanonicalCoreStepDecoder.decode(node),
        )
        assertEquals("build/libs/*.jar", decoded.artifacts)
        assertEquals(false, decoded.allowEmptyArchive)
        assertEquals("", decoded.excludes)
        assertEquals(false, decoded.fingerprint)

        // And the candidate codec round-trips its own envelope.
        assertEquals(
            ArchiveArtifactsInput("build/libs/*.jar", false, "", false),
            CoreArchiveArtifactsStep.definition.contract.inputCodec.decode(encoded),
        )
    }

    // ===== 7. frozen delta D2: excludes =====

    @Test
    fun `delta D2 — excludes is ignored by the legacy engine and applied by the registry candidate`() {
        val root = newRoot("archiveartifacts-excludes-")
        val files = mapOf("build/libs/smoke.jar" to "jar\n", "build/libs/skip.jar" to "skip\n")
        val relativeRoot = newRelativeRoot("char-excludes")
        seedRelativeWorkspace(relativeRoot, files)
        seedAbsoluteWorkspace(root, files)

        // LEGACY: excludes present on the envelope, silently ignored.
        val legacySink = InMemoryEventStore()
        CanonicalArchiveArtifactsNodeDispatcher().dispatch(
            legacyCommand(
                artifacts = relativePattern(relativeRoot),
                excludes = "build/libs/skip.jar",
            ),
            CanonicalArchiveArtifactsDispatchContext(
                runId = "run-legacy",
                stageName = "test",
                stageIndex = 0,
                stepIndex = 0,
                controlDirRoot = relativeRoot,
                eventSink = legacySink,
                workspaceRoot = relativeRoot,
            ),
        )
        assertEquals(
            setOf("build/libs/skip.jar", "build/libs/smoke.jar"),
            archived(legacySink, "run-legacy").single().files.map { it.relPath }.toSet(),
        )

        // REGISTRY: excludes applied after the Jenkins default-exclude set.
        val registrySink = InMemoryEventStore()
        val output = registryRun(
            ArchiveArtifactsInput(artifacts = "build/libs/*.jar", excludes = "build/libs/skip.jar"),
            root,
            registrySink,
            runId = "run-registry",
        )
        val success = assertInstanceOf(
            CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java,
            output,
        )
        assertEquals(1, success.archivedCount)
        assertEquals(
            listOf("build/libs/smoke.jar"),
            archived(registrySink, "run-registry").single().files.map { it.relPath },
        )
    }

    // ===== 8. frozen delta D3: effect classification + replay policy =====

    @Test
    fun `delta D3 — legacy row declares READ_ONLY while the candidate descriptor declares WRITES_WORKSPACE`() {
        val legacy = CanonicalCoreStepMetadata.metadata("core.archiveArtifacts")
        val candidate = CoreArchiveArtifactsStep.definition.contract.descriptor

        assertEquals(setOf(Effect.READ_ONLY), legacy.effects)
        assertEquals(listOf(Effect.WRITES_WORKSPACE), candidate.effects)

        // Replay policy is NOT part of the delta: both are MEMOIZED (reuse is the replay
        // authority; output presence never governs reuse).
        assertEquals(ReplayPolicy.MEMOIZED, legacy.replayPolicy)
        assertEquals(ReplayPolicy.MEMOIZED, candidate.replayPolicy)

        // Capability contract: the candidate declares exactly the one capability it uses.
        assertEquals(
            setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY),
            CoreArchiveArtifactsStep.definition.contract.requiredCapabilities,
        )
        assertEquals(PluginStepId("core.archiveArtifacts"), CoreArchiveArtifactsStep.KEY)
    }

    // ===== 9. frozen delta D4: retention directory spelling =====

    @Test
    fun `delta D4 — legacy retention directory is artifacts while the candidate resolver writes artefacts`() {
        val root = newRoot("archiveartifacts-retention-")
        val relativeRoot = newRelativeRoot("char-retention")
        val files = mapOf("build/libs/smoke.jar" to "jar\n")
        seedRelativeWorkspace(relativeRoot, files)
        seedAbsoluteWorkspace(root, files)

        CanonicalArchiveArtifactsNodeDispatcher().dispatch(
            legacyCommand(artifacts = relativePattern(relativeRoot)),
            CanonicalArchiveArtifactsDispatchContext(
                runId = "run-legacy",
                stageName = "test",
                stageIndex = 0,
                stepIndex = 0,
                controlDirRoot = relativeRoot,
                eventSink = InMemoryEventStore(),
                workspaceRoot = relativeRoot,
            ),
        )
        assertTrue(Files.exists(relativeRoot.resolve("artifacts/run-legacy/test/build/libs/smoke.jar"))) {
            "legacy retention path <root>/artifacts/<runId>/<stage>/... expected"
        }

        registryRun(
            ArchiveArtifactsInput(artifacts = "build/libs/*.jar"),
            root,
            InMemoryEventStore(),
            runId = "run-registry",
        )
        assertTrue(Files.exists(root.resolve("artefacts/run-registry/test/build/libs/smoke.jar"))) {
            "candidate retention path <root>/artefacts/<runId>/<stage>/... expected"
        }

        // The two spellings are DIFFERENT directories; neither path is shared.
        assertFalse(Files.exists(root.resolve("artifacts")))
    }

    // ===== 10. frozen delta D5: idempotent re-execution (registry) =====

    @Test
    fun `delta D5 — registry re-execution is idempotent and emits exactly one event per execution`() {
        val root = newRoot("archiveartifacts-idempotent-")
        val sink = InMemoryEventStore()
        seedAbsoluteWorkspace(root)

        val first = registryRun(ArchiveArtifactsInput(artifacts = "build/libs/*.jar"), root, sink)
        val second = registryRun(ArchiveArtifactsInput(artifacts = "build/libs/*.jar"), root, sink)

        assertEquals(
            1,
            assertInstanceOf(CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java, first).archivedCount,
        )
        assertEquals(
            1,
            assertInstanceOf(CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java, second).archivedCount,
        )

        val events = archived(sink, "run-1")
        assertEquals(2, events.size, "one ArtifactArchived per execution, never duplicated within one")
        assertEquals(
            events[0].files.map { it.relPath to it.sha256 },
            events[1].files.map { it.relPath to it.sha256 },
            "REPLACE_EXISTING makes the second archive byte-identical to the first",
        )
        assertEquals(0, archiveFailed(sink, "run-1").size)

        // The archived bytes equal the workspace source bytes on both runs.
        assertEquals(
            "jar\n",
            Files.readString(root.resolve("artefacts/run-1/test/build/libs/smoke.jar")),
        )
    }

    // ===== 11. counters are untouched by G2 =====

    @Test
    fun `counters — G2 leaves the legacy residual at 3 3 3 (no flip in this gate)`() {
        assertTrue("core.archiveArtifacts" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertNotNull(CanonicalCoreStepMetadata.metadata("core.archiveArtifacts"))
        assertEquals(
            3,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "S2-A10 closed cleanWs; residual is {load, waitUntil, archiveArtifacts}",
        )
    }
}
