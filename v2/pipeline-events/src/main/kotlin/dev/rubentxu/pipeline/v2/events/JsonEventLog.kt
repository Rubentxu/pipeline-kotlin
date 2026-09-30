package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.CredentialsRef
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
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
                remoteUri = EventJsonFields.stringField(s, "remoteUri")?.takeIf { it.isNotEmpty() },
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
                stepName = EventJsonFields.stringField(s, "stepName")?.takeIf { it.isNotEmpty() },
                stepType = EventJsonFields.stringField(s, "stepType")?.takeIf { it.isNotEmpty() },
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
                val buildResult = EventJsonFields.stringField(s, "buildResult")?.takeIf { it.isNotEmpty() }
                val stageResult = EventJsonFields.stringField(s, "stageResult") ?: "UNSTABLE"
                val message = EventJsonFields.stringField(s, "message")?.takeIf { it.isNotEmpty() }
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
                val outcome = EventJsonFields.stringField(s, "outcome") ?: "completed"
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
                val label = EventJsonFields.stringField(s, "label")?.takeIf { it.isNotEmpty() }
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
            else -> null
        }
    }
}
