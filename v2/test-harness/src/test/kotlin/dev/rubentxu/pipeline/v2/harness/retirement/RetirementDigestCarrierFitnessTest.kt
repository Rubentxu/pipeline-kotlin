package dev.rubentxu.pipeline.v2.harness.retirement

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v6 (strict digest carrier, 2026-10-17): the
 * retirement package's SHA-256 fields used to be
 * length-only validated at construction. An uppercase
 * digest or a non-hex string of length 64 was accepted
 * as a "SHA-256" and compared by string equality
 * against the harness's lowercase digest, silently
 * never matching. The carriers now strict-validate
 * (64-char lowercase hex); the test pins that the
 * format check cannot regress to "length only".
 *
 * The three carriers pinned here:
 *  1. [HarnessVerdict.Verified] (contentSha256,
 *     proofDigest, candidateSha256) — the retirement
 *     receipt from the external certifier.
 *  2. [FailureFingerprint.inputDigest] — the producer /
 *     harness-shared defect fingerprint's input digest.
 *  3. [DeletionDecision.RedundantWithProof]
 *     (originalFileSha256, proofDigest) — the producer's
 *     retirement decision.
 *  4. [TestRecord.contentSha256] — the inventory CSV row
 *     the classifier consumes.
 *
 * The previous length-only shape let `"hello".padEnd(64, '0')`
 * pass; the new shape refuses it. The fitness
 * greps the retirement package's main source for the
 * forbidden length-only check pattern (a `length == 64`
 * that is NOT followed by an `all { it in '0'..'9' }`
 * hex check on the same field) so a regression to the
 * old shape fails the build with a named offender.
 */
class RetirementDigestCarrierFitnessTest {

    private val retirementPackageRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/retirement")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(retirementPackageRoot)) {
            error("retirement package source not found at $retirementPackageRoot")
        }
        return Files.walk(retirementPackageRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertNoLengthOnlyDigestCheck(sources: List<Path>) {
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val text = path.readText()
            // The previous shape was a single
            // `require(xxx.length == 64) { ... }` block
            // (no hex character check on the same field).
            // The new shape combines length and hex in one
            // `require` (e.g. `length == 64 && all { it in
            // '0'..'9' || it in 'a'..'f' }`). The forbidden
            // pattern is a `require(` line containing
            // `length == 64` with NO matching `all { it in
            // '0'..'9' | 'a'..'f' }` on a subsequent line
            // for the same field.
            //
            // Simpler approximation: forbid a `require(...length == 64...)`
            // line that is not followed by a hex-check line
            // on the next non-blank line. False positives
            // are tolerable: a `length == 64` check on a
            // non-digest field (e.g. a `tag` of length 64)
            // would be unusual; the regex is conservative.
            val lengthOnly = Regex("""require\([^)]*\.length == 64[^)]*\)\s*\{[^}]*SHA-256[^}]*\}""")
            val hexCheck = Regex("""all\s*\{\s*it\s+in\s+'0'\.\.'9'\s*\|\|\s*it\s+in\s+'a'\.\.'f'\s*\}""")
            // The line-by-line check: a `length == 64` SHA-256
            // require that has NO hex-check line within 4
            // lines (the typical distance between length
            // and hex require blocks).
            val lines = text.lines()
            for (i in lines.indices) {
                if (lengthOnly.containsMatchIn(lines[i])) {
                    val window = (i..minOf(i + 4, lines.lastIndex)).joinToString("\n") { lines[it] }
                    if (!hexCheck.containsMatchIn(window)) {
                        offenders += "${path.fileName}: line ${i + 1}: length-only " +
                            "SHA-256 require without hex-character check on the same field"
                    }
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v6 fitness: every SHA-256 field in the retirement package MUST be " +
                "strict-validated (length + lowercase hex), not length-only. A " +
                "length-only check lets an uppercase or non-hex string pass as a " +
                "'SHA-256' and silently never match the harness's lowercase digest. " +
                "Offending sites: $offenders",
        )
    }

    @Test
    fun `HarnessVerdict Verified refuses an uppercase proofDigest at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            HarnessVerdict.Verified(
                path = "v2/pipeline-application/src/test/.../UatLocal008.kt",
                contentSha256 = "0".repeat(64),
                proofDigest = "A".repeat(64),
                authority = "pipelinek-release-harness",
                candidateSha256 = "0".repeat(64),
                failureFingerprint = FailureFingerprint(
                    inputDigest = "0".repeat(64),
                    symptom = "OutputLeak",
                    type = "OutputLeak",
                    cause = "redaction ring-full spin",
                ),
            )
        }
        assertTrue(
            (ex.message ?: "").contains("lowercase hex"),
            "refusal must name the lowercase-hex requirement; got ${ex.message}",
        )
    }

    @Test
    fun `HarnessVerdict Verified refuses a non-64-char proofDigest at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            HarnessVerdict.Verified(
                path = "x",
                contentSha256 = "0".repeat(64),
                proofDigest = "abc",
                authority = "pipelinek-release-harness",
                candidateSha256 = "0".repeat(64),
                failureFingerprint = FailureFingerprint(
                    inputDigest = "0".repeat(64),
                    symptom = "OutputLeak",
                    type = "OutputLeak",
                    cause = "redaction ring-full spin",
                ),
            )
        }
        assertTrue(
            (ex.message ?: "").contains("64-char hex SHA-256"),
            "refusal must name the format requirement; got ${ex.message}",
        )
    }

    @Test
    fun `DeletionDecision RedundantWithProof refuses a non-hex originalFileSha256`() {
        val ex = assertThrows<IllegalArgumentException> {
            DeletionDecision.RedundantWithProof(
                originalFileSha256 = "z".repeat(64),
                proofDigest = "0".repeat(64),
                authority = "pipelinek-release-harness",
            )
        }
        assertTrue(
            (ex.message ?: "").contains("lowercase hex"),
            "refusal must name the lowercase-hex requirement; got ${ex.message}",
        )
    }

    @Test
    fun `TestRecord refuses a non-hex contentSha256 at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            TestRecord(
                path = "v2/.../FooTest.kt",
                kindHint = KindHint.OTHER_TEST_SOURCE,
                runnerHint = RunnerHint.V2_GRADLE,
                observedProcessApis = emptyList(),
                testAnnotated = 0,
                disabledAnnotations = 0,
                releaseScaleTag = false,
                contentSha256 = "Z".repeat(64),
            )
        }
        assertTrue(
            (ex.message ?: "").contains("lowercase hex"),
            "refusal must name the lowercase-hex requirement; got ${ex.message}",
        )
    }

    @Test
    fun `the retirement package has no length-only SHA-256 require block`() {
        assertNoLengthOnlyDigestCheck(kotlinSources())
    }
}
