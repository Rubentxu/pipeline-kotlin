package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * F-ARCH-E4B4-001: the waitUntil terminal is a typed value, and its wire token is a
 * projection that nothing may read back as an authority.
 *
 * ## What this holds, and why a plain unit test could not
 *
 * `WaitUntilCompletion` in `pipeline-domain` makes the terminal a closed ADT and derives three
 * things from it: the durable `OperationStatus`, the typed `StepOutcome`, and the historical
 * `WaitUntilCompleted.outcome` token. A unit test proves those three agree *for the three cases
 * it constructs*. It cannot see the fourth caller.
 *
 * That is the whole failure mode this law exists for. The wire token travelled as a `String`, so
 * the moment any code wrote `outcome = "completed"` by hand, the agreement stopped being a property
 * of the type and became a coincidence of five call sites — and a **reader** that string-matched
 * `outcome` had quietly become the authority on how a run ended. Neither is visible from inside
 * `pipeline-domain`.
 *
 * So this scans production source and holds two clauses that fail in opposite directions:
 *
 * 1. **The durable engine may not hand-write a token.** `WaitUntilEngine.kt` must contain none of
 *    the three literals; every terminal projects from its `WaitUntilCompletion`. This is the
 *    regression that would undo E4b.4, and it fails closed.
 * 2. **No production code may read a token to decide something.** A line that names `outcome` and
 *    a token is a *read* unless it is an assignment, and a read is the shape of an authority. An
 *    assignment is an *emission* and is counted separately in the baseline below rather than
 *    forbidden, because three emission sites outside the durable engine are real and unfinished —
 *    see the pinned baseline.
 *
 * ## The three remaining emissions are pinned, not excused
 *
 * `CoreWaitUntilStep.kt` and `BodyExecutionEngine.kt` still construct `WaitUntilCompleted` with a
 * literal. They are the non-durable waitUntil path, and §5.3 of
 * `P3E_EVENT_SEMANTIC_STRING_INVENTORY.md` already flags `CoreWaitUntilStep` as a non-routed
 * candidate. Asserting the count rather than asserting an empty set is the honest form here: the
 * claim "the projection did not reintroduce a String" is true for the durable engine and **false**
 * for the tree, and a test that only asserted the true half would be the defect it is checking for.
 * When those sites are migrated, this count changes and whoever changes it has to say why.
 *
 * ## Why `CoreWaitUntilStep` is allowlisted for *reading*, and why that is not a pardon
 *
 * This law was written after the refactor and it **failed on the first run** against
 * `CoreWaitUntilStep.kt:69`:
 *
 * ```kotlin
 * override val outcome: StepOutcome
 *     get() = if (resultOutcome == "completed") StepOutcome.Success else …
 * ```
 *
 * which is the defect verbatim: a `StepOutcome` reconstructed by matching the wire token. The fix
 * was *not* to rewrite that line, because the honest fix is not reachable inside E4b.4:
 * `WaitUntilOutput.resultOutcome` is a **published scripting surface** — three contract tests
 * construct it by named argument and its `StepCodec` round-trips it through a JSON `outcome` field
 * — so replacing it with a `WaitUntilCompletion` is a step-contract change with its own gate, and
 * deriving the outcome from a token parsed back out of JSON would re-create the parse this whole
 * ADT exists to remove.
 *
 * So the read is allowlisted by **file and line**, with the reason in the list, and the object is
 * a stub that structural routing does not select (§5.3). A package-level exemption is the wrong
 * shape and would re-admit every future line in the file; one file, one line, one stated reason is
 * the shape that can be re-read. Migrating it is tracked, not forgotten.
 *
 * ## Known bound of the reader
 *
 * Clause 3 matches **per line, plus the two lines above it**. A `when` whose subject is on one line
 * and whose arms are further down would be caught only if the token sits within that window. That
 * is a real limit and it is stated here rather than papered over: the clause is a strong net, not a
 * proof. The typed ADT and the compiler are what actually make the authority impossible; this law
 * is what makes the regression loud.
 *
 * RED: AssertionError naming the file and line that reads or re-hand-writes a token.
 * GREEN: The durable engine projects all five terminals and nothing else reads a token back.
 */
/**
 * One site where a wire token is still read back, named so the exemption is visible
 * in the diff rather than implied by the absence of a failure.
 */
private data class AllowedRead(val relativePath: String, val line: Int, val reason: String)

private val ALLOWED_READS: List<AllowedRead> = listOf(
    AllowedRead(
        relativePath =
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWaitUntilStep.kt",
        line = 70,
        reason = "WaitUntilOutput.outcome derives Success/Failure by matching " +
            "resultOutcome == \"completed\". This is a published scripting surface: three " +
            "contract tests construct WaitUntilOutput by named argument and its StepCodec " +
            "round-trips the value through a JSON `outcome` field, so replacing it with a " +
            "WaitUntilCompletion is a step-contract change with its own gate, not part of " +
            "E4b.4. The handler is a non-routed registry candidate (§5.3). Migrating it is " +
            "tracked; this entry exists so that ANY second read in this file fails the law. " +
            "S6/F moved the line from 69 to 70 by adding one import for StepRegistryBuilder; the " +
            "defect is unchanged and stays assigned to S7, so the entry moved with the line rather " +
            "than being widened to a file-level exemption.",
    ),
)

class FArchE4b4WaitUntilTerminalAuthorityTest {

    /** The three historical tokens. S8 freezes event schemas against exactly these. */
    private val wireTokens = listOf("completed", "deadline-exceeded", "aborted")

    /** `\boutcome\b` — deliberately not matching `wireOutcome` or `emitCompleted`. */
    private val outcomeWord = Regex("""\boutcome\b""")

    /** The only file allowed to *decide* a terminal. */
    private val durableEngine =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/waituntil/WaitUntilEngine.kt"

    private fun productionSources(): List<Path> =
        ScannerSupport.walkKotlinFiles(ScannerSupport.v2Root())
            .filter { it.toString().contains("${File.separator}src${File.separator}main${File.separator}") }

    /** Strip prose so a KDoc sentence about the token is not a use of it. */
    private fun codeOf(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return ""
        return line.substringBefore("//")
    }

    private val emission = Regex("""^\s*outcome\s*=""")

    /** `outcome = "completed"` — an emission, not a read. */
    private fun isEmission(code: String): Boolean = emission.containsMatchIn(code)

    private fun tokensIn(code: String) = wireTokens.filter { code.contains("\"$it\"") }

    @Test
    fun `the durable engine projects every terminal and hand-writes none of them`() {
        val engine = ScannerSupport.v2Root().resolve(durableEngine)
        assertTrue(Files.isRegularFile(engine), "NON-VACUITY: $durableEngine was not found, so 'no literals' would be vacuously true.")

        val offending = Files.readAllLines(engine)
            .mapIndexed { index, raw -> index + 1 to codeOf(raw) }
            .filter { (_, code) -> tokensIn(code).isNotEmpty() }

        assertEquals(
            emptyList<String>(),
            offending.map { (line, code) -> "line $line: ${code.trim()}" },
            "The durable waitUntil engine hand-writes a wire token. Every terminal must build a " +
                "WaitUntilCompletion and project it, so that the status, the StepOutcome and the " +
                "token cannot drift apart.",
        )
    }

    @Test
    fun `the durable engine emits through the projection at all five terminals`() {
        val engine = ScannerSupport.v2Root().resolve(durableEngine)
        val projected = Files.readAllLines(engine).count { it.contains("completion.wireOutcome") }

        assertEquals(
            5,
            projected,
            "NON-VACUITY / REGRESSION: expected five `completion.wireOutcome` emissions — two " +
                "reached by the fresh loop and three by the reconciler — and found $projected. A " +
                "smaller number means a terminal stopped projecting, and the token there would be " +
                "decided by whatever the call site wrote.",
        )
    }

    @Test
    fun `no production code reads a wire token as an authority`() {
        val reads = mutableListOf<String>()
        productionSources().forEach { file ->
            val relative = ScannerSupport.v2Root().relativize(file).toString().replace('\\', '/')
            val lines = Files.readAllLines(file)
            lines.forEachIndexed { index, raw ->
                val code = codeOf(raw)
                if (tokensIn(code).isEmpty() || isEmission(code)) return@forEachIndexed
                // The subject of a `when` may sit above the arm that names the token.
                val window = (maxOf(0, index - 2)..index).joinToString("\n") { codeOf(lines[it]) }
                if (!outcomeWord.containsMatchIn(window)) return@forEachIndexed

                val site = relative to (index + 1)
                val allowed = ALLOWED_READS.firstOrNull { it.relativePath == site.first && it.line == site.second }
                if (allowed != null) return@forEachIndexed

                reads += "$relative:${index + 1}: ${code.trim()}"
            }
        }

        assertEquals(
            emptyList<String>(),
            reads,
            "Production code reads the waitUntil wire token. The token is a projection of " +
                "WaitUntilCompletion and is frozen by S8; anything that branches on it has " +
                "become an authority over the terminal, which is the authority the ADT removed. " +
                "One read is allowlisted by file and line (${ALLOWED_READS.size} entr" +
                "${if (ALLOWED_READS.size == 1) "y" else "ies"}); anything beyond it is a " +
                "second authority, not an extension of the first.",
        )
    }

    @Test
    fun `the emissions outside the durable engine are pinned at three`() {
        val emissions = mutableListOf<String>()
        productionSources().forEach { file ->
            Files.readAllLines(file).forEachIndexed { index, raw ->
                val code = codeOf(raw)
                if (tokensIn(code).isEmpty() || !isEmission(code)) return@forEachIndexed
                emissions += "${ScannerSupport.v2Root().relativize(file)}:${index + 1}"
            }
        }

        assertEquals(
            3,
            emissions.size,
            "Three production sites outside the durable engine still construct WaitUntilCompleted " +
                "with a literal token: $emissions. That count is the unfinished half of E4b.4 " +
                "(the non-durable path, §5.3) and it is pinned so it cannot grow unnoticed. If it " +
                "dropped, the sites were migrated and this baseline needs rewriting; if it rose, a " +
                "new terminal was decided by a literal instead of by the ADT.",
        )
    }
}
