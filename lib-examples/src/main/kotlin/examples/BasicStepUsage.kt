package examples

import dev.rubentxu.pipeline.dsl.pipeline
import dev.rubentxu.pipeline.context.LocalPipelineContext
import dev.rubentxu.pipeline.steps.dsl.annotations.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

/**
 * Basic examples of the @Step system usage patterns.
 * 
 * This file demonstrates:
 * - Simple @Step function creation
 * - Using built-in @Step functions
 * - Parameter handling and validation
 * - Basic error handling
 * - Working with PipelineContext
 */

// Example 1: Simple Hello World Pipeline
fun helloWorldPipeline() = runBlocking { pipeline {
    stages {
        stage("Greet") {
            steps {
                // Built-in @Step function
                echo("Hello, World!")
                
                // Custom @Step function
                greetUser("Pipeline Developer")
            }
        }
    }
} }

// Example 2: File Processing Pipeline
fun fileProcessingPipeline() = runBlocking { pipeline {
    stages {
        stage("Setup") {
            steps {
                echo("Setting up file processing...")
                createSampleFile()
            }
        }
        
        stage("Process") {
            steps {
                processTextFile("sample.txt")
                validateFileOutput("processed-sample.txt")
            }
        }
        
        stage("Cleanup") {
            steps {
                cleanupFiles()
                echo("File processing completed!")
            }
        }
    }
} }

// Example 3: Simple Build Pipeline
fun simpleBuildPipeline() = runBlocking { pipeline {
    stages {
        stage("Build") {
            steps {
                echo("Starting build...")
                
                // Built-in shell execution
                sh("echo 'Compiling sources...'")
                sh("sleep 2")  // Simulate build time
                sh("echo 'Build completed!'")
                
                // Custom validation
                validateBuild()
            }
        }
        
        stage("Test") {
            steps {
                echo("Running tests...")
                runSimpleTests()
            }
        }
    }
} }

// Custom @Step Functions

/**
 * Greets a user with a personalized message
 */
@Step(
    name = "greetUser",
    description = "Greets a user with a personalized message",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun greetUser(userName: String) {
    require(userName.isNotBlank()) { "User name cannot be blank" }
    
    val context = LocalPipelineContext.current
    
    val greeting = when {
        userName.contains("Developer", ignoreCase = true) -> 
            "Hello, $userName! Welcome to the pipeline system."
        userName.contains("Admin", ignoreCase = true) -> 
            "Greetings, $userName. System ready for your commands."
        else -> 
            "Hi there, $userName! Hope you're having a great day."
    }
    
    context.logger.info(greeting)
}

/**
 * Creates a sample text file for processing
 */
@Step(
    name = "createSampleFile",
    description = "Creates a sample text file for demonstration",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun createSampleFile() {
    val context = LocalPipelineContext.current
    
    val sampleContent = """
        Line 1: This is a sample file
        Line 2: Created by the pipeline
        Line 3: For demonstration purposes
        Line 4: Processing will transform this
        Line 5: Into something useful
    """.trimIndent()
    
    context.writeFile("sample.txt", sampleContent)
    context.logger.info("Sample file created: sample.txt")
}

/**
 * Processes a text file by transforming its content
 */
@Step(
    name = "processTextFile",
    description = "Processes a text file by transforming its content",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun processTextFile(filePath: String) {
    require(filePath.isNotBlank()) { "File path cannot be blank" }
    
    val context = LocalPipelineContext.current
    
    // Check if file exists
    if (!context.fileExists(filePath)) {
        throw RuntimeException("File not found: $filePath")
    }
    
    context.logger.info("Processing file: $filePath")
    
    // Read the file
    val content = context.readFile(filePath)
    
    // Transform the content (example: add line numbers and convert to uppercase)
    val processedContent = content.lines()
        .mapIndexed { index, line -> "[${index + 1}] ${line.uppercase()}" }
        .joinToString("\n")
    
    // Write the processed content
    val outputPath = "processed-$filePath"
    context.writeFile(outputPath, processedContent)
    
    context.logger.info("File processed successfully. Output: $outputPath")
}

/**
 * Validates that a file was processed correctly
 */
@Step(
    name = "validateFileOutput",
    description = "Validates that file processing produced expected output",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun validateFileOutput(filePath: String) {
    val context = LocalPipelineContext.current
    
    if (!context.fileExists(filePath)) {
        throw RuntimeException("Processed file not found: $filePath")
    }
    
    val content = context.readFile(filePath)
    
    // Basic validation checks
    val lines = content.lines()
    
    if (lines.isEmpty()) {
        throw RuntimeException("Processed file is empty")
    }
    
    // Check that lines have expected format [number] CONTENT
    val hasCorrectFormat = lines.all { line ->
        line.matches(Regex("\\[\\d+\\] .*"))
    }
    
    if (!hasCorrectFormat) {
        throw RuntimeException("Processed file does not have expected format")
    }
    
    context.logger.info("File validation passed: $filePath (${lines.size} lines)")
}

/**
 * Cleans up temporary files
 */
@Step(
    name = "cleanupFiles",
    description = "Removes temporary files created during processing",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun cleanupFiles() {
    val context = LocalPipelineContext.current
    
    val filesToClean = listOf("sample.txt", "processed-sample.txt")
    
    for (file in filesToClean) {
        if (context.fileExists(file)) {
            // In a real implementation, this would use file system operations
            context.logger.info("Cleaning up file: $file")
            // context.deleteFile(file) // This method doesn't exist yet in our API
        }
    }
    
    context.logger.info("Cleanup completed")
}

/**
 * Validates build output
 */
@Step(
    name = "validateBuild",
    description = "Validates that the build completed successfully",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun validateBuild() {
    val context = LocalPipelineContext.current
    
    context.logger.info("Validating build output...")
    
    // Simulate build validation
    val buildSuccess = true // In real scenario, this would check actual build artifacts
    
    if (!buildSuccess) {
        throw RuntimeException("Build validation failed")
    }
    
    context.logger.info("Build validation passed!")
}

/**
 * Runs simple tests
 */
@Step(
    name = "runSimpleTests",
    description = "Runs a simple test suite",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun runSimpleTests() {
    val context = LocalPipelineContext.current
    
    context.logger.info("Running test suite...")
    
    // Simulate running tests
    val tests = listOf(
        "BasicFunctionalityTest",
        "ConfigurationTest", 
        "ValidationTest"
    )
    
    for (test in tests) {
        context.logger.info("Running $test...")
        // Simulate test execution time
        delay(500)
        context.logger.info("✓ $test passed")
    }
    
    context.logger.info("All tests passed!")
}

// Example 4: Working with Environment Variables
fun environmentVariableExample() = runBlocking { pipeline {
    environment {
        env["APP_NAME"] = "MyApplication"
        env["APP_VERSION"] = "1.0.0"
        env["BUILD_TYPE"] = "release"
    }
    
    stages {
        stage("Configure") {
            steps {
                configureApplication()
                displayConfiguration()
            }
        }
    }
} }

/**
 * Configures application based on environment variables
 */
@Step(
    name = "configureApplication",
    description = "Configures application using environment variables",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun configureApplication() {
    val context = LocalPipelineContext.current
    
    val appName = context.environment["APP_NAME"] ?: "DefaultApp"
    val appVersion = context.environment["APP_VERSION"] ?: "1.0.0"
    val buildType = context.environment["BUILD_TYPE"] ?: "debug"
    
    val configContent = """
        app.name=$appName
        app.version=$appVersion
        app.build.type=$buildType
        app.build.timestamp=${System.currentTimeMillis()}
    """.trimIndent()
    
    context.writeFile("application.properties", configContent)
    context.logger.info("Application configured with name: $appName, version: $appVersion")
}

/**
 * Displays current configuration
 */
@Step(
    name = "displayConfiguration",
    description = "Displays the current application configuration",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun displayConfiguration() {
    val context = LocalPipelineContext.current
    
    if (!context.fileExists("application.properties")) {
        context.logger.warn("Configuration file not found")
        return
    }
    
    val config = context.readFile("application.properties")
    
    context.logger.info("Current Configuration:")
    config.lines().forEach { line ->
        if (line.trim().isNotEmpty() && !line.startsWith("#")) {
            context.logger.info("  $line")
        }
    }
}

// Example 5: Error Handling and Retry Logic
fun errorHandlingExample() = runBlocking { pipeline {
    stages {
        stage("Resilient Operations") {
            steps {
                echo("Demonstrating error handling...")
                
                // Built-in retry step
                retry(maxRetries = 3) {
                    unreliableOperation()
                }
                
                // Custom retry with validation
                retryWithValidation()
            }
        }
    }
} }

/**
 * Simulates an unreliable operation that may fail
 */
@Step(
    name = "unreliableOperation",
    description = "Simulates an operation that may fail randomly",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun unreliableOperation() {
    val context = LocalPipelineContext.current
    
    // Simulate random failure (30% chance)
    val success = (1..10).random() > 3
    
    if (!success) {
        throw RuntimeException("Simulated random failure")
    }
    
    context.logger.info("Operation completed successfully!")
}

/**
 * Demonstrates custom retry logic with validation
 */
@Step(
    name = "retryWithValidation",
    description = "Demonstrates custom retry logic with validation",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun retryWithValidation() {
    val context = LocalPipelineContext.current
    
    var attempts = 0
    val maxAttempts = 3
    
    while (attempts < maxAttempts) {
        try {
            attempts++
            context.logger.info("Attempt $attempts/$maxAttempts")
            
            // Simulate some operation
            val result = performValidatedOperation()
            
            if (result.isValid()) {
                context.logger.info("Operation succeeded after $attempts attempts")
                return
            } else {
                throw RuntimeException("Validation failed: ${result.errorMessage}")
            }
            
        } catch (e: Exception) {
            if (attempts >= maxAttempts) {
                throw RuntimeException("Operation failed after $maxAttempts attempts", e)
            }
            
            context.logger.warn("Attempt $attempts failed: ${e.message}, retrying...")
            delay(1000 * attempts) // Exponential backoff
        }
    }
}

/**
 * Data class for operation results
 */
data class OperationResult(
    val success: Boolean,
    val data: String? = null,
    val errorMessage: String? = null
) {
    fun isValid(): Boolean = success && data != null
}

/**
 * Performs an operation that returns a validated result
 */
private suspend fun performValidatedOperation(): OperationResult {
    // Simulate operation with 70% success rate
    val success = (1..10).random() > 3
    
    return if (success) {
        OperationResult(success = true, data = "Operation data")
    } else {
        OperationResult(success = false, errorMessage = "Random validation failure")
    }
}