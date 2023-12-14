package dev.rubentxu.pipeline.model.agents


import dev.rubentxu.pipeline.model.PipelineComponent
import dev.rubentxu.pipeline.model.PipelineComponentFromMapFactory
import dev.rubentxu.pipeline.model.validations.validateAndGet

interface AgentConfig : PipelineComponent {
    val name: String
    val remoteFS: String
    val numExecutors: Int
    val labelString: String
    val mode: String

    companion object : PipelineComponentFromMapFactory<AgentConfig> {
        override fun create(data: Map<String, Any>): AgentConfig {
            val type = data.keys.first()
            return when (type) {
                "permanent" -> PermanentAgentConfig.create(data)
                "temporary" -> TemporaryAgentConfig.create(data)
                else -> throw IllegalArgumentException("Invalid type $type in AgentConfig")
            }
        }
    }
}

// Configuración para un agente permanente
data class PermanentAgentConfig(
    override val name: String,
    override val remoteFS: String,
    override val numExecutors: Int,
    override val labelString: String,
    override val mode: String,
    val launcher: SSHLauncherConfig,
) : AgentConfig {
    companion object : PipelineComponentFromMapFactory<PermanentAgentConfig> {
        override fun create(data: Map<String, Any>): PermanentAgentConfig {
            val launcherMap: Map<String, Any> = data.validateAndGet("permanent.launcher")
                .isMap()
                .throwIfInvalid("launcher is required in PermanentAgentConfig") as Map<String, Any>

            return PermanentAgentConfig(
                name = data.validateAndGet("permanent.name").isString()
                    .throwIfInvalid("name is required in PermanentAgentConfig"),
                remoteFS = data.validateAndGet("permanent.remoteFS").isString()
                    .throwIfInvalid("remoteFS is required in PermanentAgentConfig"),
                numExecutors = data.validateAndGet("permanent.numExecutors").isNumber()
                    .throwIfInvalid("numExecutors is required in PermanentAgentConfig") as Int,
                labelString = data.validateAndGet("permanent.labelString").isString()
                    .throwIfInvalid("labelString is required in PermanentAgentConfig"),
                mode = data.validateAndGet("permanent.mode").isString()
                    .throwIfInvalid("mode is required in PermanentAgentConfig"),
                launcher = SSHLauncherConfig.create(launcherMap)
            )
        }
    }
}

// Configuración para un agente temporal
data class TemporaryAgentConfig(
    override val name: String,
    override val remoteFS: String,
    override val numExecutors: Int,
    override val labelString: String,
    override val mode: String,
) : AgentConfig {
    companion object : PipelineComponentFromMapFactory<TemporaryAgentConfig> {
        override fun create(data: Map<String, Any>): TemporaryAgentConfig {
            return TemporaryAgentConfig(
                name = data.validateAndGet("temporary.name").isString()
                    .throwIfInvalid("name is required in TemporaryAgentConfig"),
                remoteFS = data.validateAndGet("temporary.remoteFS").isString()
                    .throwIfInvalid("remoteFS is required in TemporaryAgentConfig"),
                numExecutors = data.validateAndGet("temporary.numExecutors").isNumber()
                    .throwIfInvalid("numExecutors is required in TemporaryAgentConfig") as Int,
                labelString = data.validateAndGet("temporary.labelString").isString()
                    .throwIfInvalid("labelString is required in TemporaryAgentConfig"),
                mode = data.validateAndGet("temporary.mode").isString()
                    .throwIfInvalid("mode is required in TemporaryAgentConfig")
            )
        }
    }
}

// Configuración para el lanzador SSH
data class SSHLauncherConfig(
    val host: String,
    val port: Int,
    val credentialsId: String,
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<SSHLauncherConfig> {
        override fun create(data: Map<String, Any>): SSHLauncherConfig {
            return SSHLauncherConfig(
                host = data.validateAndGet("SSHLauncher.host").isString()
                    .throwIfInvalid("host is required in SSHLauncherConfig"),
                port = data.validateAndGet("SSHLauncher.port").isNumber()
                    .throwIfInvalid("port is required in SSHLauncherConfig") as Int,
                credentialsId = data.validateAndGet("SSHLauncher.credentialsId").isString()
                    .throwIfInvalid("credentialsId is required in SSHLauncherConfig")
            )
        }
    }
}
