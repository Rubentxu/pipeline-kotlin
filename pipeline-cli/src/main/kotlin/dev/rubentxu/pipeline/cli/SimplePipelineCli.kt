package dev.rubentxu.pipeline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*  
import dev.rubentxu.pipeline.backend.PipelineScriptRunner
import dev.rubentxu.pipeline.backend.normalizeAndAbsolutePath
import dev.rubentxu.pipeline.context.PipelineServiceInitializer
import java.io.File

/**
 * Simplified professional CLI for Pipeline-Kotlin
 */
class SimplePipelineCli : CliktCommand(
    name = "pipeline",
    help = "A professional CLI for managing and executing Kotlin DSL pipelines"
) {
    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echo(getFormattedHelp())
        }
    }
    
    private fun getFormattedHelp(): String {
        return """
${cyan("🚀 Pipeline-Kotlin CLI v1.0.0")}

${dim("Professional pipeline management for Kotlin")}

${cyan("Available Commands:")}
  ${green("run")} <script>      Execute a pipeline script
  ${green("validate")} <script> Validate a pipeline script without executing
  ${green("list")}              List pipeline scripts in current directory
  ${green("clean")}             Clean up generated files and caches
  ${green("version")}           Show version information
  ${green("--help")}            Show this help message

${cyan("Examples:")}
  ${dim("pipeline run build.pipeline.kts")}
  ${dim("pipeline validate deploy.kts --detailed")}
  ${dim("pipeline list --all")}
  ${dim("pipeline clean --cache")}
  ${dim("pipeline version")}

${green("Happy pipelining! 🎯")}
        """.trimIndent()
    }

}

class SimpleRunCommand : CliktCommand(
    name = "run",
    help = "Execute a pipeline script"
) {
    private val script by argument(
        name = "SCRIPT",
        help = "Path to the pipeline script (.pipeline.kts)"
    ).file(mustExist = true, canBeFile = true, canBeDir = false)

    private val config by option(
        "--config", "-c",
        help = "Path to configuration file"
    ).file(mustExist = true, canBeFile = true, canBeDir = false)

    private val verbose by option(
        "--verbose", "-v",
        help = "Enable verbose logging"
    ).default("false")

    override fun run() {
        val terminal = currentContext.terminal
        
        try {
            terminal.println(green("🚀 Starting pipeline execution..."))
            
            // Initialize pipeline services first
            if (verbose.toBoolean()) {
                terminal.println(cyan("🔧 Initializing pipeline services..."))
            }
            val serviceInitializer = PipelineServiceInitializer()
            val serviceLocator = serviceInitializer.initialize()
            
            val configPath = config?.absolutePath ?: getDefaultConfigPath()
            val scriptPath = script.absolutePath
            
            if (verbose.toBoolean()) {
                terminal.println(cyan("📄 Script: ${normalizeAndAbsolutePath(scriptPath)}"))
                terminal.println(cyan("⚙️  Config: ${normalizeAndAbsolutePath(configPath)}"))
                terminal.println(cyan("✅ Services initialized successfully"))
            }
            
            val startTime = System.currentTimeMillis()
            
            PipelineScriptRunner.evalWithDslManager(scriptPath, configPath)
            
            val duration = System.currentTimeMillis() - startTime
            terminal.println(green("✅ Pipeline completed successfully in ${duration}ms"))
            
        } catch (e: Exception) {
            terminal.println(red("❌ Pipeline execution failed: ${e.message}"))
            if (verbose.toBoolean()) {
                e.printStackTrace()
            }
            throw e
        }
    }

    private fun getDefaultConfigPath(): String {
        val homeDir = System.getProperty("user.home")
        return "$homeDir/.pipeline/config.yaml"
    }
}

class SimpleVersionCommand : CliktCommand(
    name = "version",
    help = "Display version information"
) {
    override fun run() {
        val terminal = currentContext.terminal
        
        terminal.println(cyan("Pipeline CLI"))
        terminal.println("Version: 1.0.0")
        terminal.println("Build Date: ${getCurrentDate()}")
        terminal.println("Kotlin Version: ${KotlinVersion.CURRENT}")
        terminal.println("OS/Arch: ${getOsArch()}")
        terminal.println()
        
        terminal.println(cyan("Core Components:"))
        terminal.println("  Pipeline Core: 1.0.0")
        terminal.println("  DSL Engine: 1.0.0")
        terminal.println("  Script Runner: 1.0.0")
        terminal.println()
        
        terminal.println(cyan("Dependencies:"))
        terminal.println("  Clikt: 4.2.1")
        terminal.println("  Mordant: 2.2.0")
        terminal.println("  Kotlinx Coroutines: 1.10.2")
        terminal.println()
        
        terminal.println(cyan("Runtime Information:"))
        terminal.println("  JVM Version: ${System.getProperty("java.version")}")
        terminal.println("  JVM Vendor: ${System.getProperty("java.vendor")}")
        terminal.println("  Working Directory: ${System.getProperty("user.dir")}")
    }

    private fun getCurrentDate(): String {
        return java.time.LocalDateTime.now().toString()
    }

    private fun getOsArch(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return "$os/$arch"
    }
}