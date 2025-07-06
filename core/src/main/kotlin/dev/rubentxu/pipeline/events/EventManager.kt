package dev.rubentxu.pipeline.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlin.reflect.KClass

/**
 * Legacy EventManager for backward compatibility.
 * New code should use the DomainEvent system with EventBus.
 *
 * @deprecated Use EventBus and DomainEvent instead
 */
@Deprecated("Use EventBus and DomainEvent instead", ReplaceWith("EventBus", "dev.rubentxu.pipeline.events.EventBus"))
object EventManager {
    private val _events = MutableSharedFlow<Event>() // Backing property

    /**
     * A Flow of [Event]s that have been notified.
     *
     * Use [subscribeToEvents] to subscribe to specific types of events.
     */
    val events = _events.asSharedFlow() // Public property

    /**
     * Notifies a new event.
     *
     * This will emit the event to the stream of events, and any subscribers to this event will be triggered.
     *
     * @param event The [Event] to notify.
     */
    suspend fun notify(event: Event) {
        _events.emit(event) // Emit the event to the flow
    }

    /**
     * Subscribes to specific types of events.
     *
     * The provided block of code will be executed for each event of the specified types.
     *
     * @param eventClasses The classes of the events to subscribe to. You can provide multiple classes.
     * @param block The block of code to execute for each event. The event will be passed as a parameter to this block.
     */
    suspend inline fun <reified T : Event> subscribeToEvents(
        vararg eventClasses: KClass<T>,
        noinline block: suspend (Event) -> Unit
    ) {
        events
            .filter { event -> eventClasses.any { it.isInstance(event) } }
            .collect(block)
    }
}