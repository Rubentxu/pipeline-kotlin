package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.TempWorkspaceResult
import dev.rubentxu.pipeline.v2.application.TemporaryWorkspaceOperations
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.PwdResolved
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The single canonical implementation of [TemporaryWorkspaceOperations] — the typed
 * port consumed by `CorePwdTmpStep.handler` (S2-A6 / G3T, correct post-review).
 *
 * ## Why an adapter rather than inlining the IO into the handler
 *
 * AGENTS.md STEP IMPLEMENTATION — OPERATIVE GUIDE rule 9 (effectful handlers adapt
 * to existing certified infrastructure, never embed process/IO logic). The
 * certified `core.sh` Step reaches `ShExecution.invokeShell` through a
 * `ShellOperations` capability. By symmetry, the registry `core.pwd.tmp` reaches
 * `Files.createDirectories` + `PwdResolved` emission through THIS adapter —
 * reached through `TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY`.
 *
 * ## Capability discipline
 *
 * This adapter is the ONLY thing in `core.pwd.tmp`'s path that:
 * - holds an [EventSink] reference,
 * - calls `Files.createDirectories`,
 * - reads `OpId.format()` / constructs `sha256(opId.format())`,
 * - constructs and emits a `PwdResolved`.
 *
 * The handler reaches the typed seam and gets back a `TempWorkspaceResult(path)`.
 * It does NOT see filesystem, event sink, runId, opId, or workspaceRoot. Same
 * isolation principle as `core.sh → ShellOperations → ShOperationsAdapter`.
 *
 * ## Determinism (D5–D12, S2-A6 / G3T)
 *
 * The same OpId produces the same path; a different OpId produces a different path.
 * The path identity is `sha256(opId.format())` — never `System.currentTimeMillis()`,
 * never `UUID.randomUUID()`. `Files.createDirectories` is idempotent so a second
 * invocation with the same OpId returns the same path without throwing.
 *
 * ## Why bind the canonical OpId here, not at the call site
 *
 * Per `ShOperationsAdapter` rationale (see that file's docstring for the parallel
 * parallel-branch argument): the OpId is the durable identity anchor — same
 * `(runId, stageIndex, stepIndex[, branchIndex][, bodyPath])` ⇒ same resource.
 * The handler must never reconstruct an OpId; the bridge hands it in here.
 *
 * ## No privileged executor
 *
 * The adapter does NOT itself run a process. It only performs a `mkdir` on the
 * canonical workspace path and emits one `PwdResolved` event. Anything stronger
 * (e.g. running `mktemp -d`) is intentionally out of scope.
 */
class TemporaryWorkspaceOperationsAdapter(
    private val runIdString: String,
    private val opId: OpId,
    private val workspaceRoot: Path,
    private val eventSink: EventSink,
) : TemporaryWorkspaceOperations {

    override fun resolveOrCreate(): TempWorkspaceResult {
        // D5–D12 determinism: sha256(canonical OpId.format string) is the ONLY
        // source of path identity. No timestamp, no random suffix. The token
        // is 64 lowercase hex chars by construction.
        val token = sha256Hex(opId.format())
        val tmpPath: Path = workspaceRoot.resolve("tmp-pwd-$token").toAbsolutePath()
        // Idempotent: same OpId ⇒ same path; second mkdir is a no-op.
        Files.createDirectories(tmpPath)
        val absolutePath = tmpPath.toString()
        eventSink.append(
            PwdResolved(
                eventId = UUID.randomUUID().toString(),
                runId = runIdString,
                sequence = 0L,
                occurredAt = Instant.now(),
                path = absolutePath,
                workspaceRoot = workspaceRoot.toAbsolutePath().toString(),
                sha256 = sha256Hex(absolutePath),
            ),
        )
        return TempWorkspaceResult(path = absolutePath)
    }

    companion object {
        /**
         * Internal-only deterministic helper. Visible to tests in the same
         * module that need to assert against the canonical token. NO other
         * production code path is allowed to construct the token outside
         * this adapter.
         */
        internal fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray())
            return hashBytes.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
