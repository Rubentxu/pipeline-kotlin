package dev.rubentxu.pipeline.context

import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.model.pipeline.Pipeline

/**
 * Implementación de IPipelineContext que también implementa los roles segregados.
 * En una implementación completa, delegaría a managers específicos para cada rol.
 */
class PipelineContext(
    override val pipeline: Pipeline,
    override val serviceLocator: IServiceLocator,
    private val legacyLogger: ILogger,
) : IPipelineContext {
    // Delegate stateHolder to the service locator
    override val stateHolder: IPipelineStateHolder get() = serviceLocator.get()
}

/**
 * Implementation of IPipelineStateHolder
 */
class PipelineStateHolder : IPipelineStateHolder {
    private val rememberedValues = mutableMapOf<Any, Any>()
    private val contextValues = mutableMapOf<ContextKey<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> remember(key: Any, computation: () -> T): T {
        return rememberedValues.computeIfAbsent(key) { computation() as Any } as T
    }

    override fun invalidate() {
        rememberedValues.clear()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> provide(key: ContextKey<T>, value: T, block: () -> Unit) {
        val previous = contextValues.put(key, value as Any)
        try {
            block()
        } finally {
            if (previous != null) {
                contextValues[key] = previous
            } else {
                contextValues.remove(key)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> consume(key: ContextKey<T>): T? {
        return contextValues[key] as? T
    }
}