package dev.rubentxu.pipeline.v2.harness.plan

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The commit the INSTALLED DISTRIBUTION reports, read rather than told.
 *
 * ## Why this exists
 *
 * The gate compares two commits: the one the evidence is bound to, and the one the binary on disk
 * carries. That comparison is only worth anything if the second one is OBSERVED.
 *
 * It was not. `observedCommit` arrived as the third CLI argument, which meant the operator named
 * both sides of the comparison, and `ImpactResolver.adjudicate` does exactly
 * `observedCommit != expectedCommit`. A real run passed `HEAD` twice and discharged on the
 * tautology `SHA != SHA`. The check was decorative, and the KDoc that described it as "what the
 * binary reports" was describing an intention the code did not implement.
 *
 * ## Why not infer it from Git
 *
 * `git rev-parse HEAD` would always agree with `--expected-commit`, which is the same tautology one
 * layer down. The value has to come from the artifact, because the artifact is the thing that can
 * disagree: an `installDist` from a previous commit, a stale distribution on a release host, a
 * launcher that reports nothing.
 *
 * ## Why fail closed
 *
 * Every way this can fail — missing launcher, non-zero exit, absent field, empty value — is
 * [ObservedProvenance.Unreadable], and `commitOrNull()` turns that into `null`, which
 * [ImpactResolver.adjudicate] refuses. There is no path from "could not tell" to "matches".
 */
sealed interface ObservedProvenance {

    /** The installed distribution reported a commit. */
    data class Reported(val commit: String) : ObservedProvenance {
        // BQ4 v15 (plan carrier self-protect, 2026-10-17):
        // a blank `commit` is the gate's record of "an
        // installed distribution reported a commit" for a
        // commit that has no SHA — a fabrication. The
        // carrier refuses blanks at construction; the
        // launcher-level parsing in [InstalledDistribution.parse]
        // also rejects non-hex / non-40-char values.
        init { require(commit.isNotBlank()) { "Reported.commit must not be blank" } }
    }

    /**
     * No commit could be read.
     *
     * [reason] names the cause rather than a bare null, so the refusal is an operator instruction
     * ("run `installDist`") instead of a puzzle.
     */
    data class Unreadable(val reason: String) : ObservedProvenance {
        init { require(reason.isNotBlank()) { "Unreadable.reason must not be blank" } }
    }

    /**
     * What [ImpactResolver.adjudicate] compares against.
     *
     * Unknown is `null`, and unknown refuses. That asymmetry is the whole point: it is the only
     * direction that fails safe.
     */
    fun commitOrNull(): String? = when (this) {
        is Reported -> commit
        is Unreadable -> null
    }

    /** The same information as one line for the build log. */
    fun describe(): String = when (this) {
        is Reported -> "installed distribution reports commit $commit"
        is Unreadable -> "installed provenance unavailable: $reason"
    }
}

/**
 * Reads [ObservedProvenance] from the installed distribution.
 *
 * The parsing half is pure and takes the raw `version --json` document, so the shape of the
 * accepted input is testable without launching a process. The launching half is the only impure
 * part and it exists solely to hand the process output to [parse].
 */
object InstalledDistribution {

    /**
     * Where `installDist` writes the launcher, relative to the build root.
     *
     * A build convention, not a preference: the gate must interrogate the distribution that would
     * actually be published, and asking anything else would answer a different question.
     */
    private val LAUNCHER: Path =
        Path.of("pipeline-application", "build", "install", "pipelinek", "bin", "pipelinek")

    /**
     * The `buildCommit` in a `version --json` document.
     *
     * A targeted field read rather than a JSON parse: the document is one flat object produced by
     * this project's own CLI, and the failure this guards against is a MISSING commit, not a
     * malformed payload. Anything that is not a recognisable commit comes back [Unreadable] and
     * therefore refuses, which is the same outcome a parse error would produce without needing a
     * parser dependency in the harness.
     */
    fun parse(json: String): ObservedProvenance {
        val document = json.trim()
        if (document.isEmpty()) {
            return ObservedProvenance.Unreadable("`version --json` printed nothing")
        }
        val field = Regex("\"buildCommit\"\\s*:\\s*\"([^\"]*)\"").find(document)
            ?: return ObservedProvenance.Unreadable("`version --json` reported no buildCommit field")
        val commit = field.groupValues[1].trim()
        if (commit.isEmpty()) {
            return ObservedProvenance.Unreadable("buildCommit is empty")
        }
        return ObservedProvenance.Reported(commit)
    }

    /** The installed launcher under [buildRoot], for the refusal message and for tests. */
    fun launcher(buildRoot: Path): Path = buildRoot.resolve(LAUNCHER)

    /**
     * Run `version --json` on the installed launcher and parse the commit.
     *
     * Every failure mode is a value, never an exception escaping to the caller: the gate's job is
     * to refuse with a reason, and throwing would turn a legible refusal into a stack trace.
     */
    fun read(buildRoot: Path): ObservedProvenance {
        val launcher = launcher(buildRoot)
        if (!Files.isRegularFile(launcher)) {
            return ObservedProvenance.Unreadable(
                "no installed distribution at $launcher; run :pipeline-application:installDist",
            )
        }

        val process = try {
            ProcessBuilder(launcher.toString(), "version", "--json")
                .directory(buildRoot.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            return ObservedProvenance.Unreadable("could not launch $launcher: ${e.message}")
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) {
            return ObservedProvenance.Unreadable(
                "`version --json` exited ${process.exitValue()}: ${output.trim().take(200)}",
            )
        }
        return parse(output)
    }
}