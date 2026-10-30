package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.CredentialsRef
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
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
import dev.rubentxu.pipeline.v2.events.PostConditionSelected
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted
import dev.rubentxu.pipeline.v2.events.PwdResolved
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunCorrelated
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
import java.time.Instant

/**
 * Why ONE event object inside a payload did not become a [DomainEvent], naming the field.
 * This exists because a bare `null` cannot answer a question the store is obliged to ask. When
 * `decodeStoredRow` refuses a row, it used to quote the payload — but a real payload can be
 * GiB-scale, so the quote is truncated at [STORED_ROW_DETAIL_CHARS] and the offending field is
 * routinely cut off. Measured: a 339-character payload with a diagnostics blob produced a
 * refusal whose detail ended mid-filler, with neither the field name nor the offending value
 * present. An operator reading a refused row could not learn WHICH field failed.
 * So the cause travels with the refusal instead of being recoverable from an echo of the very
 * bytes that were unreadable. [UnreadableField] is the cause; [UnknownEventKind] is kept
 * separate because an unknown kind is version skew and is reported through
 * `UndecodableReason.UnknownKind` by a caller that can act on it, while an unreadable field in
 * a known kind is corruption.
 * This is private on purpose. [decode] and [decodeStoredRow] keep their public shapes, so no
 * caller outside this file can depend on the codec's internal failure vocabulary.
*/
internal sealed interface EventDecodeFailure {

    /** The cause, rendered for [dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload]. */
    val description: String

    /** [field] held nothing, or held something this kind cannot read as the type it needs. */
    data class UnreadableField(val field: String, val value: String?) : EventDecodeFailure {
        override val description: String = buildString {
            append("field \"").append(field).append("\" is unreadable")
            if (value != null) append(": ").append(value.take(FIELD_VALUE_DETAIL_CHARS))
        }
    }

    /** The object names a [kind] this binary has no decoder for — forward-compat, not damage. */
    data class UnknownEventKind(val kind: String) : EventDecodeFailure {
        override val description: String = "no decoder for kind \"$kind\""
    }

    /**
     * The payload was read into an event whose own kind is not the kind that was announced.
     *
     * The matrix says which kind belongs to which type, and the decoder built a type. When the
     * two do not agree, the row was not what it announced itself to be: that is damage, not skew,
     * and it is refused instead of accepted under a kind nobody asked for.
     */
    data class KindMismatch(val announcedKind: String, val decodedKind: String) : EventDecodeFailure {
        override val description: String =
            "kind \"$announcedKind\" decoded into an event whose kind is \"$decodedKind\""
    }
}

/** How much of an offending VALUE a cause quotes. Bounded for the same reason as the payload. */
internal const val FIELD_VALUE_DETAIL_CHARS = 64

/**
 * What [decodeEvent] produced: the event, or the cause it could not produce.
 * Carries its own payload on each side so a caller cannot be tempted to read a null as
 * "nothing to report" — that is the exact confusion this type removes. [decodeStoredRow] MUST
 * answer for the row it was given, so it needs the failure, not its absence.
*/
internal sealed interface EventDecodeOutcome {

    data class Decoded(val event: DomainEvent) : EventDecodeOutcome

    data class Failed(val cause: EventDecodeFailure) : EventDecodeOutcome
}
/**
 * How ONE event object becomes a [DomainEvent].
 *
 * ## Why this lives apart from [JsonEventLog]
 *
 * The log's framing and an event's own shape are two different questions. The framing — where an
 * event array starts and ends, what a stored row is, which kinds this binary can decode — is
 * [JsonEventLog]'s job. The shape of each kind is this object's job, and it is a table with one
 * arm per kind: a long table, and a long table does not belong inside the object that frames the
 * document around it.
 *
 * The seam is the mirror of the writer's: [EventJsonWriter] turns an event into JSON, this turns
 * JSON back into an event, and the two files say the same kinds in the same order.
 *
 * ## The vocabulary is declared once
 *
 * The kinds this binary can decode are [EventKind]'s, and the `when` below is the decoder for
 * them. The kinds are not restated here: a kind this binary does not know is refused BEFORE the
 * table is entered, and a kind it knows has an arm, because the table is exhaustive over
 * [EventKind] and the compiler says so.
 *
 * What the table cannot hold is the WIRING: an arm that builds the wrong type for a kind, and a
 * type whose own kind literal no longer names its constant, are strings to the compiler and
 * invisible to it. So the decoder compares what it built against the kind that was announced, and
 * refuses the row when the two disagree.
 *
 * The failure vocabulary is internal to the module, on purpose: [JsonEventLog.decode] and
 * [JsonEventLog.decodeStoredRow] keep their public shapes, so nothing outside this module can
 * depend on how a row failed to decode.
 */
internal object DomainEventJsonDecoder {
    /**
     * Does this binary have a decoder for [kind]? E4c
     * [decode] answers with events and cannot say WHY one is missing: an unknown `kind` and a known
     * `kind` with a bad payload both come back as "no event". A durable read that has to report a
     * refusal needs that distinction — "this runtime is older than the row" is version skew worth
     * surfacing upward, and "this runtime knows the row and cannot read it" is corruption — so it
     * cannot infer one from the other.
     * The answer is the vocabulary's own: [EventKindMatrix] holds the kinds this binary reads and
     * writes, and this asks it. There is no second list to keep in step with the table.
*/
    fun knowsKind(kind: String): Boolean = EventKindMatrix.knows(kind)

    fun decode(s: String): EventDecodeOutcome {
        fun absent(field: String) = EventDecodeOutcome.Failed(EventDecodeFailure.UnreadableField(field, null))
        fun unreadable(field: String, value: String?) =
            EventDecodeOutcome.Failed(EventDecodeFailure.UnreadableField(field, value))
        val eventId = EventJsonFields.stringField(s, "eventId") ?: return absent("eventId")
        val runId = EventJsonFields.stringField(s, "runId") ?: return absent("runId")
        val sequence = EventJsonFields.longField(s, "sequence")
            ?: return absent("sequence")
        val kindWire = EventJsonFields.stringField(s, "kind") ?: return absent("kind")
        val occurredAtStr = EventJsonFields.stringField(s, "occurredAt")
            ?: return absent("occurredAt")
        // An occurredAt that is present but unparseable is refused. Falling back to
        // Instant.now stamped every replayed row with the time of READING it, so
        // an event from months ago claimed to have happened during the replay, and
        // two reads of the same row produced two different events.
        val occurredAt = try {
            Instant.parse(occurredAtStr)
        } catch (_: Exception) {
            return unreadable("occurredAt", occurredAtStr)
        }
        // The kind is resolved to the vocabulary BEFORE the table is entered: a kind this binary
        // does not know is refused with its own reason, and every arm below is reached only for a
        // kind the vocabulary holds.
        val kind = EventKindMatrix.fromWire(kindWire)
            ?: return EventDecodeOutcome.Failed(EventDecodeFailure.UnknownEventKind(kindWire))

        val event = when (kind) {
            EventKind.RunStarted -> {
                val scriptPath = EventJsonFields.stringField(s, "scriptPath")
                    ?: return EventDecodeOutcome.Failed(
                        EventDecodeFailure.UnreadableField("scriptPath", kind.wire),
                    )
                RunStarted(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    scriptPath = scriptPath,
                )
            }
            // Decode the correlation event so
            // the durable log can re-emit it on a replay / observe
            // without losing the correlationId binding.
            EventKind.RunCorrelated -> {
                val correlationIdStr = EventJsonFields.stringField(s, "correlationId")
                    ?: return EventDecodeOutcome.Failed(
                        EventDecodeFailure.UnreadableField("correlationId", kind.wire),
                    )
                val ownerId = EventJsonFields.stringField(s, "ownerId")
                    ?: return EventDecodeOutcome.Failed(
                        EventDecodeFailure.UnreadableField("ownerId", kind.wire),
                    )
                val startedAtMs = EventJsonFields.longField(s, "startedAtMs")
                    ?: return EventDecodeOutcome.Failed(
                        EventDecodeFailure.UnreadableField("startedAtMs", kind.wire),
                    )
                RunCorrelated(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    correlationId = dev.rubentxu.pipeline.v2.domain.CorrelationId(correlationIdStr),
                    ownerId = ownerId,
                    startedAtMs = startedAtMs,
                )
            }
            EventKind.CompilationStarted -> CompilationStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
            )
            EventKind.CompilationFinished -> {
                val cacheKey = EventJsonFields.parseCacheKey(s)
                    ?: return absent("cacheKey")
                val diagnostics = EventJsonDecoder.decodeDiagnostics(s)
                CompilationFinished(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    cacheKey = cacheKey,
                    diagnostics = diagnostics,
                )
            }
            EventKind.RunFinished -> {
                val outcome = EventJsonFields.stringField(s, "outcome") ?: "unknown"
                val diagnostics = EventJsonDecoder.decodeDiagnostics(s)
                RunFinished(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    outcome = outcome,
                    diagnostics = diagnostics,
                )
            }
            EventKind.StageStarted -> StageStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stageName = EventJsonFields.stringField(s, "stageName") ?: "",
            )
            EventKind.StageFinished -> StageFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stageName = EventJsonFields.stringField(s, "stageName") ?: "",
                outcome = EventJsonFields.stringField(s, "outcome") ?: "unknown",
            )
            EventKind.StepStarted -> StepStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
                stepName = EventJsonFields.stringField(s, "stepName") ?: "",
                stepType = EventJsonFields.stringField(s, "stepType") ?: "",
            )
            EventKind.StepFinished -> StepFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
                stepName = EventJsonFields.stringField(s, "stepName") ?: "",
                stepType = EventJsonFields.stringField(s, "stepType") ?: "",
                // A row written before the verdict was published has no such field, and it decodes
                // to no verdict: that build said nothing about the step's result, and this reader
                // must not invent one.
                outcome = EventJsonFields.optionalStringField(s, OptionalEventField.OUTCOME),
            )
            EventKind.AgentResolved -> AgentResolved(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                agentLabel = EventJsonFields.stringField(s, "agentLabel") ?: "",
                remoteUri = EventJsonFields.optionalStringField(s, OptionalEventField.REMOTE_URI),
            )
            EventKind.ExecutionTargetResolved -> ExecutionTargetResolved(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stageName = EventJsonFields.stringField(s, "stageName") ?: "",
                directiveKey = EventJsonFields.stringField(s, "directiveKey") ?: "",
                requirement = EventJsonFields.stringField(s, "requirement") ?: "",
                targetId = EventJsonFields.stringField(s, "targetId") ?: "",
            )
            EventKind.ParallelBranchStarted -> ParallelBranchStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                branchIndex = EventJsonFields.intField(s, "branchIndex") ?: 0,
                branchName = EventJsonFields.stringField(s, "branchName") ?: "",
                parentStageIndex = EventJsonFields.intField(s, "parentStageIndex") ?: 0,
            )
            EventKind.ParallelBranchFinished -> ParallelBranchFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                branchIndex = EventJsonFields.intField(s, "branchIndex") ?: 0,
                branchName = EventJsonFields.stringField(s, "branchName") ?: "",
                parentStageIndex = EventJsonFields.intField(s, "parentStageIndex") ?: 0,
                outcome = EventJsonFields.stringField(s, "outcome") ?: "unknown",
            )
            EventKind.RetryAttemptStarted -> RetryAttemptStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                attemptNumber = EventJsonFields.intField(s, "attemptNumber") ?: 1,
                maxAttempts = EventJsonFields.intField(s, "maxAttempts") ?: 1,
                stepName = EventJsonFields.stringField(s, "stepName") ?: "",
                stepType = EventJsonFields.stringField(s, "stepType") ?: "",
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
            )
            EventKind.RetryAttemptFinished -> RetryAttemptFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                attemptNumber = EventJsonFields.intField(s, "attemptNumber") ?: 1,
                maxAttempts = EventJsonFields.intField(s, "maxAttempts") ?: 1,
                stepName = EventJsonFields.stringField(s, "stepName") ?: "",
                stepType = EventJsonFields.stringField(s, "stepType") ?: "",
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
                outcome = EventJsonFields.stringField(s, "outcome") ?: "unknown",
            )
            EventKind.TimeoutScheduled -> TimeoutScheduled(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                timeoutSeconds = EventJsonFields.longField(s, "timeoutSeconds") ?: 0L,
                timeoutAction = EventJsonFields.stringField(s, "timeoutAction") ?: "FAIL",
                stepName = EventJsonFields.optionalStringField(s, OptionalEventField.STEP_NAME),
                stepType = EventJsonFields.optionalStringField(s, OptionalEventField.STEP_TYPE),
                stageIndex = EventJsonFields.intField(s, "stageIndex")?.takeIf { it != -1 },
                stepIndex = EventJsonFields.intField(s, "stepIndex")?.takeIf { it != -1 },
            )
            EventKind.StepFailed -> {
                val failureKindStr = EventJsonFields.stringField(s, "failureKind") ?: "UNKNOWN"
                val failureKind = try {
                    FailureKind.valueOf(failureKindStr)
                } catch (_: Exception) {
                    FailureKind.UNKNOWN
                }
                StepFailed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    // The stage the failure belongs to. A row written before the field existed
                    // has no stage at all, and it decodes to "this event does not name the
                    // stage" — never to stage 0, which is a stage like any other.
                    stageIndex = EventJsonFields.intField(s, "stageIndex")?.takeIf { it != -1 },
                    stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
                    stepName = EventJsonFields.stringField(s, "stepName") ?: "",
                    stepType = EventJsonFields.stringField(s, "stepType") ?: "",
                    failureKind = failureKind,
                    message = EventJsonFields.stringField(s, "message") ?: "",
                )
            }
            EventKind.EchoOutputCaptured -> EchoOutputCaptured(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
                content = EventJsonFields.stringField(s, "content") ?: "",
            )
            EventKind.CredentialBound -> {
                // An ABSENT purpose keeps its documented `API_KEY` default: a missing optional
                // field is a tolerated shape. A PRESENT-but-unreadable token is not the same
                // defect and MUST NOT be folded into that default — `BoundPurpose` has no
                // `UNKNOWN` member, so defaulting here does not degrade to a neutral value, it
                // asserts "this credential is an API key" on a row that never said so. On a
                // durable event that is a wrong fact with no way for an observer to tell it apart
                // from a real API-key binding, so the row is refused instead and
                // `decodeStoredRow` reports the refusal against this known kind.
                val purpose = when (val purposeStr = EventJsonFields.stringField(s, "purpose")) {
                    null -> BoundPurpose.API_KEY
                    else -> BoundPurpose.parse(purposeStr)
                        ?: return unreadable("purpose", purposeStr)
                }
                val credIdStr = EventJsonFields.stringField(s, "credentialsId") ?: ""
                CredentialBound(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    credentialsId = CredentialsId(credIdStr),
                    purpose = purpose,
                )
            }
            EventKind.CredentialUsed -> {
                // Same rule as `CredentialBound` above: absent keeps the documented default,
                // present-but-unreadable refuses rather than inventing a binding kind.
                val purpose = when (val purposeStr = EventJsonFields.stringField(s, "purpose")) {
                    null -> BoundPurpose.API_KEY
                    else -> BoundPurpose.parse(purposeStr)
                        ?: return unreadable("purpose", purposeStr)
                }
                val credIdStr = EventJsonFields.stringField(s, "credentialsId") ?: ""
                CredentialUsed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    credentialsId = CredentialsId(credIdStr),
                    purpose = purpose,
                    stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
                )
            }
            EventKind.CredentialUnbound -> {
                val credIdStr = EventJsonFields.stringField(s, "credentialsId") ?: ""
                CredentialUnbound(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    credentialsId = CredentialsId(credIdStr),
                )
            }
            // SCM Events
            EventKind.GitCheckoutStarted -> {
                val url = EventJsonFields.stringField(s, "url") ?: ""
                val branch = EventJsonFields.stringField(s, "branch") ?: ""
                val credIdStr = EventJsonFields.stringField(s, "credentialsRef")
                GitCheckoutStarted(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    url = url,
                    branch = branch,
                    credentialsRef = credIdStr?.let { CredentialsRef(CredentialsId(it)) },
                )
            }
            EventKind.GitCheckoutCompleted -> {
                val url = EventJsonFields.stringField(s, "url") ?: ""
                val branch = EventJsonFields.stringField(s, "branch") ?: ""
                val sha = EventJsonFields.stringField(s, "sha") ?: ""
                val changelogPath = EventJsonFields.stringField(s, "changelogPath") ?: ""
                val durationMs = EventJsonFields.longField(s, "durationMs") ?: 0L
                GitCheckoutCompleted(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    url = url,
                    branch = branch,
                    sha = sha,
                    changelogPath = changelogPath,
                    durationMs = durationMs,
                )
            }
            EventKind.GitCheckoutFailed -> {
                val url = EventJsonFields.stringField(s, "url") ?: ""
                val branch = EventJsonFields.stringField(s, "branch") ?: ""
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                val exitCode = EventJsonFields.intField(s, "exitCode") ?: 0
                GitCheckoutFailed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    url = url,
                    branch = branch,
                    reason = reason,
                    exitCode = exitCode,
                )
            }
            EventKind.GitPollChanged -> {
                val url = EventJsonFields.stringField(s, "url") ?: ""
                val branch = EventJsonFields.stringField(s, "branch") ?: ""
                val previousSha = EventJsonFields.stringField(s, "previousSha")
                val newSha = EventJsonFields.stringField(s, "newSha") ?: ""
                GitPollChanged(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    url = url,
                    branch = branch,
                    previousSha = previousSha,
                    newSha = newSha,
                )
            }
            // Jenkins File + Artefact Events
            EventKind.FileWritten -> {
                val pathStr = EventJsonFields.stringField(s, "path") ?: ""
                val path = java.nio.file.Paths.get(pathStr)
                val sha256 = EventJsonFields.stringField(s, "sha256") ?: ""
                val size = EventJsonFields.longField(s, "size") ?: 0L
                val atomicallyMoved = EventJsonFields.boolField(s, "atomicallyMoved")
                FileWritten(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    path = path,
                    sha256 = sha256,
                    size = size,
                    atomicallyMoved = atomicallyMoved,
                )
            }
            EventKind.FileRead -> {
                val pathStr = EventJsonFields.stringField(s, "path") ?: ""
                val path = java.nio.file.Paths.get(pathStr)
                val sha256 = EventJsonFields.stringField(s, "sha256")
                val size = EventJsonFields.longField(s, "size")
                FileRead(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    path = path,
                    sha256 = sha256,
                    size = size,
                )
            }
            EventKind.FileExistsChecked -> {
                val pathStr = EventJsonFields.stringField(s, "path") ?: ""
                val path = java.nio.file.Paths.get(pathStr)
                val exists = EventJsonFields.boolField(s, "exists") ?: false
                FileExistsChecked(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    path = path,
                    exists = exists,
                )
            }
            EventKind.ArtifactArchived -> {
                val files = EventJsonDecoder.decodeArtifactEntriesOrRefuse(s)
                    ?: return unreadable("files", kind.wire)
                ArtifactArchived(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    files = files,
                )
            }
            EventKind.ArtifactArchiveFailed -> {
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                ArtifactArchiveFailed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    reason = reason,
                )
            }
            // Core.stash/core.unstash durable cross-stage data movement
            EventKind.StashCreated -> {
                val stageName = EventJsonFields.stringField(s, "stageName")
                    ?: return absent("stageName")
                val name = EventJsonFields.stringField(s, "name")
                    ?: return absent("name")
                val files = EventJsonDecoder.decodeStashedEntriesOrRefuse(s)
                    ?: return unreadable("files", kind.wire)
                StashCreated(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageName = stageName,
                    name = name,
                    files = files,
                )
            }
            EventKind.StashRestored -> {
                val stageName = EventJsonFields.stringField(s, "stageName")
                    ?: return absent("stageName")
                val name = EventJsonFields.stringField(s, "name")
                    ?: return absent("name")
                val entries = EventJsonDecoder.decodeRestoredEntriesOrRefuse(s)
                    ?: return unreadable("entries", kind.wire)
                StashRestored(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageName = stageName,
                    name = name,
                    entries = entries,
                )
            }
            EventKind.StashFailed -> {
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val name = EventJsonFields.stringField(s, "name") ?: ""
                val operation = EventJsonFields.stringField(s, "operation") ?: ""
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                StashFailed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageName = stageName,
                    name = name,
                    operation = operation,
                    reason = reason,
                )
            }
            // Core.publishHTML durable HTML report publishing
            EventKind.HtmlReportPublished -> {
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val reportName = EventJsonFields.stringField(s, "reportName") ?: ""
                val reportDir = EventJsonFields.stringField(s, "reportDir") ?: ""
                val entries = EventJsonDecoder.decodeHtmlReportEntries(s)
                val targetPath = EventJsonFields.stringField(s, "targetPath") ?: ""
                HtmlReportPublished(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageName = stageName,
                    reportName = reportName,
                    reportDir = reportDir,
                    entries = entries,
                    targetPath = targetPath,
                )
            }
            EventKind.HtmlReportSkipped -> {
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val reportName = EventJsonFields.stringField(s, "reportName") ?: ""
                val reportDir = EventJsonFields.stringField(s, "reportDir") ?: ""
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                HtmlReportSkipped(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageName = stageName,
                    reportName = reportName,
                    reportDir = reportDir,
                    reason = reason,
                )
            }
            EventKind.HtmlReportFailed -> {
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val reportName = EventJsonFields.stringField(s, "reportName") ?: ""
                val reportDir = EventJsonFields.stringField(s, "reportDir") ?: ""
                val failureKindStr = EventJsonFields.stringField(s, "failureKind") ?: "UNKNOWN"
                val failureKind = try {
                    FailureKind.valueOf(failureKindStr)
                } catch (_: IllegalArgumentException) {
                    FailureKind.UNKNOWN
                }
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                HtmlReportFailed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageName = stageName,
                    reportName = reportName,
                    reportDir = reportDir,
                    failureKind = failureKind,
                    reason = reason,
                )
            }
            EventKind.DirectiveAdmitted -> {
                val stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val directiveKey = EventJsonFields.stringField(s, "directiveKey") ?: ""
                val phase = EventJsonFields.stringField(s, "phase") ?: ""
                val policy = EventJsonFields.stringField(s, "policy") ?: ""
                DirectiveAdmitted(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageIndex = stageIndex,
                    stageName = stageName,
                    directiveKey = directiveKey,
                    phase = phase,
                    policy = policy,
                )
            }
            EventKind.DirectiveDenied -> {
                val stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val directiveKey = EventJsonFields.stringField(s, "directiveKey") ?: ""
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                DirectiveDenied(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageIndex = stageIndex,
                    stageName = stageName,
                    directiveKey = directiveKey,
                    reason = reason,
                )
            }
            EventKind.GateEvaluated -> {
                val stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val directiveKeys = EventJsonFields.stringListField(s, "directiveKeys") ?: emptyList()
                val satisfied = EventJsonFields.boolField(s, "satisfied")
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                GateEvaluated(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageIndex = stageIndex,
                    stageName = stageName,
                    directiveKeys = directiveKeys,
                    satisfied = satisfied,
                    reason = reason,
                )
            }
            EventKind.StageSkipped -> {
                val stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                StageSkipped(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageIndex = stageIndex,
                    stageName = stageName,
                    reason = reason,
                )
            }
            EventKind.PostConditionSelected -> {
                val stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val stageOutcome = EventJsonFields.stringField(s, "stageOutcome") ?: ""
                val selectedConditions = EventJsonFields.stringListField(s, "selectedConditions") ?: emptyList()
                val skippedConditions = EventJsonFields.stringListField(s, "skippedConditions") ?: emptyList()
                PostConditionSelected(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageIndex = stageIndex,
                    stageName = stageName,
                    stageOutcome = stageOutcome,
                    selectedConditions = selectedConditions,
                    skippedConditions = skippedConditions,
                )
            }
            EventKind.DirEntered -> {
                val path = EventJsonFields.stringField(s, "path") ?: ""
                val previousPath = EventJsonFields.stringField(s, "previousPath") ?: ""
                DirEntered(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    path = path,
                    previousPath = previousPath,
                )
            }
            EventKind.DirExited -> {
                val path = EventJsonFields.stringField(s, "path") ?: ""
                val restoredTo = EventJsonFields.stringField(s, "restoredTo") ?: ""
                DirExited(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    path = path,
                    restoredTo = restoredTo,
                )
            }
            EventKind.DirDeleted -> {
                val path = EventJsonFields.stringField(s, "path") ?: ""
                val deletedCount = EventJsonFields.intField(s, "deletedCount") ?: 0
                val sha256 = EventJsonFields.stringField(s, "sha256") ?: ""
                DirDeleted(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    path = path,
                    deletedCount = deletedCount,
                    sha256 = sha256,
                )
            }
            EventKind.WsCleaned -> {
                val deletedFiles = EventJsonFields.intField(s, "deletedFiles") ?: 0
                val deletedDirs = EventJsonFields.intField(s, "deletedDirs") ?: 0
                val patterns = EventJsonDecoder.decodeStringList(s, "patterns")
                val sha256 = EventJsonFields.stringField(s, "sha256") ?: ""
                WsCleaned(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    deletedFiles = deletedFiles,
                    deletedDirs = deletedDirs,
                    patterns = patterns,
                    sha256 = sha256,
                )
            }
            EventKind.CatchErrorTriggered -> {
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                // Both results now cross the codec as a CLOSED vocabulary.
                // `buildResult` stays nullable (a declared absence is legitimate: the DSL's
                // default), but a PRESENT token outside the vocabulary is corruption and is
                // refused, exactly like the absent-and-required `stageResult` below.
                val buildResult = EventJsonFields.optionalStringField(s, OptionalEventField.BUILD_RESULT)
                    ?.let { CatchErrorBuildResult.parse(it) ?: return unreadable("buildResult", it) }
                // Was `?: "UNSTABLE"`. That default turned a MISSING field into a
                // semantic claim, and the claim was not neutral: UNSTABLE means the run
                // continues while FAILURE aborts it. A record whose stageResult could not be
                // read therefore came back describing a different run than the one that
                // happened. The writer ALWAYS emits this key (EventJsonWriter writes
                // `stageResult` unconditionally, and the compatibility corpus carries it in
                // 4/4 historical occurrences), so absence is corruption rather than a
                // version this runtime predates — and the key being present with a JSON null
                // is a real encoding, because the field is nullable.
                // `stageResult` is a String in the event, so a JSON null is not a
                // legitimate encoding of "not declared" — it is corruption, exactly like the
                // key being absent. One reader covers both, which is why no hasField probe
                // is needed here even though `buildResult` right above it IS nullable.
                val stageResult = EventJsonFields.stringField(s, "stageResult")
                    ?.let { CatchErrorBuildResult.parse(it) ?: return unreadable("stageResult", it) }
                    ?: return absent("stageResult")
                val message = EventJsonFields.optionalStringField(s, OptionalEventField.MESSAGE)
                CatchErrorTriggered(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageName = stageName,
                    buildResult = buildResult,
                    stageResult = stageResult,
                    message = message,
                )
            }
            EventKind.StageMarkedUnstable -> {
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val message = EventJsonFields.stringField(s, "message") ?: ""
                StageMarkedUnstable(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageName = stageName,
                    message = message,
                )
            }
            EventKind.WorkflowLoaded -> {
                val path = EventJsonFields.stringField(s, "path") ?: ""
                val stepCount = EventJsonFields.intField(s, "stepCount") ?: 0
                val sha256 = EventJsonFields.stringField(s, "sha256") ?: ""
                WorkflowLoaded(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    path = path,
                    stepCount = stepCount,
                    sha256 = sha256,
                )
            }
            EventKind.WaitUntilPolled -> {
                val attempt = EventJsonFields.intField(s, "attempt") ?: 1
                val durationMs = EventJsonFields.longField(s, "durationMs") ?: 0L
                val conditionResult = EventJsonFields.boolField(s, "conditionResult") ?: false
                WaitUntilPolled(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    attempt = attempt,
                    durationMs = durationMs,
                    conditionResult = conditionResult,
                )
            }
            EventKind.WaitUntilCompleted -> {
                val totalAttempts = EventJsonFields.intField(s, "totalAttempts") ?: 0
                val totalDurationMs = EventJsonFields.longField(s, "totalDurationMs") ?: 0L
                // Was `?: "completed"`. Same defect, opposite direction: a missing
                // outcome decoded as SATISFACTION. `completed` is the one value that means
                // the wait condition held, so the default reported success for a record that
                // said nothing at all. The field is non-null in the event and the writer
                // always emits it, so absence is malformed and the line stays unclassified.
                // Vocabulary validation is deliberately NOT here: whether these three tokens
                // are one concept is the open E4b.4 question, and validating against an
                // undecided vocabulary would freeze it by accident.
                val outcome = EventJsonFields.stringField(s, "outcome")
                    ?: return absent("outcome")
                WaitUntilCompleted(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    totalAttempts = totalAttempts,
                    totalDurationMs = totalDurationMs,
                    outcome = outcome,
                )
            }
            EventKind.PwdResolved -> {
                val path = EventJsonFields.stringField(s, "path") ?: ""
                val workspaceRoot = EventJsonFields.stringField(s, "workspaceRoot") ?: ""
                val sha256 = EventJsonFields.stringField(s, "sha256") ?: ""
                PwdResolved(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    path = path,
                    workspaceRoot = workspaceRoot,
                    sha256 = sha256,
                )
            }
            EventKind.UnixDetected -> {
                val isUnix = EventJsonFields.boolField(s, "isUnix") ?: false
                val osName = EventJsonFields.stringField(s, "osName") ?: ""
                val sha256 = EventJsonFields.stringField(s, "sha256") ?: ""
                UnixDetected(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    isUnix = isUnix,
                    osName = osName,
                    sha256 = sha256,
                )
            }
            EventKind.MilestoneReached -> {
                val ordinal = EventJsonFields.intField(s, "ordinal") ?: 0
                val label = EventJsonFields.optionalStringField(s, OptionalEventField.LABEL)
                MilestoneReached(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    ordinal = ordinal,
                    label = label,
                )
            }
            EventKind.MilestoneAborted -> {
                val ordinal = EventJsonFields.intField(s, "ordinal") ?: 0
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                MilestoneAborted(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    ordinal = ordinal,
                    reason = reason,
                )
            }
            // / lock events
            EventKind.LockRequested -> {
                val resource = EventJsonFields.stringField(s, "resource") ?: ""
                val reason = EventJsonFields.optionalStringField(s, OptionalEventField.REASON)
                val skipIfLocked = EventJsonFields.boolField(s, "skipIfLocked") ?: false
                LockRequested(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    resource = resource,
                    reason = reason,
                    skipIfLocked = skipIfLocked,
                )
            }
            EventKind.LockAcquired -> LockAcquired(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                resource = EventJsonFields.stringField(s, "resource") ?: "",
            )
            EventKind.LockReleased -> LockReleased(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                resource = EventJsonFields.stringField(s, "resource") ?: "",
            )
            EventKind.LockSkipped -> {
                val resource = EventJsonFields.stringField(s, "resource") ?: ""
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                LockSkipped(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    resource = resource,
                    reason = reason,
                )
            }
            EventKind.LockAcquireFailed -> {
                val resource = EventJsonFields.stringField(s, "resource") ?: ""
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                LockAcquireFailed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    resource = resource,
                    reason = reason,
                )
            }
            // / httpRequest events
            EventKind.HttpRequestStarted -> {
                HttpRequestStarted(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    url = EventJsonFields.stringField(s, "url") ?: "",
                    method = EventJsonFields.stringField(s, "method") ?: "GET",
                    headerCount = EventJsonFields.longField(s, "headerCount")?.toInt() ?: 0,
                )
            }
            EventKind.HttpResponseReceived -> {
                HttpResponseReceived(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    url = EventJsonFields.stringField(s, "url") ?: "",
                    status = EventJsonFields.longField(s, "status")?.toInt() ?: 0,
                    durationMs = EventJsonFields.longField(s, "durationMs") ?: 0L,
                )
            }
            EventKind.HttpStatusRejected -> {
                HttpStatusRejected(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    url = EventJsonFields.stringField(s, "url") ?: "",
                    status = EventJsonFields.longField(s, "status")?.toInt() ?: 0,
                    accepted = EventJsonFields.stringField(s, "accepted") ?: "",
                )
            }
            EventKind.HttpRequestFailed -> {
                HttpRequestFailed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    url = EventJsonFields.stringField(s, "url") ?: "",
                    reason = EventJsonFields.stringField(s, "reason") ?: "",
                )
            }
            // / input events
            EventKind.InputRequested -> {
                val message = EventJsonFields.stringField(s, "message") ?: ""
                val submitter = EventJsonFields.optionalStringField(s, OptionalEventField.SUBMITTER)
                val id = EventJsonFields.optionalStringField(s, OptionalEventField.ID)
                InputRequested(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    message = message,
                    submitter = submitter,
                    id = id,
                )
            }
            EventKind.InputProceed -> {
                val submitter = EventJsonFields.optionalStringField(s, OptionalEventField.SUBMITTER)
                val message = EventJsonFields.optionalStringField(s, OptionalEventField.MESSAGE)
                InputProceed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    submitter = submitter,
                    message = message,
                )
            }
            EventKind.InputAborted -> {
                val submitter = EventJsonFields.optionalStringField(s, OptionalEventField.SUBMITTER)
                val message = EventJsonFields.optionalStringField(s, OptionalEventField.MESSAGE)
                InputAborted(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    submitter = submitter,
                    message = message,
                )
            }
            EventKind.InputDenied -> InputDenied(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                reason = EventJsonFields.stringField(s, "reason") ?: "",
            )
            EventKind.TimeoutTriggered -> {
                val stageOrStep = EventJsonFields.stringField(s, "stageOrStep") ?: ""
                val action = EventJsonFields.stringField(s, "action") ?: "interrupt"
                val durationMs = EventJsonFields.longField(s, "durationMs") ?: 0L
                TimeoutTriggered(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageOrStep = stageOrStep,
                    action = action,
                    durationMs = durationMs,
                )
            }
            // Connascence fix: these three variants were encodable
            // but NOT decodable (decodeEvent returned null), silently dropping
            // them from every replay/observation surface that reads persisted
            // history through JsonEventLog.
            EventKind.TimestampsEntered -> TimestampsEntered(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
            )
            EventKind.TimestampsExited -> TimestampsExited(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
            )
            EventKind.StepAdmissionObserved -> {
                val stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0
                val stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0
                val stepKey = EventJsonFields.stringField(s, "stepKey") ?: ""
                val law = EventJsonFields.stringField(s, "law") ?: ""
                val executorCalls = EventJsonFields.intField(s, "executorCalls") ?: 0
                StepAdmissionObserved(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    stepKey = stepKey,
                    law = law,
                    executorCalls = executorCalls,
                )
            }
            // The plugin carrier. Written by EventJsonWriter as
            // registryKind/schemaVersion/payload/emittedBy.
            // The two identity fields are NOT defaulted the way the fields above are, and the
            // difference is deliberate. A StageSkipped with no `reason` is still the StageSkipped
            // that happened. A PluginEventEmitted with no `registryKind` or no `schemaVersion` is
            // not a degraded carrier — it is an unidentifiable one: any substituted value would
            // re-type the payload as SOME OTHER plugin's event, which is a wrong observation
            // rather than a missing one. So a malformed carrier line decodes to null here, the
            // same as a line this function cannot classify at all, and the registry refuses it
            // again on the read-back side. Two fail-closed hops beat one confident fabrication.
            EventKind.PluginEventEmitted -> {
                val registryKind = EventJsonFields.stringField(s, "registryKind")
                val schemaVersion = EventJsonFields.intField(s, "schemaVersion")
                if (registryKind.isNullOrBlank() || schemaVersion == null) {
                    null
                } else {
                    PluginEventEmitted(
                        eventId = eventId,
                        runId = runId,
                        sequence = sequence,
                        occurredAt = occurredAt,
                        registryKind = registryKind,
                        schemaVersion = schemaVersion,
                        payload = EventJsonFields.stringField(s, "payload") ?: "",
                        emittedBy = EventJsonFields.stringField(s, "emittedBy") ?: "",
                    )
                }
            }
            // decode the armed-obligation notification. The knowledge of how this
            // event is encoded lives in CompensationArmedCodec, so the dispatcher here stays a
            // dispatcher and does not grow one branch per event.
            EventKind.CompensationArmed -> CompensationArmedCodec.decode(
                s = s,
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
            )
            // the loop's three facts. Their knowledge of how they are encoded
            // lives in SagaLoopCodec, so the dispatcher here stays a dispatcher and the file
            // does not grow one branch per event.
            EventKind.CompensationStarted,
            EventKind.CompensationFinished,
            EventKind.SagaTerminal,
            -> SagaLoopCodec.decode(
                kind = kind.wire,
                s = s,
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
            )
        }
        val decoded = event ?: return malformedBranch(kind.wire)
        // The matrix says which kind belongs to which type. When the arm built a different one,
        // the row was not what it announced itself to be, and it is refused rather than accepted
        // under a kind nobody asked for.
        if (!EventKindMatrix.declares(kind, decoded.kind)) {
            return EventDecodeOutcome.Failed(
                EventDecodeFailure.KindMismatch(
                    announcedKind = kind.wire,
                    decodedKind = decoded.kind,
                ),
            )
        }
        return EventDecodeOutcome.Decoded(decoded)
    }

    /**
     * The arm for this kind produced no event.
     *
     * The table is exhaustive over [EventKind] and the compiler says so, but an arm may still
     * answer that its event cannot be built from this payload: the codec behind it reads fields
     * this object does not have to be told about, and it declines rather than inventing. That
     * refusal is a stated failure, not a null nobody reads.
*/
    private fun malformedBranch(kind: String): EventDecodeOutcome = EventDecodeOutcome.Failed(
        EventDecodeFailure.UnreadableField("kind", "the decoder produced no event for kind \"$kind\"")
    )
}
