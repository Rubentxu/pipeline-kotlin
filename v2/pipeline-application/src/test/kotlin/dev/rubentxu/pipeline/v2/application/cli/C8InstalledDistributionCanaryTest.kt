package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * C8-INSTALLED — the root-deletion guard, against the INSTALLED DISTRIBUTION.
 *
 * ## Why this file exists when `SC-011-13` and `SC-011-14` already run real pipelines
 *
 * Because those two are not installed-distribution tests, and reading their
 * names suggests otherwise. `UatLocal011WorkflowControlTest.runPipeline` builds
 *
 * ```text
 * $JAVA_HOME/bin/java -cp <the TEST classpath> MainKt run ...
 * ```
 *
 * so it forks a JVM whose classpath is the build output of `check`. It proves
 * the guard holds in the compiled tree. It says nothing about the artifact a
 * user actually installs: the `installDist` layout, the launcher script, the
 * packaged JARs, the classpath the distribution assembles. Those are different
 * bytes produced by a different task.
 *
 * That distinction is not pedantry. `InstalledDistributionHarnessFitnessTest`
 * exists precisely because harnesses "launch the same runtime by its main class
 * on the test classpath" is a *second door* to the same property, and
 * `UatLocal011WorkflowControlTest.kt` is currently a listed entry in its debt
 * ledger for that reason. A green SC-011-13/14 therefore certifies the tree, not
 * the product.
 *
 * ## What only this can catch
 *
 * - a `deleteDir` / `cleanWs` implementation that a module swap silently
 *   excludes from the shipped classpath, so the packaged run takes a different
 *   path than `compileTestKotlin` sees;
 * - a launcher or layout change that alters the effective workspace root, which
 *   is the value every containment decision is anchored to;
 * - the ownership wiring being assembled at runtime differently by the
 *   application entry point than by a test fixture.
 *
 * ## Harness discipline
 *
 * [OwnedSubprocess], not a bare `ProcessBuilder`: the installed `pipelinek`
 * prints its whole run event log in one `println` and can exceed a pipe buffer,
 * and this file reads the output after waiting. The class `@Timeout` sits
 * outside that: a hung test must fail, not take the suite with it.
 *
 * See `docs/v2/07-uat/C8_WORKSPACE_ROOT_DELETION_RECEIPT.md`.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class C8InstalledDistributionCanaryTest {

    private val binary = AppBinSupport.discover().toAbsolutePath()

    private lateinit var root: Path

    @BeforeEach
    fun createRoot() {
        root = Files.createTempDirectory("c8-installed")
    }

    @AfterEach
    fun removeRoot() {
        root.toFile().deleteRecursively()
    }

    private fun writeScript(name: String, body: String): Path {
        val script = root.resolve(name)
        Files.writeString(script, body.trimIndent())
        return script
    }

    private fun runInstalled(script: Path, vararg extraArgs: String): CliRun.Completed {
        val dbPath = root.resolve("journal-${script.fileName}.db")
        val controlRoot = Files.createDirectories(root.resolve("ctrl-${script.fileName}"))

        val outcome = OwnedSubprocess.run(
            command = listOf(
                binary.toString(),
                "run",
                "--db", dbPath.toString(),
                "--control-root", controlRoot.toString(),
            ) + extraArgs + script.toString(),
            timeout = Duration.ofMinutes(5),
            workingDirectory = root.toFile(),
        )

        return when (outcome) {
            is CliRun.Completed -> outcome
            is CliRun.TimedOut -> error(
                "the installed binary hung on $script after 5 min; " +
                    "pid=${outcome.diagnostics.pid} descendants=${outcome.diagnostics.descendantPids}",
            )
            is CliRun.LaunchFailed -> error(
                "the installed binary could not be launched for $script: ${outcome.cause}",
            )
        }
    }

    @Test
    fun `the installed distribution refuses deleteDir on an attached root and keeps the files`() {
        val script = writeScript(
            "delete-dir.pipeline.kts",
            """
            pipeline {
                stages {
                    stage("test") {
                        sh("echo 'precious' > important.txt")
                        sh("mkdir -p src && echo 'nested' > src/main.kt")
                        deleteDir()
                    }
                }
            }
            """,
        )

        val result = runInstalled(script, "--workspace", root.toString())

        assertNotEquals(
            0,
            result.exitCode,
            "deleteDir() over an attached root must fail closed in the installed " +
                "distribution, but the run exited 0. output: ${result.stdout}${result.stderr}",
        )
        assertFalse(
            Files.exists(root.resolve(".deleted")),
            "no MEMOIZED marker may be written for a refused root deletion; one would make a " +
                "later replay believe the wipe already happened",
        )
        assertTrue(
            Files.exists(root.resolve("important.txt")),
            "the caller's file must survive a refused root deletion in the installed distribution",
        )
        assertTrue(
            Files.exists(root.resolve("src/main.kt")),
            "the caller's nested source must survive a refused root deletion",
        )
    }

    @Test
    fun `the installed distribution refuses a pattern-less cleanWs on an attached root`() {
        // The twin. C9 is the more dangerous of the two: `cleanWs()` with no
        // patterns has no partial form to narrow it by accident.
        val script = writeScript(
            "clean-ws.pipeline.kts",
            """
            pipeline {
                stages {
                    stage("test") {
                        sh("echo 'precious' > important.txt")
                        cleanWs()
                    }
                }
            }
            """,
        )

        val result = runInstalled(script, "--workspace", root.toString())

        assertNotEquals(
            0,
            result.exitCode,
            "cleanWs() over an attached root must fail closed in the installed " +
                "distribution, but the run exited 0. output: ${result.stdout}${result.stderr}",
        )
        assertFalse(
            Files.exists(root.resolve(".cleaned")),
            "no MEMOIZED marker may be written for a refused pattern-less sweep",
        )
        assertTrue(
            Files.exists(root.resolve("important.txt")),
            "the caller's file must survive a refused pattern-less cleanWs",
        )
    }

    @Test
    fun `the installed distribution still deletes a managed scratch root, so the guards are not a blanket refusal`() {
        // If the two rows above passed because the installed binary refused
        // everything, they would prove nothing about ownership. This row pins
        // the other half against the same artifact.
        //
        // It runs with `--isolated`, not `--workspace`. That is not a stylistic
        // choice, it is the measured contract: `WorkspaceIntent.requestFor`
        // maps `workspace != null` to `AttachExplicit` -> `WorkspaceLease.Attached`
        // -> `Refused`, ALWAYS. So **every** `--workspace` run is USER-owned, and
        // the first version of this row passed `--workspace <scratch>` expecting
        // a wipe and failed with
        //
        //   "deleteDir refuses to delete the workspace root itself"
        //
        // while the file it had just created sat untouched. The refusal was
        // correct; the premise about how to reach `ScratchOwned` from the CLI
        // was wrong. `--isolated` is the `ManagedIsolated` request, which is the
        // only flag combination that resolves to `WorkspaceLease.Managed` ->
        // `Permitted`.
        val script = writeScript(
            "scratch-delete.pipeline.kts",
            """
            pipeline {
                stages {
                    stage("test") {
                        deleteDir()
                    }
                }
            }
            """,
        )

        val result = runInstalled(script, "--isolated")

        assertTrue(
            result.exitCode == 0,
            "a managed scratch root must still be deletable in the installed distribution, but " +
                "the run exited ${result.exitCode}. output: ${result.stdout}${result.stderr}",
        )
    }
}
