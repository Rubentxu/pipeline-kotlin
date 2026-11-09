package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `--endpoint`: the one option that makes this CLI talk to a service.
 *
 * **The guarantee.** The option is admitted by ONE mode of ONE verb, it is
 * read as a value and never as a flag, and every other verb refuses it BY
 * NAME — saying which verb owns it and what the service answers. A verb that
 * dropped it would leave the operator believing in a service it never asked,
 * and a verb that answered from somewhere else would report a fact about a
 * source nobody named.
 *
 * **The capacity this serves.** An operator who points this CLI at a service
 * has to be able to trust that the answer came from there, and an operator
 * who typed the option against the wrong verb has to be told which verb owns
 * it — in the same shape as every other option that belongs to one verb.
 *
 * **Why this instrument.** Every decision here is PURE over an array of
 * arguments: no socket, no service, no store. The one fact that needs a
 * machine — that the remote path prints what the local path prints — is
 * measured by the integration lane, over a real socket.
 */
class CliEndpointAdmissionTest {

    @Test
    fun `the inspect verb reads the endpoint as the value of its own option`() {
        val parsed = CliInspectParser.parse(
            arrayOf("--state", "--endpoint", "/tmp/service.sock", "run-1"),
        )

        val query = (parsed as InspectParseResult.Parsed).value
        assertEquals("/tmp/service.sock", query.endpoint)
        assertEquals(true, query.state)
        assertEquals("run-1", query.runId)
    }

    @Test
    fun `a request that names no endpoint has none`() {
        val parsed = CliInspectParser.parse(arrayOf("--state", "run-1"))

        assertEquals(null, (parsed as InspectParseResult.Parsed).value.endpoint)
    }

    @Test
    fun `an endpoint with no value is refused and not dropped`() {
        val parsed = CliInspectParser.parse(arrayOf("--state", "--endpoint"))

        assertEquals(
            CliError.MissingOptionValue("--endpoint"),
            (parsed as InspectParseResult.Rejected).error,
        )
    }

    @Test
    fun `the endpoint consumes the argument after it and the run id is then missing`() {
        // The same rule every valued option of this CLI follows: the value is
        // the argument after the option, and a run id that was written where
        // a value was expected is a value. The verb refuses the request that
        // has no run id left, and it does not go looking for one.
        val parsed = CliInspectParser.parse(arrayOf("--state", "--endpoint", "run-1"))

        val query = (parsed as InspectParseResult.Parsed).value
        assertEquals("run-1", query.endpoint)
        assertEquals(null, query.runId)
    }

    @Test
    fun `a value spelled like the option itself is refused instead of being swallowed`() {
        val parsed = CliInspectParser.parse(arrayOf("--state", "--endpoint", "--db", "/tmp/store.db", "run-1"))

        assertEquals(
            CliError.OptionValueIsAnOption("--endpoint", "--db"),
            (parsed as InspectParseResult.Rejected).error,
        )
    }

    @Test
    fun `a verb that does not answer a run's state refuses the option by name`() {
        for (verb in listOf("events", "console", "observe", "operations")) {
            val err = ByteArrayOutputStream()
            val stream = PrintStream(err, true, Charsets.UTF_8.name())

            val outcome = handleInventoryVerb(
                arrayOf(verb, "--endpoint", "/tmp/service.sock", "run-1"),
                PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8.name()),
                stream,
            )

            val refusal = err.toString(Charsets.UTF_8)
            assertTrue(
                refusal.contains("$verb: option --endpoint belongs to 'inspect --state'"),
                "the '$verb' verb did not refuse the option by name: $refusal",
            )
            assertEquals(2, (outcome as InventoryVerbOutcome.Answered).exit.code)
        }
    }

    @Test
    fun `a mode the service cannot answer is refused and the local store is not opened`() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())

        val exit = MainInspectCli.main(
            arrayOf("--profile", "--endpoint", "/tmp/service.sock", "run-1"),
            out,
            err,
        )
        out.flush()
        err.flush()

        assertEquals(2, exit, "a request the service cannot answer is the invalid-argument code")
        assertTrue(
            errBytes.toString(Charsets.UTF_8).contains("--endpoint is admitted only with --state"),
            "the refusal names the option: ${errBytes}",
        )
        assertTrue(
            errBytes.toString(Charsets.UTF_8).contains("--profile"),
            "the refusal names the mode: ${errBytes}",
        )
        assertEquals("", outBytes.toString(Charsets.UTF_8), "nothing was answered")
    }
}
