package dev.rubentxu.pipeline.backend.agent.docker

import com.github.dockerjava.api.DockerClient

interface DockerClientProvider {
    val dockerClient: DockerClient
    val dockerLogManager: DockerLogManager

}
