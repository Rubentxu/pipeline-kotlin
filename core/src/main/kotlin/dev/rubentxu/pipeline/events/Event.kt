package dev.rubentxu.pipeline.events

import dev.rubentxu.pipeline.model.pipeline.Status

interface Event


// Define tus eventos implementando la interfaz de evento
data class StartEvent(val stageName: String, val timeStamp: Long) : Event
data class EndEvent(
    val stageName: String,
    val timeStamp: Long,
    val duration: Long,
    val status: Status
) : Event
