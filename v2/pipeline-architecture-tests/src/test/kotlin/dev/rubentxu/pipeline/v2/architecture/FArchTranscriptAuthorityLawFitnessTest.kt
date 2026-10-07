package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * OBS-B — the two laws that make "the Output Plane is the only transcript authority" mechanical.
 *
 * ## Why these, and why now
 *
 * `console.log` was a staging buffer that `M1_P2_SINGLE_BYTE_AUTHORITY_RECEIPT.md` already declared
 * would disappear "the moment the wrapper grows a pipe protocol". OBS-B2 is that moment. Removing
 * a surface without a guard leaves the next change free to add it back, and the field it carried is
 * exactly the kind that survives removal: a nullable `String?` in a domain type reads as
 * "sometimes there is a transcript here", which is an invitation, not a prohibition.
 *
 * So both halves are pinned: the terminal may not carry transcript bytes, and the canonical shell
 * path may not name the staging file. Neither row claims that live output works — that is
 * behavioural and belongs to `ObsBLiveOutputIngressTest`.
 *
 * Comments are stripped before every scan, for the reason stated in
 * `FArchObservationContractLawFitnessTest`: otherwise the KDoc explaining a law names the thing it
 * forbids.
 */
class FArchTranscriptAuthorityLawFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private fun stripComments(source: String): String =
        source.lines()
            .map { it.substringBefore("//") }
            .joinToString("\n")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private fun offendersMatching(module: String, needle: String): List<Path> {
        val main = v2Root.resolve(module).resolve("src/main/kotlin")
        if (!Files.isDirectory(main)) return emptyList()
        return ScannerSupport.walkKotlinFiles(v2Root)
            .filter { it.startsWith(main) }
            .filter { stripComments(Files.readString(it)).contains(needle) }
            .toList()
    }

    private fun assertNoOffenders(offenders: List<Path>, law: String, why: String) {
        if (offenders.isNotEmpty()) {
            throw AssertionError(
                "$law\n\n$why\n\noffending files:\n" +
                    offenders.joinToString("\n") { "  ${v2Root.relativize(it)}" },
            )
        }
    }

    /**
     * `DurableTaskTerminal` must not carry observable transcript bytes.
     *
     * The transcript has one durable authority and one read surface: the Output Plane, through
     * `OutputReadPort`. A terminal that also carried the bytes would be a second copy, in memory,
     * that nothing could keep consistent with the store — and the two would disagree the first time
     * a consumer read the plane at a cursor that the terminal did not reflect.
     *
     * The scan is on the DECLARATION file rather than on every use, because a type-level law is
     * what is being stated: once the field is gone, no caller can reintroduce the copy.
     */
    @Test
    fun `the durable terminal carries no transcript field`() {
        val terminal = v2Root.resolve(
            "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/DurableTaskTerminal.kt",
        )
        assertTrue(
            Files.exists(terminal),
            "cannot read $terminal; this guard would be vacuous if the terminal moved",
        )
        val declared = stripComments(Files.readString(terminal))
        listOf("consoleTranscript", "consoleLog", "transcript").forEach { forbidden ->
            assertTrue(
                !declared.contains(forbidden),
                "DurableTaskTerminal declares '$forbidden'. Observable transcript bytes belong to " +
                    "the Output Plane and are read through OutputReadPort. A transcript field on " +
                    "the terminal is a second byte authority that nothing can keep consistent with " +
                    "the store (ADR-M1 D2). capturedStdout stays: it is the typed VALUE requested " +
                    "by returnStdout, not an observability channel.",
            )
        }
    }

    /**
     * The canonical `sh` seam may not name the staging transcript file.
     *
     * ## Why the scan is narrow, and why that is the point
     *
     * The first version of this law scanned both owning modules for `CONSOLE_LOG` and
     * `resolveConsoleLog`, and it was wrong. It flagged `DurableShellFiles` itself — whose KDoc
     * says the constant exists precisely so operations created before the rename can still be
     * recovered — and it flagged the cleanup and reconciliation paths that filter the file out. A
     * law that can only be satisfied by deleting the read-compatibility constant is not a stricter
     * law; it is a wrong one, and it contradicted the ADR written alongside it.
     *
     * So the law is scoped to the seam that actually decides where bytes go: [ShExecution], the
     * application code that composes the destination for a `sh` step. The SDK's compatibility
     * readers stay, and stay classified.
     *
     * What this row does NOT cover, and what covers it instead:
     *
     * - that the canonical path never **writes** the file is a behavioural property, and
     *   `ObsBLiveOutputIngressTest.the canonical path writes no staging transcript` asserts it by
     *   looking for the file after a real step. A text scan cannot beat that.
     * - that no second durable copy exists is `OutputSingleAuthorityFitnessTest`'s job.
     */
    @Test
    fun `the canonical sh seam does not name the staging transcript file`() {
        val offenders = offendersMatching("pipeline-application", "resolveConsoleLog") +
            offendersMatching("pipeline-application", "CONSOLE_LOG")
        assertNoOffenders(
            offenders = offenders.distinct(),
            law = "the canonical sh seam must not reference the staging transcript file",
            why = "ShExecution decides where a step's bytes are made durable. A reference to the " +
                "staging file here means a read is still feeding a transcript somewhere other than " +
                "the Output Plane, which is the second authority ADR-OBS-001 removes. The SDK's " +
                "own read-compatibility paths are deliberately out of scope and stay classified as " +
                "such.",
        )
    }

    /**
     * The shell substrate must not name a store, a renderer or a transport.
     *
     * `ProcessOutputSink` exists precisely so the substrate decides WHEN bytes are available and
     * the composed sink decides WHERE they land. If the SDK could reach the Output Plane directly,
     * the seam would be a suggestion: the dependency would point outward from the runtime, and the
     * application would lose the only place that chooses the durable authority.
     */
    @Test
    fun `the shell substrate does not name the output plane`() {
        listOf(
            "SegmentOutputStore",
            "OutputPlaneProvider",
            "OutputStreamHandle",
            "OutputAppendPort",
        ).forEach { forbidden ->
            val offenders = offendersMatching("pipeline-step-sdk/runtime", forbidden)
            assertNoOffenders(
                offenders = offenders,
                law = "the shell substrate must not name $forbidden",
                why = "The runtime writes to ProcessOutputSink and knows nothing about where bytes " +
                    "land. Reaching for the store from here would reverse the dependency and make " +
                    "the SDK own the choice of durable authority, which is the application's.",
            )
        }
    }
}