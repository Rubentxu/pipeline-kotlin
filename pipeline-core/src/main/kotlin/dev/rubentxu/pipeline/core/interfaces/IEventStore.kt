package dev.rubentxu.pipeline.core.interfaces

import dev.rubentxu.pipeline.core.events.PipelineEvent

interface IEventStore {

    fun publishEvent(event: PipelineEvent)

    fun publishEvent(name: String, payload: Map<String, Any>)

    fun retrieveEventsSince(since: Long): List<PipelineEvent>

    fun retrieveEvents(sinceId: Long): List<PipelineEvent>
}