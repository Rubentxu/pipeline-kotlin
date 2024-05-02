package dev.rubentxu.pipeline.core.cdi.dependencies

import dev.rubentxu.pipeline.core.cdi.ConfigurationPriority
import dev.rubentxu.pipeline.core.cdi.annotations.Qualifier
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

class DependencyGraph {

    private val graph: MutableMap<DependencyKey, DependencyNode> = mutableMapOf()

    fun addNode(key: DependencyKey, clazz: KClass<*>): DependencyNode {
        if (graph.containsKey(key)) {
            throw Exception("""The interfaz ${key.type.qualifiedName} is already registered with qualifier name '${key.name}'.
              Please ensure that the class ${clazz.qualifiedName} is annotated with @PipelineComponent and the qualifier name is unique.                                   
            """)
        }
        val dependencyNode = DependencyNode(key, clazz)
        graph[key] = dependencyNode
        return dependencyNode
    }

    fun resolveDependencies(): List<DependencyNode> {
        for (node in graph.values) {
            addDependencies(node)
        }
        return sortNodesByDependencies()
    }

    private fun addDependencies(dependencyNode: DependencyNode) {
        val clazz = dependencyNode.clazz
        val constructor = clazz.primaryConstructor

        if (constructor == null) {
            throw Exception("The class ${clazz.qualifiedName} does not have a public constructor. Please ensure that the class has a public constructor.")
        }

        val paramAnnotations = constructor.parameters.map { it.annotations }
        val paramTypes = constructor.parameters.map { it.type.classifier as KClass<*> }

        for (i in paramTypes.indices) {
            var name = ""
            for (annotation in paramAnnotations[i]) {
                if (annotation is Qualifier) {
                    name = annotation.value
                }
            }

            val keyDependency = resolveDependencyKey(paramTypes[i], name, dependencyNode.key.priority)
            val paramNode = graph[keyDependency]

            if (paramNode == null) {
                throw Exception("Dependency not found: $keyDependency for ${dependencyNode.clazz.qualifiedName}. Check if the class exists or the qualifier name is correct")
            }
            dependencyNode.addDependency(paramNode)
        }
    }

    private fun sortNodesByDependencies(): List<DependencyNode> {
        val references = findCyclicReferences()

        if (references.isNotEmpty()) {
            throw Exception("Circular dependency detected: ${references.joinToString(" depends on ") { it.qualifiedName.orEmpty() }}")
        }

        val nodes = ArrayList(graph.values)
        val sortedDependencyNodes = ArrayList<DependencyNode>(nodes.size)
        val visited = mutableSetOf<DependencyNode>()

        for (node in nodes) {
            topologicalSort(node, visited, sortedDependencyNodes)
        }

        return sortedDependencyNodes.asReversed()
    }

    private fun topologicalSort(dependencyNode: DependencyNode, visited: MutableSet<DependencyNode>, sortedDependencyNodes: MutableList<DependencyNode>) {
        if (visited.none { it.key == dependencyNode.key }) {
            visited.add(dependencyNode)

            for (dependency in dependencyNode.dependencies) {
                topologicalSort(dependency, visited, sortedDependencyNodes)
            }
            sortedDependencyNodes.add(0, dependencyNode)
        }
    }

    private fun findCyclicReferences(): Set<KClass<*>> {
        val visited = mutableSetOf<DependencyNode>()
        val recursionStack = mutableSetOf<DependencyNode>()

        for (node in graph.values) {
            if (detectCycle(node, visited, recursionStack)) {
                return recursionStack.map { it.clazz }.toSet()
            }
        }
        return emptySet()
    }

    private fun detectCycle(dependencyNode: DependencyNode, visited: MutableSet<DependencyNode>, recursionStack: MutableSet<DependencyNode>): Boolean {
        if (recursionStack.contains(dependencyNode)) {
            return true
        }

        if (visited.contains(dependencyNode)) {
            return false
        }

        visited.add(dependencyNode)
        recursionStack.add(dependencyNode)

        for (dependency in dependencyNode.dependencies) {
            if (detectCycle(dependency, visited, recursionStack)) {
                return true
            }
        }

        recursionStack.remove(dependencyNode)
        return false
    }

    companion object {
        fun resolveDependencyKey(type: KClass<*>, name: String, priority: ConfigurationPriority = ConfigurationPriority.LOW): DependencyKey {
            val dependencyKey = DependencyKey(type, name, priority)
            return dependencyKey
        }
    }
}