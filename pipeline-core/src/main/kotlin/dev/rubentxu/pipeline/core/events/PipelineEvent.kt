package dev.rubentxu.pipeline.core.events

import java.io.Serializable

abstract class PipelineEvent : Serializable {
    var timeMillis: Long = 0
    var eventId: Long = 0

    abstract fun toMap(): Map<String, Any>
}