package dev.rubentxu.pipeline.v2.runtime.inspect

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The journal-fingerprint format rule, and the carriers that project it.
 *
 * ## What this row certifies
 *
 * That a projection of the journal refuses a fingerprint the journal could not have held. Both
 * `OperationInspection` and `OperationSummary` project the same field, so one rule and one test
 * cover both; the rows below construct each carrier, because a rule verified on one caller is not
 * evidence about the other.
 *
 * The rule itself belongs to the class that mints the format — see `Sha256.isSha256Hex`, whose
 * KDoc records why it is not a per-consumer re-derivation.
 *
 * ## The authority it crosses
 *
 * The two carriers' `init` blocks, which delegate to `Sha256.isSha256Hex`. Nothing here
 * re-implements the check — it asks the constructor and reads what happened. HF0 pure contract:
 * immutable values, no store, no clock, no filesystem.
 *
 * ## Why "64 non-hex characters" is the row that matters
 *
 * The previous guard was `fingerprint == null || fingerprint.length == 64`, which is exactly the
 * assertion a reader of the KDoc would expect and precisely not the assertion it made. A mangled
 * value of the right length passed, and then travelled to every consumer as a fingerprint that can
 * never match a real one — a failure that surfaces as "the operation was not found", not as a bad
 * digest. So the negative rows below are all *right length, wrong content*: they are the ones the
 * old guard waved through.
 */
class JournalFingerprintFormatTest {

    private val real = "a".repeat(63) + "1"
    private val streams = OperationOutputStreams(null, null)

    private fun inspection(fingerprint: String?) = OperationInspection(
        operationId = "run-7-s0-0",
        attempt = 1,
        runId = "run-7",
        stageOrdinal = 0,
        stepOrdinal = 0,
        status = dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED,
        startedAtMs = 0,
        endedAtMs = 1,
        deadlineMs = null,
        fingerprint = fingerprint,
        streams = streams,
    )

    private fun summary(fingerprint: String?) = OperationSummary(
        operationId = "run-7-s0-0",
        attempt = 1,
        status = dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED,
        startedAtMs = 0,
        endedAtMs = 1,
        fingerprint = fingerprint,
        streams = streams,
    )

    @Test
    fun `absent and well-formed are the two states the journal can hold`() {
        // Both carriers carry `fingerprint: String?`, and "the journal did not record one" is a real
        // state the M6 KDoc names. A guard that demanded a value would refuse it.
        assertTrue(inspection(null).fingerprint == null)
        assertTrue(summary(null).fingerprint == null)
        assertTrue(inspection(real).fingerprint == real)
        assertTrue(summary(real).fingerprint == real)
    }

    @Test
    fun `64 characters that are not hex are refused by both carriers`() {
        // Exactly what the length-only guard admitted.
        val mangled = "z".repeat(64)
        assertThrows(IllegalArgumentException::class.java) { inspection(mangled) }
        assertThrows(IllegalArgumentException::class.java) { summary(mangled) }
    }

    @Test
    fun `uppercase hex is refused — production mints lowercase`() {
        // `Fingerprint` and `OutputDigest` both require lowercase, so a store holding uppercase was
        // not written by them. This is the one row where the rule is narrower than "is it hex", and
        // it is deliberate: accepting it would make two spellings of one digest compare unequal.
        val shouted = "A".repeat(64)
        assertThrows(IllegalArgumentException::class.java) { inspection(shouted) }
        assertThrows(IllegalArgumentException::class.java) { summary(shouted) }
    }

    @Test
    fun `hex with one non-hex character in the middle is refused`() {
        // The `all { }` walk is what makes this fail; a check of the first 8 or the last 8 would
        // pass it, and such a value is what a truncated-then-padded store actually holds.
        val spliced = "a".repeat(30) + "g" + "b".repeat(33)
        assertThrows(IllegalArgumentException::class.java) { inspection(spliced) }
        assertThrows(IllegalArgumentException::class.java) { summary(spliced) }
    }

    @Test
    fun `the refusal names the value so a mangled store is diagnosable`() {
        val failure = assertThrows(IllegalArgumentException::class.java) { inspection("z".repeat(64)) }
        assertTrue(
            failure.message!!.contains("lowercase hex"),
            "the message must state the format it enforces, not the length it checked. " +
                "Message was: ${failure.message}",
        )
    }
}