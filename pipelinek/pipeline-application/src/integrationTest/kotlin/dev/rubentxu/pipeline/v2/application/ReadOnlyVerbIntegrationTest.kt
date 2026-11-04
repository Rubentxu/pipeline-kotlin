package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.SqliteSagaObligationStore
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The verbs that answer without writing, over the material the product keeps.
 *
 * ## The guarantee
 *
 * Every read-only verb answers over a REAL store with a REAL run in it, and
 * the answer it gives is the one the taxonomy declares: the code, and the
 * name of the case that owns that code. A verb that stops answering, or that
 * answers with a case the taxonomy does not declare, is caught here.
 *
 * ## Why this instrument is an INTEGRATION test and not a unit test
 *
 * The unit lane already knows what a refusal IS: `CliExitDecisionTest` calls
 * the dispatcher and reads the case it returns. What the unit lane cannot know
 * is whether the verb can ANSWER at all, because answering means opening the
 * durable store, reading a run's history and rendering it. That is three real
 * pieces, and this instrument composes them the way the product wires them:
 * the store is the product's own `SqliteEventStore`, the events are the
 * product's own event types, and the contract is decoded by the product's own
 * codec.
 *
 * The only thing faked here is the world OUTSIDE the product: the directory the
 * material lives in, which is a temporary directory this test owns.
 *
 * ## What is NOT here, and why
 *
 * The quickstart of the README and the installation of the published ZIP are
 * NOT executed: that would need an end-to-end executor, and this repository has
 * none. What the README PROMISES is checked here, though: every command form
 * its quickstart names is a form this CLI dispatches.
 *
 * The `run` verb is not exercised either: it is not a read-only verb and it
 * writes into the directory the process was started in, which a test cannot
 * own. What the run's DEFAULT workspace mode decides IS asserted, as the
 * parser's own decision, because that is the fact nobody asserted.
 *
 * ## The codes this lane reaches, and the ones it does not
 *
 * Reached, each with a row of its own below: 0 (the success a verb answers
 * with), 1 (a contract the run does not meet), 2 (a store that is not there)
 * and 4 (a view this build cannot deliver).
 *
 * Not reached, and DECLARED here instead of being left in silence:
 *
 *  - 3, IncompatibleVersion. Its producers are the `version` verb refusing a
 *    build with no generated version resource — the resource IS in this lane's
 *    classpath, so that refusal cannot be reached from here — and the
 *    credential store refusing to open or to unlock, which belongs to the run
 *    path.
 *  - 5, UnknownExecution. The taxonomy declares it for a run id that is not
 *    in the durable store, and no verb raises it: `observe` answers an empty
 *    success for a run the store does not have, and `saga inspect` refuses the
 *    same store and the same run id with the invalid-argument code. The row
 *    that pins the empty success says so in its message.
 *  - 6, ReadFailure, and 7, IntegrityViolation. Neither case is raised
 *    anywhere in the product's sources.
 *
 * An absence is not something an instrument can prove over a codebase, so
 * these are declared facts and not assertions: the rows below affirm the
 * codes that ARE produced, and this paragraph says why the others are not.
 */
class ReadOnlyVerbIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The durable store the product writes to, and the read verbs read from. */
    private lateinit var db: Path

    /** The workspace the run's material belongs to. */
    private lateinit var workspace: Path

    private val runId = "run-integration-1"

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        workspace = Files.createDirectories(root.resolve("workspace"))
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val store = (admission as EventStoreAdmission.Admitted).store
        // The sequence is the STORE's to assign: a sequence of zero is the
        // store's own way of saying "give me the next one for this run". A
        // sequence written here would be a second authority over the order of
        // the run's history, and the store would refuse the day the two
        // disagreed.
        store.append(RunStarted(
            eventId = "event-1",
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.EPOCH,
            scriptPath = workspace.resolve("pipeline.kts").toString(),
        ))
        store.append(StepStarted(
            eventId = "event-2",
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.EPOCH,
            stageIndex = 0,
            stepIndex = 0,
            stepName = "build",
            stepType = "core.sh",
        ))
        store.append(StepFinished(
            eventId = "event-3",
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.EPOCH,
            stageIndex = 0,
            stepIndex = 0,
            stepName = "build",
            stepType = "core.sh",
            outcome = "success",
        ))
        store.append(RunFinished(
            eventId = "event-4",
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.EPOCH,
            outcome = "success",
            diagnostics = emptyList(),
        ))
        // The writer is asynchronous: a reader that opened the store now would
        // find a run whose last event had not landed yet.
        store.flush()
        val connectionFactory = store.underlyingConnectionFactory()
        store.close()
        // The saga promise table is created by the product's own saga store,
        // the way a durable run creates it. A store without that table cannot
        // say what any saga owes, and the verb that reads it would answer a
        // refusal about a table the product had never been asked about.
        SqliteSagaObligationStore(connectionFactory, db.toString())
    }

    @Test
    fun `the version verb answers with the version this build carries`() {
        val answer = ask("version")

        assertEquals(0, answer.exit.code, "the version verb failed: ${answer.stderr}")
        assertEquals(
            "Success",
            answer.exit.name,
            "the code carries the name of the case that owns it",
        )
        assertTrue(
            answer.stdout.startsWith("pipeline "),
            "the version verb prints the product version; it printed: ${answer.stdout}",
        )
    }

    @Test
    fun `the capabilities verb answers with the keys this build admits`() {
        val answer = ask("capabilities")

        assertEquals(0, answer.exit.code, "the capabilities verb failed: ${answer.stderr}")
        assertFalse(
            answer.stdout.isBlank(),
            "the capabilities verb printed nothing at all",
        )
    }

    @Test
    fun `the doctor verb answers with the health of this installation`() {
        val answer = ask("doctor")

        assertEquals(0, answer.exit.code, "the doctor verb failed: ${answer.stderr}")
        assertTrue(
            answer.stdout.contains("jdk"),
            "the doctor reports the runtime it found; it printed: ${answer.stdout}",
        )
        assertTrue(
            answer.stdout.contains("os"),
            "the doctor reports the operating system it found; it printed: ${answer.stdout}",
        )
    }

    @Test
    fun `the sdk-info verb answers with the coordinates a plugin builds against`() {
        val answer = ask("sdk-info")

        assertEquals(0, answer.exit.code, "the sdk-info verb failed: ${answer.stderr}")
        assertFalse(
            answer.stdout.isBlank(),
            "the sdk-info verb printed nothing at all",
        )
    }

    @Test
    fun `the events verify verb answers about a real run against a real contract`() {
        val contract = writeContract("contract-met.yaml", "SUCCESS")

        val answer = ask("events", "verify", "--db", db.toString(), "--run", runId, "--contract", contract.toString())

        assertEquals(0, answer.exit.code, "the events verify verb failed: ${answer.stderr}")
        assertTrue(
            answer.stdout.contains("acceptance: PASSED"),
            "the run met the contract, so the verb says so; it printed: ${answer.stdout}",
        )
    }

    @Test
    fun `a contract the run does not meet is answered with the pipeline-failed code`() {
        val contract = writeContract("contract-unmet.yaml", "FAILURE")

        val answer = ask("events", "verify", "--db", db.toString(), "--run", runId, "--contract", contract.toString())

        assertEquals(
            1,
            answer.exit.code,
            "a contract the run does not meet is the pipeline-failed code; the verb printed: " +
                "${answer.stdout} / ${answer.stderr}",
        )
        assertEquals(
            "PipelineFailed",
            answer.exit.name,
            "the code carries the name of the case that owns it",
        )
        assertTrue(
            answer.stdout.contains("acceptance: FAILED"),
            "the verb says the contract was not met; it printed: ${answer.stdout}",
        )
    }

    @Test
    fun `the observe verb replays a real run from the real store`() {
        val answer = ask("observe", runId, "--db", db.toString())

        assertEquals(0, answer.exit.code, "the observe verb failed: ${answer.stderr}")
        // What the verb prints is the run's own material: the script the run
        // was given and the step it ran. An empty replay would print neither.
        assertTrue(
            answer.stdout.contains("pipeline.kts"),
            "the observe verb replays the run's own events; it printed: ${answer.stdout}",
        )
        assertTrue(
            answer.stdout.contains("build"),
            "the observe verb replays the run's own events; it printed: ${answer.stdout}",
        )
    }

    @Test
    fun `the saga inspect verb answers what a run still owes`() {
        val answer = ask("saga", "inspect", runId, "--db", db.toString())

        assertEquals(0, answer.exit.code, "the saga inspect verb failed: ${answer.stderr}")
        assertTrue(
            answer.stdout.contains("nothing owed"),
            "the run armed no promise, so the verb says that nothing is owed; it printed: ${answer.stdout}",
        )
    }

    @Test
    fun `a view this build cannot deliver is answered with the runtime-not-available code`() {
        val answer = ask("observe", runId, "--db", db.toString(), "--view", "full")

        assertEquals(
            4,
            answer.exit.code,
            "a view this build cannot deliver is the runtime-not-available code; the verb " +
                "printed: ${answer.stdout} / ${answer.stderr}",
        )
        assertEquals(
            "RuntimeNotAvailable",
            answer.exit.name,
            "the code carries the name of the case that owns it",
        )
    }

    @Test
    fun `a run the store does not have is answered with an empty success, and that is a gap`() {
        val answer = ask("observe", "run-that-was-never-written", "--db", db.toString())

        // What the product answers TODAY, pinned: the replay of a run the store
        // does not have is an empty replay, and an empty replay is a success.
        //
        // The taxonomy has a case for exactly this and NOTHING produces it: the
        // unknown-execution code (5) says "the run id is not in the durable
        // store", and no verb of this CLI answers with it. The `saga inspect`
        // verb, reading the SAME store with the SAME run id, refuses instead:
        // "no run X in ... Nothing was read, so nothing can be said about what
        // it owes". Two readers, one store, one run id, and two different
        // answers to the same question.
        //
        // The gap is declared here rather than covered: closing it means
        // changing what `observe` answers for a run that does not exist, and
        // that is a change of the CLI's contract, not a test.
        assertEquals(
            0,
            answer.exit.code,
            "the replay of a run the store does not have is an empty success; the verb " +
                "printed: ${answer.stdout} / ${answer.stderr}",
        )
        assertTrue(
            answer.stdout.isBlank(),
            "an empty replay prints nothing; it printed: ${answer.stdout}",
        )
    }

    @Test
    fun `a store that is not there is answered with the invalid-argument code`() {
        val answer = ask("observe", runId, "--db", root.resolve("absent.db").toString())

        assertEquals(
            2,
            answer.exit.code,
            "a store that is not there is answered with the invalid-argument code; the verb " +
                "printed: ${answer.stdout} / ${answer.stderr}",
        )
        assertEquals(
            "InvalidArgument",
            answer.exit.name,
            "the code carries the name of the case that owns it",
        )
    }

    @Test
    fun `the run verb attaches to the invocation directory by default`() {
        // The decision, not the run: the run itself is not a read-only verb,
        // and running it would write into the directory the process was
        // started in, which a test cannot own. What IS assertable, and what
        // nobody asserted, is the DEFAULT the parser hands to the run: no
        // isolated scratch workspace and no attached workspace, which means
        // the run attaches to the invocation directory.
        val parsed = CliExecutionParser.parse(arrayOf("run", "pipeline.kts"))

        val flags = requireNotNull(parsed as? CliParseResult.Parsed) {
            "the parser did not admit a plain run request: $parsed"
        }.flags

        assertFalse(
            flags.isolated,
            "the default mode of the run is the invocation directory, not a scratch workspace",
        )
        assertEquals(
            null,
            flags.workspace,
            "the default mode of the run names no workspace of its own: the invocation " +
                "directory is the one it uses",
        )
    }

    @Test
    fun `every form the README's quickstart names is a form this CLI dispatches`() {
        val named = quickstartForms()

        assertTrue(
            named.isNotEmpty(),
            "the README names no command form in its quickstart, so this rule proves nothing",
        )

        val dispatched = CLI_VERBS.map { it.label }.toSet()
        named.forEach { form ->
            assertTrue(
                form in dispatched,
                "the README tells the operator to run '$form', which this CLI does not dispatch; " +
                    "the forms it dispatches are ${dispatched.sorted().joinToString()}",
            )
        }
    }

    /**
     * The command forms the README's quickstart tells the operator to run.
     *
     * Only the invocations of the binary itself count: a line that starts with
     * the variable the quickstart binds the binary to is a command the reader
     * will type, and the scripts the README installs are not commands of this
     * CLI. A family form takes its second word too, because `runs` alone is a
     * refusal and `runs cancel` is the form.
     */
    private fun quickstartForms(): List<String> {
        val text = Files.readString(repositoryRoot().resolve("README.md"))

        return text.lineSequence()
            .map { it.trim() }
            .mapNotNull { line -> INVOCATION.find(line) }
            .map { match -> match.groupValues[1] to match.groupValues[2] }
            .map { (first, second) -> if (first == RUNS_FAMILY) "$first $second" else first }
            .distinct()
            .toList()
    }

    /**
     * A contract in the wire form the product's own codec decodes.
     *
     * The expectation is the only thing that changes between the two
     * contracts: the run ends in success, so the contract that expects
     * SUCCESS is met and the one that expects FAILURE is not.
     */
    private fun writeContract(name: String, expected: String): Path {
        val path = root.resolve(name)
        Files.writeString(
            path,
            """
            version: 1
            name: $name
            expect: { runOutcome: $expected }
            constraints:
              - exactly: { event: RunStarted, count: 1 }
              - never: { event: StepFailed }
            """.trimIndent() + "\n",
        )
        return path
    }

    /**
     * The answer of a verb, and what it printed.
     */
    private data class Answer(val exit: CliExit, val stdout: String, val stderr: String)

    /**
     * Runs the read-only dispatcher over [words] and reads its answer.
     *
     * The dispatcher is the product's own door, and it is the door the entry
     * point uses: the verbs are dispatched in process, so the answer is the
     * same answer the operator would get, and the process is not ended by a
     * test.
     */
    private fun ask(vararg words: String): Answer = askWords(words.asList())

    /**
     * The answer of a verb over the words it is asked with.
     */
    private fun askWords(words: List<String>): Answer {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())

        val outcome = handleReadOnlyVerb(words.toTypedArray(), out, err)
        out.flush()
        err.flush()

        val answered = outcome as? ReadOnlyVerbOutcome.Answered
            ?: error("the verb did not answer at all: $outcome")
        return Answer(answered.exit, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }

    /**
     * The root of the repository, found from the directory the lane runs in.
     *
     * The markers are the files that only the root has, so the walk stops at
     * the root and not at a directory that happens to hold a README.
     */
    private fun repositoryRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            val directory = candidate
            if (ROOT_MARKERS.all { Files.isRegularFile(directory.resolve(it)) }) {
                return directory
            }
            candidate = directory.parent
        }
        error("the repository root was not found from '${Paths.get("").toAbsolutePath()}'")
    }

    private companion object {
        /** The files that only the root of the repository holds. */
        val ROOT_MARKERS = listOf("AGENTS.md", "README.md", "scripts/verify-all.sh")

        /** The family whose forms take a second word to name them. */
        const val RUNS_FAMILY = "runs"

        /**
         * An invocation of the binary the quickstart binds it to.
         *
         * The first group is the word the operator types, and the second is
         * the one a family form carries. A line that starts with a flag names
         * no form at all, and the rule does not match it.
         */
        val INVOCATION = Regex("""^"\${'$'}PIPELINEK"\s+([a-z][a-z0-9-]*)(?:\s+([a-z][a-z0-9-]*))?""")
    }
}
