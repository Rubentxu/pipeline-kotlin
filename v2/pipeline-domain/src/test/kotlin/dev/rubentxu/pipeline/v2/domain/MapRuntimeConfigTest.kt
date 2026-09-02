package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MapRuntimeConfigTest {

    @Test
    fun `env returns the value from the frozen map`() {
        val config = MapRuntimeConfig(
            env = mapOf("PIPELINE_FOO" to "foo-value", "PIPELINE_BAR" to "bar-value"),
            properties = emptyMap(),
        )

        assertEquals("foo-value", config.env("PIPELINE_FOO"))
        assertEquals("bar-value", config.env("PIPELINE_BAR"))
    }

    @Test
    fun `env returns null for missing keys, never an empty string`() {
        val config = MapRuntimeConfig(env = emptyMap(), properties = emptyMap())

        assertNull(config.env("PIPELINE_NOT_SET"))
        assertNull(config.env(""))
    }

    @Test
    fun `env keys are case-sensitive`() {
        val config = MapRuntimeConfig(
            env = mapOf("Pipeline_Foo" to "mixed-case"),
            properties = emptyMap(),
        )

        assertEquals("mixed-case", config.env("Pipeline_Foo"))
        assertNull(config.env("pipeline_foo"))
        assertNull(config.env("PIPELINE_FOO"))
    }

    @Test
    fun `property returns the value from the frozen map`() {
        val config = MapRuntimeConfig(
            env = emptyMap(),
            properties = mapOf("pipeline.sandbox.allow.extra" to "/tmp/extra", "pipeline.foo" to "bar"),
        )

        assertEquals("/tmp/extra", config.property("pipeline.sandbox.allow.extra"))
        assertEquals("bar", config.property("pipeline.foo"))
    }

    @Test
    fun `property with default returns the default when the key is missing`() {
        val config = MapRuntimeConfig(env = emptyMap(), properties = emptyMap())

        assertEquals("default-value", config.property("pipeline.not.set", "default-value"))
        assertEquals("", config.property("pipeline.not.set", ""))
    }

    @Test
    fun `property with default returns the actual value when the key is present`() {
        val config = MapRuntimeConfig(
            env = emptyMap(),
            properties = mapOf("pipeline.foo" to "actual-value"),
        )

        assertEquals("actual-value", config.property("pipeline.foo", "default-value"))
    }

    @Test
    fun `property returns null for missing keys, never an empty string`() {
        val config = MapRuntimeConfig(env = emptyMap(), properties = emptyMap())

        assertNull(config.property("pipeline.not.set"))
        assertNull(config.property(""))
    }

    @Test
    fun `osName reads from the properties map when present`() {
        val config = MapRuntimeConfig(
            env = emptyMap(),
            properties = mapOf("os.name" to "Custom Runtime Linux"),
        )

        assertEquals("Custom Runtime Linux", config.osName())
    }

    @Test
    fun `osName returns an empty string when the key is absent — MapRuntimeConfig is deterministic`() {
        val config = MapRuntimeConfig(env = emptyMap(), properties = emptyMap())

        // Explicit: no fallback to System.getProperty. Determinism is the
        // entire reason this adapter exists; falling back to the JVM property
        // would silently couple the test to the host.
        assertEquals("", config.osName())
    }

    @Test
    fun `empty factory produces a config that returns null for every env lookup`() {
        val config = MapRuntimeConfig.empty()

        assertNull(config.env("ANY"))
        assertNull(config.property("ANY"))
        assertEquals("fallback", config.property("ANY", "fallback"))
    }

    @Test
    fun `source maps are defensively copied at construction`() {
        val mutableEnv = mutableMapOf<String, String>("PIPELINE_FOO" to "original")
        val mutableProperties = mutableMapOf<String, String>("pipeline.foo" to "original")
        val config = MapRuntimeConfig(mutableEnv, mutableProperties)

        mutableEnv["PIPELINE_FOO"] = "mutated"
        mutableEnv["PIPELINE_BAR"] = "added"
        mutableProperties["pipeline.foo"] = "mutated"
        mutableProperties["pipeline.bar"] = "added"

        assertEquals("original", config.env("PIPELINE_FOO"))
        assertNull(config.env("PIPELINE_BAR"))
        assertEquals("original", config.property("pipeline.foo"))
        assertNull(config.property("pipeline.bar"))
    }
}
