package dev.rubentxu.pipeline.v2.domain.step

/**
 * Trust metadata for a plugin provider (PLUGIN_IDENTITY_MODEL).
 *
 * This ADT is **deliberately minimal** in the LFC-2E2-prep cycle. The only
 * constructor today is [Unverified]: the plugin manifest exists, but
 * pipelinek has no verified trust evidence for it (no signature check,
 * no digest allow-list, no provenance verification).
 *
 * Verified / Signed / Approved states are NOT introduced in this cycle
 * because introducing them would require real verification logic — a
 * Cedar policy engine, a signature verifier, a digest allow-list, a
 * provenance verifier — none of which exists. Adding a new state is an
 * ADR-level decision that brings its own evidence path.
 *
 * Construction of any state other than [Unverified] is a deliberate code
 * review checkpoint: extending this sealed interface is a visible change.
 */
sealed interface TrustMetadata {
    /**
     * The plugin manifest exists and is structurally valid, but pipelinek
     * has no verified trust evidence for this provider. This is the only
     * legal state in LFC-2E2-prep; future verification work will add
     * additional sealed subclasses.
     */
    data object Unverified : TrustMetadata
}
