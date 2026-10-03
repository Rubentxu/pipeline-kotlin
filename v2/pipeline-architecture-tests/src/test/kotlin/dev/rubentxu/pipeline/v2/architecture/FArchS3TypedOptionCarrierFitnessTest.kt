package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S3.3 — a stage option is a TYPED case, not a name/value bag.
 *
 * `02-directive-model.md` states the rule this fitness makes mechanical:
 *
 * > Every option must map to a real policy/interpreter or be rejected.
 *
 * A `name: String` / `value: String?` pair cannot carry that rule. Nothing in
 * the type says which options exist, so an option whose name nobody matches is
 * indistinguishable from one that is interpreted — it is admitted, compiled,
 * stored, and then read by nobody. That is the *silent drop* the Semantic
 * Constitution names as a defect class, wearing the costume of a data class.
 *
 * The shape this fitness replaces:
 *
 * ```
 * DSL: OptionsSpec(timeout: Long?)                       // typed
 * compiler: OptionSpec("timeout", it.toString())         // flattened to text
 * interpreter: options.filter { it.name == "timeout" }   // discriminated by name
 *              .single().value?.toLongOrNull()           // re-parsed
 * ```
 *
 * A typo in the filter name is not a compile error and not a test failure. It
 * is an option that compiles, serializes, and does nothing.
 *
 * ## Properties pinned
 *
 *  1. `OptionSpec` no longer exists in any production source. Its absence is
 *     the ratchet: the stringly-typed carrier cannot come back quietly.
 *  2. No production source discriminates an option by a name string.
 *  3. `StageOption` is a sealed interface, so the interpreter's `when` is
 *     forced to be exhaustive by the compiler.
 *  4. Every declared case carries a real interpreter, checked by requiring a
 *     producer and a consumer of the same case name. This is the §8 rule: a
 *     case with no interpreter is a dead semantic parameter, so adding one
 *     without wiring it turns this fitness red.
 *  5. `StageOption.Timeout` rejects a non-positive duration in its own
 *     invariant, so the interpreter needs no positivity check and cannot throw
 *     one.
 *  6. The interpreter's projection contains no `else` branch. An `else` here
 *     would silently absorb the next case added to the ADT.
 */
class FArchS3TypedOptionCarrierFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private val mainRoots: List<Path> = listOf(
        v2Root.resolve("pipeline-domain/src/main/kotlin"),
        v2Root.resolve("pipeline-application/src/main/kotlin"),
        v2Root.resolve("pipeline-scripting-api/src/main/kotlin"),
    )

    /** Production sources, comment-stripped so prose cannot satisfy or trip a property. */
    private val productionSources: List<Pair<Path, String>> = mainRoots.flatMap { root ->
        ScannerSupport.walkKotlinFiles(root).map { it to strip(it) }
    }

    private fun strip(path: Path): String =
        Files.readString(path)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")

    /**
     * The domain file with comments and KDoc removed.
     *
     * Stripping matters here and is not defensive boilerplate: the `StageOption`
     * KDoc documents mutation M-s3-3 by QUOTING `data object Timestamps :
     * StageOption`. An unstripped read makes the case scanner find a case that
     * does not exist, which turns this fitness into a false RED on a correct
     * implementation — the mirror image of the false green it exists to prevent.
     */
    private fun stageOptionSource(): String =
        strip(v2Root.resolve("pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/CompiledPipeline.kt"))

    // ------------------------------------------------------------------
    // 1. The stringly-typed carrier is gone
    // ------------------------------------------------------------------

    @Test
    fun `the stringly-typed OptionSpec no longer exists in production source`() {
        val offenders = productionSources
            .filter { (_, source) ->
                // Declaration or construction, not a historical mention in prose
                // (prose is stripped above) and not the new typed name.
                Regex("""\b(class|data class|object)\s+OptionSpec\b""").containsMatchIn(source) ||
                    Regex("""\bOptionSpec\s*\(""").containsMatchIn(source)
            }
            .map { (path, _) -> path.toString() }

        assertEquals(
            emptyList<String>(),
            offenders,
            "OptionSpec(name, value) is a name/value bag with no type-level statement of which " +
                "options exist, so an option nobody reads is indistinguishable from one that is. " +
                "Use the StageOption ADT and add a case only alongside its interpreter. Found: $offenders",
        )
    }

    // ------------------------------------------------------------------
    // 2. No name-string discrimination
    // ------------------------------------------------------------------

    @Test
    fun `no production source discriminates an option by a name string`() {
        val offenders = productionSources
            .filter { (_, source) ->
                Regex("""\.name\s*==\s*"[^"]*"""").containsMatchIn(source) &&
                    Regex("""\boptions\b|\bOptionSpec\b|\bStageOption\b""").containsMatchIn(source)
            }
            .map { (path, _) -> path.toString() }

        assertEquals(
            emptyList<String>(),
            offenders,
            "an option interpreter that matches on `it.name == \"...\"` is a string switch: a " +
                "typo compiles, serializes and does nothing. Match the closed StageOption ADT " +
                "instead, so the compiler enforces exhaustiveness. Found: $offenders",
        )
    }

    // ------------------------------------------------------------------
    // 3. StageOption is sealed
    // ------------------------------------------------------------------

    @Test
    fun `StageOption is a sealed interface so the match is compiler-enforced`() {
        val source = stageOptionSource()
        assertTrue(
            Regex("""sealed interface StageOption\b""").containsMatchIn(source),
            "StageOption must be sealed: an open interface would let a case be added without an " +
                "interpreter and without the interpreter's `when` failing to compile",
        )
    }

    // ------------------------------------------------------------------
    // 4. Every case has a producer and a consumer (the section 8 rule)
    // ------------------------------------------------------------------

    @Test
    fun `every declared StageOption case has a producer and a consumer`() {
        val declaration = stageOptionSource()
        val cases = Regex("""(?:data object|data class)\s+(\w+)[^\n]*:\s*StageOption""")
            .findAll(declaration)
            .map { it.groupValues[1] }
            .toList()

        assertTrue(
            cases.isNotEmpty(),
            "no StageOption case found in CompiledPipeline.kt — the declaration shape changed and " +
                "this fitness must be updated rather than silently passing on an empty match",
        )

        val allSources = productionSources.joinToString("\n") { it.second }
        for (case in cases) {
            val mentions = Regex("""StageOption\.${Regex.escape(case)}\b""").findAll(allSources).count()
            assertTrue(
                mentions >= 2,
                "StageOption.$case appears $mentions time(s) across production source, so it is " +
                    "either never constructed or never consumed. §8 requires every option to map " +
                    "to a real interpreter: a declared-but-unread case is a dead semantic " +
                    "parameter, and an unconstructed one is unreachable",
            )
        }
    }

    // ------------------------------------------------------------------
    // 5. The invariant lives in the carrier
    // ------------------------------------------------------------------

    @Test
    fun `Timeout rejects a non-positive duration in its own invariant`() {
        val source = stageOptionSource()
        val timeoutBlock = Regex("""data class Timeout\(val milliseconds: Long\)[\s\S]*?\n    \}""")
            .find(source)
            ?.value
        assertTrue(timeoutBlock != null, "could not locate the StageOption.Timeout declaration")
        assertTrue(
            "require(milliseconds > 0)" in timeoutBlock!!,
            "StageOption.Timeout must reject a non-positive duration at construction, so the " +
                "interpreter needs no positivity check and the failure cannot be deferred to run " +
                "time. Found: $timeoutBlock",
        )
    }

    // ------------------------------------------------------------------
    // 6. No else in the interpreter
    // ------------------------------------------------------------------

    @Test
    fun `the option interpreter has no else branch`() {
        val path = v2Root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalStructuralDecisions.kt",
        )
        val source = Files.readString(path)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        val start = source.indexOf("fun StageNode.timeoutProjection(")
        assertTrue(start > 0, "could not locate timeoutProjection in ${path.fileName}")
        val end = source.indexOf("\ninternal fun ", start + 1).let { if (it < 0) source.length else it }
        val body = source.substring(start, end)

        assertTrue(
            "is StageOption.Timeout ->" in body,
            "timeoutProjection must match the typed case: $body",
        )
        assertTrue(
            "else ->" !in body,
            "timeoutProjection must not carry an `else`: it would silently absorb the next " +
                "StageOption case added, which is the closed-world defect the Step registry was " +
                "split to remove. Body: $body",
        )
    }
}
