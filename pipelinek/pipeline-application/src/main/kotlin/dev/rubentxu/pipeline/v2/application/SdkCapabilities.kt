package dev.rubentxu.pipeline.v2.application

/**
 * The registry of SDK capabilities the current build advertises.
 *
 * It is the single place in the tree where an SDK capability id lives. The
 * list is exposed by the `pipeline capabilities` subcommand and is the
 * machine-verifiable surface a consumer queries before invoking a
 * capability-gated path.
 *
 * ## What the status tells a consumer
 *
 * - [CapabilityStatus.PUBLICADA] — the build delivers it and its shape is
 *   stable.
 * - [CapabilityStatus.EXPERIMENTAL] — the build delivers it and its shape
 *   may still change.
 * - [CapabilityStatus.DEPRECATED] — the id is announced, and this build does
 *   NOT deliver it. A consumer that finds one of these must not build on it.
 *
 * The distinction between "announced" and "delivered" is the whole point of
 * the table: an id that no command and no published seam can serve is a
 * promise the build cannot keep, and a catalogue that keeps it in the
 * EXPERIMENTAL column would make a consumer try a path that does not exist.
 *
 * ## The table, and where it came from
 *
 * The eleven ids and their publication state. The first two were published
 * together; the three runtime verbs came with the next release; the output
 * trio with the one after; the runtime trio with the following two.
 *
 * | Capability ID                 | Status     | First published |
 * |-------------------------------|------------|-----------------|
 * | `output.follow.v1`            | PUBLICADA  | `v0.49.0-rc1`   |
 * | `events.follow.v1`            | PUBLICADA  | `v0.49.0-rc1`   |
 * | `runtime.inspect.v1`          | EXPERIMENTAL | `v0.50.0-rc1` |
 * | `runtime.cancel.v1`           | EXPERIMENTAL | `v0.50.0-rc1` |
 * | `runtime.recover.v1`          | EXPERIMENTAL | `v0.50.0-rc1` |
 * | `output.read.digested.v1`     | EXPERIMENTAL | `v0.51.0-rc1` |
 * | `output.pin.v1`               | EXPERIMENTAL | `v0.51.0-rc1` |
 * | `output.refusal.retention.v1` | EXPERIMENTAL | `v0.51.0-rc1` |
 * | `runtime.structure.v1`        | EXPERIMENTAL | `v0.53.0-rc1` |
 * | `runtime.operation.v1`        | EXPERIMENTAL | `v0.53.0-rc1` |
 * | `runtime.eventshape.v1`       | EXPERIMENTAL | `v0.54.0-rc1` |
 *
 * The table used to live in a second file, inside the module that implements
 * the runtime ports, and that file claimed to be the canonical one. It was a
 * duplicate: the same eleven ids with the same states and the same first
 * publication, a second list to keep in step, and not a single reader. The
 * catalogue that the product actually prints is the one above, so it is the
 * one that owns the truth.
 *
 * ## Negotiation
 *
 * A consumer negotiates by the presence of these ids in the artifact's
 * metadata. The absence of a capability means the consumer has to fall back
 * or to refuse — never to assume.
 */
object SdkCapabilities {
    /** All 11 capabilities this build knows. */
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
            // Delivered, and delivered through a published seam: `pipelinek runs
            // cancel` composes the published control port, so a consumer can
            // close a run that no live process is working on. It says
            // EXPERIMENTAL because the shape of the port may still change.
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

    /**
     * What the build delivers, per capability.
     *
     * PUBLICADA and EXPERIMENTAL are delivered; DEPRECATED is announced and
     * not delivered. The distinction is what makes the catalogue usable by a
     * machine instead of decorative.
     */
    enum class CapabilityStatus {
        PUBLICADA,
        EXPERIMENTAL,
        DEPRECATED,
    }
}
