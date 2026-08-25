package dev.rubentxu.pipeline.v2.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class GoldenBinaryCompatibilityTest {

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `worker hello binary roundtrip preserves data`() {
        val hello = GoldenFixtureHarness.createWorkerHello()
        val bytes = hello.toByteArray()
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.WorkerHello.parseFrom(bytes)
        assertEquals(hello.workerId, parsed.workerId)
        assertEquals(hello.instanceId, parsed.instanceId)
        assertEquals(hello.runtimeVersion, parsed.runtimeVersion)
    }

    @Test
    fun `negotiated session binary roundtrip preserves data`() {
        val session = GoldenFixtureHarness.createNegotiatedSession()
        val bytes = session.toByteArray()
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.NegotiatedSession.parseFrom(bytes)
        assertEquals(session.sessionId, parsed.sessionId)
        assertEquals(session.heartbeatIntervalSeconds, parsed.heartbeatIntervalSeconds)
        assertEquals(session.maxMessageSizeBytes, parsed.maxMessageSizeBytes)
    }

    @Test
    fun `prepare run command binary roundtrip preserves data`() {
        val cmd = GoldenFixtureHarness.createPrepareRunCommand()
        val bytes = cmd.toByteArray()
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.Command.parseFrom(bytes)
        assertEquals(cmd.commandId, parsed.commandId)
        assertEquals(cmd.type, parsed.type)
        assertTrue(parsed.hasPrepareRun())
    }

    @Test
    fun `pipeline started event binary roundtrip preserves data`() {
        val evt = GoldenFixtureHarness.createPipelineStartedEvent()
        val bytes = evt.toByteArray()
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.EventEnvelope.parseFrom(bytes)
        assertEquals(evt.eventId, parsed.eventId)
        assertEquals(evt.type, parsed.type)
    }

    @Test
    fun `pipeline completed event binary roundtrip preserves data`() {
        val evt = GoldenFixtureHarness.createPipelineCompletedEvent()
        val bytes = evt.toByteArray()
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.EventEnvelope.parseFrom(bytes)
        assertEquals(evt.eventId, parsed.eventId)
        assertEquals(evt.type, parsed.type)
    }

    @Test
    fun `binary representation has consistent SHA-256 for same input`() {
        val hello = GoldenFixtureHarness.createWorkerHello("test-worker", "test-instance")
        val bytes = hello.toByteArray()
        val sha = sha256(bytes)

        assertEquals(64, sha.length)
        assertTrue(sha.matches(Regex("^[a-f0-9]{64}$")))

        val hello2 = GoldenFixtureHarness.createWorkerHello("test-worker", "test-instance")
        val bytes2 = hello2.toByteArray()
        val sha2 = sha256(bytes2)

        assertEquals(sha, sha2)
    }

    @Test
    fun `binary size is within governance limits`() {
        val hello = GoldenFixtureHarness.createWorkerHello()
        val bytes = hello.toByteArray()

        assertTrue(
            ProtocolGovernance.validateMessageSize(bytes.size.toLong()),
            "Binary size ${bytes.size} should be within MAX_MESSAGE_SIZE_BYTES"
        )
    }

    @Test
    fun `step completed event binary roundtrip preserves data`() {
        val evt = GoldenFixtureHarness.createStepCompletedEvent()
        val bytes = evt.toByteArray()
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.EventEnvelope.parseFrom(bytes)
        assertEquals(evt.eventId, parsed.eventId)
        assertEquals(evt.type, parsed.type)
        assertTrue(parsed.hasStepCompleted())
    }
}
