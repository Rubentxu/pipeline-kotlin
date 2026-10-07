package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * BLOCK 2 — the product proof of the admission chain, and the gap it found.
 *
 * ## Why this class exists at all, given that a gate already covers admission
 *
 * [dev.rubentxu.pipeline.v2.application.PluginAdmissionPreLoadOrderingTest] and friends prove the
 * admission chain is fail-closed. They prove it by calling [dev.rubentxu.pipeline.v2.application.PluginAdmissionGate]
 * **directly**. That is a real proof of a real property — and it is a proof of a property that no
 * shipped command ever asks for.
 *
 * BLOCK 1 ended with eight callers of `admitThenLoad` and **every one of them in `src/test`**. The
 * installed `pipelinek` reaches plugins through `PluginComposition.resolve`, a different path, so the
 * whole of S6/B..E is unreachable from the product.
 *
 * ## What this class asserts, and why it asserts the WRONG thing on purpose
 *
 * Both rows below are CHARACTERISATION of a known defect, deliberately. Each says what the installed
 * binary does **today** with an artifact the admission chain would refuse, and its failure message
 * says what to change it to.
 *
 * That is not a way to make a defect acceptable. It is the difference between an invisible hole and
 * a tracked one: a hole nobody can see is closed by accident or never, while a row that names itself
 * when the behaviour changes announces its own fix.
 *
 * ## Why these two mutants and not others
 *
 * - **no manifest**: the weakest possible artifact. The gate refuses it, and refusing it is the
 *   property `PluginAdmissionPreLoadOrderingTest.artifactWithoutManifestIsRefused` already pins.
 * - **declared-but-unimplemented Step**: a plugin that ships a real manifest and LIES in it. The
 *   cross-check in `admitContributions` is what catches this, and it is the only link of the chain
 *   that cannot be reached by reading a manifest at all.
 *
 * A third mutant was considered — an `apiRange` the runtime does not satisfy — and dropped: it
 * would prove the same link twice while adding a fourth fixture to maintain.
 *
 * ## Harness fidelity
 *
 * HF2. This crosses the productive authority twice — the installed binary in one process, and a
 * freshly constructed artifact in another — and it asserts on discrete observations: an exit code
 * and a composition line. Nothing here reads a duration, a size or an ordering.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class PluginAdmissionInstalledDistributionUatTest {

    private val binary: File = AppBinSupport.discover().toFile()

    private val pluginJar: Path = locatePluginJar()

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(vararg args: String): CliResult {
        val process = ProcessBuilder(binary.absolutePath, *args).start()
        assertTrue(process.waitFor(5, TimeUnit.MINUTES)) { "the installed binary hung on ${args.toList()}" }
        return CliResult(
            process.exitValue(),
            process.inputStream.bufferedReader().readText(),
            process.errorStream.bufferedReader().readText(),
        )
    }

    /** Write a script whose only Step comes from the external plugin. */
    private fun writeScript(dir: Path): Path {
        val script = dir.resolve("admission.pipeline.kts")
        Files.writeString(
            script,
            """
            import example.uppercase.uppercase

            pipeline {
                stages {
                    stage("External") {
                        uppercase("hello")
                    }
                }
            }
            """.trimIndent(),
        )
        return script
    }

    /**
     * Repack the plugin with [transform] applied to its manifest, or removed when it is null.
     *
     * The real JAR is the SUBJECT, so the mutant is a real artifact too: the plugin classes stay
     * inside the copy. A harness that synthesised a stand-in would be proving its own stub.
     */
    private fun repack(dir: Path, transform: (String) -> String?): Path {
        val target = dir.resolve("mutant-${System.nanoTime()}.jar")
        JarFile(pluginJar.toFile()).use { source ->
            java.util.zip.ZipOutputStream(Files.newOutputStream(target)).use { out ->
                for (entry in source.entries().toList()) {
                    val name = entry.name
                    if (entry.isDirectory) continue
                    val isManifest = name == MANIFEST_RESOURCE
                    val bytes = if (isManifest) {
                        transform(source.getInputStream(entry).readBytes().toString(Charsets.UTF_8))
                            ?.toByteArray(Charsets.UTF_8)
                    } else {
                        source.getInputStream(entry).readBytes()
                    }
                    if (isManifest && bytes == null) continue
                    out.putNextEntry(java.util.zip.ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        return target
    }

    private fun runPlugin(dir: Path, jar: Path): CliResult {
        val script = writeScript(dir)
        return run("run", "--db", dir.resolve("db.sqlite").toString(), "--plugin-jar", jar.toString(), script.toString())
    }

    @Test
    @DisplayName("KNOWN GAP: an artifact with NO manifest is executed by the installed product")
    fun anArtifactWithoutAManifestIsStillExecuted(@TempDir tempDir: Path) {
        val jar = repack(tempDir) { null }
        val result = runPlugin(tempDir, jar)

        assertTrue(
            result.exitCode == 0 && result.stderr.contains("Pipeline finished with SUCCESS"),
            "The installed product USED to admit an artifact with no manifest and no longer does. " +
                "PluginAdmissionGate refuses exactly this artifact, and " +
                "PluginAdmissionPreLoadOrderingTest.artifactWithoutManifestIsRefused pins that refusal — " +
                "but no shipped command calls the gate. Change this row to assert a refusal once the " +
                "product admits before composing. Observed: exit=${result.exitCode}, " +
                "stderr tail=${result.stderr.takeLast(300)}",
        )
    }

    @Test
    @DisplayName("KNOWN GAP: a manifest declaring a Step the plugin does not implement is executed")
    fun aManifestThatLiesAboutItsStepsIsStillExecuted(@TempDir tempDir: Path) {
        val jar = repack(tempDir) { text ->
            val stepsStart = text.indexOf("\"steps\"")
            val stepsEnd = text.indexOf("]", stepsStart)
            require(stepsStart > 0 && stepsEnd > stepsStart) {
                "the plugin manifest no longer has the steps array this mutant assumes: $text"
            }
            // Splice a ghost Step into the array without disturbing anything else, so the ONLY thing
            // wrong with this artifact is the claim it makes about itself.
            text.substring(0, stepsEnd) +
                ", {\"stepKey\": \"example.uppercase.ghost\", \"declaredCapabilities\": []}" +
                text.substring(stepsEnd)
        }
        val result = runPlugin(tempDir, jar)

        assertTrue(
            result.exitCode == 0 && result.stderr.contains("Pipeline finished with SUCCESS"),
            "The installed product USED to execute a plugin whose manifest declares a Step it does " +
                "not implement and no longer does. That claim is what admitContributions exists to " +
                "catch, and it is the one link of the chain a manifest read alone cannot reach. " +
                "Change this row to assert a refusal naming the ghost Step once the product crosses " +
                "the gate. Observed: exit=${result.exitCode}, stderr tail=${result.stderr.takeLast(300)}",
        )
    }

    private companion object {
        const val MANIFEST_RESOURCE = "META-INF/pipelinek/plugin-manifest.json"

        /** The real plugin JAR produced by `:buildExamplePlugin` from THIS revision's SDK. */
        fun locatePluginJar(): Path {
            val dir = Path.of("..", "..", "examples", "example-uppercase-plugin", "build", "libs")
            if (!Files.isDirectory(dir)) {
                throw IllegalStateException(
                    "no examples plugin build directory at $dir; run :buildExamplePlugin first. " +
                        "Skipping here would be a green that proves nothing.",
                )
            }
            val candidates = Files.list(dir).use { stream ->
                stream.filter { candidate ->
                    val name = candidate.fileName.toString()
                    name.startsWith("example-uppercase-plugin-") &&
                        name.endsWith(".jar") &&
                        // Mutants written by an earlier row land in this same directory. Admitting
                        // one would make one row's fixture the next row's SUBJECT, which is how a
                        // test starts passing for the wrong reason.
                        !name.startsWith("mutant-")
                }.toList()
            }
            val jar = candidates.maxByOrNull { Files.getLastModifiedTime(it) }
            return requireNotNull(jar) {
                "no built example-uppercase-plugin JAR under $dir. Skipping here would be a green " +
                    "that proves nothing."
            }
        }
    }
}
