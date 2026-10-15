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

        val offenders = fusionSites(executor)
            .filterNot { (_, block) -> PERMITTED.any { it.marker in block } }
            .map { (line, block) ->
                "line $line: ${block.lineSequence().first { "ProcessBuilder(" in it }.trim()} — fused " +
                    "with no declared reason. Declare it in PERMITTED_FUSIONS with its justification, " +
                    "or remove the call."
            }

        assertTrue(
            offenders.isEmpty(),
            "the canonical transcript path must not fuse the child's channels, because the fusion " +
                "is invisible above it and makes channel attribution unrecoverable. OBS-C2.3 " +
                "removed the call; reintroducing it silently re-breaks `--channel stderr` while every " +
                "other row in this suite stays green.\n" +
                "  ${PERMITTED.size} declared exclusions apply: " +
                PERMITTED.joinToString("; ") { "'${it.marker}' — ${it.why}" } +
                "\n  Found: $offenders",
        )
    }

    /**
     * Every executable `redirectErrorStream(true)`, paired with the `ProcessBuilder` block it belongs to.
     *
     * ## Why the block and not the line
     *
     * The first version of this row matched the LINE, which reported the whole file as fused as soon
     * as any process in it fused — including `mkfifo`, which creates the control dir's rendezvous, and
     * the ingest agent, whose stdout and stderr are deliberately fused into one diagnostic log. Neither
     * is the child's transcript. The row was red for a reason its own KDoc explicitly exempts, and a
     * fitness that cries wolf on legal code trains people to route around the law instead of obeying
     * it.
     *
     * The unit is therefore the whole `ProcessBuilder(...)….start()` expression: the call fuses a
     * specific process, and whether that process is the child is a property of the block, not of the
     * line the flag happens to sit on.
     */
    private fun fusionSites(file: Path): List<Pair<Int, String>> {
        val lines = file.readText().lines()
        val sites = mutableListOf<Pair<Int, String>>()
        var blockStart = -1

        lines.forEachIndexed { index, raw ->
            val line = raw.trimStart()
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
            when {
                "ProcessBuilder(" in line -> blockStart = index
                line.contains("redirectErrorStream(true)") && blockStart >= 0 -> {
                    val end = generateSequence(blockStart) { it + 1 }
                        .take(12)
                        .firstOrNull { lines[it].contains(".start(") } ?: index
                    sites += (index + 1) to lines.subList(blockStart, end + 1).joinToString("\n")
                }
                line.contains(".start(") -> blockStart = -1
            }
        }
        return sites
    }

    /**
     * The declared exclusions, each with the reason it is not the child's transcript.
     *
     * Fail-closed by construction: a NEW fusion without a marker here is an offender, so this list
     * cannot grow silently. It is the exclusion the KDoc promised and the line-level scan never
     * delivered.
     */
    private data class PermittedFusion(val marker: String, val why: String)

    private val PERMITTED = listOf(
        PermittedFusion(
            marker = "\"mkfifo\"",
            why = "creates the control directory's rendezvous FIFOs; its output is mkfifo's own " +
                "diagnostic and never reaches the transcript",
        ),
        PermittedFusion(
            marker = "AGENT_MAIN_CLASS",
            why = "the ingest agent's own log; agent stdout and stderr are fused on purpose so a " +
                "pump failure's stack trace is not lost to a half-written stream",
        ),
    )

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
