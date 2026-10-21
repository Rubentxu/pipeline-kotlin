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
 * The fifteen ids and their publication state. The first two were published
 * together; the three runtime verbs came with the next release; the output
 * trio with the one after; the runtime trio with the following two; the
 * step's verdict, with the event shape that carries it, and the failure's
 * stage, with this build.
 *
 * | Capability ID                 | Status     | First published |
 * |-------------------------------|------------|-----------------|
 * | `output.follow.v1`            | PUBLICADA  | `v0.49.0-rc1`   |
 * | `events.follow.v1`            | DEPRECATED | `v0.49.0-rc1`   |
 * | `events.follow.v2`            | PUBLICADA  | `v0.62.0`       |
 * | `runtime.inspect.v1`          | EXPERIMENTAL | `v0.50.0-rc1` |
 * | `runtime.cancel.v1`           | EXPERIMENTAL | `v0.50.0-rc1` |
 * | `runtime.recover.v1`          | EXPERIMENTAL | `v0.50.0-rc1` |
 * | `output.read.digested.v1`     | EXPERIMENTAL | `v0.51.0-rc1` |
 * | `output.pin.v1`               | EXPERIMENTAL | `v0.51.0-rc1` |
 * | `output.refusal.retention.v1` | EXPERIMENTAL | `v0.51.0-rc1` |
 * | `runtime.structure.v1`        | DEPRECATED | `v0.53.0-rc1`   |
 * | `runtime.operation.v1`        | DEPRECATED | `v0.53.0-rc1`   |
 * | `runtime.eventshape.v1`       | DEPRECATED | `v0.54.0-rc1`   |
 * | `runtime.eventshape.v2`       | PUBLICADA  | `v0.62.0`       |
 * | `events.stepoutcome.v1`       | PUBLICADA  | `v0.62.0`       |
 * | `events.failurestage.v1`      | PUBLICADA  | `v0.62.0`       |
 *
 * The table used to live in a second file, inside the module that implements
 * the runtime ports, and that file claimed to be the canonical one. It was a
 * duplicate: the same ids with the same states and the same first
 * publication, a second list to keep in step, and not a single reader. The
 * catalogue that the product actually prints is the one above, so it is the
 * one that owns the truth.
 *
 * ## Why the follow capability changed its version
 * The follow's refusal vocabulary grew: `EventFollowRefusal` now names the
 * cursor past the tail and the storage failure as facts of their own, instead
 * of folding both into "the run does not exist". A consumer compiled against
 * the old vocabulary matches that sealed type exhaustively, and a case it
 * does not know is a crash at runtime, not a compile error.
 *
 * So the old id cannot keep claiming to be delivered: `events.follow.v1` is
 * announced and NOT delivered, which is exactly what DEPRECATED means here,
 * and `events.follow.v2` is the id a consumer of this vocabulary negotiates.
 * The vocabulary itself is published in `dev.rubentxu.pipeline.v2.events.follow`
 * and its frozen ABI is in `pipeline-events/api/pipeline-events.api`.
 *
 * `firstPublished` names the product version that FIRST CARRIES the id in this
 * line: for an id that already shipped it is the released version it shipped
 * in, and for an id that first ships here it is the version this
 * build declares. The release that carries it is named by its own tag.
 *
 * ## Why the event shape changed its version
 * The typed shape's `outcome` field carried the step's **type**, under a name
 * that promised a result. The same field now carries the step's **verdict**,
 * and the type has a field of its own. A consumer compiled against the old
 * shape reads a different thing out of the same field — and nothing in the
 * field's type says so. So the old id cannot keep claiming to be delivered:
 * `runtime.eventshape.v1` is announced and NOT delivered, which is exactly what
 * DEPRECATED means here, and `runtime.eventshape.v2` is the id a consumer of
 * that shape negotiates.
 *
 * The step's verdict in the raw event lane has its own id, `events.stepoutcome.v1`,
 * because a consumer that reads the event lane has to be able to ask whether
 * this build publishes a verdict at all: a terminal event with no verdict is
 * what an older build wrote, and a consumer that cannot tell that apart from
 * "unknown" is guessing.
 *
 * ## Why the failure's stage has its own id
 *
 * The failure event carries the stage the failure belongs to, and a consumer of the event lane
 * has to be able to ask whether this build publishes it: a failure that names no stage is what
 * an older build wrote, and a consumer that cannot tell that apart from a failure whose producer
 * did not know the stage is guessing. Worse than guessing: a failure that says no stage and a
 * failure in the **first** stage are different facts, and a consumer that reads the absent stage
 * as stage 0 invents a stage nobody said the failure belonged to.
 *
 * So the stage travels in the failure event, and `events.failurestage.v1` is the id that names
 * that vocabulary. It is not a new verdict and not a second model of results: the failure already
 * carried the evidence, and what it could not say was **where** it happened.
 *
 * ## Why the structure and operation ids are announced and not delivered
 *
 * The introspection port publishes three ways of asking about a run: `inspect`
 * (what state is this run in), `runStructure` (enumerate its stages, steps and
 * operations, paginated) and `inspectOperation` (one operation, one attempt).
 *
 * The build delivers the first one — `pipelinek inspect --state` composes it —
 * and it delivers the other two by NOT delivering them. The adapter has the
 * methods; nothing in the tree calls them: no command, no script, no example.
 * A consumer that finds `runtime.structure.v1` in this catalogue and compiles
 * against it has nothing to call, and the failure would arrive at runtime
 * instead of at the catalogue.
 *
 * So the two ids say the truth: announced, and NOT delivered. That is what
 * DEPRECATED means here — it does not mean "will be removed", it means "do
 * not build on this, this build does not serve it". The same rule moved
 * `runtime.cancel.v1` there and back when the cancel verb arrived, and it is
 * the reason `events.follow.v1` and `runtime.eventshape.v1` sit there too.
 *
 * ## Negotiation
 *
 * A consumer negotiates by the presence of these ids in the artifact's
 * metadata. The absence of a capability means the consumer has to fall back
 * or to refuse — never to assume.
 */
object SdkCapabilities {
    /** All 15 capabilities this build knows. */
    val ALL: List<CapabilityEntry> = listOf(
        CapabilityEntry(
            id = "output.follow.v1",
            status = CapabilityStatus.PUBLICADA,
            firstPublished = "v0.49.0-rc1",
        ),
        CapabilityEntry(
            // Announced, and NOT delivered by this build. The follow's refusal vocabulary grew
            // (the cursor past the tail and the storage failure became facts of their own), and a
            // consumer compiled against the three-case vocabulary matches that sealed type
            // exhaustively: a case it does not know is a crash at runtime. `events.follow.v2` is
            // the id this build delivers.
            id = "events.follow.v1",
            status = CapabilityStatus.DEPRECATED,
            firstPublished = "v0.49.0-rc1",
        ),
        CapabilityEntry(
            id = "events.follow.v2",
            status = CapabilityStatus.PUBLICADA,
            firstPublished = "v0.62.0",
        ),
        CapabilityEntry(
            // Delivered, and delivered through a published seam: `pipelinek inspect
            // --state` composes the published introspection port, so a consumer can
            // ask a run what state it is in without knowing the stores behind it.
            // It says EXPERIMENTAL because the shape of the port may still change.
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
            // Delivered, and delivered through a published seam: `pipelinek runs
            // recover` composes the published recover port, so a consumer can
            // close a run whose writer died without repeating its effects. It
            // says EXPERIMENTAL because the shape of the port may still change.
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
            // Announced, and NOT delivered by this build. The implementation
            // exists — the introspection adapter has a `runStructure` — and
            // NOTHING composes it: no command, no script, no example reaches
            // it. An id that a consumer can find but not use is a promise the
            // build cannot keep, so it says so. `runtime.inspect.v1` is the id
            // this build delivers.
            id = "runtime.structure.v1",
            status = CapabilityStatus.DEPRECATED,
            firstPublished = "v0.53.0-rc1",
        ),
        CapabilityEntry(
            // Announced, and NOT delivered by this build, for the same reason as
            // `runtime.structure.v1`: the adapter has an `inspectOperation`, and
            // nothing composes it.
            id = "runtime.operation.v1",
            status = CapabilityStatus.DEPRECATED,
            firstPublished = "v0.53.0-rc1",
        ),
        CapabilityEntry(
            // Announced, and NOT delivered by this build. The typed shape's `outcome` field used to
            // carry the step's TYPE, under a name that promised a result: a consumer compiled
            // against it read a different thing out of the same field, and no way to tell the two
            // apart. `runtime.eventshape.v2` is the id this build delivers.
            id = "runtime.eventshape.v1",
            status = CapabilityStatus.DEPRECATED,
            firstPublished = "v0.54.0-rc1",
        ),
        CapabilityEntry(
            // The typed shape with the step's verdict in `outcome` and the step's type in its own
            // field. A consumer that wants a step's result reads it; a consumer that wants the kind
            // of step reads that too.
            id = "runtime.eventshape.v2",
            status = CapabilityStatus.PUBLICADA,
            firstPublished = "v0.62.0",
        ),
        CapabilityEntry(
            // The step's verdict, published in the event that ends the step. Before this id existed
            // there was no way for a consumer to know whether a step succeeded: the terminal event
            // carried no result, and the only way to learn one was to join it with the failure event
            // — by a step index that does not say which stage the failure belongs to.
            id = "events.stepoutcome.v1",
            status = CapabilityStatus.PUBLICADA,
            firstPublished = "v0.62.0",
        ),
        CapabilityEntry(
            // The stage a failure belongs to, published in the failure event itself. Before this id
            // existed there was no way for a consumer to know which stage a failure came from: the
            // failure named the step and not the stage, and the step index means a different step in
            // each stage, so the only way to learn the stage was to join the failure with another
            // event. A failure that names no stage is not a failure in the first stage, and a
            // consumer that cannot tell those apart is guessing.
            id = "events.failurestage.v1",
            status = CapabilityStatus.PUBLICADA,
            firstPublished = "v0.62.0",
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
