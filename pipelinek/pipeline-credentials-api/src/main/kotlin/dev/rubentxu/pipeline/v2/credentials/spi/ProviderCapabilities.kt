package dev.rubentxu.pipeline.v2.credentials.spi

/**
 * Capabilities descriptor for credential providers.
 *
 * Design (design §2.4, research §4.6-proposed):
 * - `supportsLease`: provider can lease credentials for time-bounded usage
 * - `supportsRevocation`: provider supports credential revocation
 * - `supportsAcl`: provider supports access control lists
 *
 * H0 exposes all-false defaults — LocalCredentialProvider has no
 * lease/revocation/ACL support in scope for H0.
 *
 * ## Future extensions (H1+)
 * - Lease: time-bounded credential borrowing with auto-expiry
 * - Revocation: immediate credential invalidation
 * - ACL: per-subject credential access control
 */
data class ProviderCapabilities(
    val supportsLease: Boolean = false,
    val supportsRevocation: Boolean = false,
    val supportsAcl: Boolean = false
)
