package dev.rubentxu.pipeline.v2.application.durable

import java.nio.file.Path

/**
 * The failure a credential cleanup leaves behind when it could not wipe what it materialized.
 *
 * The surviving paths travel as the CAUSE of the body's failure, never as its message: the
 * message stays the body's own, so a reader of the run's verdict still sees what actually went
 * wrong inside the body, and the leftovers are one hop away instead of replacing the original.
 */
internal class CredentialCleanupFailure(
    val orphanPaths: List<Path>,
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)
