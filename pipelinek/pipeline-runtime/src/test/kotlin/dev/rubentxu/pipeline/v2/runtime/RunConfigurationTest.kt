package dev.rubentxu.pipeline.v2.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The configuration a client delivers with a request to start a run.
 *
 * ## The guarantee under trial
 *
 * That a configuration is either ACCEPTED whole or REFUSED whole, and that
 * the refusal says WHICH rule it broke. A configuration that reaches a run
 * with half of its entries dropped is a configuration the client did not
 * ask for, and a client that cannot tell a refusal from a partial
 * acceptance cannot act on either.
 *
 * ## Why the rules are tested HERE and not in the caller
 *
 * Because there are three callers —the service, the command line that reads
 * the snapshot, and the runtime that projects the values— and the rules
 * live in ONE type. A test here is a test of all three, because all three
 * ask this type.
 *
 * ## What the rows are partitioned by
 *
 * By the rule each row breaks, and by the BOUNDARY of each rule: the last
 * name that fits and the first that does not, the last value that fits and
 * the first that does not, the last entry that fits and the first that does
 * not. A rule tested only in the middle is a rule whose edges are unknown.
 */
class RunConfigurationTest {

    @Test
    fun `a configuration with no entries is accepted and carries nothing`() {
        val outcome = RunConfiguration.of(emptyMap())

        val accepted = assertInstanceOf(RunConfigurationOutcome.Accepted::class.java, outcome)
        assertTrue(accepted.configuration.isEmpty)
        assertEquals(emptyMap<String, String>(), accepted.configuration.values)
    }

    @Test
    fun `the last entry that fits is accepted and the one that does not is refused`() {
        val fits = RunConfiguration.of((1..RunConfiguration.MAX_ENTRIES).associate { "N$it" to "v" })
        assertInstanceOf(RunConfigurationOutcome.Accepted::class.java, fits)

        val doesNotFit = RunConfiguration.of(
            (1..RunConfiguration.MAX_ENTRIES + 1).associate { "N$it" to "v" },
        )
        val refused = assertInstanceOf(RunConfigurationOutcome.Refused::class.java, doesNotFit)
        assertEquals("the-configuration-has-too-many-entries", refused.refusal.reason)
    }

    @Test
    fun `the last name that fits is accepted and the one that does not is refused`() {
        val name = "N".repeat(RunConfiguration.MAX_NAME_LENGTH)
        assertInstanceOf(
            RunConfigurationOutcome.Accepted::class.java,
            RunConfiguration.of(mapOf(name to "v")),
        )

        val tooLong = "N".repeat(RunConfiguration.MAX_NAME_LENGTH + 1)
        val refused = assertInstanceOf(
            RunConfigurationOutcome.Refused::class.java,
            RunConfiguration.of(mapOf(tooLong to "v")),
        )
        assertEquals("the-configuration-name-is-too-long", refused.refusal.reason)
    }

    @Test
    fun `a name that is not the shape of a name is refused`() {
        val refused = assertInstanceOf(
            RunConfigurationOutcome.Refused::class.java,
            RunConfiguration.of(mapOf("1LEADING_DIGIT" to "v")),
        )
        assertEquals("the-configuration-name-is-not-legal", refused.refusal.reason)
    }

    @Test
    fun `the PATH+ form is not expressible because the alphabet has no plus`() {
        val refused = assertInstanceOf(
            RunConfigurationOutcome.Refused::class.java,
            RunConfiguration.of(mapOf("PATH+M2" to "/opt/maven/bin")),
        )
        assertEquals("the-configuration-name-is-not-legal", refused.refusal.reason)
    }

    @Test
    fun `the names of the product are refused`() {
        for (name in listOf("PIPELINE_STORE_PASSPHRASE", "PIPELINEK_SERVICE_TOKEN", "DURABLE_SH_COOKIE")) {
            val refused = assertInstanceOf(
                RunConfigurationOutcome.Refused::class.java,
                RunConfiguration.of(mapOf(name to "v")),
                "the name $name is the product's own",
            )
            assertEquals("the-configuration-name-is-reserved", refused.refusal.reason)
        }
    }

    @Test
    fun `the names the sandbox removes from an environment are refused`() {
        for (name in listOf("LD_PRELOAD", "BASH_ENV", "IFS", "JAVA_TOOL_OPTIONS", "BASH_FUNC_x")) {
            val refused = assertInstanceOf(
                RunConfigurationOutcome.Refused::class.java,
                RunConfiguration.of(mapOf(name to "v")),
                "the name $name is denied by the sandbox",
            )
            assertEquals("the-configuration-name-is-denied", refused.refusal.reason)
        }
    }

    @Test
    fun `the names the launcher consumes to build the PATH are refused`() {
        for (name in listOf("JAVA_HOME", "M2_HOME")) {
            val refused = assertInstanceOf(
                RunConfigurationOutcome.Refused::class.java,
                RunConfiguration.of(mapOf(name to "/tmp/evil")),
                "the name $name is the launcher's own",
            )
            assertEquals("the-configuration-name-is-a-path-anchor", refused.refusal.reason)
        }
    }

    @Test
    fun `the last value that fits is accepted and the one that does not is refused`() {
        val value = "v".repeat(RunConfiguration.MAX_VALUE_LENGTH)
        assertInstanceOf(
            RunConfigurationOutcome.Accepted::class.java,
            RunConfiguration.of(mapOf("K" to value)),
        )

        val tooLong = "v".repeat(RunConfiguration.MAX_VALUE_LENGTH + 1)
        val refused = assertInstanceOf(
            RunConfigurationOutcome.Refused::class.java,
            RunConfiguration.of(mapOf("K" to tooLong)),
        )
        assertEquals("the-configuration-value-is-too-long", refused.refusal.reason)
    }

    @Test
    fun `a value with a NUL is refused because the launcher refuses it`() {
        val refused = assertInstanceOf(
            RunConfigurationOutcome.Refused::class.java,
            RunConfiguration.of(mapOf("K" to "a\u0000b")),
        )
        assertEquals("the-configuration-value-has-a-nul", refused.refusal.reason)
    }

    @Test
    fun `the canonical text of one entry is the length of the name, the name, the length of the value and the value`() {
        assertEquals(
            "1:A=1:1",
            RunConfiguration.canonicalText(mapOf("A" to "1")),
            "the canonical text says where every field ends, and it says it with the lengths",
        )
    }

    @Test
    fun `the canonical text of two entries is the two of them in the order of their names`() {
        assertEquals(
            "1:A=1:1|1:B=1:2",
            RunConfiguration.canonicalText(mapOf("B" to "2", "A" to "1")),
            "the canonical text is the entries sorted by name, and it is the SAME text",
        )
    }

    @Test
    fun `the values of a configuration are the values it was given`() {
        val outcome = RunConfiguration.of(mapOf("BUILD_PROFILE" to "release"))
        val accepted = assertInstanceOf(RunConfigurationOutcome.Accepted::class.java, outcome)

        assertEquals(
            mapOf("BUILD_PROFILE" to "release"),
            accepted.configuration.values,
            "the configuration carries the values the client delivered",
        )
        assertEquals(
            1,
            accepted.configuration.size,
            "the size of a configuration is the number of its entries",
        )
    }

    @Test
    fun `a configuration that carries entries is not empty`() {
        val outcome = RunConfiguration.of(mapOf("BUILD_PROFILE" to "release"))
        val accepted = assertInstanceOf(RunConfigurationOutcome.Accepted::class.java, outcome)

        assertFalse(
            accepted.configuration.isEmpty,
            "a configuration that carries an entry is not the configuration that carries nothing",
        )
    }

    @Test
    fun `two configurations with the same values are the same configuration`() {
        val one = accepted(mapOf("BUILD_PROFILE" to "release"))
        val other = accepted(mapOf("BUILD_PROFILE" to "release"))
        val different = accepted(mapOf("BUILD_PROFILE" to "debug"))

        assertEquals(one, other, "the same values are the same configuration")
        assertEquals(one.hashCode(), other.hashCode(), "the same configuration has the same hash")
        assertNotEquals(one, different, "two configurations with other values are not the same")
        assertEquals(
            mapOf("BUILD_PROFILE" to "release").hashCode(),
            one.hashCode(),
            "the hash of a configuration is the hash of its values, and not a constant",
        )
    }

    @Test
    fun `the shape of a configuration says how many entries it carries`() {
        val configuration = accepted(mapOf("BUILD_PROFILE" to "release"))

        assertEquals(
            "RunConfiguration(1 entries)",
            configuration.toString(),
            "the shape of a configuration is its shape and not its values",
        )
    }

    @Test
    fun `a refusal says which rule was broken and why`() {
        val refused = assertInstanceOf(
            RunConfigurationOutcome.Refused::class.java,
            RunConfiguration.of(mapOf("LD_PRELOAD" to "/tmp/x.so")),
        )

        assertTrue(
            refused.refusal.detail.isNotBlank(),
            "a refusal that names the rule also says what to do about it",
        )
        assertEquals(
            "the-configuration-name-is-denied",
            refused.refusal.reason,
            "the reason is the word the wire carries",
        )
    }

    @Test
    fun `the order of the rules is the order of the reasons`() {
        // A configuration that breaks TWO rules at once always produces the
        // reason of the FIRST rule, and the order is fixed so that the same
        // configuration always produces the same reason.
        assertEquals(
            "the-configuration-name-is-too-long",
            refusedReason(mapOf("1" + "N".repeat(RunConfiguration.MAX_NAME_LENGTH) to "v")),
            "how long the name is comes before whether the name has the shape of a name",
        )
        assertEquals(
            "the-configuration-name-is-not-legal",
            refusedReason(mapOf("PIPELINE_A!" to "v")),
            "whether the name has the shape of a name comes before whether it belongs to the product",
        )
        assertEquals(
            "the-configuration-name-is-reserved",
            refusedReason(mapOf("PIPELINE_A" to "v".repeat(RunConfiguration.MAX_VALUE_LENGTH + 1))),
            "whether the name belongs to the product comes before how long the value is",
        )
        assertEquals(
            "the-configuration-name-is-denied",
            refusedReason(mapOf("LD_PRELOAD" to "v".repeat(RunConfiguration.MAX_VALUE_LENGTH + 1))),
            "whether the sandbox denies the name comes before how long the value is",
        )
        assertEquals(
            "the-configuration-name-is-a-path-anchor",
            refusedReason(mapOf("JAVA_HOME" to "v".repeat(RunConfiguration.MAX_VALUE_LENGTH + 1))),
            "whether the name is an anchor of the PATH comes before how long the value is",
        )
    }

    private fun accepted(values: Map<String, String>): RunConfiguration {
        val outcome = RunConfiguration.of(values)
        return (outcome as RunConfigurationOutcome.Accepted).configuration
    }

    private fun refusedReason(values: Map<String, String>): String {
        val outcome = RunConfiguration.of(values)
        val refused = assertInstanceOf(
            RunConfigurationOutcome.Refused::class.java,
            outcome,
            "the configuration $values breaks a rule",
        )
        return refused.refusal.reason
    }

    @Test
    fun `the canonical text does not depend on the order the entries arrived in`() {
        val one = RunConfiguration.canonicalText(mapOf("B" to "2", "A" to "1"))
        val other = RunConfiguration.canonicalText(mapOf("A" to "1", "B" to "2"))

        assertEquals(one, other, "the same entries in another order are the same configuration")
    }

    @Test
    fun `the canonical text tells two configurations apart that a plain join would not`() {
        // A plain join of `name=value` would make these two the same text:
        // the first is two entries and the second is one entry whose value
        // happens to look like the other one.
        val twoEntries = RunConfiguration.canonicalText(mapOf("A" to "1", "B" to "2"))
        val oneEntry = RunConfiguration.canonicalText(mapOf("A" to "1\nB=2"))

        assertNotEquals(
            twoEntries,
            oneEntry,
            "two different configurations may not produce the same canonical text",
        )
    }

    @Test
    fun `the canonical text of nothing is empty`() {
        assertEquals("", RunConfiguration.canonicalText(emptyMap()))
    }

    @Test
    fun `the values are projected as PLAIN handles because the channel is public`() {
        val outcome = RunConfiguration.of(mapOf("BUILD_PROFILE" to "release"))
        val accepted = assertInstanceOf(RunConfigurationOutcome.Accepted::class.java, outcome)

        val handle = accepted.configuration.asEnvironment().getValue("BUILD_PROFILE")
        assertEquals("release", handle.materialize())
        assertTrue(
            !handle.isMasked,
            "a value that ends up in the environment of the steps is not a secret",
        )
    }

    @Test
    fun `the configuration does not print its values`() {
        val outcome = RunConfiguration.of(mapOf("PASSWORD" to "hunter2"))
        val accepted = assertInstanceOf(RunConfigurationOutcome.Accepted::class.java, outcome)

        val printed = accepted.configuration.toString()
        assertTrue(!printed.contains("hunter2"), "the values do not travel in a log line")
    }
}
