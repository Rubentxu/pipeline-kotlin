package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The guarantee: registering a credential for masking must not consume it.
 *
 * The capacity this serves: a call site that registers a handle the pipeline still owns — a
 * bound credential that `sh` is about to run — must find that credential intact afterwards.
 * Masking is an observation of the bytes, not a transfer of ownership.
 *
 * This is a regression guard for a defect that was real. `addSecret` used to call `handle.use`,
 * which wipes the buffer in a `finally`. Registering a bound credential therefore destroyed it
 * before the step ran. The canary call sites never caught it because they register handles they
 * just built and throw away; the failure only reaches a caller that keeps the handle. So the
 * property is asserted on the handle AFTER the call, which is the only place it can be observed.
 *
 * Both halves are asserted. Non-consumption alone would also pass if `addSecret` became a no-op,
 * and a redactor that registers nothing leaves the secret in the log — so the second assertion
 * pins that the registration still happens. A guard that survives being made harmless is not a
 * guard.
 *
 * `SecretHandle.plain` is an in-memory factory and `materialize` reads the buffer directly, so
 * this is a pure domain test: no clock, no filesystem, no credentials, no I/O. The wipe fills the
 * array with NULs rather than truncating it, so the failure surface is a string of NULs, not an
 * empty string.
 */
class SecretPatternRegistryHandleTest {

    /** 9 chars, above [SecretPatternRegistry.MIN_MASKABLE_LENGTH] so it really is registered. */
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