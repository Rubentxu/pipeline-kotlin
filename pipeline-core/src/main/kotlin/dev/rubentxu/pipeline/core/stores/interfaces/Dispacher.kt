package dev.rubentxu.pipeline.core.stores.interfaces

import dev.rubentxu.pipeline.core.models.interfaces.DataModel


interface Dispacher<M: DataModel> {
    fun apply(state: M, event: CoreAction<out DataModel>): M
}