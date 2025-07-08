package dev.rubentxu.pipeline.events

import dev.rubentxu.pipeline.model.pipeline.Status

/**
 * Represents a generic pipeline event.
 */
interface Event {
    /** The name of the stage associated with the event. */
    val stageName: String

    /** The timestamp when the event occurred (milliseconds since epoch). */
    val timeStamp: Long
}

/**
 * Event indicating the start of a pipeline stage.
 *
 * @property stageName The name of the stage.
 * @property timeStamp The timestamp of the event.
 */
data class StartEvent(
    override val stageName: String,
    override val timeStamp: Long
) : Event

/**
 * Event indicating the end of a pipeline stage.
 *
 * @property stageName The name of the stage.
 * @property timeStamp The timestamp of the event.
 * @property duration The duration of the stage in milliseconds.
 * @property status The final status of the stage.
 */
data class EndEvent(
    override val stageName: String,
    override val timeStamp: Long,
    val duration: Long,
    val status: Status
) : Event
