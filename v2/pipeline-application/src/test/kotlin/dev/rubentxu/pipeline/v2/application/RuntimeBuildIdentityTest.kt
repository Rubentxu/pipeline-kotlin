package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H1.4 — a product version is not provenance, and this suite is the proof that the
 * distinction now exists.
 *
 * ## The defect this exists to prevent
 *
 * The five corpus failures recorded in `docs/v2/07-uat/CDH_PLANE_DIAGNOSTIC_RECEIPT.md`
 * were classified `STALE_BINARY` after measurement. The binary used was built from an
 * ancestor of PR #130 and #133. Nothing about that binary would have revealed it: it
 * reported the correct product version, because every commit on the release line reports
 * the same one.
 *
 * So the specific false green this kills is: **two different binaries reporting the same
 * version, and no tool able to tell which is which.** A harness that asks only
 * `pipeline version` cannot detect it, which is why asking is not enough and the answer
 * has to carry the commit.
 *
 * ## Fidelity
 *
 * These are HF0 pure-contract rows over [RuntimeApiVersion], which is the ONE authority for
 * both values (WU-LPR-071). The provenance of the REAL artifact is covered by
 * `BuildProvenanceIsReportedByTheInstalledBinaryTest`, which launches the installed
 * executable; a unit test that only reads the same object the runtime reads cannot prove the
 * shipped bytes answer the question.
 */
class RuntimeBuildIdentityTest {

    @Test
    fun `the build records the commit so the two identities are separable`() {
        // The production build wires this; see generateVersionResource in
        // pipeline-application/build.gradle.kts.
        val commit = RuntimeApiVersion.readBuildCommit()
        assertTrue(
            commit != null && commit.matches(Regex("[0-9a-f]{40}")),
            "the generated resource must carry a full 40-hex commit so a binary can name the " +
                "code it came from; got '$commit'. A truncated or non-SHA value would look like " +
                "provenance without being any.",
        )
    }

    @Test
    fun `product version and build commit are separate facts, not one string`() {
        // The whole point: 0.60.0 identifies a release LINE, and many commits report it.
        // Overloading the two would re-create the ambiguity this removes.
        val version = RuntimeApiVersion.readImplementationVersion()
        val commit = RuntimeApiVersion.readBuildCommit()
        assertTrue(version != null && version.matches(Regex("[0-9]+.[0-9]+.[0-9]+")))
        assertTrue(commit != null && !version!!.contains(commit!!))
    }

    @Test
    fun `build identity is a closed result rather than a nullable string`() {
        val identity = RuntimeApiVersion.buildIdentity()
        assertInstanceOf(RuntimeApiVersion.BuildIdentity.Recorded::class.java, identity)
        // Narrow explicitly: the point of the sealed type is that the Recorded case CARRIES the
        // commit, so a consumer never has to go back for the nullable string it replaced.
        assertEquals(RuntimeApiVersion.readBuildCommit(), (identity as RuntimeApiVersion.BuildIdentity.Recorded).commit)
    }

    @Test
    fun `an absent commit is a distinct case, never a fabricated value`() {
        // The property is about the TYPE, so it holds whether or not this build recorded one:
        // Absent must be constructible and must not be confusable with Recorded("").
        val absent = RuntimeApiVersion.BuildIdentity.Absent
        val blank = RuntimeApiVersion.BuildIdentity.Recorded("")
        assertNotEquals(absent, blank)
        assertInstanceOf(RuntimeApiVersion.BuildIdentity.Absent::class.java, absent)
    }
}

/**
 * HF2 — the provenance must come out of the INSTALLED executable, not from a classpath the
 * test controls.
 *
 * This is the row that would have caught the original defect end to end. It runs
 * `installDist`'s launcher and asks it which commit it is, then compares that against the
 * checkout's own HEAD. If the build stopped writing the commit, or the resource stopped
 * being packaged, this fails where the unit test above would still pass.
 */
class BuildProvenanceIsReportedByTheInstalledBinaryTest {

    private fun installedBinary(repoRoot: Path): Path {
        val launcher = repoRoot.resolve("v2/pipeline-application/build/install/pipelinek/bin/pipelinek")
        assertTrue(
            Files.exists(launcher),
            "the installed executable is missing at $launcher. Run `./gradlew installDist` first; " +
                "a test that silently falls back to MainKt on the test classpath certifies the " +
                "wrong binary.",
        )
        return launcher
    }

    private fun runVersion(binary: Path, vararg extra: String): Pair<Int, String> =
        when (val run = dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess.run(listOf(binary.toString(), "version") + extra, Duration.ofSeconds(120))) {
            is dev.rubentxu.pipeline.v2.application.support.CliRun.Completed ->
                run.exitCode to run.stdout.trim()

            is dev.rubentxu.pipeline.v2.application.support.CliRun.TimedOut ->
                throw AssertionError("`version` must terminate; it timed out")

            is dev.rubentxu.pipeline.v2.application.support.CliRun.LaunchFailed ->
                throw AssertionError("`version` must launch", run.cause)
        }

    private fun repoRoot(): Path {
        // The module directory is v2/pipeline-application; the checkout root is two levels up.
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.parent
        }
        assertTrue(dir != null, "could not locate the checkout root from ${Path.of("").toAbsolutePath()}")
        return dir!!
    }

    @Test
    fun `the installed binary names the commit it was built from`() {
        val binary = installedBinary(repoRoot())
        val (code, output) = runVersion(binary, "--json")
        assertEquals(0, code, "`version --json` must succeed; got: $output")

        val commit = Regex("\"buildCommit\":\"([0-9a-f]{40})\"").find(output)?.groupValues?.get(1)
        assertTrue(
            commit != null,
            "the installed binary reported no full commit, so a harness cannot tell it apart " +
                "from any other build of the same version. Output was: $output",
        )

        val head =
            dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess.run(
                command = listOf("git", "rev-parse", "HEAD"),
                timeout = Duration.ofSeconds(30),
                workingDirectory = repoRoot().toFile(),
            ).let { run ->
                assertTrue(
                    run is dev.rubentxu.pipeline.v2.application.support.CliRun.Completed &&
                        run.exitCode == 0,
                    "git rev-parse HEAD must succeed",
                )
                (run as dev.rubentxu.pipeline.v2.application.support.CliRun.Completed).stdout.trim()
            }
        assertEquals(
            head,
            commit,
            "the binary's recorded commit must be the checkout it was built from; a mismatch is " +
                "exactly the stale-binary condition this row exists to detect",
        )
    }

    @Test
    fun `the human readable version is unchanged`() {
        // H1.4 adds provenance; it must not alter the existing contract.
        val binary = installedBinary(repoRoot())
        val (code, output) = runVersion(binary)
        assertEquals(0, code)
        assertTrue(
            output.matches(Regex("pipeline [0-9]+.[0-9]+.[0-9]+")),
            "the human form must stay `pipeline MAJOR.MINOR.PATCH`; got: $output",
        )
    }
}
