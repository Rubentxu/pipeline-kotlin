package dev.rubentxu.pipeline.events

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Thread-safe, coroutine-based event bus for domain events.
 * Supports asynchronous event publishing and subscription with type safety.
 */
interface EventBus {
    suspend fun publish(event: DomainEvent)
    fun <T : DomainEvent> subscribe(eventType: KClass<T>): Flow<T>
    fun subscribeToAll(): Flow<DomainEvent>
    suspend fun close()
}

/**
 * Default implementation of EventBus using Kotlin coroutines and flows.
 * Provides efficient event distribution with back-pressure handling.
 */
class DefaultEventBus(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val bufferSize: Int = 1000
) : EventBus {
    
    private val eventChannel = Channel<DomainEvent>(bufferSize)
    private val eventFlow = eventChannel.receiveAsFlow().shareIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        replay = 0
    )
    
    private val subscribers = ConcurrentHashMap<String, MutableSet<EventSubscriber>>()
    private val eventHandlers = ConcurrentHashMap<KClass<out DomainEvent>, MutableSet<EventHandler<*>>>()
    
    init {
        // Start event processing
        scope.launch {
            eventFlow.collect { event ->
                processEvent(event)
            }
        }
    }
    
    override suspend fun publish(event: DomainEvent) {
        eventChannel.trySend(event).getOrThrow()
    }
    
    override fun <T : DomainEvent> subscribe(eventType: KClass<T>): Flow<T> {
        @Suppress("UNCHECKED_CAST")
        return eventFlow
            .filter { eventType.isInstance(it) }
            .map { it as T }
    }
    
    override fun subscribeToAll(): Flow<DomainEvent> = eventFlow
    
    private suspend fun processEvent(event: DomainEvent) {
        // Process registered handlers
        eventHandlers[event::class]?.forEach { handler ->
            try {
                @Suppress("UNCHECKED_CAST")
                (handler as EventHandler<DomainEvent>).handle(event)
            } catch (e: Exception) {
                // Log error but don't stop processing other handlers
                // In a real implementation, you'd use proper logging
                println("Error processing event ${event::class.simpleName}: ${e.message}")
            }
        }
    }
    
    override suspend fun close() {
        eventChannel.close()
        scope.cancel()
    }
    
    /**
     * Registers a handler for a specific event type.
     * This provides an alternative to flow-based subscription for simpler use cases.
     */
    fun <T : DomainEvent> registerHandler(eventType: KClass<T>, handler: EventHandler<T>) {
        @Suppress("UNCHECKED_CAST")
        eventHandlers.computeIfAbsent(eventType) { mutableSetOf<EventHandler<*>>() }.add(handler as EventHandler<*>)
    }
    
    /**
     * Unregisters a handler for a specific event type.
     */
    fun <T : DomainEvent> unregisterHandler(eventType: KClass<T>, handler: EventHandler<T>) {
        eventHandlers[eventType]?.remove(handler)
    }
}

/**
 * Interface for event handlers that can process domain events.
 */
interface EventHandler<in T : DomainEvent> {
    suspend fun handle(event: T)
}

/**
 * Interface for event subscribers with metadata.
 */
interface EventSubscriber {
    val id: String
    val eventTypes: Set<KClass<out DomainEvent>>
    suspend fun onEvent(event: DomainEvent)
}

/**
 * Builder for creating event bus instances with custom configuration.
 */
class EventBusBuilder {
    private var scope: CoroutineScope? = null
    private var bufferSize: Int = 1000
    private val handlers = mutableMapOf<KClass<out DomainEvent>, MutableSet<EventHandler<*>>>()
    
    fun scope(scope: CoroutineScope) = apply { this.scope = scope }
    fun bufferSize(size: Int) = apply { this.bufferSize = size }
    
    fun <T : DomainEvent> addHandler(eventType: KClass<T>, handler: EventHandler<T>) = apply {
        @Suppress("UNCHECKED_CAST")
        handlers.computeIfAbsent(eventType) { mutableSetOf<EventHandler<*>>() }.add(handler as EventHandler<*>)
    }
    
    fun build(): DefaultEventBus {
        val eventBus = DefaultEventBus(
            scope = scope ?: CoroutineScope(Dispatchers.Default + SupervisorJob()),
            bufferSize = bufferSize
        )
        
        // Register pre-configured handlers
        handlers.forEach { (eventType, handlerSet) ->
            handlerSet.forEach { handler ->
                @Suppress("UNCHECKED_CAST")
                eventBus.registerHandler(eventType as KClass<DomainEvent>, handler as EventHandler<DomainEvent>)
            }
        }
        
        return eventBus
    }
}

/**
 * Convenience extension functions for easier usage
 */
inline fun <reified T : DomainEvent> EventBus.subscribe(): Flow<T> = subscribe(T::class)

inline fun <reified T : DomainEvent> DefaultEventBus.registerHandler(noinline handler: suspend (T) -> Unit) {
    registerHandler(T::class, object : EventHandler<T> {
        override suspend fun handle(event: T) = handler(event)
    })
}

/**
 * DSL for building event bus configuration
 */
fun eventBus(configure: EventBusBuilder.() -> Unit): DefaultEventBus {
    return EventBusBuilder().apply(configure).build()
}