package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The guarantee: registering a credential for masking must not consume it.
 *
 * The capacity this serves: a caller that registers a credential the pipeline still owns — a
 * bound value that a step is about to run — must find it intact afterwards. Masking is an
 * observation of the bytes, not a transfer of ownership.
 *
 * Registration used to consume the value, wiping its buffer in a cleanup block. Registering a
 * bound credential therefore destroyed it before the step ran. Call sites that only register a
 * value they built and discard never noticed, because nothing afterwards read it; the failure
 * reaches a caller that keeps the value. So the property is asserted on the value after the
 * call, which is the only place it can be observed.
 *
 * Both halves are asserted. Non-consumption alone would also pass if registration became a
 * no-op, and a redactor that registers nothing leaves the secret in the log — so the second
 * assertion pins that the registration still happens. A guard that survives being made harmless
 * is not a guard.
 *
 * Everything here is a pure in-memory value: no clock, no filesystem, no credentials, no I/O.
 * The wipe fills the buffer with NUL characters rather than shortening it, so a consumed
 * credential surfaces as a run of NUL characters, not as an empty string.
 */
class SecretPatternRegistryHandleTest {

    /** Long enough to clear the shortest maskable value, so it really is registered. */
    private val secret = "p@ss w0rd"

    @Test
    fun `registering a handle does not wipe the credential the caller still owns`() {
        val handle = SecretHandle.plain(secret)
        val registry = SecretPatternRegistry()

        registry.addSecret(handle)

        assertEquals(
            secret,
            handle.materialize(),
            "addSecret must observe the bytes, not consume them: the handle belongs to the caller",
        )
    }

    @Test
    fun `registering a handle still masks the value it read`() {
        val handle = SecretHandle.plain(secret)
        val registry = SecretPatternRegistry()

        registry.addSecret(handle)

        assertFalse(
            registry.scrub("token=$secret;").contains(secret),
            "addSecret must register what it peeked at; a handle that survives but masks nothing leaves the secret in the log",
        )
    }
}
