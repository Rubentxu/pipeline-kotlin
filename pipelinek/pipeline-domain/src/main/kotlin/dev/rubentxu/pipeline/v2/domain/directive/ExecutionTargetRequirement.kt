package dev.rubentxu.pipeline.v2.domain.directive

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * S3.1 — the `agent` directive's typed carrier: what an execution target MUST
 * satisfy before the stage body runs.
 *
 * This file is PURE. It is a sealed ADT of requirements plus a total codec.
 * It does not know whether a target exists, does not allocate one, and cannot
 * reach a runtime. Deciding whether a requirement is satisfiable and acquiring
 * a target are separate acts, separated deliberately: the first is a value
 * comparison, the second is an effect that needs a capability.
 *
 * ## Why this is an ADT and not a label String
 *
 * The construct this replaces was `agent(label: String, remoteUri: String?):
 * Nothing` — a stub that always threw, because the compiled definition used to
 * store a label that no runtime component ever read. The reason it had to throw
 * is the reason it is now modelled this way: a Jenkins agent declaration is
 * not a name, it is a CONSTRAINT. `agent { label 'linux' }` does not mean "run
 * somewhere called linux"; it means "the target must satisfy this", and the
 * honest shapes for that constraint are not variations of a string.
 *
 * Carrying the constraint as data also makes the fail-closed case expressible
 * instead of implicit. [Remote] is a legitimate declaration that this runtime
 * cannot honour. Under a string model that situation would have to be encoded
 * as a magic label, and a magic label is indistinguishable from a typo. As a
 * case it is refused by an exhaustive match, and the refusal is a decision
 * rather than a coincidence.
 *
 * ## Why these cases and not more
 *
 * [LocalAny], [LocalLabels] and [CapabilitySet] are what a single-host runtime
 * can actually decide, so they are executable today. [Remote] is declared
 * because the DSL surface is Jenkins-familiar and authors will write it, but
 * there is no remote allocator until RP-8; it is carried, decoded and then
 * refused with a diagnostic that says why, rather than rejected at the syntax
 * level where the author would learn nothing.
 */

/** A node label a target may carry, e.g. `linux`, `docker`, `ssd`. */
@JvmInline
value class AgentLabel(val value: String) {
    init {
        require(value.isNotBlank()) { "AgentLabel must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * How a remote target is addressed.
 *
 * Deliberately a single opaque string rather than a structured selector: until
 * RP-8 defines a wire handshake, there is nothing to structure it against, and
 * a structured type would imply a protocol that does not exist. Keeping it
 * opaque means adding a real selector later is a versioned change to ONE case
 * rather than a rewrite of the carrier.
 */
@JvmInline
value class RemoteSelector(val value: String) {
    init {
        require(value.isNotBlank()) { "RemoteSelector must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * The typed requirement a stage declares about its execution target.
 *
 * Each case carries only the payload that case needs. There is no
 * `labels: Set<String>?` plus a `remote: String?` pair, because that shape
 * admits combinations that mean nothing — a remote selector with no labels, a
 * label list on a capability requirement — and a caller cannot tell which of
 * them the author meant.
 */
sealed interface ExecutionTargetRequirement {

    /** Any local target will do. The honest "no constraint" declaration. */
    data object LocalAny : ExecutionTargetRequirement

    /**
     * A local target whose advertised labels contain all of [labels].
     *
     * Containment, not equality: Jenkins labels are additive, and a target
     * advertising `linux && docker` satisfies a requirement for `linux`.
     */
    data class LocalLabels(val labels: Set<AgentLabel>) : ExecutionTargetRequirement {
        init {
            require(labels.isNotEmpty()) {
                "LocalLabels must carry at least one label; use LocalAny for an unconstrained " +
                    "local target, because an empty label set is indistinguishable from it"
            }
        }
    }

    /**
     * A local target that provides every capability in [required].
     *
     * Reuses [StepCapability] rather than declaring a parallel vocabulary: it
     * is the same capability key space a Step declares, and two key types for
     * one concept would make "does this target provide what the Step needs?"
     * unanswerable without a conversion that could lose information.
     */
    data class CapabilitySet(val required: Set<StepCapability>) : ExecutionTargetRequirement {
        init {
            require(required.isNotEmpty()) {
                "CapabilitySet must require at least one capability; use LocalAny for an " +
                    "unconstrained local target"
            }
        }
    }

    /**
     * A target on another host, addressed by [selector].
     *
     * Carried and decodable, refused at resolution. See the type KDoc.
     */
    data class Remote(val selector: RemoteSelector) : ExecutionTargetRequirement
}

/**
 * The typed outcome of asking for a target.
 *
 * Closed, because "there is no target" and "the target was granted" are
 * opposite outcomes and a nullable would let a caller read one as the other.
 * The interpreter returns one of these; the caller applies it.
 */
sealed interface TargetLeaseResult {

    /**
     * A target was acquired. [targetId] names it for events and diagnostics;
     * on the local profile it is the current host, and no resource is held.
     */
    data class Granted(val targetId: String) : TargetLeaseResult

    /**
     * No target satisfies the requirement.
     *
     * NOT a skip and NOT a run failure by default: a stage whose target is
     * unavailable has not made progress, and the caller decides whether that
     * is an abort or a skip. [reason] is the operator-facing diagnostic and
     * MUST distinguish "nothing here could satisfy this" from "this runtime
     * cannot satisfy it at all", because only the second is a product gap.
     */
    data class Refused(val reason: String) : TargetLeaseResult
}
