package dev.rubentxu.pipeline.backend.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.WaitContainerResultCallback
import dev.rubentxu.pipeline.model.logger.IPipelineLogger
import dev.rubentxu.pipeline.model.logger.PipelineLogger

import java.util.*

class ContainerLifecycleManager(
    private val dockerClientProvider: DockerClientProvider,
) {
    private val logger: IPipelineLogger = PipelineLogger.getLogger()
    private val dockerClient: DockerClient = dockerClientProvider.dockerClient
    private val dockerLogManager: DockerLogManager = dockerClientProvider.dockerLogManager
    fun createAndStartContainer(environment: Map<String, String>): String {
        val containerId = createContainer(environment)

        logger.system("Starting container $containerId")
        dockerClient.startContainerCmd(containerId).exec()

        logger.system("Showing container logs $containerId")
        dockerLogManager.showContainerLogs(containerId)

        logger.system("Waiting for container $containerId")
        waitForContainer(containerId)

        return containerId
    }

    fun waitForContainer(containerId: String) {
        logger.system("Waiting for container $containerId")
        dockerClient.waitContainerCmd(containerId)
            .exec(WaitContainerResultCallback())
            .awaitCompletion() // Bloquea y espera a que el contenedor termine.
    }

    fun createContainer(environment: Map<String, String>): String {
        logger.system("Create container with environment: $environment")
        val uniqueContainerName = "${DockerImageBuilder.IMAGE_NAME}-${UUID.randomUUID()}"

        val result = dockerClient.createContainerCmd(DockerImageBuilder.IMAGE_NAME)
            .withName(uniqueContainerName)
            .withEnv(environment.map { "${it.key}=${it.value}" })
            .exec()

        return result.id
    }

}