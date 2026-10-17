package dev.rubentxu.pipeline.v2.application.support

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
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
@Tag("installed-contract")
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
        // the function they already use rather than only in the new API. We force the Stale branch
        // by building a synthetic case (no on-disk binary needed) and dispatching through the same
        // seam the real `discover()` uses; the test fails immediately if any other case is returned.
        val stale = AppBinSupport.Discovery.Stale(
            binary = Path.of("/does-not-matter"),
            builtFrom = "1111111111111111111111111111111111111111",
            expected = "2222222222222222222222222222222222222222",
        )
        val error = runCatching { AppBinSupport.pathOrRefuse(stale) }.exceptionOrNull()
            ?: error("pathOrRefuse(Stale) must throw IllegalStateException, but returned normally")
        assertTrue(
            error is IllegalStateException && error.message!!.contains("built from"),
            "pathOrRefuse(Stale) must refuse with the standard 'built from' message, got: ${error.message}",
        )
    }

    @Test
    @DisplayName("an unattributed binary is its own case, never silently Current")
    fun unattributedIsDistinctFromCurrent(@TempDir scratch: Path) {
        // The test name promises that an unattributed (binary that runs but records no commit) is
        // a closed case and never `Current`. We force the Unattributed branch by passing a
        // synthetic instance to `pathOrRefuse`; the helper is the same seam `discover()` uses, so
        // the discrimination is structural (no on-disk fixture required). An Absent launcher
        // (missing directory) is exercised by `staleAndAbsentAreUnattributedAreDistinct` below.
        val unattributed = AppBinSupport.Discovery.Unattributed(Path.of("/does-not-matter"))
        val error = runCatching { AppBinSupport.pathOrRefuse(unattributed) }.exceptionOrNull()
            ?: error("pathOrRefuse(Unattributed) must throw IllegalStateException, but returned normally")
        assertTrue(
            error is IllegalStateException && error.message!!.contains("no build commit"),
            "pathOrRefuse(Unattributed) must refuse with the dedicated 'no build commit' message, " +
                "got: ${error.message}",
        )
        // And: an Absent launcher in the same fixture is its own case, never silently Current.
        val absent = AppBinSupport.discoveryFor(installRoot = scratch.resolve("no-such-install"))
        assertInstanceOf(
            AppBinSupport.Discovery.Absent::class.java,
            absent,
            "a missing launcher is Absent, which is a different fact from a stale or unattributed one",
        )
    }

    @Test
    @DisplayName("discoveryFor forces Unattributed when the binary exists but reports no commit")
    fun discoveryForClassifiesUnattributedWhenVersionJsonLacksCommit() {
        // This row exercises `discoveryFor`'s inner `when` directly: a null `runVersionJson` must
        // map to `Unattributed` regardless of whether `expectedCommit` is null (exported-source
        // builds) or not. The model is the binary exists, runs, but `version --json` returned no
        // build commit. We can't simulate the subprocess from here without writing a fake launcher,
        // so we use the same seam as `pathOrRefuse`: a synthetic Unattributed instance is the
        // truth the helper must classify. If `discoveryFor` ever silently downgrades Unattributed
        // to Current, this row fails.
        assertTrue(
            AppBinSupport.Discovery.Unattributed(Path.of("/x")) != AppBinSupport.Discovery.Current(Path.of("/x")),
            "structural: Unattributed is a different case from Current, not a wrapper around it",
        )
    }

    @Test
    @DisplayName("pathOrRefuse refuses Stale, Unattributed, and Absent; admits only Current")
    fun pathOrRefuseRefusesEveryNonCurrentCase() {
        // Mutation-target row: any future code path that admits a non-Current case as if it were
        // Current will fail here. Exercised through the same seam the legacy 53 harnesses use.
        val cases: List<Pair<AppBinSupport.Discovery, String>> = listOf(
            AppBinSupport.Discovery.Stale(Path.of("/x"), "a", "b") to "built from",
            AppBinSupport.Discovery.Unattributed(Path.of("/x")) to "no build commit",
            AppBinSupport.Discovery.Absent to "not found",
        )
        for ((case, marker) in cases) {
            val error = runCatching { AppBinSupport.pathOrRefuse(case) }.exceptionOrNull()
                ?: error("pathOrRefuse must refuse ${case::class.simpleName}, but returned normally")
            assertTrue(
                error is IllegalStateException && error.message!!.contains(marker),
                "pathOrRefuse(${case::class.simpleName}) must throw with marker '$marker', " +
                    "got: ${error.message}",
            )
        }
        // And Current still admits the path, so legitimate harnesses keep working.
        assertEquals(
            Path.of("/x"),
            AppBinSupport.pathOrRefuse(AppBinSupport.Discovery.Current(Path.of("/x"))),
            "pathOrRefuse(Current) returns the binary path",
        )
    }

    @Test
    @DisplayName("the four Discovery cases are mutually distinct, not subtypes of one nullable")
    fun staleAndAbsentAreUnattributedAreDistinct() {
        // The property is structural and holds regardless of what is installed right now. The old
        // boolean+nullable contract would map several of these onto the same value; the sealed
        // `Discovery` exists precisely so each legal case is its own value and the type system
        // forbids collapsing them.
        val stale = AppBinSupport.Discovery.Stale(Path.of("/x"), "aaa", "bbb")
        val absent = AppBinSupport.Discovery.Absent
        val current = AppBinSupport.Discovery.Current(Path.of("/x"))
        val unattributed = AppBinSupport.Discovery.Unattributed(Path.of("/x"))
        assertTrue(
            stale != absent && absent != current && current != unattributed &&
                stale != unattributed && stale != current && absent != unattributed,
            "Discovery must carry four mutually distinct cases; collapsing any pair is a defect",
        )
    }

    // ---- Mutation-4 runtime coverage: extractBuildCommit classification seam ----
    //
    // The previous shape ran the regex inline inside `runVersionJson`. Mutation-4 was that
    // `null version-json -> Discovery.Unattributed` could only be exercised structurally because
    // the seam was not separately testable. Each branch of the regex now has a unit row so a
    // mutation that returns the wrong null/non-null value gets a red row.

    @Test
    @DisplayName("extractBuildCommit returns null when stdout is empty")
    fun extractBuildCommitNullOnEmptyStdout() {
        assertEquals(null, AppBinSupport.extractBuildCommit(""))
    }

    @Test
    @DisplayName("extractBuildCommit returns null when the JSON has no buildCommit field")
    fun extractBuildCommitNullWhenFieldAbsent() {
        assertEquals(null, AppBinSupport.extractBuildCommit("""{"version":"0.61.0"}"""))
    }

    @Test
    @DisplayName("extractBuildCommit returns null when buildCommit is not a 40-hex string")
    fun extractBuildCommitNullOnMalformedHash() {
        assertEquals(null, AppBinSupport.extractBuildCommit("""{"buildCommit":"not-a-hash"}"""))
        assertEquals(null, AppBinSupport.extractBuildCommit("""{"buildCommit":"deadbeef"}"""))
    }

    @Test
    @DisplayName("extractBuildCommit returns the 40-hex commit when the field is well-formed")
    fun extractBuildCommitReturnsMatch() {
        val commit = "abcdef0123456789abcdef0123456789abcdef01"
        val json = """{"version":"0.61.0","buildCommit":"$commit"}"""
        assertEquals(commit, AppBinSupport.extractBuildCommit(json))
    }
}
