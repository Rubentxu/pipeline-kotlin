package dev.rubentxu.pipeline.v2.harness.plan

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dispatch from a plan to Gradle argv, and the fitness that keeps the module attribution honest.
 *
 * ## Harness class
 *
 * `pure` / `model`. [Dispatch.dispatch] returns argv and touches nothing, so every assertion below
 * is on a value a build would receive. The one test that reads the repository ([every bound suite
 * names the module it actually lives in]) walks source files under a fixed root and is hermetic:
 * no network, no clock, no singleton, nothing written.
 */
class DispatchTest {

    private val redactor = "v2/pipeline-credentials-api/src/main/kotlin/dev/rubentxu/pipeline/v2/" +
        "credentials/api/StreamingRedactor.kt"
    private val pump = "v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/" +
        "sdk/runtime/durable/DurableShellExecutor.kt"
    private val appBuild = "v2/pipeline-application/build.gradle.kts"

    private fun selected(paths: List<String>): ValidationPlan.Selected =
        ImpactResolver.resolve(paths) as ValidationPlan.Selected

    @Test
    fun `a single suite becomes one invocation with one filter`() {
        val invocations = Dispatch.dispatch(selected(listOf(redactor)))
        assertEquals(1, invocations.size)
        assertEquals(":pipeline-application", invocations[0].gradleProject)
        assertEquals(listOf("ShTranscriptRedactionReproductionTest"), invocations[0].filters)
        assertEquals(
            listOf(
                ":pipeline-application:test",
                "--tests", "ShTranscriptRedactionReproductionTest",
            ),
            invocations[0].argv(),
        )
    }

    /**
     * The task path must be ONE argument joined by a colon.
     *
     * `listOf(":mod", "test")` looks like the natural spelling and it is wrong: Gradle resolves it
     * as the task path `:mod`, so the build reports "task 'mod' not found in root project" and the
     * message names the PROJECT rather than the missing task. That cost one real gate run and read
     * as a misconfigured module. Pinned here so the argv cannot drift back to the space form, which
     * is green in any test that only inspects a list.
     */
    @Test
    fun `the project and task are one colon-joined argument, never two`() {
        val argv = Dispatch.GradleInvocation(
            gradleProject = ":test-harness",
            task = "test",
            filters = listOf("ContractCatalogueFitnessTest"),
        ).argv()

        assertEquals(
            listOf(":test-harness:test", "--tests", "ContractCatalogueFitnessTest"),
            argv,
        )
        assertEquals(
            1,
            argv.count { it.startsWith(":") },
            "exactly one task-path argument: a second one would be read as another task to run",
        )
    }

    @Test
    fun `suites in different modules become different invocations, not one giant fork`() {
        val invocations = Dispatch.dispatch(selected(listOf(redactor, pump)))
        assertEquals(
            2,
            invocations.size,
            "the redactor suite and the pump suite live in different Gradle projects, so one " +
                "invocation cannot run both; got ${invocations.map { it.gradleProject }}",
        )
        assertEquals(listOf(":pipeline-application", ":pipeline-step-sdk:runtime"),
            invocations.map { it.gradleProject })
    }

    @Test
    fun `several suites in one module share a single invocation`() {
        // The whole point of grouping: one JVM fork per module, not per suite. The redactor and
        // the build file pull in FOUR suites here, not six: CompatibilityCorpusTest belongs to
        // Shell.execution.composition and ContractCatalogueFitnessTest to Build.composition, and
        // neither capability is selected by these two paths. The earlier draft of this test
        // asserted six and was wrong; the dispatch was right.
        val invocations = Dispatch.dispatch(selected(listOf(redactor, appBuild)))
        val app = invocations.single { it.gradleProject == ":pipeline-application" }
        assertEquals(
            listOf(
                "AppBinProvenanceTest",
                "BuildProvenanceIsReportedByTheInstalledBinaryTest",
                "RuntimeBuildIdentityTest",
                "ShTranscriptRedactionReproductionTest",
            ),
            app.filters,
            "every selected suite in this module must appear in ONE run, got ${app.filters}",
        )
    }

    @Test
    fun `a capability that pulls in a shared suite still lands in its owning module`() {
        // Shell.execution.composition shares ShTranscriptRedactionReproductionTest with the
        // redaction capability. Both suites must survive into the one invocation, which is the
        // case a naive single-suite-per-fork dispatch would silently drop.
        val invocations = Dispatch.dispatch(
            selected(
                listOf(
                    redactor,
                    "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/" +
                        "application/durable/ShExecution.kt",
                ),
            ),
        )
        val app = invocations.single { it.gradleProject == ":pipeline-application" }
        assertTrue(
            app.filters.containsAll(
                listOf("CompatibilityCorpusTest", "ShTranscriptRedactionReproductionTest"),
            ),
            "a shared suite must not be dropped when a second capability selects it too, got ${app.filters}",
        )
    }

    @Test
    fun `no suite is repeated within a module invocation`() {
        // Two capabilities can share a suite. If dispatch did not deduplicate, the same --tests
        // would be passed twice and the class would run twice inside one invocation.
        val invocations = Dispatch.dispatch(selected(listOf(redactor, appBuild)))
        invocations.forEach { invocation ->
            val duplicates = invocation.filters.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertTrue(
                duplicates.isEmpty(),
                "these suites would run more than once inside one invocation: $duplicates",
            )
        }
    }

    @Test
    fun `a refused plan dispatches nothing`() {
        val refused = ImpactResolver.resolve(
            listOf("v2/pipeline-application/src/main/kotlin/x/Unknown.kt"),
        )
        assertTrue(refused is ValidationPlan.Refused)
        assertTrue(Dispatch.dispatch(refused).isEmpty())
    }

    @Test
    fun `missingFrom names the suites that did not run`() {
        val plan = selected(listOf(redactor))
        val executed = setOf("SomeOtherTest")
        assertEquals(
            listOf("ShTranscriptRedactionReproductionTest"),
            Dispatch.missingFrom(plan, executed),
        )
    }

    @Test
    fun `dispatchedFilters is exactly the plan's suite set`() {
        val plan = selected(listOf(redactor))
        assertEquals(
            plan.suites.map { it.filter }.toSet(),
            Dispatch.dispatchedFilters(Dispatch.dispatch(plan)),
            "the dispatch must cover the plan and nothing else, or the comparison is meaningless",
        )
    }

    // ---- the fitness that keeps module attribution honest ----

    @Test
    fun `every bound suite names the module it actually lives in`() {
        // Without this, a suite attributed to the wrong module still appears in the plan and still
        // looks covered, while `--tests` on that module finds nothing. That is a suite that never
        // runs and is recorded as if it had.
        //
        // This fitness is written to kill mutation E (the pump suite re-attributed to
        // :pipeline-application). The first version did not, for a reason worth keeping in mind:
        // it resolved the module from `root.resolve("v2")` while Gradle already runs this test with
        // cwd = v2/test-harness, so it searched v2/v2, found nothing, returned null for every suite,
        // and passed because the assertion skipped on null. A fitness that cannot see its own
        // subject is worse than no fitness, because it is reported as green evidence.
        val suites = SuitePolicy.BINDINGS.flatMap { it.suites }.distinctBy { it.filter }
        val resolved = suites.associate { it.filter to findOwningGradleProject(it.filter) }

        val unseen = resolved.filterValues { it == null }.keys
        assertTrue(
            unseen.isEmpty(),
            "these bound suites are not on disk at all, so this fitness cannot check them and " +
                "their declared module is unverifiable: $unseen",
        )

        val wrong = suites.filter { resolved[it.filter] != it.gradleProject }
        assertTrue(
            wrong.isEmpty(),
            "these suites declare a Gradle project they do not live in, so their dispatch would " +
                "run nothing.\ndeclared vs on disk:\n" +
                suites.joinToString("\n") {
                    "  ${it.filter}: says ${it.gradleProject}, on disk ${resolved[it.filter]}" +
                        if (resolved[it.filter] == it.gradleProject) "" else "   <-- MISMATCH"
                },
        )
    }

    /**
     * The Gradle project path of the named class.
     *
     * It walks UP from the class to the nearest directory holding a `build.gradle.kts`, because
     * that is how Gradle names a project. Returns null only when the class is genuinely absent.
     *
     * It matches by DECLARATION, not by file name: a Kotlin file may declare several classes, and
     * `BuildProvenanceIsReportedByTheInstalledBinaryTest` lives inside `RuntimeBuildIdentityTest.kt`.
     * Matching on `$className.kt` would call that suite missing while it is right there.
     */
    private fun findOwningGradleProject(className: String): String? {
        val v2 = v2Root()
        Files.walk(v2).use { paths ->
            val match = paths
                .filter { it.toString().endsWith(".kt") }
                .filter { declares(it, className) }
                .findAny()
            if (!match.isPresent) return null
            var dir: Path? = match.get().parent
            while (dir != null && dir.startsWith(v2)) {
                if (Files.exists(dir.resolve("build.gradle.kts"))) {
                    return ":" + v2.relativize(dir).toString().replace('/', ':')
                }
                dir = dir.parent
            }
            return null
        }
    }

    /** Whether the source file declares the class, or a test method named after it. */
    private fun declares(path: Path, className: String): Boolean {
        val text = try {
            Files.readString(path)
        } catch (e: Exception) {
            return false
        }
        return text.contains("class $className") || text.contains("fun `$className")
    }

    /**
     * The `v2/` directory, located by walking UP from this module to the directory that contains
     * `settings.gradle.kts`.
     *
     * It must NOT be derived from the working directory. Gradle runs `Test` with cwd = the module
     * directory, so `Paths.get("..")` is already `v2/` and the old `root.resolve("v2")` looked for
     * `v2/v2`, which does not exist. That silently returned null for every suite, and because the
     * caller filtered on `actual != null`, the fitness passed without ever looking at anything.
     * That is the whole reason it failed to catch a suite re-attributed to the wrong module.
     */
    private fun v2Root(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath().normalize()
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        throw AssertionError("no settings.gradle.kts above ${Paths.get("").toAbsolutePath()}")
    }
}
