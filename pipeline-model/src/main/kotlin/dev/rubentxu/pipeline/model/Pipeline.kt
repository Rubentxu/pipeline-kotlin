package dev.rubentxu.pipeline.model

import arrow.core.Either

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

interface PipelineDomain

interface IPipelineConfig : PipelineDomain

open class PipelineException(message: String) : Exception(message)


sealed interface PipelineError{
    val message: String
}

/**
 * Data class for validation errors.
 *
 * @property message The error message. This is used to represent an error
 *     that occurs during validation.
 */
data class PropertiesError(override val message: String) : PipelineException(message)

data class NoSuchServiceError(override val message: String) : PipelineException(message)


typealias Res<T> = Either<out PipelineError, T>


interface PipelineDomainDslFactory<T : PipelineDomain> {
    suspend fun create(block: T.() -> Unit): PipelineDomain
}


data class IDComponent private constructor(
    val id: String,
) {
    companion object {
        fun create(id: String): IDComponent {
            require(id.isNotEmpty()) { "ID cannot be empty" }
            require(id.length <= 50) { "ID cannot be more than 50 characters" }
            require(id.all { it.isDefined() }) { "ID can only contain alphanumeric characters : ${id}" }
            return IDComponent(id)
        }
    }

    override fun toString(): String {
        return id
    }
}

interface IPipelineContext : PipelineDomain {
    val pipelineName: String
    suspend fun <T : PipelineDomain> getService(interfaceType: KClass<T>, name: String = pipelineName): Result<T>

    suspend fun <T : PipelineDomain> registerService(interfaceType: KClass<T>, service: T, name: String = pipelineName)

    suspend fun <T : PipelineDomain> registerResource(
        interfaceType: KClass<T>,
        resource: T,
        name: String = pipelineName,
    )

    suspend fun <T : PipelineDomain> getResource(interfaceType: KClass<T>, name: String = pipelineName): Result<T>

}

class PipelineContext(override val pipelineName: String = "default") : IPipelineContext {
    private val services = ConcurrentHashMap<Pair<KClass<*>, String>, Any>()
    private val resources = ConcurrentHashMap<Pair<KClass<*>, String>, Any>()

    private val mutex = Mutex()

    override suspend fun <T : PipelineDomain> getService(interfaceType: KClass<T>, name: String): Result<T> {
        return mutex.withLock {
            val key = Pair(interfaceType, name)
            val service = services[key]

            @Suppress("UNCHECKED_CAST")
            if (service != null) {
                Result.success(service as T)
            } else {
                Result.failure(NoSuchElementException("No service found for type: ${interfaceType.simpleName} and name: $name"))
            }
        }
    }

    override suspend fun <T : PipelineDomain> registerService(interfaceType: KClass<T>, service: T, name: String) {
        mutex.withLock {
            val key = Pair(interfaceType, name)
            services[key] = service
        }
    }

    override suspend fun <T : PipelineDomain> registerResource(interfaceType: KClass<T>, resource: T, name: String) {
        mutex.withLock {
            val key = Pair(interfaceType, name)
            resources[key] = resource
        }
    }

    override suspend fun <T : PipelineDomain> getResource(interfaceType: KClass<T>, name: String): Result<T> {
        return mutex.withLock {
            val key = Pair(interfaceType, name)
            val resource = resources[key]

            @Suppress("UNCHECKED_CAST")
            if (resource != null) {
                Result.success(resource as T)
            } else {
                Result.failure(NoSuchElementException("Resource not found for type: ${interfaceType.simpleName} and name: $name"))
            }
        }
    }
}



