package dev.rubentxu.pipeline.v2.application

/**
 * the registry of SDK capabilities the
 * current build advertises.
 *
 * Source of truth for which [Capability] IDs the runtime knows. The
 * list is exposed via the `pipeline capabilities` subcommand and is
 * the machine-verifiable surface a consumer queries before invoking
 * a capability-gated path.
 *
 * The list is hand-curated and pinned by
 * [SdkCapabilitiesRegistryTest]: adding or removing a capability
 * requires updating both this registry AND the test.
 *
 */
object SdkCapabilities {
    /** All 11 capabilities shipped as of the v0.55.0-rc1 chain close. */
    val ALL: List<CapabilityEntry> = listOf(
        CapabilityEntry(
            id = "output.follow.v1",
            status = CapabilityStatus.PUBLICADA,
            firstPublished = "v0.49.0-rc1",
        ),
        CapabilityEntry(
            id = "events.follow.v1",
            status = CapabilityStatus.PUBLICADA,
            firstPublished = "v0.49.0-rc1",
        ),
        CapabilityEntry(
            id = "runtime.inspect.v1",
            status = CapabilityStatus.EXPERIMENTAL,
            firstPublished = "v0.50.0-rc1",
        ),
        CapabilityEntry(
            id = "runtime.cancel.v1",
            status = CapabilityStatus.EXPERIMENTAL,
            firstPublished = "v0.50.0-rc1",
        ),
        CapabilityEntry(
            id = "runtime.recover.v1",
            status = CapabilityStatus.EXPERIMENTAL,
            firstPublished = "v0.50.0-rc1",
        ),
        CapabilityEntry(
            id = "output.read.digested.v1",
            status = CapabilityStatus.EXPERIMENTAL,
            firstPublished = "v0.51.0-rc1",
        ),
        CapabilityEntry(
            id = "output.pin.v1",
            status = CapabilityStatus.EXPERIMENTAL,
            firstPublished = "v0.51.0-rc1",
        ),
        CapabilityEntry(
            id = "output.refusal.retention.v1",
            status = CapabilityStatus.EXPERIMENTAL,
            firstPublished = "v0.51.0-rc1",
        ),
        CapabilityEntry(
            id = "runtime.structure.v1",
            status = CapabilityStatus.EXPERIMENTAL,
            firstPublished = "v0.53.0-rc1",
        ),
        CapabilityEntry(
            id = "runtime.operation.v1",
            status = CapabilityStatus.EXPERIMENTAL,
            firstPublished = "v0.53.0-rc1",
        ),
        CapabilityEntry(
            id = "runtime.eventshape.v1",
            status = CapabilityStatus.EXPERIMENTAL,
            firstPublished = "v0.54.0-rc1",
        ),
    )

    /** A single capability entry as exposed by the subcommand. */
    data class CapabilityEntry(
        val id: String,
        val status: CapabilityStatus,
        val firstPublished: String,
    )

    /** Stable status enum; matches [dev.rubentxu.pipeline.v2.runtime.Capabilities] status table. */
    enum class CapabilityStatus {
        PUBLICADA,
        EXPERIMENTAL,
        DEPRECATED,
    }
}
