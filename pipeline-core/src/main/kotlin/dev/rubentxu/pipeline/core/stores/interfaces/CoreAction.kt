package dev.rubentxu.pipeline.core.stores.interfaces

import dev.rubentxu.pipeline.core.models.interfaces.DataModel


interface CoreAction<out M : DataModel> {
    val payload: M
    fun execute(currentState: @UnsafeVariance M): M
}