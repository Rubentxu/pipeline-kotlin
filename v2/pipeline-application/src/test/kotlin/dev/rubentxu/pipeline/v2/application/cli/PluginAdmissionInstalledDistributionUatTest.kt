package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * BLOCK 2 — the product proof of the admission chain: the gap it found, and the two refusals that
 * closed it.
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
 * ## What this class asserts, and how it got here
 *
 * Both rows below were CHARACTERISATION when they were written: each said what the installed binary
 * did **today** with an artifact the admission chain would refuse, and each failure message said
 * what to change. They then became REFUSAL ASSERTIONS as the two passes were wired, one row at a
 * time.
 *
 * That transition is the point of the class and is recorded rather than rewritten quietly:
 *
 * ```text
 * row 1 (no manifest)     RED — executed an artifact that declared nothing
 *                        -> GREEN — refused with exit 2 before composition   [pass 1]
 * row 2 (lying manifest) RED — admitted a VALID manifest declaring a Step it does not implement
 *                        -> GREEN — refused by the pass-2 cross-check       [pass 2]
 * ```
 *
 * Row 2 is why the class exists in its current form. A valid manifest that lies is the one case a
 * pre-load pass cannot catch, because reading the document proves nothing about the code: both a
 * truthful and a lying manifest satisfy the grammar. Catching it requires comparing the declaration
 * against real contributions, so it is necessarily pass 2 and necessarily after admission of the
 * artifact — and it was measured as EXIT=0 SUCCESS until that comparison was wired.
 *
 * Each row also names the mutant it uses, so a reader can tell WHICH hole it covers:
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

    /**
     * S6-PRE: this was `waitFor()` and only then a stdout read and a stderr read — the exact shape
     * the deadlock investigation found, where a child filling the 64 KiB pipe buffer blocked
     * forever and the class-level `@Timeout` cut the TEST while leaving the `pipelinek` JVM alive.
     * [OwnedSubprocess] drains BOTH pipes from the instant the child starts, gives the child its
     * own deadline, and reaps the process tree on every path.
     */
    private fun run(vararg args: String): CliRun.Completed {
        val result = OwnedSubprocess.run(
            command = listOf(binary.absolutePath) + args,
            timeout = CLI_DEADLINE,
        )
        assertTrue(result is CliRun.Completed) {
            "the installed binary did not finish within ${CLI_DEADLINE.seconds}s on ${args.toList()}; " +
                "pid=${(result as? CliRun.TimedOut)?.diagnostics?.pid}. That is an ENVIRONMENT signal, " +
                "not a verdict about admission."
        }
        return result as CliRun.Completed
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

    private fun runPlugin(dir: Path, jar: Path): CliRun.Completed {
        val script = writeScript(dir)
        return run("run", "--db", dir.resolve("db.sqlite").toString(), "--plugin-jar", jar.toString(), script.toString())
    }

    @Test
    @DisplayName("un artefacto SIN manifest es rechazado antes de componer")
    fun anArtifactWithoutAManifestIsRefusedBeforeComposition(@TempDir tempDir: Path) {
        val jar = repack(tempDir) { null }
        val result = runPlugin(tempDir, jar)

        // S6-COMPOSITION: this row USED to assert the opposite. It characterised the gap BLOCK 2
        // found — the installed product executed an artifact that declared nothing, because
        // PluginAdmissionGate had no production caller. Pass 1 now reads each artifact's manifest by
        // name, with no classloader, before composition starts.
        assertTrue(
            result.exitCode == 2,
            "an artifact carrying no manifest must be REFUSED with exit 2 before any composition " +
                "runs. Observed: exit=${result.exitCode}, stderr tail=${result.stderr.takeLast(300)}",
        )
        assertTrue(
            result.stderr.contains("Plugin admission refused"),
            "and the refusal must say so on stderr, not merely exit non-zero. " +
                "stderr tail=${result.stderr.takeLast(300)}",
        )
        assertFalse(
            result.stderr.contains("Pipeline finished with SUCCESS"),
            "a refused plugin must not also report a finished run. " +
                "stderr tail=${result.stderr.takeLast(300)}",
        )
    }

    @Test
    @DisplayName("un manifest bien formado que miente sobre sus Steps es rechazado en la pasada 2")
    fun aManifestThatLiesAboutItsStepsIsRefusedByTheCrossCheck(@TempDir tempDir: Path) {
        val jar = repack(tempDir) { text ->
            // The anchor must close the LAST step object and the array that follows it. An earlier
            // version of this row took `text.indexOf("]", after "steps")`, which lands on the first
            // step's `declaredCapabilities` array and produced MALFORMED JSON — so the row was green
            // for the wrong reason: the codec refused bad grammar, not the cross-check a lie.
            val anchor = "}],\n  \"directives\""
            // `String.count` takes a (Char) -> Boolean predicate, not a String; the literal
            // occurrences are counted via split. Exactly one occurrence is required because the
            // splice must be unambiguous — two anchors would mean this row is editing a document
            // shape it has not actually looked at.
            require(text.split(anchor).size - 1 == 1) {
                "the plugin manifest no longer has exactly one anchor this mutant assumes: $text"
            }
            val spliced = text.replace(
                anchor,
                "}, {\"stepKey\": \"example.uppercase.ghost\", \"declaredCapabilities\": []}]," +
                    "\n  \"directives\"",
            )
            require(spliced.contains("example.uppercase.ghost")) { "the splice did not land" }

            // THE property this mutant must have, checked by the SAME authority that admits real
            // plugins rather than by a generic JSON parser. If the splice did not produce a
            // well-formed manifest, the row below would be asserting a refusal produced by the
            // grammar check, and the whole point — that a LYING but VALID manifest is caught — would
            // be untested while the row reported itself green.
            require(PluginManifestCodec.decode(spliced) is PluginManifestDecodeResult.Accepted) {
                "the mutant must be a VALID manifest that merely lies; if it does not decode, this " +
                    "row would pass for the wrong reason. Rejected document: $spliced"
            }
            spliced
        }
        val result = runPlugin(tempDir, jar)

        // S6-COMPOSITION pass 2. This row USED to assert the OPPOSITE: it characterised the gap that
        // pass 1 alone cannot close. Pass 1 reads a manifest and cannot tell a truthful document
        // from a lying one — both are valid JSON matching the grammar. Catching the lie requires
        // comparing the declaration against what the contributor ACTUALLY contributes, which needs
        // the contributor instantiated, so it is necessarily pass 2 and necessarily after admission
        // of the artifact.
        assertTrue(
            result.exitCode == 2,
            "a manifest declaring a Step the plugin does not implement must be REFUSED with exit 2 " +
                "by the pass-2 cross-check. Observed: exit=${result.exitCode}, " +
                "stderr tail=${result.stderr.takeLast(400)}",
        )
        assertTrue(
            result.stderr.contains("Plugin admission refused"),
            "and the refusal must say so on stderr, not merely exit non-zero. " +
                "stderr tail=${result.stderr.takeLast(400)}",
        )
        assertTrue(
            result.stderr.contains("example.uppercase.ghost"),
            "the refusal must NAME the step that was declared without being implemented; a bare " +
                "exit code proves only that something failed. " +
                "stderr tail=${result.stderr.takeLast(400)}",
        )
        assertFalse(
            result.stderr.contains("Pipeline finished with SUCCESS"),
            "a refused plugin must not also report a finished run. " +
                "stderr tail=${result.stderr.takeLast(400)}",
        )
    }

    private companion object {
        const val MANIFEST_RESOURCE = "META-INF/pipelinek/plugin-manifest.json"

        /**
         * The subprocess's own contract. Composition of five bundled plugins plus one mutant, from
         * a cold JVM, is slow but bounded; the class-level `@Timeout` stays as the outer watchdog
         * for "the whole test is broken" and is deliberately NOT what bounds a normal run.
         */
        val CLI_DEADLINE: Duration = Duration.ofMinutes(5)

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
