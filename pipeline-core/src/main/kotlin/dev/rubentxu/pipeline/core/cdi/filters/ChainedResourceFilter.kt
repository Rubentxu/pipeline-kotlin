package dev.rubentxu.pipeline.core.cdi.filters

import dev.rubentxu.pipeline.core.cdi.interfaces.ResourceFilter

enum class Mode {
    And, Or, Not
}

class ChainedResourceFilter<T>(private val mode: Mode, private vararg val filters: ResourceFilter<T>) :
    ResourceFilter<T> {
    override fun acceptScannedResource(item: T): Boolean {
        for (filter in filters) {
            val accepted = filter.acceptScannedResource(item)
            when {
                accepted && mode == Mode.Or -> return true
                accepted && mode == Mode.Not -> return false
                !accepted && mode == Mode.And -> return false
            }
        }
        return mode == Mode.And || mode == Mode.Not
    }
}