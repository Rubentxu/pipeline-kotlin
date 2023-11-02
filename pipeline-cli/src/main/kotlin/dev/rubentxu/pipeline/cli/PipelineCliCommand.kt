package dev.rubentxu.pipeline.cli


import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import io.micronaut.configuration.picocli.PicocliRunner
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.nio.file.Path

@Command(name = "pipeline-cli", description = ["..."], mixinStandardHelpOptions = true)
class PipelineCliCommand : Runnable {

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

        val configuration = readConfigFile(normalizeAndAbsolutePath(configPath))

        evalWithScriptEngineManager(File(normalizeAndAbsolutePath(scriptPath)))
    }

    fun readConfigFile(configFilePath: String): Config {
        val mapper = ObjectMapper(YAMLFactory())
        mapper.findAndRegisterModules()

        val configFile = File(configFilePath)
        if (!configFile.exists()) throw Exception("Config file not found")
        if (verbose) {
            println("Config file: ${configFile.absolutePath}")
        }
        return mapper.readValue(configFile)
    }

    fun normalizeAndAbsolutePath(path: String): String {
        return Path.of(path).toAbsolutePath().normalize().toString()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            PicocliRunner.run(PipelineCliCommand::class.java, *args)
        }
    }
}
