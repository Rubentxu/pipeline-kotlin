package dev.rubentxu.pipeline.core.cdi.dependencies

import dev.rubentxu.pipeline.core.cdi.annotations.Inject
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.cdi.annotations.Qualifier
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

class DependencyResolver : IDependencyResolver {
    private var instances: MutableMap<DependencyKey, Any> = mutableMapOf()
    private var graph: DependencyGraph = DependencyGraph()

    override fun getInstances(): Map<DependencyKey, Any> {
        return instances
    }

    override fun getSizedInstances(): Int {
        return instances.size
    }

    override fun <T : Any> getInstance(type: KClass<T>): T {
        return getInstance(type, "")
    }

    override fun <T : Any> getInstance(type: KClass<T>, name: String): T {
        val keyDependency = DependencyGraph.resolveDependencyKey(type, name)
        val instance = instances.get(keyDependency) as T?
        instance
            ?: throw Exception("Dependency not found for type: '${type.qualifiedName}' and Qualifier name: '${name}'. Check if the class exists or the qualifier name is correct")
        return instance
    }

    override fun register(type: KClass<*>, dependencyClass: KClass<*>) {
        val annotation = dependencyClass.annotations.filter { it.annotationClass == PipelineComponent::class }
            .firstOrNull() as PipelineComponent?
        annotation
            ?: throw IllegalArgumentException("The class ${dependencyClass.qualifiedName} is not annotated with @PipelineComponent. Please annotate the class with @PipelineComponent.")
        val name = annotation.name
        val priority = annotation.priority
        val dependencyKey = DependencyGraph.resolveDependencyKey(type, name, priority)
        registerWithKey(dependencyKey, dependencyClass)
    }

    fun registerWithKey(dependencyKey: DependencyKey, componentClass: KClass<*>) {
        if (!instances.containsKey(dependencyKey)) {
            ensureIsInstanceOf(dependencyKey.type, componentClass)
            graph.addNode(dependencyKey, componentClass)
        }
    }

    override fun initialize() {
        val nodes = graph.resolveDependencies().filter { this.instances[it.key] == null }

        nodes.forEach { node ->
            val instance = createNewInstanceByConstructor(node)
            instance
                ?: throw Exception("The class ${node.clazz.qualifiedName} does not have a public constructor. Please ensure that the class has a public constructor.")
            if (this.instances.containsKey(node.key)) {
                throw Exception("The class ${node.clazz.qualifiedName} is already registered. Please ensure that the class annotated with @PipelineComponent and the qualifier name is unique.")
            }
            this.instances[node.key] = instance
        }

        nodes.forEach { node ->
            injectDependenciesByFields(this.instances[node.key]!!)
        }
    }

    override fun registerCoreComponent(type: KClass<*>, instance: Any) {
        val key = DependencyGraph.resolveDependencyKey(type, "")
        instances[key] = instance
        graph.addNode(key, instance::class)
    }

    protected fun createNewInstanceByConstructor(dependencyNode: DependencyNode): Any? {
        val clazz = dependencyNode.clazz
        val constructor = clazz.primaryConstructor
        val dependencies = dependencyNode.dependencies.map { this.instances[it.key] }
        return constructor?.call(*dependencies.toTypedArray())
    }

    fun injectDependenciesByFields(instance: Any) {
        var instanceClass: KClass<*>? = instance::class
        while (instanceClass != null) {
            for (property in instanceClass.declaredMemberProperties) {
                if (!isAnnotated(property)) {
                    continue
                }
                val qualifierAnnotation = property.javaField?.getAnnotation(Qualifier::class.java)
                val fieldType = property.returnType.classifier as? KClass<*>
                fieldType?.let {
                    val dependency = if (qualifierAnnotation != null) {
                        getInstance(it, qualifierAnnotation.value)
                    } else {
                        getInstance(it, "")
                    }

                    ensureIsInstanceOf(it, dependency::class)

                    if (property is KMutableProperty<*>) {
                        property.isAccessible = true
                        property.setter.call(instance, dependency)
                    }
                }
            }
            instanceClass = instanceClass.superclasses.firstOrNull()
        }
    }

    private fun isAnnotated(property: KProperty<*>): Boolean {
        return property.javaField?.getAnnotation(Inject::class.java) != null
    }

    private fun ensureIsInstanceOf(type: KClass<*>, dependency: KClass<*>) {
        if (!dependency.isSubclassOf(type)) {
            throw IllegalArgumentException("The registered class ${dependency.qualifiedName} does not implement the dependency interface ${type.qualifiedName}. Please ensure that the registered class is an implementation of the dependency interface.")
        }
    }

}