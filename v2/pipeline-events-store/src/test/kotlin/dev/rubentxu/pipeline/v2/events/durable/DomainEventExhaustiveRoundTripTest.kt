package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Constructor
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Exhaustive durable round-trip over every [DomainEvent] variant.
 *
 * **Fidelity (HF1, in-process).** This test enters through the productive authority:
 * it serialises with [JsonEventLog.encode] and reads back with [JsonEventLog.decode],
 * the exact pair the durable log uses for a stored row. It never re-implements
 * encoding, decoding, or fingerprinting.
 *
 * **Why this test exists.** `decodeEvent` decodes 71 kinds but before this test
 * only 10 of them had a durable round-trip assertion. The other 61 were reachable
 * production code with no encode/decode coverage, which made any restructuring of
 * `decodeEvent` a blind refactor.
 *
 * Values are produced reflectively so that adding a [DomainEvent] variant fails
 * here by construction rather than silently going untested.
 */
class DomainEventExhaustiveRoundTripTest {

    @Test
    @DisplayName("every DomainEvent variant survives encode -> decode with its own kind intact")
    fun `every variant round-trips through the durable encoder`() {
        val variants = DomainEvent::class.sealedSubclasses
        assertEquals(
            variants.size,
            variants.map { it.simpleName }.toSet().size,
            "sealedSubclasses must yield distinct classes; a duplicate would silently drop coverage",
        )

        val failures = mutableListOf<String>()

        for (variant in variants) {
            val original = sampleInstance(variant)
            val json = JsonEventLog.encode(listOf(original))

            val decoded = JsonEventLog.decode(json)

            if (decoded.isEmpty()) {
                failures += "${variant.simpleName}: encode produced nothing decodable: $json"
                continue
            }

            val returned = decoded.single()
            if (returned.kind != original.kind) {
                failures += "${variant.simpleName}: kind changed '${original.kind}' -> '${returned.kind}'"
                continue
            }
            if (returned::class != original::class) {
                failures += "${variant.simpleName}: decoded as ${returned::class.simpleName}, not ${original::class.simpleName}"
                continue
            }
            if (returned != original) {
                failures += "${variant.simpleName}: field drift\n  original: $original\n  decoded:  $returned"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${variants.size} DomainEvent variants do not round-trip:\n" +
                failures.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    @DisplayName("decode rejects an unknown kind rather than inventing an event")
    fun `unknown kind is refused, not coerced`() {
        val json = """[{"eventId":"evt-1","runId":"run-1","sequence":1,"""" +
            """"occurredAt":"2026-08-28T10:00:00Z","kind":"NotARealEvent"}]"""

        val decoded = JsonEventLog.decode(json)

        assertTrue(
            decoded.isEmpty(),
            "an unknown kind must not decode into a plausible event, got: $decoded",
        )
    }

    @Test
    @DisplayName("a row missing a required field is refused, not filled with an invented default")
    fun `a row missing a required field is refused rather than defaulted`() {
        val good = RunStarted(
            eventId = "evt-good",
            runId = "run-001",
            sequence = 1L,
            occurredAt = Instant.parse("2026-08-28T10:00:00Z"),
            scriptPath = "pipeline.kts",
        )
        // RunStarted without scriptPath. An empty string here would be a silent
        // no-op: the run would claim to have started without ever naming the script.
        val incompleteRow = """{"eventId":"evt-bad","runId":"run-001","sequence":2,""" +
            """"occurredAt":"2026-08-28T10:00:00Z","kind":"RunStarted"}"""

        val decoded = JsonEventLog.decode(
            """[$incompleteRow,""" + JsonEventLog.encode(listOf(good)).removePrefix("[").removeSuffix("]") + "]",
        )

        assertEquals(
            listOf(good.eventId),
            decoded.map { it.eventId },
            "the readable row must survive, and the row missing a required field must be refused",
        )
    }

    // --- reflective sample construction -------------------------------------

    private fun sampleInstance(variant: KClass<out DomainEvent>): DomainEvent {
        val constructor = requireNotNull(
            variant.java.declaredConstructors.firstOrNull { it.parameterCount > 0 },
        ) {
            "${variant.simpleName}: no constructor with parameters; the net must learn it rather than skip the variant"
        }
        constructor.isAccessible = true
        val owner = variant.simpleName ?: error("anonymous DomainEvent variant has no simpleName")
        val genericTypes = constructor.genericParameterTypes
        val args = constructor.parameters.mapIndexed { index, parameter ->
            // Constructors with default values take a synthetic DefaultConstructorMarker
            // last parameter. Fill it with null so the defaults apply.
            if (parameter.type.name == "kotlin.jvm.internal.DefaultConstructorMarker") {
                null
            } else {
                sampleValue(owner, parameter.name, parameter.type, genericTypes.getOrNull(index))
            }
        }
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(*args.toTypedArray()) as DomainEvent
    }

    private fun sampleValue(owner: String, field: String?, type: Class<*>, generic: Type? = null): Any {
        val label = field ?: "arg"
        if (type == String::class.java) return "s-$owner-$label"
        if (type == java.lang.Long::class.java || type == java.lang.Long.TYPE) return 42L
        if (type == java.lang.Integer::class.java || type == java.lang.Integer.TYPE) return 7
        if (type == java.lang.Boolean::class.java || type == java.lang.Boolean.TYPE) return true
        if (type == java.lang.Double::class.java || type == java.lang.Double.TYPE) return 1.5
        if (type == java.lang.Float::class.java || type == java.lang.Float.TYPE) return 1.5f
        if (type == Instant::class.java) return Instant.parse("2026-08-28T10:00:00Z")
        if (type == java.nio.file.Path::class.java) return java.nio.file.Paths.get("/tmp/net-sample.txt")

        // @JvmInline value classes erase to their single underlying JVM type, so a
        // CredentialsId/CredentialsRef field is a String on the wire and on reflection.
        if (type.isEnum) {
            val constant = type.enumConstants.firstOrNull()
                ?: throw IllegalStateException("$owner.$label: enum $type has no constants")
            return constant
        }

        // Nested domain payloads (ArtifactEntry and friends) are ordinary data classes.
        if (isDataClass(type)) {
            return sampleDataClass(type)
        }

        // Sealed hierarchies of objects (CatchErrorBuildResult.Success/Unstable/Failure):
        // pick the first nested case rather than inventing a value.
        if (type.kotlin.isSealed) {
            val nested = type.kotlin.nestedClasses
                .mapNotNull { candidate ->
                    objectInstanceOrNull(candidate.java) ?: sampleDataClassOrNull(candidate.java)
                }
                .firstOrNull()
            if (nested != null) return nested
        }

        if (List::class.java.isAssignableFrom(type)) {
            return listOf(sampleValue(owner, label, elementTypeOf(generic, owner, label)))
        }

        if (Set::class.java.isAssignableFrom(type)) {
            return setOf(sampleValue(owner, label, elementTypeOf(generic, owner, label)))
        }

        if (Map::class.java.isAssignableFrom(type)) return mapOf("k" to "v")

        throw IllegalStateException(
            "$owner.$label: no sample value for ${type.name}; the reflective net must " +
                "learn the new field rather than skip the variant",
        )
    }

    private fun isDataClass(type: Class<*>): Boolean =
        type.declaredMethods.any { it.name == "copy\$default" }

    /** `data object` compiles to a static INSTANCE field; read it without kotlin-reflect. */
    private fun objectInstanceOrNull(type: Class<*>): Any? =
        runCatching { type.getDeclaredField("INSTANCE").also { it.isAccessible = true }.get(null) }.getOrNull()

    private fun sampleDataClassOrNull(type: Class<*>): Any? =
        runCatching { sampleDataClass(type) }.getOrNull()

    private fun sampleDataClass(type: Class<*>): Any {
        val constructor = requireNotNull(type.declaredConstructors.firstOrNull { it.parameterCount > 0 }) {
            "${type.simpleName}: no constructor with parameters"
        }
        constructor.isAccessible = true
        val genericTypes = constructor.genericParameterTypes
        val args = constructor.parameters.mapIndexed { index, parameter ->
            if (parameter.type.name == "kotlin.jvm.internal.DefaultConstructorMarker") {
                null
            } else {
                sampleValue(type.simpleName, parameter.name, parameter.type, genericTypes.getOrNull(index))
            }
        }
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(*args.toTypedArray()) as Any
    }

    private fun elementTypeOf(generic: Type?, owner: String, label: String): Class<*> {
        if (generic is ParameterizedType) {
            val arg = generic.actualTypeArguments.firstOrNull()
            if (arg is Class<*>) return arg
            if (arg is ParameterizedType) {
                return arg.rawType as? Class<*>
                    ?: throw IllegalStateException("$owner.$label: cannot determine element type")
            }
        }
        throw IllegalStateException(
            "$owner.$label: cannot determine element type from $generic",
        )
    }
}
