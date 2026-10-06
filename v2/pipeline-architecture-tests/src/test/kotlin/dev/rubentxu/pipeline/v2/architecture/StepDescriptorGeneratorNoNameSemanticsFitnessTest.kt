package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * S6 / A1 — the compiler layer TRANSPORTS declared metadata and never INFERRS semantics
 * from a Step name.
 *
 * ## What was removed, and why this law exists
 *
 * `StepDescriptorGenerator` used to open with a comment explaining that "KSP cannot
 * reliably read enum/array annotation arguments from Kotlin 2.x annotations in this
 * configuration", and then branched on `when (name)` over the literal strings `"echo"`,
 * `"sh"`, `"error"` and `"sleep"` to choose `ExecutionLocation`, `Effect` and
 * `ReplayPolicy`. A companion name-keyed `JenkinsSurfaceMeta` map supplied the Jenkins
 * surface, and a second `when` on the surface prefix supplied location, replay policy and
 * a failure-kind bridge for an LSP metadata file nobody read.
 *
 * Two things were wrong with that, and the second is why this is a law.
 *
 * 1. It is extension by editing a semantic switch. A new Step family needed the processor
 *    edited. The Step Constitution permits a plugin to extend by *addition* and never by
 *    editing semantic switches, and AGENTS.md's compiler/lowering law says KSP "MUST NOT
 *    contain concrete Step or directive semantics, nor a `when(key)` semantic dispatcher".
 *
 * 2. **The stated limitation was false.** The declared values were present all along as
 *    `KSClassDeclarationEnumEntryImpl` and only needed one more case in the reader. The
 *    `when` was not a necessary workaround around a toolchain limit; it was an avoidable
 *    authority handed a plausible excuse.
 *
 * The processor now reads each value from `@Step` / `@JenkinsSurface` and fails the build
 * when it cannot, because a genuinely unreadable declaration is a build error, not a
 * licence to guess from a name.
 *
 * ## Why a source scan, stated honestly
 *
 * The defect IS a source-level shape: a table, a `when`, or an `if (stepKey == ...)` that
 * maps a Step identity to its semantics. There is no runtime observation that separates
 * "the processor read the declaration" from "the processor hardcoded the answer", because
 * for the four pre-existing Steps both paths produce byte-identical descriptors. Only the
 * name-keyed shape is detectable without inventing a new Step family on every run.
 *
 * So this test bans the shape, and the block carries a separate BEHAVIOURAL mutation: a new
 * Step family whose metadata contradicts any name-based guess, added without touching the
 * processor, must produce a correct descriptor. The scan makes the ban permanent; the
 * mutation is what makes it necessary. Neither substitutes for the other.
 *
 * ## Non-vacuity
 *
 * Proven by reintroducing a name-keyed `when` in `StepDescriptorGenerator.kt` and
 * observing this test go red. A source scan never shown to fail is a comment.
 */
@DisplayName("S6/A1 — the compiler layer never infers Step semantics from a name")
class StepDescriptorGeneratorNoNameSemanticsFitnessTest {

    private val v2: Path = FitnessPaths.v2Root()

    private val generatorRelativePath =
        "pipeline-step-sdk/processor/src/main/kotlin/" +
            "dev/rubentxu/pipeline/v2/sdk/processor/StepDescriptorGenerator.kt"

    private fun Path.relativeToV2(): String =
        toString().removePrefix("$v2/").removePrefix("$v2\\")

    private fun kotlinSources(root: Path): List<Path> =
        Files.walk(root).use { stream -> stream.filter { it.toString().endsWith(".kt") }.toList() }

    /** The compiler layer: the KSP processor plus the annotation surface it reads. */
    private fun compilerLayerSources(): List<Path> {
        val roots = listOf(
            "pipeline-step-sdk/processor/src/main/",
            "pipeline-step-sdk/api/src/main/",
        )
        return kotlinSources(v2).filter { path ->
            val relative = path.relativeToV2()
            roots.any { relative.startsWith(it) }
        }
    }

    @Test
    fun `the compiler layer declares no semantic branch over a Step name`() {
        val offenders = mutableListOf<String>()

        for (source in compilerLayerSources()) {
            val code = stripCommentsAndKdoc(source)
            val where = source.relativeToV2()

            // 1. A branch whose discriminator is a Step identity. `when (raw)` over a KSP
            //    value type stays legal — that is transport, not semantics.
            Regex("""\bwhen\s*\(\s*(name|stepKey|stepName|stepId)\b""")
                .findAll(code)
                .forEach { offenders += "$where: branches on a Step identity discriminator" }

            // 2. A table keyed by Step name. The same defect with different punctuation.
            for (literal in listOf("\"echo\"", "\"sh\"", "\"error\"", "\"sleep\"")) {
                if (Regex("""$literal\s*(to|->)""").containsMatchIn(code)) {
                    offenders += "$where: $literal is used as a table key"
                }
            }

            // 3. Prefix comparison against a Jenkins surface string, the third name-shaped
            //    authority that used to exist here.
            if (Regex("""startsWith\(\s*"(echo|sh|error|sleep)\|""").containsMatchIn(code)) {
                offenders += "$where: derives semantics from a Jenkins surface prefix"
            }

            // 4. Equality comparison against a Step name literal.
            if (Regex("""(==|!=)\s*"(echo|sh|error|sleep)\"""").containsMatchIn(code)) {
                offenders += "$where: compares a Step identity literal"
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "The compiler/processor layer must transport declared metadata, never infer it " +
                "from a Step name. A name-keyed table, a `when (name)` or a surface-prefix " +
                "comparison all force a new Step family to edit the processor, which is " +
                "extension by editing a semantic switch. Offending sources:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `an unreadable declaration fails the build instead of substituting a default`() {
        val code = stripCommentsAndKdoc(v2.resolve(generatorRelativePath))

        // The banned shape is a DEFAULT SEMANTIC VALUE standing in for an unreadable
        // declaration. `else -> null` is the opposite and stays legal: null routes to the
        // build failure, which is the honest answer.
        val semanticFallbacks = Regex(
            """(else\s*->\s*)?(ExecutionLocation|ReplayPolicy|Effect)\.[A-Z_]+""",
        ).findAll(code)
            .map { it.value }
            .filter { it.isNotBlank() }
            .toList()

        // A declared semantic constant may appear when TRANSPORTING a read value into the
        // generated source; what must never appear is a default chosen for an unreadable one.
        // The generator emits those constants by name only inside the descriptor template, so
        // the check that matters is structural: no `?:` and no `else ->` may produce one.
        val producesDefault = Regex("""\?\s*:\s*(ExecutionLocation|ReplayPolicy|Effect)\.""")
            .containsMatchIn(code) ||
            Regex("""else\s*->\s*(ExecutionLocation|ReplayPolicy|Effect)\.""").containsMatchIn(code)

        assertTrue(
            !producesDefault,
            "StepDescriptorGenerator must fail the build when a declared @Step value cannot be " +
                "read, not substitute a default. A default over unreadable metadata is the same " +
                "name-based inference wearing a different hat. Semantic constants observed: " +
                "$semanticFallbacks",
        )
    }

    /**
     * Strips comments so a KDoc sentence DISCUSSING the removed `when` is not read as code
     * containing one. A law satisfiable by quoting the thing it forbids is not a law.
     */
    private fun stripCommentsAndKdoc(source: Path): String {
        val text = Files.readString(source)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> {
                    val nl = text.indexOf('\n', i)
                    i = if (nl < 0) text.length else nl
                }

                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                }

                else -> {
                    out.append(text[i]); i++
                }
            }
        }
        return out.toString()
    }
}
