package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * RP034-E — `dir` scopes the OFFICIAL_PLUGIN utilities family to the current
 * directory, not to the workspace root.
 *
 * The utilities Steps declare `WORKSPACE_IDENTITY_CAPABILITY`, whose runtime
 * value has always been `workingDirectory ?: workspaceRoot` — that is, the cwd
 * when a `dir` scope is active. So the *behaviour* this class freezes is the
 * behaviour already in force; what is under migration (RP034-E) is the
 * authority the Steps ask for, not the paths they produce.
 *
 * That makes this class a characterisation, not a RED test. It exists so the
 * migration to `EXECUTION_LOCATION_CAPABILITY` cannot silently move any of
 * these Steps back onto the workspace root: every assertion here fails the
 * moment a Step resolves against `location.workspace.root` instead of
 * `location.cwd`.
 *
 * All five asserted Steps (`writeYaml`, `readYaml`, `sha256`, `findFiles`,
 * `zipDir`, `unzip`) resolve paths on the way in, and the assertion that a
 * root-scoped resolution is wrong is the explicit absence of the same files at
 * the workspace root.
 */
@Timeout(240)
@DisplayName("RP034-E dir scopes the utilities family to the current directory")
class WorkspaceAnchorScopeEndToEndTest {

    private val processes = mutableListOf<Process>()

    @AfterEach
    fun tearDown() {
        processes.forEach { process ->
            val descendants = process.descendants().toList()
            descendants.forEach { it.destroyForcibly() }
            process.destroyForcibly()
            descendants.forEach { runCatching { it.onExit().get(5, TimeUnit.SECONDS) } }
            process.waitFor(5, TimeUnit.SECONDS)
        }
        processes.clear()
    }

    @Test
    @DisplayName("every utilities Step resolves its input and output under the dir scope")
    fun `utilities resolve under the dir scope not the root`(@TempDir tempDir: Path) {
        val project = Files.createDirectory(tempDir.resolve("project"))
        val workspace = Files.createDirectory(tempDir.resolve("workspace"))
        Files.createDirectories(workspace.resolve("backend"))

        val script = project.resolve("pipeline.kts")
        Files.writeString(
            script,
            """
            import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeYaml
            import dev.rubentxu.pipeline.v2.sdk.utilities.step.readYaml
            import dev.rubentxu.pipeline.v2.sdk.utilities.step.sha256
            import dev.rubentxu.pipeline.v2.sdk.utilities.step.findFiles
            import dev.rubentxu.pipeline.v2.sdk.utilities.step.zipDir
            import dev.rubentxu.pipeline.v2.sdk.utilities.step.unzip
            import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument

            pipeline {
                stages {
                    stage("scoped") {
                        dir("backend") {
                            writeYaml(
                                "cfg.yaml",
                                value = YamlDocument.Map(listOf(
                                    YamlDocument.Map.Entry("name", YamlDocument.Str("scoped")),
                                )),
                            )
                            readYaml("cfg.yaml")
                            sha256("cfg.yaml")
                            findFiles(base = ".", glob = "*.yaml")
                            zipDir(path = "bundle.zip", directory = ".")
                            unzip(path = "bundle.zip", destination = "restored")
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runCli(
            project,
            listOf(
                "run",
                "--db", tempDir.resolve("durable.db").toString(),
                "--control-root", tempDir.resolve("control").toString(),
                "--workspace", workspace.toString(),
                script.toString(),
            ),
        )

        assertEquals(0, result.exitCode, result.output)

        val scoped = workspace.resolve("backend")

        // writeYaml: the produced document must land under the scope.
        assertTrue(
            Files.exists(scoped.resolve("cfg.yaml")),
            "writeYaml inside dir('backend') must write ${scoped.resolve("cfg.yaml")}. Output: ${result.output}",
        )

        // zipDir: the archive path is resolved against the scope too.
        assertTrue(
            Files.exists(scoped.resolve("bundle.zip")),
            "zipDir inside dir('backend') must write ${scoped.resolve("bundle.zip")}. Output: ${result.output}",
        )

        // unzip: the destination is a cwd-relative path.
        assertTrue(
            Files.exists(scoped.resolve("restored/cfg.yaml")),
            "unzip inside dir('backend') must extract to ${scoped.resolve("restored/cfg.yaml")}. " +
                "Output: ${result.output}",
        )

        // The discriminating assertion: none of the above may appear at the
        // workspace root. If any Step anchored on WORKSPACE_ROOT, exactly one
        // of these would exist and the migration would have regressed the
        // CURRENT_DIRECTORY contract from 04-step-path-anchor-matrix.md.
        listOf("cfg.yaml", "bundle.zip", "restored").forEach { leaked ->
            assertFalse(
                Files.exists(workspace.resolve(leaked)),
                "'$leaked' must not exist at the workspace root: a utilities Step resolved " +
                    "against WORKSPACE_ROOT instead of CURRENT_DIRECTORY. Output: ${result.output}",
            )
        }
    }

    /**
     * DF-UNSTASH-001: stash source and unstash target both follow the active
     * `dir` scope, while the stash store stays an internal control-root path.
     *
     * Stashing from `producer/` and restoring into `consumer/` is the case that
     * a Step re-deriving its base from `controlDirRoot/stage/index` gets wrong:
     * it would read and write the stage workspace, so the restore would land at
     * the root instead of inside `consumer/`.
     */
    @Test
    @DisplayName("stash reads from the dir scope and unstash restores into the dir scope")
    fun `stash source and unstash target follow the dir scope`(@TempDir tempDir: Path) {
        val project = Files.createDirectory(tempDir.resolve("project"))
        val workspace = Files.createDirectory(tempDir.resolve("workspace"))
        Files.createDirectories(workspace.resolve("producer"))
        Files.createDirectories(workspace.resolve("consumer"))

        val script = project.resolve("pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("produce") {
                        dir("producer") {
                            writeFile("payload.txt", "carried-across-scopes")
                            stash(name = "rp034-payload", includes = "**/*.txt")
                        }
                    }
                    stage("consume") {
                        dir("consumer") {
                            unstash(name = "rp034-payload")
                            sh("cat payload.txt")
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runCli(
            project,
            listOf(
                "run",
                "--db", tempDir.resolve("durable.db").toString(),
                "--control-root", tempDir.resolve("control").toString(),
                "--workspace", workspace.toString(),
                script.toString(),
            ),
        )

        assertEquals(0, result.exitCode, result.output)

        assertTrue(
            Files.exists(workspace.resolve("consumer/payload.txt")),
            "unstash inside dir('consumer') must restore into the scope. Output: ${result.output}",
        )
        assertEquals(
            "carried-across-scopes",
            Files.readString(workspace.resolve("consumer/payload.txt")),
        )

        // The stash store is an INTERNAL_STORE path under the control root and
        // must never be confused with a workspace anchor.
        assertFalse(
            Files.exists(workspace.resolve("payload.txt")),
            "the restored file must not appear at the workspace root: unstash ignored the scope. " +
                "Output: ${result.output}",
        )
        assertTrue(
            Files.exists(workspace.resolve("producer/payload.txt")),
            "the stashed source must remain in the producer scope. Output: ${result.output}",
        )
    }

    private fun runCli(workingDirectory: Path, arguments: List<String>): CliResult {
        val output = Files.createTempFile(workingDirectory, "rp034e-cli-", ".log")
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            *arguments.toTypedArray(),
        )
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()
        processes += process

        val completed = process.waitFor(180, TimeUnit.SECONDS)
        assertTrue(completed, "CLI did not finish: ${Files.readString(output)}")
        return CliResult(process.exitValue(), Files.readString(output))
    }

    private data class CliResult(val exitCode: Int, val output: String)
}
