package dev.rubentxu.pipeline.v2.application.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S6-PRE — the law that keeps the pipe deadlock from coming back.
 *
 * ## Why this is scoped and not a blanket ban
 *
 * The repo has **196 `ProcessBuilder` sites across 95 test files**, and banning the token outright
 * would be a law with no relation to the defect. Most of them launch `git`, `tar` or `sh` for a
 * domain test and write nothing to a pipe; they cannot deadlock this way.
 *
 * The property that actually failed is narrower and sharper:
 *
 * > A harness that forks the INSTALLED DISTRIBUTION must go through [OwnedSubprocess].
 *
 * Only that binary has the shape that kills: `Main.kt:431` writes the entire run event log in one
 * `println`, so any run can exceed the 64 KiB pipe buffer, and a harness that waits before it drains
 * will hang forever and leave the child alive. [AppBinSupport] is what marks a file as one of
 * those harnesses, which is why the scan keys on it rather than on the test class name.
 *
 * ## Why the allowlist is the point and not a concession
 *
 * The 32 names below are the current debt, written out. A law that permitted them silently would be
 * the same defect shape as a prohibition over a file that no longer exists: a green produced by the
 * absence of a subject. Instead each one is a visible entry that a migration deletes, and the
 * assertion is that the set of NEW offenders is empty — so the debt can only shrink, never grow.
 *
 * `CompatibilityCorpusTest` is deliberately absent: it was the first migration and it is the file
 * that carried the measured hang.
 */
@DisplayName("S6-PRE — todo harness de la distribucion instalada pasa por OwnedSubprocess")
class InstalledDistributionHarnessFitnessTest {

    @Test
    fun `no hay ningún harness nuevo que lance la distribucion instalada por su cuenta`() {
        val root = testSourcesRoot()
        val offenders = Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .map { it to Files.readString(it) }
                .filter { (_, text) ->
                    text.contains(INSTALLED_DISTRIBUTION_MARKER) &&
                        (text.contains("ProcessBuilder(") || text.contains(RUNTIME_EXEC)) &&
                        !text.contains(PRIMITIVE)
                }
                .map { (path, _) -> relative(root, path) }
                .sorted()
                .collect(Collectors.toList())
        }

        val unexpected = offenders - KNOWN_DEBT
        assertTrue(
            unexpected.isEmpty(),
            "these files fork the installed distribution with a raw ProcessBuilder and bypass " +
                "OwnedSubprocess, which is how `fixture12-error-handling` hung until @Timeout left " +
                "its child alive:\n" + unexpected.joinToString("\n") { "  - $it" } +
                "\n\nRoute them through OwnedSubprocess.run(...) and delete them from KNOWN_DEBT. " +
                "A name may only be ADDED here together with the reason it cannot be migrated yet.",
        )

        // The law reads what it governs: if a migration deletes an entry, the file is genuinely gone
        // rather than merely no longer detected, and the two facts cannot drift apart silently.
        val stale = KNOWN_DEBT - offenders
        assertTrue(
            stale.isEmpty(),
            "these entries are migrated but were not removed from KNOWN_DEBT, so the ledger now " +
                "claims debt that does not exist:\n" + stale.joinToString("\n") { "  - $it" },
        )
    }

    private fun testSourcesRoot(): Path {
        val appDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            .let { if (it.fileName?.toString() == "pipeline-application") it else it.resolve("v2").resolve("pipeline-application") }
        val root = appDir.resolve("src").resolve("test").resolve("kotlin")
        if (!Files.isDirectory(root)) {
            throw IllegalStateException("no test sources at $root; skipping would be a green that proves nothing")
        }
        return root
    }

    private fun relative(root: Path, path: Path): String =
        root.relativize(path).toString().replace('\\', '/')

    private companion object {
        const val INSTALLED_DISTRIBUTION_MARKER = "AppBinSupport"
        const val RUNTIME_EXEC = "Runtime.getRuntime().exec"
        const val PRIMITIVE = "OwnedSubprocess"

        /**
         * The measured debt on the day this law was written: 32 harnesses that fork the installed
         * distribution directly. Each is a RED waiting to be migrated, and the count is expected to
         * fall with every commit that adopts the primitive.
         */
        val KNOWN_DEBT: Set<String> = setOf(
            "dev/rubentxu/pipeline/v2/application/CliCompileErrorExitsOneTest.kt",
            "dev/rubentxu/pipeline/v2/application/CliDslConstructionFailureSurfacesTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/HttpInstalledUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/CliNonCanonicalInMemoryExitsTwoTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/P3DPluginEventInstalledDistributionUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/PluginAdmissionInstalledDistributionUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WULpr010CliCharacterizationTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WULpr011ResumeLifecycleUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WURp019GradleRealUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WURp020MavenRealUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WURp021NodeRealUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WURp023ObservationModesUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/Lfc2WaitUntilCanonicalReentryFitnessTest.kt",
            "dev/rubentxu/pipeline/v2/application/S0SemanticWitnessMatrixTest.kt",
            "dev/rubentxu/pipeline/v2/application/S3EnvironmentSemanticWitnessTest.kt",
            "dev/rubentxu/pipeline/v2/application/SelfHostedPipelineScriptHonestyTest.kt",
            "dev/rubentxu/pipeline/v2/application/support/PureBuilderProbe.kt",
            "dev/rubentxu/pipeline/v2/application/TrapFormNegativeFixtureTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatDsl003ParallelTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatDsl005TimeoutGrammarTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatDsl006BodyExecutionTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatEvt001ReplayTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatEvt002MultiStepReplayTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatS2AWhenGateInstalledBinaryTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatS2R0RunOwnershipCliTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatStep001ShExecutionTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatStep002EchoCaptureTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatStep003ErrorAbortTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatStep004SleepTimingTest.kt",
        )
    }
}
