package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v7 (gate CLI strict-validate, 2026-10-17): the
 * previous shape of [GateCli]'s `<expected-commit>`
 * argument accepted any String. A short or non-hex
 * value silently never matched the binaries' reported
 * `sourceCommit` and the gate was refused on
 * `StaleBinary` — the wrong typed case. The CLI now
 * strict-validates 40-char lowercase hex at the
 * argument boundary.
 *
 * The pinned claim: the gate CLI's `main(args)` MUST
 * call `requireSha1("expected-commit", ...)` at the
 * argument boundary, after the `args.size == 2`
 * check, before any gate logic starts. A regression
 * to "accept any String" leaves the format error to
 * the gate, which surfaces it as `StaleBinary` — the
 * wrong typed case.
 */
class GateCliSourceShaStrictnessTest {

    private val gateCliPath: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/plan/GateCli.kt")
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `GateCli main validates expected-commit is 40-char lowercase hex`() {
        if (!Files.isRegularFile(gateCliPath)) {
            error("GateCli.kt not found at $gateCliPath")
        }
        val text = gateCliPath.readText()
        val hasStrictExpectedCommitCheck = text.contains("requireSha1(\"expected-commit\"") ||
            text.contains("requireSha1(\"expected-commit\",")
        assertTrue(
            hasStrictExpectedCommitCheck,
            "BQ4 v7 fitness: GateCli.main MUST validate <expected-commit> as " +
                "40-char lowercase hex at the argument boundary, not at the gate. " +
                "A regression to the previous shape (accepting any String) lets a " +
                "short or non-hex value silently never match the binaries' " +
                "sourceCommit and the gate is refused on StaleBinary — the wrong " +
                "typed case.",
        )
    }
}
