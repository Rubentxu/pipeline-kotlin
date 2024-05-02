package dev.rubentxu.pipeline.core.cdi.dependencies

import dev.rubentxu.pipeline.core.cdi.ConfigurationPriority
import kotlin.reflect.KClass

data class DependencyKey(
    var name: String,
    var type: KClass<*>,
    var priority: ConfigurationPriority
) {
    constructor(type: KClass<*>, name: String = "", priority: ConfigurationPriority = ConfigurationPriority.LOW)
            : this(name, type, priority)

    override fun toString(): String {
        return "DependencyKey name: $name, type: $type, priority: $priority"
    }
}