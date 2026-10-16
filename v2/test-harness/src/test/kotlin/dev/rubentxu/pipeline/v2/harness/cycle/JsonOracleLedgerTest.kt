package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The [JsonOracleLedger] is the CLI's bridge to a real
 * oracle registry. It is the only ledger a CLI run can use;
 * tests use [MapOracleLedger] for the in-process path. The
 * tests below pin the wire format and the lookup
 * semantics: a PASS row at the bound commit answers
 * `true`; a row at a different commit answers `false`; an
 * oracle that is not in the file answers `false`; a
 * malformed file throws.
 */
class JsonOracleLedgerTest {

    @Test
    fun `a fitness row at the bound commit says PASS`(@TempDir tmp: Path) {
        val file = tmp.resolve("ledger.json")
        val commit = "0123456789abcdef0123456789abcdef01234567"
        Files.writeString(
            file,
            """
            {
              "oracles": [
                {
                  "type": "fitness",
                  "module": ":test-harness",
                  "name": "PromotionEvidenceVerifierTest.fixture",
                  "pass_commits": ["$commit"]
                }
              ]
            }
            """.trimIndent(),
        )
        val ledger = JsonOracleLedger.load(file)
        val oracle = OracleRef.Fitness("PromotionEvidenceVerifierTest.fixture", ":test-harness")
        assertTrue(ledger.hasPassed(oracle, commit))
    }

    @Test
    fun `a JUnit row at the bound commit says PASS`(@TempDir tmp: Path) {
        val file = tmp.resolve("ledger.json")
        val commit = "0123456789abcdef0123456789abcdef01234567"
        Files.writeString(
            file,
            """
            {
              "oracles": [
                {
                  "type": "junit",
                  "module": ":pipeline-application",
                  "test_class": "com.example.Foo",
                  "test_method": "bar",
                  "pass_commits": ["$commit"]
                }
              ]
            }
            """.trimIndent(),
        )
        val ledger = JsonOracleLedger.load(file)
        val oracle = OracleRef.Junit(
            testClass = "com.example.Foo",
            testMethod = "bar",
            module = ":pipeline-application",
        )
        assertTrue(ledger.hasPassed(oracle, commit))
    }

    @Test
    fun `a row at a different commit says FAIL (the ledger is commit-specific)`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("ledger.json")
        Files.writeString(
            file,
            """
            {
              "oracles": [
                {
                  "type": "fitness",
                  "module": ":test-harness",
                  "name": "Test.fixture",
                  "pass_commits": ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
                }
              ]
            }
            """.trimIndent(),
        )
        val ledger = JsonOracleLedger.load(file)
        val oracle = OracleRef.Fitness("Test.fixture", ":test-harness")
        assertFalse(
            ledger.hasPassed(oracle, "0123456789abcdef0123456789abcdef01234567"),
            "a different bound commit MUST NOT inherit the row's PASS",
        )
    }

    @Test
    fun `an oracle not in the ledger says FAIL (a missing ledger is not an implicit PASS)`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("ledger.json")
        Files.writeString(file, """{"oracles": []}""")
        val ledger = JsonOracleLedger.load(file)
        val oracle = OracleRef.Fitness("Unknown", ":test-harness")
        assertFalse(ledger.hasPassed(oracle, "0123456789abcdef0123456789abcdef01234567"))
    }

    @Test
    fun `multiple pass_commits all say PASS`(@TempDir tmp: Path) {
        val file = tmp.resolve("ledger.json")
        val c1 = "0123456789abcdef0123456789abcdef01234567"
        val c2 = "fedcba9876543210fedcba9876543210fedcba98"
        Files.writeString(
            file,
            """
            {
              "oracles": [
                {
                  "type": "fitness",
                  "module": ":test-harness",
                  "name": "MultiCommit",
                  "pass_commits": ["$c1", "$c2"]
                }
              ]
            }
            """.trimIndent(),
        )
        val ledger = JsonOracleLedger.load(file)
        val oracle = OracleRef.Fitness("MultiCommit", ":test-harness")
        assertTrue(ledger.hasPassed(oracle, c1))
        assertTrue(ledger.hasPassed(oracle, c2))
    }

    @Test
    fun `a missing ledger file throws (the CLI MUST NOT silently default to EmptyOracleLedger)`(
        @TempDir tmp: Path,
    ) {
        // The CLI's argument parser sees the path string; the
        // ledger loader is the authority on whether the file
        // is real. A missing file is a programmer error
        // (the UAT/AAT scripts that drive the CLI are the
        // ones that would have written it), not a soft
        // failure that falls back to EmptyOracleLedger.
        val ex = assertThrows<java.io.IOException> {
            JsonOracleLedger.load(tmp.resolve("does-not-exist.json"))
        }
        assertNotNull(ex.message)
    }

    @Test
    fun `a malformed ledger file throws with a parse error`(@TempDir tmp: Path) {
        val file = tmp.resolve("bad.json")
        Files.writeString(file, "{ this is not JSON")
        assertThrows<kotlinx.serialization.SerializationException> {
            JsonOracleLedger.load(file)
        }
    }

    @Test
    fun `a ledger with a row whose commit count is zero still parses (the verifier answers false)`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("ledger.json")
        Files.writeString(
            file,
            """
            {
              "oracles": [
                {
                  "type": "fitness",
                  "module": ":test-harness",
                  "name": "EmptyPasses",
                  "pass_commits": []
                }
              ]
            }
            """.trimIndent(),
        )
        val ledger = JsonOracleLedger.load(file)
        val oracle = OracleRef.Fitness("EmptyPasses", ":test-harness")
        assertEquals(emptySet<String>(), setOf<String>().let { it })
        assertFalse(ledger.hasPassed(oracle, "0123456789abcdef0123456789abcdef01234567"))
    }
}
