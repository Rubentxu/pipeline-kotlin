package dev.rubentxu.pipeline.steps.extensions

import dev.rubentxu.pipeline.context.PipelineContext
import dev.rubentxu.pipeline.steps.annotations.Step
import dev.rubentxu.pipeline.steps.annotations.StepCategory
import dev.rubentxu.pipeline.steps.annotations.SecurityLevel
import dev.rubentxu.pipeline.steps.builtin.*

/**
 * Testing-related @Step functions with DSL v2 syntax.
 * 
 * These steps provide comprehensive testing functionality with automatic PipelineContext injection
 * via the K2 compiler plugin, eliminating manual context access.
 */

/**
 * Runs JUnit tests using Gradle.
 * 
 * @param testTask Gradle test task name (default: "test")
 * @param testFilter Test filter pattern (optional)
 * @param profile Gradle profile to use (optional)
 * @param parallel Whether to run tests in parallel (default: true)
 * @param continueOnFailure Whether to continue on test failures (default: false)
 * @param generateReports Whether to generate test reports (default: true)
 * @return Test execution result summary
 */
@Step(
    name = "junitTest",
    description = "Run JUnit tests using Gradle",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun junitTest(
    context: PipelineContext,
    testTask: String = "test",
    testFilter: String? = null,
    profile: String? = null,
    parallel: Boolean = true,
    continueOnFailure: Boolean = false,
    generateReports: Boolean = true
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    context.logger.info("+ junitTest: $testTask")
    
    // Verify Gradle wrapper or Gradle is available
    val gradleCmd = if (fileExists(context, "gradlew")) {
        "./gradlew"
    } else {
        "gradle"
    }
    
    val cmd = mutableListOf(gradleCmd, testTask)
    
    testFilter?.let { cmd.addAll(listOf("--tests", it)) }
    profile?.let { cmd.addAll(listOf("-P$it")) }
    
    if (parallel) cmd.add("--parallel")
    if (continueOnFailure) cmd.add("--continue")
    if (generateReports) cmd.add("--info")
    
    // Add common test options
    cmd.addAll(listOf(
        "-Dorg.gradle.jvmargs=-Xmx2g",
        "--stacktrace"
    ))
    
    val command = cmd.joinToString(" ")
    
    try {
        val result = sh(context, command, returnStdout = true)
        context.logger.info("Tests completed successfully")
        return result
    } catch (e: Exception) {
        context.logger.error("Tests failed: ${e.message}")
        
        // Try to extract test results
        val testResultsPath = "build/reports/tests/test/index.html"
        if (fileExists(context, testResultsPath)) {
            context.logger.info("Test reports available at: $testResultsPath")
        }
        
        if (!continueOnFailure) {
            throw e
        }
        return "Tests failed but continuing due to continueOnFailure=true"
    }
}

/**
 * Runs Maven tests.
 * 
 * @param goals Maven goals to execute (default: "test")
 * @param profiles Maven profiles to activate (optional)
 * @param properties Map of Maven properties
 * @param skipTests Whether to skip tests (default: false)
 * @param failFast Whether to fail fast on first test failure (default: true)
 * @return Maven execution result
 */
@Step(
    name = "mavenTest",
    description = "Run Maven tests",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun mavenTest(
    context: PipelineContext,
    goals: String = "test",
    profiles: List<String> = emptyList(),
    properties: Map<String, String> = emptyMap(),
    skipTests: Boolean = false,
    failFast: Boolean = true
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    context.logger.info("+ mavenTest: $goals")
    
    // Verify Maven wrapper or Maven is available
    val mavenCmd = if (fileExists(context, "mvnw")) {
        "./mvnw"
    } else {
        "mvn"
    }
    
    val cmd = mutableListOf(mavenCmd, goals)
    
    if (profiles.isNotEmpty()) {
        cmd.addAll(listOf("-P", profiles.joinToString(",")))
    }
    
    properties.forEach { (key, value) ->
        cmd.add("-D$key=$value")
    }
    
    if (skipTests) cmd.add("-DskipTests")
    if (failFast) cmd.add("-Dmaven.test.failure.ignore=false")
    
    // Add common Maven options
    cmd.addAll(listOf(
        "-B", // Batch mode
        "--update-snapshots"
    ))
    
    val command = cmd.joinToString(" ")
    return sh(context, command, returnStdout = true)
}

/**
 * Runs integration tests with Docker Compose.
 * 
 * @param composeFile Docker Compose file path (default: "docker-compose.test.yml")
 * @param serviceName Service to run tests in (optional)
 * @param testCommand Command to run tests (default: derived from service)
 * @param environment Additional environment variables
 * @param timeout Timeout for tests in seconds (default: 600)
 * @return Integration test results
 */
@Step(
    name = "integrationTest",
    description = "Run integration tests with Docker Compose",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun integrationTest(
    context: PipelineContext,
    composeFile: String = "docker-compose.test.yml",
    serviceName: String? = null,
    testCommand: String? = null,
    environment: Map<String, String> = emptyMap(),
    timeout: Int = 600
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    context.logger.info("+ integrationTest: $composeFile")
    
    // Verify Docker Compose is available
    sh(context, "docker compose version")
    
    // Verify compose file exists
    if (!fileExists(context, composeFile)) {
        throw IllegalArgumentException("Docker Compose file not found: $composeFile")
    }
    
    try {
        // Set environment variables
        environment.forEach { (key, value) ->
            sh(context, "export $key='$value'")
        }
        
        // Start services
        context.logger.info("Starting test environment...")
        sh(context, "docker compose -f $composeFile up -d")
        
        // Wait for services to be ready
        sleep(context, 5000) // Wait 5 seconds for services to start
        
        // Run tests
        val result = if (serviceName != null) {
            val command = testCommand ?: "test"
            context.logger.info("Running tests in service: $serviceName")
            sh(context, "docker compose -f $composeFile exec -T $serviceName $command", returnStdout = true)
        } else {
            context.logger.info("Running tests with docker compose run")
            val testService = testCommand ?: "test"
            sh(context, "docker compose -f $composeFile run --rm $testService", returnStdout = true)
        }
        
        context.logger.info("Integration tests completed successfully")
        return result
        
    } finally {
        // Always cleanup
        try {
            context.logger.info("Cleaning up test environment...")
            sh(context, "docker compose -f $composeFile down -v")
        } catch (e: Exception) {
            context.logger.warn("Failed to cleanup Docker Compose: ${e.message}")
        }
    }
}

/**
 * Runs API tests using Newman (Postman CLI).
 * 
 * @param collectionPath Path to Postman collection file
 * @param environmentPath Path to Postman environment file (optional)
 * @param globalVars Map of global variables
 * @param iterations Number of iterations to run (default: 1)
 * @param delayRequest Delay between requests in ms (optional)
 * @param timeout Request timeout in ms (default: 30000)
 * @param reporters List of reporters to use (default: ["cli", "json"])
 * @return API test results
 */
@Step(
    name = "apiTest",
    description = "Run API tests using Newman (Postman CLI)",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun apiTest(
    context: PipelineContext,
    collectionPath: String,
    environmentPath: String? = null,
    globalVars: Map<String, String> = emptyMap(),
    iterations: Int = 1,
    delayRequest: Int? = null,
    timeout: Int = 30000,
    reporters: List<String> = listOf("cli", "json")
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(collectionPath.isNotBlank()) { "Collection path cannot be blank" }
    
    context.logger.info("+ apiTest: $collectionPath")
    
    // Verify Newman is available
    sh(context, "newman --version")
    
    // Verify collection exists
    if (!fileExists(context, collectionPath)) {
        throw IllegalArgumentException("Postman collection not found: $collectionPath")
    }
    
    val cmd = mutableListOf("newman run $collectionPath")
    
    environmentPath?.let { 
        if (fileExists(context, it)) {
            cmd.add("-e $it")
        } else {
            context.logger.warn("Environment file not found: $it")
        }
    }
    
    globalVars.forEach { (key, value) ->
        cmd.add("--global-var \"$key=$value\"")
    }
    
    cmd.add("-n $iterations")
    delayRequest?.let { cmd.add("--delay-request $it") }
    cmd.add("--timeout-request $timeout")
    
    if (reporters.isNotEmpty()) {
        cmd.add("-r ${reporters.joinToString(",")}")
    }
    
    // Set output directory for reports
    cmd.add("--reporter-json-export newman-results.json")
    cmd.add("--reporter-html-export newman-report.html")
    
    val command = cmd.joinToString(" ")
    val result = sh(context, command, returnStdout = true)
    
    context.logger.info("API tests completed. Reports generated: newman-results.json, newman-report.html")
    return result
}

/**
 * Runs performance tests using Apache Bench (ab).
 * 
 * @param url Target URL to test
 * @param requests Total number of requests (default: 1000)
 * @param concurrency Number of concurrent requests (default: 10)
 * @param timelimit Time limit for testing in seconds (optional)
 * @param headers Map of HTTP headers to send
 * @param postData Data to POST (optional)
 * @param contentType Content type for POST data (default: "application/json")
 * @return Performance test results
 */
@Step(
    name = "performanceTest",
    description = "Run performance tests using Apache Bench",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun performanceTest(
    context: PipelineContext,
    url: String,
    requests: Int = 1000,
    concurrency: Int = 10,
    timelimit: Int? = null,
    headers: Map<String, String> = emptyMap(),
    postData: String? = null,
    contentType: String = "application/json"
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(url.isNotBlank()) { "URL cannot be blank" }
    require(requests > 0) { "Requests must be positive" }
    require(concurrency > 0) { "Concurrency must be positive" }
    
    context.logger.info("+ performanceTest: $url ($requests requests, $concurrency concurrent)")
    
    // Verify ab (Apache Bench) is available
    sh(context, "ab -V")
    
    val cmd = mutableListOf("ab")
    
    cmd.addAll(listOf("-n", requests.toString()))
    cmd.addAll(listOf("-c", concurrency.toString()))
    
    timelimit?.let { cmd.addAll(listOf("-t", it.toString())) }
    
    headers.forEach { (key, value) ->
        cmd.addAll(listOf("-H", "\"$key: $value\""))
    }
    
    postData?.let {
        // Write POST data to temporary file
        val postFile = "ab-post-data.tmp"
        writeFile(context, postFile, it)
        cmd.addAll(listOf("-T", contentType))
        cmd.addAll(listOf("-p", postFile))
    }
    
    // Output results to file
    cmd.addAll(listOf("-g", "performance-results.tsv"))
    cmd.add(url)
    
    try {
        val result = sh(context, cmd.joinToString(" "), returnStdout = true)
        
        context.logger.info("Performance test completed. Results saved to performance-results.tsv")
        return result
        
    } finally {
        // Cleanup temporary files
        postData?.let {
            deleteFile(context, "ab-post-data.tmp")
        }
    }
}

/**
 * Runs security tests using OWASP ZAP.
 * 
 * @param targetUrl Target URL to scan
 * @param scanType Type of scan ("baseline", "full", "api") (default: "baseline")
 * @param configFile ZAP configuration file (optional)
 * @param reportFormat Report format ("html", "json", "xml") (default: "html")
 * @param excludeUrls List of URLs to exclude from scan
 * @param timeout Scan timeout in minutes (default: 60)
 * @return Security scan results
 */
@Step(
    name = "securityTest",
    description = "Run security tests using OWASP ZAP",
    category = StepCategory.SECURITY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun securityTest(
    context: PipelineContext,
    targetUrl: String,
    scanType: String = "baseline",
    configFile: String? = null,
    reportFormat: String = "html",
    excludeUrls: List<String> = emptyList(),
    timeout: Int = 60
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(targetUrl.isNotBlank()) { "Target URL cannot be blank" }
    require(scanType in listOf("baseline", "full", "api")) { "Invalid scan type: $scanType" }
    
    context.logger.info("+ securityTest: $targetUrl ($scanType scan)")
    
    // Check if ZAP is available via Docker
    sh(context, "docker --version")
    
    val zapImage = "owasp/zap2docker-stable"
    val reportFile = "zap-report.$reportFormat"
    
    val cmd = mutableListOf(
        "docker run --rm",
        "-v \${PWD}:/zap/wrk/:rw",
        zapImage
    )
    
    when (scanType) {
        "baseline" -> cmd.add("zap-baseline.py")
        "full" -> cmd.add("zap-full-scan.py")
        "api" -> cmd.add("zap-api-scan.py")
    }
    
    cmd.addAll(listOf("-t", targetUrl))
    cmd.addAll(listOf("-f", reportFormat))
    cmd.addAll(listOf("-w", reportFile))
    
    configFile?.let { 
        if (fileExists(context, it)) {
            cmd.addAll(listOf("-c", it))
        }
    }
    
    excludeUrls.forEach { url ->
        cmd.addAll(listOf("-x", url))
    }
    
    // Set timeout
    cmd.addAll(listOf("-T", timeout.toString()))
    
    val command = cmd.joinToString(" ")
    
    try {
        val result = sh(context, command, returnStdout = true)
        
        context.logger.info("Security scan completed. Report saved to $reportFile")
        return result
        
    } catch (e: Exception) {
        context.logger.error("Security scan failed: ${e.message}")
        
        // ZAP returns non-zero exit codes for findings, check if report was generated
        if (fileExists(context, reportFile)) {
            context.logger.info("Security scan found issues. Report available at: $reportFile")
            return "Security scan completed with findings. Check $reportFile for details."
        } else {
            throw e
        }
    }
}

/**
 * Publishes test results to various reporting systems.
 * 
 * @param testResultsPath Path to test results (e.g., JUnit XML files)
 * @param format Test results format ("junit", "testng", "cucumber") (default: "junit")
 * @param reportTitle Title for the test report (optional)
 * @param allowEmptyResults Whether to allow empty test results (default: false)
 * @return Publishing result summary
 */
@Step(
    name = "publishTestResults",
    description = "Publish test results to reporting systems",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun publishTestResults(
    context: PipelineContext,
    testResultsPath: String,
    format: String = "junit",
    reportTitle: String? = null,
    allowEmptyResults: Boolean = false
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(testResultsPath.isNotBlank()) { "Test results path cannot be blank" }
    
    context.logger.info("+ publishTestResults: $testResultsPath ($format format)")
    
    // Check if test results exist
    val resultsFiles = listFiles(context, testResultsPath, recursive = true)
        .filter { it.endsWith(".xml") || it.endsWith(".json") }
    
    if (resultsFiles.isEmpty() && !allowEmptyResults) {
        throw IllegalArgumentException("No test result files found in: $testResultsPath")
    }
    
    context.logger.info("Found ${resultsFiles.size} test result files")
    
    // Create a summary report
    val summaryFile = "test-results-summary.md"
    val summary = buildString {
        appendLine("# Test Results Summary")
        appendLine()
        reportTitle?.let { 
            appendLine("**Report**: $it")
            appendLine()
        }
        appendLine("**Format**: $format")
        appendLine("**Results Path**: $testResultsPath")
        appendLine("**Files Found**: ${resultsFiles.size}")
        appendLine()
        appendLine("## Files")
        resultsFiles.forEach { file ->
            appendLine("- $file")
        }
        appendLine()
        appendLine("*Generated at: ${timestamp()}*")
    }
    
    writeFile(context, summaryFile, summary)
    
    context.logger.info("Test results summary generated: $summaryFile")
    
    return "Published ${resultsFiles.size} test result files. Summary: $summaryFile"
}