package dev.rubentxu.pipeline.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import java.nio.file.Path
import kotlin.script.experimental.api.ScriptDiagnostic


class PipelineCli : CliktCommand() {
    private val configPath: String by option("-c", help = "Path to the YAML configuration file")
        .default("/home/rubentxu/Proyectos/Rubentxu/kotlin/pipeline-kotlin/pipeline-cli/src/test/resources/config.yaml")
    private val scriptPath: String by option("-s", help = "Path to the Kotlin script file")
        .default("/home/rubentxu/Proyectos/Rubentxu/kotlin/pipeline-kotlin/pipeline-cli/src/test/resources/HelloWorld.pipeline.kts")

    override fun run() {
        assert(configPath.isNotEmpty()) { "Config path is empty" }
        assert(scriptPath.isNotEmpty()) { "Script path is empty" }

        println("Config path: ${normalizeAndAbsolutePath(configPath)}")
        println("Script path: ${normalizeAndAbsolutePath(scriptPath)}")

        val configuration = readConfigFile(normalizeAndAbsolutePath(configPath))
        executeScript(configuration, normalizeAndAbsolutePath(scriptPath))
    }

    fun readConfigFile(configFilePath: String): Config {
        val mapper =  ObjectMapper(YAMLFactory())
        mapper.findAndRegisterModules()

        val configFile = File(configFilePath)
        if (!configFile.exists()) throw Exception("Config file not found")
        println("Config file: ${configFile.absolutePath}")
        return mapper.readValue(configFile)
    }
    fun executeScript(config: Config, scriptPath: String) {
        print("> ")
        val scriptFile = File(scriptPath)
        val result = evalFile(scriptFile)
        print("> ")

        result.reports.forEach {
            if (it.severity > ScriptDiagnostic.Severity.DEBUG) {
                println(" : ${it.message}" + if (it.exception == null) "" else ": ${it.exception}")
            }
        }
        println(result)

    }

    fun normalizeAndAbsolutePath(path: String): String {
       return Path.of(path).toAbsolutePath().normalize().toString()
    }

}

fun main(args: Array<String>) = PipelineCli().main(args)

