package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.InvalidResourceRefException
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs

/**
 * Occurrence identity of an event. Events do NOT receive a ResourceRef of their
 * own; `(source, EventId)` is what makes an occurrence addressable (EVT-1 law 2/5).
 */
data class EventId(val value: String) {
    init {
        require(value.isNotBlank()) { "EventId must not be blank" }
    }
}

/**
 * Unique occurrence reference: `source + EventId` (CloudEvents-aligned identity
 * pair, transport-agnostic).
 */
data class EventRef(
    val source: ResourceRef,
    val id: EventId,
)
