package dev.rubentxu.pipeline.events

import dev.rubentxu.pipeline.model.pipeline.Status

interface Event {
    val stageName: String
    val timeStamp: Long
}

// Define tus eventos implementando la interfaz de evento
data class StartEvent(override val stageName: String, override val timeStamp: Long): Event
data class EndEvent(override val stageName: String, override val timeStamp: Long, val duration: Long, val status: Status): Event
