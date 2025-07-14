package examples

import dev.rubentxu.pipeline.dsl.pipeline
import dev.rubentxu.pipeline.steps.dsl.annotations.*
import dev.rubentxu.pipeline.context.PipelineContext
import kotlinx.coroutines.runBlocking

/**
 * Examples demonstrating DSL v2 with K2 compiler plugin.
 * 
 * These examples show the "magic" syntax enabled by the K2 plugin where
 * @Step functions automatically receive PipelineContext without manual injection.
 * 
 * The plugin transforms:
 * ```
 * @Step suspend fun deployApp(version: String) {
 *     executeShell("kubectl apply...")  // Direct access!
 * }
 * ```
 * 
 * Into:
 * ```
 * @Step suspend fun deployApp($context: PipelineContext, version: String) {
 *     $context.executeShell("kubectl apply...")
 * }
 * ```
 */

// Example 1: Simple DSL v2 Pipeline
fun dslV2BasicPipeline() = runBlocking { pipeline {
    stages {
        stage("Build") {
            steps {
                echo("Starting build...")
                
                // @Step function with automatic context injection
                buildApplication()
                
                echo("Build completed!")
            }
        }
        
        stage("Deploy") {
            steps {
                // @Step function calls other @Step functions seamlessly
                deployToProduction("1.2.3")
            }
        }
    }
} }

// Example 2: Complex CI/CD Pipeline with DSL v2
fun dslV2ComplexPipeline() = runBlocking { pipeline {
    environment {
        env("APP_VERSION", "2.0.0")
        env("TARGET_ENV", "production")
    }
    
    stages {
        stage("Preparation") {
            steps {
                validateEnvironment()
                setupCredentials()
            }
        }
        
        stage("Build & Test") {
            steps {
                parallelSteps(
                    "build" to { buildWithCache() },
                    "lint" to { runLinting() },
                    "security-scan" to { runSecurityScan() }
                )
                
                runTestSuite()
                generateArtifacts()
            }
        }
        
        stage("Deploy") {
            steps {
                deployWithRollback("production", "2.0.0")
                runSmokeTests()
                notifyTeams("Deployment successful!")
            }
        }
    }
} }

// DSL v2 @Step Functions with Automatic Context Injection

/**
 * DSL v2: Build application with automatic context access
 * 
 * The K2 plugin automatically transforms this to include PipelineContext
 * as the first parameter and injects it at call sites.
 */
@Step(
    name = "buildApplication",
    description = "Builds the application using Gradle",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun PipelineContext.buildApplication() {
    // Direct access to pipeline operations - no LocalPipelineContext.current needed!
    logger.info("Starting application build...")
    
    // Check if build cache exists
    if (fileExists("build/cache")) {
        logger.info("Using existing build cache")
    } else {
        logger.info("Creating fresh build")
    }
    
    // Execute build command
    val result = sh("./gradlew clean build --parallel")
    
    if (!result.success) {
        throw RuntimeException("Build failed: ${result.stderr}")
    }
    
    // Validate build output
    if (!fileExists("build/libs/app.jar")) {
        throw RuntimeException("Build artifact not found")
    }
    
    logger.info("Build completed successfully!")
}

/**
 * DSL v2: Deploy to production with automatic rollback
 */
@Step(
    name = "deployToProduction", 
    description = "Deploy application to production with automatic rollback",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun PipelineContext.deployToProduction(version: String) {
    logger.info("Deploying version $version to production...")
    
    // Store current deployment for rollback
    val currentVersion = getCurrentDeployedVersion()
    
    try {
        // Update deployment
        sh("kubectl set image deployment/app app=myregistry/app:$version")
        
        // Wait for rollout
        sh("kubectl rollout status deployment/app --timeout=300s")
        
        // Verify deployment
        val healthCheck = sh("curl -f http://app.example.com/health")
        if (!healthCheck.success) {
            throw RuntimeException("Health check failed after deployment")
        }
        
        logger.info("Deployment successful!")
        
    } catch (e: Exception) {
        logger.error("Deployment failed, rolling back to $currentVersion")
        rollbackToPreviousVersion(currentVersion)
        throw e
    }
}

/**
 * DSL v2: Build with intelligent caching
 */
@Step(
    name = "buildWithCache",
    description = "Build with intelligent cache management",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun PipelineContext.buildWithCache() {
    logger.info("Building with cache optimization...")
    
    // Check cache validity
    val cacheKey = generateCacheKey()
    val cacheValid = validateCache(cacheKey)
    
    if (cacheValid) {
        logger.info("Using valid cache, skipping build")
        return
    }
    
    // Perform build
    sh("./gradlew build --build-cache")
    
    // Update cache metadata
    updateCacheMetadata(cacheKey)
    
    logger.info("Build with cache completed")
}

/**
 * DSL v2: Run comprehensive test suite
 */
@Step(
    name = "runTestSuite",
    description = "Run comprehensive test suite with reporting",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun PipelineContext.runTestSuite() {
    logger.info("Running comprehensive test suite...")
    
    try {
        // Unit tests
        sh("./gradlew test")
        
        // Integration tests
        sh("./gradlew integrationTest")
        
        // Generate coverage report
        sh("./gradlew jacocoTestReport")
        
        // Validate coverage threshold
        val coverage = extractCoveragePercentage()
        if (coverage < 80.0) {
            logger.warn("Coverage below threshold: ${coverage}%")
        }
        
        logger.info("All tests passed! Coverage: ${coverage}%")
        
    } catch (e: Exception) {
        // Archive test results even on failure
        archiveTestResults()
        throw e
    }
}

/**
 * DSL v2: Deploy with automatic rollback capability
 */
@Step(
    name = "deployWithRollback",
    description = "Deploy with automatic rollback on failure",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun PipelineContext.deployWithRollback(environment: String, version: String) {
    logger.info("Deploying $version to $environment with rollback capability...")
    
    val previousVersion = getCurrentDeployedVersion()
    
    try {
        // Create deployment backup
        createDeploymentSnapshot(environment)
        
        // Deploy new version
        sh("helm upgrade --install app ./charts/app --set image.tag=$version")
        
        // Wait for deployment
        sh("kubectl rollout status deployment/app -n $environment")
        
        // Run health checks
        runHealthChecks(environment)
        
        logger.info("Deployment to $environment successful!")
        
    } catch (e: Exception) {
        logger.error("Deployment failed, initiating rollback...")
        rollbackToPreviousVersion(previousVersion)
        
        // Verify rollback success
        runHealthChecks(environment)
        
        throw RuntimeException("Deployment failed and rolled back to $previousVersion", e)
    }
}

/**
 * DSL v2: Validate environment prerequisites
 */
@Step(
    name = "validateEnvironment",
    description = "Validate environment prerequisites for deployment",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun PipelineContext.validateEnvironment() {
    logger.info("Validating environment prerequisites...")
    
    // Check cluster connectivity
    val clusterInfo = sh("kubectl cluster-info")
    if (!clusterInfo.success) {
        throw RuntimeException("Cannot connect to Kubernetes cluster")
    }
    
    // Validate required secrets
    val requiredSecrets = listOf("app-secrets", "registry-credentials", "database-config")
    for (secret in requiredSecrets) {
        val secretCheck = sh("kubectl get secret $secret")
        if (!secretCheck.success) {
            throw RuntimeException("Required secret '$secret' not found")
        }
    }
    
    // Check resource quotas
    val resources = sh("kubectl describe quota")
    logger.info("Resource quotas validated")
    
    // Verify external dependencies
    validateExternalDependencies()
    
    logger.info("Environment validation completed successfully!")
}

/**
 * DSL v2: Setup deployment credentials
 */
@Step(
    name = "setupCredentials",
    description = "Setup deployment credentials and configurations",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED // Higher security for credential operations
)
suspend fun PipelineContext.setupCredentials() {
    logger.info("Setting up deployment credentials...")
    
    // Configure registry access
    sh("docker login -u \$REGISTRY_USER -p \$REGISTRY_PASS myregistry.com")
    
    // Setup kubectl context
    sh("kubectl config use-context production-cluster")
    
    // Verify access
    val accessCheck = sh("kubectl auth can-i create deployments")
    if (!accessCheck.success) {
        throw RuntimeException("Insufficient permissions for deployment")
    }
    
    logger.info("Credentials setup completed")
}

// Helper functions (would be @Step functions in real implementation)

private suspend fun PipelineContext.getCurrentDeployedVersion(): String {
    val result = sh("kubectl get deployment app -o jsonpath='{.spec.template.spec.containers[0].image}'")
    return result.stdout.substringAfterLast(":")
}

private suspend fun PipelineContext.rollbackToPreviousVersion(version: String) {
    sh("kubectl rollout undo deployment/app")
    sh("kubectl rollout status deployment/app")
}

private suspend fun PipelineContext.generateCacheKey(): String {
    val gitHash = sh("git rev-parse HEAD").stdout.trim()
    val buildFiles = sh("find . -name '*.gradle*' -exec md5sum {} \\;").stdout
    return "${gitHash}-${buildFiles.hashCode()}"
}

private suspend fun PipelineContext.validateCache(cacheKey: String): Boolean {
    return fileExists("build/cache/$cacheKey")
}

private suspend fun PipelineContext.updateCacheMetadata(cacheKey: String) {
    writeFile("build/cache/current", cacheKey)
}

private suspend fun PipelineContext.extractCoveragePercentage(): Double {
    val coverageReport = readFile("build/reports/jacoco/test/html/index.html")
    // Parse coverage percentage from HTML report
    return 85.5 // Simplified for example
}

private suspend fun PipelineContext.archiveTestResults() {
    sh("tar -czf test-results.tar.gz build/reports/tests/")
}

private suspend fun PipelineContext.createDeploymentSnapshot(environment: String) {
    sh("kubectl get all -n $environment -o yaml > deployment-snapshot.yaml")
}

private suspend fun PipelineContext.runHealthChecks(environment: String) {
    val endpoints = listOf("/health", "/metrics", "/ready")
    for (endpoint in endpoints) {
        val check = sh("curl -f http://app.$environment.example.com$endpoint")
        if (!check.success) {
            throw RuntimeException("Health check failed for $endpoint")
        }
    }
}

private suspend fun PipelineContext.validateExternalDependencies() {
    val dependencies = mapOf(
        "Database" to "pg_isready -h db.example.com",
        "Redis" to "redis-cli -h redis.example.com ping",
        "External API" to "curl -f https://api.external.com/health"
    )
    
    for ((name, command) in dependencies) {
        val result = sh(command)
        if (!result.success) {
            throw RuntimeException("$name dependency check failed")
        }
    }
}

@Step(
    name = "runLinting",
    description = "Run code linting and style checks",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun PipelineContext.runLinting() {
    logger.info("Running code linting...")
    sh("./gradlew ktlintCheck")
    sh("./gradlew detekt")
    logger.info("Linting completed successfully")
}

@Step(
    name = "runSecurityScan",
    description = "Run security vulnerability scan",
    category = StepCategory.SECURITY,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun PipelineContext.runSecurityScan() {
    logger.info("Running security scan...")
    sh("./gradlew dependencyCheckAnalyze")
    sh("docker run --rm -v \$(pwd):/code sonarqube/sonar-scanner")
    logger.info("Security scan completed")
}

@Step(
    name = "generateArtifacts",
    description = "Generate and archive build artifacts",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun PipelineContext.generateArtifacts() {
    logger.info("Generating build artifacts...")
    
    // Create distribution
    sh("./gradlew distTar")
    
    // Generate checksums
    sh("sha256sum build/distributions/*.tar > build/distributions/checksums.txt")
    
    // Archive artifacts
    sh("tar -czf artifacts.tar.gz build/distributions/")
    
    logger.info("Artifacts generated and archived")
}

@Step(
    name = "runSmokeTests",
    description = "Run post-deployment smoke tests",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun PipelineContext.runSmokeTests() {
    logger.info("Running smoke tests...")
    
    // API endpoint tests
    sh("newman run smoke-tests.postman_collection.json")
    
    // Performance baseline check
    sh("ab -n 100 -c 10 https://app.example.com/api/health")
    
    logger.info("Smoke tests passed!")
}

@Step(
    name = "notifyTeams",
    description = "Send notifications to development teams",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun PipelineContext.notifyTeams(message: String) {
    logger.info("Sending team notifications...")
    
    // Slack notification
    val slackPayload = """{"text": "$message"}"""
    sh("""curl -X POST -H 'Content-type: application/json' --data '$slackPayload' ${"$"}SLACK_WEBHOOK_URL""")
    
    // Email notification
    sh("""echo "$message" | mail -s "Pipeline Update" team@example.com""")
    
    logger.info("Notifications sent successfully")
}

/**
 * Comparison: DSL v1.5 vs DSL v2
 * 
 * This example shows the difference between manual context injection
 * and automatic injection via the K2 plugin.
 */

// DSL v1.5 (Current Implementation)
@Step suspend fun PipelineContext.deployAppV1_5(version: String) {
    val context = this  // Manual context access
    context.logger.info("Deploying $version...")
    context.sh("kubectl apply -f deployment.yaml")
}

// DSL v2 (With K2 Plugin)
@Step suspend fun PipelineContext.deployAppV2(version: String) {
    // No manual context access needed!
    logger.info("Deploying $version...")         // Direct access to logger
    sh("kubectl apply -f deployment.yaml")  // Direct access to sh
}

/**
 * Advanced DSL v2 Features
 * 
 * The K2 plugin enables advanced patterns like context propagation
 * through complex call chains and conditional step execution.
 */

@Step suspend fun PipelineContext.conditionalDeployment(environment: String, version: String) {
    when (environment) {
        "staging" -> {
            deployToStaging(version)
            runStagingTests()
        }
        "production" -> {
            validateProductionReadiness()
            deployToProduction(version)
            runProductionHealthChecks()
        }
        else -> {
            throw IllegalArgumentException("Unknown environment: $environment")
        }
    }
}

@Step suspend fun PipelineContext.deployToStaging(version: String) {
    logger.info("Deploying $version to staging...")
    sh("helm upgrade --install app-staging ./charts/app --set image.tag=$version")
}

@Step suspend fun PipelineContext.runStagingTests() {
    logger.info("Running staging tests...")
    sh("newman run staging-tests.json")
}

@Step suspend fun PipelineContext.validateProductionReadiness() {
    logger.info("Validating production readiness...")
    // Complex validation logic with direct context access
    if (!fileExists("production-approval.txt")) {
        throw RuntimeException("Production deployment not approved")
    }
}

@Step suspend fun PipelineContext.runProductionHealthChecks() {
    logger.info("Running production health checks...")
    retry(maxRetries = 3) {
        val health = sh("curl -f https://app.example.com/health")
        if (!health.success) {
            throw RuntimeException("Production health check failed")
        }
    }
}