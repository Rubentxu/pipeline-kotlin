package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ1 v2 (typed gate, 2026-10-17): the audit's "silent null on
 * `as?`" defect class. The previous `Gate.run` shape was
 * ```
 *   if (plan !is ValidationPlan.Selected) return plan as? ValidationPlan.Refused
 * ```
 * — an `as?` that silently returns `null` (= "no refusal" = "passed")
 * if the cast fails. The sealed interface has two cases today, so
 * the cast cannot fail, but the `as?` explicitly hides a future
 * third case from the compiler: a new [ValidationPlan] variant
 * would silently route the gate to "no refusal" — the false green
 * the gate exists to prevent.
 *
 * The fix is exhaustive `when` over the closed set: the compiler
 * forces the developer to handle every case, including future
 * ones. This fitness test pins the absence of the previous shape
 * in the gate's source tree.
 *
 * Mutation verification (HARNESS FIDELITY LAW §5): injecting
 * `as? ValidationPlan.Refused` anywhere in the plan package
 * source fires this test with a precise source-line citation;
 * reverting the mutation restores green.
 */
class GateAsCastFitnessTest {

    private val packageRoots: List<Path> by lazy {
        val cwd = Path.of("").toAbsolutePath()
        // Scope to MAIN source only. Test files may legitimately
        // use `as?` for setup (e.g. an explicitly-unknown plan
        // fixture), and a permissive regex would fire on those.
        // The fitness scans the plan/ package AND the cycle/
        // package, both of which share the same defect class
        // (silent null on a sealed-type cast). A future
        // retirement/ or catalog/ site with the same pattern
        // can be added by extending this list.
        val harnessMain = cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness")
        listOf(
            harnessMain.resolve("plan"),
            harnessMain.resolve("cycle"),
        )
    }

    private fun kotlinSources(): List<Path> = packageRoots.flatMap { root ->
        if (!Files.isDirectory(root)) {
            error("harness package source not found at $root")
        }
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `plan and cycle packages do not silently cast to ValidationPlan via as`() {
        val sources = kotlinSources()
        val offenders = mutableListOf<String>()
        // The pattern the audit named: any `as? ValidationPlan.X`
        // on a sealed hierarchy in a decision path. The previous
        // `Gate.run` shape had this exact literal; the new shape
        // is an exhaustive `when` over the closed set.
        // We scan for the `as?` followed by `ValidationPlan` to
        // catch both `as? ValidationPlan.Selected` and
        // `as? ValidationPlan.Refused`. A future third case
        // (`as? ValidationPlan.Pending`) would also be caught
        // without updating the regex — the prefix is the
        // discriminant.
        val pattern = Regex("""as\?\s+ValidationPlan\.[A-Z][A-Za-z0-9]*""")
        for (path in sources) {
            val text = path.readText()
            for (match in pattern.findAll(text)) {
                offenders += "${path.fileName}:${match.range.first}: ${match.value}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ1 v2 fitness: the plan and cycle packages MUST NOT silently cast to " +
                "ValidationPlan via `as?` — that is the dual 'silent null on cast' defect the " +
                "audit named. The previous Gate.run shape used `as? ValidationPlan.Refused` and " +
                "silently returned null (= passed) if the cast ever failed. The new shape is an " +
                "exhaustive `when` over the sealed interface. Offending sites: $offenders",
        )
    }
}
