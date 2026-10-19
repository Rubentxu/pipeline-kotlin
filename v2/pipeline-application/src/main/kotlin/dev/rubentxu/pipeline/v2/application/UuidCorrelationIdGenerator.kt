package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CorrelationId
import java.util.UUID

/**
 * Platform adapter that generates a UUIDv4
 * [CorrelationId] when the external invoker does not supply one.
 * The generator is deterministic-by-construction: each call yields a
 * fresh UUIDv4 (122 random bits, 6 fixed bits). It is intentionally
 * not deterministic across process restarts: a [CorrelationId] is a
 * correlation token for ONE invocation, not a stable cross-process
 * identifier.
*/
class UuidCorrelationIdGenerator {
    fun next(): CorrelationId = CorrelationId(UUID.randomUUID().toString())
}
