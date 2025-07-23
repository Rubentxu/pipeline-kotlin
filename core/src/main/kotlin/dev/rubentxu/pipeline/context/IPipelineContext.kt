package dev.rubentxu.pipeline.context

import dev.rubentxu.pipeline.context.managers.*
import dev.rubentxu.pipeline.context.managers.interfaces.IEnvironmentManager
import dev.rubentxu.pipeline.context.managers.interfaces.IParameterManager
import dev.rubentxu.pipeline.context.managers.interfaces.ISecretManager
import dev.rubentxu.pipeline.context.managers.interfaces.IWorkspaceManager
import dev.rubentxu.pipeline.events.EventBus
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A type-safe key for use with the IPipelineStateHolder's provide/consume mechanism.
 * The generic type <T> ensures that when you consume a value using this key,
 * you get back an object of the correct type.
 */
data class ContextKey<T>(val name: String)

/**
 * Nueva interfaz para la gestión de estado efímero.
 */
interface IPipelineStateHolder {
    fun <T> remember(key: Any, computation: () -> T): T
    fun invalidate()
    fun <T> provide(key: ContextKey<T>, value: T, block: () -> Unit)
    fun <T> consume(key: ContextKey<T>): T?
}


// --- IPipelineContext REFACTORIZADO ---

/**
 * IPipelineContext - Refactorizado para ser un Localizador de Servicios puro.
 * Su única responsabilidad es proporcionar acceso a los managers del pipeline.
 * Esto reduce drásticamente el acoplamiento y clarifica su propósito.
 */
interface IPipelineContext : ServiceLocatorAware {

    // === CORE PIPELINE ACCESS ===
    val pipeline: Pipeline

    // === SERVICE LOCATOR PATTERN (Responsabilidad Principal) ===

    val parameters: IParameterManager get() = serviceLocator.get()
    val environment: IEnvironmentManager get() = serviceLocator.get()
    val secrets: ISecretManager get() = serviceLocator.get()
    val logger: ILoggerManager get() = serviceLocator.get()
    val workspace: IWorkspaceManager get() = serviceLocator.get()
    val events: EventBus get() = serviceLocator.get()
    // Los nuevos roles también son servicios localizables

    val stateHolder: IPipelineStateHolder get() = serviceLocator.get()
}

/**
 * CoroutineContext.Element para propagar el IPipelineContext de forma segura.
 * Reemplaza al inseguro ThreadLocal.
 */
class PipelineContextElement(val context: IPipelineContext) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PipelineContextElement>

    override val key: CoroutineContext.Key<*> = Key
}

/**
 * Objeto para acceder al contexto actual de la corrutina.
 * Reemplaza a LocalPipelineContext.
 */
object CurrentPipelineContext {
    val current: IPipelineContext
        get() = throw NotImplementedError("Use `currentPipelineContext()` suspend function instead.")
}

/**
 * Función suspendida para obtener el contexto actual de forma segura.
 */
suspend fun currentPipelineContext(): IPipelineContext {
    return kotlinx.coroutines.currentCoroutineContext()[PipelineContextElement.Key]?.context
        ?: error("No IPipelineContext found in the current CoroutineContext.")
}

/**
 * Ejecuta un bloque de código con un contexto específico inyectado.
 * Esta es la forma correcta de establecer el contexto en un entorno de corrutinas.
 */
suspend fun <T> withPipelineContext(context: IPipelineContext, block: suspend IPipelineContext.() -> T): T {
    return withContext(PipelineContextElement(context)) {
        context.block()
    }
}

// Extension functions for convenience

suspend fun IPipelineContext.getEnvVar(name: String): String? = environment.get(name)

suspend fun IPipelineContext.getEnvVar(name: String, defaultValue: String): String = environment.get(name, defaultValue)

fun IPipelineContext.setParam(key: String, value: Any) = parameters.set(key, value)

fun <T> IPipelineContext.getParam(key: String): T? = parameters.get(key)

fun <T> IPipelineContext.getParam(key: String, defaultValue: T): T = parameters.get(key, defaultValue)