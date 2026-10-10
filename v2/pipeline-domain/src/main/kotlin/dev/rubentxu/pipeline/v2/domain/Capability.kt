package dev.rubentxu.pipeline.v2.domain

/**
 * Stable identifier of a consumer-negotiable capability advertised by a
 * PipelineK release. The `id` is the byte-exact string that appears in
 * `INTERFACE_CONTRACT.md` and in `WorkerHello` / manifest negotiation;
 * equality, hashing, and rendering all derive from it.
 *
 * Introduced for CRIC-M1/M2 capability registration (see
 * `docs/pipelinek-coordinated-evolution/m2-design/M2_INSPECT_RECOVER_CANCEL_DESIGN.md`
 * §1, §2.2). Prior to this type the M1 strings (`output.follow.v1`,
 * `events.follow.v1`) lived only in Javadoc + test fixtures; the value
 * class is the single authority for what a capability ID is allowed to be
 * (non-blank).
 *
 * Backwards-compatible: any `String` previously asserted against a
 * capability row in the contract remains a valid equality target because
 * the type wraps a `String` with no additional state.
 */
@JvmInline
value class Capability(val id: String) {
    init { require(id.isNotBlank()) { "capability id must not be blank" } }
}