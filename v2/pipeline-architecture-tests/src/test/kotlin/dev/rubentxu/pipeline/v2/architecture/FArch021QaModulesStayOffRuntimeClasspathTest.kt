package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * FArch-021: a QA-only module must never be reachable from a module's RUNTIME classpath.
 *
 * ## Why this is not already covered
 *
 * [FArch020EventHarnessIsolationTest] extracts every `project("...")` from a build
 * file and checks *which module* it names. It never asks *which configuration* named
 * it — and the configuration is the whole question. `testImplementation(project(":x"))`
 * and `implementation(project(":x"))` are indistinguishable to a dependency-set check;
 * only one of them ships.
 *
 * That gap is not hypothetical. `:pipeline-application` declared
 * `implementation(project(":test-harness"))`, so 6 QA class files were compiled into
 * `pipeline-application-0.62.0.jar`, and the transitive `runtimeElements` edge exposed
 * them to `:pipeline-testkit` as well. The class that used them was referenced only by
 * tests, so nothing failed and nothing shipped a QA harness at runtime *by accident* —
 * it shipped by declaration. ADR-PK-DF-002 H0/R1 closed it (commit `5e816d84`); this
 * fitness is the guarantee, so the same edge cannot silently reopen.
 *
 * ## Why `:pipeline-event-harness` is NOT in the QA set
 *
 * `dev.rubentxu.pipeline.v2.harness` is a package prefix shared by three modules, and
 * `pipeline events verify` is a public shipped feature backed by `:pipeline-event-harness`.
 * Naming it "harness" is not evidence that it is a QA module — the ADR says so in as many
 * words ("El nombre 'harness' no es criterio de baja"). This fitness therefore asserts a
 * named set of QA modules, never a name pattern, and adding an entry is a deliberate act
 * that has to name the ADR that cut it.
 *
 * ## Shape
 *
 * Pure, hermetic and deterministic: it reads build files as text (comments stripped, so a
 * prose mention of a QA module cannot trip it) and asserts a set difference. It cannot
 * execute, so it cannot hang and needs no timeout.
 */
class FArch021QaModulesStayOffRuntimeClasspathTest {

    /**
     * Modules that exist to verify, not to be consumed by a shipped product.
     * Extend ONLY when an ADR names a new QA module and states that it is cut.
     */
    private val qaModules = setOf(":test-harness")

    /** Configurations whose contents reach the runtime classpath, and therefore the JAR. */
    private val runtimeConfigurations = setOf("api", "implementation", "runtimeOnly")

    private fun buildFiles(): List<Path> =
        ScannerSupport.v2Root()
            .walk()
            .filter { it.toString().endsWith("build.gradle.kts") }
            .toList()

    /** Strips `//` comments; a block mentioning a QA module is prose, not a declaration. */
    private fun declarations(text: String): List<Pair<String, String>> =
        Regex("""(\w+)\s*\(\s*project\(\s*"([^"]+)"\s*\)""")
            .findAll(text.replace(Regex("""//[^\n]*"""), " "))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    @Test
    fun `no QA module is declared on a runtime configuration`() {
        val builds = buildFiles()
        // Fail-closed on an empty scan: finding no build file must not read as
        // "therefore nothing is on a runtime classpath".
        assertTrue(builds.isNotEmpty(), "no build.gradle.kts found under ${ScannerSupport.v2Root()}")

        val violations = builds.flatMap { file ->
            declarations(file.readText())
                .filter { (configuration, project) ->
                    project in qaModules && configuration in runtimeConfigurations
                }
                .map { (configuration, project) ->
                    "${file.fileName} declares $configuration($project): a QA module on a " +
                        "runtime classpath reaches the shipped JAR"
                }
        }
        assertTrue(
            violations.isEmpty(),
            "QA modules must not appear on api/implementation/runtimeOnly: $violations",
        )
    }

    @Test
    fun `the runtime-configuration scan is not vacuous`() {
        // A regex that silently stops matching would make the fitness GREEN over a
        // reintroduced leak, which is the failure this whole mechanism exists to prevent.
        // `FArchL5NoJgitTest` and `BannedImportsFitnessTest` were both fixed for the same
        // class of defect, so the check pins that the parser still sees the declarations
        // it is supposed to see.
        val all = buildFiles().flatMap { declarations(it.readText()) }
        assertTrue(all.isNotEmpty(), "the project() declaration parser matched nothing at all")
        assertTrue(
            all.any { it.first in runtimeConfigurations },
            "the parser found no runtime-configuration project dependency in the whole build, " +
                "so the runtime axis is untested rather than satisfied",
        )
        // And the QA axis must still be reachable, or the filter is dead code.
        assertTrue(
            all.any { it.second in qaModules },
            "the parser found no $qaModules dependency at all, so the QA filter would " +
                "pass vacuously if a runtime declaration were added later",
        )
    }
}
