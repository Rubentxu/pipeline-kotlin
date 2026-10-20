package dev.rubentxu.pipeline.v2.credentials.spi

/**
 * Context data carrier for credential binding operations.
 *
 * Design (design §2.2, research §4.4-proposed; gap G-9 multitenancy deferral to H1+):
 * - `runId`: identifies the pipeline run for audit trail
 * - `subject`: identifies the execution context (default "local")
 *
 * This is a data-only carrier with no logic. H1+ will extend this for
 * multi-tenant scenarios where credentials are scoped to specific subjects.
 *
 * ## H1+ deferred scope
 * - Multi-tenant credential isolation per subject
 * - Credential scoping rules per runId
 * - Subject-based ACL enforcement
 */
data class CredentialContext(
    val runId: String,
    val subject: String = "local"
)
