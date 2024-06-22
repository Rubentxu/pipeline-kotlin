package dev.rubentxu.pipeline.core.stores

import dev.rubentxu.pipeline.core.models.DataModel
import dev.rubentxu.pipeline.core.stores.interfaces.CoreAction
import dev.rubentxu.pipeline.core.stores.interfaces.Dispacher
import dev.rubentxu.pipeline.core.stores.interfaces.Effect
import dev.rubentxu.pipeline.core.stores.interfaces.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KClass

class Store<S : DataModel>(initialState: S, actionDispacher: Dispacher<S>) {
    private var state: State<S> = State(initialState)
    private var effectMap: MutableMap<KClass<out CoreAction<DataModel>>, MutableList<Effect>> = mutableMapOf()
    private var actionQueue: MutableList<CoreAction<out DataModel>> = mutableListOf()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val stateMutex = Mutex()
    private val dispacher: Dispacher<S> = actionDispacher

    fun commit(action: CoreAction<out DataModel>) {
        actionQueue.add(action)
        scope.launch {
            while (actionQueue.isNotEmpty()) {
                val currentAction = actionQueue.removeAt(0)
                stateMutex.withLock {
                    state = dispacher.apply(state, currentAction) // Apply the mutation to the state
                    val effects = effectMap[currentAction::class]
                    effects?.forEach { it.handleEvent(currentAction) }
                }
            }
        }
    }

    private suspend fun getState(): State<S> {
        return stateMutex.withLock { state }
    }

    fun addEffect(actionType: KClass<out CoreAction<DataModel>>, effect: Effect) {
        effectMap.getOrPut(actionType) { mutableListOf() }.add(effect)
    }

    suspend fun <T> select(selector: (State<S>) -> T): T {
        return selector(getState())
    }
}