package dev.rubentxu.pipeline.v2.protocol

object ProtocolModules {
    const val WORKER_HELLO = "worker_hello"
    const val NEGOTIATED_SESSION = "negotiated_session"
    const val COMMANDS = "commands"
    const val EVENTS = "events"
    const val ACK_REPLAY = "ack_replay"
    const val LEASES = "leases"
    const val HEARTBEAT = "heartbeat"
}

enum class ProtocolVersion(val major: Int, val minor: Int) {
    V1_0(1, 0),
    CURRENT(1, 0);

    fun toVersion(): dev.rubentxu.pipeline.v2.protocol.Version =
        dev.rubentxu.pipeline.v2.protocol.Version.newBuilder()
            .setMajor(major)
            .setMinor(minor)
            .build()
}

object ProtocolGovernance {
    const val MAX_MESSAGE_SIZE_BYTES = 10L * 1024 * 1024
    const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val LEASE_TIMEOUT_SECONDS = 300L

    fun validateMessageSize(sizeBytes: Long): Boolean =
        sizeBytes in 0..MAX_MESSAGE_SIZE_BYTES

    fun validateVersionRange(min: dev.rubentxu.pipeline.v2.protocol.NegotiatedSession, max: dev.rubentxu.pipeline.v2.protocol.NegotiatedSession): Boolean {
        val current = ProtocolVersion.CURRENT
        return min.negotiatedProtocol.major <= current.major && max.negotiatedProtocol.major >= current.major
    }
}

/**
 * Domain trio removed in M4-R2: WorkerIdentity, SessionContext, ProtocolEvent.
 * Zero consumers confirmed via `grep -rnE 'WorkerIdentity|SessionContext|ProtocolEvent' v2/`.
 * These speculative declarations were never wired into the protocol runtime.
 * Kept: ProtocolModules, ProtocolVersion, ProtocolGovernance.
 */
