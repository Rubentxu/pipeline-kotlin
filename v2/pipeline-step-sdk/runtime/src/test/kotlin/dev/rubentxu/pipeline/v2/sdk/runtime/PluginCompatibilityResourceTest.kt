package dev.rubentxu.pipeline.v2.sdk.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PluginCompatibilityResourceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads the apiRange from a well-formed resource on the classpath`() {
        val classes = tempDir.resolve("classes")
        val resource = classes.resolve(PluginCompatibilityResource.RESOURCE_PATH)
        Files.createDirectories(resource.parent)
        resource.toFile().writeText(
            """
            {
              "schema": "pipelinek-plugin-compatibility/v1",
              "productVersion": "0.56.0",
              "minInclusive": "0.47.0",
              "untilExclusive": "0.57.0",
              "apiRange": "[0.47.0, 0.57.0)",
              "hostAuthority": "rootProject.version",
              "generatedBy": "PluginCompatibilityPolicy"
            }
            """.trimIndent(),
        )
        val loader = java.net.URLClassLoader(arrayOf(classes.toUri().toURL()), null)
        val range = PluginCompatibilityResource.load(loader)
        assertEquals("0.47.0", range.fromInclusive.run { "$major.$minor.$patch" })
        assertEquals("0.57.0", range.untilExclusive.run { "$major.$minor.$patch" })
    }

    @Test
    fun `missing resource is a hard refusal, not a default`() {
        val classes = tempDir.resolve("classes")
        Files.createDirectories(classes)
        val loader = java.net.URLClassLoader(arrayOf(classes.toUri().toURL()), null)
        val ex = assertThrows(IllegalStateException::class.java) {
            PluginCompatibilityResource.load(loader)
        }
        assertNotNull(ex.message)
        assert(ex.message!!.contains("absent on the classpath"))
    }

    @Test
    fun `malformed apiRange field is a hard refusal`() {
        val classes = tempDir.resolve("classes")
        val resource = classes.resolve(PluginCompatibilityResource.RESOURCE_PATH)
        Files.createDirectories(resource.parent)
        resource.toFile().writeText("""{"productVersion":"0.56.0"}""")
        val loader = java.net.URLClassLoader(arrayOf(classes.toUri().toURL()), null)
        val ex = assertThrows(IllegalStateException::class.java) {
            PluginCompatibilityResource.load(loader)
        }
        assert(ex.message!!.contains("does not contain a parseable"))
    }

    @Test
    fun `out-of-range literal is a hard refusal`() {
        val classes = tempDir.resolve("classes")
        val resource = classes.resolve(PluginCompatibilityResource.RESOURCE_PATH)
        Files.createDirectories(resource.parent)
        resource.toFile().writeText("""{"apiRange":"[0.57.0, 0.47.0)"}""")
        val loader = java.net.URLClassLoader(arrayOf(classes.toUri().toURL()), null)
        val ex = assertThrows(IllegalStateException::class.java) {
            PluginCompatibilityResource.load(loader)
        }
        assert(ex.message!!.contains("not a '[X.Y.Z, A.B.C)' literal"))
    }

    @Test
    fun `parseApiRangeField extracts the range substring`() {
        assertEquals("[0.47.0, 0.57.0)", PluginCompatibilityResource.parseApiRangeField(
            """{"apiRange":"[0.47.0, 0.57.0)","productVersion":"0.56.0"}""",
        ))
        assertNull(PluginCompatibilityResource.parseApiRangeField("""{"productVersion":"0.56.0"}"""))
    }

    @Test
    fun `parseRangeLiteral accepts the well-formed literal and rejects deviations`() {
        val r = PluginCompatibilityResource.parseRangeLiteral("[0.47.0, 0.57.0)")
        assertNotNull(r)
        assertEquals("0.47.0", r!!.fromInclusive.run { "$major.$minor.$patch" })
        assertEquals("0.57.0", r.untilExclusive.run { "$major.$minor.$patch" })
        assertNull(PluginCompatibilityResource.parseRangeLiteral("[0.47.0, 0.57.0]"))  // inclusive upper
        assertNull(PluginCompatibilityResource.parseRangeLiteral("0.47.0..0.57.0"))
        assertNull(PluginCompatibilityResource.parseRangeLiteral("not a range"))
    }
}
