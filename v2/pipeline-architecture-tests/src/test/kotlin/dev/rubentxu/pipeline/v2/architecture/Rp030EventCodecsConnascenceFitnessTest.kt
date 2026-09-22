package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.extension

/**
 * WU-RP-030 fitness — connascence between the event model, its codecs and the
 * sequence assignment sites (ROADMAP §5).
 *
 * The DomainEvent family is a sealed interface, so every class-dispatching
 * `when` (SequenceAssigner, SqliteEventStore, InMemoryEventStore) is
 * compiler-exhaustive: adding a variant breaks compilation, which IS the
 * intended coupling. The dangerous seam is [JsonEventLog.decodeEvent]:
 * it dispatches on the wire string `kind`. A new variant compiles cleanly,
 * decodes to `null` (silently dropped on replay) and no compiler error is
 * produced. These fitness checks pin the model↔codec connascence mechanically:
 *
 * F1. Every sealed variant declares `kind == simpleName` (one source of truth
 *     for the wire discriminator) and kind strings are unique.
 * F2. JsonEventLog.decodeEvent has a decode branch for EVERY variant kind:
 *     a variant without a branch would round-trip to null on replay.
 * F3. The exhaustive class-dispatch sites (SequenceAssigner, both stores)
 *     contain no `else ->` branch: an `else` would silently absorb new
 *     variants and defeat the compiler exhaustivity guarantee.
 * F4. EnvelopeProjector.subjectOf covers the same closed family (no `else`
 *     returning a generic subject without a compiler-exhaustive match).
 *
 * Test-side only: no production code changes. If a legit new event is added,
 * these checks FORCE the codec branch to be added in the same change.
 */
class Rp030EventCodecsConnascenceFitnessTest {

    private val eventsSrc = FitnessPaths.v2Root()
        .resolve("pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events")

    private val domainEventSource: String by lazy {
        Files.readString(eventsSrc.resolve("DomainEvent.kt"))
    }

    private val jsonEventLogSource: String by lazy {
        Files.readString(eventsSrc.resolve("JsonEventLog.kt"))
    }

    private val sequenceAssignerSource: String by lazy {
        Files.readString(
            eventsSrc.resolve("identity/SequenceAssigner.kt")
        )
    }

    private val sqliteStoreSource: String by lazy {
        Files.readString(eventsSrc.resolve("SqliteEventStore.kt"))
    }

    private val inMemoryStoreSource: String by lazy {
        Files.readString(eventsSrc.resolve("InMemoryEventStore.kt"))
    }

    private val envelopeProjectorSource: String by lazy {
        Files.readString(eventsSrc.resolve("identity/EnvelopeProjector.kt"))
    }

    /** Sealed variant names, straight from the compiled hierarchy. */
    private val variantNames: List<String> by lazy {
        dev.rubentxu.pipeline.v2.events.DomainEvent::class.sealedSubclasses
            .map { it.simpleName!! }
            .sorted()
    }

    /**
     * F1. Sealed variant names, straight from the compiled hierarchy, carry a
     * kind literal equal to their own name, and kind strings are unique.
     */
    @Test
    fun `every variant declares a unique kind literal equal to its class name`() {
        val literals = Regex("override val kind: String get\\(\\) = \"([A-Za-z]+)\"")
            .findAll(domainEventSource)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            variantNames.size, literals.size,
            "kind declarations (${literals.size}) must match sealed variants (${variantNames.size}). " +
                "Duplicate literals: ${literals.groupBy { it }.filterValues { it.size > 1 }.keys}"
        )
        assertEquals(
            literals.sorted(), variantNames,
            "kind literals and sealed variant names must be the same set"
        )
    }

    /**
     * F2. decodeEvent dispatches on every variant kind string. A missing
     * branch means the event survives write but is silently dropped by
     * decode (replay/observation surfaces see nothing).
     */
    @Test
    fun `decodeEvent has a branch for every variant kind`() {
        val decodeSection = jsonEventLogSource
            .substringAfter("private fun decodeEvent")
            .substringBefore("\n    private fun ")
        val failures = variantNames.filter { "\"$it\" ->" !in decodeSection }
        assertTrue(failures.isEmpty()) {
            "JsonEventLog.decodeEvent is missing branches for: $failures. " +
                "Every DomainEvent variant MUST be decodable or replay loses events silently."
        }
    }

    /**
     * F3. The exhaustive class-dispatch sites must not hide an `else`.
     * An `else -> event` would silently accept future variants without
     * re-stamping/projection, defeating compiler exhaustivity.
     */
    @Test
    fun `class dispatch sites have no else branch`() {
        val sites = mapOf(
            "SequenceAssigner.withSequence" to sequenceAssignerSource,
            "SqliteEventStore.appendAssigned" to sqliteStoreSource,
            "InMemoryEventStore.appendAssigned" to inMemoryStoreSource,
        )
        val failures = sites.filterValues { src ->
            val whenBody = src.substringAfter("when (event)")
            // an `else` inside the (exhaustive) event when, before its closing brace
            val braceWindow = whenBody.substringBefore("\n    }")
            braceWindow.contains(Regex("^\\s*else ->", RegexOption.MULTILINE))
        }
        assertTrue(failures.isEmpty()) {
            "exhaustive event dispatch must not use `else` (compiler exhaustivity is the contract): ${failures.keys}"
        }
    }

    /**
     * F4. EnvelopeProjector.subjectOf stays compiler-exhaustive. An `else ->`
     * there would project new variants with a generic subject silently.
     */
    @Test
    fun `envelope projector subjectOf is exhaustive without else`() {
        val subjectBlock = envelopeProjectorSource
            .substringAfter("private fun subjectOf")
            .substringBefore(".let { it }")
        assertTrue(
            subjectBlock.contains("when (event)"),
            "EnvelopeProjector.subjectOf must dispatch on the closed event family"
        )
        assertTrue(
            !Regex("^\\s*else ->", RegexOption.MULTILINE).containsMatchIn(subjectBlock),
            "EnvelopeProjector.subjectOf must not use `else` — new variants would be " +
                "projected with a generic fallback instead of a typed subject."
        )
    }
}
