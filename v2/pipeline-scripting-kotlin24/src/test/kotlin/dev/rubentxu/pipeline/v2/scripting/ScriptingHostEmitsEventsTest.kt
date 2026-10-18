package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Tests that Kotlin24ScriptingHost emits CompilationStarted and CompilationFinished
 * events through an injected EventSink.
 */
class ScriptingHostEmitsEventsTest {

    private class RecordingEventSink : EventSink {
        private val _events = mutableListOf<DomainEvent>()
        val events: List<DomainEvent> get() = _events.toList()

        override fun append(event: DomainEvent) {
            _events.add(event)
        }

        override fun eventsFor(runId: String) = _events.asSequence()
    }

    /**
     * The label is asserted, not just the format, because this event is the durable surface:
     * [CacheKey.version] is the field that tells a reader which algorithm produced `value`. A
     * change in the identity's composition that did NOT move the label would make an old value
     * unreadable while still claiming the old recipe.
     *
     * Moved `v1` -> `v2` when the identity completed to the shape declared by
     * `SCRIPTING_COMPILER_SPEC.md` §7: length-framed pre-image, plus the compiler options as a
     * real dimension instead of a manual `hostVersion` bump. See `CacheKey.kt`.
     */
    @Test
    fun `happy compile emits start then finished with v2 cache key`() {
        val sink = RecordingEventSink()
        val host = Kotlin24ScriptingHost(sink)

        val scriptPath = Paths.get(
            javaClass.getResource("/hello.pipeline.kts")!!.toURI()
        )
        val dslJar = ScriptDefinition.dslApiJar()
        val dslClasspath = if (dslJar != null) listOf(dslJar) else emptyList()
        val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
        val result = host.compile(definition)

        assertTrue(result.isSuccess, "Expected successful compilation: ${result.diagnostics}")
        assertEquals(2, sink.events.size)

        val startEvent = sink.events[0]
        assertTrue(startEvent is CompilationStarted)

        val finishEvent = sink.events[1]
        assertTrue(finishEvent is CompilationFinished)
        val cf = finishEvent as CompilationFinished
        assertEquals("v2", cf.cacheKey.version)
        assertEquals(64, cf.cacheKey.value.length)
    }

    @Test
    fun `failed compile emits start then finished with non-empty diagnostics`() {
        val sink = RecordingEventSink()
        val host = Kotlin24ScriptingHost(sink)

        val scriptPath = Paths.get(
            javaClass.getResource("/broken.pipeline.kts")!!.toURI()
        )
        val definition = ScriptDefinition.file(scriptPath)
        val result = host.compile(definition)

        assertTrue(!result.isSuccess)
        assertTrue(result.diagnostics.isNotEmpty())
        assertEquals(2, sink.events.size)

        val startEvent = sink.events[0]
        assertTrue(startEvent is CompilationStarted)

        val finishEvent = sink.events[1]
        assertTrue(finishEvent is CompilationFinished)
        val cf = finishEvent as CompilationFinished
        assertTrue(cf.diagnostics.isNotEmpty())
    }
}
