package dev.rubentxu.pipeline.cli

//
//import ch.qos.logback.classic.Logger
//import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.LoggerContext
import dev.rubentxu.pipeline.backend.PipelineScriptRunner
import dev.rubentxu.pipeline.backend.normalizeAndAbsolutePath
import dev.rubentxu.pipeline.logger.LogLevel
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.logger.SocketLogConfigurationStrategy
import io.micronaut.configuration.picocli.PicocliRunner
import org.slf4j.LoggerFactory
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Command(name = "pipeline-cli", description = ["..."], mixinStandardHelpOptions = true)
class PipelineCliCommand() : Runnable {


    @Option(names = ["-c", "--config"], description = ["Path to the YAML configuration file"])
    private var configPath: String =
        "/home/rubentxu/Proyectos/Rubentxu/kotlin/pipeline-kotlin/pipeline-cli/src/test/resources/config.yaml"

    @Option(names = ["-s", "--script"], description = ["Path to the Kotlin script file"])
    private var scriptPath: String =
        "/home/rubentxu/Proyectos/Rubentxu/kotlin/pipeline-kotlin/pipeline-cli/src/test/resources/HelloWorld.pipeline.kts"

    @Option(names = ["-v", "--verbose"], description = ["Verbose mode"])
    private var verbose: Boolean = false


    override fun run() {
        assert(configPath.isNotEmpty()) { "Config path is empty" }
        assert(scriptPath.isNotEmpty()) { "Script path is empty" }

        if (verbose) {
            println("Config path: ${normalizeAndAbsolutePath(configPath)}")
            println("Script path: ${normalizeAndAbsolutePath(scriptPath)}")
        }
//        val logConfiguration = SocketLogConfigurationStrategy()
//        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
//        logConfiguration.configure(loggerContext, LogLevel.TRACE)
//        val logger = PipelineLogger(logLevel = LogLevel.TRACE, logConfigurationStrategy = logConfiguration)
//        logger.info("Pipeline CLI started...")
        PipelineScriptRunner.evalWithScriptEngineManager(
            scriptPath,
            configPath,
//            logger = logger
            )


//        detachAndStopAllAppenders()
    }

//    private fun detachAndStopAllAppenders() {
//        // Stop all appenders from showing micronaut related traces
//        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
//        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
//        rootLogger.detachAndStopAllAppenders()
//    }


    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            PicocliRunner.run(PipelineCliCommand::class.java, *args)
        }
    }
}
