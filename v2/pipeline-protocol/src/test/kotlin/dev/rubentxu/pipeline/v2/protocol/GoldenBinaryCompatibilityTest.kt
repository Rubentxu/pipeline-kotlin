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

    private val fixtures = mapOf(
        "worker_hello.pb" to "226dcb3b1ef2edb46299d3f8cc8bd960221c92dfb2a8938e3a1278295061c929",
        "negotiated_session.pb" to "a31b2386853f2fd8c2951698a5e27f3bb9bec0e8c102338a408ce4861d6ade41",
        "commands.pb" to "b474a6b568028ecdcefbeeb87d67176269668d645ed9ae6951bedfdca918d96a",
        "events.pb" to "4b7ad17ae87d45756721506a53a24f301b69dfd62ff79d4374c983f20afbde7b",
        "ack_replay.pb" to "6e03e3d8760fa72cfcf3aaea02e2040916f303db565e45764ad81c217a4242c3",
        "leases.pb" to "bd4386d1ccedfb1b3494f8e869600d1836b4a159ab3473ddc1af87c8f877e7b2",
        "heartbeat.pb" to "d4a4908d5ef0176b386ce22acab0b53f4a3adef24f9018fbdc52df5dff27c699"
    )

    @Test
    fun `all seven topic fixtures have valid checksums`() {
        for ((fixture, expectedSha) in fixtures) {
            val resource = javaClass.getResourceAsStream("/fixtures/$fixture")
            assertNotNull(resource, "Fixture $fixture must exist in resources")
            val bytes = resource.readBytes()
            val sha = sha256(bytes)
            assertEquals(expectedSha, sha, "SHA-256 mismatch for $fixture")
        }
    }

    @Test
    fun `each fixture roundtrips through deserialize-serialize to exact bytes`() {
        // worker_hello
        val whResource = javaClass.getResourceAsStream("/fixtures/worker_hello.pb")
        assertNotNull(whResource)
        val whBytes = whResource.readBytes()
        val whParsed = dev.rubentxu.pipeline.v2.protocol.WorkerHello.parseFrom(whBytes)
        val whReserialized = whParsed.toByteArray()
        assertArrayEquals(whBytes, whReserialized, "worker_hello.pb: reserialized bytes must match fixture exactly")

        // negotiated_session
        val nsResource = javaClass.getResourceAsStream("/fixtures/negotiated_session.pb")
        assertNotNull(nsResource)
        val nsBytes = nsResource.readBytes()
        val nsParsed = dev.rubentxu.pipeline.v2.protocol.NegotiatedSession.parseFrom(nsBytes)
        val nsReserialized = nsParsed.toByteArray()
        assertArrayEquals(nsBytes, nsReserialized, "negotiated_session.pb: reserialized bytes must match fixture exactly")

        // commands
        val cmdResource = javaClass.getResourceAsStream("/fixtures/commands.pb")
        assertNotNull(cmdResource)
        val cmdBytes = cmdResource.readBytes()
        val cmdParsed = dev.rubentxu.pipeline.v2.protocol.Command.parseFrom(cmdBytes)
        val cmdReserialized = cmdParsed.toByteArray()
        assertArrayEquals(cmdBytes, cmdReserialized, "commands.pb: reserialized bytes must match fixture exactly")

        // events
        val evtResource = javaClass.getResourceAsStream("/fixtures/events.pb")
        assertNotNull(evtResource)
        val evtBytes = evtResource.readBytes()
        val evtParsed = dev.rubentxu.pipeline.v2.protocol.EventEnvelope.parseFrom(evtBytes)
        val evtReserialized = evtParsed.toByteArray()
        assertArrayEquals(evtBytes, evtReserialized, "events.pb: reserialized bytes must match fixture exactly")

        // ack_replay
        val arResource = javaClass.getResourceAsStream("/fixtures/ack_replay.pb")
        assertNotNull(arResource)
        val arBytes = arResource.readBytes()
        val arParsed = dev.rubentxu.pipeline.v2.protocol.Ack.parseFrom(arBytes)
        val arReserialized = arParsed.toByteArray()
        assertArrayEquals(arBytes, arReserialized, "ack_replay.pb: reserialized bytes must match fixture exactly")

        // leases
        val lsResource = javaClass.getResourceAsStream("/fixtures/leases.pb")
        assertNotNull(lsResource)
        val lsBytes = lsResource.readBytes()
        val lsParsed = dev.rubentxu.pipeline.v2.protocol.LeaseGrant.parseFrom(lsBytes)
        val lsReserialized = lsParsed.toByteArray()
        assertArrayEquals(lsBytes, lsReserialized, "leases.pb: reserialized bytes must match fixture exactly")

        // heartbeat
        val hbResource = javaClass.getResourceAsStream("/fixtures/heartbeat.pb")
        assertNotNull(hbResource)
        val hbBytes = hbResource.readBytes()
        val hbParsed = dev.rubentxu.pipeline.v2.protocol.Heartbeat.parseFrom(hbBytes)
        val hbReserialized = hbParsed.toByteArray()
        assertArrayEquals(hbBytes, hbReserialized, "heartbeat.pb: reserialized bytes must match fixture exactly")
    }

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

    @Test
    fun `commands fixture loads and parses correctly`() {
        val resource = javaClass.getResourceAsStream("/fixtures/commands.pb")
        assertNotNull(resource, "commands.pb fixture must exist")
        val bytes = resource.readBytes()
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.Command.parseFrom(bytes)
        assertTrue(parsed.hasPrepareRun())
    }

    @Test
    fun `events fixture loads and parses correctly`() {
        val resource = javaClass.getResourceAsStream("/fixtures/events.pb")
        assertNotNull(resource, "events.pb fixture must exist")
        val bytes = resource.readBytes()
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.EventEnvelope.parseFrom(bytes)
        assertTrue(parsed.hasPipelineStarted())
    }

    @Test
    fun `ack replay fixture loads and parses correctly`() {
        val resource = javaClass.getResourceAsStream("/fixtures/ack_replay.pb")
        assertNotNull(resource, "ack_replay.pb fixture must exist")
        val bytes = resource.readBytes()
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.Ack.parseFrom(bytes)
        assertEquals("evt-001", parsed.eventId)
    }

    @Test
    fun `leases fixture loads and parses correctly`() {
        val resource = javaClass.getResourceAsStream("/fixtures/leases.pb")
        assertNotNull(resource, "leases.pb fixture must exist")
        val bytes = resource.readBytes()
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.LeaseGrant.parseFrom(bytes)
        assertTrue(parsed.leaseId == 1L)
    }

    @Test
    fun `heartbeat fixture loads and parses correctly`() {
        val resource = javaClass.getResourceAsStream("/fixtures/heartbeat.pb")
        assertNotNull(resource, "heartbeat.pb fixture must exist")
        val bytes = resource.readBytes()
        assertTrue(bytes.isNotEmpty())

        val parsed = dev.rubentxu.pipeline.v2.protocol.Heartbeat.parseFrom(bytes)
        assertEquals("test-worker-001", parsed.workerId)
    }
}
