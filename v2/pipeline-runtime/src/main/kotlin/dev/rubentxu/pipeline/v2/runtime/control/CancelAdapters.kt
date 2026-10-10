package dev.rubentxu.pipeline.v2.runtime.control

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.RunExecutionLease
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SealOutcome
import dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * M2 — production [RuntimeControlPort] adapter backed by the existing
 * `RunExecutionLease` decider, the existing `OperationJournal`, and the existing
 * `OutputFrameIndex` + `OutputSealPort` composition.
 *
 * ## What this adapter is and is not
 *
 * The adapter is a thin composition: it does NOT introduce a new lease, a new
 * fencing scheme, a new journal schema, or a new scheduler. Every authority it
 * composes is named with module:file:line in the design's composition table
 * (M2 design §6).
 *
 * ## Lease boundary (audit B.2, B.8, G.3)
 *
 * The adapter consults the existing `RunExecutionLease.acquire` decider with
 * the current lease record (read-only via `FileBackedRunExecutionLeaseStore
 * .observe`) and a synthetic request. A `LeaseAcquisition.AlreadyOwned` verdict
 * surfaces as [CancelRefusal.LeaseHeldByAnother]. The adapter does NOT call
 * `FileBackedRunExecutionLeaseStore.acquire`, so cancel does NOT take a fresh
 * lease (audit B.8).
 *
 * ## Idempotency (audit D.3)
 *
 * A cancel call made N times produces one terminal state. The second call
 * returns [CancelOutcome.AlreadyCancelled] by reading the durable journal
 * terminal row. The contract test `CancelIdempotencyTest` (§7.2 of the
 * design) pins the invariant.
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §4 and §6.
 */
class RuntimeControlPortStoreAdapter(
    private val journal: OperationJournal,
    private val lease: FileBackedRunExecutionLeaseStore,
    private val frames: OutputFrameIndex,
    private val sealStream: (OutputStreamId) -> SealOutcome,
    private val clock: Clock,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : RuntimeControlPort {

    override fun cancel(runId: String, reason: CancelReason): CancelOutcome {
        // 1. Lease consultation (read-only).
        val observed = try {
            lease.observe(runId)
        } catch (e: Exception) {
            return CancelOutcome.Refused(
                CancelRefusal.JournalUnavailable(e.shortDiagnostic()),
            )
        }
        val syntheticRequest = LeaseRequest(
            runId = runId,
            ownerId = SYNTHETIC_OWNER,
        )
        when (val decision = RunExecutionLease.acquire(observed, syntheticRequest)) {
            is LeaseAcquisition.AlreadyOwned -> {
                return CancelOutcome.Refused(
                    CancelRefusal.LeaseHeldByAnother(
                        ownerId = decision.heldBy.value,
                        fencingToken = decision.fencingToken.value,
                    ),
                )
            }
            is LeaseAcquisition.Unverifiable -> {
                return CancelOutcome.Refused(
                    CancelRefusal.JournalUnavailable(decision.reason),
                )
            }
            else -> { /* Acquired / Reentered / TakenOver — proceed. */ }
        }

        // 2. Idempotency check: any existing terminal row determines the outcome
        // BEFORE we write. We use the existing `OperationJournal.listForRun` +
        // `getEndedAt` to project the durable terminal row.
        val existingOps = try {
            journal.listForRun(runId)
        } catch (e: Exception) {
            return CancelOutcome.Refused(
                CancelRefusal.JournalUnavailable(e.shortDiagnostic()),
            )
        }

        val existingTerminal = existingOps.firstOrNull { it.status.isTerminal }
        if (existingTerminal != null) {
            val terminalAtMs: Long = try {
                journal.getEndedAt(existingTerminal.id, existingTerminal.attempt) ?: 0L
            } catch (e: Exception) {
                return CancelOutcome.Refused(
                    CancelRefusal.JournalUnavailable(e.shortDiagnostic()),
                )
            }
            return when (existingTerminal.status) {
                OperationStatus.ABORTED -> CancelOutcome.AlreadyCancelled(
                    attempt = AttemptId(1),
                    terminalAtMs = terminalAtMs,
                )
                else -> CancelOutcome.Refused(
                    CancelRefusal.RunTerminal(existingTerminal.status.name.lowercase()),
                )
            }
        }

        if (existingOps.isEmpty() && !lease.isKnown(runId)) {
            return CancelOutcome.Refused(CancelRefusal.UnknownRun(runId))
        }

        // 3. Terminal journal write. We append a RerunOperation with ABORTED
        // status, marking the cancellation. The fingerprint is the deterministic
        // SHA-256 of the cancel marker (the same input every time, so the journal
        // row is content-addressable on subsequent calls and the append's
        // ON CONFLICT path keeps idempotency).
        val now = clock.now()
        val cancelOpId = "cancel-${runId}"
        val reasonPrimitive: JsonPrimitive = when (reason) {
            is CancelReason.UserRequested -> JsonPrimitive("user-requested")
            is CancelReason.Annotated -> JsonPrimitive(reason.text)
        }
        val cancelInput = OperationInput(
            stepId = "cancel",
            params = mapOf(
                "kind" to JsonPrimitive("cancel"),
                "runId" to JsonPrimitive(runId),
                "reason" to reasonPrimitive,
            ),
            runId = runId,
            attempt = 1,
        )
        val fingerprintHex = sha256Hex("$cancelOpId|${json.encodeToString(cancelInput)}")
        val cancelOp = RerunOperation(
            id = cancelOpId,
            fingerprint = Fingerprint(fingerprintHex),
            input = cancelInput,
            output = null,
            status = OperationStatus.ABORTED,
            attempt = 1,
        )

        val terminalAtMs: Long = now.toEpochMilli()
        try {
            journal.append(cancelOp)
        } catch (e: Exception) {
            return CancelOutcome.Refused(
                CancelRefusal.JournalUnavailable(e.shortDiagnostic()),
            )
        }

        // 4. Per-stream seal (idempotent). OutputSealPort.seal returns NeverOpened
        // for streams that were never declared — that is fine, it's a silent
        // no-op (not an error).
        val streamsToSeal: List<OutputStreamId> = try {
            frames.streamsOfRun(runId)
        } catch (e: Exception) {
            return CancelOutcome.Refused(
                CancelRefusal.StorageError(e.shortDiagnostic()),
            )
        }
        var streamsSealed = 0
        for (stream in streamsToSeal) {
            val outcome: SealOutcome = try {
                sealStream(stream)
            } catch (e: Exception) {
                return CancelOutcome.Refused(
                    CancelRefusal.StorageError(e.shortDiagnostic()),
                )
            }
            // Sealed, AlreadySealed, NeverOpened, Failure.
            when (outcome) {
                is SealOutcome.Sealed -> streamsSealed += 1
                is SealOutcome.AlreadySealed -> streamsSealed += 1
                is SealOutcome.NeverOpened -> { /* silent no-op */ }
                is SealOutcome.Failure -> {
                    return CancelOutcome.Refused(
                        CancelRefusal.StorageError(outcome.cause.shortDiagnostic()),
                    )
                }
            }
        }

        return CancelOutcome.Cancelled(
            attempt = AttemptId(1),
            terminalAtMs = terminalAtMs,
            streamsSealed = streamsSealed,
        )
    }

    private fun sha256Hex(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }

    private companion object {
        // Synthetic owner id used to consult the pure lease decider for an
        // observation-only path; see the introspection adapter's companion
        // for the same rationale.
        val SYNTHETIC_OWNER: RunOwnerId = RunOwnerId.of("cancel-m2-observer")!!
    }
}
