package dev.rubentxu.pipeline.v2.protocol

import dev.rubentxu.pipeline.v2.protocol.Command
import dev.rubentxu.pipeline.v2.protocol.PrepareRun
import dev.rubentxu.pipeline.v2.protocol.StartRun
import dev.rubentxu.pipeline.v2.protocol.EventEnvelope
import dev.rubentxu.pipeline.v2.protocol.NegotiatedSession
import dev.rubentxu.pipeline.v2.protocol.WorkerHello
import dev.rubentxu.pipeline.v2.protocol.VersionRange

object GoldenFixtureHarness {
    private const val PINNED_EPOCH_MS = 1_700_000_000_000L

    fun createWorkerHello(
        workerId: String = "worker-001",
        instanceId: String = "instance-001"
    ): WorkerHello = WorkerHello.newBuilder()
        .setWorkerId(workerId)
        .setInstanceId(instanceId)
        .setRuntimeVersion("1.0.0")
        .setProtocolVersion(
            VersionRange.newBuilder()
                .setMinMajor(1)
                .setMinMinor(0)
                .setMaxMajor(1)
                .setMaxMinor(0)
                .build()
        )
        .build()

    fun createNegotiatedSession(sessionId: String = "session-001"): NegotiatedSession = NegotiatedSession.newBuilder()
        .setSessionId(sessionId)
        .setHeartbeatIntervalSeconds(30)
        .setMaxMessageSizeBytes(10 * 1024 * 1024)
        .build()

    fun createCommand(
        commandId: String = "cmd-001",
        type: CommandType = CommandType.COMMAND_TYPE_PREPARE_RUN
    ): Command = Command.newBuilder()
        .setCommandId(commandId)
        .setType(type)
        .setSequenceNumber(1)
        .setTimestampEpochMs(PINNED_EPOCH_MS)
        .build()

    fun createPrepareRunCommand(
        pipelineId: String = "pipeline-001",
        runId: String = "run-001"
    ): Command = Command.newBuilder()
        .setCommandId("cmd-prepare-${PINNED_EPOCH_MS}")
        .setType(CommandType.COMMAND_TYPE_PREPARE_RUN)
        .setSequenceNumber(1)
        .setTimestampEpochMs(PINNED_EPOCH_MS)
        .setPrepareRun(
            PrepareRun.newBuilder()
                .setPipelineId(pipelineId)
                .setRunId(runId)
                .build()
        )
        .build()

    fun createStartRunCommand(
        pipelineId: String = "pipeline-001",
        runId: String = "run-001"
    ): Command = Command.newBuilder()
        .setCommandId("cmd-start-${PINNED_EPOCH_MS}")
        .setType(CommandType.COMMAND_TYPE_START_RUN)
        .setSequenceNumber(2)
        .setTimestampEpochMs(PINNED_EPOCH_MS)
        .setStartRun(
            StartRun.newBuilder()
                .setPipelineId(pipelineId)
                .setRunId(runId)
                .build()
        )
        .build()

    fun createPipelineStartedEvent(
        pipelineId: String = "pipeline-001",
        runId: String = "run-001",
        sequence: Long = 1L
    ): EventEnvelope = EventEnvelope.newBuilder()
        .setEventId("evt-started-${PINNED_EPOCH_MS}")
        .setSequenceNumber(sequence)
        .setTimestampEpochMs(PINNED_EPOCH_MS)
        .setPipelineId(pipelineId)
        .setRunId(runId)
        .setType(EventType.EVENT_TYPE_PIPELINE_STARTED)
        .build()

    fun createStepCompletedEvent(
        pipelineId: String = "pipeline-001",
        runId: String = "run-001",
        stepId: String = "step-001",
        sequence: Long = 2L
    ): EventEnvelope = EventEnvelope.newBuilder()
        .setEventId("evt-step-completed-${PINNED_EPOCH_MS}")
        .setSequenceNumber(sequence)
        .setTimestampEpochMs(PINNED_EPOCH_MS)
        .setPipelineId(pipelineId)
        .setRunId(runId)
        .setType(EventType.EVENT_TYPE_STEP_COMPLETED)
        .setStepCompleted(
            StepCompleted.newBuilder()
                .setStepId(stepId)
                .setExitCode(0)
                .setDurationMs(100)
                .build()
        )
        .build()

    fun createPipelineCompletedEvent(
        pipelineId: String = "pipeline-001",
        runId: String = "run-001",
        sequence: Long = 3L
    ): EventEnvelope = EventEnvelope.newBuilder()
        .setEventId("evt-completed-${PINNED_EPOCH_MS}")
        .setSequenceNumber(sequence)
        .setTimestampEpochMs(PINNED_EPOCH_MS)
        .setPipelineId(pipelineId)
        .setRunId(runId)
        .setType(EventType.EVENT_TYPE_PIPELINE_COMPLETED)
        .setPipelineCompleted(
            PipelineCompleted.newBuilder()
                .setFinalExitCode(0)
                .setTotalDurationMs(1000)
                .build()
        )
        .build()
}
