package dev.rubentxu.pipeline.core.pipeline

import dev.rubentxu.pipeline.core.cdi.ClassesInPackageScanner
import dev.rubentxu.pipeline.core.cdi.ConfigurationPriority
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.cdi.dependencies.DependencyResolver
import dev.rubentxu.pipeline.core.cdi.dependencies.IDependencyResolver
import dev.rubentxu.pipeline.core.cdi.filters.AnnotatedClassResourceFilter
import dev.rubentxu.pipeline.core.events.EventStore
import dev.rubentxu.pipeline.core.events.IEventStore
import dev.rubentxu.pipeline.core.events.PipelineEvent
import dev.rubentxu.pipeline.core.interfaces.*

import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation


class PipelineContext() : IPipelineContext {
    companion object {
        val DEFAULT_SCAN_PACKAGES = listOf("dev.rubentxu.pipeline")
    }

    private val scanner = ClassesInPackageScanner().apply {
        setResourceFilter(AnnotatedClassResourceFilter(PipelineComponent::class))
    }
    private val dependencyResolver: IDependencyResolver = DependencyResolver()

    private val IEventStore: IEventStore = EventStore()
    private var skipStages = mutableListOf<String>()
    private var autoCancelled: Boolean = false
    private var debugMode: Boolean = false
    private var envVars: EnvVars = EnvVars(mutableMapOf())

    override fun registerComponentsFromPackages() {
        registerComponentsFromPackages(DEFAULT_SCAN_PACKAGES)
    }

    override fun registerComponentsFromPackages(packageNames: List<String>) {
        packageNames.forEach { packageName ->
            scanner.findAnnotatedClasses(packageName, PipelineComponent::class).onSuccess {
                registerAll(it, packageName)
            }

        }
        dependencyResolver.registerCoreComponent(IPipelineContext::class, this)
        dependencyResolver.initialize()
    }

    private fun registerAll(componentClass: Set<KClass<*>>, packageName: String) {
        componentClass.forEach { serviceClass ->
            val interfaces = filterInstances(serviceClass, packageName)

            if (interfaces.isEmpty()) {
                registerPipelineComponent(serviceClass, serviceClass)
            }

            interfaces.forEach { interfaceClass ->
                registerPipelineComponent(interfaceClass, serviceClass)
            }
        }
    }

    private fun registerPipelineComponent(type: KClass<*>, serviceClass: KClass<*>) {
        dependencyResolver.register(type, serviceClass)
    }

    private fun filterInstances(serviceClass: KClass<*>, packageName: String): List<KClass<*>> {
        return serviceClass.java.interfaces.filter { interfaceIt ->
            interfaceIt.name.startsWith(packageName) || DEFAULT_SCAN_PACKAGES.any { pkg ->
                interfaceIt.name.startsWith(pkg)
            }
        }.map { it.kotlin }
    }

    override fun <T : Any> getComponent(type: KClass<T>, name: String): T {
        return dependencyResolver.getInstance(type, name)
    }

    override fun <T : Any> getComponent(type: KClass<T>): T {
        return dependencyResolver.getInstance(type)
    }

    override fun setAutoCancelled(autoCancel: Boolean) {
        autoCancelled = autoCancel
    }

    override fun isAutoCancelled(): Boolean {
        return autoCancelled
    }

    override fun setDebugMode(debugMode: Boolean) {
        this.debugMode = debugMode
    }

    override fun isDebugMode(): Boolean {
        return debugMode
    }

    override fun getSkipStages(): MutableList<String> {
        return skipStages
    }

    override fun addSkipStage(stage: String) {
        skipStages.add(stage)
    }

    override fun injectEnvironmentVariables(map: Map<String, String>) {
        map.forEach { (k, v) -> envVars[k] = v }
    }

    override fun getEnvVars(): EnvVars {
        return envVars
    }

    override fun configureServicesByPriority() {
        val configClient = getComponent(IConfigClient::class)
        val map = groupInstancesByPriority()
        val priorities = listOf(
            ConfigurationPriority.HIGHEST,
            ConfigurationPriority.HIGH,
            ConfigurationPriority.MEDIUM,
            ConfigurationPriority.LOW,
            ConfigurationPriority.LOWEST
        )

        priorities.forEach { priority ->
            map[priority]?.forEach { instance ->
                if (instance is Configurable) {
                    instance.configure(configClient)
                }
            }
        }
    }

    override fun groupInstancesByPriority(): Map<ConfigurationPriority, List<Any>> {
        val finalSortedInstances = mapOf(
            ConfigurationPriority.HIGHEST to mutableListOf<Any>(),
            ConfigurationPriority.HIGH to mutableListOf<Any>(),
            ConfigurationPriority.MEDIUM to mutableListOf<Any>(),
            ConfigurationPriority.LOW to mutableListOf<Any>(),
            ConfigurationPriority.LOWEST to mutableListOf<Any>()
        )

        dependencyResolver.getInstances().forEach { (key, value) ->
            val priority = key.priority
            finalSortedInstances[priority]?.add(value)
        }
        return finalSortedInstances
    }

    fun getSizedInstances(): Int {
        return dependencyResolver.getSizedInstances()
    }

    override fun publishEvent(event: PipelineEvent) {
        IEventStore.publishEvent(event)
    }

    override fun publishEvent(name: String, payload: Map<String, Any>) {
        IEventStore.publishEvent(name, payload)
    }

    override fun retrieveEventsSince(since: Long): List<PipelineEvent> {
        return IEventStore.retrieveEventsSince(since)
    }

    override fun retrieveEvents(sinceId: Long): List<PipelineEvent> {
        return IEventStore.retrieveEvents(sinceId)
    }

    override fun registerConfigAdaptersFromPackages() {
        registerConfigAdaptersFromPackages(DEFAULT_SCAN_PACKAGES)
    }

    override fun registerConfigAdaptersFromPackages(packageNames: List<String>) {
        packageNames.forEach { packageName ->
            val componentClassesResult: Result<Set<KClass<*>>> =
                scanner.findImplementers(packageName, IConfigAdapter::class)
            val configClient = getComponent(IConfigClient::class)

            componentClassesResult.onSuccess { componentClasses ->
                componentClasses.forEach { adapterClass ->
                    val annotation = adapterClass.findAnnotation<PipelineComponent>()
                    if (annotation == null) {
                        println("Config Adapter not found for ${adapterClass.simpleName} in dependency resolver")
                    } else {
                        val name = annotation.name
                        val component = getComponent(IConfigAdapter::class, name)
                        if (component == null) {
                            println("Config Adapter not found for ${adapterClass.simpleName} in dependency resolver")
                        } else {
                            configClient.addAdapter(component)
                        }
                    }
                }
            }.onFailure {
                println("Failed to find implementers for package $packageName: ${it.message}")
            }
        }
    }

}
