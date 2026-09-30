package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * P-UAT-07 — continuity. Fitness check, not a behavioural test.
 *
 * The spec: "After candidate publication, SDDK permits next independent
 * WorkItem without waiting for harness verdict."
 *
 * That is a governance property, so there is no runtime behaviour to assert.
 * What CAN be asserted mechanically — and what actually protects the
 * property — is that the producer has no way to *wait* on the harness in the
 * first place. If a future change introduced a poll, a verdict field or a
 * certification gate into the release path, the producer would silently become
 * synchronously blocked on an external system it does not own, and the next
 * WorkItem would stall behind a certification that may never arrive.
 *
 * This is a source-shape fitness test on purpose: the property lives in the
 * shape of the protocol surface, not in any single function's return value.
 */
class CandidateContinuityFitnessTest {

    private val sourceRoot: Path = Path.of("src/main/kotlin/dev/rubentxu/pipeline/v2/release")

    private fun sources(): List<Path> =
        if (!Files.isDirectory(sourceRoot)) {
            emptyList()
        } else {
            Files.walk(sourceRoot).use { stream ->
                stream.filter { it.toString().endsWith(".kt") }.toList()
            }
        }

    @Test
    fun `the release module has sources to inspect`() {
        assertTrue(sources().isNotEmpty(), "fitness test must not pass vacuously on an empty source set")
    }

    /**
     * The handoff descriptor is a hand-off, not a gate. A verdict or
     * certification field on it would mean the producer is modelling an
     * external decision it does not own, which is how a non-blocking handoff
     * quietly turns into a blocking one.
     */
    @Test
    fun `the handoff descriptor carries no certification verdict`() {
        val handoff = File("src/main/kotlin/dev/rubentxu/pipeline/v2/release/CandidateHandoff.kt")
        assertTrue(handoff.exists(), "CandidateHandoff.kt must exist")
        val body = handoff.readText()
        // Field declarations only, so prose in KDoc mentioning certification
        // does not trip the check.
        val declarations = body.lines()
            .filter { it.trimStart().startsWith("@SerialName") || FIELD_DECL.containsMatchIn(it) }
        assertTrue(
            declarations.none { it.contains("verdict", true) || it.contains("certif", true) },
            "P-UAT-07: the handoff must not carry a certification verdict; " +
                "certification is the harness's decision, not the producer's. Offending: $declarations",
        )
    }

    /**
     * No producer source may block on the external harness. Polling for a
     * verdict inside the producer would make candidate publication depend on
     * a system this repository does not control.
     */
    @Test
    fun `no producer source blocks waiting on the harness`() {
        val offenders = sources().filter { file ->
            val body = Files.readString(file)
            BLOCKING_PATTERNS.any { pattern -> pattern.containsMatchIn(body) }
        }.map { it.fileName.toString() }

        assertTrue(
            offenders.isEmpty(),
            "P-UAT-07: producer sources must not wait on an external harness verdict. " +
                "Offending files: $offenders",
        )
    }

    private companion object {
        val FIELD_DECL = Regex("""^\s*(private\s+)?(val|var)\s+\w+""")
        val BLOCKING_PATTERNS = listOf(
            Regex("""\bwhile\s*\(\s*!?\s*\w*(verdict|certified|verdictReceived)\w*\s*\)""", RegexOption.IGNORE_CASE),
            Regex("""\bwaitForVerdict\b""", RegexOption.IGNORE_CASE),
            Regex("""\bpollUntilCertified\b""", RegexOption.IGNORE_CASE),
            Regex("""\bblockUntilHarness\b""", RegexOption.IGNORE_CASE),
        )
    }
}
