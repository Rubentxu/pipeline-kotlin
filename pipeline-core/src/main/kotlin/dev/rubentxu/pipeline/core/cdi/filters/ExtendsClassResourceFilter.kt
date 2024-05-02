package dev.rubentxu.pipeline.core.cdi.filters

import dev.rubentxu.pipeline.core.cdi.interfaces.ResourceFilter
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

class ExtendsClassResourceFilter<T : Any>(private val baseClass: KClass<T>, private val concreteOnly: Boolean) :
    ResourceFilter<KClass<*>> {
    override fun acceptScannedResource(item: KClass<*>): Boolean {
        return baseClass.isSubclassOf(item) && (!concreteOnly || !(item.isAbstract))
    }
}