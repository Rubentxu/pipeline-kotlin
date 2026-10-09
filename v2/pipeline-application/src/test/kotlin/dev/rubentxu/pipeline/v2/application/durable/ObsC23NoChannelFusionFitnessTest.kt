package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * OBS-C2.3: the canonical transcript path may not fuse the child's two channels.
 *
 * ## What this prevents
 *
 * `DurableShellExecutor` used to call `redirectErrorStream(true)`, which makes the kernel merge
 * stdout and stderr into one descriptor. That fusion is **irreversible and invisible**: once it has
 * happened, no code above it can say which channel a byte came from, so `--channel stderr` has no
 * boundary to recover from and no test written afterwards can observe a difference between the two.
 *
 * `ObsCChannelAndTailCharacterisationTest` measured that fusion for a whole block before it was
 * fixed. This row exists so it cannot come back unnoticed, which is the one thing a behavioural test
 * cannot do on its own: the three laws it asserts would all still pass if someone reintroduced the
 * fusion and a second merged stream to match.
 *
 * ## Why a source scan rather than a behavioural assertion
 *
 * Because the defect is an **absence** — "there is no point where the channels are kept apart". The
 * behavioural rows prove that bytes ARE attributed; they cannot prove there is no place that would
 * quietly undo it, because the undone state is observationally identical to the fixed one from a
 * single reader's point of view. Naming the forbidden call is the only assertion that discriminates
 * those two worlds, so that is what this file does.
 *
 * ## What it does NOT say
 *
 * It does not forbid `redirectErrorStream(true)` everywhere. Test harnesses use it legitimately to
 * reproduce a merged console, and `ProcessDurableTaskRuntime` sets it explicitly to `false`. Only the
 * **production transcript path** is pinned, and the exclusion is stated rather than implied.
 */
class ObsC23NoChannelFusionFitnessTest {

    @Test
    fun `the durable shell substrate never fuses stdout and stderr`() {
        val executor = locate(
            "pipeline-step-sdk/runtime/src/main/kotlin",
            "DurableShellExecutor.kt",
        )

        val offenders = executor.readText()
            .lineSequence()
            .withIndex()
            // Comments legitimately NAME the call they forbid, which is how the reason for this law
            // survives in the file. Only executable code counts.
            .filterNot { (_, line) -> line.trimStart().startsWith("//") || line.trimStart().startsWith("*") }
            .filter { (_, line) -> line.contains("redirectErrorStream(true)") }
            .map { (index, line) -> "line ${index + 1}: ${line.trim()}" }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "the canonical transcript path must not fuse the child's channels, because the fusion " +
                "is invisible above it and makes channel attribution unrecoverable. OBS-C2.3 " +
                "removed the call; reintroducing it silently re-breaks `--channel stderr` while every " +
                "other row in this suite stays green. Found: $offenders",
        )
    }

    @Test
    fun `the canonical transcript path redirects each channel independently`() {
        val executor = locate(
            "pipeline-step-sdk/runtime/src/main/kotlin",
            "DurableShellExecutor.kt",
        )
        val code = executor.readText()

        // The two redirects must both be present and addressed to their own channel. A single
        // `redirectOutput(appendTo(logFile))` plus `redirectError(appendTo(logFile))` is the unpumped
        // fallback, which is honest about NOT separating them — so this row also pins that the
        // pumped path, the canonical one, exists at all.
        assertTrue(
            code.contains("pb.redirectOutput(ProcessBuilder.Redirect.PIPE)") &&
                code.contains("pb.redirectError(ProcessBuilder.Redirect.PIPE)"),
            "the pumped canonical path must hold stdout and stderr in two independent pipes, one per " +
                "channel, so each can be attributed before it is persisted",
        )
    }

    @Test
    fun `the SDK runtime does not depend on the Output Plane`() {
        // The channel rides in the SINK's identity, not in the substrate's vocabulary: the SDK names
        // ProcessOutputChannel, never OutputChannel. If runtime ever imported pipeline-output, the
        // dependency would point outward from the substrate to the store's contracts, which is the
        // reversal the hexagonal rule forbids.
        val runtimeSources = locate("pipeline-step-sdk/runtime/src/main/kotlin", null)

        val offenders = Files.walk(runtimeSources).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .filter { path -> importsOutputPlane(path.readText()) }
                .map { it.fileName.toString() }
                .toList()
        }

        assertTrue(
            offenders.isEmpty(),
            "pipeline-step-sdk:runtime must not name the Output Plane: the shell substrate writes to " +
                "whatever sink the application composed and never learns what durable authority " +
                "receives the bytes. Files whose CODE does: $offenders",
        )
    }

    /**
     * True when executable code in [source] names the Output Plane.
     *
     * Comments are excluded, and that exclusion is not a convenience: this file's own KDoc has to be
     * able to say "the SDK names ProcessOutputChannel, never OutputChannel", and a scan that counted
     * prose would report the law's own explanation as a violation of it. What the substrate may never
     * do is **depend** on the Output Plane, and prose cannot do that.
     */
    private fun importsOutputPlane(source: String): Boolean =
        source.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .any { it.contains("dev.rubentxu.pipeline.v2.output") }

    /**
     * Resolves [fileName] anywhere under [dir], starting from the `v2` root.
     *
     * Walking rather than joining is deliberate: the file lives several package levels below the
     * module root, and a fitness test that hard-codes a path is a fitness test that goes red when
     * the package is reorganised — reporting a law violation for what is a move.
     */
    private fun locate(dir: String, fileName: String?): Path {
        val moduleRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve(dir) }
            .firstOrNull { Files.isDirectory(it) }
            ?: error("could not locate '$dir' from ${System.getProperty("user.dir")}")

        if (fileName == null) return moduleRoot

        return Files.walk(moduleRoot).use { paths ->
            paths.filter { it.toString().endsWith("/$fileName") || it.fileName.toString() == fileName }
                .findFirst()
                .orElseThrow { error("$fileName not found under $moduleRoot") }
        }
    }
}
