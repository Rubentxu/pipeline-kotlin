package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.LineSelector
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.application.observation.RecordBudget
import dev.rubentxu.pipeline.v2.application.observation.TextSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * How many argv positions an option occupies, and what it may not swallow.
 *
 * ## The defect this file was written against
 *
 * [CliParser.applyOption] opened with
 *
 * ```kotlin
 * val value = args.getOrNull(index + 1) ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
 * ```
 *
 * BEFORE dispatching on the option name. That single line made two promises the table of options never
 * made, and both were wrong:
 *
 * 1. **Every option took a value.** `--follow`, `--isolated`, `--allow-network`, `--grep-invert`,
 *    `--resume` and `--rerun` take none, yet each demanded one. `pipelinek run --follow` was refused as
 *    `MissingOptionValue("--follow")` when the truth is [CliError.MissingScriptPath] — the flag was
 *    fine and the script was absent. The diagnostic named the wrong token, so fixing it sent the
 *    caller to write a value that does not exist.
 * 2. **Nothing stopped a valued option from eating the next flag.** `run --control-root --allow-network
 *    demo.kts` stored the literal `"--allow-network"` as the control root and left `allowNetwork`
 *    FALSE. The caller asked to open the network and the run silently did not, then exited 0. That is
 *    the fail-open shape this branch exists to remove, on the flag that gates egress.
 *
 * The fix is a single table of what each option is ([CliOption] and its arity), consulted before the
 * value is read. Nothing here is a second parser: `run` and `observe` still share one table, which is
 * what stops a flag from meaning two things.
 *
 * ## Harness fidelity
 *
 * HF0 Pure Contract. [CliParser.parse] and [CliParser.parseObservation] are pure over `Array<String>`:
 * no clock, no filesystem, no ambient state. Calling the production entry point directly IS the
 * faithful level; nothing here reimplements the parser to check it.
 *
 * ## The mutations that must kill this
 *
 * - **ARITY-1** — delete the arity table and restore `val value = args.getOrNull(index + 1)` ahead of
 *   the dispatch. Kills ARITY-FLAG-RUN and ARITY-FLAG-OBSERVE together, and returns CONSUME-* to
 *   parsed.
 * - **ARITY-2** — make every option `Valued` in the table, keeping the lookup. Kills the FLAG rows
 *   alone, and is the narrower mutation: it proves the table is what routes arity, not merely that
 *   arity was added somewhere.
 * - **CONSUME-1** — drop the `value in KNOWN_OPTIONS` refusal. Kills CONSUME-* alone; nothing else in
 *   this file observes it.
 * - **CONSUME-2** — invert the refusal, rejecting ANY value starting with `--`. Killed by
 *   VALUE-LITERAL-*, which is the row that stops the guard from becoming a new over-rejection.
 */
@DisplayName("CLI — aridad de opcion: una bandera sin valor no es una opcion a la que le falte valor")
class CliParserArityTest {

    private fun parsed(args: Array<String>): CliFlags {
        val result = CliParser.parse(args)
        assertTrue(result is CliParseResult.Parsed, "expected a parse, got $result")
        return (result as CliParseResult.Parsed).flags
    }

    private fun rejected(args: Array<String>): CliError {
        val result = CliParser.parse(args)
        assertTrue(result is CliParseResult.Rejected, "expected a rejection, got $result")
        return (result as CliParseResult.Rejected).error
    }

    private fun observed(args: Array<String>): ObservationParseResult.Parsed {
        val result = CliParser.parseObservation(args)
        assertTrue(result is ObservationParseResult.Parsed, "expected a parse, got $result")
        return result as ObservationParseResult.Parsed
    }

    private fun observedRejected(args: Array<String>): CliError {
        val result = CliParser.parseObservation(args)
        assertTrue(result is ObservationParseResult.Rejected, "expected a rejection, got $result")
        return (result as ObservationParseResult.Rejected).error
    }

    // ---------------------------------------------------------------- ARITY-FLAG-RUN

    @Test
    fun `ARITY-FLAG-RUN una bandera booleana al final no se acusa de valor ausente`() {
        // Every one of these takes NO value. The absence the parser should name is the script.
        val flags = listOf("--follow", "--isolated", "--allow-network", "--resume", "--rerun", "--grep-invert")

        flags.forEach { flag ->
            assertEquals(
                CliError.MissingScriptPath,
                rejected(arrayOf("run", flag)),
                "'$flag' is a boolean: it never wanted a value, so the thing that is missing is the " +
                    "script. Naming '$flag' as lacking a value sends the caller to write one.",
            )
        }
    }

    @Test
    fun `ARITY-FLAG-RUN la bandera se aplica y el script sigue siendo la ultima posicion`() {
        // `--follow` is deliberately absent: `run` refuses it by name (OptionBelongsToObserve), which
        // is a different rule tested elsewhere. This row is only about arity, so the flags used are
        // the ones `run` actually accepts.
        val flags = parsed(
            arrayOf("run", "--isolated", "--allow-network", "--grep-invert", "--grep", "keep", "demo.kts"),
        )

        assertTrue(flags.isolated, "--isolated was consumed")
        assertTrue(flags.allowNetwork, "--allow-network was consumed")
        assertEquals("demo.kts", flags.scriptPath, "the script is still the last position")
        assertEquals(
            LineSelector.Except(listOf(TextSelector.Literal("keep"))),
            flags.query.lines,
            "and the flag run between two values did not steal either of them",
        )
    }

    // ---------------------------------------------------------------- ARITY-FLAG-OBSERVE

    @Test
    fun `ARITY-FLAG-OBSERVE observe acepta una bandera booleana sin valor`() {
        val parsedFlags = observed(arrayOf("--follow"))

        assertTrue(parsedFlags.follow, "--follow takes no value, so a lone --follow is a complete request")
    }

    @Test
    fun `ARITY-FLAG-OBSERVE una bandera booleana no se traga la siguiente opcion`() {
        val parsedFlags = observed(arrayOf("--follow", "--view", "events"))

        assertTrue(parsedFlags.follow, "--follow stayed a flag rather than reading '--view' as its value")
        assertEquals(ObservationView.EVENTS, parsedFlags.view)
    }

    // ---------------------------------------------------------------- CONSUME

    @Test
    fun `CONSUME-DB una opcion con valor no se come la bandera siguiente`() {
        assertTrue(
            CliParser.parse(arrayOf("run", "--db", "--resume", "demo.kts")) is CliParseResult.Rejected,
            "'--db --resume' stored '--resume' as a database path and silently dropped the policy flag; " +
                "the caller believed two things were asked for and one of them was not applied.",
        )
    }

    @Test
    fun `CONSUME-EGRESS una opcion con valor no se come --allow-network`() {
        val result = CliParser.parse(arrayOf("run", "--control-root", "--allow-network", "demo.kts"))

        assertTrue(
            result is CliParseResult.Rejected,
            "the control root was set to the literal '--allow-network' and egress stayed denied with " +
                "exit 0. A denied network the caller believes they opened is the worst shape this parser " +
                "can produce; got $result",
        )
    }

    @Test
    fun `CONSUME-GREP una opcion con valor no se come la dimension siguiente`() {
        assertTrue(
            CliParser.parse(arrayOf("run", "--grep", "--stage", "build", "demo.kts")) is CliParseResult.Rejected,
            "the pattern became the literal '--stage' and the stage filter vanished, so the run printed " +
                "the wrong records while reporting success",
        )
    }

    @Test
    fun `CONSUME-WORKSPACE una opcion con valor no se come --isolated`() {
        assertTrue(
            CliParser.parse(arrayOf("run", "--workspace", "--isolated", "demo.kts")) is CliParseResult.Rejected,
            "this pair used to bypass ADR-0101 clause 3.4 entirely: '--isolated' was stored as a " +
                "workspace PATH, so isolated stayed FALSE and the conflict guard had nothing to see",
        )
    }

    // ---------------------------------------------------------------- VALUE-LITERAL — the other side

    @Test
    fun `VALUE-LITERAL un valor que no es opcion conocida sigue siendo un valor`() {
        val flags = parsed(arrayOf("run", "--grep", "--not-an-option", "demo.kts"))

        assertEquals(
            LineSelector.Only(listOf(TextSelector.Literal("--not-an-option"))),
            flags.query.lines,
            "the refusal is scoped to tokens this CLI KNOWS. Refusing every '--' would make " +
                "'--grep --verbose-stop' unexpressible, trading one silent drop for a new surprise.",
        )
    }

    @Test
    fun `VALUE-LITERAL un valor con un guion simple nunca fue opcion`() {
        val flags = parsed(arrayOf("run", "--grep", "-v", "demo.kts"))

        assertEquals(LineSelector.Only(listOf(TextSelector.Literal("-v"))), flags.query.lines)
    }

    // ---------------------------------------------------------------- Guards: behaviour that must NOT change

    @Test
    fun `VALUE-ABSENT una opcion que si lleva valor y no lo tiene sigue siendo MissingOptionValue`() {
        assertEquals(CliError.MissingOptionValue("--format"), rejected(arrayOf("run", "--format")))
        assertEquals(CliError.MissingOptionValue("--view"), rejected(arrayOf("run", "--view")))
        assertEquals(CliError.MissingOptionValue("--db"), rejected(arrayOf("run", "--db")))
        assertEquals(CliError.MissingOptionValue("--limit"), observedRejected(arrayOf("--limit")))
        assertEquals(CliError.MissingOptionValue("--tail-bytes"), observedRejected(arrayOf("--tail-bytes")))
        assertEquals(CliError.MissingOptionValue("--grep"), observedRejected(arrayOf("--grep")))
    }

    @Test
    fun `UNKNOWN una opcion que este build no tiene sigue siendo UnknownOption`() {
        assertEquals(CliError.UnknownOption("--verbose"), rejected(arrayOf("run", "--verbose", "demo.kts")))
        assertEquals(CliError.UnknownOption("--verbose"), observedRejected(arrayOf("--verbose")))
    }

    @Test
    fun `UNKNOWN una opcion parecida pero distinta no se confunde con una conocida`() {
        assertEquals(
            CliError.UnknownOption("--follows"),
            rejected(arrayOf("run", "--follows", "demo.kts")),
            "prefix matching is how '--follows' would silently turn into '--follow'",
        )
    }

    @Test
    fun `CONFLICT-POLICY las dos politicas durables siguen sin poderse combinar`() {
        assertEquals(
            CliError.ConflictingDurablePolicies,
            rejected(arrayOf("run", "--resume", "--rerun", "demo.kts")),
        )
    }

    @Test
    fun `CONFLICT-WORKSPACE isolated y workspace reales siguen chocando`() {
        assertEquals(
            CliError.ConflictingWorkspaceModes("/w"),
            rejected(arrayOf("run", "--workspace", "/w", "--isolated", "demo.kts")),
            "ADR-0101 clause 3.4, with both tokens spelled the way the caller meant them",
        )
    }

    @Test
    fun `CONFLICT-NOT-READABLE una opcion de ejecucion sigue siendo rechazada por observe`() {
        listOf("--resume", "--rerun", "--workspace", "--isolated", "--allow-network")
            .forEach { option ->
                assertTrue(
                    observedRejected(arrayOf(option, "x")) is CliError.OptionNotReadable,
                    "'$option' must be named as not readable rather than dropped",
                )
            }
    }

    @Test
    fun `SEMANTICA-INVERT --grep-invert sin grupo sigue sin grupo que negar`() {
        assertEquals(CliError.InvertWithoutGrep, rejected(arrayOf("run", "--grep-invert", "demo.kts")))
        assertEquals(CliError.InvertWithoutGrep, observedRejected(arrayOf("--grep-invert")))
    }

    @Test
    fun `SEMANTICA-INVERT con grupo el sentido lo decide la normalizacion`() {
        val flags = parsed(arrayOf("run", "--grep", "keep", "--grep", "also", "--grep-invert", "demo.kts"))

        assertEquals(
            LineSelector.Except(listOf(TextSelector.Literal("keep"), TextSelector.Literal("also"))),
            flags.query.lines,
        )
    }

    @Test
    fun `SEMANTICA-VALUES los valores siguen llegando intactos`() {
        // `--limit` and `--tail-bytes` are absent: `run` refuses them by name (OptionBelongsToObserve),
        // which is a different rule. This row is only about values surviving the arity split.
        val flags = parsed(
            arrayOf(
                "run",
                "--db", "/tmp/one.db",
                "--control-root", "/tmp/root",
                "--workspace", "/tmp/ws",
                "--plugin-jar", "/tmp/a.jar",
                "--plugin-jar", "/tmp/b.jar",
                "--grep", "needle",
                "--channel", "stdout",
                "demo.kts",
            ),
        )

        assertEquals("/tmp/one.db", flags.dbPath)
        assertEquals("/tmp/root", flags.controlRoot)
        assertEquals("/tmp/ws", flags.workspace)
        assertEquals(listOf("/tmp/a.jar", "/tmp/b.jar"), flags.pluginJars)
        assertEquals("demo.kts", flags.scriptPath)
        assertEquals(
            LineSelector.Only(listOf(TextSelector.Literal("needle"))),
            flags.query.lines,
        )
        assertFalse(flags.isolated, "a real --workspace keeps isolated FALSE")
    }

    @Test
    fun `SEMANTICA-BUDGET los presupuestos numericos siguen llegando intactos a observe`() {
        val parsedFlags = observed(arrayOf("--view", "console", "--limit", "5", "--tail-bytes", "1024"))

        assertEquals(RecordBudget.UpTo(5), parsedFlags.budget)
        assertEquals(1024L, parsedFlags.tailBytes)
    }
}
