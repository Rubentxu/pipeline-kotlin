package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v10 (plan carrier self-protect, 2026-10-17): the
 * previous shape of the [ChangedInput] sealed
 * interface's data classes had no init validation.
 * A blank `path` would silently never match any
 * catalogue row, and the classifier would return
 * the typed refusal for a real change as
 * [ChangedInput.UnknownProduction] (or worse, a
 * no-op).
 *
 * The new shape: every variant in [ChangedInput] that
 * has a non-nullable `String` field refuses blank
 * values at construction. The fitness pin here uses
 * a structural grep on the source: any `data class`
 * that is NOT followed (within 4 lines) by an
 * `isNotBlank` check on its String field is a named
 * offender. A regression to "accept any String" fails
 * the build.
 */
class ChangedInputCarrierStrictnessTest {

    private val planFile: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/plan/ChangedInput.kt")
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `every ChangedInput data class validates its String fields at construction`() {
        if (!Files.isRegularFile(planFile)) {
            error("ChangedInput.kt not found at $planFile")
        }
        val text = planFile.readText()
        // Find every data class declaration. A data
        // class is "validated" if the next 20 lines
        // (the typical init block, with KDoc-style
        // comment header) contain an isNotBlank() check
        // on at least one of its String fields.
        val dataClassDecl = Regex("""data\s+class\s+\w+\([^)]*\)""")
        val isNotBlank = Regex("""\.isNotBlank\(\)""")
        val lines = text.lines()
        val offenders = mutableListOf<String>()
        for (m in dataClassDecl.findAll(text)) {
            val declLine = text.substring(0, m.range.first).count { it == '\n' }
            val window = (declLine..minOf(declLine + 20, lines.lastIndex)).joinToString("\n") { lines[it] }
            if (!isNotBlank.containsMatchIn(window)) {
                offenders += "line ${declLine + 1}: `data class` has no isNotBlank " +
                    "check on any String field within 20 lines of the declaration"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v10 fitness: every data class in ChangedInput MUST validate " +
                "its String fields with an isNotBlank check. A blank field " +
                "silently becomes the operator's classifier output — a " +
                "fabrication. Offending data classes: $offenders",
        )
    }

    @Test
    fun `SourceCode refuses a blank path at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ChangedInput.SourceCode(path = "")
        }
        assertTrue(
            (ex.message ?: "").contains("SourceCode.path must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `UnknownProduction refuses a blank proposal_capability at construction`() {
        // BQ4 v24 (typed proposal pair, 2026-10-17):
        // the two parallel fields are now a single
        // [Proposal] carrier; the strict-validate moves
        // to the carrier's init. A blank `capability` is
        // refused by the [Proposal] carrier with a
        // message naming the carrier's field.
        val ex = assertThrows<IllegalArgumentException> {
            ChangedInput.UnknownProduction(
                path = "v2/foo/src/main/kotlin/Foo.kt",
                proposal = Proposal(capability = "", owner = ":foo"),
            )
        }
        assertTrue(
            (ex.message ?: "").contains("Proposal.capability must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `UnknownProduction refuses a blank proposal_owner at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ChangedInput.UnknownProduction(
                path = "v2/foo/src/main/kotlin/Foo.kt",
                proposal = Proposal(capability = "Unknown.foo", owner = ""),
            )
        }
        assertTrue(
            (ex.message ?: "").contains("Proposal.owner must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `Proposal refuses a blank capability at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            Proposal(capability = "", owner = ":foo")
        }
        assertTrue(
            (ex.message ?: "").contains("Proposal.capability must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `Proposal refuses a blank owner at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            Proposal(capability = "Unknown.foo", owner = "")
        }
        assertTrue(
            (ex.message ?: "").contains("Proposal.owner must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }
}
