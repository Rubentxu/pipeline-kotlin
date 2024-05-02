package dev.rubentxu.pipeline.core.events

class GenericEvent(val name: String, val payload: Map<String, Any>) : PipelineEvent() {

    override fun toString(): String {
        return "GenericEvent[name=$name, payload=$payload]"
    }

    override fun toMap(): Map<String, Any> {
        return mapOf("name" to name, "payload" to payload)
    }
}
