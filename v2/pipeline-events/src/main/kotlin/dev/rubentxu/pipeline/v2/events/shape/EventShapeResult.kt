package dev.rubentxu.pipeline.v2.events.shape

/**
 * M7 — closed envelope returned by [EventShapeReadPort.eventShapesForRun].
 *
 * Three cases:
 *
 *  - [Events] — the run has at least one typed event; the bounded list
 *    is present.
 *  - [Empty] — the run is known to the runtime but has no typed events
 *    yet (a fresh run, or a run whose events have not been journaled).
 *  - [Refused] — the substrate cannot be inspected at all; `refusal`
 *    carries the closed [EventShapeRefusal] reason.
 *
 * Mirrors the M6 pattern: a sealed result ADT with three cases, no
 * `Either`/`Result` reuse. The distinction between [Empty] and
 * [Refused] is the same one [Events.Empty] / [Events.Refused] pin
 * for the read ports: "known but empty" vs "substrate unobservable".
 */
sealed interface EventShapeResult {

    /**
     * The shape call succeeded. `events` is the bounded list; `cursor`
     * is the resume cursor for the next page (null when the result is
     * the last page or [Empty] / [Refused]).
     */
    data class Events(
        val events: List<TypedEvent>,
        val cursor: EventShapeCursor?,
    ) : EventShapeResult {
        init {
            require(events.size <= EventShapeOptions.MAX_EVENT_SHAPE_PAGE_SIZE) {
                "events.size must be <= ${EventShapeOptions.MAX_EVENT_SHAPE_PAGE_SIZE}, got ${events.size}"
            }
        }
    }

    /** The run is known to the runtime but has no typed events yet. */
    data object Empty : EventShapeResult

    /**
     * The substrate could not be inspected at all. `refusal` carries
     * the closed [EventShapeRefusal] reason.
     */
    data class Refused(val refusal: EventShapeRefusal) : EventShapeResult
}

/** Closed ADT of reasons the event shape could not be served. */
sealed interface EventShapeRefusal {

    /** The run is not known to this PK instance. */
    data class UnknownRun(val runId: String) : EventShapeRefusal

    /** The event plane is not configured for this instance. */
    data object NoEventStore : EventShapeRefusal

    /** The underlying storage failed; `cause` is a short diagnostic. */
    data class StorageError(val cause: String) : EventShapeRefusal
}

/**
 * Bounded-scan + resume-cursor options for
 * [EventShapeReadPort.eventShapesForRun]. Mirrors
 * [dev.rubentxu.pipeline.v2.runtime.inspect.StructureRunOptions]
 * semantics.
 *
 *  - `pageSize` — max number of typed events returned per call. The
 *    init guard enforces [1, 4096].
 *  - `cursor` — the [EventShapeCursor] from the previous call, or null
 *    for the first call.
 *
 * The options are validated at construction; the companion's
 * `Default` and `MAX_EVENT_SHAPE_PAGE_SIZE` constants preserve the
 * "no `Either`/`Result` reuse" rule.
 */
data class EventShapeOptions(
    val pageSize: Int = DEFAULT_EVENT_SHAPE_PAGE_SIZE,
    val cursor: EventShapeCursor? = null,
) {
    init {
        require(pageSize in 1..MAX_EVENT_SHAPE_PAGE_SIZE) {
            "pageSize must be in [1, $MAX_EVENT_SHAPE_PAGE_SIZE], got $pageSize"
        }
    }

    companion object {
        const val DEFAULT_EVENT_SHAPE_PAGE_SIZE: Int = 128
        const val MAX_EVENT_SHAPE_PAGE_SIZE: Int = 4096
        val Default: EventShapeOptions = EventShapeOptions()
    }
}

/**
 * Opaque-per-run resume cursor for
 * [EventShapeReadPort.eventShapeForRun]. Forward-only; the value
 * encodes the next event's ordinal in the journal-ordered list.
 */
data class EventShapeCursor(
    val eventOrdinal: Int,
) {
    init { require(eventOrdinal >= 0) { "eventOrdinal must be >= 0, got $eventOrdinal" } }
}
