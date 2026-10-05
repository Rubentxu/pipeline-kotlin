package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * WU-RP-030 fitness — connascence between the event model, its codecs and the
 * sequence assignment sites (ROADMAP §5).
 *
 * The DomainEvent family is a sealed interface, so every class-dispatching
 * `when` (SqliteEventStore, InMemoryEventStore) is
 * compiler-exhaustive: adding a variant breaks compilation, which IS the
 * intended coupling. A third such `when` used to exist in the published
 * `pipeline-events` module with no caller; F5 is what now rules that out.
 * The dangerous seam is [JsonEventLog.decodeEvent]:
 * it dispatches on the wire string `kind`. A new variant compiles cleanly,
 * decodes to `null` (silently dropped on replay) and no compiler error is
 * produced. These fitness checks pin the model↔codec connascence mechanically:
 *
 * F1. Every sealed variant declares `kind == simpleName` (one source of truth
 *     for the wire discriminator) and kind strings are unique.
 * F2. JsonEventLog.decodeEvent has a decode branch for EVERY variant kind:
 *     a variant without a branch would round-trip to null on replay.
 * F3. The exhaustive class-dispatch sites (both stores) contain no `else ->`
 *     branch: an `else` would silently absorb new variants and defeat the
 *     compiler exhaustivity guarantee.
 * F4. EnvelopeProjector.subjectOf covers the same closed family (no `else`
 *     returning a generic subject without a compiler-exhaustive match).
 * F5. The store-assigned sequence is stamped in exactly two places, the two
 *     store adapters. A third copy is a second authority over run ordering.
 *
 * Test-side only: no production code changes. If a legit new event is added,
 * these checks FORCE the codec branch to be added in the same change.
 */
class Rp030EventCodecsConnascenceFitnessTest {

    private val eventsSrc = FitnessPaths.v2Root()
        .resolve("pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events")

    /**
     * The durable half of the event plane, split into its own module by BLOCK 2.
     *
     * Three of the six sources this fitness reads are implementations, and all three moved: reading
     * them from [eventsSrc] would have thrown `NoSuchFileException` rather than failing quietly,
     * which is the lucky kind of breakage. The reason to name the module explicitly is the other
     * direction — a reader who adds a fourth implementation file must be told where it goes, and a
     * path that silently kept working would let the codec checks stop covering anything.
     */
    private val durableSrc = FitnessPaths.v2Root()
        .resolve("pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable")

    /**
     * Every `.kt` under the event model, concatenated.
     *
     * F1 used to read `DomainEvent.kt` alone, which encoded an assumption that had stopped being
     * true: that every sealed variant is declared inside that one file. `PluginEventEmitted`
     * (P3 slice 2) is declared in its own file, which is the better shape — `DomainEvent.kt` is
     * already a concentration point at ~46 KB — so the check silently stopped covering one
     * variant. That is the same failure mode F2 exists to catch, one layer up: a guard whose scope
     * is narrower than the thing it guards reports green while a variant goes unexamined.
     *
     * Scanning the whole source root restores the intent and is strictly stronger: a duplicate
     * kind literal in a second file is now caught too, which the single-file read could not see.
     */
    private val eventModelSources: String by lazy {
        Files.walk(eventsSrc).use { stream ->
            stream.filter { it.extension == "kt" }
                .sorted()
                .map { Files.readString(it) }
                .collect(java.util.stream.Collectors.joining("\n"))
        }
    }

    private val jsonEventLogSource: String by lazy {
        Files.readString(durableSrc.resolve("JsonEventLog.kt"))
    }

    private val sqliteStoreSource: String by lazy {
        Files.readString(durableSrc.resolve("SqliteEventStore.kt"))
    }

    private val inMemoryStoreSource: String by lazy {
        Files.readString(durableSrc.resolve("InMemoryEventStore.kt"))
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
            .findAll(eventModelSources)
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
     *
     * Two sites, not three. `SequenceAssigner` used to be listed here and was the reason this row
     * looked healthy while guarding a ghost — see F5 for what replaced it.
     */
    @Test
    fun `class dispatch sites have no else branch`() {
        val sites = mapOf(
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
     * F5. The sequence-stamping rule has exactly TWO implementations, and they are the two stores.
     *
     * `SequenceAssigner` was a third copy of `when (event) -> event.copy(sequence = …)`, sitting in
     * the published `pipeline-events` module with 74 branches and zero callers in production. Its
     * only referent anywhere was F3 above, which read its source text to check it had no `else` —
     * a guard proving the absence of dead code in a file that itself was dead.
     *
     * Two adapters (InMemory, SQLite) legitimately carry the rule twice, and their parity is a real
     * invariant asserted elsewhere. A THIRD copy that nothing calls is not an adapter; it is a
     * second authority waiting for someone to wire it, and wiring it would have made the
     * store-assigned sequence depend on which path an event took.
     *
     * So this row exists to make the deletion stick. It names the exact shape — an exhaustive
     * `copy(sequence =` dispatch over `DomainEvent` — rather than the file, because the file name
     * is a photo of today and the rule is the thing that must not come back.
     */
    @Test
    fun `the store-assigned sequence has no third implementation`() {
        val stampingSites = productionSources()
            .map { it to readTextOrEmpty(it) }
            .filter { (_, text) ->
                // An exhaustive dispatch over the closed family that re-stamps the sequence. The
                // `else` guard is deliberately absent: the two stores are exhaustive, and a future
                // `else -> event` copy is exactly the shape this row must reject.
                text.contains("copy(sequence =") && text.contains("when (event)")
            }
            .map { (path, _) -> path.fileName.toString() }
            .sorted()

        assertEquals(
            listOf("InMemoryEventStore.kt", "SqliteEventStore.kt"),
            stampingSites,
            "the store-assigned sequence must be stamped in exactly the two store adapters. " +
                "A third copy is a second authority over run ordering: it would be a parallel " +
                "implementation to keep in sync, and the first caller to reach for it would decide " +
                "which of the two sequence numbers a replayed event carries.",
        )
    }

    /**
     * Every production Kotlin source under the v2 tree, for the cross-module row above.
     *
     * This row is the one place in this fitness that leaves the module, and it has to: the whole
     * claim is that the rule lives in two files in ANOTHER module, which no per-module scan can
     * establish. Test sources and build outputs are excluded, so a copy of the pattern in a test
     * fixture — which is legitimate, tests construct events all day — does not read as a second
     * production authority.
     */
    private fun productionSources(): List<Path> =
        Files.walk(FitnessPaths.v2Root()).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".kt") }
                .filter { !it.toString().contains("${File.separator}test${File.separator}") }
                .filter { !it.toString().contains("${File.separator}build${File.separator}") }
                .toArray()
                .map { it as Path }
        }

    /**
     * Source text, or empty when the file is not readable as UTF-8.
     *
     * Empty rather than throwing: a source file this build cannot decode is not a third
     * implementation, and failing the whole gate on an encoding problem would make the row report
     * the wrong thing. A copy of the rule always lives in decodable Kotlin.
     */
    private fun readTextOrEmpty(path: Path): String =
        runCatching { Files.readString(path) }.getOrDefault("")

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
