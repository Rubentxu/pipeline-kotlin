package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunIdGenerator
import java.util.UUID

/** Platform adapter that generates a unique identity for a new pipeline invocation. */
class UuidRunIdGenerator : RunIdGenerator {
    override fun next(): RunId = RunId(UUID.randomUUID().toString())
}
