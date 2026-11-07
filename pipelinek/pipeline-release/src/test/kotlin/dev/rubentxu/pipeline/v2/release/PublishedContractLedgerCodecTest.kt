package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The guarantee: the exception ledger is read from the file that carries it,
 * and a ledger file that grew a top-level key it does not declare is
 * refused.
 *
 * The capacity this serves: a gate that decides from the ledger it read. The
 * ledger is the single authority for which breaks of a published surface were
 * approved, and a reader that silently drops a key it does not recognise
 * reads a narrower ledger than the one that was written — the gate then acts
 * on a document nobody wrote.
 *
 * These rows are pure: a document in, a ledger out. No build, no filesystem,
 * no clock.
 */
class PublishedContractLedgerCodecTest {

    private val document = """
        {
          "_comment": [
            "The exception ledger: one entry per approved break."
          ],
          "entries": [
            {
              "module": "pipeline-release",
              "sha": "0123456789abcdef",
              "surface": "declaration",
              "change": "removed",
              "permitting_maturity": "mature",
              "reasoning": [
                "the declaration had no consumer"
              ],
              "removal_boundary": "the next major version",
              "applies_to_versions": [
                "0.76.0"
              ],
              "authorized_removals": [
                "Foo#bar"
              ],
              "evidence": "the consumer failed to compile"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `the ledger is read from the file that carries it`() {
        val ledger = PublishedContractLedgerCodec.decode(document)

        assertEquals(1, ledger.entries.size)
        assertEquals("pipeline-release", ledger.entries.single().module)
        assertEquals(listOf("Foo#bar"), ledger.entries.single().authorizedRemovals)
    }

    @Test
    fun `a ledger file with a top-level key it does not declare is refused`() {
        // The document is the ledger with ONE key added, so the refusal can
        // only be about that key.
        val text = document.replaceFirst("{", "{\n  \"unexpected\": true,")

        assertThrows(SerializationException::class.java) {
            PublishedContractLedgerCodec.decode(text)
        }
    }
}
