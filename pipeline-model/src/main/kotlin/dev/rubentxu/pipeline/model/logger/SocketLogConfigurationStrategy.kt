package dev.rubentxu.pipeline.model.logger

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.net.SocketAppender
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply


class SocketLogConfigurationStrategy : ConsoleLogConfigurationStrategy() {

    override fun configure(loggerContext: LoggerContext, logLevel: LogLevel) {

        super.configure(loggerContext, logLevel)

        // Crear socket appender para recibir logs
        val dockerLogsAppender = SocketAppender().apply {
            remoteHost = "host.docker.internal"
            port = 12345
        }

        dockerLogsAppender.context = loggerContext
        dockerLogsAppender.start()


        dockerLogsAppender.addFilter(DockerLogFilter())

        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.addAppender(dockerLogsAppender)

    }
}

private const val PIPELINE_PACKAGE = "dev.rubentxu.pipeline"

class DockerLogFilter : Filter<ILoggingEvent>() {

    override fun decide(event: ILoggingEvent): FilterReply {
        if (event.loggerName.startsWith(PIPELINE_PACKAGE)) {
            return FilterReply.ACCEPT
        } else {
            return FilterReply.DENY
        }
    }

}