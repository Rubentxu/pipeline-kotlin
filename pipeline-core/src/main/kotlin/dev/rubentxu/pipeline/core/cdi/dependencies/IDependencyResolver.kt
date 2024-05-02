package dev.rubentxu.pipeline.core.cdi.dependencies

import kotlin.reflect.KClass

interface IDependencyResolver {
    fun getInstances(): Map<DependencyKey, Any>
    fun getSizedInstances(): Int
    fun <T: Any> getInstance(type: KClass<T>): T
    fun <T: Any> getInstance(type: KClass<T>, name: String): T
    fun register(type: KClass<*>, dependencyClass: KClass<*>)
    fun initialize()
    fun registerCoreComponent(type: KClass<*>, instance: Any)
}