package dev.rubentxu.pipeline.core.interfaces

import java.io.Serializable
import kotlin.reflect.KClass

interface IServiceLocator : Serializable {

    fun registerComponentsFromPackages()

    fun registerComponentsFromPackages(packageNames: List<String>)

    fun registerConfigAdaptersFromPackages()

    fun registerConfigAdaptersFromPackages(packageName: List<String>)

    fun <T: Any> getComponent(type: KClass<T>, name: String): T

    fun <T: Any> getComponent(type: KClass<T>): T

}
