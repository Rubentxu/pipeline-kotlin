package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The operator's trust file, observed where it is read.
 *
 * The file is the operator's own authority, and its format is contractual: a row that cannot be
 * read is a row that cannot be relied on, and the gate has to refuse rather than guess. Reading it
 * is pure here — the text goes in, the rows come out — so the format is pinned without a file, a
 * process or a trust decision.
 */
class OperatorTrustFileTest {

    private val pluginId = "v1:plugin:example-uppercase-plugin/plugin/uppercase"
    private val version = SemVer(0, 1, 0)
    private val digest = Digest("sha256:" + "a".repeat(64))

    @Test
    @DisplayName("a row is keyed by the plugin and the release, not by the plugin alone")
    fun aRowIsKeyedByPluginAndRelease() {
        assertEquals("$pluginId@0.1.0", OperatorTrustDatabase.key(pluginId, version))
    }

    @Test
    @DisplayName("a well-formed file yields the row the operator wrote")
    fun aWellFormedFileYieldsTheRow() {
        val text = """
            {
              "$pluginId@0.1.0": {
                "digest": "sha256:${"a".repeat(64)}",
                "publisher": "example-uppercase",
                "signed": true,
                "signedBy": "example-key"
              }
            }
        """.trimIndent()

        val rows = parseOperatorTrustRows(text)

        assertEquals(1, rows.size)
        val row = rows["$pluginId@0.1.0"]
        assertEquals(digest, row?.digest)
        assertEquals("example-uppercase", row?.publisher)
        assertEquals(true, row?.signed)
        assertEquals("example-key", row?.signedBy)
    }

    @Test
    @DisplayName("a row that cannot be read is absent, not guessed")
    fun aRowThatCannotBeReadIsAbsent() {
        // Every one of these is a file an operator could plausibly write. None of them may produce
        // a row, because a row that cannot be read must not be the row the gate trusts.
        val unreadable = listOf(
            "",
            "not json",
            "[]",
            """{"$pluginId@0.1.0": "not an object"}""",
            """{"$pluginId@0.1.0": {"publisher":"p","signed":true}}""",
            """{"$pluginId@0.1.0": {"digest":"sha256:${"a".repeat(64)}","signed":true}}""",
            """{"$pluginId@0.1.0": {"digest":"sha256:${"a".repeat(64)}","publisher":"p"}}""",
            """{"$pluginId@0.1.0": {"digest":"not-a-digest","publisher":"p","signed":true}}""",
            """{"$pluginId@0.1.0": {"digest":"sha256:${"a".repeat(64)}","publisher":"p","signed":"yes"}}""",
        )

        for (text in unreadable) {
            assertTrue(parseOperatorTrustRows(text).isEmpty(), "should not have read a row from: $text")
        }
    }

    @Test
    @DisplayName("a file that cannot be read at all yields no rows, which refuses everything")
    fun aFileThatCannotBeReadYieldsNoRows() {
        // The fail-closed reading: a policy an operator asked for and that this runtime cannot
        // read must not leave the gate thinking it is in force.
        assertTrue(parseOperatorTrustRows("{ this is not json").isEmpty())
    }

    @Test
    @DisplayName("a row the operator wrote for another release does not answer for this one")
    fun aRowForAnotherReleaseDoesNotAnswerForThisOne() {
        val text = """
            {
              "$pluginId@9.9.9": {
                "digest": "sha256:${"a".repeat(64)}",
                "publisher": "example-uppercase",
                "signed": true,
                "signedBy": "example-key"
              }
            }
        """.trimIndent()

        val rows = parseOperatorTrustRows(text)

        assertNull(rows["$pluginId@0.1.0"])
        assertEquals(1, rows.size)
    }
}
