package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 P0 (HITO 1 "errores veraces", 2026-10-17): the
 * previous shape of [CycleCli] defaulted `--source-sha`
 * to the 40-zeros sentinel (`"0".repeat(40)`) and
 * defaulted `--cycle-dir` to a path inside the
 * producer's Git working tree (via
 * [CycleCli.defaultCycleDir]). Both defaults
 * produced false identities: the zero SHA reached
 * the runner, was compared by string equality against
 * the manifest's 40-hex `boundCommit`, and produced
 * `OtherCommit` — a refusal with the wrong reason.
 * The in-tree default cycle dir was caught by
 * [CycleCli.ensureOutsideGitRepo] and refused with
 * "refuses to write inside Git" — a confusing
 * Git-conflict refusal for an operator who had simply
 * omitted `--cycle-dir`. The audit's "errores
 * veraces" bullet is the source.
 *
 * The pinned claims:
 *
 * 1. [CycleCli.parseArgs] refuses a missing
 *    `--source-sha` at the argument boundary (exits 2
 *    with a clear message) and never constructs a
 *    [CycleCli.CliArgs] with a zero placeholder. A
 *    regression to `"0".repeat(40)` is the forbidden
 *    pattern.
 *
 * 2. [CycleCli.parseArgs] refuses a missing
 *    `--cycle-dir` at the argument boundary. A
 *    regression to a `defaultCycleDir()` fallback is
 *    the forbidden pattern.
 *
 * 3. The `CliArgs` carrier exposes `cycleDir: Path`
 *    and `sourceSha: String` as non-nullable, so the
 *    typed call site cannot construct a third
 *    "synthesised placeholder" state.
 */
class CycleCliRequiredArgsFitnessTest {

    private val cycleCliPath: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/cycle/CycleCli.kt")
    }

    @OptIn(ExperimentalPathApi::class)
    private fun readSource(): String {
        if (!Files.isRegularFile(cycleCliPath)) {
            error("CycleCli.kt not found at $cycleCliPath")
        }
        return cycleCliPath.readText()
    }

    @Test
    fun `CycleCli parseArgs refuses a missing --source-sha at the argument boundary`() {
        val text = readSource()
        // The previous shape was
        //   var sourceSha: String = "0".repeat(40)
        // A regression to this default sentinel is the
        // forbidden pattern. The new shape declares
        // `sourceSha: String?` and refuses null before
        // constructing the carrier.
        val hasZeroSentinel = Regex("""sourceSha\s*:\s*String\s*=\s*"0"\.repeat\(40\)""")
        assertTrue(
            !hasZeroSentinel.containsMatchIn(text),
            "BQ4 P0 fitness: CycleCli.parseArgs MUST NOT default --source-sha to " +
                "the 40-zeros sentinel. A missing --source-sha must be refused at " +
                "the argument boundary with an explicit error (exit 2) and never " +
                "constructed as a zero placeholder. A regression to `\"0\".repeat(40)` " +
                "lets the cycle reach the runner with a sentinel source SHA, which " +
                "compares by string equality against the manifest's boundCommit and " +
                "is refused on `OtherCommit` — the wrong typed case (the operator's " +
                "\"I forgot to pass --source-sha\" looks like \"the source SHA is not " +
                "the bound one\", which is always true for the zero sentinel).",
        )
        // The refusal must be visible in the source. The
        // parseArgs function returns null when
        // sourceSha is null.
        val hasSourceShaRefusal = text.contains("--source-sha is required")
        assertTrue(
            hasSourceShaRefusal,
            "BQ4 P0 fitness: CycleCli.parseArgs MUST emit an explicit " +
                "\"--source-sha is required\" refusal (exit 2) when the flag is " +
                "absent. The current code does this in the post-while null check.",
        )
    }

    @Test
    fun `CycleCli parseArgs refuses a missing --cycle-dir at the argument boundary`() {
        val text = readSource()
        // The previous shape was a `defaultCycleDir()`
        // fallback that returned
        // `workspace/.pipelinek-cycles/<id>` — a path
        // inside the producer's Git working tree. The
        // helper is now removed.
        val hasDefaultCycleDirFn = Regex("""fun\s+defaultCycleDir\s*\(""")
        assertTrue(
            !hasDefaultCycleDirFn.containsMatchIn(text),
            "BQ4 P0 fitness: CycleCli MUST NOT have a `defaultCycleDir()` helper. " +
                "The previous helper returned a path inside the producer's Git " +
                "working tree (`workspace/.pipelinek-cycles/<id>`), which the CLI's " +
                "`ensureOutsideGitRepo` check then refused with a confusing " +
                "Git-conflict message for an operator who had simply omitted " +
                "`--cycle-dir`. The helper is removed; a missing flag exits 2 with " +
                "an explicit \"flag required\" message and the typed CliArgs carrier " +
                "is non-nullable for cycleDir.",
        )
        // The refusal must be visible in the source.
        val hasCycleDirRefusal = text.contains("--cycle-dir is required")
        assertTrue(
            hasCycleDirRefusal,
            "BQ4 P0 fitness: CycleCli.parseArgs MUST emit an explicit " +
                "\"--cycle-dir is required\" refusal (exit 2) when the flag is " +
                "absent.",
        )
    }

    @Test
    fun `CycleCli CliArgs carrier exposes cycleDir and sourceSha as non-nullable`() {
        val text = readSource()
        // The carrier must declare
        //   val cycleDir: Path
        //   val sourceSha: String
        // (no `?`). A regression to nullable types is
        // the surface for the synthesised-placeholder
        // state the audit named.
        val hasNullableCycleDir = Regex("""val\s+cycleDir\s*:\s*Path\?\s*,""")
        val hasNullableSourceSha = Regex("""val\s+sourceSha\s*:\s*String\?\s*,""")
        assertTrue(
            !hasNullableCycleDir.containsMatchIn(text),
            "BQ4 P0 fitness: CliArgs.cycleDir MUST be non-nullable. A nullable " +
                "cycleDir is the surface for a third 'synthesised placeholder' " +
                "state — the call site would need to handle null and could fall " +
                "back to a default. The CLI now refuses a missing --cycle-dir at " +
                "parseArgs time and the carrier exposes the typed non-nullable " +
                "value.",
        )
        assertTrue(
            !hasNullableSourceSha.containsMatchIn(text),
            "BQ4 P0 fitness: CliArgs.sourceSha MUST be non-nullable. A nullable " +
                "sourceSha is the surface for a third 'synthesised placeholder' " +
                "state — the call site would need to handle null and could fall " +
                "back to a default. The CLI now refuses a missing --source-sha at " +
                "parseArgs time and the carrier exposes the typed non-nullable " +
                "value.",
        )
    }
}
