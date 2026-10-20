package dev.rubentxu.pipeline.v2.sdk.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `StatusRange.parse` is the pure, total function an author-supplied range spec is decoded through.
 * Its own KDoc states the contract: *"Total: anything unparseable returns `null` and the caller
 * turns that into a typed rejection. It never throws and never guesses."* Total and never-throws are
 * separate promises, and the second is the one that was false.
 * The spec arrives from outside: `HttpCodecs` reads it out of a JSON payload at decode time, which
 * is the whole reason this function exists -- Jenkins parses the same string *after* the request has
 * already reached the world, so here a bad range must be a declaration error. A declaration error
 * that arrives as an `IllegalArgumentException` from deep inside a decoder is not a declaration
 * error, and it escapes the total boundary the caller is entitled to rely on.
*/
class StatusRangeTest {

    // --- the total promise, stated as a promise ---------------------------------

    @Test
    fun `no spec at all makes parse throw`() {
        // Every entry here is author-supplied text. The contract is that parse answers with a value
        // for all of them, so the test is a single claim over a table rather than one case per bug.
        val hostile = listOf(
            "",
            "   ",
            ",",
            ",,",
            "100:399,",
            ",100:399",
            "100:399,,404",
            ":",
            "::",
            "100:",
            ":399",
            "-",
            "abc",
            "abc:def",
            "100:abc",
            "abc:399",
            "1:2:3",
            "4:5:6:7",
            "1e3",
            "0x1F",
            "404 200",
            "200\n300",
            "200\r300",
            "٣٠٠",
            "1000",
            "999999999999999999999",
            "-100:-1",
            "200.5",
            " 200 ",
            "null",
            "NaN",
            "200:100",
            "200:200:100",
        )

        for (spec in hostile) {
            val outcome = assertDoesNotThrow(spec) { StatusRange.parse(spec) }

            assertTrue(
                outcome == null || outcome.isNotEmpty(),
                "parse(\"$spec\") returned neither null nor a range: $outcome",
            )
        }
    }

    /** Runs [block] and returns its value, failing the test if it threw. */
    private fun assertDoesNotThrow(spec: String, block: () -> List<StatusRange>?): List<StatusRange>? = try {
        block()
    } catch (e: Throwable) {
        throw AssertionError("parse(\"$spec\") threw ${e::class.simpleName}: ${e.message}", e)
    }

    // --- a span out of the legal status band is unparseable, not fatal ----------

    @Test
    fun `a span below the status band is unparseable rather than fatal`() {
        // The shape that broke totality: the token parsed as two integers in order, so the range
        // check was never applied before `StatusRange.Span`'s init demanded 100..599 and threw.
        assertNull(StatusRange.parse("50:60"))
        assertNull(StatusRange.parse("1:99"))
    }

    @Test
    fun `a span above the status band is unparseable rather than fatal`() {
        // 600 is one past the band. The upper edge matters as much as the lower one: a check that
        // only rejects "too small" would pass this and still throw.
        assertNull(StatusRange.parse("600:700"))
        assertNull(StatusRange.parse("599:600"))
    }

    @Test
    fun `a single code outside the band is unparseable, exactly like a span`() {
        assertNull(StatusRange.parse("99"))
        assertNull(StatusRange.parse("600"))
    }

    @Test
    fun `one bad token discards the whole spec`() {
        // Total-or-nothing. Accepting the good half would mean an author who wrote "100:399,50:60"
        // gets a NARROWER rule than they asked for, with no diagnostic saying so.
        assertNull(StatusRange.parse("100:399,50:60"))
        assertNull(StatusRange.parse("50:60,100:399"))
    }

    @Test
    fun `the two endpoints are each validated`() {
        assertNotNull(StatusRange.parse("100:599"))
        assertNull(StatusRange.parse("99:599"))
        assertNull(StatusRange.parse("100:600"))
    }

    // --- the Jenkins spelling ---------------------------------------------------

    @Test
    fun `the Jenkins spelling parses to its span and its single`() {
        val parsed = StatusRange.parse("100:399,404")

        assertEquals(2, parsed?.size)
        assertEquals(StatusRange.Span(100, 399), parsed?.get(0))
        assertEquals(StatusRange.Single(404), parsed?.get(1))
    }

    @Test
    fun `whitespace around tokens and endpoints is tolerated`() {
        assertEquals(StatusRange.parse("100:399,404"), StatusRange.parse(" 100 : 399 , 404 "))
    }

    @Test
    fun `a blank spec is unparseable and never an empty list`() {
        // `null` and `[]` are different claims: the caller treats null as "reject this" and an empty
        // list as "accept nothing". Returning [] for a blank spec would silently accept nothing
        // rather than falling back to the documented default.
        assertNull(StatusRange.parse(""))
        assertNull(StatusRange.parse("  "))
    }

    // --- the typed ranges themselves --------------------------------------------

    @Test
    fun `the Jenkins default accepts anything below 400 and nothing at or above`() {
        val default = StatusRange.jenkinsDefault()

        assertEquals(1, default.size)
        assertFalse(default[0].contains(400))
        assertTrue(default[0].contains(399))
        assertTrue(default[0].contains(100))
        assertFalse(default[0].contains(99))
    }

    @Test
    fun `a span is closed at both ends`() {
        val span = StatusRange.Span(200, 204)

        assertFalse(span.contains(199))
        assertTrue(span.contains(200))
        assertTrue(span.contains(204))
        assertFalse(span.contains(205))
    }

    @Test
    fun `a single status matches exactly one code`() {
        val single = StatusRange.Single(204)

        assertTrue(single.contains(204))
        assertFalse(single.contains(203))
        assertFalse(single.contains(205))
    }

    @Test
    fun `a backwards span cannot be constructed at all`() {
        // The constructor refuses, which is why parse must pre-validate: it is the only guard on
        // this branch, and a guard that throws is not a guard a total function may rely on.
        assertThrows<IllegalArgumentException> { StatusRange.Span(300, 200) }
    }

    @Test
    fun `a span outside the band cannot be constructed at all`() {
        assertThrows<IllegalArgumentException> { StatusRange.Span(50, 60) }
        assertThrows<IllegalArgumentException> { StatusRange.Span(600, 700) }
    }

    @Test
    fun `a parsed range answers contains the way its spelling promised`() {
        val parsed = StatusRange.parse("200:204,404")!!

        assertTrue(parsed[0].contains(202))
        assertFalse(parsed[0].contains(404))
        assertTrue(parsed[1].contains(404))
    }
}
