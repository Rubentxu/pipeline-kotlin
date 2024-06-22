package dev.rubentxu.pipeline.core.stores.interfaces

import dev.rubentxu.pipeline.core.models.DataModel


interface Dispacher<M: DataModel> {
    fun apply(state: State<M>, event: CoreAction<out DataModel>): State<M>
}