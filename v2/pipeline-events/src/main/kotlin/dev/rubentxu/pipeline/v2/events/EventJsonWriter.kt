package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic

/**
 * The JSON writers shared by [JsonEventLog]'s encoder and by
 * [EventJsonDecoder]'s nested serializers.
 */
internal object EventJsonWriter {

    fun serializeStashedEntries(entries: List<StashedEntry>): String {
        val sb = StringBuilder("[")
        entries.forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append("{")
            sb.append("\"relPath\":").append(jsonString(e.relPath))
            sb.append(",\"sha256\":").append(jsonString(e.sha256))
            sb.append(",\"sizeBytes\":").append(e.sizeBytes)
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    fun serializeRestoredEntries(entries: List<RestoredEntry>): String {
        val sb = StringBuilder("[")
        entries.forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append("{")
            sb.append("\"relPath\":").append(jsonString(e.relPath))
            sb.append(",\"sha256\":").append(jsonString(e.sha256))
            sb.append(",\"sizeBytes\":").append(e.sizeBytes)
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    // WU-LPR-089 — Stash decoders (inverse of the serializers above).
    // The `files`/`entries` array is a compact JSON list of objects; we
    // delegate to the existing stringField/longField helpers plus
    fun serializeHtmlReportEntries(entries: List<HtmlReportEntry>): String {
        val sb = StringBuilder("[")
        entries.forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append("{")
            sb.append("\"relPath\":").append(jsonString(e.relPath))
            sb.append(",\"sha256\":").append(jsonString(e.sha256))
            sb.append(",\"sizeBytes\":").append(e.sizeBytes)
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }
    fun jsonString(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return "\"${sb}\""
    }

    fun encodeEvent(event: DomainEvent): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"eventId\":")
        sb.append(EventJsonWriter.jsonString(event.eventId))
        sb.append(",\"runId\":")
        sb.append(EventJsonWriter.jsonString(event.runId))
        sb.append(",\"sequence\":")
        sb.append(event.sequence)
        sb.append(",\"kind\":")
        sb.append(EventJsonWriter.jsonString(event.kind))
        sb.append(",\"occurredAt\":")
        sb.append(EventJsonWriter.jsonString(event.occurredAt.toString()))
        when (event) {
            is RunStarted -> {
                sb.append(",\"scriptPath\":")
                sb.append(EventJsonWriter.jsonString(event.scriptPath))
            }
            is CompilationStarted -> {
                // no extra fields
            }
            is CompilationFinished -> {
                sb.append(",\"cacheKey\":")
                sb.append(encodeCacheKey(event.cacheKey))
                sb.append(",\"diagnostics\":")
                sb.append(encodeDiagnostics(event.diagnostics))
            }
            is RunFinished -> {
                sb.append(",\"outcome\":")
                sb.append(EventJsonWriter.jsonString(event.outcome))
                sb.append(",\"diagnostics\":")
                sb.append(encodeDiagnostics(event.diagnostics))
            }
            is StageStarted -> {
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stageName\":")
                sb.append(EventJsonWriter.jsonString(event.stageName))
            }
            is StageFinished -> {
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stageName\":")
                sb.append(EventJsonWriter.jsonString(event.stageName))
                sb.append(",\"outcome\":")
                sb.append(EventJsonWriter.jsonString(event.outcome))
            }
            is StepStarted -> {
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"stepName\":")
                sb.append(EventJsonWriter.jsonString(event.stepName))
                sb.append(",\"stepType\":")
                sb.append(EventJsonWriter.jsonString(event.stepType))
            }
            is StepFinished -> {
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"stepName\":")
                sb.append(EventJsonWriter.jsonString(event.stepName))
                sb.append(",\"stepType\":")
                sb.append(EventJsonWriter.jsonString(event.stepType))
            }
            is AgentResolved -> {
                sb.append(",\"agentLabel\":")
                sb.append(EventJsonWriter.jsonString(event.agentLabel))
                sb.append(",\"remoteUri\":")
                sb.append(EventJsonWriter.jsonString(event.remoteUri ?: ""))
            }
            is ParallelBranchStarted -> {
                sb.append(",\"branchIndex\":")
                sb.append(event.branchIndex)
                sb.append(",\"branchName\":")
                sb.append(EventJsonWriter.jsonString(event.branchName))
                sb.append(",\"parentStageIndex\":")
                sb.append(event.parentStageIndex)
            }
            is ParallelBranchFinished -> {
                sb.append(",\"branchIndex\":")
                sb.append(event.branchIndex)
                sb.append(",\"branchName\":")
                sb.append(EventJsonWriter.jsonString(event.branchName))
                sb.append(",\"parentStageIndex\":")
                sb.append(event.parentStageIndex)
                sb.append(",\"outcome\":")
                sb.append(EventJsonWriter.jsonString(event.outcome))
            }
            is RetryAttemptStarted -> {
                sb.append(",\"attemptNumber\":")
                sb.append(event.attemptNumber)
                sb.append(",\"maxAttempts\":")
                sb.append(event.maxAttempts)
                sb.append(",\"stepName\":")
                sb.append(EventJsonWriter.jsonString(event.stepName))
                sb.append(",\"stepType\":")
                sb.append(EventJsonWriter.jsonString(event.stepType))
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
            }
            is RetryAttemptFinished -> {
                sb.append(",\"attemptNumber\":")
                sb.append(event.attemptNumber)
                sb.append(",\"maxAttempts\":")
                sb.append(event.maxAttempts)
                sb.append(",\"stepName\":")
                sb.append(EventJsonWriter.jsonString(event.stepName))
                sb.append(",\"stepType\":")
                sb.append(EventJsonWriter.jsonString(event.stepType))
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"outcome\":")
                sb.append(EventJsonWriter.jsonString(event.outcome))
            }
            is TimeoutScheduled -> {
                sb.append(",\"timeoutSeconds\":")
                sb.append(event.timeoutSeconds)
                sb.append(",\"timeoutAction\":")
                sb.append(EventJsonWriter.jsonString(event.timeoutAction))
                sb.append(",\"stepName\":")
                sb.append(EventJsonWriter.jsonString(event.stepName ?: ""))
                sb.append(",\"stepType\":")
                sb.append(EventJsonWriter.jsonString(event.stepType ?: ""))
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex ?: -1)
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex ?: -1)
            }
            is StepFailed -> {
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"stepName\":")
                sb.append(EventJsonWriter.jsonString(event.stepName))
                sb.append(",\"stepType\":")
                sb.append(EventJsonWriter.jsonString(event.stepType))
                sb.append(",\"failureKind\":")
                sb.append(EventJsonWriter.jsonString(event.failureKind.name))
                sb.append(",\"message\":")
                sb.append(EventJsonWriter.jsonString(event.message))
            }
            is EchoOutputCaptured -> {
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"content\":")
                sb.append(EventJsonWriter.jsonString(event.content))
            }
            is CredentialBound -> {
                sb.append(",\"credentialsId\":")
                sb.append(EventJsonWriter.jsonString(event.credentialsId.value))
                sb.append(",\"purpose\":")
                sb.append(EventJsonWriter.jsonString(event.purpose.name))
            }
            is CredentialUsed -> {
                sb.append(",\"credentialsId\":")
                sb.append(EventJsonWriter.jsonString(event.credentialsId.value))
                sb.append(",\"purpose\":")
                sb.append(EventJsonWriter.jsonString(event.purpose.name))
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
            }
            is CredentialUnbound -> {
                sb.append(",\"credentialsId\":")
                sb.append(EventJsonWriter.jsonString(event.credentialsId.value))
            }
            // L5 SCM Events
            is GitCheckoutStarted -> {
                sb.append(",\"url\":")
                sb.append(EventJsonWriter.jsonString(event.url))
                sb.append(",\"branch\":")
                sb.append(EventJsonWriter.jsonString(event.branch))
                if (event.credentialsRef != null) {
                    sb.append(",\"credentialsRef\":")
                    sb.append(EventJsonWriter.jsonString(event.credentialsRef.id.value))
                }
            }
            is GitCheckoutCompleted -> {
                sb.append(",\"url\":")
                sb.append(EventJsonWriter.jsonString(event.url))
                sb.append(",\"branch\":")
                sb.append(EventJsonWriter.jsonString(event.branch))
                sb.append(",\"sha\":")
                sb.append(EventJsonWriter.jsonString(event.sha))
                sb.append(",\"changelogPath\":")
                sb.append(EventJsonWriter.jsonString(event.changelogPath))
                sb.append(",\"durationMs\":")
                sb.append(event.durationMs)
            }
            is GitCheckoutFailed -> {
                sb.append(",\"url\":")
                sb.append(EventJsonWriter.jsonString(event.url))
                sb.append(",\"branch\":")
                sb.append(EventJsonWriter.jsonString(event.branch))
                sb.append(",\"reason\":")
                sb.append(EventJsonWriter.jsonString(event.reason))
                sb.append(",\"exitCode\":")
                sb.append(event.exitCode)
            }
            is GitPollChanged -> {
                sb.append(",\"url\":")
                sb.append(EventJsonWriter.jsonString(event.url))
                sb.append(",\"branch\":")
                sb.append(EventJsonWriter.jsonString(event.branch))
                if (event.previousSha != null) {
                    sb.append(",\"previousSha\":")
                    sb.append(EventJsonWriter.jsonString(event.previousSha))
                }
                sb.append(",\"newSha\":")
                sb.append(EventJsonWriter.jsonString(event.newSha))
            }
            // L7 Jenkins File + Artefact Events (ML-R7)
            is FileWritten -> {
                sb.append(",\"path\":")
                sb.append(EventJsonWriter.jsonString(event.path.toString()))
                sb.append(",\"sha256\":")
                sb.append(EventJsonWriter.jsonString(event.sha256))
                sb.append(",\"size\":")
                sb.append(event.size)
                sb.append(",\"atomicallyMoved\":")
                sb.append(event.atomicallyMoved.toString())
            }
            is FileRead -> {
                sb.append(",\"path\":")
                sb.append(EventJsonWriter.jsonString(event.path.toString()))
                if (event.sha256 != null) {
                    sb.append(",\"sha256\":")
                    sb.append(EventJsonWriter.jsonString(event.sha256))
                }
                if (event.size != null) {
                    sb.append(",\"size\":")
                    sb.append(event.size)
                }
            }
            is FileExistsChecked -> {
                sb.append(",\"path\":")
                sb.append(EventJsonWriter.jsonString(event.path.toString()))
                sb.append(",\"exists\":")
                sb.append(event.exists)
            }
            is ArtifactArchived -> {
                sb.append(",\"files\":[")
                event.files.forEachIndexed { idx, entry ->
                    if (idx > 0) sb.append(",")
                    sb.append("{")
                    sb.append("\"runId\":")
                    sb.append(EventJsonWriter.jsonString(entry.runId))
                    sb.append(",\"stageName\":")
                    sb.append(EventJsonWriter.jsonString(entry.stageName))
                    sb.append(",\"relPath\":")
                    sb.append(EventJsonWriter.jsonString(entry.relPath))
                    sb.append(",\"sha256\":")
                    sb.append(EventJsonWriter.jsonString(entry.sha256))
                    sb.append(",\"size\":")
                    sb.append(entry.size)
                    sb.append(",\"archivedAt\":")
                    sb.append(EventJsonWriter.jsonString(entry.archivedAt.toString()))
                    sb.append("}")
                }
                sb.append("]")
            }
            is ArtifactArchiveFailed -> {
                sb.append(",\"reason\":")
                sb.append(EventJsonWriter.jsonString(event.reason))
            }
            // WU-LPR-089 — core.stash/core.unstash durable cross-stage data movement
            is StashCreated -> {
                sb.append(",\"stageName\":")
                sb.append(EventJsonWriter.jsonString(event.stageName))
                sb.append(",\"name\":")
                sb.append(EventJsonWriter.jsonString(event.name))
                sb.append(",\"files\":")
                sb.append(EventJsonWriter.serializeStashedEntries(event.files))
            }
            is StashRestored -> {
                sb.append(",\"stageName\":")
                sb.append(EventJsonWriter.jsonString(event.stageName))
                sb.append(",\"name\":")
                sb.append(EventJsonWriter.jsonString(event.name))
                sb.append(",\"entries\":")
                sb.append(EventJsonWriter.serializeRestoredEntries(event.entries))
            }
            is StashFailed -> {
                sb.append(",\"stageName\":")
                sb.append(EventJsonWriter.jsonString(event.stageName))
                sb.append(",\"name\":")
                sb.append(EventJsonWriter.jsonString(event.name))
                sb.append(",\"operation\":")
                sb.append(EventJsonWriter.jsonString(event.operation))
                sb.append(",\"reason\":")
                sb.append(EventJsonWriter.jsonString(event.reason))
            }
            // WU-LPR-090 — core.publishHTML durable HTML report publishing
            is HtmlReportPublished -> {
                sb.append(",\"stageName\":")
                sb.append(EventJsonWriter.jsonString(event.stageName))
                sb.append(",\"reportName\":")
                sb.append(EventJsonWriter.jsonString(event.reportName))
                sb.append(",\"reportDir\":")
                sb.append(EventJsonWriter.jsonString(event.reportDir))
                sb.append(",\"entries\":")
                sb.append(EventJsonWriter.serializeHtmlReportEntries(event.entries))
                sb.append(",\"targetPath\":")
                sb.append(EventJsonWriter.jsonString(event.targetPath))
            }
            is HtmlReportSkipped -> {
                sb.append(",\"stageName\":")
                sb.append(EventJsonWriter.jsonString(event.stageName))
                sb.append(",\"reportName\":")
                sb.append(EventJsonWriter.jsonString(event.reportName))
                sb.append(",\"reportDir\":")
                sb.append(EventJsonWriter.jsonString(event.reportDir))
                sb.append(",\"reason\":")
                sb.append(EventJsonWriter.jsonString(event.reason))
            }
            is HtmlReportFailed -> {
                sb.append(",\"stageName\":")
                sb.append(EventJsonWriter.jsonString(event.stageName))
                sb.append(",\"reportName\":")
                sb.append(EventJsonWriter.jsonString(event.reportName))
                sb.append(",\"reportDir\":")
                sb.append(EventJsonWriter.jsonString(event.reportDir))
                sb.append(",\"failureKind\":")
                sb.append(EventJsonWriter.jsonString(event.failureKind.name))
                sb.append(",\"reason\":")
                sb.append(EventJsonWriter.jsonString(event.reason))
            }
            is DirEntered -> {
                sb.append(",\"path\":")
                sb.append(EventJsonWriter.jsonString(event.path))
                sb.append(",\"previousPath\":")
                sb.append(EventJsonWriter.jsonString(event.previousPath))
            }
            is DirExited -> {
                sb.append(",\"path\":")
                sb.append(EventJsonWriter.jsonString(event.path))
                sb.append(",\"restoredTo\":")
                sb.append(EventJsonWriter.jsonString(event.restoredTo))
            }
            is DirDeleted -> {
                sb.append(",\"path\":")
                sb.append(EventJsonWriter.jsonString(event.path))
                sb.append(",\"deletedCount\":")
                sb.append(event.deletedCount)
                sb.append(",\"sha256\":")
                sb.append(EventJsonWriter.jsonString(event.sha256))
            }
            is WsCleaned -> {
                sb.append(",\"deletedFiles\":")
                sb.append(event.deletedFiles)
                sb.append(",\"deletedDirs\":")
                sb.append(event.deletedDirs)
                sb.append(",\"patterns\":[")
                event.patterns.forEachIndexed { idx, pattern ->
                    if (idx > 0) sb.append(",")
                    sb.append(EventJsonWriter.jsonString(pattern))
                }
                sb.append("]")
                sb.append(",\"sha256\":")
                sb.append(EventJsonWriter.jsonString(event.sha256))
            }
            is CatchErrorTriggered -> {
                sb.append(",\"stageName\":")
                sb.append(EventJsonWriter.jsonString(event.stageName))
                sb.append(",\"buildResult\":")
                sb.append(EventJsonWriter.jsonString(event.buildResult ?: ""))
                sb.append(",\"stageResult\":")
                sb.append(EventJsonWriter.jsonString(event.stageResult))
                sb.append(",\"message\":")
                sb.append(EventJsonWriter.jsonString(event.message ?: ""))
            }
            is StageMarkedUnstable -> {
                sb.append(",\"stageName\":")
                sb.append(EventJsonWriter.jsonString(event.stageName))
                sb.append(",\"message\":")
                sb.append(EventJsonWriter.jsonString(event.message))
            }
            is WorkflowLoaded -> {
                sb.append(",\"path\":")
                sb.append(EventJsonWriter.jsonString(event.path))
                sb.append(",\"stepCount\":")
                sb.append(event.stepCount)
                sb.append(",\"sha256\":")
                sb.append(EventJsonWriter.jsonString(event.sha256))
            }
            is WaitUntilPolled -> {
                sb.append(",\"attempt\":")
                sb.append(event.attempt)
                sb.append(",\"durationMs\":")
                sb.append(event.durationMs)
                sb.append(",\"conditionResult\":")
                sb.append(event.conditionResult)
            }
            is WaitUntilCompleted -> {
                sb.append(",\"totalAttempts\":")
                sb.append(event.totalAttempts)
                sb.append(",\"totalDurationMs\":")
                sb.append(event.totalDurationMs)
                sb.append(",\"outcome\":")
                sb.append(EventJsonWriter.jsonString(event.outcome))
            }
            is PwdResolved -> {
                sb.append(",\"path\":")
                sb.append(EventJsonWriter.jsonString(event.path))
                sb.append(",\"workspaceRoot\":")
                sb.append(EventJsonWriter.jsonString(event.workspaceRoot))
                sb.append(",\"sha256\":")
                sb.append(EventJsonWriter.jsonString(event.sha256))
            }
            is UnixDetected -> {
                sb.append(",\"isUnix\":")
                sb.append(event.isUnix)
                sb.append(",\"osName\":")
                sb.append(EventJsonWriter.jsonString(event.osName))
                sb.append(",\"sha256\":")
                sb.append(EventJsonWriter.jsonString(event.sha256))
            }
            is MilestoneReached -> {
                sb.append(",\"ordinal\":")
                sb.append(event.ordinal)
                sb.append(",\"label\":")
                sb.append(EventJsonWriter.jsonString(event.label ?: ""))
            }
            is MilestoneAborted -> {
                sb.append(",\"ordinal\":")
                sb.append(event.ordinal)
                sb.append(",\"reason\":")
                sb.append(EventJsonWriter.jsonString(event.reason))
            }
            is TimeoutTriggered -> {
                sb.append(",\"stageOrStep\":")
                sb.append(EventJsonWriter.jsonString(event.stageOrStep))
                sb.append(",\"action\":")
                sb.append(EventJsonWriter.jsonString(event.action))
                sb.append(",\"durationMs\":")
                sb.append(event.durationMs)
            }
            // ML-R9 T-08 timestamps decorator events
            is TimestampsEntered -> {
                // no extra fields
            }
            is TimestampsExited -> {
                // no extra fields
            }
            // S2.5.7 / B1.2c3 — LB-01 durable-spine admission observation (WU-1)
            is StepAdmissionObserved -> {
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"stepKey\":")
                sb.append(EventJsonWriter.jsonString(event.stepKey))
                sb.append(",\"law\":")
                sb.append(EventJsonWriter.jsonString(event.law))
                sb.append(",\"executorCalls\":")
                sb.append(event.executorCalls)
            }
        }
        sb.append("}")
        return sb.toString()
    }

    fun encodeCacheKey(ck: CacheKey): String {
        return "{\"value\":\"" + ck.value + "\",\"version\":\"" + ck.version + "\"}"
    }

    fun encodeDiagnostics(diagnostics: List<ScriptingDiagnostic>): String {
        val sb = StringBuilder("[")
        diagnostics.forEachIndexed { index, diag ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append("\"severity\":")
            sb.append(EventJsonWriter.jsonString(diag.severity.name))
            sb.append(",\"message\":")
            sb.append(EventJsonWriter.jsonString(diag.message))
            sb.append(",\"line\":")
            sb.append(diag.line)
            sb.append(",\"column\":")
            sb.append(diag.column)
            sb.append(",\"path\":")
            sb.append(EventJsonWriter.jsonString(diag.path))
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }
}
