package dev.rubentxu.pipeline.v2.domain

/**
 * Stable identifier of a consumer-negotiable capability advertised by a
 * PipelineK release. The `id` is the byte-exact string that appears in
 * `WorkerHello` / manifest negotiation;
 * equality, hashing, and rendering all derive from it.
 * Introduced for/ capability registration. Prior to this type the
 * Strings (`output.follow.v1`, `events.follow.v1`) lived only in Javadoc +
 * test fixtures; the value class is the single authority for what a capability
 * ID is allowed to be (non-blank).
 * Backwards-compatible: any `String` previously asserted against a
 * capability row in the contract remains a valid equality target because
 * the type wraps a `String` with no additional state.
*/
@JvmInline
value class Capability(val id: String) {
    init { require(id.isNotBlank()) { "capability id must not be blank" } }
}
