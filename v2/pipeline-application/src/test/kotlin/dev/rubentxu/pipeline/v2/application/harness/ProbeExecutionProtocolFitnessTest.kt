package dev.rubentxu.pipeline.v2.application.harness

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The product-side [dev.rubentxu.pipeline.v2.harness.cycle.ProbeExecutor]
 * speaks the harness's `execute`/`persist` protocol. This fitness
 * pins that this adapter speaks it in TYPES, never in strings.
 *
 * ## What this prevents
 *
 * `PendingEvidenceSentinelFitnessTest` (in `:test-harness`) already
 * guards the same defect class — the `ProbeOutcome.Verified`
 * placeholder carrying a free-form `<pending>` string that the
 * executor matches back out — but it scans ONLY
 * `test-harness/.../harness/cycle`. That scoping was correct for
 * the harness module and wrong for the whole protocol: the
 * adapter in `:pipeline-application` implements the same protocol
 * and was outside the guard's reach.
 *
 * The gap was not theoretical. Commit `c010fb51` replaced the
 * sentinel with a typed `data object NeedsPersist` and deleted
 * `ProbeExecutor.isPendingOutcome` from the harness. Nothing in
 * the product module was recompiled, so `:pipeline-application`
 * stopped compiling entirely and the breakage was discovered only
 * when an unrelated task happened to build that module. A protocol
 * change that every implementor must be checked against cannot
 * live with a guard that sees one implementor.
 *
 * ## Why a source scan rather than a behavioural assertion
 *
 * Both defects are **absences of a typed shape**: "no placeholder
 * string", "no comparison against the wire field". The behavioural
 * rows in `InProcessProductProbeExecutorTest` prove the cycle
 * still closes; they cannot tell a `Verified(realPath)` obtained
 * through `persist` from one fabricated in place and wrapped
 * afterwards, because both end with a passing cycle. Naming the
 * forbidden token is the only assertion that discriminates those
 * two worlds.
 *
 * ## What it does NOT say
 *
 * It does not forbid the string `"HF1"` from existing in this
 * package — building a manifest fixture, for instance, legitimately
 * contains the wire value. It forbids COMPARING against
 * `CycleManifest.expectedHF`, the wire field, instead of reading
 * the typed view `expectedFidelity`.
 *
 * Comments are stripped before the scan, so the KDoc on the
 * executor that narrates the historical defect (and necessarily
 * quotes `isPendingOutcome` and `<pending>`) does not match.
 */
class ProbeExecutionProtocolFitnessTest {

    private val adapterRoots: List<Path> by lazy {
        val cwd = Path.of("").toAbsolutePath()
        // SCOPED to the product-side harness adapter package. The
        // harness's own guard covers its side of the protocol; this
        // one covers the implementor that guard could not reach.
        //
        // Both source sets are searched because the guarantee is a
        // property of the ADAPTER, not of where it is compiled. When
        // the adapter shipped in `src/main` (ADR-PK-DF-002 H0/R1,
        // 2026-10-17) this guard named only `src/main`; moving the
        // file to `src/test` — where its only callers always were —
        // turned a real guarantee into a hard `error()` with no
        // adapter left to inspect.
        listOf("src/main", "src/test").map {
            cwd.resolve(it).resolve("kotlin/dev/rubentxu/pipeline/v2/application/harness")
        }
    }

    private fun adapterSources(): List<Path> {
        val present = adapterRoots.filter { Files.isDirectory(it) }
        // Fail-closed on DISAPPEARANCE, which is what this guard is
        // for: an adapter package that exists in no source set means
        // the implementor was deleted, and a guard that then scans
        // zero files would pass vacuously.
        if (present.isEmpty()) {
            error(
                "pipeline-application adapter source not found in any of " +
                    "${adapterRoots.joinToString { it.toString() }}",
            )
        }
        return present.flatMap { root ->
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
            }
        }
            // Exclude THIS file. It declares the forbidden patterns as
            // string literals, so it necessarily contains every token
            // it forbids; comment-stripping does not touch string
            // literals. Before the adapter moved into `src/test` the
            // fitness sat outside the scanned package and the
            // self-trip was impossible — widening the scan to cover the
            // adapter's new home created it.
            .filterNot { it.fileName.toString() == "ProbeExecutionProtocolFitnessTest.kt" }
            .also { found ->
                // Fail-closed on a VACUOUS scan. This guard's own
                // directory survives even when the adapter does not:
                // the guard itself lives in it. So "a directory
                // existed" is not evidence that there was anything to
                // inspect, and asserting over zero files passes
                // vacuously -- a green that proves nothing. The
                // adapter having been deleted is exactly the event
                // that must be refused.
                if (found.isEmpty()) {
                    error(
                        "pipeline-application adapter sources are absent: " +
                            "${present.joinToString { it.toString() }} contains no adapter " +
                            "file other than this guard",
                    )
                }
            }
    }

    /** Comments removed, so the file's own historical KDoc cannot self-trigger. */
    private fun codeOnly(sources: List<Path>): List<Pair<Path, String>> {
        val blockComment = Regex("""/\*[\s\S]*?\*/""")
        val lineComment = Regex("""//[^\n]*""")
        return sources.map { path ->
            val stripped = blockComment.replace(path.readText(), " ")
                .let { lineComment.replace(it, " ") }
            path to stripped
        }
    }

    @Test
    fun `the product probe adapter carries no pending-evidence sentinel`() {
        // Same shapes the harness guard forbids, including the
        // case variants: a typo on either side of the protocol is
        // the failure mode, and casing is how a typo happens.
        //
        // NOTE the pattern differs from the harness guard's on
        // purpose, and the difference is a hole in that guard.
        // `["<]([Pp]ending|PENDING)["<]` requires a delimiter on
        // BOTH sides of the word, so it does NOT match the
        // canonical literal `"<pending>"` — there the delimiters
        // are `"` and `<`, which are adjacent, and the pattern
        // demands `<pending<`. That guard catches `"pending"` and
        // `<pending"` but never the shape its own KDoc names, so
        // an inline reintroduction inside the harness would pass.
        // Anchoring on the token itself (`<pending>`) is what the
        // intent was.
        val forbidden = Regex(
            """(?i)<pending>|["']pending["']|PENDING_EVIDENCE_REF|isPendingOutcome""",
        )
        val offenders = mutableListOf<String>()
        for ((path, code) in codeOnly(adapterSources())) {
            for (match in forbidden.findAll(code)) {
                val line = code.take(match.range.first).count { it == '\n' } + 1
                offenders += "${path.fileName}:$line -> ${match.value}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "the product probe adapter must speak the execute/persist protocol with the typed " +
                "'NeedsPersist' marker, not with a Verified placeholder string. A placeholder is a " +
                "dual emitter/parser surface: nothing consumes the value, so a reader can only " +
                "misread it as a real evidence location, and a typo routes the outcome to the wrong " +
                "branch. This is the exact defect that left :pipeline-application uncompilable after " +
                "c010fb51, unguarded because the harness fitness cannot see this module.\n" +
                "offenders:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the product probe adapter never compares the fidelity wire field`() {
        // `CycleManifest.expectedHF` is the serialised String;
        // `expectedFidelity` is its typed view. Comparing the wire
        // field against a literal makes the branch depend on the
        // spelling of the JSON, which is the same dual-emitter
        // class one layer over: `"hf1"` in a hand-edited manifest
        // would fall through to the HF0 delegation path and be
        // answered by a different executor than the manifest asked
        // for. (`CycleManifest.init` already constrains the value,
        // but the adapter must not restate the constraint to read
        // it.)
        val forbidden = Regex("""expectedHF\s*[!=]=""")
        val offenders = mutableListOf<String>()
        for ((path, code) in codeOnly(adapterSources())) {
            for (match in forbidden.findAll(code)) {
                val line = code.take(match.range.first).count { it == '\n' } + 1
                offenders += "${path.fileName}:$line -> ${match.value}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "read the typed view 'CycleManifest.expectedFidelity' and match on the Fidelity enum; " +
                "comparing the serialised 'expectedHF' String against a literal makes the routing " +
                "depend on the manifest's spelling rather than on its meaning.\n" +
                "offenders:\n" + offenders.joinToString("\n"),
        )
    }
}