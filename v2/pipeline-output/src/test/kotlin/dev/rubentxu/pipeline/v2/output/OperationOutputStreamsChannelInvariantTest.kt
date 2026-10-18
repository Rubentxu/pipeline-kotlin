package dev.rubentxu.pipeline.v2.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two-channel invariant of [OperationOutputStreams], and the mislabelled pairs it removes.
 *
 * ## What this row certifies
 *
 * That a pair whose fields do NOT address the channels they are named after cannot be constructed.
 * The value type states "both channels of one operation"; this pins the word *both*.
 *
 * ## The authority it crosses
 *
 * [OperationOutputStreams]'s constructor `init`. That is where the invariant lives, and it is the
 * same code a caller runs, so the test observes a real refusal rather than a re-derived opinion
 * about what ought to be refused. Nothing here re-implements the check: each row asks the
 * constructor and reads what happened. There is no filesystem, no store and no process — the
 * invariant is over two immutable values, which is the fidelity this claim deserves (HF0 pure
 * contract).
 *
 * ## Why the invariant exists
 *
 * Measured on 2026-10-18 (`main@fbbb8edf`, H1.3 of the radical-delivery roadmap). The mutation
 * M-3 built `stderr` on the `STDOUT` channel and the type accepted it: the `init` compared
 * `runId` and `operationId` and stopped there. So a caller asking `select(setOf(STDERR))` would have
 * received **an empty list** from a live transcript with stderr on it, and `select(setOf(STDOUT))`
 * would have returned the same stream twice — a reader silently told that the operation had one
 * channel with its bytes doubled.
 *
 * Two properties would have been true at once and neither is what the type claims: both addresses
 * belonged to the same operation, and both were well-formed addresses. That is what makes the gap
 * worth a test rather than a code comment — a reader checking the fields it could see would have
 * found nothing wrong.
 *
 * ## The mutation that kills it
 *
 * Removing either `require` from the `init`. M-4 removed the `stderr` one and this file went from
 * `tests=4 failures=0` to `failures=1`, the RED being `stderr on the stdout channel is refused` —
 * the row it targets, one to one. The mutation was verified present in the source before the result
 * was read, because a mutation against a stale class is a green that measures nothing.
 *
 * Removing the check from this file instead leaves the refusal rows RED only because `of()` still
 * builds the right pair — which is precisely why they construct the mislabelled pairs explicitly
 * instead of asserting on [of].
 *
 * ## What M-3 looks like now that the invariant exists
 *
 * M-3 builds `stderr` on the `STDOUT` channel in [OperationOutputStreams.of]. Measured with the
 * invariant in place: it **still compiles** — both fields are `OutputStreamAddress` and an address
 * on the STDOUT channel is a valid address — and it turns this module from `failures=0` to
 * `failures=2`, both deaths being the `IllegalArgumentException` raised while BUILDING the pair.
 * The two positive rows are the ones that die, because they cannot obtain a value at all.
 *
 * Recorded because the tempting description — "the crossing pair is now unrepresentable" — is not
 * what was measured. It is refused at runtime. A comment claiming a stronger property than the one
 * tested is the defect this file exists to catch, so it does not get to carry one itself.
 */
class OperationOutputStreamsChannelInvariantTest {

    private val run = "run-7"
    private val op = "run-7-s0-0"

    @Test
    fun `of builds the pair it claims — the positive case the invariant must not break`() {
        val streams = OperationOutputStreams.of(run, op)

        assertEquals(OutputChannel.STDOUT, streams.stdout.channel)
        assertEquals(OutputChannel.STDERR, streams.stderr.channel)
        assertEquals("$run/$op/stdout", streams.stdout.stream.value)
        assertEquals("$run/$op/stderr", streams.stderr.stream.value)
        assertEquals(2, streams.select(setOf(OutputChannel.STDOUT, OutputChannel.STDERR)).size)
    }

    @Test
    fun `stderr on the stdout channel is refused`() {
        // The construction goes INSIDE the lambda, and that is the whole point: `init` refuses the
        // pair, so the throw happens at construction time. Hoisting the call out — the shape this
        // row was written with first — lets the correct refusal escape the assertion and the row
        // reports a product bug that is not there. A harness that cannot express the thing it is
        // measuring is a defect in the harness, whatever the message says.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            OperationOutputStreams(
                stdout = OutputStreamAddress.of(run, op, OutputChannel.STDOUT),
                stderr = OutputStreamAddress.of(run, op, OutputChannel.STDOUT),
            )
        }
        assertTrue(
            failure.message!!.contains("STDERR channel"),
            "the refusal must say WHICH invariant failed, or a reader debugging a misconstruction " +
                "learns nothing. Message was: ${failure.message}",
        )
    }

    @Test
    fun `the reversed pair is refused too — distinct channels would have accepted it`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            OperationOutputStreams(
                stdout = OutputStreamAddress.of(run, op, OutputChannel.STDERR),
                stderr = OutputStreamAddress.of(run, op, OutputChannel.STDERR),
            )
        }
        assertTrue(
            failure.message!!.contains("named stdout"),
            "a field named stdout that addresses STDERR is the same defect as the reverse of it, " +
                "and requiring only that the channels DIFFER would have admitted it. Message was: " +
                "${failure.message}",
        )
    }

    @Test
    fun `a channel-selecting reader cannot receive the same stream twice`() {
        // The consumer-visible consequence of the defect, stated as a property of the good pair.
        // Asserting it here means a future change that let a duplicate through would fail on the
        // guarantee rather than only on the constructor's refusal.
        val streams = OperationOutputStreams.of(run, op)
        val stdoutOnly = streams.select(setOf(OutputChannel.STDOUT))

        assertEquals(1, stdoutOnly.size)
        assertEquals(streams.stdout, stdoutOnly.single())
        assertTrue(streams.select(setOf(OutputChannel.STDERR)).none { it == streams.stdout })
    }
}