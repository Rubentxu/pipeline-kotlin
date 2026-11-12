package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.RecordBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Each verb answers for its own options, and says so when the option is not its own.
 *
 * **The guarantee.** Every verb of the CLI parses through ONE contract of
 * arguments: the verb that runs admits the options a run needs and refuses the
 * reader's options by naming their owner; the verb that reads admits the
 * reader's options and refuses the ones that change what a run DOES by naming
 * the flag; the verbs with a closed option set of their own (`inspect`,
 * `events`, `console`) admit the options they own and refuse every other token
 * by name. The two verbs that share a vocabulary (`run` and `observe`) give the
 * SAME flag the SAME meaning, because the meaning is decided in one table.
 *
 * **The capacity this serves.** An operator who writes an option against the
 * wrong verb learns which verb owns it instead of watching a flag be parsed,
 * remembered and never read. The two halves of that sentence were both broken
 * before this, and in opposite directions: `run` accepted `--limit` and
 * silently truncated the live transcript it was printing, and `observe`
 * accepted `--resume` and behaved as if the reader had chosen which run to
 * read. Nothing failed when the split drifted, because the split was a table
 * and the tables had no reader: the vocabulary file said so in its own KDoc,
 * and this instrument is the first thing that reads that part of it.
 *
 * **Why this instrument.** The guarantee is about a decision, and every
 * decision here is a PURE function over an array of arguments: no filesystem,
 * no clock, no process, no journal, no run. The cheapest instrument that can
 * fail is therefore to call the parser and read the typed result — no CLI
 * process, no pipe, no temporary directory. The alternative — running the
 * built binary and grepping its stderr — would check the same fact with a
 * process in the way, and it would say THAT the refusal was wrong, never
 * WHICH verb disagreed about WHICH option.
 *
 * The expectations are LITERAL: each row names the exact refusal the verb
 * owes, spelled out here rather than computed from the table under test. A
 * row that computed the expectation from the table would agree with any
 * table, including a broken one.
 *
 * **What this does not freeze.** The file layout, the class names, the order
 * of the options inside a table, the words an operator reads on stderr, and
 * the exit code a refusal produces. It freezes the DECISION each verb makes
 * about a named option: admitted or refused, and with which typed refusal.
 *
 * **What this instrument cannot see.** The shape of the code that makes the
 * decision. The same decisions are reachable from one unit per verb or from
 * one unit for the whole CLI, and this instrument answers "does the verb say
 * the right thing", never "is the parser split into units". That part of the
 * guarantee is measured by the LOC and complexity comparison, not here.
 */
class CliVerbOptionContractTest {

    @Test
    fun `the verb that runs admits the option only a run may carry`() {
        val flags = CliExecutionParser.parse(arrayOf("run", "--isolated", SCRIPT)).parsed()

        assertTrue(flags.isolated, "run accepted --isolated and did not apply it")
    }

    @Test
    fun `the verb that runs refuses a reader's option by naming its owner`() {
        val error = CliExecutionParser.parse(arrayOf("run", "--limit", "5", SCRIPT)).rejected()

        assertEquals(
            CliError.OptionBelongsToObserve("--limit"),
            error,
            "run does not refuse a reader's option by naming the reader that owns it",
        )
    }

    @Test
    fun `the verb that validates answers through the same contract as the verb that runs`() {
        val flags = CliExecutionParser.parse(arrayOf("validate", "--isolated", SCRIPT)).parsed()

        assertEquals(CliCommand.VALIDATE, flags.command, "validate answered as another command")
        assertTrue(flags.isolated, "validate accepted --isolated and did not apply it")
    }

    @Test
    fun `the verb that runs admits the receipt and applies it`() {
        val flags = CliExecutionParser.parse(arrayOf("run", "--receipt", RECEIPT, SCRIPT)).parsed()

        assertEquals(RECEIPT, flags.receiptPath, "run accepted --receipt and did not apply it")
    }

    @Test
    fun `the verb that runs refuses a receipt with no path`() {
        val error = CliExecutionParser.parse(arrayOf("run", "--receipt")).rejected()

        assertEquals(
            CliError.MissingOptionValue("--receipt"),
            error,
            "run does not refuse a receipt with no path by naming the option",
        )
    }

    @Test
    fun `the verb that runs refuses a receipt with an empty path`() {
        val error = CliExecutionParser.parse(arrayOf("run", "--receipt", "", SCRIPT)).rejected()

        assertEquals(
            CliError.MissingOptionValue("--receipt"),
            error,
            "run does not refuse a receipt with an empty path by naming the option",
        )
    }

    @Test
    fun `the verb that validates refuses the receipt by naming the verb that owns it`() {
        val error = CliExecutionParser.parse(arrayOf("validate", "--receipt", RECEIPT, SCRIPT)).rejected()

        assertEquals(
            CliError.OptionBelongsToVerb("--receipt", "run", VALIDATE_PUBLISHES_NOTHING),
            error,
            "validate does not refuse the receipt by naming the verb that owns it",
        )
    }

    /**
     * `--compile-only` asks for a compilation WITHOUT running the author's
     * body, and `validate` is the verb that can keep that promise.
     */
    @Test
    fun `the verb that validates admits the compile-only form and applies it`() {
        val flags = CliExecutionParser.parse(arrayOf("validate", "--compile-only", SCRIPT)).parsed()

        assertTrue(flags.compileOnly, "validate accepted --compile-only and did not apply it")
    }

    @Test
    fun `the verb that runs refuses the compile-only form by naming the verb that owns it`() {
        val error = CliExecutionParser.parse(arrayOf("run", "--compile-only", SCRIPT)).rejected()

        assertEquals(
            CliError.OptionBelongsToVerb("--compile-only", "validate", COMPILE_ONLY_BELONGS_TO_VALIDATE),
            error,
            "run does not refuse --compile-only by naming the verb that owns it",
        )
    }

    /**
     * `--parameter-manifest` asks a validation for the manifest an operator approves for the
     * script, and `validate` is the verb that compiles it.
     */
    @Test
    fun `the verb that validates admits the parameter manifest and applies it`() {
        val flags = CliExecutionParser.parse(arrayOf("validate", "--parameter-manifest", SCRIPT)).parsed()

        assertTrue(flags.parameterManifest, "validate accepted --parameter-manifest and did not apply it")
    }

    @Test
    fun `the verb that runs refuses the two documents of the declaration by naming the verb that owns them`() {
        assertEquals(
            CliError.OptionBelongsToVerb("--describe-parameters", "validate", DESCRIBE_BELONGS_TO_VALIDATE),
            CliExecutionParser.parse(arrayOf("run", "--describe-parameters", SCRIPT)).rejected(),
            "run does not refuse --describe-parameters by naming the verb that owns it",
        )
        assertEquals(
            CliError.OptionBelongsToVerb("--parameter-manifest", "validate", MANIFEST_BELONGS_TO_VALIDATE),
            CliExecutionParser.parse(arrayOf("run", "--parameter-manifest", SCRIPT)).rejected(),
            "run does not refuse --parameter-manifest by naming the verb that owns it",
        )
    }

    /**
     * The declaration is what the script's body builds, so a validation that does not run the
     * body has none to print: printing an empty one would describe a script that declares
     * parameters as one that declares none.
     */
    @Test
    fun `a validation that does not run the body refuses to print a declaration it never built`() {
        assertEquals(
            CliError.InvalidCombination(DESCRIBE_NEEDS_THE_BODY),
            CliExecutionParser.parse(arrayOf("validate", "--compile-only", "--describe-parameters", SCRIPT)).rejected(),
        )
        assertEquals(
            CliError.InvalidCombination(MANIFEST_NEEDS_THE_BODY),
            CliExecutionParser.parse(arrayOf("validate", "--parameter-manifest", "--compile-only", SCRIPT)).rejected(),
        )
    }

    @Test
    fun `a validation prints one document of the declaration, not two`() {
        assertEquals(
            CliError.InvalidCombination(ONE_DOCUMENT),
            CliExecutionParser.parse(arrayOf("validate", "--describe-parameters", "--parameter-manifest", SCRIPT))
                .rejected(),
        )
    }

    @Test
    fun `the verb that reads refuses the receipt by naming it`() {
        val error = CliObservationParser.parse(arrayOf("--receipt", RECEIPT, RUN_ID)).rejected()

        assertEquals(
            CliError.OptionNotReadable("--receipt"),
            error,
            "observe does not refuse the receipt by naming it",
        )
    }

    @Test
    fun `the verb that reads refuses an option that changes what a run does`() {
        val error = CliObservationParser.parse(arrayOf("--resume", RUN_ID)).rejected()

        assertEquals(
            CliError.OptionNotReadable("--resume"),
            error,
            "observe does not refuse an execution-only option by naming it",
        )
    }

    @Test
    fun `the verb that reads admits the reader's own budget`() {
        val parsed = CliObservationParser.parse(arrayOf("--limit", "5", RUN_ID)).parsed()

        assertEquals(
            RecordBudget.UpTo(5),
            parsed.budget,
            "observe accepted --limit and did not apply it",
        )
    }

    @Test
    fun `the verb that inspects admits its own option and the value arrives`() {
        val parsed = CliInspectParser.parse(arrayOf(RUN_ID, "--step", "3")).parsed()

        assertEquals(3L, parsed.value.step, "inspect accepted --step and did not apply it")
    }

    @Test
    fun `the verb that inspects refuses an option it does not own`() {
        val error = CliInspectParser.parse(arrayOf(RUN_ID, "--grep", "needle")).rejected()

        assertEquals(
            CliError.UnknownOption("--grep"),
            error,
            "inspect does not refuse an option it does not own by naming it",
        )
    }

    @Test
    fun `the verb that reads events admits its own option and the value arrives`() {
        val parsed = CliEventsParser.parse(arrayOf("--db", EVENTS_DB, RUN_ID, "--limit", "5")).parsed()

        assertEquals(5, parsed.args.limit, "events accepted --limit and did not apply it")
    }

    @Test
    fun `the verb that reads events refuses an option it does not own`() {
        val result = CliEventsParser.parse(arrayOf("--db", EVENTS_DB, RUN_ID, "--grep", "needle"))

        val reason = when (result) {
            is EventsCliParseResult.Rejected -> result.reason
            is EventsCliParseResult.Parsed -> fail("events admitted an option it does not own")
        }
        assertEquals(
            "unknown option: --grep",
            reason,
            "events does not refuse an option it does not own by naming it",
        )
    }

    @Test
    fun `the verb that reads the console admits its own option and the value arrives`() {
        val request = CliConsoleParser.parse(
            arrayOf("--control-dir", CONTROL_DIR, RUN_ID, OP_ID, "--max-bytes", "1024"),
        ).admitted()

        val paged = requireNotNull(request as? ConsoleReadRequest.Paged) {
            "console did not admit the byte-budgeted read it was asked for: $request"
        }
        assertEquals(CONTROL_DIR, paged.controlDir, "console lost the control directory it was given")
        assertEquals(RUN_ID, paged.runId, "console lost the run id it was given")
        assertEquals(OP_ID, paged.opId, "console lost the operation id it was given")
        assertEquals(1024, paged.maxBytes, "console accepted --max-bytes and did not apply it")
    }

    @Test
    fun `the verb that reads the console refuses an option it does not own`() {
        val error = CliConsoleParser.parse(
            arrayOf("--control-dir", CONTROL_DIR, RUN_ID, OP_ID, "--grep", "needle"),
        ).rejected()

        assertEquals(
            ConsoleCliRefusal.UnknownOption("--grep"),
            error,
            "console does not refuse an option it does not own by naming it",
        )
    }

    @Test
    fun `the two verbs that share a vocabulary give the same flag the same meaning`() {
        val running = CliExecutionParser.parse(arrayOf("run", "--grep", "needle", SCRIPT)).parsed()
        val reading = CliObservationParser.parse(arrayOf("--grep", "needle", RUN_ID)).parsed()

        assertEquals(
            running.query,
            reading.query,
            "run and observe read the same --grep into different queries",
        )
    }

    private fun CliParseResult.parsed(): CliFlags = when (this) {
        is CliParseResult.Parsed -> flags
        is CliParseResult.Rejected -> fail("the request was refused: $error")
    }

    private fun CliParseResult.rejected(): CliError = when (this) {
        is CliParseResult.Rejected -> error
        is CliParseResult.Parsed -> fail("the request was admitted: $flags")
    }

    private fun ObservationParseResult.parsed(): ObservationParseResult.Parsed = when (this) {
        is ObservationParseResult.Parsed -> this
        is ObservationParseResult.Rejected -> fail("the request was refused: $error")
    }

    private fun ObservationParseResult.rejected(): CliError = when (this) {
        is ObservationParseResult.Rejected -> error
        is ObservationParseResult.Parsed -> fail("the request was admitted: $query")
    }

    private fun InspectParseResult.parsed(): InspectParseResult.Parsed = when (this) {
        is InspectParseResult.Parsed -> this
        is InspectParseResult.Rejected -> fail("the request was refused: $error")
    }

    private fun InspectParseResult.rejected(): CliError = when (this) {
        is InspectParseResult.Rejected -> error
        is InspectParseResult.Parsed -> fail("the request was admitted: $value")
    }

    private fun EventsCliParseResult.parsed(): EventsCliParseResult.Parsed = when (this) {
        is EventsCliParseResult.Parsed -> this
        is EventsCliParseResult.Rejected -> fail("the request was refused: $reason")
    }

    private fun ConsoleCliParseResult.admitted(): ConsoleReadRequest = when (this) {
        is ConsoleCliParseResult.Admitted -> request
        is ConsoleCliParseResult.Rejected -> fail("the request was refused: $refusal")
    }

    private fun ConsoleCliParseResult.rejected(): ConsoleCliRefusal = when (this) {
        is ConsoleCliParseResult.Rejected -> refusal
        is ConsoleCliParseResult.Admitted -> fail("the request was admitted: $request")
    }

    private companion object {
        const val SCRIPT = "/tmp/pipelinek-verification-script.kts"
        const val RUN_ID = "run-1"
        const val OP_ID = "op-1"
        const val CONTROL_DIR = "/tmp/pipelinek-verification-control"
        const val EVENTS_DB = "/tmp/pipelinek-verification-events.db"
        const val RECEIPT = "/tmp/pipelinek-verification-receipt.json"

        /**
         * Why the receipt is refused by the verb that never runs anything.
         *
         * The expectation is a literal: a row that computed the reason from the code under
         * test would agree with every spelling of it, including the wrong one.
         */
        const val VALIDATE_PUBLISHES_NOTHING =
            "'validate' compiles a script and publishes nothing, so it has no receipt to write."

        /**
         * Why the compile-only form is refused by the verb that runs the body.
         *
         * The expectation is a literal: a row that computed the reason from the code under
         * test would agree with every spelling of it, including the wrong one.
         */
        const val COMPILE_ONLY_BELONGS_TO_VALIDATE =
            "'run' executes the script, so compiling it without running it is 'validate --compile-only'."

        /** Why the description of the declaration is refused by the verb that runs the script. */
        const val DESCRIBE_BELONGS_TO_VALIDATE =
            "'run' executes the script, so describing its parameters is 'validate --describe-parameters'."

        /** Why the manifest of the declaration is refused by the verb that runs the script. */
        const val MANIFEST_BELONGS_TO_VALIDATE =
            "'run' executes the script, so writing the manifest of its parameters is " +
                "'validate --parameter-manifest'."

        const val DESCRIBE_NEEDS_THE_BODY =
            "--describe-parameters prints the declaration the script's body builds, and " +
                "--compile-only does not run the body"

        const val MANIFEST_NEEDS_THE_BODY =
            "--parameter-manifest prints the declaration the script's body builds, and " +
                "--compile-only does not run the body"

        const val ONE_DOCUMENT =
            "--describe-parameters and --parameter-manifest each print one document on the " +
                "standard output; ask for one of them"
    }
}
