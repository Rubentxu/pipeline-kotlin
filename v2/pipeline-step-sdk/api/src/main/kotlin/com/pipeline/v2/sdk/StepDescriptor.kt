package com.pipeline.v2.sdk

/**
 * Widened Step & Plugin SDK v2 descriptor (16 fields, STEP_PLUGIN_SDK.md §5).
 *
 * The original M0-R3 fields (id, type, configRef) remain the FIRST 3 fields
 * with their original names aliased for backward-compat:
 *   - id          → stepId
 *   - type        → name
 *   - configRef   → configRef
 *
 * NEW fields (13) default to zero/empty values so M0-R5 HelloPipelineFixture
 * compiles unchanged:
 *   StepDescriptor(id = "hello-echo", type = "echo", configRef = "...")
 *   → StepDescriptor(stepId = "hello-echo", name = "echo", configRef = "...",
 *                    pluginId = "core", pluginVersion = "0.0.0",
 *                    apiVersion = "v1", executionLocation = WORKER,
 *                    inputSchema = "{}", outputSchema = "{}",
 *                    requiredCapabilities = emptyList(),
 *                    effects = emptyList(), replayPolicy = MEMOIZED,
 *                    idempotencyModel = "", timeoutModel = "",
 *                    jenkinsSurface = "", securityProfile = "",
 *                    deprecation = "")
 */
data class StepDescriptor(
    val stepId: String,                          // was: id
    val name: String,                            // was: type
    val configRef: String,                       // unchanged
    val pluginId: String = "core",
    val pluginVersion: String = "0.0.0",
    val apiVersion: String = "v1",
    val executionLocation: ExecutionLocation = ExecutionLocation.WORKER,
    val inputSchema: String = "{}",
    val outputSchema: String = "{}",
    val requiredCapabilities: List<String> = emptyList(),
    val effects: List<Effect> = emptyList(),
    val replayPolicy: ReplayPolicy = ReplayPolicy.MEMOIZED,
    val idempotencyModel: String = "",
    val timeoutModel: String = "",
    val jenkinsSurface: String = "",             // M2-R3 will populate
    val securityProfile: String = "",
    val deprecation: String = "",
)
