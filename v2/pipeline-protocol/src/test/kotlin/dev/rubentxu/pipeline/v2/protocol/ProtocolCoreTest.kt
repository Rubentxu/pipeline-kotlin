package dev.rubentxu.pipeline.v2.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolCoreTest {

    @Test
    fun `worker hello golden fixture creates valid message`() {
        val hello = GoldenFixtureHarness.createWorkerHello()

        assertNotNull(hello)
        assertEquals("worker-001", hello.workerId)
        assertEquals("instance-001", hello.instanceId)
        assertTrue(hello.protocolVersion.minMajor == 1)
        assertTrue(hello.protocolVersion.maxMajor == 1)
    }

    @Test
    fun `negotiated session golden fixture creates valid message`() {
        val session = GoldenFixtureHarness.createNegotiatedSession()

        assertNotNull(session)
        assertEquals("session-001", session.sessionId)
        assertTrue(session.heartbeatIntervalSeconds == 30)
    }

    @Test
    fun `lease grant golden fixture creates valid message`() {
        val grant = GoldenFixtureHarness.createLeaseGrant()

        assertNotNull(grant)
        assertTrue(grant.leaseId == 1L)
        assertTrue(grant.fencingToken == 1L)
        assertTrue(grant.status == LeaseStatus.LEASE_STATUS_GRANTED)
    }

    @Test
    fun `heartbeat golden fixture creates valid message`() {
        val heartbeat = GoldenFixtureHarness.createHeartbeat()

        assertNotNull(heartbeat)
        assertEquals("worker-001", heartbeat.workerId)
        assertTrue(heartbeat.status == WorkerStatus.WORKER_STATUS_IDLE)
    }

    @Test
    fun `prepare run command creates valid command`() {
        val cmd = GoldenFixtureHarness.createPrepareRunCommand()

        assertNotNull(cmd)
        assertTrue(cmd.type == CommandType.COMMAND_TYPE_PREPARE_RUN)
        assertTrue(cmd.hasPrepareRun())
    }

    @Test
    fun `pipeline started event creates valid event envelope`() {
        val evt = GoldenFixtureHarness.createPipelineStartedEvent()

        assertNotNull(evt)
        assertTrue(evt.type == EventType.EVENT_TYPE_PIPELINE_STARTED)
    }

    @Test
    fun `protocol governance validates message size`() {
        assertTrue(ProtocolGovernance.validateMessageSize(1024))
        assertTrue(ProtocolGovernance.validateMessageSize(ProtocolGovernance.MAX_MESSAGE_SIZE_BYTES))
        assertTrue(!ProtocolGovernance.validateMessageSize(ProtocolGovernance.MAX_MESSAGE_SIZE_BYTES + 1))
    }

    @Test
    fun `protocol version creates valid range`() {
        val version = ProtocolVersion.V1_0

        assertTrue(version.major == 1)
        assertTrue(version.minor == 0)
    }
}
