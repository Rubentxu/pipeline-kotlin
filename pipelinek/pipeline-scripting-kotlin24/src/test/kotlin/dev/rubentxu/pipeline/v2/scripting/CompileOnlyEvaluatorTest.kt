package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compiling a `.pipeline.kts` WITHOUT running it.
 *
 * ## The guarantee
 *
 * A `.pipeline.kts` is CODE. The product compiles it and runs its top-level
 * body inside the product's own process, with the product's classpath and the
 * operator's permissions. That is the trust model of the product, and the
 * operator needs a way to ask the narrower question — "does this script
 * compile?" — WITHOUT paying for the wider one, which executes code they may
 * not have read.
 *
 * `compileOnly` is that narrower question, and it is answered by swapping the
 * EVALUATOR and nothing else. The compilation is the same compilation the
 * ordinary path performs: same source, same default imports, same classpath,
 * same compiler options, same cache identity. Only the step that RUNS the
 * author's body is replaced by one that reports "compiled, not evaluated".
 *
 * ## Why the instrument is a marker file
 *
 * The fact under test is a SIDE EFFECT: whether the author's body ran. The
 * cheapest honest witness of "the body ran" is the body itself writing
 * something down, and the cheapest witness of "the body did not run" is that
 * same something NOT being there afterwards.
 *
 * A test that only asserted on the RESULT would not discriminate: both paths
 * return a successful compilation, because a script that writes a file and
 * stops compiles either way. The marker is what makes the two paths
 * distinguishable, and the second row below is the one that proves the
 * instrument works at all — if the ordinary path did not write the marker,
 * the first row would pass for the wrong reason.
 *
 * The cache is DISABLED on purpose. A cache would make the second compilation
 * serve the artifact the first one left behind, and the question here is what
 * each path does with the author's body, not what the store served.
 *
 * Nothing here reads a clock or asserts on a duration.
 */
class CompileOnlyEvaluatorTest {

    @TempDir
    lateinit var work: Path

    /**
     * A script whose top-level body leaves a trace. The trace is the fact:
     * present means the body ran, absent means it did not.
     */
    private fun scriptThatLeavesAMarker(marker: Path): ScriptDefinition = ScriptDefinition.inline(
        "import java.io.File\n" +
            "File(\"" + marker.toAbsolutePath() + "\").writeText(\"the body ran\")\n",
    )

    @Test
    fun `a compile-only compilation compiles the script and does not run its body`() {
        val marker = work.resolve("compile-only-marker.txt")
        val host = Kotlin24ScriptingHost(cache = CompiledScriptCache.Disabled)

        val result = host.compileOnly(scriptThatLeavesAMarker(marker))

        assertTrue(
            result.isSuccess,
            "the script did not compile, so nothing was proved about the body: ${result.diagnostics}",
        )
        assertFalse(
            Files.exists(marker),
            "the compile-only path ran the author's body, which is the one thing it promised not to do",
        )
    }

    /**
     * The counterpart row, and the reason the first row means anything: the
     * SAME script, through the ordinary path, DOES run its body. Without this
     * row, an instrument that never ran any body would pass the first one.
     */
    @Test
    fun `the ordinary path compiles the same script and does run its body`() {
        val marker = work.resolve("ordinary-marker.txt")
        val host = Kotlin24ScriptingHost(cache = CompiledScriptCache.Disabled)

        val result = host.compile(scriptThatLeavesAMarker(marker))

        assertTrue(
            result.isSuccess,
            "the script did not compile, so the marker was never going to appear: ${result.diagnostics}",
        )
        assertTrue(
            Files.exists(marker),
            "the ordinary path did not run the author's body, so the marker proves nothing",
        )
    }

    /**
     * The narrow question is still a real question: a script that does not
     * compile is REFUSED in the compile-only path too. A "compile without
     * running" that said yes to everything would be a validator that validates
     * nothing.
     */
    @Test
    fun `a script that does not compile is refused in the compile-only path`() {
        val host = Kotlin24ScriptingHost(cache = CompiledScriptCache.Disabled)

        val result = host.compileOnly(ScriptDefinition.inline("this is not a kotlin program"))

        assertFalse(
            result.isSuccess,
            "a script that does not compile was accepted by the compile-only path",
        )
    }
}
