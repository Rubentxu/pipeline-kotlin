package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.step.artifact.AuthorizedFileDescriptor
import dev.rubentxu.pipeline.v2.domain.step.artifact.CollisionPolicy
import dev.rubentxu.pipeline.v2.domain.step.artifact.ExecutablePermissionPolicy
import dev.rubentxu.pipeline.v2.domain.step.artifact.MaterializationError
import dev.rubentxu.pipeline.v2.domain.step.artifact.MaterializationOutcome
import dev.rubentxu.pipeline.v2.domain.step.artifact.MaterializationPolicy
import dev.rubentxu.pipeline.v2.domain.step.artifact.MaterializationRequest
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger

class LocalWorkspaceFileMaterializerTest {

    @TempDir
    lateinit var tempDir: Path

    private val materializer = LocalWorkspaceFileMaterializer()

    private fun newLocation(
        workspace: Path = tempDir.resolve("workspace"),
        cwd: Path = workspace,
    ): ExecutionLocation {
        Files.createDirectories(workspace)
        Files.createDirectories(cwd)
        return ExecutionLocation(
            workspace = WorkspaceLease.Managed(workspace),
            cwd = cwd,
        )
    }

    private fun createSourceFile(name: String, content: ByteArray, executable: Boolean = false): Path {
        val srcDir = tempDir.resolve("sources")
        Files.createDirectories(srcDir)
        val file = srcDir.resolve(name)
        Files.createDirectories(file.parent)
        Files.write(file, content)
        if (executable) {
            Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
                ?.setPermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        return file
    }

    private fun descriptorFor(
        source: Path,
        relativeDestination: String,
        content: ByteArray = Files.readAllBytes(source),
        executable: Boolean = false,
    ) = AuthorizedFileDescriptor(
        sourcePath = source,
        relativeDestination = relativeDestination,
        expectedSizeBytes = content.size.toLong(),
        expectedSha256 = Sha256.ofBytes(content),
        executable = executable,
    )

    private fun assertNoTempArtifacts(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            val leftovers = stream.filter {
                val name = it.fileName?.toString().orEmpty()
                name.startsWith(".pipelinek-stage-") || name.startsWith(".pipelinek-bak-")
            }.toList()
            assertTrue(leftovers.isEmpty(), "expected no staging/backup leftovers, found $leftovers")
        }
    }

    @Test
    fun `materializes multiple files into dir-scoped execution location`() {
        val ws = tempDir.resolve("ws")
        val subDir = ws.resolve("modules/app")
        val location = newLocation(ws, subDir)
        val bytesA = "alpha-bytes".toByteArray()
        val bytesB = "beta-bytes".toByteArray()
        val srcA = createSourceFile("a.txt", bytesA)
        val srcB = createSourceFile("b.bin", bytesB)

        val outcome = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(
                    descriptorFor(srcA, "out/a.txt", bytesA),
                    descriptorFor(srcB, "nested/b.bin", bytesB),
                ),
            ),
        )

        assertTrue(outcome is MaterializationOutcome.Materialized, "got $outcome")
        val files = (outcome as MaterializationOutcome.Materialized).files
        assertEquals(2, files.size)
        assertEquals("alpha-bytes", Files.readString(subDir.resolve("out/a.txt")))
        assertEquals("beta-bytes", Files.readString(subDir.resolve("nested/b.bin")))
        assertNoTempArtifacts(ws)
    }

    @Test
    fun `rejects sha256 mismatch and leaves prior destination intact`() {
        val ws = tempDir.resolve("ws")
        val location = newLocation(ws)
        val dest = ws.resolve("config.json")
        Files.writeString(dest, "prior-valid-content")

        val actualBytes = "corrupt-bytes".toByteArray()
        val expectedBytes = "trusted-bytes".toByteArray()
        val src = createSourceFile("corrupt.json", actualBytes)

        val outcome = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(
                    AuthorizedFileDescriptor(
                        sourcePath = src,
                        relativeDestination = "config.json",
                        expectedSizeBytes = expectedBytes.size.toLong(),
                        expectedSha256 = Sha256.ofBytes(expectedBytes),
                    ),
                ),
                policy = MaterializationPolicy(collisionPolicy = CollisionPolicy.OVERWRITE),
            ),
        )

        assertTrue(outcome is MaterializationOutcome.Failed)
        assertTrue((outcome as MaterializationOutcome.Failed).error is MaterializationError.ChecksumMismatch)
        assertEquals("prior-valid-content", Files.readString(dest))
        assertNoTempArtifacts(ws)
    }

    @Test
    fun `rejects size mismatch and leaves no partial file`() {
        val ws = tempDir.resolve("ws")
        val location = newLocation(ws)
        val actualBytes = "short".toByteArray()
        val src = createSourceFile("short.bin", actualBytes)

        val outcome = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(
                    AuthorizedFileDescriptor(
                        sourcePath = src,
                        relativeDestination = "out/short.bin",
                        expectedSizeBytes = 999L,
                        expectedSha256 = Sha256.ofBytes(actualBytes),
                    ),
                ),
            ),
        )

        assertTrue(outcome is MaterializationOutcome.Failed)
        assertTrue((outcome as MaterializationOutcome.Failed).error is MaterializationError.SizeMismatch)
        assertFalse(Files.exists(ws.resolve("out/short.bin")))
        assertNoTempArtifacts(ws)
    }

    @Test
    fun `enforces reject and overwrite collision policies and refuses directory collision`() {
        val ws = tempDir.resolve("ws")
        val location = newLocation(ws)
        val dest = ws.resolve("app.txt")
        Files.writeString(dest, "original")
        val newBytes = "updated".toByteArray()
        val src = createSourceFile("app.txt", newBytes)

        val rejected = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(descriptorFor(src, "app.txt", newBytes)),
                policy = MaterializationPolicy(collisionPolicy = CollisionPolicy.REJECT),
            ),
        )
        assertTrue(rejected is MaterializationOutcome.Failed)
        assertTrue((rejected as MaterializationOutcome.Failed).error is MaterializationError.DestinationCollision)
        assertEquals("original", Files.readString(dest))

        val overwritten = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(descriptorFor(src, "app.txt", newBytes)),
                policy = MaterializationPolicy(collisionPolicy = CollisionPolicy.OVERWRITE),
            ),
        )
        assertTrue(overwritten is MaterializationOutcome.Materialized)
        assertEquals("updated", Files.readString(dest))

        val dirTarget = ws.resolve("existing-dir")
        Files.createDirectories(dirTarget)
        Files.writeString(dirTarget.resolve("keep.txt"), "keep")
        val dirCollision = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(descriptorFor(src, "existing-dir", newBytes)),
                policy = MaterializationPolicy(collisionPolicy = CollisionPolicy.OVERWRITE),
            ),
        )
        assertTrue(dirCollision is MaterializationOutcome.Failed)
        assertTrue(
            (dirCollision as MaterializationOutcome.Failed).error is MaterializationError.DestinationIsDirectory,
        )
        assertEquals("keep", Files.readString(dirTarget.resolve("keep.txt")))
        assertNoTempArtifacts(ws)
    }

    @Test
    fun `simulated disk-full during staging leaves prior destination intact and cleans temp files`() {
        val ws = tempDir.resolve("ws")
        val location = newLocation(ws)
        val dest = ws.resolve("artifact.jar")
        Files.writeString(dest, "intact-prior")

        val bytes = "new-artifact-content".toByteArray()
        val src = createSourceFile("artifact.jar", bytes)

        val failingMaterializer = LocalWorkspaceFileMaterializer(
            stagingCopier = { _, stagingFile ->
                Files.write(stagingFile, "partial-".toByteArray())
                throw IOException("No space left on device")
            },
            fileMover = { s, d -> Files.move(s, d) },
        )

        val outcome = failingMaterializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(descriptorFor(src, "artifact.jar", bytes)),
                policy = MaterializationPolicy(collisionPolicy = CollisionPolicy.OVERWRITE),
            ),
        )

        assertTrue(outcome is MaterializationOutcome.Failed)
        assertTrue((outcome as MaterializationOutcome.Failed).error is MaterializationError.IoFailure)
        assertEquals("intact-prior", Files.readString(dest))
        assertNoTempArtifacts(ws)
    }

    @Test
    fun `rolls back committed files when a later file fails during batch commit`() {
        val ws = tempDir.resolve("ws")
        val location = newLocation(ws)
        val firstDest = ws.resolve("first.txt")
        Files.writeString(firstDest, "first-before")

        val bytes1 = "first-after".toByteArray()
        val bytes2 = "second-after".toByteArray()
        val src1 = createSourceFile("first.txt", bytes1)
        val src2 = createSourceFile("second.txt", bytes2)

        val moveCalls = AtomicInteger(0)
        val failingCommitMaterializer = LocalWorkspaceFileMaterializer(
            stagingCopier = { s, d -> Files.write(d, Files.readAllBytes(s)) },
            fileMover = { s, d ->
                // Call 1 moves first.txt -> backup, call 2 moves staged1 -> first.txt, call 3 fails on second.txt
                if (moveCalls.incrementAndGet() == 3) {
                    throw IOException("Simulated I/O error on second file commit")
                }
                Files.move(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            },
        )

        val outcome = failingCommitMaterializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(
                    descriptorFor(src1, "first.txt", bytes1),
                    descriptorFor(src2, "second.txt", bytes2),
                ),
                policy = MaterializationPolicy(collisionPolicy = CollisionPolicy.OVERWRITE),
            ),
        )

        assertTrue(outcome is MaterializationOutcome.Failed)
        assertTrue((outcome as MaterializationOutcome.Failed).error is MaterializationError.IoFailure)
        assertEquals("first-before", Files.readString(firstDest))
        assertFalse(Files.exists(ws.resolve("second.txt")))
        assertNoTempArtifacts(ws)
    }

    @Test
    fun `rejects symlinks in source destination and parent directories`() {
        val ws = tempDir.resolve("ws")
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        val location = newLocation(ws)

        val realSecret = outside.resolve("secret.txt")
        val secretBytes = "secret".toByteArray()
        Files.write(realSecret, secretBytes)

        // 1. Source is a symlink
        val symSource = tempDir.resolve("sym-source.txt")
        Files.createSymbolicLink(symSource, realSecret)
        val srcOutcome = materializer.materialize(
            MaterializationRequest(location, listOf(descriptorFor(symSource, "out.txt", secretBytes))),
        )
        assertTrue(srcOutcome is MaterializationOutcome.Failed)
        assertTrue((srcOutcome as MaterializationOutcome.Failed).error is MaterializationError.SymlinkRejected)

        // 2. Destination parent directory is a symlink escaping workspace
        val validBytes = "ok".toByteArray()
        val validSrc = createSourceFile("ok.txt", validBytes)
        Files.createSymbolicLink(ws.resolve("escape-dir"), outside)
        val parentSymOutcome = materializer.materialize(
            MaterializationRequest(location, listOf(descriptorFor(validSrc, "escape-dir/out.txt", validBytes))),
        )
        assertTrue(parentSymOutcome is MaterializationOutcome.Failed)
        assertTrue((parentSymOutcome as MaterializationOutcome.Failed).error is MaterializationError.SymlinkRejected)
        assertFalse(Files.exists(outside.resolve("out.txt")))

        // 3. Destination itself is a symlink
        Files.createSymbolicLink(ws.resolve("sym-dest.txt"), outside.resolve("target.txt"))
        val destSymOutcome = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(descriptorFor(validSrc, "sym-dest.txt", validBytes)),
                policy = MaterializationPolicy(collisionPolicy = CollisionPolicy.OVERWRITE),
            ),
        )
        assertTrue(destSymOutcome is MaterializationOutcome.Failed)
        assertTrue((destSymOutcome as MaterializationOutcome.Failed).error is MaterializationError.SymlinkRejected)
        assertFalse(Files.exists(outside.resolve("target.txt")))

        // 4. Destination parent directory is an in-workspace symlink (redirects writes inside workspace)
        val realSub = ws.resolve("real-sub")
        Files.createDirectories(realSub)
        Files.createSymbolicLink(ws.resolve("in-ws-link"), realSub)
        val inWsSymOutcome = materializer.materialize(
            MaterializationRequest(location, listOf(descriptorFor(validSrc, "in-ws-link/out.txt", validBytes))),
        )
        assertTrue(inWsSymOutcome is MaterializationOutcome.Failed)
        assertTrue((inWsSymOutcome as MaterializationOutcome.Failed).error is MaterializationError.SymlinkRejected)
        assertFalse(Files.exists(realSub.resolve("out.txt")))
    }

    @Test
    fun `rejects hardlinks in source and existing destination`() {
        val ws = tempDir.resolve("ws")
        val location = newLocation(ws)
        val bytes = "hardlinked".toByteArray()
        val src = createSourceFile("hard.bin", bytes)
        val extraLink = tempDir.resolve("extra-hardlink.bin")
        Files.createLink(extraLink, src)

        val srcOutcome = materializer.materialize(
            MaterializationRequest(location, listOf(descriptorFor(src, "hard.bin", bytes))),
        )
        assertTrue(srcOutcome is MaterializationOutcome.Failed)
        assertTrue((srcOutcome as MaterializationOutcome.Failed).error is MaterializationError.HardlinkRejected)

        // Existing destination with hardlink under OVERWRITE
        val cleanBytes = "clean".toByteArray()
        val cleanSrc = createSourceFile("clean.bin", cleanBytes)
        val dest = ws.resolve("linked-dest.bin")
        Files.writeString(dest, "existing")
        Files.createLink(tempDir.resolve("dest-other-link.bin"), dest)

        val destOutcome = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(descriptorFor(cleanSrc, "linked-dest.bin", cleanBytes)),
                policy = MaterializationPolicy(collisionPolicy = CollisionPolicy.OVERWRITE),
            ),
        )
        assertTrue(destOutcome is MaterializationOutcome.Failed)
        assertTrue((destOutcome as MaterializationOutcome.Failed).error is MaterializationError.HardlinkRejected)
    }

    @Test
    fun `allows workspace reached through a symlinked ancestor`() {
        val realParent = tempDir.resolve("real-parent")
        Files.createDirectories(realParent)
        val linkParent = tempDir.resolve("link-parent")
        Files.createSymbolicLink(linkParent, realParent)

        val wsViaLink = linkParent.resolve("ws")
        val location = newLocation(wsViaLink)
        val bytes = "hello".toByteArray()
        val src = createSourceFile("hello.txt", bytes)

        val outcome = materializer.materialize(
            MaterializationRequest(location, listOf(descriptorFor(src, "dir/hello.txt", bytes))),
        )

        assertTrue(outcome is MaterializationOutcome.Materialized, "got $outcome")
        assertEquals("hello", Files.readString(realParent.resolve("ws/dir/hello.txt")))
    }

    @Test
    fun `applies executable permission only when authorized by both descriptor and policy`() {
        val ws = tempDir.resolve("ws")
        val location = newLocation(ws)
        val bytes = "#!/bin/sh\necho ok\n".toByteArray()
        // Source has +x on disk, but descriptor defaults to executable = false.
        val src = createSourceFile("script.sh", bytes, executable = true)

        val defaultOutcome = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(descriptorFor(src, "non-exec.sh", bytes, executable = false)),
                policy = MaterializationPolicy(executablePolicy = ExecutablePermissionPolicy.ALLOW_AUTHORIZED),
            ),
        )
        assertTrue(defaultOutcome is MaterializationOutcome.Materialized)
        assertFalse(Files.isExecutable(ws.resolve("non-exec.sh")))

        val forbiddenOutcome = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(descriptorFor(src, "forbidden.sh", bytes, executable = true)),
                policy = MaterializationPolicy(executablePolicy = ExecutablePermissionPolicy.FORBID),
            ),
        )
        assertTrue(forbiddenOutcome is MaterializationOutcome.Failed)
        assertTrue(
            (forbiddenOutcome as MaterializationOutcome.Failed).error is MaterializationError.ExecutableNotAuthorized,
        )
        assertFalse(Files.exists(ws.resolve("forbidden.sh")))

        val authorizedOutcome = materializer.materialize(
            MaterializationRequest(
                location = location,
                files = listOf(descriptorFor(src, "run.sh", bytes, executable = true)),
                policy = MaterializationPolicy(executablePolicy = ExecutablePermissionPolicy.ALLOW_AUTHORIZED),
            ),
        )
        assertTrue(authorizedOutcome is MaterializationOutcome.Materialized)
        assertTrue(Files.isExecutable(ws.resolve("run.sh")))
        val materialized = (authorizedOutcome as MaterializationOutcome.Materialized).files.single()
        assertTrue(materialized.executable)
    }

    @Test
    fun `rejects traversal and absolute destination paths end to end`() {
        val ws = tempDir.resolve("ws")
        val location = newLocation(ws)
        val bytes = "data".toByteArray()
        val src = createSourceFile("data.txt", bytes)

        for (badDest in listOf("../escape.txt", "/etc/passwd", ws.resolve("abs.txt").toString())) {
            val outcome = materializer.materialize(
                MaterializationRequest(location, listOf(descriptorFor(src, badDest, bytes))),
            )
            assertTrue(outcome is MaterializationOutcome.Failed, "expected failure for '$badDest'")
        }
    }
}
