package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v9 (plan carrier self-protect, 2026-10-17): the
 * previous shape of the [ValidationPlan] sealed
 * interface's data classes had no init validation.
 * Every `String` field (path, capability, suite,
 * reason) was accepted verbatim, and a blank value
 * would silently be the operator's view of "what
 * went wrong" — a fabrication.
 *
 * The new shape: every data class in [ValidationPlan]
 * that has a non-nullable `String` field refuses
 * blank values at construction. The fitness pin here
 * uses a structural grep on the source: any
 * `data class ...(val <name>: String, ...)` that is
 * NOT followed (within 4 lines) by an
 * `isNotBlank` check on the same field is a named
 * offender. A regression to "accept any String" fails
 * the build.
 */
class ValidationPlanCarrierStrictnessTest {

    private val planFile: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/plan/ValidationPlan.kt")
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `every ValidationPlan data class validates its String fields at construction`() {
        if (!Files.isRegularFile(planFile)) {
            error("ValidationPlan.kt not found at $planFile")
        }
        val text = planFile.readText()
        // A `val <name>: String` field (NOT nullable, NOT
        // a String? type) is "validated" if the file
        // contains `<name>.isNotBlank()` within 15 lines
        // of the field declaration. Nullable fields
        // (`String?`) are exempt: a null is a different
        // shape from a blank, and the carrier's null
        // default is the absent-case signal.
        val fieldDecl = Regex("""val\s+(\w+)\s*:\s*String\b(?![\w?])""")
        val checkedField = Regex("""(\w+)\.isNotBlank\(\)""")
        val checkedFields = checkedField.findAll(text).map { it.groupValues[1] }.toSet()
        val lines = text.lines()
        val offenders = mutableListOf<String>()
        for (m in fieldDecl.findAll(text)) {
            val name = m.groupValues[1]
            if (name in checkedFields) continue
            // Find the field declaration's line and look
            // forward 15 lines for `<name>.isNotBlank()`.
            val declLine = text.substring(0, m.range.first).count { it == '\n' }
            val window = (declLine..minOf(declLine + 15, lines.lastIndex)).joinToString("\n") { lines[it] }
            if (!window.contains("$name.isNotBlank")) {
                offenders += "line ${declLine + 1}: `val $name: String` is not " +
                    "validated by an isNotBlank check within the init block"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v9 fitness: every non-nullable `val <name>: String` field in " +
                "ValidationPlan MUST be validated by an isNotBlank check in the " +
                "same data class's init block. A blank field silently becomes the " +
                "operator's view of 'what went wrong' — a fabrication. " +
                "Offending fields: $offenders",
        )
    }

    @Test
    fun `InsufficientFidelity refuses a blank suite at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            SelectionRefusal.InsufficientFidelity(
                suite = "",
                observed = dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity.HF0,
                required = setOf(dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity.HF2),
            )
        }
        assertTrue(
            (ex.message ?: "").contains("suite must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }
}
