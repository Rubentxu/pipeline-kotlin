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
            sb.append(encodeEvent(event))
        }
        sb.append("]")
        return sb.toString()
    }

    private fun encodeEvent(event: DomainEvent): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"eventId\":")
        sb.append(jsonString(event.eventId))
        sb.append(",\"runId\":")
        sb.append(jsonString(event.runId))
        sb.append(",\"sequence\":")
        sb.append(event.sequence)
        sb.append(",\"kind\":")
        sb.append(jsonString(event.kind))
        sb.append(",\"occurredAt\":")
        sb.append(jsonString(event.occurredAt.toString()))
        when (event) {
            is RunStarted -> {
                sb.append(",\"scriptPath\":")
                sb.append(jsonString(event.scriptPath))
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
                sb.append(jsonString(event.outcome))
                sb.append(",\"diagnostics\":")
                sb.append(encodeDiagnostics(event.diagnostics))
            }
            is StageStarted -> {
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stageName\":")
                sb.append(jsonString(event.stageName))
            }
            is StageFinished -> {
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stageName\":")
                sb.append(jsonString(event.stageName))
                sb.append(",\"outcome\":")
                sb.append(jsonString(event.outcome))
            }
            is StepStarted -> {
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"stepName\":")
                sb.append(jsonString(event.stepName))
                sb.append(",\"stepType\":")
                sb.append(jsonString(event.stepType))
            }
            is StepFinished -> {
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"stepName\":")
                sb.append(jsonString(event.stepName))
                sb.append(",\"stepType\":")
                sb.append(jsonString(event.stepType))
            }
            is AgentResolved -> {
                sb.append(",\"agentLabel\":")
                sb.append(jsonString(event.agentLabel))
                sb.append(",\"remoteUri\":")
                sb.append(jsonString(event.remoteUri ?: ""))
            }
            is ParallelBranchStarted -> {
                sb.append(",\"branchIndex\":")
                sb.append(event.branchIndex)
                sb.append(",\"branchName\":")
                sb.append(jsonString(event.branchName))
                sb.append(",\"parentStageIndex\":")
                sb.append(event.parentStageIndex)
            }
            is ParallelBranchFinished -> {
                sb.append(",\"branchIndex\":")
                sb.append(event.branchIndex)
                sb.append(",\"branchName\":")
                sb.append(jsonString(event.branchName))
                sb.append(",\"parentStageIndex\":")
                sb.append(event.parentStageIndex)
                sb.append(",\"outcome\":")
                sb.append(jsonString(event.outcome))
            }
            is RetryAttemptStarted -> {
                sb.append(",\"attemptNumber\":")
                sb.append(event.attemptNumber)
                sb.append(",\"maxAttempts\":")
                sb.append(event.maxAttempts)
                sb.append(",\"stepName\":")
                sb.append(jsonString(event.stepName))
                sb.append(",\"stepType\":")
                sb.append(jsonString(event.stepType))
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
                sb.append(jsonString(event.stepName))
                sb.append(",\"stepType\":")
                sb.append(jsonString(event.stepType))
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex)
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"outcome\":")
                sb.append(jsonString(event.outcome))
            }
            is TimeoutScheduled -> {
                sb.append(",\"timeoutSeconds\":")
                sb.append(event.timeoutSeconds)
                sb.append(",\"timeoutAction\":")
                sb.append(jsonString(event.timeoutAction))
                sb.append(",\"stepName\":")
                sb.append(jsonString(event.stepName ?: ""))
                sb.append(",\"stepType\":")
                sb.append(jsonString(event.stepType ?: ""))
                sb.append(",\"stageIndex\":")
                sb.append(event.stageIndex ?: -1)
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex ?: -1)
            }
            is StepFailed -> {
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"stepName\":")
                sb.append(jsonString(event.stepName))
                sb.append(",\"stepType\":")
                sb.append(jsonString(event.stepType))
                sb.append(",\"failureKind\":")
                sb.append(jsonString(event.failureKind.name))
                sb.append(",\"message\":")
                sb.append(jsonString(event.message))
            }
            is EchoOutputCaptured -> {
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
                sb.append(",\"content\":")
                sb.append(jsonString(event.content))
            }
            is CredentialBound -> {
                sb.append(",\"credentialsId\":")
                sb.append(jsonString(event.credentialsId.value))
                sb.append(",\"purpose\":")
                sb.append(jsonString(event.purpose.name))
            }
            is CredentialUsed -> {
                sb.append(",\"credentialsId\":")
                sb.append(jsonString(event.credentialsId.value))
                sb.append(",\"purpose\":")
                sb.append(jsonString(event.purpose.name))
                sb.append(",\"stepIndex\":")
                sb.append(event.stepIndex)
            }
            is CredentialUnbound -> {
                sb.append(",\"credentialsId\":")
                sb.append(jsonString(event.credentialsId.value))
            }
            // L5 SCM Events
            is GitCheckoutStarted -> {
                sb.append(",\"url\":")
                sb.append(jsonString(event.url))
                sb.append(",\"branch\":")
                sb.append(jsonString(event.branch))
                if (event.credentialsRef != null) {
                    sb.append(",\"credentialsRef\":")
                    sb.append(jsonString(event.credentialsRef.id.value))
                }
            }
            is GitCheckoutCompleted -> {
                sb.append(",\"url\":")
                sb.append(jsonString(event.url))
                sb.append(",\"branch\":")
                sb.append(jsonString(event.branch))
                sb.append(",\"sha\":")
                sb.append(jsonString(event.sha))
                sb.append(",\"changelogPath\":")
                sb.append(jsonString(event.changelogPath))
                sb.append(",\"durationMs\":")
                sb.append(event.durationMs)
            }
            is GitCheckoutFailed -> {
                sb.append(",\"url\":")
                sb.append(jsonString(event.url))
                sb.append(",\"branch\":")
                sb.append(jsonString(event.branch))
                sb.append(",\"reason\":")
                sb.append(jsonString(event.reason))
                sb.append(",\"exitCode\":")
                sb.append(event.exitCode)
            }
            is GitPollChanged -> {
                sb.append(",\"url\":")
                sb.append(jsonString(event.url))
                sb.append(",\"branch\":")
                sb.append(jsonString(event.branch))
                if (event.previousSha != null) {
                    sb.append(",\"previousSha\":")
                    sb.append(jsonString(event.previousSha))
                }
                sb.append(",\"newSha\":")
                sb.append(jsonString(event.newSha))
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun encodeCacheKey(ck: CacheKey): String {
        return "{\"value\":\"" + ck.value + "\",\"version\":\"" + ck.version + "\"}"
    }

    private fun encodeDiagnostics(diagnostics: List<ScriptingDiagnostic>): String {
        val sb = StringBuilder("[")
        diagnostics.forEachIndexed { index, diag ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append("\"severity\":")
            sb.append(jsonString(diag.severity.name))
            sb.append(",\"message\":")
            sb.append(jsonString(diag.message))
            sb.append(",\"line\":")
            sb.append(diag.line)
            sb.append(",\"column\":")
            sb.append(diag.column)
            sb.append(",\"path\":")
            sb.append(jsonString(diag.path))
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun jsonString(s: String): String {
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
        val eventId = stringField(s, "eventId") ?: return null
        val runId = stringField(s, "runId") ?: return null
        val sequence = longField(s, "sequence") ?: return null
        val kind = stringField(s, "kind") ?: return null
        val occurredAtStr = stringField(s, "occurredAt") ?: return null
        val occurredAt = try { Instant.parse(occurredAtStr) } catch (_: Exception) { Instant.now() }

        return when (kind) {
            "RunStarted" -> RunStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                scriptPath = stringField(s, "scriptPath") ?: "",
            )
            "CompilationStarted" -> CompilationStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
            )
            "CompilationFinished" -> {
                val cacheKey = parseCacheKey(s) ?: return null
                val diagnostics = decodeDiagnostics(s)
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
                val outcome = stringField(s, "outcome") ?: "unknown"
                val diagnostics = decodeDiagnostics(s)
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
                stageIndex = intField(s, "stageIndex") ?: 0,
                stageName = stringField(s, "stageName") ?: "",
            )
            "StageFinished" -> StageFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = intField(s, "stageIndex") ?: 0,
                stageName = stringField(s, "stageName") ?: "",
                outcome = stringField(s, "outcome") ?: "unknown",
            )
            "StepStarted" -> StepStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = intField(s, "stageIndex") ?: 0,
                stepIndex = intField(s, "stepIndex") ?: 0,
                stepName = stringField(s, "stepName") ?: "",
                stepType = stringField(s, "stepType") ?: "",
            )
            "StepFinished" -> StepFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stageIndex = intField(s, "stageIndex") ?: 0,
                stepIndex = intField(s, "stepIndex") ?: 0,
                stepName = stringField(s, "stepName") ?: "",
                stepType = stringField(s, "stepType") ?: "",
            )
            "AgentResolved" -> AgentResolved(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                agentLabel = stringField(s, "agentLabel") ?: "",
                remoteUri = stringField(s, "remoteUri")?.takeIf { it.isNotEmpty() },
            )
            "ParallelBranchStarted" -> ParallelBranchStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                branchIndex = intField(s, "branchIndex") ?: 0,
                branchName = stringField(s, "branchName") ?: "",
                parentStageIndex = intField(s, "parentStageIndex") ?: 0,
            )
            "ParallelBranchFinished" -> ParallelBranchFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                branchIndex = intField(s, "branchIndex") ?: 0,
                branchName = stringField(s, "branchName") ?: "",
                parentStageIndex = intField(s, "parentStageIndex") ?: 0,
                outcome = stringField(s, "outcome") ?: "unknown",
            )
            "RetryAttemptStarted" -> RetryAttemptStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                attemptNumber = intField(s, "attemptNumber") ?: 1,
                maxAttempts = intField(s, "maxAttempts") ?: 1,
                stepName = stringField(s, "stepName") ?: "",
                stepType = stringField(s, "stepType") ?: "",
                stageIndex = intField(s, "stageIndex") ?: 0,
                stepIndex = intField(s, "stepIndex") ?: 0,
            )
            "RetryAttemptFinished" -> RetryAttemptFinished(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                attemptNumber = intField(s, "attemptNumber") ?: 1,
                maxAttempts = intField(s, "maxAttempts") ?: 1,
                stepName = stringField(s, "stepName") ?: "",
                stepType = stringField(s, "stepType") ?: "",
                stageIndex = intField(s, "stageIndex") ?: 0,
                stepIndex = intField(s, "stepIndex") ?: 0,
                outcome = stringField(s, "outcome") ?: "unknown",
            )
            "TimeoutScheduled" -> TimeoutScheduled(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                timeoutSeconds = longField(s, "timeoutSeconds") ?: 0L,
                timeoutAction = stringField(s, "timeoutAction") ?: "FAIL",
                stepName = stringField(s, "stepName")?.takeIf { it.isNotEmpty() },
                stepType = stringField(s, "stepType")?.takeIf { it.isNotEmpty() },
                stageIndex = intField(s, "stageIndex")?.takeIf { it != -1 },
                stepIndex = intField(s, "stepIndex")?.takeIf { it != -1 },
            )
            "StepFailed" -> {
                val failureKindStr = stringField(s, "failureKind") ?: "UNKNOWN"
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
                    stepIndex = intField(s, "stepIndex") ?: 0,
                    stepName = stringField(s, "stepName") ?: "",
                    stepType = stringField(s, "stepType") ?: "",
                    failureKind = failureKind,
                    message = stringField(s, "message") ?: "",
                )
            }
            "EchoOutputCaptured" -> EchoOutputCaptured(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                stepIndex = intField(s, "stepIndex") ?: 0,
                content = stringField(s, "content") ?: "",
            )
            "CredentialBound" -> {
                val purposeStr = stringField(s, "purpose") ?: "API_KEY"
                val purpose = try { BoundPurpose.valueOf(purposeStr) } catch (_: Exception) { BoundPurpose.API_KEY }
                val credIdStr = stringField(s, "credentialsId") ?: ""
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
                val purposeStr = stringField(s, "purpose") ?: "API_KEY"
                val purpose = try { BoundPurpose.valueOf(purposeStr) } catch (_: Exception) { BoundPurpose.API_KEY }
                val credIdStr = stringField(s, "credentialsId") ?: ""
                CredentialUsed(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    credentialsId = CredentialsId(credIdStr),
                    purpose = purpose,
                    stepIndex = intField(s, "stepIndex") ?: 0,
                )
            }
            "CredentialUnbound" -> {
                val credIdStr = stringField(s, "credentialsId") ?: ""
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
                val url = stringField(s, "url") ?: ""
                val branch = stringField(s, "branch") ?: ""
                val credIdStr = stringField(s, "credentialsRef")
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
                val url = stringField(s, "url") ?: ""
                val branch = stringField(s, "branch") ?: ""
                val sha = stringField(s, "sha") ?: ""
                val changelogPath = stringField(s, "changelogPath") ?: ""
                val durationMs = longField(s, "durationMs") ?: 0L
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
                val url = stringField(s, "url") ?: ""
                val branch = stringField(s, "branch") ?: ""
                val reason = stringField(s, "reason") ?: ""
                val exitCode = intField(s, "exitCode") ?: 0
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
                val url = stringField(s, "url") ?: ""
                val branch = stringField(s, "branch") ?: ""
                val previousSha = stringField(s, "previousSha")
                val newSha = stringField(s, "newSha") ?: ""
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
            else -> null
        }
    }

    /**
     * Extracts a string field value from JSON by finding the field name
     * and reading until the closing quote (handling escapes).
     */
    private fun stringField(json: String, name: String): String? {
        val nameStart = json.indexOf("\"$name\"")
        if (nameStart == -1) return null
        val colonPos = json.indexOf(':', nameStart)
        if (colonPos == -1) return null
        var i = colonPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        var stringEnd = i + 1
        var inString = true
        var escape = false
        while (stringEnd < json.length && inString) {
            when {
                escape -> { escape = false; stringEnd++ }
                json[stringEnd] == '\\' && inString -> { escape = true; stringEnd++ }
                json[stringEnd] == '"' -> { inString = false }
                else -> stringEnd++
            }
        }
        return if (stringEnd > i + 1) json.substring(i + 1, stringEnd) else ""
    }

    /**
     * Extracts the cacheKey object value from the event JSON.
     * Returns a CacheKey or null if parsing fails.
     */
    private fun parseCacheKey(json: String): CacheKey? {
        val keyStart = json.indexOf("\"cacheKey\"")
        if (keyStart == -1) return null
        val bracePos = json.indexOf('{', keyStart)
        if (bracePos == -1) return null
        var depth = 0
        var i = bracePos
        while (i < json.length) {
            when (json[i]) {
                '{' -> { depth++; i++ }
                '}' -> { depth--; if (depth == 0) break; i++ }
                '"' -> {
                    i++
                    while (i < json.length) {
                        when {
                            json[i] == '\\' -> i += 2
                            json[i] == '"' -> { i++; break }
                            else -> i++
                        }
                    }
                }
                else -> i++
            }
        }
        if (depth != 0) return null
        val cacheKeyJson = json.substring(bracePos, i + 1)
        val value = stringField(cacheKeyJson, "value") ?: ""
        val version = stringField(cacheKeyJson, "version") ?: ""
        return CacheKey(value, version)
    }

    private fun longField(json: String, name: String): Long? {
        val nameStart = json.indexOf("\"$name\"")
        if (nameStart == -1) return null
        val colonPos = json.indexOf(':', nameStart)
        if (colonPos == -1) return null
        var i = colonPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        var numEnd = i
        while (numEnd < json.length && (json[numEnd].isDigit() || json[numEnd] == '-')) numEnd++
        return if (numEnd > i) json.substring(i, numEnd).toLongOrNull() else null
    }

    private fun intField(json: String, name: String): Int? {
        return longField(json, name)?.toInt()
    }

    private fun decodeDiagnostics(json: String): List<ScriptingDiagnostic> {
        val arrStart = json.indexOf("\"diagnostics\"")
        if (arrStart == -1) return emptyList()
        val bracketPos = json.indexOf('[', arrStart)
        if (bracketPos == -1) return emptyList()
        var i = bracketPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] == ']') return emptyList()

        val results = mutableListOf<ScriptingDiagnostic>()
        var depth = 0
        var inString = false
        var escape = false
        val current = StringBuilder()
        i = bracketPos + 1

        while (i < json.length) {
            val ch = json[i]
            when {
                escape -> { current.append(ch); escape = false; i++ }
                ch == '\\' && inString -> { current.append(ch); escape = true; i++ }
                ch == '"' -> { current.append(ch); inString = !inString; i++ }
                ch == '{' && !inString -> { depth++; current.append(ch); i++ }
                ch == '}' && !inString -> {
                    depth--
                    current.append(ch)
                    if (depth == 0) {
                        val diagStr = current.toString().trim()
                        if (diagStr.isNotEmpty()) {
                            parseDiagnostic(diagStr)?.let { results.add(it) }
                        }
                        current.clear()
                    }
                    i++
                }
                ch == ',' && depth == 0 && !inString -> {
                    i++
                }
                else -> { if (depth > 0) current.append(ch); i++ }
            }
        }
        return results
    }

    private fun parseDiagnostic(s: String): ScriptingDiagnostic? {
        val severityStr = stringField(s, "severity")
        val message = stringField(s, "message") ?: ""
        val line = stringField(s, "line")?.toIntOrNull() ?: 0
        val column = stringField(s, "column")?.toIntOrNull() ?: 0
        val path = stringField(s, "path") ?: ""
        val severity = severityStr?.let {
            try { ScriptDiagnosticSeverity.valueOf(it) } catch (_: Exception) { ScriptDiagnosticSeverity.INFO }
        } ?: ScriptDiagnosticSeverity.INFO
        return ScriptingDiagnostic(severity, message, line, column, path)
    }
}
