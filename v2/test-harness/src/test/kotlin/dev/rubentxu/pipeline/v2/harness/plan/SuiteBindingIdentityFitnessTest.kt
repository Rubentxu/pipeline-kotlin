package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every [TestSuiteId.fqcn] in [SuitePolicy.BINDINGS] must name a class that really exists, in the
 * module the binding itself declares.
 *
 * ## Why this is separate from [EvidenceKeyAgreementTest]
 *
 * The two own different properties, and conflating them would leave one unguarded:
 *
 * - **Key agreement** (there): does the producer's key reach the consumer? Answered by running
 *   both against a report, with the binding's own `fqcn`. Nothing there can tell a correct FQCN
 *   from a plausible wrong one, because the report is written FROM the binding.
 * - **Binding identity** (here): is each declared FQCN real? Answered by reading the checkout. If
 *   a suite moves package, the binding keeps the old FQCN and the seam test still passes — the
 *   two sides agree on a name no test class has — while the real report arrives under a different
 *   key and the gate refuses a suite that ran.
 *
 * ## What it deliberately does NOT do
 *
 * It does not scan for binding entries, so a capability that names no suite cannot be caught here.
 * That is the SuitePolicy fitness's direction, and duplicating it would create the second
 * authority the resolver's own design forbids.
 */
class SuiteBindingIdentityFitnessTest {

    private fun repoRoot(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve(".git"))) dir = dir.parent
        check(dir != null) { "could not locate the checkout root from ${Path.of("").toAbsolutePath()}" }
        return dir
    }

    /** `:pipeline-step-sdk:runtime` is `v2/pipeline-step-sdk/runtime`; the same derivation the gate uses. */
    private fun moduleDir(gradleProject: String): String =
        "v2/" + gradleProject.removePrefix(":").replace(':', '/')

    @Test
    @DisplayName("every bound suite names a real class, in the module it claims")
    fun everyBoundFqcnNamesARealClassInItsDeclaredModule() {
        val root = repoRoot()
        val declared = declaredTestClasses(root)

        val broken = SuitePolicy.BINDINGS
            .flatMap { binding -> binding.suites.map { binding.capability to it } }
            .mapNotNull { (capability, suite) ->
                val where = declared[suite.fqcn]
                when {
                    where == null -> "$capability -> '${suite.fqcn}' names no declared test class"
                    !where.startsWith(moduleDir(suite.gradleProject) + "/") ->
                        "$capability -> '${suite.fqcn}' is declared in $where, " +
                            "but the binding claims ${suite.gradleProject}"

                    else -> null
                }
            }

        assertTrue(
            broken.isEmpty(),
            "a binding's fqcn must be the FQCN of a class that exists in the module the binding " +
                "declares. The evidence map is keyed by that FQCN and adjudicate looks it up by " +
                "it, so a stale or mistyped one makes the gate refuse a suite that demonstrably " +
                "ran -- and the key-agreement seam test cannot see it, because it writes its " +
                "report FROM this same binding. Offenders: $broken",
        )
    }

    @Test
    @DisplayName("no binding fell back to the defaulted fqcn")
    fun noBindingUsesTheDefaultFqcn() {
        val defaulted = SuitePolicy.BINDINGS
            .flatMap { it.suites }
            .filter { it.fqcn == it.filter }

        assertTrue(
            defaulted.isEmpty(),
            "TestSuiteId.fqcn defaults to filter, the simple class name, which is NOT the key the " +
                "evidence map uses. A binding that relies on the default reintroduces the wedge " +
                "this property exists to close. Offenders: " +
                defaulted.map { "${it.filter} (${it.gradleProject})" },
        )
    }

    /**
     * `fully.qualified.ClassName` -> the checkout-relative file that declares it, for every test
     * class in the repository.
     *
     * Comments are stripped before the declaration is matched so a class named in prose does not
     * read as a declaration. That cannot mask the subject here: the subject IS the declaration,
     * and the alternative (matching raw text) would accept a mention.
     */
    private fun declaredTestClasses(root: Path): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Files.walk(root.resolve("v2")).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { it.toString().contains("/src/test/") }
                .forEach { file ->
                    val text = stripComments(Files.readString(file))
                    val pkg = Regex("(?m)^\\s*package\\s+([\\w.]+)").find(text)?.groupValues?.get(1)
                        ?: return@forEach
                    DECLARATION.findAll(text).forEach { m ->
                        out.putIfAbsent("$pkg.${m.groupValues[1]}", relative(root, file))
                    }
                }
        }
        return out
    }

    private fun relative(root: Path, file: Path): String =
        root.relativize(file).toString().replace('\\', '/')

    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    private companion object {
        /** Kotlin class declarations with any mix of modifiers and annotations before the name. */
        val DECLARATION = Regex(
            """(?m)^\s*(?:(?:@\w+(?:\([^)]*\))?|public|internal|private|protected|open|abstract|final|sealed|data|expect|actual|enum|annotation|value|inner)\s+)*class\s+(\w+)""",
        )
    }
}