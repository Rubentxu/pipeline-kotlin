package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The option that tells a run where its broker is, on the command line.
 *
 * ## The guarantees under trial
 *
 *  - only the verb that runs steps accepts the option: `validate` compiles a script and asks no
 *    credential of anybody, so the option is refused there by the name of its owner, and the verbs
 *    that read a run refuse it by name too;
 *  - the directory travels as given, and a run that was not given one has none;
 *  - an option without a directory is refused by name.
 *
 * ## What this does not freeze
 *
 * Where in the command line the option sits, and what the directory is called on disk.
 */
class CredentialBrokerOptionTest {

    @Test
    fun `the verb that runs nothing refuses the broker of a run`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("validate", "--credential-broker", "/tmp/broker", "build.kts"),
        )

        val rejected = assertInstanceOf(CliParseResult.Rejected::class.java, parsed)
        val error = assertInstanceOf(CliError.OptionBelongsToVerb::class.java, rejected.error)
        assertEquals("--credential-broker", error.option)
        assertEquals("run", error.owner)
    }

    @Test
    fun `the verb that runs accepts the broker of a run and carries its directory as given`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--credential-broker", "/tmp/broker", "build.kts"),
        )

        val flags = assertInstanceOf(CliParseResult.Parsed::class.java, parsed).flags
        assertEquals("/tmp/broker", flags.credentialBrokerPath)
    }

    @Test
    fun `a verb that reads refuses the broker of a run by naming it`() {
        val parsed = CliObservationParser.parse(arrayOf("--credential-broker", "/tmp/broker", "run-1"))

        val rejected = assertInstanceOf(ObservationParseResult.Rejected::class.java, parsed)
        assertEquals(CliError.OptionNotReadable("--credential-broker"), rejected.error)
    }

    @Test
    fun `a run that was given no broker has none`() {
        val parsed = CliExecutionParser.parse(arrayOf("run", "build.kts"))

        val flags = assertInstanceOf(CliParseResult.Parsed::class.java, parsed).flags
        assertNull(flags.credentialBrokerPath)
    }

    @Test
    fun `a broker without a directory is refused by the command line`() {
        val parsed = CliExecutionParser.parse(arrayOf("run", "--credential-broker", " ", "build.kts"))

        val rejected = assertInstanceOf(CliParseResult.Rejected::class.java, parsed)
        val error = assertInstanceOf(CliError.MissingOptionValue::class.java, rejected.error)
        assertEquals("--credential-broker", error.option)
    }
}
