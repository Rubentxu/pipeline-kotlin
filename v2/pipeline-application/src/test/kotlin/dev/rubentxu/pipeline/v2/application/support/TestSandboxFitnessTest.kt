package dev.rubentxu.pipeline.v2.application.support

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * S6-PRE, second law — a harness that forks a child must not write outside a directory it owns.
 *
 * ## What this exists to end
 *
 * BLOCK 3.5 measured it: one gate wrote **43 files into the system temp directory**, from four
 * harnesses. The BLOCK 3.3 inventory, taken unscoped rather than pattern-by-pattern, was larger
 * than that residue: **224 top-level entries plus 2255 files inside one debug directory, 9,0 MB**.
 * Those are two different populations, so they are not summed into a single "file count" here. The
 * debt is now 0 *by measurement*, and a measurement does not stop the next harness from starting.
 *
 * The two properties here are deliberately narrower than "no test writes to a temp directory",
 * because both halves are required and neither alone is the defect:
 *
 * - a test that writes to the system temp directory and forks nothing is a test with untidy
 *   bookkeeping, and there are 103 of them;
 * - a test that forks and owns every path it touches is the correct shape, and there are 70.
 *
 * Only the intersection — 25 files that fork a child AND write outside their own directory — is the
 * thing that produced the 43, and that is what this law counts.
 *
 * ## Why the scan is not a substring search
 *
 * BLOCK 3.5 tried to write this law and could not, for three measured reasons, all of them false
 * positives that a naive scan would have convicted:
 *
 * 1. **A raw text scan convicts the KDoc that documents the fix.** The migrated files name the
 *    pattern in prose precisely so the next reader knows why the code looks like that. Comments are
 *    stripped before anything is decided.
 * 2. **A scan for one API misses the same defect reached another way.** The worst offender in the
 *    repository writes through a hardcoded absolute path, not through the parentless-temp API — so a
 *    scan for that method alone would have passed it. It had accumulated 2253 files by BLOCK 3.5,
 *    from the 2255 counted one block earlier; the two figures are measurements one gate apart, not
 *    a contradiction. So the law looks for the *property*, in three forms, not for one method.
 * 3. **Argument count, not method name, is what decides "has a parent".**
 *    `Files.createTempFile(prefix, suffix)` has no parent and lands in `java.io.tmpdir`;
 *    `Files.createTempFile(dir, prefix, suffix)` has one and does not. Same method, opposite
 *    behaviour, so the law counts top-level arguments and ignores the name entirely.
 *
 * Point 3 is the one that decides the size of the list. Among the files that fork, 34 contain a
 * `createTemp*` call of some arity; counting only the parentless forms reports 25, and the 9 that
 * disappear are `createTempFile(workdir, ...)` calls that were always correct.
 *
 * ## What this law does NOT claim
 *
 * It cannot tell a *write* from a *path handed to the product as input*. One measured case is in the
 * allowlist for that reason: `WULpr010CliCharacterizationTest` passes a deliberately non-existent
 * path to `validate` so the CLI can report it as missing, and that path is not a leak. Rather than
 * build a dataflow analysis to separate the two, the distinction is **data** — it is written down in
 * the allowlist as a reviewed decision. The list is the review record, and adding to it is a claim
 * that must be argued, not a default.
 */
@DisplayName("S6-PRE 2 — un arnes que bifurca no escribe fuera de su sandbox")
class TestSandboxFitnessTest {

    @Test
    fun `ningun arnes que bifurca escribe fuera de su directorio propio`() {
        val sources = scan()
        val offenders = sources
            .filter { it.forks && it.leaks.isNotEmpty() }
            .map { it.id }
            .toSortedSet()
        val scope = "the law read ${sources.size} test sources and found ${offenders.size} offenders"

        val unexpected = offenders - KNOWN_SANDBOX_LEAKS
        assertTrue(
            unexpected.isEmpty(),
            "these files fork a child AND write outside a directory they own:\n" +
                unexpected.joinToString("\n") { "  - $it" } +
                "\n\n($scope)\n\n" +
                "Give the path a parent the test owns (a @TempDir parameter is the usual home) " +
                "and delete the name from KNOWN_SANDBOX_LEAKS. A name may only be ADDED here " +
                "together with why the write cannot be relocated yet.",
        )

        // The law reads what it governs. Without this, a migration that fixed the leak would leave
        // the ledger claiming debt that no longer exists, and a ledger that only grows is a law that
        // can only ever pass.
        val stale = KNOWN_SANDBOX_LEAKS - offenders
        assertTrue(
            stale.isEmpty(),
            "these entries no longer fork-and-leak but were not removed from KNOWN_SANDBOX_LEAKS, " +
                "so the ledger claims debt that does not exist:\n" +
                stale.joinToString("\n") { "  - $it" } +
                "\n\n($scope)\n\n" +
                "Delete the name. A debt that a migration already repaid must not be left behind, " +
                "or the next reader cannot tell a real debt from a fossil.",
        )
    }

    /** One test source, classified. */
    private data class Source(val id: String, val forks: Boolean, val leaks: Set<String>)

    private fun scan(): List<Source> {
        val root = testSourcesRoot()
        val classified = ArrayList<Source>()
        Files.walk(root).use { stream ->
            stream.forEach { path ->
                if (Files.isRegularFile(path) && path.extension == "kt") {
                    classify(root, path)?.let { classified += it }
                }
            }
        }
        return classified
    }

    private fun classify(root: Path, path: Path): Source? {
        val id = governedId(root, path) ?: return null
        val code = stripComments(Files.readString(path))
        return Source(
            id = id,
            forks = FORK_MARKERS.any { code.contains(it) },
            leaks = leakReasons(code),
        )
    }

    /**
     * The name the allowlist knows this file by, or null when the law does not govern it.
     *
     * Two decisions live here, and the first one is a correction of a mistake this file made before
     * it ever ran: an earlier version walked all of `v2` and classified whatever it found, which
     * reached the KSP output under `build/generated` and threw `no module above 'src'` on a file no
     * human wrote.
     * Generated sources are excluded, not because they are clean but because **nobody authored them**:
     * a defect there belongs to the generator or to the KSP processor that emitted the text, and
     * naming it by its output path would put a debt on a file that cannot be edited in place.
     *
     * The second decision is the identity itself: everything before `src`, then everything after the
     * source directory, with `src/test/<language>` dropped. Dropping the middle is what makes the
     * name survive a nested module — the id for the scm-git tests is
     * `pipeline-step-sdk/scm-git/dev/rubentxu/...`, not just `scm-git/...` — and keeping the leading
     * part is what makes it unique, since two modules can share a package and a class name.
     */
    private fun governedId(root: Path, path: Path): String? {
        val parts = root.relativize(path).map { it.toString() }
        val anchor = parts.indexOfLast { it == "src" }
        if (anchor < 1 || parts.getOrNull(anchor + 1) != "test") return null
        if (parts.subList(0, anchor).any { it == "build" }) return null
        return (parts.subList(0, anchor) + parts.subList(anchor + 3, parts.size)).joinToString("/")
    }

    /**
     * What makes a file count as writing outside its own directory.
     *
     * Each entry is a shape the defect takes, not a style preference. The arity rule is the one that
     * matters: see the class KDoc.
     */
    private fun leakReasons(code: String): Set<String> {
        val reasons = linkedSetOf<String>()
        for (match in TEMP_CALLS.findAll(code)) {
            val arity = topLevelArgumentCount(code, match.range.last + 1)
            val withParent = if (match.value.contains("File")) FILE_WITH_PARENT else DIRECTORY_WITH_PARENT
            if (arity != null && arity < withParent) {
                reasons += "${match.value.trimEnd('(')} without a parent ($arity args)"
            }
        }
        if (ABSOLUTE_TMP_LITERAL.containsMatchIn(code)) reasons += "hardcoded absolute temp path"
        if (TMPDIR_PROPERTY.containsMatchIn(code)) reasons += "reads the JVM temp-dir property"
        return reasons
    }

    /** Top-level comma-separated arguments of the call whose `(` is at [openParenIndex]. */
    private fun topLevelArgumentCount(code: String, openParenIndex: Int): Int? {
        var depth = 1
        var commas = 0
        var index = openParenIndex
        var inString = false
        while (index < code.length) {
            val ch = code[index]
            when {
                inString -> {
                    if (ch == '\\') index++
                    else if (ch == '"') inString = false
                }
                ch == '"' -> inString = true
                ch == '(' || ch == '[' || ch == '{' -> depth++
                ch == ')' || ch == ']' || ch == '}' -> {
                    depth--
                    if (depth == 0) return commas + 1
                }
                ch == ',' && depth == 1 -> commas++
            }
            index++
        }
        return null
    }

    /**
     * Comments are removed before anything is decided.
     *
     * This is not cosmetic: a migrated harness names the pattern it stopped using, in prose, in the
     * KDoc of the method that replaced it. A scan over raw text convicts the documentation of its own
     * fix. Block comments go first so a `//` inside one is not mistaken for a line comment.
     *
     * The negative lookbehind on the line form is not decoration either. Without it, the `//` of a
     * `://` inside a string literal would start a comment and the rest of the line — real code —
     * would be discarded, so a file could read as compliant purely because its URL ate it. With it,
     * only a `//` that is not the tail of a protocol marker is treated as a comment. There is no `$`
     * anchor: `.` already refuses a line terminator, so `.*` ends at the line break on its own.
     */
    private fun stripComments(text: String): String =
        LINE_COMMENT_TAIL.replace(BLOCK_COMMENT.replace(text, ""), "")

    private fun testSourcesRoot(): Path {
        val appDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            .let {
                if (it.name == "pipeline-application") it
                else it.resolve("v2").resolve("pipeline-application")
            }
        val root = appDir.resolve("..").resolve("..").normalize()
        val v2 = root.resolve("v2")
        if (!Files.isDirectory(v2.resolve("pipeline-application").resolve("src").resolve("test").resolve("kotlin"))) {
            throw IllegalStateException(
                "no v2 test sources at $v2; skipping would be a green that proves nothing",
            )
        }
        return v2
    }

    private companion object {
        // Assembled from pieces on purpose. This file describes the very tokens it looks for, and
        // a detector that convicts itself is a detector nobody trusts. The BLOCK comment stripper
        // already removes the prose; these constants are the part that lives in CODE, and splitting
        // them keeps this file out of its own offender set without naming itself in an exemption
        // list that could then quietly widen.
        private val FORK_MARKERS = listOf("Process" + "Builder(", "AppBin" + "Support")
        private val TEMP_CALLS = Regex("createTemp" + "(?:File|Directory)\\(")
        private val ABSOLUTE_TMP_LITERAL = Regex("\"/" + "tmp/")
        private val TMPDIR_PROPERTY = Regex("java\\.io\\." + "tmpdir")
        private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT_TAIL = Regex("(?<!:)//.*")

        private const val FILE_WITH_PARENT = 3
        private const val DIRECTORY_WITH_PARENT = 2

        /**
         * The measured debt the day this law was written: 25 test files that fork a child process and
         * also write outside a directory they own. 11 of them are also in
         * [InstalledDistributionHarnessFitnessTest.KNOWN_DEBT], so migrating those 11 shrinks both
         * laws at once — which is the reason this list is worth keeping rather than replacing with
         * a ban.
         */
        val KNOWN_SANDBOX_LEAKS: Set<String> = setOf(
            // B1 (v0.48.0-rc2): C8InstalledDistributionCanaryTest forks the runtime as part of
            // its canary protocol and writes outside the test-owned dir as part of that protocol.
            // The migration to a sandbox-only path belongs to a later cycle that defines how
            // the canary observes the same property from within its own temp root.
            "pipeline-application/dev/rubentxu/pipeline/v2/application/cli/C8InstalledDistributionCanaryTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/B1WURp053rContextRuntimeClosureTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/CompatibilityCorpusTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/S0SemanticWitnessMatrixTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/S3EnvironmentSemanticWitnessTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/TrapFormNegativeFixtureTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/UatCompat001CorpusSmokeRunTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/UatDsl001JenkinsFamiliarityTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/UatDsl006BodyExecutionTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/UatLocal005CheckoutGitTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/UatLocal007SandboxProfileTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/UatStep001ShFailureStepFinishedCountTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/cli/HttpInstalledUatTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/cli/S54ExternalVerticalRestartUatTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/cli/WULpr010CliCharacterizationTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/cli/WULpr011ResumeLifecycleUatTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/cli/WURp019GradleRealUatTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/cli/WURp020MavenRealUatTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/cli/WURp021NodeRealUatTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/cli/WURp023ObservationModesUatTest.kt",
            "pipeline-application/dev/rubentxu/pipeline/v2/application/support/PureBuilderProbe.kt",
            "pipeline-events-store/dev/rubentxu/pipeline/v2/events/durable/FileBackedRunExecutionLeaseCrossProcessTest.kt",
            "pipeline-step-sdk/runtime/dev/rubentxu/pipeline/v2/sdk/runtime/durable/DurableShellExecutorAdversarialTest.kt",
            "pipeline-step-sdk/runtime/dev/rubentxu/pipeline/v2/sdk/runtime/durable/EnvModelTest.kt",
            "pipeline-step-sdk/scm-git/dev/rubentxu/pipeline/v2/sdk/scm/git/GitCheckoutCredentialsRefFailClosedTest.kt",
        )
    }
}
