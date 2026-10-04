package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.application.ConsoleReadService
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.SqliteConnectionFactory
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import java.nio.file.Files
import java.nio.file.Path

/**
 * S4/M1 integration — reads a Step's process transcript from the OUTPUT PLANE.
 *
 * ## Why this exists
 *
 * `EchoOutputCaptured` used to carry process output, and a large number of tests read it from
 * there. M1 moved those bytes into the Output Plane and left events carrying semantic facts
 * only, so those tests went RED with an EMPTY observed string — not because the process printed
 * nothing, but because they were looking at a channel that no longer carries output.
 *
 * Re-pointing them here is the whole point. An assertion like "stage a must see its own
 * environment value" did not become less true; the reader changed to the authority that owns
 * the bytes. [ConsoleReadService] is that authority, and it is deliberately NOT built on
 * `EventHistoryReader`: the event plane continues over a sequence, the output plane over a
 * committed byte offset, and conflating them is what `ADR-M1 D3` forbids.
 *
 * ## The rule that makes this helper safe
 *
 * A read that **cannot be answered** throws. It never returns `""`.
 *
 * That distinction is the whole ballgame. An empty page is a legitimate answer — the process
 * really printed nothing — and a refusal means the reader asked about a stream that does not
 * exist. A helper that collapses both into `""` reproduces, one level up, the exact defect this
 * migration exists to fix: a test that cannot tell "the product did nothing" from "I am reading
 * the wrong place" passes when it should fail. So the failure mode here is a thrown error with
 * the refusal reason, not a quiet empty string.
 *
 * Measured against the installed distribution, the plane holds a stream **if and only if** the
 * process wrote transcript bytes — a silent step opens none. That makes refusal a THREE-way
 * answer (the bytes exist / the step wrote nothing / the reader is mis-aimed), which is why the
 * entry points below differ: [transcript] throws on any refusal, and [transcriptOrAbsent]
 * answers `null` only for a step that legitimately wrote nothing, for the caller that has the
 * event that tells the two apart. Neither accessor ever returns `""` to mean "I could not tell".
 *
 * ## Why operation identity is ASKED FOR and never rebuilt
 *
 * A step's stream is keyed by its `OpId`, and `OpId` carries a `bodyPath`: a `sh` inside
 * `withCredentials` is not `run-s0-0` but `run-s0-0-bp1-0:core.sh`. Measured on the installed
 * distribution, the event plane does NOT carry that identity — `StepStarted` publishes
 * `stageIndex`/`stepIndex`/`stepName`/`stepType` and nothing that names the operation — while the
 * **journal already records every operation's `op_id` verbatim**, including the body path, under a
 * primary key and an index on `run_id`.
 *
 * That is why [transcriptsOfRun] reads the identity instead of composing it. Rebuilding an
 * `OpId` from a `StepStarted` means a second, private copy of the engine's body-path convention:
 * it would have to know block nesting, branch indices and the plugin step id, and it would be
 * wrong the first time any of those changed. The journal is not a competing authority here — it
 * IS the authority for which operations a run performed, and its `op_id` is the same string the
 * Output Plane is keyed by. So this helper asks it, and uses the answer literally.
 *
 * The same reasoning rules out enumerating the plane's own directory: the on-disk name is
 * `safe(runId)_safe(opId)_transcript`, and `safe()` folds `/` onto `_` irreversibly, so the
 * directory name cannot be turned back into the `OutputStreamId` the reader needs. `hasOutputFor`
 * answers a boolean for exactly that reason and `streamsOf` was removed in B2b. Asking the
 * journal is both possible and honest.
 */
object ConsolePlaneProbe {

    /**
     * The whole committed transcript of one operation, read as a consumer would.
     *
     * Pages until the plane stops advancing, so the result is independent of
     * [ConsoleReadService.DEFAULT_PAGE_BYTES]. A test that reads only the first page and asserts
     * on it would be asserting on an arbitrary cut point.
     *
     * @throws AssertionError if the plane refuses the read. Measured contract: the plane holds a
     *   stream only when the process wrote transcript bytes, so a refusal here means the caller
     *   expected output and got none. For the legitimate "wrote nothing" case see
     *   [transcriptOrAbsent], which is the only accessor that may answer `null`.
     */
    fun transcript(
        controlDirRoot: Path,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        branchIndex: Int? = null,
    ): String {
        val opId = OpId(runId, stageIndex, stepIndex, branchIndex).format()
        return when (val read = readOperation(controlDirRoot, runId, opId, stageIndex, stepIndex)) {
            is OperationRead.Bytes -> read.transcript
            // Unreachable by construction: `readOperation` throws on every refusal in strict mode
            // and only `Absent` names the no-stream case. Written out rather than elided, so that
            // adding a case to [OperationRead] later turns this into a compile error instead of a
            // `!!` that invents a transcript.
            is OperationRead.Absent -> throw AssertionError(
                "unreachable: strict read of op '$opId' reported no stream instead of refusing",
            )
        }
    }

    /**
     * The transcript of the single `sh` step a one-step test pipeline contains.
     *
     * Named for what it is rather than made to look general: every current caller runs a
     * one-stage, one-step pipeline, and a helper that pretended to handle arbitrary shapes would
     * be asserting positions it never verified.
     */
    fun singleStepTranscript(controlDirRoot: Path, runId: String): String =
        transcript(controlDirRoot, runId, stageIndex = 0, stepIndex = 0)

    /** The run the log is about, as the engine recorded it. Never guessed from the file name. */
    fun runIdOf(events: List<DomainEvent>): String =
        (events.firstOrNull { it is RunStarted } as? RunStarted)?.runId
            ?: throw AssertionError(
                "the run log carries no RunStarted event, so there is no run identity to read " +
                    "transcripts for. A probe that invented one would point at a stream that " +
                    "does not exist and report the process as silent.",
            )

    /**
     * Like [transcript], but `null` when the step opened no stream at all.
     *
     * ## The three answers, not two
     *
     * Measured on the installed distribution (B2), the Output Plane holds a stream **if and only
     * if** the process wrote transcript bytes:
     *
     * | what the child did | what the plane holds | what [transcript] does |
     * |---|---|---|
     * | wrote output | the bytes | returns them |
     * | wrote nothing (including `sh("true")` and a capture-mode `sh` with silent stderr) | **no stream** | throws |
     * | reader aimed at the wrong run / wrong op | no stream | throws |
     *
     * Rows two and three are the same observation and mean different things, so this accessor
     * exists for the caller that can tell them apart and [transcript] refuses to guess for the
     * one that cannot. A stream is opened by `appendFrom`; an empty transcript is never
     * materialised, because `consoleSource` resolves to `null` when there is nothing to stream
     * and `ingestTranscriptIntoOutputPlane` returns rather than inventing a stream.
     *
     * **Use this only where "the step produced no console output" is the claim.** That claim is
     * load-bearing on its own — it is what proves the typed value was not smuggled into the
     * transcript — but the negative must never stand alone: a run with no output at all produces
     * the same `null`. Pair it with the event that shows the step really ran.
     */
    fun transcriptOrAbsent(
        controlDirRoot: Path,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        branchIndex: Int? = null,
    ): String? {
        val opId = OpId(runId, stageIndex, stepIndex, branchIndex).format()
        return when (val read = readOperation(controlDirRoot, runId, opId, stageIndex, stepIndex)) {
            is OperationRead.Bytes -> read.transcript
            is OperationRead.Absent -> null
        }
    }

    /**
     * Every process transcript this run produced for steps of [stepType], joined in event order.
     *
     * ## Which streams exist is a question of fact, not of guesswork
     *
     * Which `(stageIndex, stepIndex)` pairs ran is recorded by the engine in [StepStarted], so
     * this resolves stream identity from the log instead of asking each test to recount its own
     * pipeline by hand. That is a lookup, not a second authority: the BYTES still come from the
     * Output Plane and the cursor still comes from the plane's own page. It is the same
     * resolution [dev.rubentxu.pipeline.v2.application.MainConsoleCli] performs for a human
     * consumer — a name is resolved, never invented.
     *
     * Filtering by [stepType] matters for the same reason. A `core.echo` step has no process and
     * therefore no transcript, and asking the plane for one is a refusal, not an empty page.
     * Treating that refusal as silence would reintroduce, one level up, the exact conflation this
     * helper exists to undo.
     *
     * **This entry point only addresses top-level steps.** A step nested in a block body is keyed
     * by an `OpId` carrying a body path, which [StepStarted] does not publish; use
     * [transcriptsOfRun] for a run that contains one. Asking [transcript] about a nested step
     * throws rather than returning `""`, which is the correct failure: the answer would otherwise
     * have been indistinguishable from a process that printed nothing.
     *
     * A step that never started has no [StepStarted] and contributes nothing. When a test asserts
     * that a body did NOT run, the proof is that event plus the absence of a transcript — not the
     * absence of a transcript alone, which a mis-aimed reader would also produce.
     */
    fun transcriptsOfSteps(
        controlDirRoot: Path,
        events: List<DomainEvent>,
        stepType: String,
    ): String {
        val runId = runIdOf(events)
        val sink = StringBuilder()
        events.filterIsInstance<StepStarted>()
            .filter { it.stepType == stepType }
            .forEach { started ->
                sink.append(
                    transcript(
                        controlDirRoot = controlDirRoot,
                        runId = runId,
                        stageIndex = started.stageIndex,
                        stepIndex = started.stepIndex,
                    ),
                )
            }
        return sink.toString()
    }

    /**
     * Like [transcriptsOfSteps], but a step that legitimately wrote nothing contributes nothing.
     *
     * ## When this is the honest accessor, and when it is not
     *
     * [transcriptsOfSteps] is right when the caller knows the step printed something — asking the
     * plane about a silent step is a refusal, and treating that refusal as silence is the conflation
     * this helper exists to undo. But a real pipeline routinely runs `sh` steps whose entire
     * purpose is a side effect: `rm -rf`, `mkdir -p … && echo x > f`. Measured on the installed
     * distribution, those open no stream, so the strict accessor throws on a run that worked
     * perfectly, and it blames the reader for a step that behaved exactly as written.
     *
     * So the choice of accessor is a claim about the run, not a style preference:
     *
     * | the claim being made | accessor |
     * |---|---|
     * | "this step printed something and here it is" | [transcript] — throws if there is none |
     * | "somewhere in this run the process printed X" | this one |
     *
     * ## Why it does not open a pass-by-zero
     *
     * A step that wrote nothing and a reader aimed at the wrong stream are the same observation,
     * and both would make a silent step contribute `null`. That is only safe because the CALLER
     * still holds the claim: a run whose plane is empty, or whose reader is mis-aimed, yields an
     * empty aggregate here, so an assertion like `contains("the-marker")` still fails. The
     * permission to shrug off a null is not permission to shrug off the whole run — which is why
     * the strict accessor is left untouched for callers that assert per step.
     *
     * Top-level steps only, for the reason given on [transcriptsOfSteps]; [transcriptsOfRun]
     * covers nested ones.
     */
    fun transcriptsOfWrittenSteps(
        controlDirRoot: Path,
        events: List<DomainEvent>,
        stepType: String,
    ): String {
        val runId = runIdOf(events)
        val sink = StringBuilder()
        events.filterIsInstance<StepStarted>()
            .filter { it.stepType == stepType }
            .forEach { started ->
                transcriptOrAbsent(
                    controlDirRoot = controlDirRoot,
                    runId = runId,
                    stageIndex = started.stageIndex,
                    stepIndex = started.stepIndex,
                )?.let(sink::append)
            }
        return sink.toString()
    }

    /**
     * EVERY process transcript this run produced, in the order the journal recorded the operations.
     *
     * ## The entry point for runs containing nested bodies
     *
     * A `sh` inside `withCredentials` is keyed by an `OpId` carrying a body path — measured as
     * `run-s0-0-bp1-0:core.sh` — and no event publishes that string. So the operation list is read
     * from [journalDb], where `operation_journal.op_id` already holds every operation of the run
     * verbatim, and each returned id is used EXACTLY as the journal wrote it. Nothing here knows
     * how a body path is built; that knowledge stays in the engine that produced it.
     *
     * Filtering by which operations have a stream is not a lossy approximation: only process
     * steps ever open one (`core.echo` does not, a block body does not), so the set of streams is
     * exactly the set of operations that ran a child process. That is why this needs no `stepType`
     * and no pairing against [StepStarted] at all.
     *
     * @param journalDb the run's `--db` file. Required, not optional: without it there is no
     *   honest way to learn a nested operation's identity, and guessing one is the failure this
     *   helper exists to prevent. Runs without a journal are read through
     *   [transcriptsOfWrittenSteps], which addresses top-level steps only.
     * @throws AssertionError if the journal names no operation for this run, which would mean the
     *   reader is pointed at the wrong run rather than that the run was silent.
     */
    fun transcriptsOfRun(
        controlDirRoot: Path,
        journalDb: Path,
        events: List<DomainEvent>,
    ): String {
        val runId = runIdOf(events)
        val opIds = operationIdsIn(journalDb, runId)
        val sink = StringBuilder()
        opIds.forEach { opId ->
            when (val read = readOperation(controlDirRoot, runId, opId, stageIndex = -1, stepIndex = -1)) {
                is OperationRead.Bytes -> sink.append(read.transcript)
                is OperationRead.Absent -> Unit
            }
        }
        return sink.toString()
    }

    /**
     * The operation identities the journal recorded for [runId], verbatim and in recording order.
     *
     * Read-only, and closed per call: the journal belongs to the run that just finished, and a
     * handle held open across runs would be a connection outliving the thing it describes.
     *
     * Connections come from [SqliteConnectionFactory] rather than a bare `DriverManager` call, and
     * that is not a style preference. The journal runs in WAL mode, so a connection opened without
     * WAL reads a different view of the file than the writer committed: a plain `getConnection`
     * here reported "no operation for this run" against a journal that demonstrably held the row.
     * The factory is the authority for how this database is opened, so the reader asks it rather
     * than assembling its own connection and inheriting a different snapshot.
     */
    private fun operationIdsIn(journalDb: Path, runId: String): List<String> {
        if (!Files.isRegularFile(journalDb)) {
            throw AssertionError(
                "no operation journal at $journalDb, so this run's operation identities cannot " +
                    "be read. A probe that reconstructed them instead would be guessing the " +
                    "engine's body-path convention, and a guess that happens to be wrong reads as " +
                    "'the process printed nothing'.",
            )
        }
        val ids = SqliteConnectionFactory.open(journalDb.toString()).use { connection ->
            connection.prepareStatement(
                "SELECT op_id FROM operation_journal WHERE run_id = ? ORDER BY created_at, op_id",
            ).use { statement ->
                statement.setString(1, runId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            // Say what the journal DOES hold. "No rows for this run" has two very different
            // causes — a journal that never recorded this run, or a reader aimed at the wrong
            // database — and the two are indistinguishable unless the actual contents are named.
            val present = SqliteConnectionFactory.open(journalDb.toString()).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT op_id, run_id FROM operation_journal ORDER BY created_at, op_id",
                    ).use { rows ->
                        buildList {
                            while (rows.next()) add("${rows.getString(1)} (run_id=${rows.getString(2)})")
                        }
                    }
                }
            }
            throw AssertionError(
                "the journal at $journalDb records no operation for run '$runId'. The journal " +
                    "holds ${present.size} operation(s): ${present.ifEmpty { "<none: the run never " +
                    "recorded one>" }}. Either the reader is pointed at the wrong database, or " +
                    "this run recorded nothing at all — and an empty transcript must never be " +
                    "able to mean both.",
            )
        }
        return ids
    }

    /**
     * What one operation's transcript turned out to be: the bytes, or the absence of a stream.
     *
     * A sealed result rather than a `String?` because the two answers are not the same kind of
     * thing, and each accessor above states which one it will accept. Collapsing them into a
     * nullable would put the decision back on the caller as a `!!`, which is how a test ends up
     * asserting on a transcript that was never read.
     */
    private sealed interface OperationRead {
        data class Bytes(val transcript: String) : OperationRead
        data object Absent : OperationRead
    }

    /**
     * Read one operation's transcript by its literal operation id.
     *
     * Every refusal other than [OutputRefusal.UnknownStream] throws here, in BOTH accessors: it is
     * a fact about the QUESTION (a bad cursor, a store that is not recovered), not about the
     * answer, and turning it into "no transcript" would let a broken read masquerade as a silent
     * process. The difference between the accessors is only what they do with [OperationRead.Absent]:
     * a caller that expects output turns it into a failure, and a caller surveying a whole run
     * skips it.
     *
     * [stageIndex]/[stepIndex] are only diagnostics for a refusal; an id resolved from the journal
     * has no event position to report, and passes -1.
     */
    private fun readOperation(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        stageIndex: Int,
        stepIndex: Int,
    ): OperationRead {
        val position = if (stageIndex >= 0) " at stage=$stageIndex step=$stepIndex" else ""
        val result = ConsoleReadService.read(controlDirRoot, runId, opId, null)
        return when (result) {
            is ConsoleReadService.Result.Page -> OperationRead.Bytes(
                drainAll(controlDirRoot, runId, opId, result.page),
            )
            is ConsoleReadService.Result.Refused -> when (val reason = result.reason) {
                is OutputRefusal.UnknownStream -> OperationRead.Absent
                else -> throw AssertionError(
                    "the Output Plane refused to answer for run '$runId' op '$opId'$position: " +
                        ConsoleReadService.renderRefusal(reason) +
                        ". Only an unknown stream is a legitimate 'nothing was written'; anything " +
                        "else is a fact about the question that the caller asked, and answering " +
                        "it as silence would let a broken read pass as a quiet process.",
                )
            }
        }
    }

    /** Paged continuation, shared by every entry point so none can drift into a single page. */
    private fun drainAll(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        firstPage: OutputPage,
    ): String {
        val sink = StringBuilder(String(firstPage.bytes))
        var cursor: OutputCursor? = firstPage.next
        while (cursor != null) {
            val result = ConsoleReadService.read(controlDirRoot, runId, opId, cursor)
            val page: OutputPage = when (result) {
                is ConsoleReadService.Result.Page -> result.page
                is ConsoleReadService.Result.Refused -> throw AssertionError(
                    "continuation of run '$runId' op '$opId' was refused: " +
                        ConsoleReadService.renderRefusal(result.reason),
                )
            }
            sink.append(String(page.bytes))
            cursor = page.next
        }
        return sink.toString()
    }
}
