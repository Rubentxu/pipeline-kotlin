package examples

import dev.rubentxu.pipeline.dsl.pipeline
import dev.rubentxu.pipeline.context.LocalPipelineContext
import dev.rubentxu.pipeline.annotations.*

/**
 * Comprehensive examples demonstrating the new @Step system.
 * 
 * These examples showcase:
 * - Basic @Step usage with automatic context injection
 * - Custom @Step functions with different security levels
 * - Error handling and validation
 * - Step composition and reusability
 * - Integration with built-in and extension steps
 */

// Example 1: Basic Build Pipeline with @Step functions
fun basicBuildPipeline() = pipeline {
    stages {
        stage("Checkout") {
            steps {
                // Built-in @Step function with automatic context injection
                checkout(
                    url = "https://github.com/example/myapp.git",
                    branch = "main"
                )
            }
        }
        
        stage("Build") {
            steps {
                echo("Starting build process...")
                
                // Use built-in @Step functions
                sh("./gradlew clean build")
                
                // Custom @Step function (defined below)
                validateBuildArtifacts()
                
                echo("Build completed successfully!")
            }
        }
        
        stage("Test") {
            steps {
                echo("Running tests...")
                
                // Extension @Step from TestingSteps
                junitTest()
                
                // Custom testing step
                runIntegrationTests()
            }
        }
        
        stage("Package") {
            steps {
                // Extension @Step from DockerSteps
                dockerBuild("myapp:latest")
                dockerPush("registry.example.com/myapp:latest")
            }
        }
    }
}

// Example 2: Complex Deployment Pipeline with Error Handling
fun deploymentPipeline() = pipeline {
    environment {
        env["DEPLOYMENT_ENV"] = "production"
        env["APP_VERSION"] = "1.2.3"
    }
    
    stages {
        stage("Pre-deployment Checks") {
            steps {
                echo("Performing pre-deployment validation...")
                
                // Custom validation step
                validateDeploymentEnvironment()
                
                // Built-in retry with exponential backoff
                retry(maxRetries = 3) {
                    checkExternalDependencies()
                }
            }
        }
        
        stage("Deploy to Staging") {
            steps {
                deployToEnvironment("staging")
                
                // Custom health check
                waitForHealthCheck("staging")
            }
        }
        
        stage("Deploy to Production") {
            steps {
                // User input step (built-in)
                input(
                    message = "Proceed with production deployment?",
                    parameters = mapOf("choice" to listOf("yes", "no"))
                )
                
                deployToEnvironment("production")
                waitForHealthCheck("production")
                
                echo("Deployment to production completed!")
            }
        }
        
        stage("Post-deployment") {
            steps {
                runSmokeTests()
                notifyTeam("Deployment successful!")
            }
        }
    }
}

// Example 3: Parallel Processing Pipeline
fun parallelProcessingPipeline() = pipeline {
    stages {
        stage("Prepare") {
            steps {
                checkout(url = "https://github.com/example/data-processor.git")
                echo("Setting up parallel processing...")
            }
        }
        
        stage("Process Data") {
            steps {
                // Execute multiple steps in parallel
                parallelSteps(
                    "process-batch-1" to { processBatch("batch1", 1, 1000) },
                    "process-batch-2" to { processBatch("batch2", 1001, 2000) },
                    "process-batch-3" to { processBatch("batch3", 2001, 3000) }
                )
                
                echo("All batches processed successfully!")
            }
        }
        
        stage("Aggregate Results") {
            steps {
                aggregateResults(listOf("batch1", "batch2", "batch3"))
                generateReport()
            }
        }
    }
}

// Example 4: Multi-Environment CI/CD Pipeline
fun multiEnvironmentPipeline() = pipeline {
    stages {
        stage("Build") {
            steps {
                buildApplication()
                runUnitTests()
                createDockerImage()
            }
        }
        
        stage("Deploy to Development") {
            steps {
                deployToKubernetes("development", "myapp:latest")
                runAPITests("https://api-dev.example.com")
            }
        }
        
        stage("Deploy to QA") {
            steps {
                deployToKubernetes("qa", "myapp:latest")
                runAPITests("https://api-qa.example.com")
                runPerformanceTests("https://api-qa.example.com")
            }
        }
        
        stage("Deploy to Production") {
            steps {
                // Security scan before production
                runSecurityScan("myapp:latest")
                
                deployToKubernetes("production", "myapp:latest")
                runAPITests("https://api.example.com")
                
                // Custom monitoring setup
                setupMonitoring("production")
            }
        }
    }
}

// Custom @Step Functions

/**
 * Validates that build artifacts were created successfully
 */
@Step(
    name = "validateBuildArtifacts",
    description = "Validates that all required build artifacts are present",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun validateBuildArtifacts() {
    val context = LocalPipelineContext.current
    
    val requiredArtifacts = listOf(
        "build/libs/myapp.jar",
        "build/distributions/myapp.tar",
        "build/reports/tests/test/index.html"
    )
    
    context.logger.info("Validating build artifacts...")
    
    for (artifact in requiredArtifacts) {
        if (!context.fileExists(artifact)) {
            throw RuntimeException("Required artifact not found: $artifact")
        }
        context.logger.info("✓ Found: $artifact")
    }
    
    context.logger.info("All build artifacts validated successfully!")
}

/**
 * Runs integration tests with proper setup and teardown
 */
@Step(
    name = "runIntegrationTests",
    description = "Runs integration tests with database and service setup",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun runIntegrationTests() {
    val context = LocalPipelineContext.current
    
    try {
        context.logger.info("Setting up integration test environment...")
        
        // Start test database
        context.executeShell("docker-compose -f docker-compose.test.yml up -d postgres")
        
        // Wait for database to be ready
        context.executeShell("./scripts/wait-for-postgres.sh")
        
        // Run integration tests
        val result = context.executeShell("./gradlew integrationTest")
        
        if (!result.success) {
            throw RuntimeException("Integration tests failed: ${result.stderr}")
        }
        
        context.logger.info("Integration tests completed successfully!")
        
    } finally {
        // Cleanup
        context.logger.info("Cleaning up test environment...")
        context.executeShell("docker-compose -f docker-compose.test.yml down")
    }
}

/**
 * Validates deployment environment readiness
 */
@Step(
    name = "validateDeploymentEnvironment",
    description = "Validates that the target environment is ready for deployment",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun validateDeploymentEnvironment() {
    val context = LocalPipelineContext.current
    val env = context.environment["DEPLOYMENT_ENV"] 
        ?: throw IllegalArgumentException("DEPLOYMENT_ENV not set")
    
    context.logger.info("Validating deployment environment: $env")
    
    // Check Kubernetes cluster connectivity
    val clusterResult = context.executeShell("kubectl cluster-info")
    if (!clusterResult.success) {
        throw RuntimeException("Cannot connect to Kubernetes cluster")
    }
    
    // Check namespace exists
    val namespaceResult = context.executeShell("kubectl get namespace $env")
    if (!namespaceResult.success) {
        throw RuntimeException("Namespace '$env' does not exist")
    }
    
    // Check required secrets exist
    val secrets = listOf("app-secrets", "registry-credentials")
    for (secret in secrets) {
        val secretResult = context.executeShell("kubectl get secret $secret -n $env")
        if (!secretResult.success) {
            throw RuntimeException("Required secret '$secret' not found in namespace '$env'")
        }
    }
    
    context.logger.info("Environment validation completed successfully!")
}

/**
 * Checks external service dependencies
 */
@Step(
    name = "checkExternalDependencies",
    description = "Verifies that external services are accessible",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun checkExternalDependencies() {
    val context = LocalPipelineContext.current
    
    val dependencies = mapOf(
        "Database" to "postgresql://db.example.com:5432",
        "Redis" to "redis://cache.example.com:6379",
        "External API" to "https://api.external.com/health"
    )
    
    context.logger.info("Checking external dependencies...")
    
    for ((name, endpoint) in dependencies) {
        context.logger.info("Checking $name at $endpoint...")
        
        val result = when {
            endpoint.startsWith("https://") -> {
                context.executeShell("curl -f $endpoint")
            }
            endpoint.startsWith("postgresql://") -> {
                context.executeShell("pg_isready -h db.example.com -p 5432")
            }
            endpoint.startsWith("redis://") -> {
                context.executeShell("redis-cli -h cache.example.com ping")
            }
            else -> throw IllegalArgumentException("Unsupported endpoint type: $endpoint")
        }
        
        if (!result.success) {
            throw RuntimeException("$name is not accessible at $endpoint")
        }
        
        context.logger.info("✓ $name is accessible")
    }
    
    context.logger.info("All external dependencies are accessible!")
}

/**
 * Deploys application to specified environment
 */
@Step(
    name = "deployToEnvironment",
    description = "Deploys the application to the specified environment",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun deployToEnvironment(environment: String) {
    val context = LocalPipelineContext.current
    val version = context.environment["APP_VERSION"] ?: "latest"
    
    context.logger.info("Deploying version $version to $environment...")
    
    // Update deployment manifest with new version
    val manifestContent = context.readFile("k8s/deployment.yaml")
    val updatedManifest = manifestContent.replace("{{VERSION}}", version)
    context.writeFile("k8s/deployment-$environment.yaml", updatedManifest)
    
    // Apply the deployment
    val result = context.executeShell("kubectl apply -f k8s/deployment-$environment.yaml -n $environment")
    if (!result.success) {
        throw RuntimeException("Deployment failed: ${result.stderr}")
    }
    
    // Wait for rollout to complete
    val rolloutResult = context.executeShell(
        "kubectl rollout status deployment/myapp -n $environment --timeout=300s"
    )
    if (!rolloutResult.success) {
        throw RuntimeException("Deployment rollout failed: ${rolloutResult.stderr}")
    }
    
    context.logger.info("Deployment to $environment completed successfully!")
}

/**
 * Waits for health check to pass
 */
@Step(
    name = "waitForHealthCheck",
    description = "Waits for application health check to pass",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun waitForHealthCheck(environment: String) {
    val context = LocalPipelineContext.current
    
    val healthUrl = when (environment) {
        "staging" -> "https://api-staging.example.com/health"
        "production" -> "https://api.example.com/health"
        else -> throw IllegalArgumentException("Unknown environment: $environment")
    }
    
    context.logger.info("Waiting for health check at $healthUrl...")
    
    // Retry health check for up to 5 minutes
    var attempts = 0
    val maxAttempts = 30
    
    while (attempts < maxAttempts) {
        try {
            val result = context.executeShell("curl -f $healthUrl")
            if (result.success) {
                context.logger.info("Health check passed!")
                return
            }
        } catch (e: Exception) {
            context.logger.info("Health check attempt ${attempts + 1}/$maxAttempts failed, retrying...")
        }
        
        attempts++
        kotlinx.coroutines.delay(10000) // Wait 10 seconds between attempts
    }
    
    throw RuntimeException("Health check failed after $maxAttempts attempts")
}

/**
 * Processes a batch of data
 */
@Step(
    name = "processBatch",
    description = "Processes a batch of data records",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun processBatch(batchName: String, startId: Int, endId: Int) {
    val context = LocalPipelineContext.current
    
    context.logger.info("Processing batch $batchName (records $startId-$endId)...")
    
    // Create batch processing script
    val scriptContent = """
        #!/bin/bash
        echo "Processing records from $startId to $endId"
        ./data-processor --batch $batchName --start $startId --end $endId
        echo "Batch $batchName completed"
    """.trimIndent()
    
    context.writeFile("process-$batchName.sh", scriptContent)
    
    // Make script executable and run it
    context.executeShell("chmod +x process-$batchName.sh")
    val result = context.executeShell("./process-$batchName.sh")
    
    if (!result.success) {
        throw RuntimeException("Batch processing failed for $batchName: ${result.stderr}")
    }
    
    context.logger.info("Batch $batchName processed successfully!")
}

/**
 * Aggregates results from multiple batches
 */
@Step(
    name = "aggregateResults",
    description = "Aggregates results from processed batches",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun aggregateResults(batches: List<String>) {
    val context = LocalPipelineContext.current
    
    context.logger.info("Aggregating results from ${batches.size} batches...")
    
    // Collect all batch results
    val resultFiles = batches.map { "results-$it.json" }
    
    // Verify all result files exist
    for (file in resultFiles) {
        if (!context.fileExists(file)) {
            throw RuntimeException("Result file not found: $file")
        }
    }
    
    // Run aggregation script
    val aggregateCommand = "python aggregate-results.py ${resultFiles.joinToString(" ")}"
    val result = context.executeShell(aggregateCommand)
    
    if (!result.success) {
        throw RuntimeException("Result aggregation failed: ${result.stderr}")
    }
    
    context.logger.info("Results aggregated successfully!")
}

/**
 * Additional example steps demonstrating different patterns
 */

@Step(
    name = "buildApplication",
    description = "Builds the application with caching",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun buildApplication() {
    val context = LocalPipelineContext.current
    
    // Use remember for caching build information
    val buildInfo = context.remember("build-info") {
        mapOf(
            "timestamp" to System.currentTimeMillis(),
            "commit" to context.executeShell("git rev-parse HEAD").stdout.trim()
        )
    }
    
    context.logger.info("Building application (commit: ${buildInfo["commit"]})")
    
    context.executeShell("./gradlew build -x test")
    context.logger.info("Application built successfully!")
}

@Step(
    name = "runUnitTests",
    description = "Runs unit tests with coverage",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun runUnitTests() {
    val context = LocalPipelineContext.current
    
    context.logger.info("Running unit tests...")
    
    val result = context.executeShell("./gradlew test jacocoTestReport")
    if (!result.success) {
        throw RuntimeException("Unit tests failed")
    }
    
    // Check coverage threshold
    val coverageResult = context.executeShell("./gradlew jacocoTestCoverageVerification")
    if (!coverageResult.success) {
        context.logger.warn("Coverage threshold not met, but continuing...")
    }
    
    context.logger.info("Unit tests completed successfully!")
}

@Step(
    name = "createDockerImage",
    description = "Creates and tags Docker image",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun createDockerImage() {
    val context = LocalPipelineContext.current
    val version = context.environment["APP_VERSION"] ?: "latest"
    
    context.logger.info("Creating Docker image with tag: $version")
    
    context.executeShell("docker build -t myapp:$version .")
    context.executeShell("docker tag myapp:$version myapp:latest")
    
    context.logger.info("Docker image created successfully!")
}

@Step(
    name = "deployToKubernetes", 
    description = "Deploys application to Kubernetes",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun deployToKubernetes(namespace: String, image: String) {
    val context = LocalPipelineContext.current
    
    context.logger.info("Deploying $image to namespace $namespace")
    
    // Update deployment with new image
    context.executeShell("kubectl set image deployment/myapp myapp=$image -n $namespace")
    
    // Wait for rollout
    context.executeShell("kubectl rollout status deployment/myapp -n $namespace --timeout=300s")
    
    context.logger.info("Deployment to $namespace completed!")
}

// Additional utility steps...

@Step(
    name = "runAPITests",
    description = "Runs API tests against specified endpoint",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun runAPITests(baseUrl: String) {
    val context = LocalPipelineContext.current
    
    context.logger.info("Running API tests against $baseUrl")
    
    val result = context.executeShell("newman run api-tests.json --env-var baseUrl=$baseUrl")
    if (!result.success) {
        throw RuntimeException("API tests failed")
    }
    
    context.logger.info("API tests passed!")
}

@Step(
    name = "runPerformanceTests",
    description = "Runs performance tests",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun runPerformanceTests(baseUrl: String) {
    val context = LocalPipelineContext.current
    
    context.logger.info("Running performance tests against $baseUrl")
    
    val result = context.executeShell("ab -n 1000 -c 10 $baseUrl/api/health")
    if (!result.success) {
        throw RuntimeException("Performance tests failed")
    }
    
    context.logger.info("Performance tests completed!")
}

@Step(
    name = "runSecurityScan",
    description = "Runs security scan on Docker image",
    category = StepCategory.SECURITY,
    securityLevel = SecurityLevel.TRUSTED  // Security scanning needs elevated privileges
)
suspend fun runSecurityScan(image: String) {
    val context = LocalPipelineContext.current
    
    context.logger.info("Running security scan on $image")
    
    val result = context.executeShell("docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy image $image")
    if (!result.success) {
        throw RuntimeException("Security scan failed or found critical vulnerabilities")
    }
    
    context.logger.info("Security scan passed!")
}

@Step(
    name = "setupMonitoring",
    description = "Sets up monitoring for the environment",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun setupMonitoring(environment: String) {
    val context = LocalPipelineContext.current
    
    context.logger.info("Setting up monitoring for $environment")
    
    // Apply monitoring configuration
    context.executeShell("kubectl apply -f monitoring/prometheus-$environment.yaml")
    context.executeShell("kubectl apply -f monitoring/grafana-$environment.yaml")
    
    context.logger.info("Monitoring setup completed!")
}

@Step(
    name = "generateReport",
    description = "Generates processing report",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun generateReport() {
    val context = LocalPipelineContext.current
    
    context.logger.info("Generating processing report...")
    
    val reportContent = """
        # Processing Report
        
        Generated: ${java.time.Instant.now()}
        
        ## Summary
        - Total records processed: $(cat aggregated-results.json | jq '.total_records')
        - Processing time: $(cat aggregated-results.json | jq '.processing_time_ms')ms
        - Success rate: $(cat aggregated-results.json | jq '.success_rate')%
        
        ## Details
        See aggregated-results.json for full details.
    """.trimIndent()
    
    context.writeFile("processing-report.md", reportContent)
    context.logger.info("Report generated: processing-report.md")
}

@Step(
    name = "runSmokeTests",
    description = "Runs smoke tests after deployment",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun runSmokeTests() {
    val context = LocalPipelineContext.current
    
    context.logger.info("Running smoke tests...")
    
    val result = context.executeShell("newman run smoke-tests.json")
    if (!result.success) {
        throw RuntimeException("Smoke tests failed")
    }
    
    context.logger.info("Smoke tests passed!")
}

@Step(
    name = "notifyTeam",
    description = "Sends notification to team",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun notifyTeam(message: String) {
    val context = LocalPipelineContext.current
    
    context.logger.info("Sending notification: $message")
    
    // Send to Slack
    val slackMessage = """{"text": "$message"}"""
    context.executeShell("""curl -X POST -H 'Content-type: application/json' --data '$slackMessage' ${'$'}SLACK_WEBHOOK_URL""")
    
    context.logger.info("Notification sent!")
}