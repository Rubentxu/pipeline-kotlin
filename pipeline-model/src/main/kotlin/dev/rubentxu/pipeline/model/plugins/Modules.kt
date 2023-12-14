package dev.rubentxu.pipeline.model.plugins

import java.lang.module.ModuleFinder
import java.nio.file.Path
import java.util.*


fun configurePlugins() {
    val boot = ModuleLayer.boot()
    val scl: ClassLoader  = ClassLoader.getSystemClassLoader()

    val path = Path.of("plugins")

    val pluginsFinder = ModuleFinder.of(path)
    val pluginConfiguration = boot.configuration()
        .resolve(
            pluginsFinder,
            ModuleFinder.of(),
            listOf("dev.rubentxu.pipeline.model.plugins")
        )

    val pluginsLayer = boot.defineModulesWithOneLoader(pluginConfiguration, scl)
    val plugins = pluginsLayer.modules()


}


interface PluginLifecycleListener {
    fun pluginAdded(plugin: PluginDescriptor)

    fun pluginRemoved(plugin: PluginDescriptor)
}

class PluginDescriptor(
    val name: String,
    val version: String,
    val module: ModuleLayer,
    val services: List<Class<out Any>>
) {
    override fun toString(): String {
        return "PluginDescriptor(name='$name', version='$version', module=$module)"
    }
}

class PluginManager {
    private val listeners = mutableListOf<PluginLifecycleListener>()
    private val plugins = mutableListOf<PluginDescriptor>()

    fun addPluginLifecycleListener(listener: PluginLifecycleListener) {
        listeners.add(listener)
    }

    fun removePluginLifecycleListener(listener: PluginLifecycleListener) {
        listeners.remove(listener)
    }

    fun addPlugin(plugin: PluginDescriptor) {
        plugins.add(plugin)
        listeners.forEach { it.pluginAdded(plugin) }
    }

    fun removePlugin(plugin: PluginDescriptor) {
        plugins.remove(plugin)
        listeners.forEach { it.pluginRemoved(plugin) }
    }

    fun getPlugins(): List<PluginDescriptor> {
        return plugins.toList()
    }
}

class PluginManagerFactory {
    companion object {
        fun create(): PluginManager {
            return PluginManager()
        }
    }
}

class PluginListener : PluginLifecycleListener {
    override fun pluginAdded(plugin: PluginDescriptor) {
        println("Plugin added: $plugin")
        val plugins = ServiceLoader.load(plugin.module,plugin.services.first()) as ServiceLoader<PluginLifecycleListener>

        plugins.forEach {
            it.pluginAdded(plugin)
        }
    }

    override fun pluginRemoved(plugin: PluginDescriptor) {
        println("Plugin removed: $plugin")
    }
}