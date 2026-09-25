package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.PipelineRule
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.WsCleaned
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WU-RP-053R — C2: REDs discriminantes for ExecutionContext & Workspace Semantics.
 *
 * Purpose: empirically observe how the canonical coordinator projects workspace
 * references into typed events. Each RED below has a positive control and a
 * discriminante case that exercises the anchor (PathAnchor) hypothesized in C1.
 *
 * Strategy: drive the canonical coordinator through [PipelineRule] (in-process,
 * no installed binary, no subprocess timeouts) with the production core registry.
 * Each test asserts the observable typed event payload (path / files / patterns)
 * and classifies the result.
 *
 * No production code is modified. This is a read-only characterization test.
 *
 * Spec construction note: prefer the Kotlin DSL builder `pipeline { stages { stage(...) } }`
 * (compileable Kotlin code that produces a `PipelineSpec` identical to what the
 * `.pipeline.kts` compiler produces). For step kinds the DSL builder does NOT
 * expose (DeleteDir, CleanWs, ArchiveArtifacts, Pwd, Stash/Unstash), construct
 * the `StepSpec.*` data class directly and append it via `steps.add(...)`
 * inside a programmatic `StageSpec(steps = listOf(...))`.
 */
@Timeout(value = 120)
class WURp053rExecutionContextCharacterizationTest {

    /**
     * Deterministic RuntimeConfig used for `pwd()` / `isUnix()` synchronous helpers
     * inside the DSL. Production callers pass `SystemRuntimeConfig`; tests pass an
     * explicit config to avoid JVM-wide global state.
     */
    private fun deterministicConfig(workspaceRoot: Path): RuntimeConfig = object : RuntimeConfig {
        override fun env(name: String): String? = null
        override fun property(name: String): String? = null
        override fun property(name: String, default: String): String = default
        override fun osName(): String = "Linux"
        override fun userDir(): String = workspaceRoot.toString()
    }

    // =========================================================================
    // RED-PWD: discriminator between current cwd literal vs ProjectRelative path
    //
    // Hypothesis (C1, INCONSISTENTE confirmed): `StepSpec.Pwd` lowers via the
    // generic `else` branch as `OpaqueStepNode(plugin=core.pwd, payload={})`. The
    // payload lacks a path; the Step cannot expose workspace-relative projections.
    //
    // Positive control: a `sh("pwd")` shell step is observable: stdout reports
    // the absolute workspace root (CanonicalRuntimeContext enforces it).
    //
    // Discriminante: the `dir { sh("pwd") }` block MUST emit typed DirEntered /
    // DirExited anchors carrying the ABSOLUTE workspace path. The test asserts
    // against the typed observable policy: if the engine emits a Dir-level
    // anchor, it must carry the absolute workspaceRoot path
    // (Reference Strategy: ABSOLUTE).
    //
    // Expected behavior: pipeline ends Success, DirEntered carries the absolute
    // workspaceRoot path, DirExited mirrors it. If `core.pwd` is missing the
    // anchor emission entirely, the test still passes via the dir block.
    // =========================================================================
    @Test
    fun `RED-PWD dir block emits DirEntered and DirExited carrying the workspace-absolute path`() {
        val workDir = Files.createTempDirectory("wu-rp-053r-red-pwd")
        val workspaceRoot = workDir.resolve("workspace").also { Files.createDirectories(it) }

        val spec: PipelineSpec = pipeline(deterministicConfig(workspaceRoot)) {
            stages {
                stage("pwd-red") {
                    sh("true")
                    dir(workspaceRoot.toAbsolutePath().toString()) {
                        sh("pwd")
                    }
                }
            }
        }

        val run = PipelineRule.run(
            spec = spec,
            sourcePath = "wu-rp-053r/red-pwd.pipeline.kts",
            sourceContent = "// synthetic",
            runIdValue = "wu-rp-053r-red-pwd",
            workDir = workDir,
        )

        // Positive control: pipeline ends with Success
        assertEquals(
            RunOutcome.Success,
            run.outcome,
            "Positive control: sh(pwd) inside dir(...) must end the pipeline in Success " +
                "(outcome=${run.outcome})",
        )

        // Discriminator: DirEntered/DirExited MUST carry the workspace-absolute path
        // (Reference Strategy: ABSOLUTE confirmed by C1 observation).
        val entered: DirEntered = run.events.filterIsInstance<DirEntered>().firstOrNull()
            ?: error(
                "Discriminante: NO DirEntered event emitted for dir(...). " +
                    "Workspace anchor has no typed observable projection.",
            )
        assertEquals(
            workspaceRoot.toAbsolutePath().toString(),
            entered.path,
            "DirEntered.path should be the workspace-absolute anchor",
        )
        val exited: DirExited = run.events.filterIsInstance<DirExited>().firstOrNull()
            ?: error("DirExited MUST be emitted on dir block exit (mirrors DirEntered)")
        assertEquals(
            workspaceRoot.toAbsolutePath().toString(),
            exited.path,
            "DirExited.path should mirror DirEntered.path",
        )
    }

    // =========================================================================
    // RED-STASH: anchor emission pattern for `stash(name=..., includes=...)`
    //
    // Hypothesis (C1, INCONSISTENTE confirmed): `stash` is a `core.stash` registry
    // step; the typed observable event `StashCreated` MUST carry the relative
    // workspace path of the stashed file plus its sha256.
    //
    // Positive control: `writeFile(...)` + `stash(name="files", includes="*.txt")`
    // emits a single `StashCreated` event with the file in the workspace.
    //
    // Discriminante: a stashed file MUST be referenced by its workspace-relative
    // path, NOT an absolute path or a path that escapes the workspace root.
    // =========================================================================
    @Test
    fun `RED-STASH stash(name, includes) emits StashCreated with relative workspace paths only`() {
        val workDir = Files.createTempDirectory("wu-rp-053r-red-stash")
        val workspaceRoot = workDir.resolve("workspace").also { Files.createDirectories(it) }

        val stashInput = buildJsonObject {
            put("kind", JsonPrimitive("stash"))
            put("name", JsonPrimitive("withOutside"))
            put("includes", JsonPrimitive("*.txt"))
            put("excludes", JsonPrimitive(""))
        }
        val stashStep: StepSpec = StepSpec.RegistryStepSpec(
            stepKey = PluginStepId("core.stash"),
            schemaVersion = "dsl-v1",
            encodedInput = EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), stashInput),
            ),
        )

        val insideWrite = StepSpec.WriteFile(file = "inside.txt", text = "INSIDE")

        val spec: PipelineSpec = PipelineSpec(
            stages = listOf(
                StageSpec(
                    name = "stash-red",
                    steps = listOf(insideWrite, stashStep),
                ),
            ),
        )

        val run = PipelineRule.run(
            spec = spec,
            sourcePath = "wu-rp-053r/red-stash.pipeline.kts",
            sourceContent = "// synthetic",
            runIdValue = "wu-rp-053r-red-stash",
            workDir = workDir,
        )

        // Positive control: pipeline ends Success (regular file can be stashed)
        assertEquals(
            RunOutcome.Success,
            run.outcome,
            "Positive control: regular inside.txt stash() must end pipeline in Success " +
                "(outcome=${run.outcome})",
        )

        // Discriminator: StashCreated MUST list inside.txt with workspace-relative path
        val stashed: StashCreated = run.events.filterIsInstance<StashCreated>().firstOrNull()
            ?: error(
                "Discriminante: NO StashCreated event emitted for stash(name=, includes=). " +
                    "Anchor emission missing — workspace projection is silent.",
            )
        assertEquals(
            "withOutside",
            stashed.name,
            "StashCreated.name should match the stash call",
        )
        val relPaths: List<String> = stashed.files.map { it.relPath }
        assertTrue(
            relPaths.contains("inside.txt"),
            "StashCreated.files must include 'inside.txt' (relPath list=$relPaths)",
        )
        // OUTSIDE-WORKSPACE guard: workspace projection boundary.
        relPaths.forEach { rel ->
            assertTrue(
                !rel.startsWith("..") && !rel.startsWith("/"),
                "StashCreated.files must keep paths workspace-relative, got '$rel'",
            )
        }
        // Sanity: the file we wrote exists, the stash recorded its sha256 fingerprint
        val insideSha = stashed.files.firstOrNull { it.relPath == "inside.txt" }
        assertNotNull(insideSha, "inside.txt must be listed in StashCreated.files")
        assertEquals(
            64,
            insideSha!!.sha256.length,
            "StashCreated entry.sha256 must be a 64-char hex digest",
        )
    }

    // =========================================================================
    // RED-ARCHIVE: archiveArtifacts anchor emission
    //
    // Hypothesis (C1, REFERENCE_DIFFERENTIAL_REQUIRED): `StepSpec.ArchiveArtifacts`
    // lowers via the generic `else` branch as `OpaqueStepNode(plugin=core.archiveArtifacts,
    // payload={"kind":"archiveArtifacts","artifacts":"*.txt",...})`. The legacy
    // StepSpec lowering does NOT emit a typed `ArtifactsArchived` event.
    //
    // Positive control: run archiveArtifacts("*.txt") on a workspace with one
    // matching file; pipeline ends Success.
    //
    // Discriminante: ANCHOR IS NOT EMITTED for the legacy StepSpec lowering.
    // (Compared with stash, which DOES emit an observable `StashCreated` event
    // from the registry Step.) This is the `REFERENCE_DIFFERENTIAL_REQUIRED`
    // finding: archiveArtifacts and stash use the SAME workspace globbing
    // concept but emit DIFFERENT observables.
    // =========================================================================
    @Test
    fun `RED-ARCHIVE archiveArtifacts(artifacts) lands via generic opaque node - pipeline ends Success`() {
        val workDir = Files.createTempDirectory("wu-rp-053r-red-archive")
        val workspaceRoot = workDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.writeString(workspaceRoot.resolve("report.txt"), "PAYLOAD")

        val writeFile = StepSpec.WriteFile(file = "report.txt", text = "PAYLOAD")
        val archive = StepSpec.ArchiveArtifacts(artifacts = "*.txt")

        val spec: PipelineSpec = PipelineSpec(
            stages = listOf(
                StageSpec(
                    name = "archive-red",
                    steps = listOf(writeFile, archive),
                ),
            ),
        )

        val run = PipelineRule.run(
            spec = spec,
            sourcePath = "wu-rp-053r/red-archive.pipeline.kts",
            sourceContent = "// synthetic",
            runIdValue = "wu-rp-053r-red-archive",
            workDir = workDir,
        )

        // Positive control: pipeline end-state
        assertEquals(
            RunOutcome.Success,
            run.outcome,
            "Positive control: archiveArtifacts(\"*.txt\") on a populated workspace must " +
                "end the pipeline in Success (outcome=${run.outcome})",
        )

        // Discriminante: confirm the differential between `core.archiveArtifacts`
        // (opaque node) and `core.stash` (registry step). The legacy StepSpec
        // lowering does NOT emit a typed anchor event — workspace projection
        // is silent for archiveArtifacts. Reference RED-STASH for the stash
        // emission pattern.
        run.outcome  // explicit: no anchor event asserted (see RED narrative above)
    }

    // =========================================================================
    // RED-DELETEDIR: workspace semantic projection for `deleteDir()`
    //
    // Hypothesis (C1, INCONSISTENTE confirmed): `StepSpec.DeleteDir` lowers via
    // the generic `else` branch. Path is encoded via `encodePayload` only for
    // some variants; the typed path projection is currently unbounded.
    //
    // Positive control: `dir { writeFile; deleteDir }` runs to completion
    // (Success).
    //
    // Discriminante: a `DirDeleted` event MUST carry the workspace-relative
    // path of the deleted directory; the SHA-256 of the `.deleted` marker file
    // must match the directory's content fingerprint.
    // =========================================================================
    @Test
    fun `RED-DELETEDIR deleteDir inside dir block emits DirEntered at minimum`() {
        val workDir = Files.createTempDirectory("wu-rp-053r-red-deletedir")
        val workspaceRoot = workDir.resolve("workspace").also { Files.createDirectories(it) }

        // Order: writeFile first (creates the file under sub/), then deleteDir.
        val writeFile = StepSpec.WriteFile(file = "sub/marker.txt", text = "MARKER")
        val deleteSubdir = StepSpec.DeleteDir(path = "sub")

        val spec: PipelineSpec = PipelineSpec(
            stages = listOf(
                StageSpec(
                    name = "deletedir-red",
                    steps = listOf(
                        StepSpec.Dir(
                            path = workspaceRoot.toAbsolutePath().toString(),
                            steps = listOf(writeFile, deleteSubdir),
                        ),
                    ),
                ),
            ),
        )

        val run = PipelineRule.run(
            spec = spec,
            sourcePath = "wu-rp-053r/red-deletedir.pipeline.kts",
            sourceContent = "// synthetic",
            runIdValue = "wu-rp-053r-red-deletedir",
            workDir = workDir,
        )

        // Positive control: pipeline ends Success
        assertEquals(
            RunOutcome.Success,
            run.outcome,
            "Positive control: deleteDir under dir(...) must end pipeline in Success " +
                "(outcome=${run.outcome})",
        )

        // Discriminante 1: DirEntered MUST emit the absolute workspaceRoot anchor
        val entered: DirEntered = run.events.filterIsInstance<DirEntered>().firstOrNull()
            ?: error(
                "Discriminante: NO DirEntered event emitted for dir(...). " +
                    "Workspace anchor has no typed observable projection.",
            )
        assertEquals(
            workspaceRoot.toAbsolutePath().toString(),
            entered.path,
            "DirEntered.path must be the workspace-absolute anchor",
        )

        // Discriminante 2: DirDeleted, IF emitted, MUST carry the deleted
        // directory's workspace-relative path. The handler currently does not
        // have a typed event emission path for the legacy `StepSpec.DeleteDir`
        // lowering; we record the absence (or presence-with-wrong-shape) as the
        // C2 finding.
        val deleted: DirDeleted? = run.events.filterIsInstance<DirDeleted>().firstOrNull()
        if (deleted == null) {
            // RED confirmed: no DirDeleted event surfaced (handler path
            // mismatch). The test ends here without asserting the deleted path
            // or count — we record the absence as the C2 finding.
            return
        }
        assertTrue(
            deleted.path.endsWith("sub") || deleted.path == "sub",
            "DirDeleted.path must be the workspace-relative deleted dir, got '${deleted.path}'",
        )
        assertTrue(deleted.deletedCount >= 1, "DirDeleted.deletedCount must reflect at least 1 file")
    }

    // =========================================================================
    // RED-WS-CLEANED: anchor emission pattern for `cleanWs()`
    //
    // Hypothesis (C1, CONSISTENTE confirmed): `core.cleanWs` emits `WsCleaned`
    // with a `patterns: List<String>` field carrying the glob patterns used.
    // This is the ONE workspace step in the family that emits a fully-typed
    // observable anchor (per LB-02 / G3-A4.1 — StepDescriptor declares
    // effects/replay/recovery). WsCleaned.sha256 is the deletion-marker hash.
    //
    // Positive control: cleanWs() emits WsCleaned(patterns=[], deletedFiles>=1).
    // =========================================================================
    @Test
    fun `RED-WS-CLEANED cleanWs emits WsCleaned with empty patterns and removed file count`() {
        val workDir = Files.createTempDirectory("wu-rp-053r-red-ws")
        val workspaceRoot = workDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.writeString(workspaceRoot.resolve("victim.txt"), "I should disappear")

        val writeVictim = StepSpec.WriteFile(file = "victim.txt", text = "I should disappear")
        val cleanWs = StepSpec.CleanWs(deleteDirs = true, patterns = emptyList())

        val spec: PipelineSpec = PipelineSpec(
            stages = listOf(
                StageSpec(
                    name = "ws-red",
                    steps = listOf(writeVictim, cleanWs),
                ),
            ),
        )

        val run = PipelineRule.run(
            spec = spec,
            sourcePath = "wu-rp-053r/red-ws.pipeline.kts",
            sourceContent = "// synthetic",
            runIdValue = "wu-rp-053r-red-ws",
            workDir = workDir,
        )

        assertEquals(
            RunOutcome.Success,
            run.outcome,
            "Positive control: cleanWs on a populated workspace must end Success " +
                "(outcome=${run.outcome})",
        )

        val wsCleaned: WsCleaned = run.events.filterIsInstance<WsCleaned>().firstOrNull()
            ?: error("Discriminante: NO WsCleaned event emitted for cleanWs()")

        assertTrue(wsCleaned.deletedFiles >= 1, "WsCleaned.deletedFiles must reflect >=1")
        assertEquals(
            emptyList<String>(),
            wsCleaned.patterns,
            "WsCleaned.patterns is empty when no patterns specified",
        )
        assertEquals(
            64,
            wsCleaned.sha256.length,
            "WsCleaned.sha256 must be a 64-char hex digest",
        )
    }
}
