package com.pipeline.v2.domain

/**
 * Domain step descriptor (widened to 16 fields).
 *
 * Original M0-R3 fields (id, type, configRef) remain at positions 1-3
 * with their original names for backward compatibility with HelloPipelineFixture.
 * NEW fields (13) default to zero/empty values.
 */
data class StepDescriptor(
    val id: String,
    val type: String,
    val configRef: String,
    val pluginId: String = "core",
    val pluginVersion: String = "0.0.0",
    val apiVersion: String = "v1",
    val executionLocation: String = "WORKER",
    val inputSchema: String = "{}",
    val outputSchema: String = "{}",
    val requiredCapabilities: List<String> = emptyList(),
    val effects: List<String> = emptyList(),
    val replayPolicy: String = "MEMOIZED",
    val idempotencyModel: String = "",
    val timeoutModel: String = "",
    val jenkinsSurface: String = "",
    val securityProfile: String = "",
    val deprecation: String = "",
)
