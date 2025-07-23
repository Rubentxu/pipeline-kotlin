package dev.rubentxu.pipeline.annotations

/**
 * Mock StepCategory for compiler plugin testing
 * This mirrors the real StepCategory from plugin-annotations
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