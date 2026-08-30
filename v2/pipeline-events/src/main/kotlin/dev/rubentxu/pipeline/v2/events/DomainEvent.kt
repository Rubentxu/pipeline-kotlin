package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.CredentialsRef
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import java.time.Instant

/**
 * Sealed hierarchy of domain events emitted during a pipeline run.
 */
sealed interface DomainEvent {
    val eventId: String
    val runId: String
    val sequence: Long
    val kind: String
    val occurredAt: Instant
}

/**
 * Emitted when a pipeline run starts.
 */
data class RunStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val scriptPath: String,
) : DomainEvent {
    override val kind: String get() = "RunStarted"
}

/**
 * Emitted when script compilation starts.
 */
data class CompilationStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
) : DomainEvent {
    override val kind: String get() = "CompilationStarted"
}

/**
 * Emitted when script compilation finishes (success or failure).
 */
data class CompilationFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val cacheKey: CacheKey,
    val diagnostics: List<ScriptingDiagnostic>,
) : DomainEvent {
    override val kind: String get() = "CompilationFinished"
}

/**
 * Emitted when a pipeline run finishes.
 */
data class RunFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val outcome: String,
    val diagnostics: List<ScriptingDiagnostic>,
) : DomainEvent {
    override val kind: String get() = "RunFinished"
}

/**
 * Emitted when a stage in the pipeline starts.
 */
data class StageStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stageIndex: Int,
    val stageName: String,
) : DomainEvent {
    override val kind: String get() = "StageStarted"
}

/**
 * Emitted when a stage in the pipeline finishes.
 */
data class StageFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stageIndex: Int,
    val stageName: String,
    val outcome: String,
) : DomainEvent {
    override val kind: String get() = "StageFinished"
}

/**
 * Emitted when a step within a stage starts.
 */
data class StepStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stageIndex: Int,
    val stepIndex: Int,
    val stepName: String,
    val stepType: String,
) : DomainEvent {
    override val kind: String get() = "StepStarted"
}

/**
 * Emitted when a step within a stage finishes.
 */
data class StepFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stageIndex: Int,
    val stepIndex: Int,
    val stepName: String,
    val stepType: String,
) : DomainEvent {
    override val kind: String get() = "StepFinished"
}

/**
 * Emitted when an agent is resolved for parallel execution.
 * Records which agent label was selected and any associated metadata.
 */
data class AgentResolved(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val agentLabel: String,
    val remoteUri: String?,
) : DomainEvent {
    override val kind: String get() = "AgentResolved"
}

/**
 * Emitted when a parallel branch starts execution.
 */
data class ParallelBranchStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val branchIndex: Int,
    val branchName: String,
    val parentStageIndex: Int,
) : DomainEvent {
    override val kind: String get() = "ParallelBranchStarted"
}

/**
 * Emitted when a parallel branch finishes execution.
 */
data class ParallelBranchFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val branchIndex: Int,
    val branchName: String,
    val parentStageIndex: Int,
    val outcome: String,
) : DomainEvent {
    override val kind: String get() = "ParallelBranchFinished"
}

/**
 * Emitted when a retry attempt starts.
 */
data class RetryAttemptStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val attemptNumber: Int,
    val maxAttempts: Int,
    val stepName: String,
    val stepType: String,
    val stageIndex: Int,
    val stepIndex: Int,
) : DomainEvent {
    override val kind: String get() = "RetryAttemptStarted"
}

/**
 * Emitted when a retry attempt finishes.
 */
data class RetryAttemptFinished(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val attemptNumber: Int,
    val maxAttempts: Int,
    val stepName: String,
    val stepType: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val outcome: String,
) : DomainEvent {
    override val kind: String get() = "RetryAttemptFinished"
}

/**
 * Emitted when a timeout is scheduled for a step or stage.
 */
data class TimeoutScheduled(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val timeoutSeconds: Long,
    val timeoutAction: String,
    val stepName: String?,
    val stepType: String?,
    val stageIndex: Int?,
    val stepIndex: Int?,
) : DomainEvent {
    override val kind: String get() = "TimeoutScheduled"
}

/**
 * Emitted when a step fails (e.g., error step type).
 */
data class StepFailed(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stepIndex: Int,
    val stepName: String,
    val stepType: String,
    val failureKind: FailureKind,
    val message: String,
) : DomainEvent {
    override val kind: String get() = "StepFailed"
}

/**
 * Emitted when echo output is captured during step execution.
 */
data class EchoOutputCaptured(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stepIndex: Int,
    val content: String,
) : DomainEvent {
    override val kind: String get() = "EchoOutputCaptured"
}

/**
 * Emitted when a credential scope is entered and a credential is bound (T6).
 *
 * Audit trail: documents that a credential was bound to the execution environment.
 * No secret value, secret bytes, or secret field names cross the DSL/event boundary.
 *
 * @param credentialsId The bound credential ID (L1 structural carrier)
 * @param purpose How the credential is injected (ENV / FILE / VALUE)
 */
data class CredentialBound(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val credentialsId: CredentialsId,
    val purpose: BoundPurpose,
) : DomainEvent {
    override val kind: String get() = "CredentialBound"
}

/**
 * Emitted when a bound credential is used — env injection or returnStdout delivery (T6).
 *
 * Audit trail: documents each use of a bound credential.
 *
 * @param credentialsId The credential ID used
 * @param purpose How the credential was injected
 * @param stepIndex The step that triggered the use
 */
data class CredentialUsed(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val credentialsId: CredentialsId,
    val purpose: BoundPurpose,
    val stepIndex: Int,
) : DomainEvent {
    override val kind: String get() = "CredentialUsed"
}

/**
 * Emitted when a credential scope exits and all bound credentials are released (T6).
 *
 * Audit trail: documents that credentials were unbound and handles wiped.
 * Emitted exactly once per withCredentials block on scope exit (success/failure/interrupt).
 *
 * @param credentialsId The unbound credential ID
 */
data class CredentialUnbound(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val credentialsId: CredentialsId,
) : DomainEvent {
    override val kind: String get() = "CredentialUnbound"
}

// =============================================================================
// L5 SCM Events (ML-R5)
// =============================================================================

/**
 * Emitted when a git checkout step starts.
 *
 * BEFORE git ls-remote / clone / fetch — credentials are resolved but NOT yet used.
 * Carries typed [credentialsRef] which is a CredentialsRef, NOT secret bytes.
 * INV-CR-CR4: no secret material in event fields.
 *
 * @param url Repository URL
 * @param branch Branch being checked out
 * @param credentialsRef Typed carrier for credentials (not secret bytes)
 */
data class GitCheckoutStarted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val url: String,
    val branch: String,
    val credentialsRef: CredentialsRef?,
) : DomainEvent {
    override val kind: String get() = "GitCheckoutStarted"
}

/**
 * Emitted when a git checkout completes successfully.
 *
 * After SHA-equality no-op OR fetch+reset --hard.
 * INV-CR-CR4: no secret material.
 *
 * @param url Repository URL
 * @param branch Branch that was checked out
 * @param sha Final SHA at HEAD after checkout
 * @param changelogPath Path to changelog.txt (empty string if changelog=false)
 * @param durationMs Time taken in milliseconds
 */
data class GitCheckoutCompleted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val url: String,
    val branch: String,
    val sha: String,
    val changelogPath: String,
    val durationMs: Long,
) : DomainEvent {
    override val kind: String get() = "GitCheckoutCompleted"
}

/**
 * Emitted when a git checkout fails.
 *
 * After non-zero exit from clone/fetch/reset.
 * INV-CR-CR4: reason field is stderr first 256 chars (non-ASCII stripped),
 * not secret material.
 *
 * @param url Repository URL
 * @param branch Branch that failed
 * @param reason stderr first 256 chars, non-ASCII stripped
 * @param exitCode git exit code
 */
data class GitCheckoutFailed(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val url: String,
    val branch: String,
    val reason: String,
    val exitCode: Int,
) : DomainEvent {
    override val kind: String get() = "GitCheckoutFailed"
}

/**
 * Emitted when git ls-remote detects the remote SHA has changed.
 *
 * Synchronous poll (no daemon) — emitted before fetch decision.
 * INV-CR-CR4: no secret material.
 *
 * @param url Repository URL
 * @param branch Branch being polled
 * @param previousSha SHA from last poll (null for first poll)
 * @param newSha Current remote SHA
 */
data class GitPollChanged(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val url: String,
    val branch: String,
    val previousSha: String?,
    val newSha: String,
) : DomainEvent {
    override val kind: String get() = "GitPollChanged"
}

// =============================================================================
// L7 Jenkins File + Artefact Events (ML-R7)
// =============================================================================

/**
 * Entry for a single archived artifact file.
 *
 * @param runId Run ID
 * @param stageName Stage name
 * @param relPath Path relative to workspace root
 * @param sha256 SHA-256 hex digest of the file
 * @param size Size in bytes
 * @param archivedAt When the file was archived
 */
data class ArtifactEntry(
    val runId: String,
    val stageName: String,
    val relPath: String,
    val sha256: String,
    val size: Long,
    val archivedAt: Instant,
)

/**
 * Emitted when a writeFile step completes successfully.
 *
 * Payload is restricted to path + sha256 + size — NEVER text/content/bytes.
 * INV-L6-EVT-001 (F-ARCH-L6-004): no secret material.
 *
 * @param runId Run ID
 * @param stageName Stage name
 * @param path Resolved absolute path of the written file
 * @param sha256 SHA-256 hex digest of the file content
 * @param size Size in bytes
 * @param atomicallyMoved True if ATOMIC_MOVE succeeded; false if cross-fs fallback was used
 * @param occurredAt Timestamp
 */
data class FileWritten(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val path: java.nio.file.Path,
    val sha256: String,
    val size: Long,
    val atomicallyMoved: Boolean,
) : DomainEvent {
    override val kind: String get() = "FileWritten"
}

/**
 * Emitted when a readFile step executes.
 *
 * Payload is restricted to path + sha256 + size — NEVER text/content/bytes.
 * INV-L6-EVT-001 (F-ARCH-L6-004): no secret material.
 * readText is captured ONLY for files created within the same run (writeFile→readFile pipeline).
 * Files read from outside the run have exists=false and no bytes captured.
 *
 * @param runId Run ID
 * @param stageName Stage name
 * @param path Resolved absolute path of the file
 * @param sha256 SHA-256 hex digest (null if file does not exist)
 * @param size Size in bytes (null if file does not exist)
 * @param occurredAt Timestamp
 */
data class FileRead(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val path: java.nio.file.Path,
    val sha256: String?,
    val size: Long?,
) : DomainEvent {
    override val kind: String get() = "FileRead"
}

/**
 * Emitted when archiveArtifacts completes successfully.
 *
 * ONE event per call (not per file) — INV-L6-ARC-006 journal hygiene.
 * Each ArtifactEntry carries sha256 + size for auditability.
 * Payload is restricted to typed ArtifactEntry list — NEVER content/bytes/data.
 * INV-L6-EVT-001 (F-ARCH-L6-004): no secret material.
 *
 * @param runId Run ID
 * @param stageName Stage name
 * @param files List of archived artifact entries
 * @param occurredAt Timestamp
 */
data class ArtifactArchived(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val files: List<ArtifactEntry>,
) : DomainEvent {
    override val kind: String get() = "ArtifactArchived"
}

/**
 * Emitted when archiveArtifacts fails (glob mismatch with allowEmptyArchive=false, or I/O error).
 *
 * The reason is passed through SecretPatternRegistry.scrub() BEFORE emit.
 * INV-L6-CR-015: no secret material in reason field.
 *
 * @param runId Run ID
 * @param stageName Stage name
 * @param reason Scrubbed failure reason
 * @param occurredAt Timestamp
 */
data class ArtifactArchiveFailed(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val reason: String,
) : DomainEvent {
    override val kind: String get() = "ArtifactArchiveFailed"
}

// =============================================================================
// ML-R9 workflow-control events
// =============================================================================

/**
 * Emitted when entering a dir block (cwd changes).
 *
 * @param path The directory path entered
 * @param previousPath The previous working directory (for restore on exit)
 */
data class DirEntered(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val path: String,
    val previousPath: String,
) : DomainEvent {
    override val kind: String get() = "DirEntered"
}

/**
 * Emitted when exiting a dir block (cwd restored).
 *
 * @param path The directory path exited
 * @param restoredTo The working directory after restoration
 */
data class DirExited(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val path: String,
    val restoredTo: String,
) : DomainEvent {
    override val kind: String get() = "DirExited"
}

// =============================================================================
// ML-R9 workspace-cleanup events (T-05)
// =============================================================================

/**
 * Emitted when deleteDir completes (atomically erases workspace contents).
 *
 * Idempotent: re-execution on already-deleted path emits deletedCount=0 with same sha.
 *
 * @param path The directory path that was deleted (workspace-relative)
 * @param deletedCount Number of files/directories deleted (0 if already deleted)
 * @param sha256 SHA-256 hex of the .deleted marker file content
 */
data class DirDeleted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val path: String,
    val deletedCount: Int,
    val sha256: String,
) : DomainEvent {
    override val kind: String get() = "DirDeleted"
}

/**
 * Emitted when cleanWs completes (selective workspace cleanup with glob patterns).
 *
 * @param deletedFiles Number of files deleted
 * @param deletedDirs Number of directories deleted (recursive, includes now-empty parents)
 * @param patterns Ant-style glob patterns applied (empty list = delete all non-.v2)
 * @param sha256 SHA-256 hex of the .cleaned marker file content
 */
data class WsCleaned(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val deletedFiles: Int,
    val deletedDirs: Int,
    val patterns: List<String>,
    val sha256: String,
) : DomainEvent {
    override val kind: String get() = "WsCleaned"
}

// =============================================================================
// ML-R9 error-handling events (T-06)
// =============================================================================

/**
 * Emitted when catchError catches an inner failure.
 *
 * @param stageName The stage containing the catchError block
 * @param buildResult The build result override (null = use Jenkins default UNSTABLE)
 * @param stageResult The stage result after catch (UNSTABLE or FAILURE)
 * @param message The user-provided message from catchError
 */
data class CatchErrorTriggered(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stageName: String,
    val buildResult: String?,
    val stageResult: String,
    val message: String?,
) : DomainEvent {
    override val kind: String get() = "CatchErrorTriggered"
}

/**
 * Emitted when unstable(message) marks the stage as unstable.
 *
 * @param stageName The stage containing the unstable step
 * @param message The user-provided message
 */
data class StageMarkedUnstable(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    val stageName: String,
    val message: String,
) : DomainEvent {
    override val kind: String get() = "StageMarkedUnstable"
}
