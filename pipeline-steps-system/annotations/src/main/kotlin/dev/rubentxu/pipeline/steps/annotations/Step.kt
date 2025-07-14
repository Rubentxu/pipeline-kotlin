package dev.rubentxu.pipeline.steps.annotations

/**
 * Marks a function as a pipeline step that should have PipelineContext automatically injected.
 * 
 * Similar to @Composable in Jetpack Compose, functions marked with @Step:
 * 1. Automatically receive a PipelineContext as the first parameter (injected by compiler plugin)
 * 2. Can only be called from within other @Step functions or from StepsBlock context
 * 3. Are automatically registered in the step system for discovery
 * 
 * Example:
 * ```kotlin
 * @Step
 * fun myCustomStep(message: String) {
 *     // PipelineContext is automatically available
 *     echo("Custom: $message")
 *     sh("echo $message")
 * }
 * ```
 * 
 * @param name The step name for registration. If empty, uses the function name
 * @param description Human-readable description of what this step does
 * @param category Logical grouping for organization and discovery
 * @param securityLevel Security isolation level for third-party steps
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class Step(
    val name: String = "",
    val description: String = "",
    val category: StepCategory = StepCategory.GENERAL,
    val securityLevel: SecurityLevel = SecurityLevel.RESTRICTED
)

/**
 * Categories for organizing steps logically
 */
enum class StepCategory {
    GENERAL,
    SCM,           // Source control management (git, checkout, etc.)
    BUILD,         // Build tools (sh, gradle, maven, etc.)
    TEST,          // Testing tools (junit, test runners, etc.)
    DEPLOY,        // Deployment tools (docker, k8s, cloud providers)
    SECURITY,      // Security scanning, credentials, etc.
    UTIL,          // Utility functions (echo, readFile, writeFile)
    NOTIFICATION   // Notifications (slack, email, etc.)
}

/**
 * Security levels for step execution sandbox
 */
enum class SecurityLevel {
    /**
     * Trusted steps from core pipeline system.
     * Run with full access to PipelineContext capabilities.
     */
    TRUSTED,
    
    /**
     * Restricted third-party steps (default).
     * Run with limited access, resource limits enforced.
     */
    RESTRICTED,
    
    /**
     * Fully isolated steps for untrusted code.
     * Run in maximum sandbox with minimal capabilities.
     */
    ISOLATED
}