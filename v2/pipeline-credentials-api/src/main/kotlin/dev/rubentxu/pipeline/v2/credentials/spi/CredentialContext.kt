package dev.rubentxu.pipeline.v2.credentials.spi

/**
 * Context data carrier for credential binding operations.
 *
 * Design:
 * - `runId`: identifies the pipeline run for audit trail
 * - `subject`: identifies the execution context (default "local")
 *
 * This is a data-only carrier with no logic. Multi-tenant scenarios where
 * credentials are scoped to specific subjects are deferred.
 *
 * ## Deferred scope
 * - Multi-tenant credential isolation per subject
 * - Credential scoping rules per runId
 * - Subject-based ACL enforcement
 */
data class CredentialContext(
    val runId: String,
    val subject: String = "local"
)
