package dev.rubentxu.pipeline.v2.domain

/**
 * Records how a credential is bound to a step at runtime.
 *
 * ## Purpose
 *
 * [BoundPurpose] documents the injection method for audit trail purposes.
 * It is carried in [CredentialBound][dev.rubentxu.pipeline.v2.events.CredentialBound],
 * [CredentialUsed][dev.rubentxu.pipeline.v2.events.CredentialUsed] events.
 * The purpose is informational only - it does not affect security.
 *
 * ## Variants
 *
 * - [ENV]: Credential injected via environment variable (pb.environment().putAll).
 *   This is the L4 canonical binding mode.
 * - [FILE]: Credential written to a temp file and path injected via env.
 *   Reserved for ML-R4.1 `file` binding support.
 * - [VALUE]: Credential returned via returnStdout pipeline step result.
 *   Reserved for DEC-Q3 `returnStdout` interplay.
 *
 * ## L4 Scope
 *
 * L4 (this cycle) implements only [ENV] mode.
 * [FILE] and [VALUE] are reserved for future cycles.
 */
enum class BoundPurpose {
    /**
     * Credential injected via environment variable.
     * This is the primary L4 binding mechanism.
     */
    ENV,

    /**
     * Credential bound to a temporary file.
     * Reserved for ML-R4.1 `file` binding (sshUserPrivateKey, certificate).
     * NOT implemented in L4.
     */
    FILE,

    /**
     * Credential returned as pipeline step value (returnStdout).
     * Reserved for DEC-Q3 interplay with `returnStdout` step.
     * NOT implemented in L4.
     */
    VALUE,
}
