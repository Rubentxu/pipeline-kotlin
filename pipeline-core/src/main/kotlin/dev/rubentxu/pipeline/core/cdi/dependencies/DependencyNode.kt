package dev.rubentxu.pipeline.core.cdi.dependencies

import kotlin.reflect.KClass

data class DependencyNode(val key: DependencyKey, val clazz: KClass<*>) {
    val dependencies = mutableListOf<DependencyNode>()

    fun addDependency(dependencyNode: DependencyNode) {
        this.dependencies.add(dependencyNode)
    }
}