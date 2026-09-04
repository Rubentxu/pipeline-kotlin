package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Unit tests for --control-root path validation (C5 / EC-3).
 *
 * Verifies:
 * - C5-1: valid user-controlled path is accepted and created
 * - C5-2: path with parent traversal is rejected
 * - C5-3: system root /tmp is rejected
 * - C5-4: system root /home is rejected
 * - C5-5: system root /var is rejected
 * - C5-6: system root / is rejected
 */
class MainCliArgsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `C5-1 valid user-controlled path is accepted and created`() {
        val userPath = tempDir.resolve("my-control-root")
        val result = validateControlRoot(userPath.toString())
        assertEquals(userPath, result)
        assert(java.nio.file.Files.exists(result)) { "validateControlRoot should create the directory" }
    }

    @Test
    fun `C5-2 path with parent traversal is rejected`() {
        val invalidPath = "/tmp/some-dir/parent-ref/../etc/passwd"
        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateControlRoot(invalidPath)
        }
        assert(exception.message?.contains("..") == true) {
            "Exception should mention '..' segments"
        }
    }

    @Test
    fun `C5-3 system root slash-tmp is rejected`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateControlRoot("/tmp")
        }
        assert(exception.message?.contains("system root") == true) {
            "Exception should mention 'system root'"
        }
    }

    @Test
    fun `C5-4 system root slash-home is rejected`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateControlRoot("/home")
        }
        assert(exception.message?.contains("system root") == true) {
            "Exception should mention 'system root'"
        }
    }

    @Test
    fun `C5-5 system root slash-var is rejected`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateControlRoot("/var")
        }
        assert(exception.message?.contains("system root") == true) {
            "Exception should mention 'system root'"
        }
    }

    @Test
    fun `C5-6 system root slash is rejected`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateControlRoot("/")
        }
        assert(exception.message?.contains("system root") == true) {
            "Exception should mention 'system root'"
        }
    }
}
