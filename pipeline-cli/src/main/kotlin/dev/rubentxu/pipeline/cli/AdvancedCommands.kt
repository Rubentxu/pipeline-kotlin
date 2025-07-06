package dev.rubentxu.pipeline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File

/**
 * Validate a pipeline script without executing it
 */
class ValidateCommand : CliktCommand(
    name = "validate",
    help = "Validate a pipeline script without executing it"
) {
    
    private val script by argument(
        name = "SCRIPT",
        help = "Path to the pipeline script (.pipeline.kts or .kts)"
    ).file(mustExist = true, canBeFile = true, canBeDir = false)
    
    private val syntax by option(
        "--syntax",
        help = "Check syntax only"
    ).flag(default = false)
    
    private val detailed by option(
        "--detailed", "-d",
        help = "Show detailed validation report"
    ).flag(default = false)
    
    override fun run() {
        val terminal = currentContext.terminal
        
        try {
            terminal.println(cyan("🔍 Validating pipeline: ${script.name}"))
            terminal.println()
            
            if (syntax) {
                validateSyntax(terminal)
            } else {
                validatePipeline(terminal)
            }
            
        } catch (e: Exception) {
            terminal.println(red("❌ Validation failed: ${e.message}"))
            if (detailed) {
                e.printStackTrace()
            }
            throw e
        }
    }
    
    private fun validateSyntax(terminal: Terminal) {
        val content = script.readText()
        val lines = content.lines()
        
        terminal.println(green("✅ Syntax Validation"))
        terminal.println("${dim("File:")} ${script.absolutePath}")
        terminal.println("${dim("Size:")} ${script.length()} bytes (${lines.size} lines)")
        terminal.println()
        
        // Basic syntax checks
        var errors = 0
        var warnings = 0
        
        if (!content.contains("pipeline") && !content.contains("stages")) {
            terminal.println(yellow("⚠️  Warning: No pipeline or stages block found"))
            warnings++
        }
        
        if (content.contains("TODO") || content.contains("FIXME")) {
            terminal.println(yellow("⚠️  Warning: Contains TODO/FIXME comments"))
            warnings++
        }
        
        val unclosedBraces = content.count { it == '{' } - content.count { it == '}' }
        if (unclosedBraces != 0) {
            terminal.println(red("❌ Error: Unmatched braces (${if (unclosedBraces > 0) "missing ${unclosedBraces} closing" else "extra ${-unclosedBraces} closing"} braces)"))
            errors++
        }
        
        terminal.println()
        if (errors == 0) {
            terminal.println(green("✅ Syntax validation passed"))
        } else {
            terminal.println(red("❌ Found ${errors} syntax error(s)"))
        }
        
        if (warnings > 0) {
            terminal.println(yellow("⚠️  Found ${warnings} warning(s)"))
        }
    }
    
    private fun validatePipeline(terminal: Terminal) {
        terminal.println(green("✅ Full Pipeline Validation"))
        terminal.println()
        
        // First do syntax validation
        validateSyntax(terminal)
        terminal.println()
        
        // Advanced DSL validation
        try {
            terminal.println(cyan("🔧 Running advanced validation..."))
            
            val content = script.readText()
            
            // Check for basic pipeline structure
            val hasPipelineBlock = content.contains(Regex("pipeline\\s*\\{"))
            val hasStagesBlock = content.contains(Regex("stages\\s*\\{"))
            val hasAgentBlock = content.contains(Regex("agent\\s*\\{"))
            
            if (hasPipelineBlock) {
                terminal.println(green("✅ Pipeline block found"))
                
                if (hasStagesBlock) {
                    terminal.println(green("✅ Stages block found"))
                } else {
                    terminal.println(yellow("⚠️  Warning: No stages block found"))
                }
                
                if (hasAgentBlock) {
                    terminal.println(green("✅ Agent configuration found"))
                } else {
                    terminal.println(yellow("⚠️  Warning: No agent configuration found"))
                }
                
                // Count stages
                val stageMatches = Regex("stage\\s*\\(").findAll(content).count()
                if (stageMatches > 0) {
                    terminal.println(green("✅ Found $stageMatches stage(s)"))
                } else {
                    terminal.println(yellow("⚠️  Warning: No stages defined"))
                }
                
                terminal.println(green("✅ Pipeline structure validation passed"))
                
            } else {
                // Check if it's a simple Kotlin script
                if (content.contains("fun main") || content.contains("println")) {
                    terminal.println(yellow("ℹ️  Appears to be a regular Kotlin script"))
                    terminal.println(dim("Consider using 'pipeline' block for better structure"))
                } else {
                    terminal.println(red("❌ No recognizable pipeline or script structure found"))
                }
            }
            
        } catch (e: Exception) {
            terminal.println(red("❌ Advanced validation failed: ${e.message}"))
            if (detailed) {
                terminal.println(dim("Full error:"))
                e.printStackTrace()
            }
        }
    }
}

/**
 * List and manage pipeline scripts
 */
class ListCommand : CliktCommand(
    name = "list",
    help = "List pipeline scripts in current directory"
) {
    
    private val all by option(
        "--all", "-a",
        help = "Show all .kts files, not just .pipeline.kts"
    ).flag(default = false)
    
    private val detailed by option(
        "--detailed", "-d", 
        help = "Show detailed information about each pipeline"
    ).flag(default = false)
    
    override fun run() {
        val terminal = currentContext.terminal
        
        val currentDir = File(".")
        val pipelineFiles = currentDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                if (all) {
                    file.name.endsWith(".kts")
                } else {
                    file.name.endsWith(".pipeline.kts") || 
                    (file.name.endsWith(".kts") && file.readText().contains("pipeline"))
                }
            }
            .take(50)
            .toList()
            .sortedBy { it.name }
        
        if (pipelineFiles.isEmpty()) {
            terminal.println(yellow("ℹ️  No pipeline files found in current directory"))
            terminal.println(dim("Try using --all to see all .kts files"))
            return
        }
        
        terminal.println(cyan("📋 Pipeline Scripts Found: ${pipelineFiles.size}"))
        terminal.println()
        
        pipelineFiles.forEach { file ->
            terminal.println("📄 ${green(file.name)}")
            
            if (detailed) {
                terminal.println("   ${dim("Path:")} ${file.relativeTo(File(".")).path}")
                terminal.println("   ${dim("Size:")} ${file.length()} bytes")
                terminal.println("   ${dim("Modified:")} ${java.time.Instant.ofEpochMilli(file.lastModified())}")
                
                try {
                    val content = file.readText()
                    val lines = content.lines().size
                    val hasStages = content.contains("stages")
                    val hasAgent = content.contains("agent")
                    
                    terminal.println("   ${dim("Lines:")} $lines")
                    if (hasStages) terminal.println("   ${green("✓")} Has stages")
                    if (hasAgent) terminal.println("   ${green("✓")} Has agent configuration")
                    
                } catch (e: Exception) {
                    terminal.println("   ${red("❌")} Cannot read file: ${e.message}")
                }
                
                terminal.println()
            } else {
                terminal.println("   ${dim(file.relativeTo(File(".")).path)} (${file.length()} bytes)")
            }
        }
        
        if (!detailed) {
            terminal.println()
            terminal.println(dim("Use --detailed for more information about each file"))
        }
    }
}

/**
 * Clean up generated files and caches
 */
class CleanCommand : CliktCommand(
    name = "clean",
    help = "Clean up generated files and caches"
) {
    
    private val cache by option(
        "--cache",
        help = "Clean compilation cache only"
    ).flag(default = false)
    
    private val logs by option(
        "--logs", 
        help = "Clean log files only"
    ).flag(default = false)
    
    private val dryRun by option(
        "--dry-run", "-n",
        help = "Show what would be cleaned without actually doing it"
    ).flag(default = false)
    
    override fun run() {
        val terminal = currentContext.terminal
        
        terminal.println(cyan("🧹 Pipeline Cleanup"))
        terminal.println()
        
        val homeDir = System.getProperty("user.home")
        val pipelineHome = File("$homeDir/.pipeline")
        
        var cleanedFiles = 0
        var cleanedSize = 0L
        
        if (!cache && !logs) {
            // Clean everything
            cleanCache(terminal, pipelineHome, dryRun)?.let { (files, size) ->
                cleanedFiles += files
                cleanedSize += size
            }
            cleanLogs(terminal, pipelineHome, dryRun)?.let { (files, size) ->
                cleanedFiles += files
                cleanedSize += size
            }
            cleanTempFiles(terminal, dryRun)?.let { (files, size) ->
                cleanedFiles += files  
                cleanedSize += size
            }
        } else {
            if (cache) {
                cleanCache(terminal, pipelineHome, dryRun)?.let { (files, size) ->
                    cleanedFiles += files
                    cleanedSize += size
                }
            }
            if (logs) {
                cleanLogs(terminal, pipelineHome, dryRun)?.let { (files, size) ->
                    cleanedFiles += files
                    cleanedSize += size
                }
            }
        }
        
        terminal.println()
        if (dryRun) {
            terminal.println(yellow("🔍 Dry run completed"))
            terminal.println("Would clean $cleanedFiles files (${formatBytes(cleanedSize)})")
        } else {
            terminal.println(green("✅ Cleanup completed"))
            terminal.println("Cleaned $cleanedFiles files (${formatBytes(cleanedSize)})")
        }
    }
    
    private fun cleanCache(terminal: Terminal, pipelineHome: File, dryRun: Boolean): Pair<Int, Long>? {
        val cacheDir = File(pipelineHome, "cache")
        if (!cacheDir.exists()) {
            terminal.println(dim("  Cache directory not found"))
            return null
        }
        
        val cacheFiles = cacheDir.walkTopDown().filter { it.isFile }.toList()
        val totalSize = cacheFiles.sumOf { it.length() }
        
        terminal.println("📦 ${if (dryRun) "Would clean" else "Cleaning"} cache directory")
        terminal.println("   ${cacheFiles.size} files (${formatBytes(totalSize)})")
        
        if (!dryRun) {
            cacheFiles.forEach { it.delete() }
            cacheDir.deleteRecursively()
        }
        
        return cacheFiles.size to totalSize
    }
    
    private fun cleanLogs(terminal: Terminal, pipelineHome: File, dryRun: Boolean): Pair<Int, Long>? {
        val logsDir = File(pipelineHome, "logs")
        if (!logsDir.exists()) {
            terminal.println(dim("  Logs directory not found"))
            return null
        }
        
        val logFiles = logsDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".txt")) }
            .toList()
        val totalSize = logFiles.sumOf { it.length() }
        
        terminal.println("📝 ${if (dryRun) "Would clean" else "Cleaning"} log files")
        terminal.println("   ${logFiles.size} files (${formatBytes(totalSize)})")
        
        if (!dryRun) {
            logFiles.forEach { it.delete() }
        }
        
        return logFiles.size to totalSize
    }
    
    private fun cleanTempFiles(terminal: Terminal, dryRun: Boolean): Pair<Int, Long>? {
        val tempFiles = File(".").walkTopDown()
            .filter { it.isFile }
            .filter { 
                it.name.startsWith(".pipeline-") || 
                it.name.endsWith(".tmp") ||
                it.name.endsWith(".bak")
            }
            .toList()
            
        if (tempFiles.isEmpty()) {
            terminal.println(dim("  No temporary files found"))
            return null
        }
        
        val totalSize = tempFiles.sumOf { it.length() }
        
        terminal.println("🗑️  ${if (dryRun) "Would clean" else "Cleaning"} temporary files")
        terminal.println("   ${tempFiles.size} files (${formatBytes(totalSize)})")
        
        if (!dryRun) {
            tempFiles.forEach { it.delete() }
        }
        
        return tempFiles.size to totalSize
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            else -> "${bytes / (1024 * 1024 * 1024)}GB"
        }
    }
}