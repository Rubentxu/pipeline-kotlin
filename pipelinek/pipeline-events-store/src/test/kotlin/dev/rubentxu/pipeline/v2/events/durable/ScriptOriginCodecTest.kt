package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptOrigin
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a stored compilation came from, on the wire.
 *
 * ## The guarantee
 *
 * A durable row that carries the origin gives it back; a durable row that
 * does NOT carry it says so instead of inventing one.
 *
 * ## The failure this covers
 *
 * The origin is a new field on an existing event. Every row already on disk
 * was written before it existed, and the journal is durable: its oldest rows
 * are the ones nobody can rewrite. A decoder that defaulted the missing field
 * to "COMPILED" would tell the operator that a compiler ran on every
 * historical run — including the ones that reused a stored artifact, which is
 * exactly the fact the card is about.
 *
 * So the missing field decodes to UNKNOWN, the same three-valued shape the
 * failure vocabulary already uses: "the record does not say" is not a
 * flavour of "the compiler ran".
 *
 * ## Why the legacy row is REAL and not synthetic
 *
 * The row is produced by encoding a current event and DELETING the origin
 * key from the result: that is byte-for-byte what a row written before the
 * field existed looks like, including the absence of the comma. A hand-written
 * JSON string would prove less.
 *
 * Pure and total: no database, no clock, no filesystem.
 */
class ScriptOriginCodecTest {

    @Test
    fun `a stored origin travels under its name and comes back`() {
        val event = compilationStarted(ScriptOrigin.REUSED)

        val encoded = JsonEventLog.encodeOne(event)

        assertTrue(
            encoded.contains(",\"origin\":\"REUSED\""),
            "the wire does not carry the origin: $encoded",
        )
        assertEquals(event, JsonEventLog.decode("[$encoded]").single())
    }

    @Test
    fun `the finished event carries the origin too`() {
        val event = compilationFinished(ScriptOrigin.COMPILED)

        val encoded = JsonEventLog.encodeOne(event)

        assertTrue(
            encoded.contains(",\"origin\":\"COMPILED\""),
            "the wire does not carry the origin: $encoded",
        )
        assertEquals(event, JsonEventLog.decode("[$encoded]").single())
    }

    /**
     * The row that already existed on disk. It has no origin, and it decodes
     * to the truth about itself.
     */
    @Test
    fun `a row written before the origin existed reads as not recorded`() {
        val encoded = JsonEventLog.encodeOne(compilationStarted(ScriptOrigin.REUSED))
        val legacy = encoded.replace(",\"origin\":\"REUSED\"", "")

        assertNotEquals(
            encoded,
            legacy,
            "the key was not removed, so this row would prove nothing",
        )

        val decoded = JsonEventLog.decode("[$legacy]").single() as CompilationStarted
        assertEquals(
            ScriptOrigin.UNKNOWN,
            decoded.origin,
            "a row that does not say where the artifact came from was read as a compilation",
        )
    }

    /**
     * A word this build does not know is not a fact. It reads as "not
     * recorded", never as "compiled".
     */
    @Test
    fun `an origin this build does not know reads as not recorded`() {
        val encoded = JsonEventLog.encodeOne(compilationStarted(ScriptOrigin.REUSED))
        val future = encoded.replace("\"origin\":\"REUSED\"", "\"origin\":\"FROM_THE_FUTURE\"")

        val decoded = JsonEventLog.decode("[$future]").single() as CompilationStarted
        assertEquals(
            ScriptOrigin.UNKNOWN,
            decoded.origin,
            "a word nobody can interpret was read as a fact",
        )
    }

    private fun compilationStarted(origin: ScriptOrigin): DomainEvent = CompilationStarted(
        eventId = "e-1",
        runId = "run-1",
        sequence = 7L,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        origin = origin,
    )

    private fun compilationFinished(origin: ScriptOrigin): DomainEvent = CompilationFinished(
        eventId = "e-2",
        runId = "run-1",
        sequence = 8L,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        cacheKey = CacheKey("hash", CacheKey.V3),
        diagnostics = emptyList(),
        origin = origin,
    )
}
