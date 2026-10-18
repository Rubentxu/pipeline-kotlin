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
 * permanent gate in this repository.
 *
 * The UAT row IMP-001 stays @Disabled, but its reason was corrected:
 * it used to promise that the rule "belongs to
 * pipelinek-release-harness" and could only be retired once the
 * harness produced a 5-axis verdict. `RESPONSIBILITY_MIGRATION_ROADMAP.md`
 * classifies this rule **KEEP**, and a rule over source code does not
 * belong to a certifier that consumes a ZIP. The reason now records
 * that it is superseded by this fitness rather than awaiting an
 * external verdict that nothing tracks. The harness's
 * cross-fidelity production-vs-installed verdict (ADR-PK-QV-003) is
 * unaffected: that is a different guarantee and it still belongs
 * to the harness.
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
 * The fitness scans every production `src/main` tree under `v2`,
 * **discovered from the file system**, not listed by hand.
 *
 * It previously carried a hand-maintained list of 7 trees,
 * inherited verbatim from the UAT it replaced. That list was
 * written when those were the modules; 23 more exist now, so a
 * JGit import in `pipeline-credentials-executor`,
 * `pipeline-runtime`, `pipeline-output` or `workflow-control`
 * passed this row green while the row is named
 * `no org_eclipse_jgit imports in v2 main source`. A guard that
 * silently stops covering the tree it names is worse than no
 * guard, because it reads as coverage.
 *
 * Discovery is rooted at `v2` and not at the repository root:
 * `core/` is V1 and quarantined, and a ban that fails on
 * quarantined V1 code would block V2 work for a V1 violation.
 * Rooting at `v2` is also exactly what this row already claimed.
 *
 * The second row pins the discovery to an INDEPENDENT
 * derivation — the modules that declare a `build.gradle.kts`
 * and have a `src/main` — so a discovery bug cannot quietly
 * reduce the scan to nothing and leave the ban green.
 *
 * Test sources are deliberately NOT scanned: a test that imports
 * JGit to assert on the ban is permitted (this is the meta-test
 * itself; the test framework, gradle build, and developer scripts
 * are all exempt).
 *
 * ## Mutation verification (HARNESS FIDELITY LAW §5)
 *
 * Two claims, two mutations, each killed:
 *
 * 1. *The ban covers every production module.* A probe file
 *    carrying the banned token was added under
 *    `pipeline-credentials-executor/src/main` — one of the 23
 *    modules the old 7-entry list missed. The widened ban fired
 *    and cited the file. **The pre-change fitness, run against
 *    that same probe, was GREEN** (`tests=1 failures=0`), which
 *    is the proof that the gap was real rather than theoretical.
 * 2. *The discovery is complete.* A module was added to
 *    [extraScanPrunes]; the cross-check row fired with
 *    `29 trees` against `30` and named the module it lost. A
 *    shared prune set between the two walks would have hidden
 *    that mutation from both sides, which is why they do not
 *    share one.
 *
 * The probe used a string literal rather than a real import:
 * JGit is not on the classpath, so a genuine import is a
 * COMPILE error, and a failing compile is not a RED.
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
 *   NOTE: only `scm-git`'s own build file is checked anywhere
 *   today, by `FArchL5NoJgitTest`; a JGit *dependency* declared
 *   in any other module's build file would not be caught by
 *   either fitness. Recorded as a lead, not closed here —
 *   the mandate asks for the import ban, and widening a gate
 *   beyond the ask is its own kind of defect.
 * - `v2/test-harness/`: now scanned, because it is a `v2`
 *   production tree like any other. An earlier draft excluded
 *   it as "meta-tooling"; that exclusion was inherited with the
 *   7-entry list and had no argument behind it.
 * - Non-`.kt` files (e.g. documentation, JSON manifests):
 *   JGit is a Kotlin/Java import; non-source files cannot
 *   import it.
 * - Everything outside `v2`: `core/` is V1 and quarantined,
 *   and the `examples/` plugins are separately certified
 *   artifacts. Neither is this row's subject.
 */
class BannedImportsFitnessTest {

    /**
     * Directories that can never contain a module and are expensive to
     * walk. `build` is the one that matters: without it the walk
     * descends into every module's compiled output.
     *
     * Both derivations prune exactly these, and the src/main walk adds
     * [extraScanPrunes] on top. The asymmetry is deliberate: if both
     * walks pruned the same wider set, then pruning a module from one
     * would hide it from BOTH and the cross-check row would stay green
     * while the ban silently covered less. Sharing the blind spot is
     * exactly what a cross-check must not do.
     */
    private val prunedDirNames = setOf("build", ".gradle", ".git", "node_modules")

    /**
     * Extra names the src/main walk skips. These are recognised as
     * never-a-module, so if one of them ever turns out to hold a
     * `build.gradle.kts` and a `src/main`, the cross-check row goes
     * red and says so rather than letting the ban quietly narrow.
     */
    private val extraScanPrunes = setOf("out", "target")

    /**
     * Every `src/main` tree under `v2`, discovered rather than listed.
     *
     * Returns the `main` directories themselves so a caller can scan
     * them without re-deriving the `src` step. Walks breadth-first and
     * stops descending once it reaches a `src`, so it cannot wander
     * into a source tree looking for a nested one.
     */
    private fun discoverProductionMainTrees(v2Root: Path): List<Path> {
        val found = mutableListOf<Path>()
        val pending = ArrayDeque<Path>()
        pending.addLast(v2Root)
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            val children = try {
                Files.newDirectoryStream(dir).use { stream -> stream.toList() }
            } catch (_: java.io.IOException) {
                continue
            }
            for (child in children) {
                if (!Files.isDirectory(child)) continue
                val name = child.fileName.toString()
                if (name in prunedDirNames || name in extraScanPrunes) continue
                if (name == "src") {
                    val main = child.resolve("main")
                    if (Files.isDirectory(main)) found.add(main)
                    continue
                }
                pending.addLast(child)
            }
        }
        return found
    }

    /**
     * The `src/main` trees of every module that declares a build file,
     * derived by walking for `build.gradle.kts` instead of for `src`.
     *
     * This is deliberately a DIFFERENT derivation from
     * [discoverProductionMainTrees]: the ban row and this one must not
     * be able to be wrong in the same way. A module with a build file
     * but no `src/main` (the root, the architecture-tests module, the
     * BOM) is not a source tree and is excluded here for that reason,
     * not by name.
     */
    private fun discoverModuleMainTrees(v2Root: Path): Set<Path> {
        val result = mutableSetOf<Path>()
        val pending = ArrayDeque<Path>()
        pending.addLast(v2Root)
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            val children = try {
                Files.newDirectoryStream(dir).use { stream -> stream.toList() }
            } catch (_: java.io.IOException) {
                continue
            }
            for (child in children) {
                if (!Files.isDirectory(child)) continue
                val name = child.fileName.toString()
                if (name in prunedDirNames) continue
                val main = child.resolve("src").resolve("main")
                if (Files.isRegularFile(child.resolve("build.gradle.kts")) && Files.isDirectory(main)) {
                    result.add(main)
                }
                if (name != "src") pending.addLast(child)
            }
        }
        return result
    }

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

    /**
     * The ban row cannot pass vacuously. Two independent derivations
     * of "the production source trees" must agree exactly; if they do
     * not, discovery has broken and the ban above is scanning something
     * other than the tree it names.
     *
     * No threshold appears here on purpose. A count like `>= 7` is the
     * same hand-maintained value in a slower form: it stays green while
     * the real coverage shrinks. The equality cannot drift, because both
     * sides are recomputed from the repository on every run.
     */
    @Test
    fun `every production module main source is scanned by the ban`() {
        val v2Root = TestProjectRoot.dir.toPath().resolve("v2")
        val bySourceWalk = discoverProductionMainTrees(v2Root).toSet()
        val byBuildFileWalk = discoverModuleMainTrees(v2Root)

        assertEquals(
            byBuildFileWalk,
            bySourceWalk,
            "the JGit ban would not cover every production module. Discovered by walking for " +
                "src/main: ${bySourceWalk.size} trees; derived from the modules that declare a " +
                "build.gradle.kts and have a src/main: ${byBuildFileWalk.size}. " +
                "Only in the build-file derivation: ${(byBuildFileWalk - bySourceWalk).map { projectRootRelativePath(it) }}. " +
                "Only in the src/main walk: ${(bySourceWalk - byBuildFileWalk).map { projectRootRelativePath(it) }}.",
        )
    }

    @Test
    fun `no org_eclipse_jgit imports in v2 main source`() {
        val v2Root = TestProjectRoot.dir.toPath().resolve("v2")
        val violations = mutableListOf<String>()

        for (mainTree in discoverProductionMainTrees(v2Root)) {
            Files.walk(mainTree).use { stream ->
                stream.filter { it.toString().endsWith(".kt") }
                    .forEach { file -> checkFile(file, violations) }
            }
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
