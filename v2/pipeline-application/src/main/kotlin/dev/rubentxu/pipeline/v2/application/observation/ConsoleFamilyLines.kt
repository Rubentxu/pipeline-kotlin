package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.application.observation.HumanConsoleRenderer.Family
import dev.rubentxu.pipeline.v2.application.observation.HumanConsoleRenderer.Line
import dev.rubentxu.pipeline.v2.application.observation.HumanConsoleRenderer.Scope
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
 * How each family of [DomainEvent] becomes console text.
 *
 * ## Why this is a separate object
 *
 * `HumanConsoleRenderer` is the projection's entry point: it decides which view sees a line and it
 * walks the scope. The text of each case is a different fact with a different owner, and keeping it
 * in the same object made the entry point carry sixteen functions — past the point where a reader
 * opening the file is looking for "what does a console line look like" and gets an inventory instead.
 *
 * So the routing table stays in [HumanConsoleRenderer.line], where the closed dispatch belongs, and
 * the construction lives here. The split is along the seam the types already describe: routing is
 * about WHICH handler, construction is about WHAT it says.
 *
 * ## The contract every function here honours
 *
 * Each takes a [DomainEvent] and returns a [Line], and each ends in `else -> unrouted(...)`, which
 * throws. They are only ever reached through [HumanConsoleRenderer.line], which is exhaustive over
 * the sealed hierarchy, so the fallback is unreachable unless the routing table and a handler
 * disagree — and that disagreement must stop the console rather than produce a plausible wrong line.
 * `RendererRoutePartitionTest` checks the two lists against the hierarchy so the disagreement
 * cannot survive.
 */
internal object ConsoleFamilyLines {

    /** RUN — the run and compilation lifecycle. */
    fun run(event: DomainEvent): Line = when (event) {
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

        else -> unrouted(event, this)
    }

    /**
     * STRUCTURE — stage and Step openers and closers, plus branch markers.
     *
     * The only family that reads [Scope], because a Step line names its stage.
     */
    fun structure(event: DomainEvent, scope: Scope): Line = when (event) {
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

        else -> unrouted(event, this)
    }

    /** MESSAGE — deliberate script output, printed as the user wrote it. */
    fun message(event: DomainEvent): Line = when (event) {
        is EchoOutputCaptured -> Line(Family.MESSAGE, event.content, verbatim = true)

        else -> unrouted(event, this)
    }

    /** FAILURE — anything that went wrong. */
    fun failure(event: DomainEvent): Line = when (event) {
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

        else -> unrouted(event, this)
    }

    /** NOTE — what the pipeline decided about itself, rather than about the work. */
    @Suppress("DEPRECATION") // AgentResolved has no producer but is part of the sealed hierarchy.
    fun controlNote(event: DomainEvent): Line = when (event) {
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

        else -> unrouted(event, this)
    }

    /** NOTE — durable resources the pipeline took and released: stashes, reports, locks. */
    fun resourceNote(event: DomainEvent): Line = when (event) {
        is StashCreated -> Line(Family.NOTE, "stash created: ${event.name}")
        is StashRestored -> Line(Family.NOTE, "stash restored: ${event.name}")

        is HtmlReportPublished ->
            Line(Family.NOTE, "html report published: ${event.reportName} -> ${event.targetPath}")

        is HtmlReportSkipped ->
            Line(Family.NOTE, "html report skipped '${event.reportName}': ${event.reason}")

        is LockRequested -> Line(Family.NOTE, "lock requested: ${event.resource}")
        is LockAcquired -> Line(Family.NOTE, "lock acquired: ${event.resource}")
        is LockReleased -> Line(Family.NOTE, "lock released: ${event.resource}")
        is LockSkipped -> Line(Family.NOTE, "lock skipped '${event.resource}': ${event.reason}")

        else -> unrouted(event, this)
    }

    /** NOTE — the pipeline asking a person, or borrowing something a person provided. */
    fun interactionNote(event: DomainEvent): Line = when (event) {
        is InputRequested -> Line(Family.NOTE, "input requested: ${event.message}")
        is InputProceed -> Line(Family.NOTE, "input proceeded: ${event.submitter}")

        is CredentialBound ->
            Line(Family.NOTE, "credential bound: ${event.credentialsId} (${event.purpose})")

        is CredentialUsed -> Line(Family.NOTE, "credential used: ${event.credentialsId}")
        is CredentialUnbound -> Line(Family.NOTE, "credential unbound: ${event.credentialsId}")

        else -> unrouted(event, this)
    }

    /** NOTE — the outside world: version control, files, directories, HTTP. */
    fun ioNote(event: DomainEvent): Line = when (event) {
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

        else -> unrouted(event, this)
    }

    /**
     * NOTE — a plugin's own event.
     *
     * Its payload is the contributor's serialization, not ours, so the console reports WHICH contract
     * fired rather than guessing at the contents. This branch is also the proof that the exhaustive
     * `when` is doing its job: PluginEventEmitted lives in its own file and was absent from a grep of
     * DomainEvent.kt. An `else` in the routing table would have swallowed it.
     */
    fun pluginNote(event: DomainEvent): Line = when (event) {
        is PluginEventEmitted -> Line(
            Family.NOTE,
            "plugin event ${event.registryKind} v${event.schemaVersion} by ${event.emittedBy}",
        )

        else -> unrouted(event, this)
    }

    /**
     * The last resort of a family function, and deliberately the loudest thing in this file.
     *
     * Reaching it means the routing table and a family handler disagree — an event was sent to a
     * function that does not know it. Returning [Family.NOTE] with a placeholder would turn that
     * programming error into a plausible console line, which is the failure mode the routing table
     * exists to prevent. Naming the event and the function makes the disagreement a one-line
     * diagnosis instead of a bug report.
     */
    private fun unrouted(event: DomainEvent, function: Any): Nothing =
        error("${event::class.simpleName} routed to a family function that does not render it: $function")
}
