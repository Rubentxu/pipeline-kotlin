package dev.rubentxu.pipeline.v2.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A stream's identity is its ADDRESS STRING: `runId/operationId/channel`. Everything downstream --
 * the pin index, the frame index, the transcript reader -- keys off that string, so how it is built
 * and when a string is refused are both contract, not formatting.
 * `OutputStreamAddress.parse` is the only way an address becomes a typed value again, and it is the
 * only place a persisted stream id re-enters the product. A `null` from it is the refusal; every
 * rejection is a whole-value return, never a throw, because a malformed id arrives from a journal on
 * disk and must not be able to escape as an exception.
*/
class OutputChannelTest {

    // --- the address is a pure function of its three fields ------------------

    @Test
    fun `the stream id joins run, operation and channel token with slashes`() {
        val address = OutputStreamAddress.of("run-7", "step.out", OutputChannel.STDOUT)

        assertEquals("run-7/step.out/stdout", address.stream.value)
    }

    @Test
    fun `an address parses back into the address that wrote it`() {
        for (channel in OutputChannel.entries) {
            val address = OutputStreamAddress.of("run-7", "step.out", channel)

            assertEquals(address, OutputStreamAddress.parse(address.stream),
                "channel ${channel.token} must survive the round-trip")
        }
    }

    @Test
    fun `an operation id containing a slash survives the round-trip`() {
        // parse splits on the FIRST and LAST slashes and rejoins the middle, so an operation id
        // may itself contain slashes. Pinning it because a change to "join with the first two
        // segments" would silently truncate the operation and point a reader at another stream.
        val address = OutputStreamAddress.of("run-7", "stage/step/inner", OutputChannel.STDERR)

        assertEquals(address, OutputStreamAddress.parse(address.stream))
        assertEquals("stage/step/inner", OutputStreamAddress.parse(address.stream)?.operationId)
    }

    @Test
    fun `a blank run id or operation id is refused at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            OutputStreamAddress.of(" ", "op", OutputChannel.STDOUT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OutputStreamAddress.of("run", "", OutputChannel.STDOUT)
        }
    }

    // --- parse refuses, and never throws ------------------------------------

    @Test
    fun `a stream with fewer than three segments is refused`() {
        assertNull(OutputStreamAddress.parse(OutputStreamId("run/stdout")))
        assertNull(OutputStreamAddress.parse(OutputStreamId("stdout")))
    }

    @Test
    fun `a stream whose last segment is not a channel is refused`() {
        // "transcript" is the pre-V2 shape; it is refused because it is not a channel token, not
        // because of its segment count. Pinning that distinction keeps a future migration from
        // re-admitting it by accident.
        assertNull(OutputStreamAddress.parse(OutputStreamId("run/op/transcript")))
        assertNull(OutputStreamAddress.parse(OutputStreamId("run/op/")))
    }

    @Test
    fun `a channel token in the wrong case is refused`() {
        // The token is a wire value compared with `==`. Accepting "STDOUT" here would create a
        // stream id that no other component, all of which compare exactly, would ever match.
        assertNull(OutputStreamAddress.parse(OutputStreamId("run/op/STDOUT")))
        assertNull(OutputStreamAddress.parse(OutputStreamId("run/op/Stdout")))
    }

    @Test
    fun `an empty segment anywhere is refused`() {
        assertNull(OutputStreamAddress.parse(OutputStreamId("a//stdout")), "blank operation id")
        assertNull(OutputStreamAddress.parse(OutputStreamId("//stdout")), "blank run id")
    }

    @Test
    fun `fromToken matches exactly and never throws`() {
        assertEquals(OutputChannel.STDOUT, OutputChannel.fromToken("stdout"))
        assertEquals(OutputChannel.STDERR, OutputChannel.fromToken("stderr"))
        assertNull(OutputChannel.fromToken("STDOUT"))
        assertNull(OutputChannel.fromToken(""))
        assertNull(OutputChannel.fromToken("transcript"))
    }

    // --- the pair an operation writes ---------------------------------------

    @Test
    fun `an operation's two streams are on stdout and stderr of the same operation`() {
        val pair = OperationOutputStreams.of("run-7", "step.out")

        assertEquals(OutputChannel.STDOUT, pair.stdout.channel)
        assertEquals(OutputChannel.STDERR, pair.stderr.channel)
        assertEquals("run-7/step.out/stdout", pair.stdout.stream.value)
        assertEquals("run-7/step.out/stderr", pair.stderr.stream.value)
    }

    @Test
    fun `a pair whose two streams name different operations is refused`() {
        val stdout = OutputStreamAddress.of("run-7", "step.out", OutputChannel.STDOUT)
        val stderr = OutputStreamAddress.of("run-7", "step.err", OutputChannel.STDERR)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            OperationOutputStreams(stdout, stderr)
        }
        assertTrue(ex.message?.contains("same operation") == true, "message names the rule: ${ex.message}")
    }

    @Test
    fun `a pair whose fields are swapped is refused`() {
        // The diagnostic has to name the FIELD that is wrong, not merely that something is wrong:
        // this is the mistake an operator meets when a mapping is transposed, and a message
        // pointing at the other field sends them to the stream that is already correct.
        val stdout = OutputStreamAddress.of("run-7", "op", OutputChannel.STDERR)
        val stderr = OutputStreamAddress.of("run-7", "op", OutputChannel.STDOUT)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            OperationOutputStreams(stdout, stderr)
        }
        assertTrue(ex.message?.contains("stdout") == true,
            "the message must name the stdout field, which is the one holding STDERR: ${ex.message}")
        assertTrue(ex.message?.contains("stderr") == true,
            "the message must name the stderr field, which is the one holding STDOUT: ${ex.message}")
    }

    @Test
    fun `select narrows to the requested channels in the fixed order`() {
        val pair = OperationOutputStreams.of("run-7", "op")

        assertEquals(
            listOf(pair.stdout, pair.stderr),
            pair.select(setOf(OutputChannel.STDERR, OutputChannel.STDOUT)),
            "selection preserves the pair's own order, not the caller's set order",
        )
        assertEquals(listOf(pair.stderr), pair.select(setOf(OutputChannel.STDERR)))
        assertEquals(emptyList<OutputStreamAddress>(), pair.select(emptySet()))
    }
}
