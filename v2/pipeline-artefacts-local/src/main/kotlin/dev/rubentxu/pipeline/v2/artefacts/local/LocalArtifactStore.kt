package dev.rubentxu.pipeline.v2.artefacts.local

import dev.rubentxu.pipeline.v2.events.ArtifactEntry
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Workspace artifact store using per-stage tar archives.
 *
 * Layout: `<controlRoot>/artefacts/<runId>/<stageName>/<ts>-<uuid>.tar`
 *
 * Design: D6 — artefacts live OUTSIDE the workspace (F-ARCH-L7 workspace-cleanup
 * preservation invariant). When `WorkspaceResolver.cleanupAfterComplete()` wipes the
 * stage workspace, the artefacts directory survives because it is a sibling of the
 * workspace directory under `<controlRoot>`, not a descendant.
 *
 * ## API
 *
 * - [stageDir] — returns the per-run per-stage artefact directory
 * - [archive] — creates a tar archive from files matching the given Ant-style pattern
 * - [close] — idempotent cleanup
 *
 * ## Workspace-Cleanup Preservation (F-ARCH-L7 / INV-L7-FS-007)
 *
 * The artefacts directory is `<controlRoot>/artefacts/` and the workspace is
 * `<controlRoot>/workspace/<stageName>-<stageIndex>/`. They are siblings under
 * `<controlRoot>`, not nested. `WorkspaceResolver.cleanupAfterComplete()` only
 * deletes `<controlRoot>/workspace/...`. The artefacts directory is untouched.
 */
class LocalArtifactStore(
    private val controlRoot: Path,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Returns the artefact directory for a given run and stage.
     *
     * @param runId Run identifier
     * @param stageName Human-readable stage name
     * @return Path to `<controlRoot>/artefacts/<runId>/<stageName>/`
     */
    fun stageDir(runId: RunId, stageName: StageName): Path {
        val artefactsRoot = controlRoot.resolve("artefacts")
        return artefactsRoot
            .resolve(runId.value)
            .resolve(stageName.value)
    }

    /**
     * Creates a tar archive of files matching [pattern] in the stage workspace.
     *
     * Uses [AntStyleGlob] for pattern matching with default excludes enabled.
     * The archive is named `<ts>-<uuid>.tar` and written under [stageDir].
     *
     * @param runId Run identifier
     * @param stageName Human-readable stage name
     * @param workspace Workspace root (stage directory resolved via WorkspaceResolver)
     * @param pattern Ant-style glob pattern (e.g. "build/**/*.jar")
     * @param allowEmptyArchive If true, empty match is not an error; if false, throws [EmptyArchiveException]
     * @param excludes Optional additional exclude patterns
     * @return ArchiveResult with sha256 and file entries
     * @throws EmptyArchiveException when no files match and allowEmptyArchive is false
     * @throws java.io.IOException on I/O errors
     */
    fun archive(
        runId: RunId,
        stageName: StageName,
        workspace: Path,
        pattern: String,
        allowEmptyArchive: Boolean = false,
        excludes: List<String> = emptyList(),
    ): ArchiveResult {
        val dir = stageDir(runId, stageName)
        assertPosixSupported(controlRoot)
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")))

        // Ensure workspace exists before walking — Jenkins semantics: archive matches
        // files in the current workspace. If workspace was never created (no steps
        // wrote files), Files.walk() would throw NoSuchFileException.
        Files.createDirectories(workspace)

        val glob = AntStyleGlob(pattern)
        val matchedFiles = glob.match(workspace, excludes = excludes, defaultExcludes = true)

        if (matchedFiles.isEmpty() && !allowEmptyArchive) {
            throw EmptyArchiveException(
                "no files matched pattern: $pattern (allowEmptyArchive=false)"
            )
        }

        val timestamp = clock.instant().toEpochMilli()
        val uuid = UUID.randomUUID().toString().take(8)
        val archiveName = "${timestamp}-${uuid}.tar"
        val archivePath = dir.resolve(archiveName)

        val digest = MessageDigest.getInstance("SHA-256")
        val entries = mutableListOf<ArtifactEntry>()

        Files.newOutputStream(archivePath).use { fos ->
            TarWriter(fos, digest).use { tar ->
                for (file in matchedFiles) {
                    val relPath = workspace.relativize(file).toString()
                    tar.add(file, workspace)

                    val sha256 = sha256(file)
                    entries.add(
                        ArtifactEntry(
                            runId = runId.value,
                            stageName = stageName.value,
                            relPath = relPath,
                            sha256 = sha256,
                            size = Files.size(file),
                            archivedAt = Instant.now(clock),
                        )
                    )
                }
            }
        }

        Files.setPosixFilePermissions(archivePath, PosixFilePermissions.fromString("rw-------"))

        return ArchiveResult(
            archivePath = archivePath,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            entries = entries,
        )
    }

    /**
     * Idempotent close — no-op for this implementation.
     */
    fun close() {
        // No resources held beyond the filesystem
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val content = Files.readAllBytes(file)
        digest.update(content)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Result of a successful archive operation.
 */
data class ArchiveResult(
    /** Path to the created tar archive, or null if empty and allowEmptyArchive=true */
    val archivePath: Path?,
    /** SHA-256 hex digest of the tar file, or empty string if no archive created */
    val sha256: String,
    /** Individual file entries with sha256 + size + relPath */
    val entries: List<ArtifactEntry>,
)

/**
 * Thrown when archive is called with no matching files and allowEmptyArchive=false.
 */
class EmptyArchiveException(message: String) : RuntimeException(message)

/**
 * Run identifier — domain value type.
 */
data class RunId(val value: String)

/**
 * Stage name — domain value type.
 */
data class StageName(val value: String)

/**
 * Thrown when archive is invoked on a filesystem that does not support POSIX permissions.
 */
class LocalArtifactStorePosixUnsupportedException(message: String) : RuntimeException(message)

private fun assertPosixSupported(root: Path) {
    if (!root.fileSystem.supportedFileAttributeViews().contains("posix")) {
        throw LocalArtifactStorePosixUnsupportedException(
            "POSIX file attributes not supported on this filesystem"
        )
    }
}
