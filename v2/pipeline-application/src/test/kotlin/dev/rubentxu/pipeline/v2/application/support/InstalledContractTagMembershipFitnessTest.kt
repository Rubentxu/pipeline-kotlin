package dev.rubentxu.pipeline.v2.application.support

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Lane membership is DECLARED, and this fitness is what makes the declaration mean
 * something.
 *
 * ## The two lanes
 *
 * `:pipeline-application` runs its tests in two lanes that partition on one JUnit tag:
 *
 * - `:test` — the fast lane. In-process. Excludes `installed-contract`.
 * - `:installedContractTest` — declares `dependsOn(installDist)`, so a clean checkout
 *   gets a binary built from the current commit.
 *
 * A class is in one lane or the other by carrying `@Tag("installed-contract")` or not
 * carrying it. There is no third option and no negotiation.
 *
 * ## What was broken, measured
 *
 * The migration to this tag stopped at **9 of the harnesses that need the installed
 * distribution**. The other 31 ran in the fast lane, so every inner-loop `:test` after a
 * `pipeline-domain` edit forked the installed `pipelinek` binary dozens of times. The
 * whole-module `:test` exceeded a 1500 s budget on two consecutive attempts.
 *
 * Worse, the damage from a stale binary was invisible until it was total: one run
 * produced **86 REDs that were all one cause** — `AppBinSupport.discover()` refusing an
 * installed distribution built from a different commit. One environmental fact, 86 red
 * rows.
 *
 * And the build file had been *describing* the split the whole time. It claimed the
 * fast lane "does NOT need the plugin JAR; the test that needs it is in the installed
 * lane" and that `:test` "excludes the tag so it never sees an installed-CLI test".
 * Neither sentence was true: the only `excludeTags` in the module was `release-scale`,
 * behind a CI-unset property. This is the third instance in this module's build file of
 * a comment asserting a property the wiring does not have — the same class as the
 * `mustRunAfter`/`dependsOn` disagreement closed on 2026-10-17.
 *
 * ## The property is the PRECONDITION, not the fork
 *
 * The first version of this rule asked whether a class *spawns* the launcher. That was
 * wrong, and `AppBinProvenanceTest` is why. It resolves the real install path and
 * asserts it exists — "a test that skipped this would assert nothing" — but forks only
 * `git`, never the binary. Keyed on the fork, that class would have stayed in the fast
 * lane and refused for want of a distribution, which is the failure this whole change
 * exists to remove.
 *
 * So the rule asks the question a lane actually turns on: **does an installed
 * distribution have to exist for this class to mean anything?** That covers both
 * observed shapes — `RuntimeBuildIdentityTest` resolves the path and forks it,
 * `AppBinProvenanceTest` resolves it and asserts it — and it does not depend on which
 * helper a given harness happens to use.
 *
 * **Known limitation.** Two spellings of the property are covered: calling
 * `AppBinSupport.discover(`, and naming an installed-launcher path. A harness that
 * obtains the distribution some third way would not be seen. The mitigation is that the
 * population is re-measured whenever a new distribution helper is introduced, not that
 * the rule is complete by construction.
 *
 * ## Why a source scan
 *
 * The failure this guards has no behavioural signature. A harness that forgets its tag
 * still **passes** — it just runs in the expensive lane, which is invisible unless you
 * were watching the wall clock. No assertion about outcomes can separate "correctly
 * tagged" from "silently slow". Naming the missing token is the only assertion that
 * discriminates those two worlds.
 *
 * Comments are stripped before the scan, so a file may discuss the installed
 * distribution in prose without being convicted of depending on it.
 */
class InstalledContractTagMembershipFitnessTest {

    private val tag = "installed-contract"

    /**
     * EVERY source root this module compiles tests from, not just the one named `test`.
     *
     * The module has two test source sets, and the four harnesses that require a built plugin
     * artifact moved into the second one (`pluginContractTest`) precisely because that is the
     * lane that declares the plugin builds. A scan that walked only `test/kotlin` would then
     * have been GREEN over a population it could no longer see — the same blindness as a
     * file-level check being blind to a second class in one file, and the reason a guard must
     * be fitted to the population rather than to the directory that happens to hold it today.
     *
     * A missing root is an error, not an empty result: a root that silently contributes nothing
     * turns the `population > 0` assertion into a partial green rather than a failure.
     */
    private val testSourceRoots: List<Path> by lazy {
        val appDir = Path.of("").toAbsolutePath()
            .let { if (it.fileName?.toString() == "pipeline-application") it else it.resolve("v2").resolve("pipeline-application") }
        listOf("test", "pluginContractTest").map { appDir.resolve("src").resolve(it).resolve("kotlin") }
    }

    /** Comments removed, so prose about the installed distribution is not a conviction. */
    private fun codeOnly(text: String): String =
        Regex("""/\*[\s\S]*?\*/""").replace(text, " ").let { Regex("""//[^\n]*""").replace(it, " ") }

    private fun testClasses(): List<Path> {
        val missing = testSourceRoots.filterNot { Files.isDirectory(it) }
        require(missing.isEmpty()) {
            "pipeline-application test source roots not found: $missing. A root that " +
                "contributes nothing would leave this fitness GREEN over a population it " +
                "cannot see."
        }
        return testSourceRoots.flatMap { root ->
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith("Test.kt") }
                    // SELF-EXCLUSION. This file names `AppBinSupport.discover(` and both install
                    // paths in its own patterns, so a scan that includes it convicts the fitness
                    // of the defect it hunts. The exclusion is by class name and stated here
                    // rather than hidden in the predicate, because an invisible self-exclusion is
                    // how a fitness ends up quietly measuring nothing.
                    .filter { it.fileName.toString() != javaClass.simpleName + ".kt" }
                    .toList()
            }
        }
    }

    /**
     * True when an installed distribution is a precondition of the class.
     *
     * Three spellings, and the third one is there because the first two were measured to be
     * insufficient. `ObsFConsumerContinuityUatTest` builds the same install root as
     * `ObsFConsumerContinuityUatTest` does by splicing it — `.resolve("build").resolve("install")`
     * — so the literal `build/install` never appears and the file was invisible to a check that
     * looked for it. It then ran a full forked distribution pipeline inside the fast lane while
     * this fitness reported GREEN.
     *
     * The third spelling is the distribution's own `applicationName` as a code literal. It is
     * the one token that cannot be hidden by concatenation, because naming the executable file
     * is what "requires an installed distribution" actually means at the call site. Comments are
     * already stripped, so a file that only *discusses* `pipelinek` in prose is not convicted —
     * two of the three files naming it do exactly that, and the population count below stays
     * honest.
     */
    private fun requiresInstalledDistribution(code: String): Boolean =
        code.contains("AppBinSupport.discover(") ||
            code.contains("\"$DISTRIBUTION_APPLICATION_NAME\"") ||
            INSTALL_LAUNCHER_PATTERNS.any { it.containsMatchIn(code) }

    /**
     * Every top-level class in [code] that can actually EXECUTE, paired with whether it
     * carries the tag.
     *
     * Granularity is the class, not the file, and that is a correction rather than a
     * preference. The first version of this fitness asserted at file level and GREEN-lit
     * a file holding two test classes with only one of them tagged: `RuntimeBuildIdentityTest.kt`
     * declares `RuntimeBuildIdentityTest` and `BuildProvenanceIsReportedByTheInstalledBinaryTest`,
     * the second is the one that spawns the launcher, and a file-level check could not see
     * the difference. A file-level lane check is a lane check with a hole shaped like a file.
     *
     * "Can execute" is decided by carrying a `@Test` method in its own region, not by the
     * class name ending in `Test`. The name is a convention; the annotation is what makes
     * a class a subject of the `Test` task, and a check keyed on the convention would go
     * blind on the first class that breaks it.
     */
    private fun executableClasses(code: String): List<Pair<String, Boolean>> {
        val lines = code.lines()
        val decls = lines.withIndex().filter { (_, l) ->
            CLASS_DECLARATION.containsMatchIn(l)
        }
        return decls.mapIndexed { index, (i, declLine) ->
            val end = decls.getOrNull(index + 1)?.index ?: lines.size
            val body = lines.subList(i + 1, end).joinToString("\n")
            // the contiguous annotation block directly above the declaration
            val annotations = buildString {
                var k = i - 1
                while (k >= 0 && Regex("""^\s*@\w""").containsMatchIn(lines[k])) {
                    appendLine(lines[k]); k--
                }
            }
            val name = CLASS_NAME.find(declLine)?.groupValues?.get(1).orEmpty()
            name to (TEST_METHOD.containsMatchIn(body) && annotations.contains("""@Tag("$tag")"""))
        }
    }

    @Test
    fun `every harness that requires an installed distribution carries the installed-contract tag`() {
        val untagged = mutableListOf<String>()
        val taggedWithoutRequirement = mutableListOf<String>()
        var population = 0

        for (path in testClasses()) {
            val code = codeOnly(path.readText())
            val requires = requiresInstalledDistribution(code)
            if (requires) population++
            for ((name, tagged) in executableClasses(code)) {
                when {
                    requires && !tagged -> untagged += "${path.fileName}:$name"
                    tagged && !requires -> taggedWithoutRequirement += "${path.fileName}:$name"
                }
            }
        }

        assertTrue(
            untagged.isEmpty(),
            "these classes require an INSTALLED distribution but do not carry " +
                "@Tag(\"$tag\"), so they run in the FAST lane: every inner-loop :test then " +
                "depends on whichever distribution the machine last installed, and one stale " +
                "binary turns all of them RED at once (measured: 86 REDs, one cause). Add the " +
                "tag -- the installed lane is where a distribution is a declared dependency " +
                "rather than ambient state.\n" +
                "untagged (${untagged.size}):\n" + untagged.joinToString("\n"),
        )
        assertTrue(
            taggedWithoutRequirement.isEmpty(),
            "these classes carry @Tag(\"$tag\") but do not require an installed distribution, " +
                "so they pay for an installDist they never use. Tag membership is a declaration " +
                "about a precondition; a class with no such precondition is declaring one it " +
                "does not have.\n" +
                "over-tagged (${taggedWithoutRequirement.size}):\n" + taggedWithoutRequirement.joinToString("\n"),
        )
        assertTrue(
            population > 0,
            "no harness requires an installed distribution at all. That means the scan found " +
                "no subject -- a law over an empty population is a green produced by the " +
                "absence of a subject, which is the failure this fitness exists to prevent.",
        )
    }

    private companion object {
        /**
         * The distribution's `applicationName` (WU-LPR-070). Not a runtime constant, and that is
         * the point: a string that cannot be spliced out of reach of the scan.
         */
        const val DISTRIBUTION_APPLICATION_NAME = "pipelinek"
        val INSTALL_LAUNCHER_PATTERNS = listOf(
            Regex("""build/install"""),
            Regex("""install/pipelinek"""),
        )
        val CLASS_DECLARATION =
            Regex("""^(?:public |internal |private |open |final |abstract |sealed |data |inner )*class\s+\w""")
        val CLASS_NAME = Regex("""class\s+(\w+)""")
        val TEST_METHOD = Regex("""@(?:Test|ParameterizedTest|TestFactory|RepeatedTest)\b""")
    }
}