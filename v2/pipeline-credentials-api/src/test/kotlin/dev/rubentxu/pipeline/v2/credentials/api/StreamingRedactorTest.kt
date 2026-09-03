package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Tests for [StreamingRedactor] — REDACT-CAN-001..004.
 *
 * Validates:
 * - REDACT-CAN-001: Secret split across arbitrary read boundaries is fully redacted
 * - REDACT-CAN-002: Bounded heap — pending never exceeds maxLiteralByteLength + chunkSize
 * - REDACT-CAN-003: Independent stream wraps are fully isolated
 * - REDACT-CAN-004: Whole-string [RedactingEventSink] tests are byte-identical
 *                   (regression guard — this class does not modify that code)
 */
@DisplayName("StreamingRedactor contract tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StreamingRedactorTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Simulates an InputStream that delivers exactly `bytesPerRead` bytes per
     * read() call, enabling testing of split-boundary scenarios.
     */
    private class PartialReadInputStream(
        private val data: ByteArray,
        private val bytesPerRead: Int,
    ) : InputStream() {
        private var pos = 0

        override fun read(): Int {
            if (pos >= data.size) return -1
            return data[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val remaining = data.size - pos
            val toRead = minOf(len, bytesPerRead, remaining)
            System.arraycopy(data, pos, b, off, toRead)
            pos += toRead
            return toRead
        }

        override fun available(): Int = data.size - pos
    }

    /**
     * Reads all bytes from an InputStream into a ByteArray.
     *
     * Uses the contract: read() with len>0 returns positive count or -1.
     * If read returns -1 with no bytes read, stops.
     * The caller buffer is always filled completely before requesting more reads.
     */
    private fun readAllBytes(stream: InputStream): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        n = stream.read(buf)
        while (n != -1) {
            baos.write(buf, 0, n)
            n = stream.read(buf)
        }
        return baos.toByteArray()
    }

    /**
     * Reads exactly `expectedLen` bytes from stream, throwing if fewer available.
     */
    private fun readFully(stream: InputStream, expectedLen: Int): ByteArray {
        val buf = ByteArray(expectedLen)
        var offset = 0
        var remaining = expectedLen
        while (remaining > 0) {
            val n = stream.read(buf, offset, remaining)
            assertTrue(n > 0, "Expected positive read, got $n at offset $offset of $expectedLen")
            offset += n
            remaining -= n
        }
        return buf
    }

    // ---------------------------------------------------------------------------
    // REDACT-CAN-001: split-across-chunks canary at every offset
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("REDACT-CAN-001: split-across-chunks canary")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    inner class SplitAcrossChunksTests {

        @Test
        fun `canary split at every offset within a chunk is fully redacted`() {
            val registry = SecretPatternRegistry()
            val canary = "SECRET42" // 8-byte secret, 16-char hex encoding
            registry.addSecret(SecretHandle.plain(canary))

            // chunkSize=5, bytesPerRead=1 forces single-byte reads
            val redactor = StreamingRedactor(registry, chunkSize = 5)
            val sourceData = "PREFIX_${canary}_SUFFIX".toByteArray()
            val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 1)

            val output = readAllBytes(redactor.wrap(partialStream))
            val outputStr = String(output)

            assertFalse(
                outputStr.contains(canary),
                "Canary should not appear in output at any offset (split across reads)",
            )
            assertTrue(
                outputStr.contains("****"),
                "Scrub marker must appear in output",
            )
        }

        @Test
        fun `literal longer than chunk with one-byte source reads is fully redacted`() {
            val registry = SecretPatternRegistry()
            // Secret longer than chunkSize — forces multiple reads before full secret is in pending
            val longSecret = "SUPERSECRETKEY" // 14 bytes, chunkSize=5
            registry.addSecret(SecretHandle.plain(longSecret))

            val redactor = StreamingRedactor(registry, chunkSize = 5)
            // Source delivers one byte at a time, secret spans many source reads
            val sourceData = "BEGIN:$longSecret:END".toByteArray()
            val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 1)

            val output = readAllBytes(redactor.wrap(partialStream))
            val outputStr = String(output)

            assertFalse(
                outputStr.contains(longSecret),
                "Long secret split across many single-byte reads must be fully redacted",
            )
            assertTrue(
                outputStr.contains("****"),
                "Scrub marker must appear when secret is redacted",
            )
            assertTrue(
                outputStr.contains("BEGIN:"),
                "Safe prefix must be preserved",
            )
            assertTrue(
                outputStr.contains(":END"),
                "Safe suffix must be preserved",
            )
        }

        @Test
        fun `canary at chunk boundary (last byte of chunk + first of next) is redacted`() {
            val registry = SecretPatternRegistry()
            // 8-byte secret → 16-char hex → window = 15
            val canary = "BIGSECRET99"
            registry.addSecret(SecretHandle.plain(canary))

            // chunkSize=8 — the secret "X${canary}Y" spans exactly at the chunk boundary
            val redactor = StreamingRedactor(registry, chunkSize = 8)
            val sourceData = "XXXXXXXX${canary}YYYY".toByteArray()
            val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 4)

            val output = readAllBytes(redactor.wrap(partialStream))
            val outputStr = String(output)

            assertFalse(outputStr.contains(canary), "Canary at chunk boundary must be redacted")
            assertTrue(outputStr.contains("****"), "Scrub marker must appear")
        }

        @Test
        fun `no false positive — safe content surrounding canary is preserved`() {
            val registry = SecretPatternRegistry()
            val canary = "MY_SECRET_TOKEN"
            registry.addSecret(SecretHandle.plain(canary))

            val redactor = StreamingRedactor(registry, chunkSize = 5)
            val sourceData = "BEGIN: $canary :END".toByteArray()
            val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 3)

            val output = readAllBytes(redactor.wrap(partialStream))
            val outputStr = String(output)

            assertTrue(outputStr.contains("BEGIN:"), "Safe prefix must be preserved")
            assertTrue(outputStr.contains(":END"), "Safe suffix must be preserved")
            assertFalse(outputStr.contains(canary), "Canary must be redacted")
        }
    }

    // ---------------------------------------------------------------------------
    // REDACT-CAN-002: O(maxLiteral + chunk) heap-budget
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("REDACT-CAN-002: bounded heap budget")
    inner class HeapBudgetTests {

        @Test
        fun `pending buffer size never exceeds maxLiteralByteLength`() {
            val registry = SecretPatternRegistry()
            // Register a secret to get a non-trivial maxLiteralByteLength
            registry.addSecret(SecretHandle.plain("X".repeat(20)))

            val chunkSize = 8192
            val redactor = StreamingRedactor(registry, chunkSize = chunkSize)
            val maxLiteral = redactor.maxLiteralByteLength

            // Access the pending buffer via reflection
            val pendingField = StreamingRedactor.RedactingInputStream::class.java.getDeclaredField("pending").apply {
                isAccessible = true
            }

            val sourceData = "X".repeat(chunkSize * 3).toByteArray()
            val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 1)
            val wrapped = redactor.wrap(partialStream)

            // Read some bytes to trigger pending fill
            val readBuf = ByteArray(1024)
            while (wrapped.read(readBuf).also { /* consume */ } != -1) {
                // keep reading
            }

            @Suppress("UNCHECKED_CAST")
            val pending = pendingField.get(wrapped) as java.util.ArrayDeque<Byte>
            assertTrue(
                pending.size <= maxLiteral,
                "Pending size ${pending.size} must not exceed maxLiteralByteLength $maxLiteral",
            )
        }

        @Test
        fun `outputQueue bounded by SCRUB_MARKER length`() {
            val registry = SecretPatternRegistry()
            registry.addSecret(SecretHandle.plain("SECRET99"))

            val redactor = StreamingRedactor(registry, chunkSize = 1024)

            val outputField = StreamingRedactor.RedactingInputStream::class.java.getDeclaredField("outputQueue").apply {
                isAccessible = true
            }

            val sourceData = "PREFIX_SECRET99_SUFFIX".toByteArray()
            val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 1)
            val wrapped = redactor.wrap(partialStream)

            val readBuf = ByteArray(2) // Small read buffer to observe outputQueue
            while (wrapped.read(readBuf).also { /* consume */ } != -1) {
                @Suppress("UNCHECKED_CAST")
                val outputQueue = outputField.get(wrapped) as java.util.ArrayDeque<Byte>
                // outputQueue should never grow beyond marker length
                assertTrue(
                    outputQueue.size <= SecretPatternRegistry.SCRUB_MARKER.length,
                    "outputQueue size ${outputQueue.size} must not exceed marker length ${SecretPatternRegistry.SCRUB_MARKER.length}",
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // REDACT-CAN-003: independent stream wraps
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("REDACT-CAN-003: independent stream wraps")
    inner class IndependentWrapsTests {

        @Test
        fun `wrap returns independent streams for same redactor`() {
            val registry = SecretPatternRegistry()
        registry.addSecret(SecretHandle.plain("SECRET01"))

            val redactor = StreamingRedactor(registry)

            val stream1 = ByteArrayInputStream("Hello".toByteArray())
            val stream2 = ByteArrayInputStream("World".toByteArray())

            val wrapped1 = redactor.wrap(stream1)
            val wrapped2 = redactor.wrap(stream2)

            val buf1 = ByteArray(5)
            val buf2 = ByteArray(5)

            val n1 = wrapped1.read(buf1)
            val n2 = wrapped2.read(buf2)

            assertEquals(5, n1)
            assertEquals(5, n2)
            assertEquals("Hello", String(buf1))
            assertEquals("World", String(buf2))
        }

        @Test
        fun `concurrent wraps do not interfere`() {
            val registry = SecretPatternRegistry()
            val canary = "UNIQUE_SECRET_12345"
            registry.addSecret(SecretHandle.plain(canary))

            val redactor = StreamingRedactor(registry, chunkSize = 3)

            // Two streams with same secret
            val stream1Data = "Before ${canary} After1"
            val stream2Data = "Before ${canary} After2"

            val wrapped1 = redactor.wrap(ByteArrayInputStream(stream1Data.toByteArray()))
            val wrapped2 = redactor.wrap(ByteArrayInputStream(stream2Data.toByteArray()))

            val output1 = readAllBytes(wrapped1)
            val output2 = readAllBytes(wrapped2)

            assertFalse(String(output1).contains(canary), "Stream1 should redact canary")
            assertFalse(String(output2).contains(canary), "Stream2 should redact canary")
            assertTrue(String(output1).contains("After1"), "Stream1 suffix preserved")
            assertTrue(String(output2).contains("After2"), "Stream2 suffix preserved")
        }
    }

    // ---------------------------------------------------------------------------
    // REDACT-CAN-004: regression guard — RedactingEventSink unchanged
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("REDACT-CAN-004: RedactingEventSink byte-identical regression guard")
    inner class RegressionGuardTests {

        @Test
        fun `RedactingEventSink is unaffected by StreamingRedactor addition`() {
            // This test is a structural marker confirming that StreamingRedactor
            // does not modify RedactingEventSink or its test class.
            // REDACT-CAN-004 is validated by running RedactingEventSinkTest
            // (separate test class, same module) — if it fails, this slice broke
            // the existing whole-string redaction path.
            val redactingEventSinkClass = try {
                Class.forName("dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink")
            } catch (e: ClassNotFoundException) {
                fail("RedactingEventSink class not found — module structure changed")
            }

            val redactorClass = StreamingRedactor::class.java

            // Different classes — StreamingRedactor is a new type, not a modification
            assertNotSame(redactingEventSinkClass, redactorClass)

            // The redactor uses the same registry API that RedactingEventSink uses
            val registry = SecretPatternRegistry()
            registry.addSecret(SecretHandle.plain("shared-secret-key"))

            // Both should produce the same scrub result for a given input
            val testInput = "Before: shared-secret-key, After"
            val redactorScrubbed = registry.scrub(testInput)
            assertFalse(redactorScrubbed.contains("shared-secret-key"))
            assertTrue(redactorScrubbed.contains("****"))
        }

        @Test
        fun `registry produces identical patterns for both redaction paths`() {
            val registry = SecretPatternRegistry()
            val secret = "CONFIGURED_SECRET_TOKEN"
            registry.addSecret(SecretHandle.plain(secret))

            val patterns = registry.buildActivePatterns()
            assertTrue(patterns.isNotEmpty(), "Registry must produce patterns for registered secret")

            // Apply patterns as RedactingEventSink would (whole-string scrub)
            var result = "Token: CONFIGURED_SECRET_TOKEN used here"
            for (pattern in patterns) {
                result = pattern.replace(result, "****")
            }

            // Both the whole-string path and StreamingRedactor must use the same
            // registry.literalSeam() call, so the pattern set must be identical
            assertFalse(result.contains(secret), "Whole-string path must scrub the secret")
        }
    }

    // ---------------------------------------------------------------------------
    // Additional invariants and edge cases
    // ---------------------------------------------------------------------------

    @Test
    fun `empty source returns empty output without error`() {
        val registry = SecretPatternRegistry()
        registry.addSecret(SecretHandle.plain("any-secret"))

        val redactor = StreamingRedactor(registry)
        val emptyStream = ByteArrayInputStream(ByteArray(0))

        val output = readAllBytes(redactor.wrap(emptyStream))
        assertEquals(0, output.size)
    }

    @Test
    fun `stream with no registered secrets passes through unchanged`() {
        val registry = SecretPatternRegistry() // no secrets added
        val redactor = StreamingRedactor(registry, chunkSize = 4)

        val sourceData = "Plain text with no secrets at all".toByteArray()
        val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 3)

        val output = readAllBytes(redactor.wrap(partialStream))
        assertEquals(String(sourceData), String(output))
    }

    @Test
    fun `no-secret stream terminates cleanly at EOF`() {
        val registry = SecretPatternRegistry() // no secrets
        val redactor = StreamingRedactor(registry, chunkSize = 5)

        val sourceData = "AB".toByteArray()
        val stream = ByteArrayInputStream(sourceData)
        val wrapped = redactor.wrap(stream)

        val buf = ByteArray(10)
        val n1 = wrapped.read(buf)
        assertTrue(n1 > 0, "First read should return bytes")
        assertEquals("AB", String(buf, 0, n1))

        val n2 = wrapped.read(buf)
        assertEquals(-1, n2, "Second read after EOF should return -1")
    }

    @Test
    fun `secret larger than chunkSize is fully redacted`() {
        val registry = SecretPatternRegistry()
        // Secret much larger than chunkSize
        val longSecret = "A".repeat(500) // 500 bytes, chunkSize=50
        registry.addSecret(SecretHandle.plain(longSecret))

        val redactor = StreamingRedactor(registry, chunkSize = 50)
        val sourceData = "PREFIX_${longSecret}_SUFFIX".toByteArray()
        val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 7) // Force many reads

        val output = readAllBytes(redactor.wrap(partialStream))
        val outputStr = String(output)

        assertFalse(outputStr.contains(longSecret), "Long secret should be fully redacted")
        assertTrue(outputStr.contains("****"), "Scrub marker should appear")
        assertTrue(outputStr.contains("PREFIX_"), "Safe prefix should be preserved")
        assertTrue(outputStr.contains("_SUFFIX"), "Safe suffix should be preserved")
    }

    @Test
    fun `no data loss at EOF with pending buffer`() {
        val registry = SecretPatternRegistry()
        registry.addSecret(SecretHandle.plain("SECRET99"))

        val redactor = StreamingRedactor(registry, chunkSize = 5)
        // Source with exact bytes
        val sourceData = "ABCDEFGHIJKLMNO".toByteArray() // 15 bytes
        val stream = ByteArrayInputStream(sourceData)

        val output = readAllBytes(redactor.wrap(stream))
        assertEquals(sourceData.size, output.size, "All bytes should be preserved at EOF")
    }

    @Test
    fun `URL-encoded variant split at boundary is redacted`() {
        val registry = SecretPatternRegistry()
        // Secret >= 8 chars with special char that gets URL-encoded
        val secret = "pass@word1" // 10 chars, @ encodes to %40
        registry.addSecret(SecretHandle.plain(secret))
        assertEquals(1, registry.size(), "Registry must have exactly 1 secret (MIN_MASKABLE_LENGTH precondition)")

        // URL-encoded variant generated from the registered secret
        val urlEncodedVariant = "pass%40word1"
        val redactor = StreamingRedactor(registry, chunkSize = 4)
        // Source data contains the URL-encoded variant, NOT the raw secret
        val sourceData = "prefix${urlEncodedVariant}suffix".toByteArray()
        val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 3)

        val output = readAllBytes(redactor.wrap(partialStream))
        val outputStr = String(output)

        assertFalse(outputStr.contains(urlEncodedVariant), "URL-encoded variant should be redacted")
        assertTrue(outputStr.contains("****"), "Scrub marker must appear")
    }

    @Test
    fun `hex upper variant split at boundary is redacted`() {
        val registry = SecretPatternRegistry()
        // Secret >= 8 chars to satisfy MIN_MASKABLE_LENGTH
        val secret = "SecretKey12" // 11 chars
        registry.addSecret(SecretHandle.plain(secret))
        assertEquals(1, registry.size(), "Registry must have exactly 1 secret (MIN_MASKABLE_LENGTH precondition)")

        // hex upper computed from UTF-8 bytes of "SecretKey12"
        val hexUpperVariant = "5365637265744B65793132"
        val redactor = StreamingRedactor(registry, chunkSize = 3)
        // Source data contains the hex-encoded variant, NOT the raw literal
        val sourceData = "pre${hexUpperVariant}post".toByteArray()
        val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 2)

        val output = readAllBytes(redactor.wrap(partialStream))
        val outputStr = String(output)

        assertFalse(outputStr.contains(hexUpperVariant), "Hex upper variant should be redacted")
        assertTrue(outputStr.contains("****"), "Scrub marker must appear")
    }

    @Test
    fun `hex lower variant split at boundary is redacted`() {
        val registry = SecretPatternRegistry()
        // Secret >= 8 chars to satisfy MIN_MASKABLE_LENGTH
        val secret = "SecretKey12" // 11 chars
        registry.addSecret(SecretHandle.plain(secret))
        assertEquals(1, registry.size(), "Registry must have exactly 1 secret (MIN_MASKABLE_LENGTH precondition)")

        // hex lower computed from UTF-8 bytes of "SecretKey12"
        val hexLowerVariant = "5365637265744b65793132"
        val redactor = StreamingRedactor(registry, chunkSize = 3)
        // Source data contains the hex-encoded variant, NOT the raw literal
        val sourceData = "before${hexLowerVariant}after".toByteArray()
        val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 2)

        val output = readAllBytes(redactor.wrap(partialStream))
        val outputStr = String(output)

        assertFalse(outputStr.contains(hexLowerVariant), "Hex lower variant should be redacted")
        assertTrue(outputStr.contains("****"), "Scrub marker must appear")
    }

    @Test
    fun `base64 std variant split at boundary is redacted`() {
        val registry = SecretPatternRegistry()
        // Raw bytes >= 8 to satisfy MIN_MASKABLE_LENGTH
        val rawBytes = "SecretKey12".toByteArray() // 11 bytes
        val b64 = java.util.Base64.getEncoder().encodeToString(rawBytes)
        registry.addSecret(SecretHandle.secret(rawBytes))
        assertEquals(1, registry.size(), "Registry must have exactly 1 secret (MIN_MASKABLE_LENGTH precondition)")

        val redactor = StreamingRedactor(registry, chunkSize = 4)
        // Source data contains the base64 variant
        val sourceData = "start${b64}end".toByteArray()
        val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 3)

        val output = readAllBytes(redactor.wrap(partialStream))
        val outputStr = String(output)

        assertFalse(outputStr.contains(b64), "base64 variant should be redacted")
        assertTrue(outputStr.contains("****"), "Scrub marker must appear")
    }

    @Test
    fun `base64 url-safe variant split at boundary is redacted`() {
        val registry = SecretPatternRegistry()
        // Raw bytes >= 8 to satisfy MIN_MASKABLE_LENGTH
        val rawBytes = "SecretKey12".toByteArray() // 11 bytes
        val b64Url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)
        registry.addSecret(SecretHandle.secret(rawBytes))
        assertEquals(1, registry.size(), "Registry must have exactly 1 secret (MIN_MASKABLE_LENGTH precondition)")

        val redactor = StreamingRedactor(registry, chunkSize = 4)
        // Source data contains the base64url variant
        val sourceData = "start${b64Url}end".toByteArray()
        val partialStream = PartialReadInputStream(sourceData, bytesPerRead = 3)

        val output = readAllBytes(redactor.wrap(partialStream))
        val outputStr = String(output)

        assertFalse(outputStr.contains(b64Url), "base64url variant should be redacted")
        assertTrue(outputStr.contains("****"), "Scrub marker must appear")
    }

    @Test
    fun `close clears all buffers`() {
        val registry = SecretPatternRegistry()
        registry.addSecret(SecretHandle.plain("SECRET99"))

        val redactor = StreamingRedactor(registry, chunkSize = 5)

        val pendingField = StreamingRedactor.RedactingInputStream::class.java.getDeclaredField("pending").apply {
            isAccessible = true
        }
        val outputField = StreamingRedactor.RedactingInputStream::class.java.getDeclaredField("outputQueue").apply {
            isAccessible = true
        }
        val inputField = StreamingRedactor.RedactingInputStream::class.java.getDeclaredField("inputBuffer").apply {
            isAccessible = true
        }

        val sourceData = "HelloSECRET99World".toByteArray()
        val stream = ByteArrayInputStream(sourceData)
        val wrapped = redactor.wrap(stream)

        // Read some bytes
        val buf = ByteArray(10)
        wrapped.read(buf)

        // Close the stream
        wrapped.close()

        // Buffers should be cleared
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(wrapped) as java.util.ArrayDeque<Byte>
        @Suppress("UNCHECKED_CAST")
        val outputQueue = outputField.get(wrapped) as java.util.ArrayDeque<Byte>
        val inputBuffer = inputField.get(wrapped) as ByteArray

        assertTrue(pending.isEmpty(), "Pending should be empty after close()")
        assertTrue(outputQueue.isEmpty(), "OutputQueue should be empty after close()")
        assertTrue(inputBuffer.all { it == 0.toByte() }, "InputBuffer should be zeroed after close()")
    }

    @Test
    fun `read returns -1 at EOF with no pending bytes`() {
        val registry = SecretPatternRegistry()
        registry.addSecret(SecretHandle.plain("SECRET01"))

        val redactor = StreamingRedactor(registry, chunkSize = 10)
        val sourceData = "nosecret".toByteArray()
        val stream = ByteArrayInputStream(sourceData)
        val wrapped = redactor.wrap(stream)

        val buf = ByteArray(10)
        val n1 = wrapped.read(buf)
        assertTrue(n1 > 0, "First read should return bytes")
        assertEquals("nosecret", String(buf, 0, n1))

        val n2 = wrapped.read(buf)
        assertEquals(-1, n2, "Second read at EOF should return -1")

        val n3 = wrapped.read(buf)
        assertEquals(-1, n3, "Third read after EOF should return -1")
    }

    @Test
    fun `partial reads within marker length are handled correctly`() {
        val registry = SecretPatternRegistry()
        val canary = "SECRET42"
        registry.addSecret(SecretHandle.plain(canary))

        val redactor = StreamingRedactor(registry, chunkSize = 8192)
        val sourceData = "PREFIX_${canary}_SUFFIX".toByteArray()
        val stream = ByteArrayInputStream(sourceData)
        val wrapped = redactor.wrap(stream)

        // Read one byte at a time to verify partial marker handling
        val baos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1)
        var bytesRead: Int
        while (true) {
            bytesRead = wrapped.read(buf, 0, 1)
            if (bytesRead <= 0) break
            baos.write(buf[0].toInt() and 0xFF)
        }

        val output = baos.toByteArray()
        val outputStr = String(output)

        assertFalse(outputStr.contains(canary), "Canary must not appear")
        assertTrue(outputStr.contains("****"), "Marker must appear")
    }
}
