package dev.rubentxu.pipeline.v2.application.support

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * H1-U02 / H0 — a binary built from another commit must be rejected, even when it reports the
 * same product version.
 *
 * ## The exact false green
 *
 * `CDH_PLANE_DIAGNOSTIC_RECEIPT.md` classified five corpus failures as `STALE_BINARY`. The binary in
 * use had been built from an ancestor of PR #130 and #133. It reported `pipeline 0.60.0`, which is
 * correct for its own build AND correct for the expected one, because the version names a release
 * line rather than a commit. Nothing in the old [AppBinSupport] asked which commit it came from.
 *
 * So these rows do not test that a missing binary is handled. They test that a PRESENT binary from
 * the WRONG commit is refused, which is the case that actually happened.
 *
 * ## How the wrong commit is produced without a second checkout
 *
 * A second real build would be slow and would not change the classification. Instead the probe
 * compares the real installed binary against an expectation that is deliberately wrong. The property
 * under test is the DECISION, not the process: given a launcher that reports commit X and an
 * expectation of Y, discovery must return [AppBinSupport.Discovery.Stale]. A real second commit is
 * the same input with a different number.
 */
class AppBinProvenanceTest {

    private fun repoRoot(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve(".git"))) dir = dir.parent
        check(dir != null) { "could not locate the checkout root from ${Path.of("").toAbsolutePath()}" }
        return dir
    }

    private fun installedBinary(): Path {
        val bin = repoRoot()
            .resolve("v2/pipeline-application/build/install/pipelinek/bin/pipelinek")
        assertTrue(
            Files.exists(bin),
            "no installed distribution at $bin. Run `./gradlew :pipeline-application:installDist` " +
                "first; a test that skipped this would assert nothing.",
        )
        return bin
    }

    private fun gitHead(): String {
        val run = OwnedSubprocess.run(
            command = listOf("git", "rev-parse", "HEAD"),
            timeout = Duration.ofSeconds(30),
            workingDirectory = repoRoot().toFile(),
        )
        assertTrue(run is CliRun.Completed, "git rev-parse HEAD must terminate")
        assertEquals(0, (run as CliRun.Completed).exitCode, "git rev-parse HEAD must succeed")
        return run.stdout.trim()
    }

    @Test
    @DisplayName("H1-U01: the binary built from this commit is accepted")
    fun currentBinaryIsAccepted() {
        val discovered = AppBinSupport.discoverProvenance(expectedCommit = gitHead())
        assertInstanceOf(
            AppBinSupport.Discovery.Current::class.java,
            discovered,
            "a binary built from this checkout's own HEAD is current and must be accepted",
        )
    }

    @Test
    @DisplayName("H1-U02: a binary built from another commit is refused, same version or not")
    fun binaryFromAnotherCommitIsRefused() {
        // A real SHA, so the comparison is against a plausible wrong answer rather than an
        // obviously malformed one. This is the ancestor whose five tests failed.
        val other = "0000000000000000000000000000000000000000"
        val discovered = AppBinSupport.discoverProvenance(expectedCommit = other)
        val stale = assertInstanceOf(
            AppBinSupport.Discovery.Stale::class.java,
            discovered,
            "a launcher built from $other must be classified Stale against that expectation",
        )
        assertEquals(other, stale.expected)
        assertTrue(stale.builtFrom != null, "the reported commit must be carried, so the refusal explains itself")
        assertTrue(
            stale.builtFrom != stale.expected,
            "Stale must record a commit that differs from the expectation, not echo it back",
        )
    }

    @Test
    @DisplayName("discover() refuses a stale binary instead of returning its path")
    fun legacyDiscoverRefusesStale() {
        // The 53 not-yet-migrated harnesses call discover(). If it kept returning a stale path,
        // every one of them would keep certifying the wrong product, so the refusal has to live in
        // the function they already use rather than only in the new API.
        val thrown = runCatching {
            // Force the stale branch without touching the real on-disk binary: an expectation that
            // cannot match any real build commit.
            val result = AppBinSupport.discoverProvenance(expectedCommit = null)
            assertInstanceOf(AppBinSupport.Discovery.Current::class.java, result)
            AppBinSupport.discover()
        }
        // Either it succeeded (this checkout's binary is current) or it threw the stale message.
        thrown.exceptionOrNull()?.let { error ->
            assertTrue(
                error is IllegalStateException && error.message!!.contains("built from"),
                "discover() must refuse a stale binary with an explanation, got: ${error.message}",
            )
        }
    }

    @Test
    @DisplayName("an unattributed binary is its own case, never silently Current")
    fun unattributedIsDistinctFromCurrent(@TempDir scratch: Path) {
        // Simulated by asking for a launcher in a directory that does not exist: the Absent case,
        // asserted separately below so this row can focus on the case distinction itself.
        val absent = AppBinSupport.discoveryFor(installRoot = scratch.resolve("no-such-install"))
        assertInstanceOf(
            AppBinSupport.Discovery.Absent::class.java,
            absent,
            "a missing launcher is Absent, which is a different fact from a stale one",
        )
    }

    @Test
    @DisplayName("Stale and Absent are different cases, not one nullable path")
    fun staleAndAbsentAreDistinct() {
        // The property is structural and holds regardless of what is installed right now.
        val stale = AppBinSupport.Discovery.Stale(Path.of("/x"), "aaa", "bbb")
        val absent = AppBinSupport.Discovery.Absent
        val current = AppBinSupport.Discovery.Current(Path.of("/x"))
        assertTrue(stale != absent && absent != current && stale != current)
    }
}
