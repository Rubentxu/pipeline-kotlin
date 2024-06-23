package dev.rubentxu.pipeline.core.stores.interfaces

import dev.rubentxu.pipeline.core.models.interfaces.DataModel


interface Selector<State : DataModel, T> {
    fun select(state: State): T
}