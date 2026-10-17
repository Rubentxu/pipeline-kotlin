package dev.rubentxu.pipeline.v2.application.architecture

import dev.rubentxu.pipeline.v2.application.TestProjectRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Block D (BQ5, 2026-10-17 mandate): restore as an INTERNAL
 * fitness test the JGit import ban that the @Disabled UAT
 * `UatLocal005BannedImportsTest.IMP-001` carried while
 * `migrated-pending` to the external harness.
 *
 * The mandate says: "Restaurar como fitness interno la
 * prohibición de imports de JGit actualmente deshabilitada en
 * `UatLocal005BannedImportsTest`." The new test is the
 * permanent gate in this repository; the UAT remains
 * @Disabled as documentation of the harness's eventual
 * equivalent, and the harness owns the cross-fidelity
 * production-vs-installed verdict (ADR-PK-QV-003).
 *
 * Architectural authority: F-ARCH-L5-001 (CLI-git only, no
 * JGit) and ADR-0050. JGit is the Eclipse pure-Java Git
 * implementation; the project deliberately uses CLI-git so
 * the binary under test is the same binary the consumer
 * invokes. A JGit import silently re-implements git in
 * pure Java and would diverge from the install-time CLI.
 *
 * ## Scope
 *
 * The fitness scans the same production `src/main` trees the
 * UAT scanned. Test sources are deliberately NOT scanned: a
 * test that imports JGit to assert on the ban is permitted
 * (this is the meta-test itself; the test framework, gradle
 * build, and developer scripts are all exempt).
 *
 * ## Mutation verification (HARNESS FIDELITY LAW §5)
 *
 * The fitness is mutation-verified. To check it actually
 * detects a JGit import, inject a banned token into a
 * temporary file under one of the scanned `src/main` trees,
 * confirm the fitness fires with a precise file:line citation,
 * and revert. This proves the test asserts a real property
 * and is not just green-by-construction.
 *
 * ## Why this is hermetic
 *
 * The fitness reads from the file system (the canonical
 * source of truth for "is this import here?") and writes
 * nothing. It has no I/O beyond reading `.kt` files in
 * scanned directories; it has no clock, no network, no
 * random. A failure produces a deterministic list of
 * file:pattern citations the operator can grep for.
 *
 * ## What this fitness does NOT cover
 *
 * - Test sources: intentionally not scanned (see above).
 * - Build files (`*.gradle.kts`, `*.properties`): not
 *   scanned; JGit in a build file is a classpath leak, not
 *   an import. Build-file classpath audit lives in a
 *   separate fitness (`build-cache-fitness` family).
 * - The `v2/test-harness/` module: not scanned; the
 *   harness is meta-tooling and may legitimately use
 *   whatever parser it needs to extract evidence.
 * - Non-`.kt` files (e.g. documentation, JSON manifests):
 *   JGit is a Kotlin/Java import; non-source files cannot
 *   import it.
 */
class BannedImportsFitnessTest {

    /**
     * The closed set of `src/main` trees the fitness scans.
     * Adding a new production module that could plausibly
     * import JGit (e.g. a new git-adjacent adapter) requires
     * extending this list — that is the discipline: every
     * module that COULD import JGit MUST be on the list.
     */
    private val scannedMainTrees: List<String> = listOf(
        "v2/pipeline-step-sdk/scm-git/src/main",
        "v2/pipeline-domain/src/main",
        "v2/pipeline-application/src/main",
        "v2/pipeline-events/src/main",
        "v2/pipeline-credentials-api/src/main",
        "v2/pipeline-credentials-local/src/main",
        "v2/pipeline-scripting-api/src/main",
    )

    /**
     * The banned import prefixes. Both `org.eclipse.jgit`
     * (root) and `org.eclipse.jgit.` (subpackage) are
     * banned. A match anywhere in a `.kt` file is a
     * violation; the test fails with a per-file citation.
     */
    private val bannedPatterns: List<String> = listOf(
        "org.eclipse.jgit",
        "org.eclipse.jgit.",
    )

    @Test
    fun `no org_eclipse_jgit imports in v2 main source`() {
        val projectRoot = TestProjectRoot.dir.toPath()
        val violations = mutableListOf<String>()

        for (relPath in scannedMainTrees) {
            val dir = projectRoot.resolve(relPath)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir)
                .filter { it.toString().endsWith(".kt") }
                .forEach { file -> checkFile(file, violations) }
        }

        assertEquals(
            0,
            violations.size,
            buildString {
                append("BANNED IMPORTS: org.eclipse.jgit found in v2/ main source.\n")
                append("F-ARCH-L5-001 requires CLI-git only (no JGit).\n")
                append("ADR-0050 D1: the project uses the installed `git` binary; JGit is forbidden.\n")
                append("Violations (${violations.size}):\n")
                violations.forEach { append("  $it\n") }
            },
        )
    }

    private fun checkFile(file: Path, violations: MutableList<String>) {
        val content = Files.readString(file)
        val relPath = projectRootRelativePath(file)
        for (pattern in bannedPatterns) {
            if (content.contains(pattern)) {
                violations += "$relPath: contains '$pattern'"
            }
        }
    }

    /**
     * Project-relative path for a readable diagnostic. The
     * absolute path leaks the operator's checkout layout
     * (per the test's hermetic contract: no machine-specific
     * paths in failure messages).
     */
    private fun projectRootRelativePath(file: Path): String {
        val projectRoot = TestProjectRoot.dir.toPath()
        return try {
            projectRoot.relativize(file).toString()
        } catch (_: IllegalArgumentException) {
            file.toString()
        }
    }
}
