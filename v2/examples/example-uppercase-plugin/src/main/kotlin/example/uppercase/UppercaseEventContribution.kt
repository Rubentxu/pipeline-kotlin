package example.uppercase

import dev.rubentxu.pipeline.v2.events.registry.EventDefinition
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionCreation
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventPayloadCodec
import dev.rubentxu.pipeline.v2.events.registry.PayloadDecode

/**
 * P3-D: this plugin contributes an event KIND, not just a Step (S6.4).
 *
 * Until now a plugin could ship an `EventDefinition` and the runtime would never hear of it —
 * there was no `ServiceLoader` path for events and the registry was only reachable from a test.
 * This is the reachable half: a real external JAR, compiled against the PUBLISHED contract, that
 * owns one kind of event and emits it during a real execution.
 *
 * The kind is namespaced (`example.*`) because the registry refuses an unnamespaced one: two
 * plugins that both picked `validated` would collide, and the refusal is only useful if it is
 * decided before the collision rather than after the first recorded payload.
 */
data class UppercaseApplied(val inputLength: Int, val outputLength: Int)

/**
 * A typed codec with a version, never raw JSON as the plugin-facing semantic API.
 *
 * Deliberately hand-written and deterministic: the same payload must encode to the same bytes on
 * every run, or a replayed event would decode into something the original run never said. The
 * version check returns [PayloadDecode.UnknownSchemaVersion] rather than guessing, because a
 * reader that treats an unrecognised shape as "probably fine" is the semantic drop the
 * Constitution forbids.
 */
object UppercaseAppliedCodec : EventPayloadCodec<UppercaseApplied> {
    private const val V1 = 1

    override fun encode(payload: UppercaseApplied): String = "v1:${payload.inputLength}:${payload.outputLength}"

    override fun decode(raw: String, schemaVersion: Int): PayloadDecode<UppercaseApplied> {
        if (schemaVersion != V1) return PayloadDecode.UnknownSchemaVersion(schemaVersion)
        val parts = raw.split(":")
        if (parts.size != 3 || parts[0] != "v1") {
            return PayloadDecode.Malformed("not a v1 uppercase-applied payload: $raw")
        }
        val inputLength = parts[1].toIntOrNull()
        val outputLength = parts[2].toIntOrNull()
        if (inputLength == null || outputLength == null) {
            return PayloadDecode.Malformed("v1 payload carries a non-numeric length: $raw")
        }
        return PayloadDecode.Decoded(UppercaseApplied(inputLength, outputLength))
    }
}

/**
 * ServiceLoader entry point, mirroring [UppercaseContributor]:
 * `META-INF/services/dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor`.
 *
 * Yields a CONSTRUCTION OUTCOME, not a bare definition, so a malformed declaration reaches the
 * runtime as a named refusal and composition aborts — instead of the registry quietly admitting
 * three of this plugin's four kinds.
 */
class UppercaseEventContributor : EventDefinitionContributor {
    override val id: String = "example.uppercase"

    override fun definitions(): Iterable<EventDefinitionCreation<*>> = listOf(
        EventDefinition.create(
            kind = "example.uppercase.applied",
            schemaVersion = 1,
            payloadClass = UppercaseApplied::class.java,
            emittedBy = "example.uppercase",
            codec = UppercaseAppliedCodec,
        ),
    )
}
