package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What the host announces about a compilation, and what it reports when the
 * compilation is over.
 *
 * ## The guarantee
 *
 * The event that OPENS the compilation phase carries the DECISION — the
 * compilation is going to compile, or it is going to reuse a stored artifact —
 * and the event that CLOSES it carries the OUTCOME.
 *
 * ## Why the two are different facts
 *
 * The decision is known when the phase opens: the cache has been asked and
 * has answered. The outcome is known only when the phase closes, and it
 * differs from the decision in exactly one case: a stored artifact that loads
 * but cannot be evaluated makes the run compile after all. A single field
 * would have to pick one of the two and lose the other.
 *
 * The decision has to travel in the OPENING event because the console prints
 * its line when the phase opens, and a console that says "compiling" on a
 * cached run is a console that lies about the run.
 *
 * ## Why the instrument is a real cache and not a fake one
 *
 * A fake that answers "hit" with an artifact it made up would prove only that
 * the host trusts its input. This test uses the store that ships: the first
 * compilation writes the jar, the second one finds it and loads it. That is
 * the sequence the operator runs, and the second compilation is genuinely
 * served by the artifact the first one left behind.
 *
 * Nothing here reads a clock or asserts on a duration: the guarantee is about
 * WHAT the run did, not about how long it took.
 */
class ScriptOriginTest {

    @TempDir
    lateinit var cacheDir: Path

    /**
     * A sink that remembers what it was given. The events are the fact under
     * test: the origin is published, and published to the right phase.
     */
    private class RecordingSink : EventSink {
        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    @Test
    fun `a cold compilation announces a compilation and reports one`() {
        val sink = RecordingSink()
        val host = Kotlin24ScriptingHost(eventSink = sink, cache = JarCompiledScriptCache(cacheDir.resolve("compiled")))

        host.compile(ScriptDefinition.inline(SCRIPT))

        assertEquals(ScriptOrigin.COMPILED, started(sink).origin, "the opening event announced another thing")
        assertEquals(ScriptOrigin.COMPILED, finished(sink).origin, "the closing event reported another thing")
    }

    /**
     * The sequence the operator actually runs: the first compilation writes
     * the artifact, the second one is served by it.
     */
    @Test
    fun `a warm compilation announces a reuse and reports one`() {
        // The store lives in a directory the cache creates itself, so it is
        // private by construction and the rows below are the real sequence.
        val cache = JarCompiledScriptCache(cacheDir.resolve("compiled"))

        val cold = RecordingSink()
        Kotlin24ScriptingHost(eventSink = cold, cache = cache).compile(ScriptDefinition.inline(SCRIPT))
        assertEquals(
            ScriptOrigin.COMPILED,
            started(cold).origin,
            "the first compilation did not compile, so the store holds nothing to reuse",
        )

        val warm = RecordingSink()
        Kotlin24ScriptingHost(eventSink = warm, cache = cache).compile(ScriptDefinition.inline(SCRIPT))

        assertEquals(ScriptOrigin.REUSED, started(warm).origin, "the opening event announced another thing")
        assertEquals(ScriptOrigin.REUSED, finished(warm).origin, "the closing event reported another thing")
        assertTrue(
            warm.events.none { it is CompilationStarted && it.origin == ScriptOrigin.COMPILED },
            "the warm run announced a compilation it did not perform",
        )
    }

    private fun started(sink: RecordingSink): CompilationStarted =
        sink.events.filterIsInstance<CompilationStarted>().single()

    private fun finished(sink: RecordingSink): CompilationFinished =
        sink.events.filterIsInstance<CompilationFinished>().single()

    private companion object {
        const val SCRIPT = "1 + 1"
    }
}
