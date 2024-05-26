package dev.rubentxu.pipeline.core.interfaces

import dev.rubentxu.pipeline.core.cdi.ConfigurationPriority
import dev.rubentxu.pipeline.core.events.IEventStore
import dev.rubentxu.pipeline.core.pipeline.EnvVars

interface IPipelineContext : IServiceLocator, IEventStore {

    fun getSkipStages(): List<String>

    fun addSkipStage(stage: String)

    fun injectEnvironmentVariables(envVars: Map<String, String>)

    fun getEnvVars(): EnvVars

    fun configureServicesByPriority()

    fun setAutoCancelled(autoCancel: Boolean)

    fun isAutoCancelled(): Boolean

    fun setDebugMode(debugMode: Boolean)

    fun isDebugMode(): Boolean

    fun groupInstancesByPriority(): Map<ConfigurationPriority, List<Any>>

}