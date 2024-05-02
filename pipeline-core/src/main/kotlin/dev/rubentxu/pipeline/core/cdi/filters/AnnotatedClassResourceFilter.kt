package dev.rubentxu.pipeline.core.cdi.filters

import dev.rubentxu.pipeline.core.cdi.interfaces.ResourceFilter
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

class AnnotatedClassResourceFilter(private val annotationClass: KClass<out Annotation>) : ResourceFilter<KClass<*>> {
    override fun acceptScannedResource(item: KClass<*>): Boolean {
        return item.annotations.any { it.annotationClass == annotationClass }
    }
}