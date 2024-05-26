package dev.rubentxu.pipeline.core.events


import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class EventStore : IEventStore {
    private val nextEventId = AtomicLong()
    private val eventHistory: CopyOnWriteArrayList<PipelineEvent>

    init {
        nextEventId.set(0)
        eventHistory = CopyOnWriteArrayList()
    }

    private fun storeEvent(event: PipelineEvent) {
        eventHistory.add(event)
    }

    override fun retrieveEventsSince(since: Long): List<PipelineEvent> {
        return eventHistory.filter { event -> event.timeMillis >= since }
    }

    override fun retrieveEvents(sinceId: Long): List<PipelineEvent> {
        return eventHistory.filter { event -> event.eventId >= sinceId }
    }

    @Synchronized
    override fun publishEvent(event: PipelineEvent) {
        val eventId = nextEventId.incrementAndGet()
        event.eventId = eventId
        if (event.timeMillis == 0L) {
            event.timeMillis = System.currentTimeMillis()
        }
        storeEvent(event)
    }

    override fun publishEvent(name: String, payload: Map<String, Any>) {
        publishEvent(GenericEvent(name, payload))
    }
}