package dev.rubentxu.pipeline.v2.sdk.scm.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertLinesMatch
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Tests for GitChangelogWriter.
 *
 * Requires real git on PATH (V2_GIT_AVAILABLE=true).
 * Uses a local bare repo fixture.
 */
@EnabledIfEnvironmentVariable(named = "V2_GIT_AVAILABLE", matches = "true")
class GitChangelogWriterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `append formats first commit correctly`() {
        // Create a local bare repo and work dir
        val bareRepo = tempDir.resolve("fixture.git")
        Files.createDirectories(bareRepo)

        ProcessBuilder("git", "init", "--bare", bareRepo.toString())
            .directory(tempDir.toFile())
            .inheritIO()
            .start().waitFor()

        val workDir = tempDir.resolve("work")
        Files.createDirectories(workDir)
        ProcessBuilder("git", "init")
            .directory(workDir.toFile())
            .inheritIO()
            .start().waitFor()

        val readme = workDir.resolve("README.txt")
        Files.writeString(readme, "Hello World")
        ProcessBuilder("git", "add", ".")
            .directory(workDir.toFile())
            .inheritIO()
            .start().waitFor()
        ProcessBuilder("git", "commit", "-m", "Initial commit with README")
            .directory(workDir.toFile())
            .inheritIO()
            .start().waitFor()
        ProcessBuilder("git", "push", bareRepo.toString(), "master", "--set-upstream")
            .directory(workDir.toFile())
            .inheritIO()
            .start().waitFor()

        // Now test the changelog writer
        val writer = GitChangelogWriter()
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        // Clone into workspace
        ProcessBuilder("git", "clone", bareRepo.toString(), workspace.toString())
            .directory(tempDir.toFile())
            .inheritIO()
            .start().waitFor()

        // Write changelog with first commit
        val changelogPath = workspace.resolve("changelog.txt")
        writer.append(workspace, ".", "0000000", "HEAD")

        assertTrue(Files.exists(changelogPath), "changelog.txt must exist")

        val lines = Files.readAllLines(changelogPath)
        assertTrue(lines.isNotEmpty(), "changelog.txt must have at least one line")

        // First line must match <7-hex> <subject> format
        val firstLine = lines[0]
        val shaPattern = Pattern.compile("^[0-9a-f]{7}\\s\\S.*$")
        assertTrue(shaPattern.matcher(firstLine).matches(),
            "First line must match '<7-hex> <subject>' pattern, got: $firstLine")
    }

    @Test
    fun `append is idempotent - skips already listed commits`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)
        val changelogPath = workspace.resolve("changelog.txt")

        // Write the same entry twice
        val writer = GitChangelogWriter()
        writer.append(workspace, ".", "0000000", "HEAD")
        writer.append(workspace, ".", "0000000", "HEAD")

        val lines = Files.readAllLines(changelogPath)
        assertEquals(1, lines.size, "Idempotent append must not duplicate entries")
    }
}
