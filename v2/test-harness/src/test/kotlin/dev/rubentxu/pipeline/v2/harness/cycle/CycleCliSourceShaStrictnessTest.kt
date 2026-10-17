package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v7 (cycle CLI strict-validate, 2026-10-17): the
 * previous shape of [CycleCli]'s `--source-sha` argument
 * accepted any String. A short or non-hex value silently
 * never matched the manifest's 40-hex `boundCommit` and
 * the cycle was refused on `OtherCommit` — the wrong
 * reason. The CLI now strict-validates the format at
 * the argument boundary, mirroring the carrier-level
 * checks the rest of the cycle package already
 * enforces.
 *
 * The pinned claim: the cycle CLI's `parseArgs` function
 * MUST validate `--source-sha` is 40-char lowercase hex
 * before assigning it to the `sourceSha` field. A
 * regression to "accept any String" leaves the format
 * error to the runner, which surfaces it as
 * `OtherCommit` — the wrong typed case.
 */
class CycleCliSourceShaStrictnessTest {

    private val cycleCliPath: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/cycle/CycleCli.kt")
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `CycleCli parseArgs validates --source-sha is 40-char lowercase hex`() {
        if (!Files.isRegularFile(cycleCliPath)) {
            error("CycleCli.kt not found at $cycleCliPath")
        }
        val text = cycleCliPath.readText()
        // The previous shape was
        //   "--source-sha" -> {
        //       require(i + 1 < args.size) { "--source-sha requires a value" }
        //       sourceSha = args[i + 1]
        //       i += 2
        //   }
        // A regression to this shape (assigning `args[i + 1]`
        // directly to `sourceSha` with no format check) is
        // the forbidden pattern. The new shape calls a
        // `requireSha1("--source-sha", args[i + 1])` helper
        // (or equivalent strict format check) between the
        // "requires a value" require and the assignment.
        val hasStrictSourceShaCheck = text.contains("requireSha1(\"--source-sha\"") ||
            text.contains("requireSha1(\"--source-sha\",")
        assertTrue(
            hasStrictSourceShaCheck,
            "BQ4 v7 fitness: CycleCli.parseArgs MUST validate --source-sha as " +
                "40-char lowercase hex at the argument boundary, not at the runner. " +
                "A regression to the previous shape (accepting any String) lets a " +
                "short or non-hex value silently never match the manifest's " +
                "boundCommit and the cycle is refused on OtherCommit — the wrong " +
                "typed case. The expected pattern is a `requireSha1(\"--source-sha\", " +
                "args[i + 1])` call between the 'requires a value' require and the " +
                "assignment.",
        )
    }
}
