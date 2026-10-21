package dev.rubentxu.pipeline.v2.credentials.spi

/**
 * Capabilities descriptor for credential providers.
 *
 * Design:
 * - `supportsLease`: provider can lease credentials for time-bounded usage
 * - `supportsRevocation`: provider supports credential revocation
 * - `supportsAcl`: provider supports access control lists
 *
 * Defaults are all-false; LocalCredentialProvider has no
 * lease/revocation/ACL support in the current scope.
 *
 * ## Future extensions
 * - Lease: time-bounded credential borrowing with auto-expiry
 * - Revocation: immediate credential invalidation
 * - ACL: per-subject credential access control
 */
data class ProviderCapabilities(
    val supportsLease: Boolean = false,
    val supportsRevocation: Boolean = false,
    val supportsAcl: Boolean = false
)
