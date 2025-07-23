package dev.rubentxu.pipeline.backend.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.LogContainerCmd
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.core.command.LogContainerResultCallback
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.logger.model.LoggingContext
import java.util.concurrent.CountDownLatch

class DockerLogManager(
    private val dockerClient: DockerClient,
) {
    private val logger: ILogger = PipelineLogger(
        "DockerLogManager",
        LoggingContext(),
        { event -> println("[${event.level}] ${event.loggerName}: ${event.message}") }
    )
    fun showContainerLogs(containerId: String) {
        val latch = CountDownLatch(1)

        try {
            val logContainerCmd: LogContainerCmd = dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .withTailAll()

            logContainerCmd.exec(object : LogContainerResultCallback() {
                override fun onNext(item: Frame) {
                    println(String(item.payload).trim())
                }

                override fun onComplete() {
                    super.onComplete()
                    latch.countDown() // Notificar cuando se completa el procesamiento de logs
                }

                override fun onError(throwable: Throwable?) {
                    logger.error("Error al leer los logs del contenedor: ${throwable?.message}")
                    latch.countDown() // Notificar en caso de error
                }
            })

            latch.await() // Esperar a que se completen los logs
        } catch (e: Exception) {
            logger.error("Error al obtener logs del contenedor $containerId: ${e.message}")
        }
    }
}
