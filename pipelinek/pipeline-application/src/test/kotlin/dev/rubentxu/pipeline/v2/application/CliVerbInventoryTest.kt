package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The help names the forms the CLI answers, and only those.
 *
 * **The guarantee.** Every form in the inventory reaches the help with its
 * own line, and every line of the help names a form the CLI dispatches: a
 * form with a handler, a command the parser admits, or a form of the family
 * that owns its own CLI. The hint printed on a rejected command enumerates
 * the same forms, and a family word that is not a form of its own is refused
 * by name.
 *
 * **The capacity this serves.** An operator who reads `--help`, or who
 * mistypes a command and reads the hint, learns the words the CLI actually
 * answers to. Both surfaces were wrong before this, and they were wrong in
 * the same direction: the help was missing forms the CLI answered, and it
 * named one form the CLI refuses. `runs recover` and `events verify` were
 * implemented and reachable and the help did not name them; the help named
 * `saga`, and the CLI refuses that word on its own and requires
 * `saga inspect`. The hint enumerated `runs cancel` and `saga` as if they
 * were command words. Nothing failed when the list drifted, because the list
 * was prose: no test and no example looked at the help at all.
 *
 * **Why this instrument.** The guarantee is about a table and two renderings
 * of it, all of them pure: no filesystem, no clock, no process, no run. The
 * cheapest instrument that can fail is to render the help into a buffer and
 * read it against the inventory and the authorities that dispatch. The
 * alternative — a CLI test that runs `--help` and greps the output — would
 * check the same bytes with a process and a pipe in the way, and it would say
 * THAT the help was wrong, never WHICH form was missing.
 *
 * **What this does not freeze.** The wording of a summary, the order of the
 * lines, and the exit code of a refusal. It freezes WHICH forms are named,
 * that each of them is a form the CLI dispatches, and that the enumeration in
 * the hint is the inventory. It does read the help's section header and the
 * hint's shape, because the enumeration in each is only identifiable by it.
 *
 * **What this instrument cannot see.** A read-only form REMOVED from the
 * table takes its capability with it: the table IS the dispatch, so nothing
 * can answer a form the table does not name, and there is no second list left
 * to disagree with it. That shape is prevented by construction rather than by
 * a check here. The two families that DO have an authority outside the table
 * — the commands the parser admits and the verbs the runs family spells — are
 * the ones this instrument can hold complete, and they are checked one by one.
 */
class CliVerbInventoryTest {

    @Test
    fun `the help names every form of the inventory`() {
        val rows = renderedVerbRows()

        assertEquals(
            CLI_VERBS.size,
            rows.size,
            "the help does not render one line per form: ${rows.size} lines for ${CLI_VERBS.size} forms",
        )

        val named = rows.map { row ->
            requireNotNull(labelOf(row)) {
                "the help renders a line that names no form of the inventory: '$row'"
            }
        }
        assertEquals(
            CLI_VERBS.map { it.label }.toSet(),
            named.toSet(),
            "the help names forms the inventory does not have, or misses forms it has",
        )
    }

    @Test
    fun `every form the help names is a form the CLI dispatches`() {
        val parserForms = CLI_PARSER_COMMANDS.keys
        val runsForms = MainRunsCli.RunsVerb.entries
            .map { "${MainRunsCli.FAMILY} ${it.spelling}" }
            .toSet()

        for (verb in CLI_VERBS) {
            val dispatched = verb.handler != null || verb.label in parserForms || verb.label in runsForms
            assertTrue(
                dispatched,
                "the help names '${verb.label}' and nothing dispatches it",
            )
        }
    }

    @Test
    fun `the runs family is named the way the family spells it`() {
        for (spelling in MainRunsCli.RunsVerb.entries) {
            val label = "${MainRunsCli.FAMILY} ${spelling.spelling}"
            val verb = requireNotNull(CLI_VERBS.firstOrNull { it.label == label }) {
                "the help does not name the form '$label'"
            }
            assertEquals(
                spelling.summary,
                verb.summary,
                "the help describes '$label' with another text",
            )
        }
    }

    @Test
    fun `every command the parser admits is a form the help names`() {
        for ((spelling, command) in CLI_PARSER_COMMANDS) {
            val verb = requireNotNull(CLI_VERBS.firstOrNull { it.label == spelling }) {
                "the help does not name the command '$spelling'"
            }
            assertEquals(
                command.summary,
                verb.summary,
                "the help describes '$spelling' with another text",
            )
            assertTrue(
                CliParser.parse(arrayOf(spelling, "/tmp/pipelinek-verification-script.kts")) is CliParseResult.Parsed,
                "the parser refuses the command '$spelling' that the help names",
            )
        }
    }

    @Test
    fun `a family word that is not a form of its own is refused by name`() {
        for (family in CLI_VERBS.mapNotNull { it.family }.distinct()) {
            if (CLI_VERBS.any { it.label == family }) continue
            assertNull(
                cliVerbFor(arrayOf(family)),
                "the family word '$family' is answered as a form",
            )
            val owner = CLI_VERBS.first { it.family == family }
            if (owner.familyUsage != null) {
                assertNotNull(
                    cliFamilyDoor(arrayOf(family)),
                    "the family word '$family' has no refusal",
                )
            }
        }
    }

    @Test
    fun `the longest form answers the words it owns`() {
        // "events" and "events verify" both answer the word "events". The longer
        // form owns the words, because the shorter one is the family of the
        // longer one: answering "events verify" as "events" would read a run's
        // events with the word "verify" as the run's identity.
        val resolved = requireNotNull(cliVerbFor(arrayOf("events", "verify"))) {
            "the dispatcher answers nothing to the words of 'events verify'"
        }
        assertEquals(
            "events verify",
            resolved.label,
            "the words of 'events verify' are answered by another form",
        )
    }

    @Test
    fun `the rejection hint enumerates the forms of the inventory`() {
        val hint = requireNotNull(
            CliError.InvalidCommand("observ").toCliExit().hint,
        ) { "the rejection of an unknown command carries no hint" }
        val enumerated = enumeratedForms(hint)

        assertEquals(
            CLI_VERBS.map { it.label }.toSet(),
            enumerated.toSet(),
            "the hint does not enumerate the forms of the inventory",
        )
        assertEquals(
            CLI_VERBS.size,
            enumerated.size,
            "the hint enumerates a form more than once, or one that is not in the inventory",
        )
    }

    /**
     * The lines the help renders under its list of forms.
     */
    private fun renderedVerbRows(): List<String> {
        val buffer = StringBuilder()
        printUsage(buffer)
        val lines = buffer.toString().lines()
        val header = lines.indexOf("Verbs:")
        check(header >= 0) { "the help renders no list of forms" }
        val rows = mutableListOf<String>()
        for (line in lines.drop(header + 1)) {
            if (line.isBlank()) break
            rows += line
        }
        return rows
    }

    /**
     * The form a rendered line names, or null when it names none.
     *
     * The longest form of the inventory that the line starts with is the one
     * it names: `events verify  check...` starts with both `events` and
     * `events verify`, and it is the longer one that the line is about.
     */
    private fun labelOf(row: String): String? {
        val trimmed = row.trim()
        return CLI_VERBS.map { it.label }
            .filter { trimmed.startsWith("$it ") }
            .maxByOrNull { it.length }
    }

    /**
     * The forms the hint enumerates.
     */
    private fun enumeratedForms(hint: String): List<String> {
        check(hint.contains(ENUMERATION_PREFIX)) { "the hint does not enumerate any form: '$hint'" }
        return hint.substringAfter(ENUMERATION_PREFIX)
            .substringBefore(';')
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private companion object {
        const val ENUMERATION_PREFIX = "expected one of: "
    }
}
