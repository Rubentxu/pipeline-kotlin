package dev.rubentxu.pipeline.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.Flow

class EventBus {
    val bus = MutableSharedFlow<Event>()

    val events: SharedFlow<Event> = bus

    suspend fun <T: Event> publish(event: T) {
        bus.emit(event)
    }

    inline fun <reified T : Event> ofType(): Flow<T> = bus.filterIsInstance<T>()
}