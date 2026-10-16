package dev.rubentxu.pipeline.v2.harness.retirement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HarnessVerdictReaderTest {

    private val sha = "a".repeat(64)
    private val other = "b".repeat(64)
    private val cand = "c".repeat(64)

    @Test
    fun `verified verdict parses to typed HarnessVerdict`() {
        val text = """
            {
              "status": "verified",
              "path": "v2/pipeline-application/src/test/.../UatLocal008SecretLeakTest.kt",
              "content_sha256": "$sha",
              "proof_digest": "$other",
              "authority": "pipelinek-release-harness",
              "candidate_sha256": "$cand",
              "fingerprint": {
                "input_digest": "$sha",
                "symptom": "OutputLeak",
                "type": "OutputLeak",
                "cause": "redaction ring-full spin"
              }
            }
        """.trimIndent()
        val out = HarnessVerdictReader.readText("test.json", text)
        assertTrue(out is ParseOutcome.Parsed) { "got $out" }
        val verdict = (out as ParseOutcome.Parsed).verdict
        assertTrue(verdict is HarnessVerdict.Verified) { "got $verdict" }
        val v = verdict as HarnessVerdict.Verified
        assertEquals(sha, v.contentSha256)
        assertEquals(other, v.proofDigest)
        assertEquals("pipelinek-release-harness", v.authority)
        assertEquals(cand, v.candidateSha256)
        assertEquals(sha, v.failureFingerprint.inputDigest)
        assertEquals("OutputLeak", v.failureFingerprint.symptom)
    }

    @Test
    fun `blocked verdict parses to typed HarnessVerdict`() {
        val text = """
            { "status": "blocked", "path": "v2/.../UatX.kt", "reason": "no substitute" }
        """.trimIndent()
        val out = HarnessVerdictReader.readText("test.json", text)
        assertTrue(out is ParseOutcome.Parsed) { "got $out" }
        val v = (out as ParseOutcome.Parsed).verdict
        assertTrue(v is HarnessVerdict.Blocked) { "got $v" }
        assertEquals("no substitute", (v as HarnessVerdict.Blocked).reason)
    }

    @Test
    fun `pending verdict parses to typed HarnessVerdict`() {
        val out = HarnessVerdictReader.readText(
            "test.json",
            """{ "status": "pending", "path": "v2/.../UatX.kt" }""",
        )
        assertTrue(out is ParseOutcome.Parsed) { "got $out" }
        val v = (out as ParseOutcome.Parsed).verdict
        assertTrue(v is HarnessVerdict.Pending) { "got $v" }
    }

    @Test
    fun `unknown status is reported as UnknownStatus`() {
        val out = HarnessVerdictReader.readText(
            "test.json",
            """{ "status": "MAYBE", "path": "v2/.../UatX.kt" }""",
        )
        assertTrue(out is ParseOutcome.UnknownStatus) { "got $out" }
        assertEquals("maybe", (out as ParseOutcome.UnknownStatus).status)
    }

    @Test
    fun `missing status field is Malformed`() {
        val out = HarnessVerdictReader.readText(
            "test.json",
            """{ "path": "v2/.../UatX.kt" }""",
        )
        assertTrue(out is ParseOutcome.Malformed) { "got $out" }
    }

    @Test
    fun `verified verdict missing fingerprint is Malformed`() {
        val text = """
            {
              "status": "verified",
              "path": "v2/.../UatX.kt",
              "content_sha256": "$sha",
              "proof_digest": "$other",
              "authority": "pipelinek-release-harness",
              "candidate_sha256": "$cand"
            }
        """.trimIndent()
        val out = HarnessVerdictReader.readText("test.json", text)
        assertTrue(out is ParseOutcome.Malformed) { "got $out" }
    }

    @Test
    fun `verified verdict with non-hex digest is Malformed`() {
        val text = """
            {
              "status": "verified",
              "path": "v2/.../UatX.kt",
              "content_sha256": "not-hex",
              "proof_digest": "$other",
              "authority": "pipelinek-release-harness",
              "candidate_sha256": "$cand",
              "fingerprint": {
                "input_digest": "$sha",
                "symptom": "OutputLeak",
                "type": "OutputLeak",
                "cause": "ring-full"
              }
            }
        """.trimIndent()
        val out = HarnessVerdictReader.readText("test.json", text)
        assertTrue(out is ParseOutcome.Malformed) { "got $out" }
    }

    @Test
    fun `blank path is Malformed`() {
        val out = HarnessVerdictReader.readText(
            "test.json",
            """{ "status": "pending", "path": "" }""",
        )
        assertTrue(out is ParseOutcome.Malformed) { "got $out" }
    }

    @Test
    fun `non-object root is Malformed`() {
        val out = HarnessVerdictReader.readText("test.json", """[ "not", "an", "object" ]""")
        assertTrue(out is ParseOutcome.Malformed) { "got $out" }
    }

    @Test
    fun `read of missing file returns Missing outcome`(@TempDir tmp: Path) {
        val missing = tmp.resolve("nope.json")
        val out = HarnessVerdictReader.read(missing)
        assertTrue(out is ParseOutcome.Missing) { "got $out" }
        assertNotNull((out as ParseOutcome.Missing).path)
    }

    @Test
    fun `read of file on disk round-trips through readText`(@TempDir tmp: Path) {
        val f = tmp.resolve("verdict.json")
        Files.writeString(
            f,
            """
            { "status": "pending", "path": "v2/.../UatX.kt" }
            """.trimIndent(),
        )
        val out = HarnessVerdictReader.read(f)
        assertTrue(out is ParseOutcome.Parsed) { "got $out" }
    }
}
