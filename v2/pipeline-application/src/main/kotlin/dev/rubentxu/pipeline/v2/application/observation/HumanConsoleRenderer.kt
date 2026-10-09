package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.CredentialUsed
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.DirectiveAdmitted
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.ExecutionTargetResolved
import dev.rubentxu.pipeline.v2.events.FileExistsChecked
import dev.rubentxu.pipeline.v2.events.FileRead
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.GateEvaluated
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import dev.rubentxu.pipeline.v2.events.GitPollChanged
import dev.rubentxu.pipeline.v2.events.HtmlReportFailed
import dev.rubentxu.pipeline.v2.events.HtmlReportPublished
import dev.rubentxu.pipeline.v2.events.HtmlReportSkipped
import dev.rubentxu.pipeline.v2.events.HttpRequestFailed
import dev.rubentxu.pipeline.v2.events.HttpRequestStarted
import dev.rubentxu.pipeline.v2.events.HttpResponseReceived
import dev.rubentxu.pipeline.v2.events.HttpStatusRejected
import dev.rubentxu.pipeline.v2.events.InputAborted
import dev.rubentxu.pipeline.v2.events.InputDenied
import dev.rubentxu.pipeline.v2.events.InputProceed
import dev.rubentxu.pipeline.v2.events.InputRequested
import dev.rubentxu.pipeline.v2.events.LockAcquireFailed
import dev.rubentxu.pipeline.v2.events.LockAcquired
import dev.rubentxu.pipeline.v2.events.LockReleased
import dev.rubentxu.pipeline.v2.events.LockRequested
import dev.rubentxu.pipeline.v2.events.LockSkipped
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted
import dev.rubentxu.pipeline.v2.events.PostConditionSelected
import dev.rubentxu.pipeline.v2.events.PwdResolved
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.StashFailed
import dev.rubentxu.pipeline.v2.events.StashRestored
import dev.rubentxu.pipeline.v2.events.StepAdmissionObserved
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.events.TimeoutTriggered
import dev.rubentxu.pipeline.v2.events.TimestampsEntered
import dev.rubentxu.pipeline.v2.events.TimestampsExited
import dev.rubentxu.pipeline.v2.events.UnixDetected
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.WorkflowLoaded
import dev.rubentxu.pipeline.v2.events.WsCleaned

/**
 * Pure projection from domain events to human-readable console text.
 *
 * ## Laws
 *
 * 1. **Presentation only.** No clock, no filesystem, no process, no ambient
 *    state. The same input list always yields the same string, which is what
 *    makes this testable without a coordinator, a store or a running pipeline.
 * 2. **Closed dispatch, and it never constructs.** [line] matches the closed
 *    [DomainEvent] hierarchy exhaustively and does nothing but name, for each
 *    event, the family that renders it. A new event is a compile error here, so
 *    somebody must decide how it looks instead of it silently vanishing from the
 *    console. The construction lives in [ConsoleFamilyLines], which is what keeps
 *    [line] readable in one screen.
 * 3. **A family renders only its own events.** Each in [ConsoleFamilyLines] ends
 *    in an `else` that throws rather than returning a placeholder, because the
 *    only way to reach one is a disagreement between the routing table and a
 *    handler — and a console that renders a plausible wrong line for an unrouted
 *    event is worse than a console that stops. `RendererRoutePartitionTest`
 *    proves the disagreement cannot survive.
 * 4. **Views select families; they do not delete records.** A family absent
 *    from a view is a declared projection — the event is still in the store and
 *    still visible under [ObservationView.EVENTS].
 * 5. **Closed structural match, never a key switch.** The `when` matches event
 *    TYPE. There is no `if (kind == "...")` anywhere.
 */
object HumanConsoleRenderer {

    /** Observable families; each view decides which of them a reader sees. */
    enum class Family {
        /** Run and compilation lifecycle. */
        RUN,

        /** Stage and Step structure — openers and closers. */
        STRUCTURE,

        /** Deliberate script output (`echo`). */
        MESSAGE,

        /** Something failed. Always visible: a failure a view can hide is a lie. */
        FAILURE,

        /** Observational detail — retries, credentials, HTTP, files, locks. */
        NOTE,
    }

    /**
     * One rendered line plus what the view filter needs.
     *
     * [verbatim] marks deliberate script output: Jenkins prints an `echo` as the
     * message itself with no engine prefix, and prefixing it would corrupt the
     * user's own output.
     */
    internal data class Line(
        val family: Family,
        val text: String,
        val verbatim: Boolean = false,
    )

    /** Renders [events] under [view]. Pure; returns a single string. */
    fun render(events: List<DomainEvent>, view: ObservationView): String =
        fold(events)
            .filter { visible(it.family, view) }
            .joinToString(separator = "\n") { format(it, view) }
            .let { if (it.isEmpty()) "" else "$it\n" }

    /**
     * Incremental renderer for a live run: one event at a time, carrying the
     * stage scope forward so a Step line still names its stage.
     *
     * [render] folds a whole list, which is right for a completed run and wrong
     * for a live one: waiting for the end is exactly the behaviour this exists
     * to remove. Both share the same [line] and [format], so a live stream and
     * a post-hoc render of the same events agree — a live line is never a
     * different presentation than the printed one.
     */
    fun stream(view: ObservationView): ConsoleStream = ConsoleStream(view)

    /** One live console. Not thread-safe; owned by the single appending thread. */
    class ConsoleStream internal constructor(private val view: ObservationView) {
        private var scope = Scope()

        /** Renders one event, or returns `null` when the view excludes it. */
        fun accept(event: DomainEvent): String? {
            scope = scope.after(event)
            val line = line(event, scope)
            if (!visible(line.family, view)) return null
            return format(line, view)
        }
    }

    internal fun format(line: Line, view: ObservationView): String = when {
        line.verbatim -> line.text
        view == ObservationView.EVENTS -> "[${line.text}]"
        else -> "[PipelineK] ${line.text}"
    }

    internal fun visible(family: Family, view: ObservationView): Boolean = when (view) {
        ObservationView.QUIET -> false
        ObservationView.NORMAL -> family != Family.NOTE
        ObservationView.EVENTS -> true
        // Refused by `resolveView`. Kept total so no future caller can obtain
        // an unfiltered dump by accident.
        ObservationView.CONSOLE, ObservationView.FULL -> true
    }

    /**
     * Stage names seen so far, keyed by index.
     *
     * Step events carry `stageIndex` but not the stage's name, and a console
     * line is far more readable when it names the stage. The association is
     * read from the events themselves — the renderer never invents it.
     */
    internal data class Scope(val stageNames: Map<Int, String> = emptyMap()) {
        fun stage(index: Int): String = stageNames[index] ?: "stage $index"

        /**
         * The scope AFTER [event], which is the only question either walk has to ask.
         *
         * [fold] and [ConsoleStream.accept] each used to carry their own copy of "if this is a
         * StageStarted, remember the name". Two copies of a state transition is precisely how a live
         * stream and a post-hoc render of the same events come to disagree about which stage a Step
         * line belongs to — and the disagreement would be invisible, because both would still print a
         * plausible stage. One function, called by both, is the only version where SCOPE-* is a
         * property of the model rather than of the caller.
         */
        fun after(event: DomainEvent): Scope =
            if (event is StageStarted) Scope(stageNames + (event.stageIndex to event.stageName)) else this
    }

    private fun fold(events: List<DomainEvent>): List<Line> {
        var scope = Scope()
        val out = ArrayList<Line>(events.size)
        for (event in events) {
            scope = scope.after(event)
            out += line(event, scope)
        }
        return out
    }

    /**
     * The routing table: every [DomainEvent] to the one family that renders it.
     *
     * This `when` is exhaustive and constructs nothing. Both halves of that matter: exhaustiveness
     * is what turns a new event into a compile error rather than a line nobody wrote, and
     * constructing nothing is what keeps the list of what exists separate from the list of what each
     * case looks like.
     *
     * @see RendererRoutePartitionTest for the check that routing and rendering agree.
     */
    @Suppress("DEPRECATION") // AgentResolved has no producer but is part of the sealed hierarchy.
    internal fun line(event: DomainEvent, scope: Scope): Line = when (event) {
        is RunStarted, is RunFinished, is CompilationStarted, is CompilationFinished ->
            ConsoleFamilyLines.run(event)

        is StageStarted, is StageFinished, is StageSkipped, is StepStarted, is StepFinished,
        is ParallelBranchStarted, is ParallelBranchFinished,
        -> ConsoleFamilyLines.structure(event, scope)

        is EchoOutputCaptured -> ConsoleFamilyLines.message(event)

        is StepFailed, is GitCheckoutFailed, is ArtifactArchiveFailed, is StashFailed,
        is HtmlReportFailed, is LockAcquireFailed, is InputAborted, is InputDenied,
        is DirectiveDenied, is HttpRequestFailed, is HttpStatusRejected, is StageMarkedUnstable,
        is TimeoutTriggered, is MilestoneAborted,
        -> ConsoleFamilyLines.failure(event)

        is AgentResolved, is ExecutionTargetResolved, is RetryAttemptStarted,
        is RetryAttemptFinished, is TimeoutScheduled, is CatchErrorTriggered, is WorkflowLoaded,
        is WaitUntilPolled, is WaitUntilCompleted, is MilestoneReached, is TimestampsEntered,
        is TimestampsExited, is StepAdmissionObserved, is DirectiveAdmitted, is GateEvaluated,
        is PostConditionSelected,
        -> ConsoleFamilyLines.controlNote(event)

        is StashCreated, is StashRestored, is HtmlReportPublished, is HtmlReportSkipped,
        is LockRequested, is LockAcquired, is LockReleased, is LockSkipped,
        -> ConsoleFamilyLines.resourceNote(event)

        is InputRequested, is InputProceed,
        is CredentialBound, is CredentialUsed, is CredentialUnbound,
        -> ConsoleFamilyLines.interactionNote(event)

        is GitCheckoutStarted, is GitCheckoutCompleted, is GitPollChanged,
        is FileWritten, is FileRead, is FileExistsChecked, is ArtifactArchived,
        is DirEntered, is DirExited, is DirDeleted, is WsCleaned,
        is PwdResolved, is UnixDetected, is HttpRequestStarted, is HttpResponseReceived,
        -> ConsoleFamilyLines.ioNote(event)

        is PluginEventEmitted -> ConsoleFamilyLines.pluginNote(event)
    }
}
