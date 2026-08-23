package com.pipeline.v2.scripting

import com.pipeline.v2.events.CompilationFinished
import com.pipeline.v2.events.CompilationStarted
import com.pipeline.v2.events.DomainEvent
import com.pipeline.v2.events.EventSink
import com.pipeline.v2.scripting.Kotlin24ScriptingHost
import com.pipeline.v2.scripting.ScriptDefinition
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

    @Test
    fun `happy compile emits start then finished with v1 cache key`() {
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
        assertEquals("v1", cf.cacheKey.version)
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
