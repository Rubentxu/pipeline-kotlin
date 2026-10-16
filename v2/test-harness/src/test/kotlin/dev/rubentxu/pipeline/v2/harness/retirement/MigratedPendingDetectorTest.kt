package dev.rubentxu.pipeline.v2.harness.retirement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MigratedPendingDetectorTest {

    @Test
    fun `migrated-pending file is detected`() {
        val text = """
            @Disabled("migrated-pending")
            // Migrated-pending per docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md BQ5.
            // The harness is the owner of the equivalence proof.
            class FooUatTest { }
        """.trimIndent()
        assertTrue(MigratedPendingDetector.isMigratedPendingText(text))
    }

    @Test
    fun `non-migrated file is not detected`() {
        val text = """
            class FooUatTest { }
        """.trimIndent()
        assertFalse(MigratedPendingDetector.isMigratedPendingText(text))
    }

    @Test
    fun `other @Disabled reason is not migrated-pending`() {
        val text = """
            @Disabled("regression")
            class FooUatTest { }
        """.trimIndent()
        assertFalse(MigratedPendingDetector.isMigratedPendingText(text))
    }

    @Test
    fun `provenance comment without annotation is not migrated-pending`() {
        val text = """
            // Migrated-pending per docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md BQ5.
            class FooUatTest { }
        """.trimIndent()
        assertFalse(MigratedPendingDetector.isMigratedPendingText(text))
    }

    @Test
    fun `file path detection round-trips`(@TempDir tmp: Path) {
        val file = Files.createFile(tmp.resolve("Sample.kt"))
        Files.writeString(file, """
            @Disabled("migrated-pending")
            // Migrated-pending per docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md BQ5.
            class FooUatTest { }
        """.trimIndent())
        assertTrue(MigratedPendingDetector.isMigratedPending(file))

        val plain = Files.createFile(tmp.resolve("Plain.kt"))
        Files.writeString(plain, "class PlainTest { }")
        assertFalse(MigratedPendingDetector.isMigratedPending(plain))
    }
}
