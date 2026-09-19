package dev.rubentxu.pipeline.v2.domain.step

/**
 * Distribution classification of a Step family (PLUGIN_IDENTITY_MODEL,
 * STEP_PLUGIN_SDK §11).
 *
 * Delivery is **metadata, never a verdict**. The runtime MUST NOT grant
 * or deny execution based on the delivery value: identity-based admission
 * is enforced by the registry (duplicate-key) and by capability admission;
 * delivery is read by the future policy engine and by audit consumers.
 */
enum class Delivery {
    /** Code shipped with pipelinek itself (core Steps). */
    CORE,

    /** First-party plugin maintained in the pipeline-kotlin organisation. */
    OFFICIAL_PLUGIN,

    /** Independent external plugin (e.g. community contribution). */
    EXTERNAL_REFERENCE,

    /** Plugin identified in the catalogue but deferred (no SDK seam ready). */
    DEFERRED_REMOTE,

    /** Jenkins-Java-extension bridge rejected from the ecosystem. */
    REJECTED_JENKINS_INTERNAL,
}
