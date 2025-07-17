package examples

import dev.rubentxu.pipeline.dsl.pipeline
import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.context.LocalPipelineContext
import dev.rubentxu.pipeline.annotations.*

/**
 * Migration examples showing how to convert from extension functions to @Step system.
 * 
 * This file demonstrates:
 * - Before/after comparisons
 * - Migration patterns
 * - Best practices for @Step conversion
 * - Connascence improvements
 */

// ================================
// BEFORE: Extension Function Style (DEPRECATED)
// ================================

/*
// This is the OLD way - DO NOT USE in new code

// Extension function (deprecated approach)
fun StepsBlock.deployToProduction(version: String, environment: String = "prod") {
    echo("Deploying version $version to $environment")
    
    // Tightly coupled to StepsBlock
    sh("kubectl set image deployment/myapp myapp:$version -n $environment")
    sh("kubectl rollout status deployment/myapp -n $environment")
    
    // Hard to test, no security isolation
    echo("Deployment completed")
}

// Usage with extension functions
fun oldStylePipeline() = pipeline {
    stages {
        stage("Deploy") {
            steps {
                // Extension function call - deprecated
                deployToProduction("1.2.3", "production")
            }
        }
    }
}
*/

// ================================
// AFTER: @Step Function Style (RECOMMENDED)
// ================================

/**
 * Modern @Step function with improved connascence
 */
@Step(
    name = "deployToProduction",
    description = "Deploys application to production environment",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun deployToProduction(
    version: String,
    environment: String = "prod",
    timeout: Int = 300
) {
    require(version.isNotBlank()) { "Version cannot be blank" }
    require(environment.isNotBlank()) { "Environment cannot be blank" }
    require(timeout > 0) { "Timeout must be positive" }
    
    val context = LocalPipelineContext.current
    
    context.logger.info("Deploying version $version to $environment")
    
    // Better error handling and validation
    val deployResult = context.executeShell(
        "kubectl set image deployment/myapp myapp:$version -n $environment"
    )
    
    if (!deployResult.success) {
        throw RuntimeException("Deployment failed: ${deployResult.stderr}")
    }
    
    // Wait for rollout with timeout
    val rolloutResult = context.executeShell(
        "kubectl rollout status deployment/myapp -n $environment --timeout=${timeout}s"
    )
    
    if (!rolloutResult.success) {
        throw RuntimeException("Rollout failed: ${rolloutResult.stderr}")
    }
    
    context.logger.info("Deployment completed successfully")
}

// Usage with @Step functions
fun modernStylePipeline() = pipeline {
    stages {
        stage("Deploy") {
            steps {
                // @Step function call - modern approach
                deployToProduction("1.2.3", "production")
            }
        }
    }
}

// ================================
// CONNASCENCE IMPROVEMENTS EXAMPLES
// ================================

// BEFORE: High Connascence of Position (CoP)
/*
fun StepsBlock.buildAndTest(
    sourceDir: String,
    outputDir: String, 
    testDir: String,
    configFile: String,
    logLevel: String,
    parallel: Boolean
) {
    // Many positional parameters - high CoP
    // Hard to remember order, easy to make mistakes
}
*/

// AFTER: Lower Connascence with Data Classes
data class BuildConfiguration(
    val sourceDir: String = "src",
    val outputDir: String = "build",
    val testDir: String = "test", 
    val configFile: String = "build.gradle.kts",
    val logLevel: String = "INFO",
    val parallel: Boolean = true
) {
    fun validate() {
        require(sourceDir.isNotBlank()) { "Source directory cannot be blank" }
        require(outputDir.isNotBlank()) { "Output directory cannot be blank" }
        require(logLevel in listOf("DEBUG", "INFO", "WARN", "ERROR")) { 
            "Invalid log level: $logLevel" 
        }
    }
}

@Step(
    name = "buildAndTest",
    description = "Builds and tests the application with configuration",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun buildAndTest(config: BuildConfiguration = BuildConfiguration()) {
    config.validate()
    
    val context = LocalPipelineContext.current
    
    context.logger.info("Building with configuration: $config")
    
    // Implementation with validated configuration
    val buildCommand = buildString {
        append("./gradlew")
        append(" --build-dir ${config.outputDir}")
        append(" --log-level ${config.logLevel}")
        if (config.parallel) append(" --parallel")
        append(" build test")
    }
    
    val result = context.executeShell(buildCommand)
    if (!result.success) {
        throw RuntimeException("Build failed: ${result.stderr}")
    }
}

// BEFORE: High Connascence of Algorithm (CoA)
/*
fun StepsBlock.processFiles() {
    // Repeated algorithm pattern
    val files = listFiles()
    for (file in files) {
        if (file.endsWith(".txt")) {
            processTextFile(file)
        }
    }
}

fun StepsBlock.processImages() {
    // Same algorithm pattern repeated
    val files = listFiles()
    for (file in files) {
        if (file.endsWith(".png") || file.endsWith(".jpg")) {
            processImageFile(file)
        }
    }
}
*/

// AFTER: Lower Connascence with Strategy Pattern
enum class FileType(val extensions: List<String>, val processor: String) {
    TEXT(listOf("txt", "md", "log"), "processTextFile"),
    IMAGE(listOf("png", "jpg", "jpeg", "gif"), "processImageFile"),
    DATA(listOf("json", "xml", "csv"), "processDataFile")
}

@Step(
    name = "processFilesByType",
    description = "Processes files by type using configurable strategy",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun processFilesByType(
    fileType: FileType,
    directory: String = ".",
    recursive: Boolean = false
) {
    val context = LocalPipelineContext.current
    
    context.logger.info("Processing ${fileType.name} files in $directory")
    
    // Get files using a more sophisticated approach
    val files = getFilesOfType(directory, fileType.extensions, recursive)
    
    if (files.isEmpty()) {
        context.logger.info("No ${fileType.name} files found")
        return
    }
    
    for (file in files) {
        try {
            processFile(file, fileType)
            context.logger.info("✓ Processed: $file")
        } catch (e: Exception) {
            context.logger.error("✗ Failed to process $file: ${e.message}")
            throw e
        }
    }
    
    context.logger.info("Processed ${files.size} ${fileType.name} files")
}

// Helper functions with better separation of concerns
private suspend fun getFilesOfType(
    directory: String, 
    extensions: List<String>, 
    recursive: Boolean
): List<String> {
    val context = LocalPipelineContext.current
    
    val findCommand = if (recursive) {
        "find $directory -type f \\( ${extensions.joinToString(" -o ") { "-name \"*.$it\"" }} \\)"
    } else {
        "ls $directory/*.{${extensions.joinToString(",")}} 2>/dev/null || true"
    }
    
    val result = context.executeShell(findCommand)
    return result.stdout.lines().filter { it.isNotBlank() }
}

private suspend fun processFile(filePath: String, fileType: FileType) {
    val context = LocalPipelineContext.current
    
    when (fileType) {
        FileType.TEXT -> processTextFile(filePath)
        FileType.IMAGE -> processImageFile(filePath)  
        FileType.DATA -> processDataFile(filePath)
    }
}

// Individual processing functions with clear responsibilities
@Step(
    name = "processTextFile",
    description = "Processes a text file",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun processTextFile(filePath: String) {
    val context = LocalPipelineContext.current
    context.logger.info("Processing text file: $filePath")
    // Implementation...
}

@Step(
    name = "processImageFile", 
    description = "Processes an image file",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun processImageFile(filePath: String) {
    val context = LocalPipelineContext.current
    context.logger.info("Processing image file: $filePath")
    // Implementation...
}

@Step(
    name = "processDataFile",
    description = "Processes a data file",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun processDataFile(filePath: String) {
    val context = LocalPipelineContext.current
    context.logger.info("Processing data file: $filePath")
    // Implementation...
}

// ================================
// BEFORE/AFTER: State Management
// ================================

// BEFORE: Connascence of Value (CoV) with magic strings and global state
/*
class GlobalState {
    companion object {
        var buildNumber = 0
        var lastBuildTime = ""
        var artifacts = mutableListOf<String>()
    }
}

fun StepsBlock.incrementalBuild() {
    GlobalState.buildNumber++
    GlobalState.lastBuildTime = System.currentTimeMillis().toString()
    // High coupling to global state
}
*/

// AFTER: Better state management with context
data class BuildState(
    val buildNumber: Int,
    val lastBuildTime: Long,
    val artifacts: List<String> = emptyList()
) {
    fun nextBuild(newArtifacts: List<String> = emptyList()) = copy(
        buildNumber = buildNumber + 1,
        lastBuildTime = System.currentTimeMillis(),
        artifacts = artifacts + newArtifacts
    )
}

@Step(
    name = "incrementalBuild",
    description = "Performs incremental build with state management",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun incrementalBuild(previousState: BuildState? = null) {
    val context = LocalPipelineContext.current
    
    // Use remember for state persistence across pipeline runs
    val currentState = context.remember("build-state") {
        previousState ?: BuildState(buildNumber = 1, lastBuildTime = System.currentTimeMillis())
    }
    
    context.logger.info("Starting incremental build #${currentState.buildNumber}")
    
    // Perform build
    val newArtifacts = performBuild(currentState)
    
    // Update state
    val newState = currentState.nextBuild(newArtifacts)
    context.remember("build-state") { newState }
    
    context.logger.info("Build completed. New artifacts: ${newArtifacts.size}")
}

private suspend fun performBuild(state: BuildState): List<String> {
    val context = LocalPipelineContext.current
    
    // Check if incremental build is possible
    val lastModified = getLastModifiedTime()
    val canIncremental = lastModified < state.lastBuildTime
    
    return if (canIncremental) {
        context.logger.info("Performing incremental build")
        buildIncrementally(state.artifacts)
    } else {
        context.logger.info("Performing full build")
        buildFully()
    }
}

private suspend fun getLastModifiedTime(): Long {
    val context = LocalPipelineContext.current
    val result = context.executeShell("find src -type f -name '*.kt' -printf '%T@\\n' | sort -n | tail -1")
    return result.stdout.trim().toDoubleOrNull()?.toLong() ?: 0L
}

private suspend fun buildIncrementally(existingArtifacts: List<String>): List<String> {
    // Implementation for incremental build
    return listOf("incremental-artifact.jar")
}

private suspend fun buildFully(): List<String> {
    // Implementation for full build
    return listOf("full-artifact.jar", "documentation.jar")
}

// ================================
// MIGRATION EXAMPLES WITH TESTING
// ================================

// BEFORE: Hard to test extension functions
/*
fun StepsBlock.deployWithNotification(version: String) {
    sh("kubectl apply -f deployment.yaml")
    // Hard-coded notification - difficult to test
    sh("curl -X POST https://hooks.slack.com/...")
}
*/

// AFTER: Testable @Step functions with dependency injection
data class NotificationConfig(
    val slackWebhook: String,
    val emailRecipients: List<String> = emptyList(),
    val enabled: Boolean = true
)

@Step(
    name = "deployWithNotification",
    description = "Deploys application and sends notifications",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun deployWithNotification(
    version: String,
    notificationConfig: NotificationConfig
) {
    val context = LocalPipelineContext.current
    
    try {
        // Deploy
        context.logger.info("Deploying version $version")
        val deployResult = context.executeShell("kubectl apply -f deployment.yaml")
        
        if (!deployResult.success) {
            throw RuntimeException("Deployment failed: ${deployResult.stderr}")
        }
        
        // Send success notification
        if (notificationConfig.enabled) {
            sendNotification(
                message = "✅ Successfully deployed version $version",
                config = notificationConfig
            )
        }
        
    } catch (e: Exception) {
        // Send failure notification
        if (notificationConfig.enabled) {
            sendNotification(
                message = "❌ Failed to deploy version $version: ${e.message}",
                config = notificationConfig
            )
        }
        throw e
    }
}

@Step(
    name = "sendNotification",
    description = "Sends notification using configured channels",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun sendNotification(
    message: String,
    config: NotificationConfig
) {
    val context = LocalPipelineContext.current
    
    // Send Slack notification
    if (config.slackWebhook.isNotBlank()) {
        val slackPayload = """{"text": "$message"}"""
        val slackResult = context.executeShell(
            """curl -X POST -H 'Content-type: application/json' --data '$slackPayload' ${config.slackWebhook}"""
        )
        
        if (slackResult.success) {
            context.logger.info("Slack notification sent")
        } else {
            context.logger.warn("Failed to send Slack notification: ${slackResult.stderr}")
        }
    }
    
    // Send email notifications
    if (config.emailRecipients.isNotEmpty()) {
        for (recipient in config.emailRecipients) {
            sendEmailNotification(recipient, message)
        }
    }
}

private suspend fun sendEmailNotification(recipient: String, message: String) {
    val context = LocalPipelineContext.current
    
    // Implementation would depend on available email service
    context.logger.info("Email notification sent to $recipient")
}

// ================================
// EXAMPLE: Complete Migration
// ================================

// Complete example showing a complex pipeline migrated to @Step system
fun migratedComplexPipeline() = pipeline {
    environment {
        env["BUILD_ENV"] = "ci"
        env["NOTIFICATION_WEBHOOK"] = System.getenv("SLACK_WEBHOOK") ?: ""
    }
    
    stages {
        stage("Build") {
            steps {
                val buildConfig = BuildConfiguration(
                    sourceDir = "src",
                    outputDir = "build", 
                    parallel = true,
                    logLevel = "INFO"
                )
                
                buildAndTest(buildConfig)
                incrementalBuild()
            }
        }
        
        stage("Process Assets") {
            steps {
                // Process different file types in parallel
                parallelSteps(
                    "process-docs" to { processFilesByType(FileType.TEXT, "docs", recursive = true) },
                    "process-images" to { processFilesByType(FileType.IMAGE, "assets", recursive = true) },
                    "process-data" to { processFilesByType(FileType.DATA, "config", recursive = false) }
                )
            }
        }
        
        stage("Deploy") {
            steps {
                val notificationConfig = NotificationConfig(
                    slackWebhook = env["NOTIFICATION_WEBHOOK"] ?: "",
                    emailRecipients = listOf("team@example.com"),
                    enabled = env["BUILD_ENV"] == "ci"
                )
                
                deployWithNotification("1.2.3", notificationConfig)
            }
        }
    }
}

// This migration demonstrates:
// 1. Reduced Connascence of Position with data classes
// 2. Reduced Connascence of Algorithm with strategy patterns  
// 3. Better separation of concerns
// 4. Improved testability
// 5. Enhanced error handling
// 6. Type safety with validation
// 7. Security isolation with @Step annotations