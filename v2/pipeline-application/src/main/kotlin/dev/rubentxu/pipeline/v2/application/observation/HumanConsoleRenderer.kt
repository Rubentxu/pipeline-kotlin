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
 * 2. **No `else`.** [line] matches the closed [DomainEvent] hierarchy
 *    exhaustively. A new event is a compile error here, so somebody must decide
 *    how it looks instead of it silently vanishing from the console.
 * 3. **Views select families; they do not delete records.** A family absent
 *    from a view is a declared projection — the event is still in the store and
 *    still visible under [ObservationView.EVENTS].
 * 4. **Closed structural match, never a key switch.** The `when` matches event
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
            if (event is StageStarted) {
                scope = Scope(scope.stageNames + (event.stageIndex to event.stageName))
            }
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
    }

    private fun fold(events: List<DomainEvent>): List<Line> {
        var scope = Scope()
        val out = ArrayList<Line>(events.size)
        for (event in events) {
            if (event is StageStarted) {
                scope = Scope(scope.stageNames + (event.stageIndex to event.stageName))
            }
            out += line(event, scope)
        }
        return out
    }

    @Suppress("DEPRECATION") // AgentResolved has no producer but is part of the sealed hierarchy.
    internal fun line(event: DomainEvent, scope: Scope): Line = when (event) {

        // ---- RUN lifecycle ------------------------------------------------
        is RunStarted -> Line(Family.RUN, "Run started: ${event.scriptPath}")

        is RunFinished -> Line(
            Family.RUN,
            if (event.diagnostics.isEmpty()) "Finished: ${event.outcome}"
            else "Finished: ${event.outcome} (${event.diagnostics.size} diagnostic(s))",
        )

        is CompilationStarted -> Line(Family.RUN, "Compiling pipeline")

        is CompilationFinished -> Line(
            Family.RUN,
            if (event.diagnostics.isEmpty()) "Compilation finished"
            else "Compilation finished with ${event.diagnostics.size} diagnostic(s)",
        )

        // ---- STRUCTURE ----------------------------------------------------
        is StageStarted -> Line(Family.STRUCTURE, "[stage: ${event.stageName}]")

        is StageFinished ->
            Line(Family.STRUCTURE, "[stage: ${event.stageName}] -> ${event.outcome}")

        is StageSkipped ->
            Line(Family.STRUCTURE, "[stage: ${event.stageName}] skipped: ${event.reason}")

        is StepStarted -> Line(
            Family.STRUCTURE,
            "[step: ${scope.stage(event.stageIndex)}] ${event.stepName}",
        )

        is StepFinished -> Line(
            Family.STRUCTURE,
            "[step: ${scope.stage(event.stageIndex)}] ${event.stepName} ok",
        )

        is ParallelBranchStarted ->
            Line(Family.NOTE, "branch ${event.branchIndex} started: ${event.branchName}")

        is ParallelBranchFinished ->
            Line(
                Family.NOTE,
                "branch ${event.branchIndex} finished: ${event.branchName} -> ${event.outcome}",
            )

        // ---- MESSAGE ------------------------------------------------------
        is EchoOutputCaptured -> Line(Family.MESSAGE, event.content, verbatim = true)

        // ---- FAILURE ------------------------------------------------------
        is StepFailed -> Line(
            Family.FAILURE,
            "ERROR: step '${event.stepName}' failed [${event.failureKind}]: ${event.message}",
        )

        is GitCheckoutFailed ->
            Line(Family.FAILURE, "ERROR: git checkout failed: ${event.reason} (exit ${event.exitCode})")

        is ArtifactArchiveFailed ->
            Line(Family.FAILURE, "ERROR: artifact archive failed: ${event.reason}")

        is StashFailed ->
            Line(Family.FAILURE, "ERROR: stash '${event.name}' failed: ${event.reason}")

        is HtmlReportFailed ->
            Line(Family.FAILURE, "ERROR: html report '${event.reportName}' failed: ${event.reason}")

        is LockAcquireFailed ->
            Line(Family.FAILURE, "ERROR: lock '${event.resource}' failed: ${event.reason}")

        is InputAborted -> Line(Family.FAILURE, "ERROR: input aborted: ${event.message}")
        is InputDenied -> Line(Family.FAILURE, "ERROR: input denied: ${event.reason}")
        is DirectiveDenied ->
            Line(Family.FAILURE, "ERROR: directive '${event.directiveKey}' denied: ${event.reason}")

        is HttpRequestFailed ->
            Line(Family.FAILURE, "ERROR: http request failed: ${event.reason} (${event.url})")

        is HttpStatusRejected -> Line(
            Family.FAILURE,
            "ERROR: http status rejected: ${event.status} (accepted ${event.accepted})",
        )

        is StageMarkedUnstable ->
            Line(Family.FAILURE, "UNSTABLE: stage ${event.stageName}: ${event.message}")

        is TimeoutTriggered ->
            Line(Family.FAILURE, "ERROR: timeout ${event.action} on ${event.stageOrStep}")

        is MilestoneAborted ->
            Line(Family.FAILURE, "ERROR: milestone aborted: ${event.reason}")

        // ---- NOTE ---------------------------------------------------------
        is AgentResolved -> Line(Family.NOTE, "agent resolved: ${event.agentLabel}")

        is ExecutionTargetResolved -> Line(
            Family.NOTE,
            "execution target for '${event.directiveKey}': ${event.targetId}",
        )

        is RetryAttemptStarted -> Line(
            Family.NOTE,
            "retry ${event.attemptNumber}/${event.maxAttempts}: ${event.stepName}",
        )

        is RetryAttemptFinished -> Line(
            Family.NOTE,
            "retry ${event.attemptNumber}/${event.maxAttempts}: ${event.stepName} -> ${event.outcome}",
        )

        is TimeoutScheduled -> Line(
            Family.NOTE,
            "timeout ${event.timeoutSeconds}s ${event.timeoutAction}: ${event.stepName}",
        )

        is CatchErrorTriggered ->
            Line(Family.NOTE, "catchError on ${event.stageName}: ${event.message}")

        is WorkflowLoaded ->
            Line(Family.NOTE, "workflow loaded: ${event.path} (${event.stepCount} steps)")

        is WaitUntilPolled ->
            Line(Family.NOTE, "waitUntil polled (attempt ${event.attempt}): ${event.conditionResult}")

        is WaitUntilCompleted ->
            Line(Family.NOTE, "waitUntil completed after ${event.totalAttempts}: ${event.outcome}")

        is MilestoneReached -> Line(Family.NOTE, "milestone ${event.ordinal}: ${event.label}")

        is TimestampsEntered -> Line(Family.NOTE, "timestamps decorator entered")
        is TimestampsExited -> Line(Family.NOTE, "timestamps decorator exited")

        is StepAdmissionObserved -> Line(
            Family.NOTE,
            "admission ${event.stepKey} under ${event.law} (${event.executorCalls} executor call(s))",
        )

        is StashCreated -> Line(Family.NOTE, "stash created: ${event.name}")
        is StashRestored -> Line(Family.NOTE, "stash restored: ${event.name}")

        is HtmlReportPublished ->
            Line(Family.NOTE, "html report published: ${event.reportName} -> ${event.targetPath}")

        is HtmlReportSkipped ->
            Line(Family.NOTE, "html report skipped '${event.reportName}': ${event.reason}")

        is DirectiveAdmitted ->
            Line(Family.NOTE, "directive admitted: ${event.directiveKey} (${event.phase})")

        is GateEvaluated -> Line(
            Family.NOTE,
            "gate on ${event.stageName}: ${if (event.satisfied) "satisfied" else "not satisfied"} " +
                "- ${event.reason}",
        )

        is PostConditionSelected -> Line(
            Family.NOTE,
            "post conditions for ${event.stageName}: selected ${event.selectedConditions.size}, " +
                "skipped ${event.skippedConditions.size}",
        )

        is LockRequested -> Line(Family.NOTE, "lock requested: ${event.resource}")
        is LockAcquired -> Line(Family.NOTE, "lock acquired: ${event.resource}")
        is LockReleased -> Line(Family.NOTE, "lock released: ${event.resource}")
        is LockSkipped -> Line(Family.NOTE, "lock skipped '${event.resource}': ${event.reason}")

        is InputRequested -> Line(Family.NOTE, "input requested: ${event.message}")
        is InputProceed -> Line(Family.NOTE, "input proceeded: ${event.submitter}")

        is CredentialBound ->
            Line(Family.NOTE, "credential bound: ${event.credentialsId} (${event.purpose})")

        is CredentialUsed -> Line(Family.NOTE, "credential used: ${event.credentialsId}")
        is CredentialUnbound -> Line(Family.NOTE, "credential unbound: ${event.credentialsId}")

        is GitCheckoutStarted -> Line(Family.NOTE, "git checkout: ${event.url} (${event.branch})")
        is GitCheckoutCompleted -> Line(Family.NOTE, "git checkout completed: ${event.sha}")
        is GitPollChanged -> Line(Family.NOTE, "git poll: ${event.previousSha} -> ${event.newSha}")

        is FileWritten -> Line(Family.NOTE, "file written: ${event.path} (${event.size} bytes)")
        is FileRead -> Line(Family.NOTE, "file read: ${event.path} (${event.size} bytes)")
        is FileExistsChecked ->
            Line(Family.NOTE, "file exists ${event.path}: ${if (event.exists) "yes" else "no"}")

        is ArtifactArchived -> Line(Family.NOTE, "artifact archived: ${event.files.size} file(s)")

        is DirEntered -> Line(Family.NOTE, "dir entered: ${event.path}")
        is DirExited -> Line(Family.NOTE, "dir exited: ${event.path} -> ${event.restoredTo}")
        is DirDeleted -> Line(Family.NOTE, "dir deleted: ${event.path} (${event.deletedCount} entries)")
        is WsCleaned -> Line(Family.NOTE, "workspace cleaned: ${event.deletedFiles} file(s)")

        is PwdResolved -> Line(Family.NOTE, "pwd: ${event.path}")
        is UnixDetected -> Line(Family.NOTE, "unix detected: ${event.osName}")

        is HttpRequestStarted ->
            Line(Family.NOTE, "http ${event.method} ${event.url} (${event.headerCount} header(s))")

        is HttpResponseReceived ->
            Line(Family.NOTE, "http response ${event.status} in ${event.durationMs}ms")

        // A plugin's own event. Its payload is the contributor's serialization,
        // not ours, so the console reports WHICH contract fired rather than
        // guessing at the contents. This branch is also the proof that the
        // exhaustive `when` is doing its job: PluginEventEmitted lives in its own
        // file and was absent from a grep of DomainEvent.kt. An `else` would have
        // swallowed it.
        is PluginEventEmitted -> Line(
            Family.NOTE,
            "plugin event ${event.registryKind} v${event.schemaVersion} by ${event.emittedBy}",
        )
    }
}