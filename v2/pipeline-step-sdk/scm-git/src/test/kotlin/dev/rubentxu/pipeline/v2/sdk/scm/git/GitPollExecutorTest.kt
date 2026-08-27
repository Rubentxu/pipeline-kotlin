package dev.rubentxu.pipeline.v2.sdk.scm.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for GitPollExecutor.
 *
 * Requires real git on PATH (V2_GIT_AVAILABLE=true).
 * Uses a local bare repo fixture.
 */
@EnabledIfEnvironmentVariable(named = "V2_GIT_AVAILABLE", matches = "true")
class GitPollExecutorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `execute against file remote returns sha`() {
        // Create a local bare repo fixture
        val bareRepo = tempDir.resolve("fixture.git")
        Files.createDirectories(bareRepo)

        val pb = ProcessBuilder("git", "init", "--bare", bareRepo.toString())
        pb.directory(tempDir.toFile())
        pb.inheritIO()
        val initResult = pb.start().waitFor()
        assertEquals(0, initResult, "git init --bare must succeed")

        // Create a working repo and push
        val workDir = tempDir.resolve("work")
        Files.createDirectories(workDir)
        val initWork = ProcessBuilder("git", "init")
        initWork.directory(workDir.toFile())
        initWork.inheritIO()
        initWork.start().waitFor()

        val commitFile = workDir.resolve("README.txt")
        Files.writeString(commitFile, "Hello")
        val addPb = ProcessBuilder("git", "add", ".")
        addPb.directory(workDir.toFile())
        addPb.inheritIO()
        addPb.start().waitFor()

        val commitPb = ProcessBuilder("git", "commit", "-m", "Initial commit")
        commitPb.directory(workDir.toFile())
        commitPb.inheritIO()
        commitPb.start().waitFor()

        val pushPb = ProcessBuilder("git", "push", bareRepo.toString(), "master", "--set-upstream")
        pushPb.directory(workDir.toFile())
        pushPb.inheritIO()
        pushPb.start().waitFor()

        // Now test the poll executor
        val executor = GitPollExecutor()
        val result = executor.execute(bareRepo.toString(), "master")

        assertTrue(result.isSuccess, "Poll must succeed: ${result.exceptionOrNull()?.message}")
        val sha = result.getOrNull()
        assertTrue(!sha.isNullOrBlank(), "SHA must not be blank")
        assertEquals(40, sha?.length, "SHA must be full 40-char git hash")
    }

    @Test
    fun `execute against non-existent remote returns failure`() {
        val executor = GitPollExecutor()
        val result = executor.execute("https://nonexistent.example.com/repo.git", "master")

        assertTrue(result.isFailure, "Must fail for non-existent remote")
    }
}
