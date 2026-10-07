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
 * `println`, so any run can exceed the pipe buffer, and a harness that waits before it drains will
 * hang forever and leave the child alive. [AppBinSupport] is what marks a file as one of those
 * harnesses, which is why the scan keys on it rather than on the test class name.
 *
 * ## The buffer size, measured rather than remembered
 *
 * This file used to state 64 KiB, and so do eight other Kotlin files and five receipts. **On this
 * host that number is wrong.** Measured with `F_GETPIPE_SZ` on 20 of 20 freshly created pipes: the
 * default pipe buffer is **8192 bytes**. `fs.pipe-max-size = 1048576` is the ceiling a process may
 * *request* with `F_SETPIPE_SZ`, not the size it gets for free.
 *
 * The law is unaffected in direction — any buffer can be exceeded, and 8 KiB is easier to exceed
 * than 64 KiB, so waiting before draining is still wrong — but a threshold stated as 8x larger than
 * reality is a threshold that reads as safe. The historical receipts keep their original figure:
 * a receipt is evidence for the belief held at its SHA, and rewriting one silently would be a
 * falsification. This KDoc, which is read as current documentation, carries the measured value.
 *
 * ## Why the allowlist is the point and not a concession
 *
 * The 24 names below are the current debt, written out. A law that permitted them silently would be
 * the same defect shape as a prohibition over a file that no longer exists: a green produced by the
 * absence of a subject. Instead each one is a visible entry that a migration deletes, and the
 * assertion is that the set of NEW offenders is empty — so the debt can only shrink, never grow.
 *
 * The count fell 32 -> 29 -> 28 -> 27 -> 26 -> 25 -> 24 as harnesses adopted [OwnedSubprocess].
 * `CompatibilityCorpusTest` is deliberately absent: it was the first migration and it is the file
 * that carried the measured hang.
 */
@DisplayName("S6-PRE — todo harness de la distribucion instalada pasa por OwnedSubprocess")
class InstalledDistributionHarnessFitnessTest {

    @Test
    fun `no hay ningún harness nuevo que lance la distribucion instalada por su cuenta`() {
        val root = testSourcesRoot()
        // S6-PRE, second correction: the exemption used to be `!text.contains(OwnedSubprocess)`,
        // i.e. PER FILE. That is the wrong granularity, and it was found by a real failure rather
        // than by inspection: `CompatibilityCorpusTest` had six launch sites, S6-PRE migrated two,
        // and the remaining four were exempt forever because the file mentioned the primitive
        // somewhere else. `fixture14CredentialsBindings` then sat on `waitFor()` with no reader on
        // the pipe and died on the 600 s JUnit timeout with a live `pipelinek` behind it.
        //
        // A file now counts as migrated only when it contains NO raw launch of the installed
        // distribution at all. Adopting the primitive once buys nothing for the call sites that
        // were left behind, which is exactly the property the previous rule failed to state.
        val offenders = Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .filter { path -> !path.fileName.toString().equals(FITNESS_FILE_NAME) }
                .map { it to Files.readString(it) }
                .filter { (_, text) ->
                    text.contains(INSTALLED_DISTRIBUTION_MARKER) &&
                        (text.contains("ProcessBuilder(") || text.contains(RUNTIME_EXEC))
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
         * This file names `ProcessBuilder(` and `OwnedSubprocess` because it READS them out of other
         * files as text. It is the law, not a harness, so excluding it by name is what keeps the
         * rule from convicting its own detector. Named rather than pattern-matched so the exemption
         * cannot quietly widen to a second file.
         */
        const val FITNESS_FILE_NAME = "InstalledDistributionHarnessFitnessTest.kt"

        /**
         * The measured debt on the day this law was written: 32 harnesses that fork the installed
         * distribution directly, now 24. Each is a RED waiting to be migrated, and the count is
         * expected to fall with every commit that adopts the primitive.
         */
        val KNOWN_DEBT: Set<String> = setOf(
            "dev/rubentxu/pipeline/v2/application/CliCompileErrorExitsOneTest.kt",
            "dev/rubentxu/pipeline/v2/application/CliDslConstructionFailureSurfacesTest.kt",
            "dev/rubentxu/pipeline/v2/application/CliNonCanonicalInMemoryExitsTwoTest.kt",
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
