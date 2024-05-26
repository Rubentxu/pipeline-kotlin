package dev.rubentxu.pipeline.core.events

interface IEventStore {

    fun retrieveEventsSince(since: Long): List<PipelineEvent>

    fun retrieveEvents(sinceId: Long): List<PipelineEvent>

    fun publishEvent(event: PipelineEvent)

    fun publishEvent(name: String, payload: Map<String, Any>)
}
