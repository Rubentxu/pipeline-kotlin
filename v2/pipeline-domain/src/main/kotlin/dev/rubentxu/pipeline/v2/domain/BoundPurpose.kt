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
 * ## Variants (ML-R6)
 *
 * Maps to Jenkins credentials-binding kinds per JENKINS_FAMILIARITY_CATALOG.md §1.6:
 * - [API_KEY]: Secret text credential — `string` binding → env variable
 * - [USERNAME_PASSWORD]: Username/password pair — `usernamePassword` binding
 * - [SSH_KEY]: SSH private key with optional passphrase — `sshUserPrivateKey` binding
 * - [FILE]: Secret file credential — `file` binding
 * - [CERTIFICATE]: Keystore certificate — `certificate` binding
 * - [ZIP]: ZIP archive credential — `zip` binding
 * - [USERNAME_COLON_PASSWORD]: Colon-joined credentials — `usernameColonPassword` binding
 *
 * ## Deprecation aliases
 *
 * [ENV] is deprecated — renamed to [API_KEY] (semantically equivalent for L4 callers).
 * [VALUE] is deprecated — no direct replacement in ML-R6; reserved for future `returnStdout` interplay.
 */
enum class BoundPurpose {
    /**
     * Secret text credential — maps to `string` binding.
     * Injects via environment variable (pb.environment().putAll).
     */
    API_KEY,

    /**
     * Username/password pair — maps to `usernamePassword` binding.
     */
    USERNAME_PASSWORD,

    /**
     * SSH private key with optional passphrase — maps to `sshUserPrivateKey` binding.
     */
    SSH_KEY,

    /**
     * Secret file credential — maps to `file` binding.
     */
    FILE,

    /**
     * Certificate keystore — maps to `certificate` binding.
     */
    CERTIFICATE,

    /**
     * ZIP archive credential — maps to `zip` binding.
     */
    ZIP,

    /**
     * Colon-joined credentials (user:pass) — maps to `usernameColonPassword` binding.
     */
    USERNAME_COLON_PASSWORD,
}
