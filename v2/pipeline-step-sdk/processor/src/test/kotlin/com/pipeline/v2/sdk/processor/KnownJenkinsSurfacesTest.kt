package com.pipeline.v2.sdk.processor

import com.pipeline.v2.sdk.CompatibilityLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KnownJenkinsSurfacesTest {

    @Test
    fun `forName returns JenkinsSurfaceMeta for echo`() {
        val meta = KnownJenkinsSurfaces.forName("echo")
        assertEquals("echo", meta?.step)
        assertEquals("workflow-durable-task-step", meta?.plugin)
        assertEquals(CompatibilityLevel.MIGRATION, meta?.compatibility)
    }

    @Test
    fun `forName returns JenkinsSurfaceMeta for sh`() {
        val meta = KnownJenkinsSurfaces.forName("sh")
        assertEquals("sh", meta?.step)
        assertEquals("workflow-durable-task-step", meta?.plugin)
        assertEquals(CompatibilityLevel.MIGRATION, meta?.compatibility)
    }

    @Test
    fun `forName returns JenkinsSurfaceMeta for error`() {
        val meta = KnownJenkinsSurfaces.forName("error")
        assertEquals("error", meta?.step)
        assertEquals("workflow-step", meta?.plugin)
        assertEquals(CompatibilityLevel.MIGRATION, meta?.compatibility)
    }

    @Test
    fun `forName returns JenkinsSurfaceMeta for sleep`() {
        val meta = KnownJenkinsSurfaces.forName("sleep")
        assertEquals("sleep", meta?.step)
        assertEquals("workflow-durable-task-step", meta?.plugin)
        assertEquals(CompatibilityLevel.MIGRATION, meta?.compatibility)
    }

    @Test
    fun `forName returns null for unknown step`() {
        val meta = KnownJenkinsSurfaces.forName("unknown-step")
        assertEquals(null, meta)
    }

    @Test
    fun `tripleFor returns canonical triple for echo`() {
        val triple = KnownJenkinsSurfaces.tripleFor("echo")
        assertEquals("echo|workflow-durable-task-step|F3", triple)
    }

    @Test
    fun `tripleFor returns canonical triple for sh`() {
        val triple = KnownJenkinsSurfaces.tripleFor("sh")
        assertEquals("sh|workflow-durable-task-step|F3", triple)
    }

    @Test
    fun `tripleFor returns canonical triple for error`() {
        val triple = KnownJenkinsSurfaces.tripleFor("error")
        assertEquals("error|workflow-step|F3", triple)
    }

    @Test
    fun `tripleFor returns canonical triple for sleep`() {
        val triple = KnownJenkinsSurfaces.tripleFor("sleep")
        assertEquals("sleep|workflow-durable-task-step|F3", triple)
    }

    @Test
    fun `tripleFor returns empty string for unknown step`() {
        val triple = KnownJenkinsSurfaces.tripleFor("unknown-step")
        assertEquals("", triple)
    }
}
