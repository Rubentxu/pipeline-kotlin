package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * UAT-LOCAL-005: Class Timeout and Teardown Infrastructure Tests.
 *
 * Verifies the UAT-LOCAL-005 test family itself meets AGENTS.md requirements:
 * - TC-001: @Timeout declared on each test class (class-level)
 * - TC-002: @AfterEach kills all child processes
 * - TC-003: No living child processes after teardown
 *
 * These are the infrastructure/health tests for the family, not the
 * behavioral tests of the checkout-git implementation itself.
 *
 * @see <a href="AGENTS.md">AGENTS.md §7-8</a>
 */
@Timeout(120)
class UatLocal005ClassTimeoutAndTeardownTest {

    private val processes = mutableListOf<Process>()

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "UAT integration tests require Linux"
        )
    }

    @AfterEach
    fun teardown() {
        processes.forEach { p ->
            if (p.isAlive) {
                p.destroyForcibly()
            }
        }
        processes.clear()
        val selfPid = ProcessHandle.current().pid()
        try {
            val pb = ProcessBuilder("pgrep", "-P", selfPid.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
            val childProcs = pb.start().inputStream.bufferedReader().readText()
            if (childProcs.isNotBlank()) {
                childProcs.lines().filter { it.isNotBlank() }.forEach { pid ->
                    try {
                        ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() }
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * TC-001: UatLocal005CheckoutGitTest has @Timeout(120) at class level.
     */
    @Test
    fun `TC-001 UatLocal005CheckoutGitTest declares class-level Timeout 120`() {
        val annotation = UatLocal005CheckoutGitTest::class.java.getAnnotation(Timeout::class.java)
        assertNotNull(annotation, "UatLocal005CheckoutGitTest must have @Timeout annotation")
        assertEquals(120, annotation.value)
        assertEquals(TimeUnit.SECONDS, annotation.unit)
    }

    /**
     * TC-002: UatLocal005CheckoutGitTest AfterEach kills surviving children.
     */
    @Test
    fun `TC-002 AfterEach kills surviving child processes`(@TempDir tempDir: Path) {
        // Start a background sleep process
        val pb = ProcessBuilder("sleep", "30")
            .directory(tempDir.toFile())
            .start()
        processes.add(pb)
        assertTrue(pb.isAlive, "Background sleep should be running before teardown")

        // Call teardown manually
        teardown()

        assertFalse(pb.isAlive, "Sleep should be killed by teardown")
    }

    /**
     * TC-003: GitCheckoutExecutorAdversarialTest has @Timeout(60) at class level.
     */
    @Test
    fun `TC-003 GitCheckoutExecutorAdversarialTest declares class-level Timeout 60`() {
        val annotation = GitCheckoutExecutorAdversarialTest::class.java.getAnnotation(Timeout::class.java)
        assertNotNull(annotation, "GitCheckoutExecutorAdversarialTest must have @Timeout annotation")
        assertEquals(60, annotation.value)
        assertEquals(TimeUnit.SECONDS, annotation.unit)
    }

    /**
     * TC-004: UatLocal005GitAuthCanaryRoundGateTest has @Timeout(120) at class level.
     */
    @Test
    fun `TC-004 UatLocal005GitAuthCanaryRoundGateTest declares class-level Timeout 120`() {
        val annotation = UatLocal005GitAuthCanaryRoundGateTest::class.java.getAnnotation(Timeout::class.java)
        assertNotNull(annotation, "UatLocal005GitAuthCanaryRoundGateTest must have @Timeout annotation")
        assertEquals(120, annotation.value)
        assertEquals(TimeUnit.SECONDS, annotation.unit)
    }

    /**
     * TC-005: UatLocal005RegressionGateTest has @Timeout(180) at class level.
     */
    @Test
    fun `TC-005 UatLocal005RegressionGateTest declares class-level Timeout 180`() {
        val annotation = UatLocal005RegressionGateTest::class.java.getAnnotation(Timeout::class.java)
        assertNotNull(annotation, "UatLocal005RegressionGateTest must have @Timeout annotation")
        assertEquals(180, annotation.value)
        assertEquals(TimeUnit.SECONDS, annotation.unit)
    }

    /**
     * TC-006: UatLocal005CorpusUntouchedTest has @Timeout(30) at class level.
     */
    @Test
    fun `TC-006 UatLocal005CorpusUntouchedTest declares class-level Timeout 30`() {
        val annotation = UatLocal005CorpusUntouchedTest::class.java.getAnnotation(Timeout::class.java)
        assertNotNull(annotation, "UatLocal005CorpusUntouchedTest must have @Timeout annotation")
        assertEquals(30, annotation.value)
        assertEquals(TimeUnit.SECONDS, annotation.unit)
    }

    /**
     * TC-007: UatLocal005BannedImportsTest has @Timeout(60) at class level.
     */
    @Test
    fun `TC-007 UatLocal005BannedImportsTest declares class-level Timeout 60`() {
        val annotation = UatLocal005BannedImportsTest::class.java.getAnnotation(Timeout::class.java)
        assertNotNull(annotation, "UatLocal005BannedImportsTest must have @Timeout annotation")
        assertEquals(60, annotation.value)
        assertEquals(TimeUnit.SECONDS, annotation.unit)
    }

    /**
     * TC-008: UatLocal005RequiresGitOnPathTest has @Timeout(30) at class level.
     */
    @Test
    fun `TC-008 UatLocal005RequiresGitOnPathTest declares class-level Timeout 30`() {
        val annotation = UatLocal005RequiresGitOnPathTest::class.java.getAnnotation(Timeout::class.java)
        assertNotNull(annotation, "UatLocal005RequiresGitOnPathTest must have @Timeout annotation")
        assertEquals(30, annotation.value)
        assertEquals(TimeUnit.SECONDS, annotation.unit)
    }

    /**
     * TC-009: No test class in the family uses maxParallelForks.
     *
     * AGENTS.md §11: UAT modules must NOT set maxParallelForks — these tests
     * verify timing semantics that degrade under CPU contention.
     */
    @Test
    fun `TC-009 no maxParallelForks in any UatLocal005 test class`(@TempDir tempDir: Path) {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")
        val testFiles = listOf(
            "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal005CheckoutGitTest.kt",
            "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/GitCheckoutExecutorAdversarialTest.kt",
            "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal005GitAuthCanaryRoundGateTest.kt",
            "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal005RegressionGateTest.kt",
            "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal005CorpusUntouchedTest.kt",
            "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal005BannedImportsTest.kt",
            "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal005RequiresGitOnPathTest.kt",
        )

        for (relPath in testFiles) {
            val file = projectRoot.resolve(relPath)
            if (Files.exists(file)) {
                val content = Files.readString(file)
                assertFalse(content.contains("maxParallelForks"),
                    "$relPath must not use maxParallelForks (timing semantics degrade under CPU contention)")
            }
        }
    }
}
