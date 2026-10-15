package dev.rubentxu.pipeline.v2.application.support

import java.time.Duration

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Shared `appBin` discovery for UAT harnesses.
 *
 * Extracted from UatDsl001JenkinsFamiliarityTest.kt to avoid duplication
 * across the corpus + compatibility UAT classes.
 */
object AppBinSupport {

    /**
     * Where the installed launcher lives, and whether it is the current one.
     *
     * ## Why this is a closed result and not a Path
     *
     * The original [discover] returned the first executable it found, preferring `pipelinek` and
     * accepting the legacy `pipeline-application` name, and returned it unconditionally. That made
     * every harness in this file depend on whatever happened to be on disk. When PR #130 and #133
     * landed, the harnesses kept running against the distribution built BEFORE those fixes and the
     * five corpus failures were classified `STALE_BINARY` — the harness had no way to know, because
     * nothing asked the binary which commit it came from and nothing compared that to the checkout.
     *
     * So discovery now answers a question that can be wrong, and the answers are distinct states
     * rather than a Path plus a boolean the caller is trusted to check:
     *
     * - [Current]: a launcher exists and its recorded build commit is the checkout's HEAD.
     * - [Stale]: a launcher exists but was built from a different commit. This is the false green,
     *   stated as its own case so a harness cannot treat it as success.
     * - [Absent]: no launcher at all.
     * - [Unattributed]: the launcher exists and works but records no commit, which is a legitimate
     *   working-tree build and still not something to certify.
     */
    sealed interface Discovery {
        data class Current(val binary: Path) : Discovery

        data class Stale(val binary: Path, val builtFrom: String?, val expected: String) : Discovery

        data object Absent : Discovery

        data class Unattributed(val binary: Path) : Discovery
    }

    /**
     * Locate the installed launcher and classify it against [expectedCommit].
     *
     * Default [expectedCommit] is the checkout's own HEAD, resolved with `git rev-parse`. A tree
     * with no Git metadata has no expectation to check against and returns [Discovery.Current] for
     * whatever is present, because there is nothing to compare and refusing would only break
     * exported-source builds that are legitimate.
     */
    fun discoverProvenance(expectedCommit: String? = checkoutHead()): Discovery {
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val moduleDir = if (userDir.fileName?.toString() == "pipeline-application") {
            userDir
        } else {
            userDir.resolve("v2").resolve("pipeline-application")
        }
        return discoveryFor(moduleDir.resolve("build").resolve("install"), expectedCommit)
    }

    /**
     * [discoverProvenance] with the install root supplied, so the Absent and Stale branches are
     * testable without arranging the filesystem. Same decision, no hidden global.
     */
    fun discoveryFor(installRoot: Path, expectedCommit: String? = checkoutHead()): Discovery {
        // WU-LPR-070: the distribution applicationName is "pipelinek". The legacy
        // "pipeline-application" install name is accepted for stale checkouts but the
        // canonical bin is install/pipelinek/bin/pipelinek.
        val bin = listOf("pipelinek", "pipeline-application")
            .map { installRoot.resolve(it).resolve("bin").resolve(it) }
            .firstOrNull { it.toFile().exists() }
            ?: return Discovery.Absent

        // Always classify against the binary's own report. A null from `runVersionJson`
        // means the launcher runs but records no commit; that is its own case and cannot be
        // silently swept under `Current`. A non-null report with no expectation (`expectedCommit
        // == null`, e.g. an exported-source build) is still legitimately `Current` because there
        // is nothing to compare against. A non-null report that mismatches `expectedCommit` is
        // `Stale`. This is the discrimination H1-U02 and the variant tests below force.
        return when (val builtFrom = runVersionJson(bin)) {
            null -> Discovery.Unattributed(bin)
            else ->
                if (expectedCommit == null || builtFrom.equals(expectedCommit, ignoreCase = true)) {
                    Discovery.Current(bin)
                } else {
                    Discovery.Stale(bin, builtFrom, expectedCommit)
                }
        }
    }

    /**
     * The launcher as before, for harnesses that have not yet migrated.
     *
     * Kept so this refactor cannot break the 53 harnesses named in
     * `InstalledDistributionHarnessFitnessTest`'s allowlist: a migration cannot land before the
     * thing it migrates FROM still compiles. New harnesses must call [discoverProvenance], because
     * this function is exactly the mechanism that let a stale binary look current.
     *
     * [discover] delegates to [pathOrRefuse], which is also the seam the discrimination tests
     * attack directly with a synthetic [Discovery] case; this keeps the legacy and the new API
     * agreeing on what counts as refusable.
     */
    fun discover(): Path = pathOrRefuse(discoverProvenance())

    /**
     * Decide whether a [Discovery] may be certified, return its launcher path, or refuse.
     *
     * Extracted so each branch is reachable on its own: an unattributed binary cannot be
     * certified even though it runs, so it lands here alongside [Discovery.Stale] and
     * [Discovery.Absent]. The old inlined [discover] silently returned [Discovery.Unattributed]
     * paths, which is exactly the false-green the sealed [Discovery] exists to prevent.
     */
    fun pathOrRefuse(found: Discovery): Path = when (found) {
        is Discovery.Current -> found.binary
        is Discovery.Stale -> throw IllegalStateException(
            "The installed binary at ${found.binary} was built from ${found.builtFrom}, but " +
                "this checkout is ${found.expected}. Running against it would certify a " +
                "different state of the product. Run ./gradlew :pipeline-application:installDist.",
        )

        is Discovery.Absent -> throw IllegalStateException(
            "Application binary not found under " +
                "${Paths.get(System.getProperty("user.dir"))}. " +
                "Run ./gradlew :pipeline-application:installDist first.",
        )

        is Discovery.Unattributed -> throw IllegalStateException(
            "The installed binary at ${found.binary} reports no build commit. " +
                "This is a legitimate working-tree build but cannot be used to certify " +
                "product behavior. Run ./gradlew :pipeline-application:installDist from this " +
                "checkout so the binary is tagged with this checkout's HEAD.",
        )
    }

    /**
     * The `buildCommit` the launcher reports, or null when it reports none.
     *
     * Through [OwnedSubprocess], not a bare ProcessBuilder: this launches the INSTALLED
     * DISTRIBUTION, which is exactly the shape `InstalledDistributionHarnessFitnessTest` requires to
     * drain before waiting. A bare reader here would reintroduce the pipe deadlock that fitness law
     * exists to prevent, and being right about provenance is no excuse for hanging.
     */
    private fun runVersionJson(binary: Path): String? =
        when (val run = OwnedSubprocess.run(listOf(binary.toString(), "version", "--json"), Duration.ofSeconds(120))) {
            is CliRun.Completed ->
                if (run.exitCode == 0) {
                    Regex("\"buildCommit\":\"([0-9a-f]{40})\"").find(run.stdout)?.groupValues?.get(1)
                } else {
                    null
                }

            is CliRun.TimedOut, is CliRun.LaunchFailed -> null
        }

    /** This checkout's HEAD, or null when there is no Git metadata to compare against. */
    private fun checkoutHead(): String? =
        runCatching {
            var dir: Path? = Paths.get("").toAbsolutePath()
            while (dir != null && !dir.resolve(".git").toFile().exists()) dir = dir.parent
            val root = dir ?: return@runCatching null
            // OwnedSubprocess even for `git`: the fitness law keys on the shape, and a git call
            // that hung would leave the whole suite hanging instead of failing one row.
            val run = OwnedSubprocess.run(
                command = listOf("git", "rev-parse", "HEAD"),
                timeout = Duration.ofSeconds(30),
                workingDirectory = root.toFile(),
            )
            if (run !is CliRun.Completed || run.exitCode != 0) return@runCatching null
            run.stdout.trim().takeIf { it.matches(Regex("[0-9a-f]{40}")) }
        }.getOrNull()
}
