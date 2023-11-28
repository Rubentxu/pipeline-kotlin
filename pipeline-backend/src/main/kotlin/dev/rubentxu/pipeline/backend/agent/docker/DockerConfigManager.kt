package dev.rubentxu.pipeline.backend.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.transport.DockerHttpClient
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import dev.rubentxu.pipeline.model.pipeline.DockerAgent
import org.apache.commons.lang3.SystemUtils
import java.time.Duration

class DockerConfigManager(val agent: DockerAgent) : DockerClientProvider {
    lateinit var configHost: String

    private val dockerClientConfig: DefaultDockerClientConfig by lazy {
        dockerConfig()
    }

    private val httpClient: DockerHttpClient by lazy {
        createHttpClient()
    }

    override val dockerClient: DockerClient by lazy {
        createContainerClient()
    }

    override val dockerLogManager: DockerLogManager by lazy {
        DockerLogManager(dockerClient)
    }


    private fun createHttpClient(): DockerHttpClient {
        return ZerodepDockerHttpClient.Builder()
            .dockerHost(dockerClientConfig.getDockerHost())
            .sslConfig(dockerClientConfig.getSSLConfig())
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build()
    }

    private fun createContainerClient(): DockerClient {
        return DockerClientImpl.getInstance(dockerClientConfig, httpClient)
    }

    fun dockerConfig(): DefaultDockerClientConfig {
        configHost = if (agent.host.isEmpty()) {
            if (SystemUtils.IS_OS_WINDOWS) {
                "tcp://localhost:2375"
            } else {
                "unix:///var/run/docker.sock"
            }
        } else {
            agent.host
        }

        return DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(configHost)
            .build()
    }
}

