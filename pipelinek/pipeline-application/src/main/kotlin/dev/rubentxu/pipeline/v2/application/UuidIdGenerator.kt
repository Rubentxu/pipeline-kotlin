package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.IdGenerator
import java.util.UUID

/**
 * The platform's source of opaque identifiers.
 *
 * The ONE place in the tree that draws an identifier from the platform's
 * entropy. Everything that needs an identifier asks this port, so a test can
 * replace it and observe a run whose identities it knows in advance.
 */
class UuidIdGenerator : IdGenerator {

    override fun next(): String = UUID.randomUUID().toString()
}
