package dev.rubentxu.pipeline.v2.events.durable


import dev.rubentxu.pipeline.v2.domain.BoundPurpose
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
import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
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
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import java.time.Instant

/**
 * JSON wire format encoder/decoder for domain events.
 * Wire shape: single-line JSON array, tagged `kind` field.
 */
object JsonEventLog {

    fun encode(events: List<DomainEvent>): String {
        val sb = StringBuilder("[")
        events.forEachIndexed { index, event ->
            if (index > 0) sb.append(",")
            sb.append(EventJsonWriter.encodeEvent(event))
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * WU-RP-044 (M5 RSS debt): streams the same JSON array as [encode] to
     * [out] one event at a time so the full document is never materialised as
     * a single in-memory String. Byte-for-byte identical output.
     */
    fun encodeTo(events: Sequence<DomainEvent>, out: java.io.Writer) {
        out.append('[')
        var index = 0
        for (event in events) {
            if (index > 0) out.append(',')
            out.append(EventJsonWriter.encodeEvent(event))
            index++
        }
        out.append(']')
    }

    /**
     * Decodes a JSON ARRAY of events, skipping any element that will not decode.
     *
     * ## This is a DOCUMENT codec, and it is NOT the durable read path (P3-E E4c)
     *
     * The `?: continue` below is deliberate and it is why this method must not be used to read
     * stored rows. Its unit is a DOCUMENT, and a document has no per-element identity to report: a
     * caller cannot say "the record at sequence 41 exists and I could not read it" from an empty
     * list, because the sequence was never in this method's hands. Inventing one would create a
     * second authority for position, which is precisely what the store's sequence law forbids.
     *
     * What this codec CAN do — and what the store must not do for it — is be told the answer it
     * actually has. The row carries its identity in its own COLUMNS, so the refusal belongs to the
     * store, at [decodeStoredRow] / `SqliteEventStore.readRecord`. That is where an `Undecodable`
     * gets a real sequence instead of a guess.
     *
     * `DurableReadTruthFitnessTest` enforces the split mechanically: a `firstOrNull`/`mapNotNull`
     * over this method in the read side fails the build.
     */
    fun decode(payload: String): List<DomainEvent> {
        if (payload.isBlank() || payload == "[]") return emptyList()
        val events = mutableListOf<DomainEvent>()
        val eventStrings = splitArray(payload.substring(1, payload.length - 1))
        for (eventStr in eventStrings) {
            val trimmed = eventStr.trim()
            if (trimmed.isEmpty()) continue
            val event = decodeEvent(trimmed) ?: continue
            events.add(event)
        }
        return events
    }

    /**
     * Decodes ONE stored payload into a closed outcome, rather than a list that may be shorter than
     * one. P3-E E4c.
     *
     * [decode] answers "which events are in this document", and for a document assembled from
     * stdout that is the whole question. It is the WRONG shape for a durable row, where the caller's
     * unit is one row that the store has already identified and must answer for: an empty list there
     * has to become a refusal carrying that row's sequence, and expressing it as `List<DomainEvent>`
     * forces every caller to write `firstOrNull()` and decide — which is precisely the discarding the
     * read-side law forbids.
     *
     * So this is the codec's own statement of "the row did not decode", and it carries a REASON so
     * the store does not have to guess one by re-parsing. An unknown `kind` and a known kind with an
     * unsatisfiable payload are different facts and they stay different here.
     *
     * [rowKind] is the row's `kind` COLUMN, passed in rather than parsed from [payload], because
     * classification must not depend on the thing being classified.
     *
     * The null payload case is a refusal too, not an absence: the row exists, the column is the
     * only thing that could have held it, and it held nothing.
     */
    fun decodeStoredRow(payload: String?, rowKind: String?): StoredRowDecode = when {
        payload == null -> StoredRowDecode.Refused(
            dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload("payload column is null")
        )
        // The kind comes from the ROW COLUMN, and that ordering is the whole point of the parameter.
        //
        // The first version of this read the kind out of the payload, which makes the classification
        // wrong exactly when it matters: a row can be unreadable *because* of its payload, and then
        // the payload is the one thing that cannot be trusted to name the row. The column is written
        // by `bindInsert` from the event and is NOT NULL, so it is the authority that survives.
        rowKind == null || rowKind.isEmpty() -> StoredRowDecode.Refused(
            dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload("row carries no kind")
        )
        !knowsKind(rowKind) -> StoredRowDecode.Refused(
            dev.rubentxu.pipeline.v2.events.UndecodableReason.UnknownKind(rowKind)
        )
        else -> {
            val decoded = decode(payload).firstOrNull()
            if (decoded != null) StoredRowDecode.Accepted(decoded)
            else StoredRowDecode.Refused(
                dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload(
                    "payload does not satisfy the $rowKind schema: " +
                        payload.take(STORED_ROW_DETAIL_CHARS).let { "\"$it\"" }
                )
            )
        }
    }

    /** How much of a failing payload a refusal quotes. Bounded: these payloads can be GiB-scale. */
    private const val STORED_ROW_DETAIL_CHARS = 200

    /**
     * Does this binary have a decoder for [kind]? P3-E E4c.
     *
     * [decode] answers with events and cannot say WHY one is missing: an unknown `kind` and a known
     * `kind` with a bad payload both come back as "no event". A durable read that has to report a
     * refusal needs that distinction — "this runtime is older than the row" is version skew worth
     * surfacing upward, and "this runtime knows the row and cannot read it" is corruption — so it
     * cannot infer one from the other.
     *
     * It is also the answer to the question the `else -> null` arm of [decodeEvent] raises. That arm
     * is correct as a REJECTION: an unknown kind must not become a fabricated event. What it cannot
     * be, on its own, is silent, because the caller has no way to report it.
     *
     * The set is the closed vocabulary [decodeEvent] switches on, restated. Duplication rather than
     * reflection is deliberate: it is already the practice in this file, it is checked against the
     * `when` by `Rp030EventCodecsConnascenceFitnessTest`, and a reflective scan of a `when` over
     * string literals would be a compiler-plugin job wearing a fitness's clothes.
     */
    fun knowsKind(kind: String): Boolean = kind in KNOWN_KINDS

    /**
     * The wire discriminators this binary can decode. The single list both [knowsKind] and the
     * architecture fitness read, so "what can be read" has one answer rather than two that can
     * drift.
     */
    private val KNOWN_KINDS: Set<String> = setOf(
        "RunStarted", "CompilationStarted", "CompilationFinished", "RunFinished",
        "StageStarted", "StageFinished", "StepStarted", "StepFinished",
        "AgentResolved", "ExecutionTargetResolved",
        "ParallelBranchStarted", "ParallelBranchFinished",
        "RetryAttemptStarted", "RetryAttemptFinished",
        "TimeoutScheduled", "StepFailed", "EchoOutputCaptured",
        "CredentialBound", "CredentialUsed", "CredentialUnbound",
        "GitCheckoutStarted", "GitCheckoutCompleted", "GitCheckoutFailed", "GitPollChanged",
        "FileWritten", "FileRead", "FileExistsChecked",
        "ArtifactArchived", "ArtifactArchiveFailed",
        "StashCreated", "StashRestored", "StashFailed",
        "HtmlReportPublished", "HtmlReportSkipped", "HtmlReportFailed",
        "DirEntered", "DirExited", "DirDeleted", "WsCleaned",
        "CatchErrorTriggered", "DirectiveAdmitted", "DirectiveDenied",
        "GateEvaluated", "StageMarkedUnstable", "WorkflowLoaded",
        "WaitUntilPolled", "WaitUntilCompleted",
        "PwdResolved", "UnixDetected",
        "MilestoneReached", "MilestoneAborted",
        "LockRequested", "LockAcquired", "LockReleased", "LockSkipped", "LockAcquireFailed",
        "InputRequested", "InputProceed", "InputAborted", "InputDenied",
        "HttpRequestStarted", "HttpResponseReceived", "HttpStatusRejected", "HttpRequestFailed",
        "TimeoutTriggered", "TimestampsEntered", "TimestampsExited",
        "StepAdmissionObserved", "PostConditionSelected", "StageSkipped",
        "PluginEventEmitted",
    )

    /**
     * Splits a JSON array content into individual event strings.
     * Handles nested objects and strings correctly.
     */
    private fun splitArray(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        val current = StringBuilder()
        for (ch in s) {
            when {
                escape -> {
                    current.append(ch)
                    escape = false
                }
                ch == '\\' && inString -> {
                    current.append(ch)
                    escape = true
                }
                ch == '"' -> {
                    current.append(ch)
                    inString = !inString
                }
                ch == '{' && !inString -> { depth++; current.append(ch) }
                ch == '}' && !inString -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 && !inString -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        val trailing = current.toString().trimEnd()
        if (trailing.isNotEmpty() && trailing != "]") result.add(current.toString())
        return result
    }

    private fun decodeEvent(s: String): DomainEvent? {
        val eventId = EventJsonFields.stringField(s, "eventId") ?: return null
        val runId = EventJsonFields.stringField(s, "runId") ?: return null
        val sequence = EventJsonFields.longField(s, "sequence") ?: return null
        val kind = EventJsonFields.stringField(s, "kind") ?: return null
        val occurredAtStr = EventJsonFields.stringField(s, "occurredAt") ?: return null
        val occurredAt = try { Instant.parse(occurredAtStr) } catch (_: Exception) { Instant.now() }

        return when (kind) {
            "RunStarted" -> RunStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                scriptPath = EventJsonFields.stringField(s, "scriptPath") ?: "",
            )
            "CompilationStarted" -> CompilationStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
            )
            "CompilationFinished" -> {
                val cacheKey = EventJsonFields.parseCacheKey(s) ?: return null
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
            "RunFinished" -> {
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
            "StageStarted" -> StageStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stageName = EventJsonFields.stringField(s, "stageName") ?: "",
            )
            "StageFinished" -> StageFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stageName = EventJsonFields.stringField(s, "stageName") ?: "",
                outcome = EventJsonFields.stringField(s, "outcome") ?: "unknown",
            )
            "StepStarted" -> StepStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
                stepName = EventJsonFields.stringField(s, "stepName") ?: "",
                stepType = EventJsonFields.stringField(s, "stepType") ?: "",
            )
            "StepFinished" -> StepFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = EventJsonFields.intField(s, "stageIndex") ?: 0,
                stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
                stepName = EventJsonFields.stringField(s, "stepName") ?: "",
                stepType = EventJsonFields.stringField(s, "stepType") ?: "",
            )
            "AgentResolved" -> AgentResolved(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                agentLabel = EventJsonFields.stringField(s, "agentLabel") ?: "",
                remoteUri = EventJsonFields.optionalStringField(s, "remoteUri"),
            )
            "ExecutionTargetResolved" -> ExecutionTargetResolved(
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
            "ParallelBranchStarted" -> ParallelBranchStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                branchIndex = EventJsonFields.intField(s, "branchIndex") ?: 0,
                branchName = EventJsonFields.stringField(s, "branchName") ?: "",
                parentStageIndex = EventJsonFields.intField(s, "parentStageIndex") ?: 0,
            )
            "ParallelBranchFinished" -> ParallelBranchFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                branchIndex = EventJsonFields.intField(s, "branchIndex") ?: 0,
                branchName = EventJsonFields.stringField(s, "branchName") ?: "",
                parentStageIndex = EventJsonFields.intField(s, "parentStageIndex") ?: 0,
                outcome = EventJsonFields.stringField(s, "outcome") ?: "unknown",
            )
            "RetryAttemptStarted" -> RetryAttemptStarted(
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
            "RetryAttemptFinished" -> RetryAttemptFinished(
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
            "TimeoutScheduled" -> TimeoutScheduled(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                timeoutSeconds = EventJsonFields.longField(s, "timeoutSeconds") ?: 0L,
                timeoutAction = EventJsonFields.stringField(s, "timeoutAction") ?: "FAIL",
                stepName = EventJsonFields.optionalStringField(s, "stepName"),
                stepType = EventJsonFields.optionalStringField(s, "stepType"),
                stageIndex = EventJsonFields.intField(s, "stageIndex")?.takeIf { it != -1 },
                stepIndex = EventJsonFields.intField(s, "stepIndex")?.takeIf { it != -1 },
            )
            "StepFailed" -> {
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
                    stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
                    stepName = EventJsonFields.stringField(s, "stepName") ?: "",
                    stepType = EventJsonFields.stringField(s, "stepType") ?: "",
                    failureKind = failureKind,
                    message = EventJsonFields.stringField(s, "message") ?: "",
                )
            }
            "EchoOutputCaptured" -> EchoOutputCaptured(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stepIndex = EventJsonFields.intField(s, "stepIndex") ?: 0,
                content = EventJsonFields.stringField(s, "content") ?: "",
            )
            "CredentialBound" -> {
                val purposeStr = EventJsonFields.stringField(s, "purpose") ?: "API_KEY"
                val purpose = try { BoundPurpose.valueOf(purposeStr) } catch (_: Exception) { BoundPurpose.API_KEY }
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
            "CredentialUsed" -> {
                val purposeStr = EventJsonFields.stringField(s, "purpose") ?: "API_KEY"
                val purpose = try { BoundPurpose.valueOf(purposeStr) } catch (_: Exception) { BoundPurpose.API_KEY }
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
            "CredentialUnbound" -> {
                val credIdStr = EventJsonFields.stringField(s, "credentialsId") ?: ""
                CredentialUnbound(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    credentialsId = CredentialsId(credIdStr),
                )
            }
            // L5 SCM Events
            "GitCheckoutStarted" -> {
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
            "GitCheckoutCompleted" -> {
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
            "GitCheckoutFailed" -> {
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
            "GitPollChanged" -> {
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
            // L7 Jenkins File + Artefact Events (ML-R7)
            "FileWritten" -> {
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
            "FileRead" -> {
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
            "FileExistsChecked" -> {
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
            "ArtifactArchived" -> {
                val files = EventJsonDecoder.decodeArtifactEntries(s)
                ArtifactArchived(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    files = files,
                )
            }
            "ArtifactArchiveFailed" -> {
                val reason = EventJsonFields.stringField(s, "reason") ?: ""
                ArtifactArchiveFailed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    reason = reason,
                )
            }
            // WU-LPR-089 — core.stash/core.unstash durable cross-stage data movement
            "StashCreated" -> {
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val name = EventJsonFields.stringField(s, "name") ?: ""
                val files = EventJsonDecoder.decodeStashedEntries(s)
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
            "StashRestored" -> {
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                val name = EventJsonFields.stringField(s, "name") ?: ""
                val entries = EventJsonDecoder.decodeRestoredEntries(s)
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
            "StashFailed" -> {
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
            // WU-LPR-090 — core.publishHTML durable HTML report publishing
            "HtmlReportPublished" -> {
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
            "HtmlReportSkipped" -> {
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
            "HtmlReportFailed" -> {
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
            "DirectiveAdmitted" -> {
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
            "DirectiveDenied" -> {
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
            "GateEvaluated" -> {
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
            "StageSkipped" -> {
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
            "PostConditionSelected" -> {
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
            "DirEntered" -> {
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
            "DirExited" -> {
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
            "DirDeleted" -> {
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
            "WsCleaned" -> {
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
            "CatchErrorTriggered" -> {
                val stageName = EventJsonFields.stringField(s, "stageName") ?: ""
                // P3-E D3 — both results now cross the codec as a CLOSED vocabulary.
                // `buildResult` stays nullable (a declared absence is legitimate: the DSL's
                // default), but a PRESENT token outside the vocabulary is corruption and is
                // refused, exactly like the absent-and-required `stageResult` below.
                val buildResult = EventJsonFields.optionalStringField(s, "buildResult")
                    ?.let { CatchErrorBuildResult.parse(it) ?: return null }
                // P3-E E4 — was `?: "UNSTABLE"`. That default turned a MISSING field into a
                // semantic claim, and the claim was not neutral: UNSTABLE means the run
                // continues while FAILURE aborts it. A record whose stageResult could not be
                // read therefore came back describing a different run than the one that
                // happened. The writer ALWAYS emits this key (EventJsonWriter writes
                // `stageResult` unconditionally, and the compatibility corpus carries it in
                // 4/4 historical occurrences), so absence is corruption rather than a
                // version this runtime predates — and the key being present with a JSON null
                // is a real encoding, because the field is nullable.
                // `stageResult` is a NON-NULL String in the event, so a JSON null is not a
                // legitimate encoding of "not declared" — it is corruption, exactly like the
                // key being absent. One reader covers both, which is why no hasField probe
                // is needed here even though `buildResult` right above it IS nullable.
                val stageResult = EventJsonFields.stringField(s, "stageResult")
                    ?.let { CatchErrorBuildResult.parse(it) }
                    ?: return null
                val message = EventJsonFields.optionalStringField(s, "message")
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
            "StageMarkedUnstable" -> {
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
            "WorkflowLoaded" -> {
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
            "WaitUntilPolled" -> {
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
            "WaitUntilCompleted" -> {
                val totalAttempts = EventJsonFields.intField(s, "totalAttempts") ?: 0
                val totalDurationMs = EventJsonFields.longField(s, "totalDurationMs") ?: 0L
                // P3-E E4 — was `?: "completed"`. Same defect, opposite direction: a missing
                // outcome decoded as SATISFACTION. `completed` is the one value that means
                // the wait condition held, so the default reported success for a record that
                // said nothing at all. The field is non-null in the event and the writer
                // always emits it, so absence is malformed and the line stays unclassified.
                //
                // Vocabulary validation is deliberately NOT here: whether these three tokens
                // are one concept is the open E4b.4 question, and validating against an
                // undecided vocabulary would freeze it by accident.
                val outcome = EventJsonFields.stringField(s, "outcome") ?: return null
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
            "PwdResolved" -> {
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
            "UnixDetected" -> {
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
            "MilestoneReached" -> {
                val ordinal = EventJsonFields.intField(s, "ordinal") ?: 0
                val label = EventJsonFields.optionalStringField(s, "label")
                MilestoneReached(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    ordinal = ordinal,
                    label = label,
                )
            }
            "MilestoneAborted" -> {
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
            // RP6-A / WU-091 §6 lock events
            "LockRequested" -> {
                val resource = EventJsonFields.stringField(s, "resource") ?: ""
                val reason = EventJsonFields.optionalStringField(s, "reason")
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
            "LockAcquired" -> LockAcquired(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                resource = EventJsonFields.stringField(s, "resource") ?: "",
            )
            "LockReleased" -> LockReleased(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                resource = EventJsonFields.stringField(s, "resource") ?: "",
            )
            "LockSkipped" -> {
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
            "LockAcquireFailed" -> {
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
            // RP6-C / WU-093 §5 httpRequest events
            "HttpRequestStarted" -> {
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
            "HttpResponseReceived" -> {
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
            "HttpStatusRejected" -> {
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
            "HttpRequestFailed" -> {
                HttpRequestFailed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    url = EventJsonFields.stringField(s, "url") ?: "",
                    reason = EventJsonFields.stringField(s, "reason") ?: "",
                )
            }
            // RP6-B / WU-092 §6 input events
            "InputRequested" -> {
                val message = EventJsonFields.stringField(s, "message") ?: ""
                val submitter = EventJsonFields.optionalStringField(s, "submitter")
                val id = EventJsonFields.optionalStringField(s, "id")
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
            "InputProceed" -> {
                val submitter = EventJsonFields.optionalStringField(s, "submitter")
                val message = EventJsonFields.optionalStringField(s, "message")
                InputProceed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    submitter = submitter,
                    message = message,
                )
            }
            "InputAborted" -> {
                val submitter = EventJsonFields.optionalStringField(s, "submitter")
                val message = EventJsonFields.optionalStringField(s, "message")
                InputAborted(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    submitter = submitter,
                    message = message,
                )
            }
            "InputDenied" -> InputDenied(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                reason = EventJsonFields.stringField(s, "reason") ?: "",
            )
            "TimeoutTriggered" -> {
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
            // WU-RP-030 — connascence fix: these three variants were encodable
            // but NOT decodable (decodeEvent returned null), silently dropping
            // them from every replay/observation surface that reads persisted
            // history through JsonEventLog.
            "TimestampsEntered" -> TimestampsEntered(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
            )
            "TimestampsExited" -> TimestampsExited(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
            )
            "StepAdmissionObserved" -> {
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
            // P3 slice 2 — the plugin carrier. Written by EventJsonWriter as
            // registryKind/schemaVersion/payload/emittedBy.
            //
            // The two identity fields are NOT defaulted the way the fields above are, and the
            // difference is deliberate. A StageSkipped with no `reason` is still the StageSkipped
            // that happened. A PluginEventEmitted with no `registryKind` or no `schemaVersion` is
            // not a degraded carrier — it is an unidentifiable one: any substituted value would
            // re-type the payload as SOME OTHER plugin's event, which is a wrong observation
            // rather than a missing one. So a malformed carrier line decodes to null here, the
            // same as a line this function cannot classify at all, and the registry refuses it
            // again on the read-back side. Two fail-closed hops beat one confident fabrication.
            "PluginEventEmitted" -> {
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
            else -> null
        }
    }
}
