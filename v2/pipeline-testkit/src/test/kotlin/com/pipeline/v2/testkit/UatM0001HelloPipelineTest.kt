package com.pipeline.v2.testkit

import com.pipeline.v2.testkit.HelloPipelineFixture
import com.pipeline.v2.testkit.StepDescriptorAssertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class UatM0001HelloPipelineTest {

    @Test
    @DisplayName("UAT-M0-001 — fixture has exact shape")
    fun fixtureHasExactShape() {
        val h = HelloPipelineFixture.build()

        assertEquals("hello", h.definition.name)
        assertEquals(2, h.steps.size)
        assertEquals("hello-echo", h.steps[0].id)
        assertEquals("echo", h.steps[0].type)
        assertTrue(h.steps[0].configRef.isNotEmpty())
        assertTrue(h.steps[0].configRef.startsWith("hello."))
        assertEquals("hello-sleep", h.steps[1].id)
        assertEquals("sleep", h.steps[1].type)
        assertTrue(h.steps[1].configRef.isNotEmpty())
        assertTrue(StepDescriptorAssertions.hasStep(h.steps, "hello-echo", "echo"))
        assertTrue(StepDescriptorAssertions.hasStep(h.steps, "hello-sleep", "sleep"))
    }

    @Test
    @DisplayName("UAT-M0-001 — rebuild is deterministic")
    fun rebuildIsDeterministic() {
        val a = HelloPipelineFixture.build()
        val b = HelloPipelineFixture.build()
        assertEquals(a, b)
        assertEquals(a.definition, b.definition)
        assertEquals(a.steps, b.steps)
    }
}
