package dev.rubentxu.pipeline.v2.application.console

import dev.rubentxu.pipeline.v2.application.ConsoleFrameFilters
import dev.rubentxu.pipeline.v2.application.ConsoleReadRequest
import dev.rubentxu.pipeline.v2.application.CliParser
import dev.rubentxu.pipeline.v2.application.ConsoleCliParseResult
import dev.rubentxu.pipeline.v2.application.ConsoleCliRefusal
import dev.rubentxu.pipeline.v2.application.FollowConfig
import dev.rubentxu.pipeline.v2.application.FollowExit
import dev.rubentxu.pipeline.v2.application.MainConsoleCli
import dev.rubentxu.pipeline.v2.application.renderConsoleCliRefusal
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * R4 / WU-RC3 — audit block 2: `--follow` with a filter actually filters.
 *
 * ## The defect (P0-4)
 *
 * `CliParser.consoleValidate` dispatched `--follow` BEFORE the filter branch:
 *
 * ```kotlin
 * if (follow) return consoleAdmitFollowFrames(...)      // <- filters never reach the request
 * if (hasFilter) return consoleAdmitFramesFiltered(...)
 * ```
 *
 * So `console --frames --follow --stage build` admitted a filterless `FollowFrames`, the
 * `--stage` was dropped without a word, and the operator was shown the whole run with exit 0.
 * Nothing warned them: the output looked like a successful filtered follow.
 *
 * ## What these rows pin
 *
 * ```text
 * RC3-A  --follow WITH --stage is admitted as a FILTERED follow (parser)
 * RC3-B  --follow WITHOUT a filter is still admitted, unfiltered (parser)
 * RC3-C  a filtered follow emits only the selected operation's frames, and exits Completed
 * RC3-Bb an UNFILTERED follow over the same store is unaffected by this block
 * RC3-Cb a filter that matches nothing still terminates rather than polling to its budget
 * RC3-D  --db reaches the request, because names and kinds are journal-only
 * ```
 *
 * ## Harness fidelity
 *
 * **HF1 for the verb rows, HF0 for the parser rows.**
 *
 * The verb rows drive the real `MainConsoleCli.runFollowFrames` over a real `SegmentOutputStore`
 * through the production publish order, and select with the real
 * [ConsoleOperationSelection]. Nothing is fabricated except the stream ids, which use
 * `OpId.format()`'s exact production shape — a fixture id that did not match would either select
 * nothing and prove nothing, or select by accident.
 *
 * The parser rows are pure: `CliParser.parseConsole` performs no effect, so calling it IS the
 * faithful level (the same reason [ConsoleOperationSelectionTest] is HF0).
 *
 * **What this class does NOT claim.** It does not prove `--stage build` resolves against a real
 * journal in a real run. That needs an installed distribution whose Output Plane is populated,
 * and the fourth defect leaves it empty. Name resolution is proved at HF0 against production
 * event shapes; the end-to-end wiring is BLOCKED and recorded as such in the receipt.
 *
 * ## Mutations
 *
 * - `M-RC3-A` (the dispatch) → put `if (follow)` back ahead of the filter branch. Killed by RC3-A.
 * - `M-RC3-B` (the loop filters) → emit `page.records` unfiltered. Killed by RC3-C.
 * - `M-RC3-C` (the cursor) → advance on the last READ ordinal rather than the last ADMITTED one.
 *   Killed by RC3-Cb: a filter that drops every frame in the first window would re-read that
 *   window forever and never reach an admitted frame — a stall that looks like "no output yet".
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ConsoleRc3FilteredFollowTest {

    @TempDir
    lateinit var root: Path

    private val runId = "run-rc3"

    @BeforeEach
    fun forgetCachedStores() = OutputPlaneProvider.forgetAll()

    // ------------------------------------------------------------------ parser (HF0)

    private fun parse(vararg args: String) = CliParser.parseConsole(arrayOf(*args))

    private fun admitted(args: Array<String>): ConsoleReadRequest.Frames =
        assertInstanceOf(
            ConsoleReadRequest.Frames::class.java,
            assertInstanceOf(ConsoleCliParseResult.Admitted::class.java, parse(*args), "expected admission").request,
            "expected a plain Frames read",
        )

    private fun admittedFollow(args: Array<String>): ConsoleReadRequest.FollowFrames =
        assertInstanceOf(
            ConsoleReadRequest.FollowFrames::class.java,
            assertInstanceOf(ConsoleCliParseResult.Admitted::class.java, parse(*args), "expected admission").request,
            "expected a FollowFrames loop",
        )

    /**
     * A filtered read with NO opId positional.
     *
     * Both halves of that are new in this block. `parseConsole` takes no verb token (the verb is
     * chosen by the entrypoint, not the parser), and the opId positional is OPTIONAL once a
     * filter is present — required, it would scope the read to one operation before the filter
     * ran, and `--stage build` could then never reach an operation the positional had not already
     * named. A filter that cannot select among operations is not a filter.
     */
    private val base = arrayOf("--control-dir", "/tmp/ctrl", "run-1", "--frames")

    /** The unfiltered shape: the opId positional is mandatory, so it is present. */
    private val scopedBase = arrayOf("--control-dir", "/tmp/ctrl", "run-1", "run-1-s0-0", "--frames")

    /**
     * RC3-A — the row that fails on rc1.
     *
     * On rc1 this request was admitted as a `FollowFrames` whose filters were the default, i.e.
     * no filter at all, and nothing anywhere reported the loss.
     */
    @Test
    fun `RC3-A a follow carrying a stage filter is admitted WITH that filter`() {
        val follow = admittedFollow(base + arrayOf("--follow", "--stage", "build"))

        assertEquals(
            ConsoleFrameFilters.Active(stages = setOf("build")),
            follow.filters,
            "the filter must REACH the request. rc1 dispatched `--follow` first and dropped it, " +
                "so the operator saw the whole run under a filter they had asked for.",
        )
    }

    @Test
    fun `RC3-Ab every axis survives the follow admission`() {
        val follow = admittedFollow(
            base + arrayOf("--follow", "--stage", "build", "--step", "compile", "--kind", "sh", "--db", "/tmp/j.db"),
        )

        assertEquals(
            ConsoleFrameFilters.Active(
                stages = setOf("build"), steps = setOf("compile"), kinds = setOf("sh"),
            ),
            follow.filters,
            "all three axes must survive; they AND together and a dropped one would widen the " +
                "answer without saying so",
        )
    }

    /**
     * RC3-B — the fix must not have broken the unfiltered follow.
     *
     * A block that fixes a filter bug by refusing filters is not a fix. This row exists so the
     * `filters = None` default is proven rather than assumed.
     */
    @Test
    fun `RC3-B a follow with no filter is admitted unfiltered`() {
        // The opId positional is still REQUIRED without a filter — this block made it optional
        // only where a filter exists to replace it. Asserting that here keeps the relaxation
        // scoped: making the positional optional everywhere would turn a required argument into
        // a silently run-wide read.
        assertEquals(
            ConsoleCliRefusal.MissingArgument,
            assertInstanceOf(
                ConsoleCliParseResult.Rejected::class.java,
                parse(*(base + arrayOf("--follow"))),
                "without a filter there is nothing to replace the positional, so it stays required",
            ).refusal,
        )

        assertEquals(
            ConsoleFrameFilters.None,
            admittedFollow(scopedBase + arrayOf("--follow")).filters,
            "no filter asked for is `None`, not `Active` with empty axes — the closed ADT refuses " +
                "the latter for a reason, and 'no constraint' is not 'a filter is active'",
        )
    }

    /** RC3-D — `--db` reaches the request; without it names and kinds cannot be resolved at all. */
    @Test
    fun `RC3-D the events journal path reaches the request`() {
        assertEquals(
            "/tmp/j.db",
            admittedFollow(base + arrayOf("--follow", "--kind", "sh", "--db", "/tmp/j.db")).dbPath,
            "the journal is the ONLY authority that knows a stage NAME or a step's kind, so " +
                "`--db` has to survive admission or those axes can only ever be refused",
        )
    }

    @Test
    fun `RC3-Db without --db a kind is admitted but CANNOT be honoured, so it is refused at interpretation`() {
        // Admission succeeds — the flags are well formed. The refusal happens where the journal
        // would have been opened, and the two are different stages on purpose: a parser that
        // opened SQLite would be performing an effect at admission.
        val follow = admittedFollow(base + arrayOf("--follow", "--kind", "sh"))
        assertEquals(null, follow.dbPath, "no journal was supplied")

        val outcome = ConsoleOperationSelection.resolve(
            runId, emptySet(), emptySet(), setOf("sh"), null,
        )
        assertEquals(
            SelectionOutcome.Refused(SelectionRefusal.NoEventsAuthority(listOf("--kind"))),
            outcome,
            "and so the resolution refuses rather than admitting a filter it cannot apply. rc1 " +
                "admitted it and returned every frame, which is the semantic-drop defect.",
        )
    }

    // ------------------------------------------------------------------ verb (HF1)

    /** Publish `lines` frames for `opId`, in the production order. */
    private fun publish(store: SegmentOutputStore, opId: String, lines: Int, seal: Boolean) {
        val stream = OutputStreamId("$runId/$opId/stdout")
        store.frameIndex().declareStream(stream, OutputChannel.STDOUT)
        for (i in 0 until lines) {
            val payload = "$opId-line-$i\n".toByteArray(Charsets.UTF_8)
            val from = store.committedExtent(stream) ?: 0L
            val reservation = store.open(stream).reserve(payload.size)
            reservation.write(payload)
            reservation.commit()
            store.frameIndex().append(stream, OutputChannel.STDOUT, from, from + payload.size)
        }
        if (seal) store.seal(stream)
    }

    private fun controlDir(name: String, build: (SegmentOutputStore) -> Unit): Path {
        val dir = Files.createDirectories(root.resolve(name))
        val store = SegmentOutputStore(dir.resolve("output-plane"))
        store.recover()
        build(store)
        return dir
    }

    /** Drive the REAL loop with an explicit selection, the way `main` does. */
    private fun follow(
        dir: Path,
        selection: OperationSelection,
        maxPolls: Int = 20,
    ): Triple<FollowExit, String, String> {
        // `opId = null` reads the WHOLE RUN; the selection is then the only thing narrowing it.
        // That is what makes `--stage` able to select among operations rather than within one.
        val request = ConsoleReadRequest.FollowFrames(
            controlDir = dir.toString(),
            runId = runId,
            opId = null,
            afterOrdinal = -1L,
            frameLimit = 64,
            followConfig = FollowConfig(1L, maxPolls),
            format = MainConsoleCli.ConsoleFormat.JSONL,
        )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = PrintStream(out, true, "UTF-8")
        val errStream = PrintStream(err, true, "UTF-8")
        val outcome = MainConsoleCli.runFollowFrames(request, dir, exit, errStream, selection)
        exit.flush(); errStream.flush()
        return Triple(outcome, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun opId(stage: Int, step: Int) =
        dev.rubentxu.pipeline.v2.application.durable.OpId(runId, stage, step).format()

    /**
     * RC3-C — the row the whole block exists for.
     *
     * Two operations, both sealed. Following one of them emits ONLY that operation's frames.
     * Under rc1 the filter never reached the loop, so this emitted all six and exited 0.
     */
    @Test
    fun `RC3-C a filtered follow emits only the selected operation's frames`() {
        val dir = controlDir("filtered") { store ->
            publish(store, opId(0, 0), lines = 3, seal = false)
            publish(store, opId(1, 0), lines = 3, seal = false)
            // Seal BOTH, so the loop reaches a genuine terminal state rather than its budget.
            store.seal(OutputStreamId("$runId/${opId(0, 0)}/stdout"))
            store.seal(OutputStreamId("$runId/${opId(1, 0)}/stdout"))
        }

        val selection = OperationSelection.IndexOnly(requiredStage = 0, requiredStep = 0)
        val (outcome, stdoutText, _) = follow(dir, selection)

        assertEquals(3, stdoutText.lines().count { it.isNotEmpty() }, "three frames, all from stage 0. got:\n$stdoutText")
        assertTrue(
            stdoutText.lines().filter { it.isNotEmpty() }.all { it.contains(opId(0, 0)) },
            "every emitted record must belong to the SELECTED operation; got:\n$stdoutText",
        )
        assertTrue(
            !stdoutText.contains(opId(1, 0)),
            "the other operation's frames leaked into a filtered follow — that is rc1's behaviour " +
                "under a different name",
        )
        assertEquals(
            FollowExit.Completed, outcome,
            "every stream is sealed, so the follow completes rather than exhausting its budget",
        )
    }

    /** RC3-Bb — the unfiltered follow over the same store is unchanged by this block. */
    @Test
    fun `RC3-Bb an unfiltered follow over the same store is unaffected`() {
        val dir = controlDir("unfiltered") { store ->
            publish(store, opId(0, 0), lines = 3, seal = true)
            publish(store, opId(1, 0), lines = 3, seal = true)
        }

        val (outcome, stdoutText, _) = follow(dir, OperationSelection.AllOperations)

        assertEquals(
            6, stdoutText.lines().count { it.isNotEmpty() },
            "no filter and no opId scope means the WHOLE run, which is six frames across two " +
                "operations. Asserting three here would be asserting that an unfiltered read is " +
                "still narrowed — which is the rc1 behaviour this block removes. got:\n$stdoutText",
        )
        assertTrue(
            stdoutText.contains(opId(0, 0)) && stdoutText.contains(opId(1, 0)),
            "both operations must appear; got:\n$stdoutText",
        )
        assertEquals(FollowExit.Completed, outcome)
    }

    /**
     * RC3-Cb — the cursor must advance on the last ADMITTED frame.
     *
     * The store holds three operations; the selection admits only the LAST one. If the cursor
     * advanced on the last READ ordinal it would stop at operation 1 and never see operation 2 —
     * and because every read still returns frames, the loop would poll until its budget and
     * report exhaustion, which reads as "the run produced nothing" rather than "the filter
     * matched late".
     */
    @Test
    fun `RC3-Cb a filter matching only a later operation still reaches it`() {
        val dir = controlDir("late-match") { store ->
            publish(store, opId(0, 0), lines = 1, seal = false)
            publish(store, opId(0, 1), lines = 1, seal = false)
            publish(store, opId(0, 2), lines = 1, seal = true)
            store.seal(OutputStreamId("$runId/${opId(0, 0)}/stdout"))
            store.seal(OutputStreamId("$runId/${opId(0, 1)}/stdout"))
        }

        val (outcome, stdoutText, _) = follow(dir, OperationSelection.IndexOnly(0, 2))

        assertEquals(
            1, stdoutText.lines().count { it.isNotEmpty() },
            "exactly the one frame the filter admits. got:\n$stdoutText",
        )
        assertTrue(
            stdoutText.contains(opId(0, 2)),
            "the loop must walk PAST two non-matching operations to reach the matching one; " +
                "advancing on the last read ordinal instead would strand it. got:\n$stdoutText",
        )
        assertEquals(FollowExit.Completed, outcome, "every stream is sealed")
    }

    /** The refusal renders a stable, greppable line rather than an exception. */
    @Test
    fun `RC3-E an unresolvable filter renders a refusal naming the axis`() {
        val rendered = renderConsoleCliRefusal(
            ConsoleCliRefusal.FilterNotResolvable(SelectionRefusal.NoEventsAuthority(listOf("--kind"))),
        )

        assertTrue(rendered.startsWith("Error: "), "argument refusals keep the `Error:` prefix; got: $rendered")
        assertTrue(rendered.contains("--db"), "the refusal must tell the caller how to fix it; got: $rendered")
        assertTrue(rendered.contains("--kind"), "and name the axis it could not honour; got: $rendered")
    }
}
