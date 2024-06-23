package dev.rubentxu.pipeline.core.stores.interfaces

import dev.rubentxu.pipeline.core.models.interfaces.DataModel


interface Effect {
    fun handleEvent(event: CoreAction<out DataModel>)
}