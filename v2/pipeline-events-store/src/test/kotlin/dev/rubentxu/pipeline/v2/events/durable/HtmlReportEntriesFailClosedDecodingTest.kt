package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.HtmlReportEntry
import dev.rubentxu.pipeline.v2.events.HtmlReportPublished
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant

/**
 * TD-R4.3 — `HtmlReportPublished` must refuse an unreadable `entries` row, not silently drop
 * corrupt entries and return a SHORT list.
 *
 * ## The defect
 *
 * `EventJsonDecoder.decodeHtmlReportEntries` decoded the array with
 *
 * ```kotlin
 * arr.mapNotNull { obj ->
 *     val relPath = EventJsonFields.stringField(obj, "relPath") ?: return@mapNotNull null
 *     val sha256  = EventJsonFields.stringField(obj, "sha256")  ?: return@mapNotNull null
 *     val size    = EventJsonFields.longField(obj,   "sizeBytes")?: return@mapNotNull null
 *     HtmlReportEntry(relPath, sha256, size)
 * }
 * ```
 *
 * which is the **silent-drop** pattern. A `HtmlReportPublished` with five files where one is
 * corrupt (missing `sha256`, malformed `sizeBytes`, etc.) came back as a `HtmlReportPublished`
 * with FOUR entries, and **nothing anywhere reported the difference**. The durable row did not
 * claim five files; it claimed four. The fingerprint over the typed event — used for replay
 * recognition — matched the corrupt subset, not the recorded one.
 *
 * `HtmlReportEntry` is typed as `relPath: String, sha256: String, sizeBytes: Long` — every
 * field is required by the type and is always written by `EventJsonWriter.encodeEvent`. The
 * `mapNotNull` had no business inventing a non-empty list from a payload that could not produce
 * one, just as `CredentialPurposeRefusalTest` retires the parallel silent-fallback on
 * `CredentialBound` / `CredentialUsed`.
 *
 * ## The fix
 *
 * Add a fail-closed `decodeHtmlReportEntriesOrRefuse` parallel to the existing
 * `decodeStashedEntriesOrRefuse` / `decodeRestoredEntriesOrRefuse` /
 * `decodeArtifactEntriesOrRefuse`. Switch the production call site in
 * `JsonEventLog.decodeEvent` from the silent-drop variant to the `…OrRefuse` variant with
 * `?: return unreadable("entries", "HtmlReportPublished")`. The empty `entries` array and the
 * absent `entries` field remain Accepted — they describe a real empty list, not a corrupt one.
 *
 * ## Why this is the same defect class as the others
 *
 * `StashCreated` (LPR-089) and `StashRestored` (LPR-089) already use `…OrRefuse`. `ArtifactArchived`
 * (the parallel archive codec) already uses `decodeArtifactEntriesOrRefuse` — its silent-drop
 * cousin at line 58 of `EventJsonDecoder.kt` is dead production code. `HtmlReportPublished` was
 * the only carrier whose unreadable entries still passed through, and the `mapNotNull` shape
 * was the visible signal that it had not been retrofitted when the rest were. This row retires
 * that asymmetry so the `OrRefuse` family is the only path.
 *
 * ## What this row does NOT claim
 *
 * It does not assert anything about the original silent-drop
 * `decodeHtmlReportEntries` after the fix: the public surface is `JsonEventLog.decodeStoredRow`
 * and that is what gets exercised here. The internal `decodeHtmlReportEntries` will remain in
 * place because removing a non-public function is a mechanical refactor (fitness test) and
 * is not part of this audit's exit criterion. If the cleanup is desired it is a one-line
 * deletion plus a fitness test; if not, the silent-drop variant is now unreached from
 * `JsonEventLog.decodeEvent` and the defect cannot reappear from the production path.
 */
@DisplayName("TD-R4.3 — HtmlReportPublished refuses an unreadable entries row instead of silently dropping it")
@Timeout(30)
class HtmlReportEntriesFailClosedDecodingTest {

    private val occurredAt: Instant = Instant.parse("2026-10-15T00:00:00Z")

    private fun sampleEvent(entries: List<HtmlReportEntry>): HtmlReportPublished = HtmlReportPublished(
        eventId = "evt-1",
        runId = "run-1",
        sequence = 1L,
        occurredAt = occurredAt,
        stageName = "build",
        reportName = "report-1",
        reportDir = "build/reports/html",
        entries = entries,
        targetPath = "build/reports/html/report-1/index.html",
    )

    /**
     * Builds a stored-row payload from a hand-shaped `entries` JSON literal. The
     * surrounding event JSON is the same as the encoder produces, so the only
     * variable under study is the `entries` array.
     */
    private fun rowWithEntries(entriesJsonLiteral: String): String {
        return "{" +
            "\"eventId\":\"evt-1\",\"runId\":\"run-1\",\"sequence\":1," +
            "\"occurredAt\":\"2026-10-15T00:00:00Z\",\"kind\":\"HtmlReportPublished\"," +
            "\"stageName\":\"build\",\"reportName\":\"report-1\",\"reportDir\":\"build/reports/html\"," +
            "\"targetPath\":\"build/reports/html/report-1/index.html\"," +
            "\"entries\":${entriesJsonLiteral}}"
    }

    private fun threeEntries(): List<HtmlReportEntry> = listOf(
        HtmlReportEntry("a.html", "a".repeat(64), 10L),
        HtmlReportEntry("b.html", "b".repeat(64), 20L),
        HtmlReportEntry("c.html", "c".repeat(64), 30L),
    )

    @Test
    fun `valid HtmlReportPublished row decodes with all entries preserved`() {
        val payload = JsonEventLog.encode(listOf(sampleEvent(threeEntries())))
        val result = JsonEventLog.decodeStoredRow(payload, "HtmlReportPublished")
        assertTrue(
            result is StoredRowDecode.Accepted,
            "a hand-shaped row that mirrors the encoder's output must decode; got $result",
        )
        val decoded = (result as StoredRowDecode.Accepted).event as HtmlReportPublished
        assertEquals(3, decoded.entries.size, "all three entries must round-trip")
        assertEquals("a.html", decoded.entries[0].relPath)
        assertEquals("c.html", decoded.entries[2].relPath)
    }

    @Test
    fun `row with one corrupt entry refuses and names entries as the offending field`() {
        // Five valid entries, one with `sizeBytes` missing. With the silent-drop decoder
        // this came back as Accepted with FIVE entries. After the fix it must refuse and
        // name `entries` in the cause so an operator can act on the row without re-reading
        // the raw payload.
        val entriesJson =
            "[" +
                "{\"relPath\":\"a.html\",\"sha256\":\"${"a".repeat(64)}\",\"sizeBytes\":10}," +
                "{\"relPath\":\"b.html\",\"sha256\":\"${"b".repeat(64)}\",\"sizeBytes\":20}," +
                "{\"relPath\":\"c.html\",\"sha256\":\"${"c".repeat(64)}\"}," + // missing sizeBytes
                "{\"relPath\":\"d.html\",\"sha256\":\"${"d".repeat(64)}\",\"sizeBytes\":40}," +
                "{\"relPath\":\"e.html\",\"sha256\":\"${"e".repeat(64)}\",\"sizeBytes\":50}" +
                "]"
        val payload = "[" + rowWithEntries(entriesJson) + "]"

        val result = JsonEventLog.decodeStoredRow(payload, "HtmlReportPublished")
        assertTrue(
            result is StoredRowDecode.Refused,
            "a row with one unreadable entry must be Refused, not silently shortened to 4. " +
                "If this passes as Accepted, decodeHtmlReportEntries is still using mapNotNull. " +
                "Got: $result",
        )
        val reason = (result as StoredRowDecode.Refused).reason
        assertTrue(
            reason is UndecodableReason.MalformedPayload,
            "a known kind with an unreadable field is MalformedPayload, not UnknownKind; " +
                "got: $reason",
        )
        val detail = (reason as UndecodableReason.MalformedPayload).detail
        assertTrue(
            detail.contains("entries"),
            "the refusal must name the offending field so an operator reading the refused row " +
                "knows where to look. Got: '$detail'",
        )
    }

    @Test
    fun `row with all entries unreadable refuses and still names entries`() {
        // The whole array is well-formed but every entry is broken (each missing a
        // different required field). This proves the refusal is not "the array shape is
        // wrong" — it is "any entry is unreadable".
        val entriesJson =
            "[" +
                "{\"relPath\":\"a.html\"}," +                                  // missing sha256, sizeBytes
                "{\"sha256\":\"${"a".repeat(64)}\",\"sizeBytes\":10}," +       // missing relPath
                "{\"relPath\":\"c.html\",\"sha256\":\"${"c".repeat(64)}\"}," +  // missing sizeBytes
                "]"
        val payload = "[" + rowWithEntries(entriesJson) + "]"

        val result = JsonEventLog.decodeStoredRow(payload, "HtmlReportPublished")
        assertTrue(result is StoredRowDecode.Refused, "all-unreadable row must refuse; got $result")
        val detail = ((result as StoredRowDecode.Refused).reason as UndecodableReason.MalformedPayload).detail
        assertTrue(
            detail.contains("entries"),
            "the offending field must be named 'entries', not the first missing sub-field; " +
                "got: '$detail'",
        )
    }

    @Test
    fun `row with absent entries array is Accepted with an empty list (absent is not unreadable)`() {
        // A published report with no files (DIRECTORY_MISSING, NO_FILES_MATCHED) emits
        // HtmlReportPublished with `entries = []`. The empty list is a real value, not
        // corruption, and a `…OrRefuse` decoder must NOT confuse "the field is absent"
        // with "the field is present and unreadable". This row pins the difference.
        val payload = "[" + rowWithEntries("[]") + "]"
        val result = JsonEventLog.decodeStoredRow(payload, "HtmlReportPublished")
        assertTrue(
            result is StoredRowDecode.Accepted,
            "an empty entries array is a real value, not corruption; got $result",
        )
        val decoded = (result as StoredRowDecode.Accepted).event as HtmlReportPublished
        assertEquals(0, decoded.entries.size, "empty entries array must round-trip empty")
    }

    @Test
    fun `row with missing sha256 on a single entry is the canonical RED for this slice`() {
        // The narrowest version of the defect: ONE entry, ONE missing field (`sha256`).
        // This is the row that flips from Accepted (with 0 entries) to Refused when the
        // silent-drop decoder is replaced with the `…OrRefuse` decoder. The previous
        // `mapNotNull` shape would skip the unreadable entry and return []; the new
        // shape MUST refuse because the array was present and one of its elements is
        // unreadable.
        val entriesJson =
            "[{\"relPath\":\"only.html\",\"sizeBytes\":10}]" // missing sha256
        val payload = "[" + rowWithEntries(entriesJson) + "]"

        val result = JsonEventLog.decodeStoredRow(payload, "HtmlReportPublished")
        assertTrue(
            result is StoredRowDecode.Refused,
            "a single-entry array where the entry is unreadable MUST be Refused — silent drop " +
                "would return [] and a downstream `entries.isEmpty()` check would mistake " +
                "'no files matched' for 'we lost the file'. Got: $result",
        )
    }
}
