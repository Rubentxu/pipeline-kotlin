package dev.rubentxu.pipeline.core.stores.interfaces

import dev.rubentxu.pipeline.core.models.DataModel


interface CoreAction<M : DataModel> {
    fun execute(currentState: M): M
}