package dev.rubentxu.pipeline.model.pipeline

import dev.rubentxu.pipeline.context.IPipelineContext
import dev.rubentxu.pipeline.context.PipelineContext
import dev.rubentxu.pipeline.context.managers.DefaultLoggerManager
import dev.rubentxu.pipeline.context.withPipelineContext
import dev.rubentxu.pipeline.logger.ConsoleLogConsumer
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager

// En PipelineRunner.kt (borrador de cómo se vería ahora)

class PipelineRunner(private val pipeline: Pipeline) {

    suspend fun run() {
        // 1. Setup de managers y contexto (como lo diseñamos antes)
        val serviceLocator = ServiceLocator()
        val loggerManager = DefaultLoggerManager()
        // ... otros managers ...
        serviceLocator.register<ILoggerManager>(loggerManager)
        // ...

        val consoleLogger = ConsoleLogConsumer(loggerManager)
        consoleLogger.start()

        val context = PipelineContext(pipeline, serviceLocator)

        // 2. Lógica de decisión del agente
        val isAgentEnv = System.getenv("IS_AGENT") != null
        if (pipeline.agent !is AnyAgent && !isAgentEnv) {
            // Esta ejecución NO está en un agente, pero el pipeline REQUIERE uno.
            // Hay que lanzar el agente.
            executeWithAgent(context)
        } else {
            // Esta ejecución ya está en el entorno correcto (o no requiere agente).
            // Ejecutar los stages.
            executeStages(context)
        }
    }

    private suspend fun executeWithAgent(context: IPipelineContext) {
        val logger = context.logger.getLogger("AgentLauncher")
        when (val agent = pipeline.agent) {
            is DockerAgent -> {
                logger.info("Pipeline requires a Docker agent. Preparing to launch container...")
                // Aquí iría la lógica de DockerConfigManager, DockerImageBuilder, etc.
                // que antes estaba en PipelineScriptRunner.
            }
            is KubernetesAgent -> {
                logger.info("Pipeline requires a Kubernetes agent. Preparing to apply pod definition...")
                // Lógica de Kubernetes...
            }
            else -> {
                logger.error("Unsupported agent type: ${agent::class.simpleName}")
            }
        }
    }

    private suspend fun executeStages(context: IPipelineContext) {
        withPipelineContext(context) {
            // La lógica de ejecutar stages que diseñamos anteriormente...
            val logger = context.logger.getLogger("PipelineRunner")
            logger.info("Starting pipeline execution...")
            // ... bucle for sobre pipeline.stages, etc. ...
        }
    }
}