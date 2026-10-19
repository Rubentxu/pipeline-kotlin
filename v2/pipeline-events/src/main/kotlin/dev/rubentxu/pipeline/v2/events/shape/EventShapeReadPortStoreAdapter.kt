package dev.rubentxu.pipeline.v2.events.shape

import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult

/**
 * Production [EventShapeReadPort] adapter.
 * ## What this adapter is and is not
 * The adapter is a thin composition: it does not introduce a new event
 * Store, a new journal, or a new lease. The composition rule (design
 * A) is: read the typed events the journal recorded for a run (via the
 * [readTypedEvents] callback) and project them to the closed [TypedEvent]
 * ADT. The callback is supplied by the application layer; in tests it
 * points at the in-memory event store's internal list; in production it
 * points at a future internal API that reads from the durable event log.
 * The adapter does NOT itself re-implement the event log scan; it
 * composes the existing event store. It is read-only by construction.
 * ## Read-only by construction
 * `eventShapesForRun` MUST NOT mutate durable state. The composition
 * rule limits it to reads and a projection pass.
 * There is no test guarding this today, and the sentence that used to say
 * otherwise ("the contract test `EventShapeReadPortAdapterTest` pins this")
 * was deleted along with that test in `2aa071aab`, leaving the claim behind.
 * That is acceptable while [EventShapeReadPort] stays EXPERIMENTAL and
 * nothing outside this module consumes it; it stops being acceptable the day
 * a production caller appears, and the first thing that should be written then
 * is a concurrency test, because "does not mutate" is a claim about two
 * callers at once and a single-threaded check cannot make it.
*/
class EventShapeReadPortStoreAdapter(
    private val readTypedEvents: (runId: String) -> List<Any>,
    private val runExists: (runId: String) -> Boolean,
) : EventShapeReadPort {

    override fun eventShapesForRun(runId: String, options: EventShapeOptions): EventShapeResult {
        return try {
            if (!runExists(runId)) {
                return EventShapeResult.Refused(EventShapeRefusal.UnknownRun(runId))
            }
            val raw: List<Any> = try {
                readTypedEvents(runId)
            } catch (e: Exception) {
                return EventShapeResult.Refused(
                    EventShapeRefusal.StorageError(e.shortDiagnostic()),
                )
            }
            if (raw.isEmpty()) {
                return EventShapeResult.Empty
            }
            val typed: List<TypedEvent> = raw.mapNotNull { ev -> projectTyped(ev, runId) }
            if (typed.isEmpty()) {
                return EventShapeResult.Empty
            }
            val startOrdinal: Int = options.cursor?.eventOrdinal ?: 0
            if (startOrdinal >= typed.size) {
                return EventShapeResult.Events(
                    events = emptyList(),
                    cursor = null,
                )
            }
            val effective = typed.drop(startOrdinal).take(options.pageSize)
            val nextCursor: EventShapeCursor? =
                if (startOrdinal + effective.size < typed.size) {
                    EventShapeCursor(eventOrdinal = startOrdinal + effective.size)
                } else null
            EventShapeResult.Events(events = effective, cursor = nextCursor)
        } catch (e: Exception) {
            EventShapeResult.Refused(EventShapeRefusal.StorageError(e.shortDiagnostic()))
        }
    }

    /**
     * Project a raw event to a typed [TypedEvent]. Returns null when
     * The raw event is not one of the five cases (so the
     * shape is restricted to the audit's source-of-truth set).
*/
    private fun projectTyped(raw: Any, runId: String): TypedEvent? = when (raw) {
        is StageStarted -> StageStartedTyped(
            runId = raw.runId,
            occurredAtMs = raw.occurredAt.toEpochMilli(),
            stageOrdinal = raw.stageIndex,
            stageIndex = raw.stageIndex.toString(),
            stageName = raw.stageName,
        )
        is StageFinished -> StageFinishedTyped(
            runId = raw.runId,
            occurredAtMs = raw.occurredAt.toEpochMilli(),
            stageOrdinal = raw.stageIndex,
            stageIndex = raw.stageIndex.toString(),
            outcome = raw.outcome,
        )
        is StageSkipped -> StageSkippedTyped(
            runId = raw.runId,
            occurredAtMs = raw.occurredAt.toEpochMilli(),
            stageOrdinal = raw.stageIndex,
            stageName = raw.stageName,
            reason = raw.reason,
        )
        is StepStarted -> StepStartedTyped(
            runId = raw.runId,
            occurredAtMs = raw.occurredAt.toEpochMilli(),
            stageOrdinal = raw.stageIndex,
            stepKey = "step-${raw.stepIndex}",
            stepName = raw.stepName,
        )
        is StepFinished -> StepFinishedTyped(
            runId = raw.runId,
            occurredAtMs = raw.occurredAt.toEpochMilli(),
            stageOrdinal = raw.stageIndex,
            stepKey = "step-${raw.stepIndex}",
            outcome = raw.stepType,
        )
        else -> null
    }

    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }
}

/**
 * Convenience: adapt an [EventRecordReadResult]-yielding reader into
 * the [EventShapeReadPort] shape. Most tests wire the InMemoryEventStore
 * directly; production wires this convenience. The reader is a SAM
 * `(runId) -> List<Any>` so the test fixture does not have to expose
 * the typed events.
*/
fun eventShapeReadPortFromReader(
    reader: (runId: String) -> List<Any>,
    runExists: (runId: String) -> Boolean,
): EventShapeReadPort = EventShapeReadPortStoreAdapter(reader, runExists)
