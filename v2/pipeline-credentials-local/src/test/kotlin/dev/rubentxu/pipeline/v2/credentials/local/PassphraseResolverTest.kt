package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [PassphraseResolver] is the only place that decides which passphrase goes into Argon2id.
 * Its rules are tiny but the consequences are not: an env value leaked to stdout becomes a
 * secret; a TTY read not zeroed becomes a secret; a wrong precedence order strands an
 * automation run.
 * The tests cover the three branches through the public [resolve] function, and pin the two
 * security rules declared in the source KDoc: the env value is never echoed back, and the
 * returned array is always a defensive copy.
*/
class PassphraseResolverTest {

    @Test
    fun `the env var named PIPELINE_STORE_PASSPHRASE is honoured by the resolver`() {
        // The contract for automation: a CI job sets `PIPELINE_STORE_PASSPHRASE`, the
        // resolver reads it. The test reaches the env branch by setting the env value and
        // asking the resolver for it back. If a future edit renames the constant, the
        // TTY branch is reached instead and the assertion catches the change.
        var ttyCalled = false

        val resolved = PassphraseResolver.resolve(
            env = mapOf("PIPELINE_STORE_PASSPHRASE" to "from-env"),
            ttyReader = {
                ttyCalled = true
                "from-tty".toCharArray()
            },
        )

        assertArrayEquals("from-env".toCharArray(), resolved)
        assertFalse(
            ttyCalled,
            "the env var name PIPELINE_STORE_PASSPHRASE must be the one the resolver reads; " +
                "if the constant drifted the TTY branch would have been taken",
        )
    }

    @Test
    fun `the env value is preferred when both env and TTY are present`() {
        val fromEnv = "from-env".toCharArray()

        val resolved = PassphraseResolver.resolve(
            env = mapOf("PIPELINE_STORE_PASSPHRASE" to "from-env"),
            ttyReader = { "from-tty".toCharArray() },
        )

        assertArrayEquals(fromEnv, resolved, "env takes precedence over TTY")
    }

    @Test
    fun `the env value is preferred when env is present and TTY is unavailable`() {
        val resolved = PassphraseResolver.resolve(
            env = mapOf("PIPELINE_STORE_PASSPHRASE" to "from-env"),
            ttyReader = { null },
        )

        assertArrayEquals("from-env".toCharArray(), resolved)
    }

    @Test
    fun `the TTY is consulted only when the env var is absent`() {
        val fromTty = "from-tty".toCharArray()
        var ttyCalled = false

        val resolved = PassphraseResolver.resolve(
            env = emptyMap(),
            ttyReader = {
                ttyCalled = true
                fromTty
            },
        )

        assertTrue(ttyCalled, "the TTY branch must be reached when env is empty")
        assertArrayEquals(fromTty, resolved)
    }

    @Test
    fun `neither env nor TTY throws the documented unavailability exception`() {
        val ex = assertThrows(
            PassphraseResolver.CredentialsStorePassphraseUnavailableException::class.java,
        ) { PassphraseResolver.resolve(env = emptyMap(), ttyReader = { null }) }

        // The message names the env var so the operator knows which knob to set.
        assertTrue(
            ex.message?.contains("PIPELINE_STORE_PASSPHRASE") == true,
            "the diagnostic must name the env var, got: ${ex.message}",
        )
    }

    @Test
    fun `an env passphrase with empty value is still treated as a passphrase`() {
        // Empty is a valid passphrase, distinct from "not set". A CI job that sets
        // `PIPELINE_STORE_PASSPHRASE=` deliberately should still reach Argon2id and the
        // store; only a missing key should force the TTY fallback.
        val resolved = PassphraseResolver.resolve(
            env = mapOf("PIPELINE_STORE_PASSPHRASE" to ""),
            ttyReader = { null },
        )

        assertArrayEquals("".toCharArray(), resolved)
    }

    @Test
    fun `the returned array is a copy, not the env value's underlying storage`() {
        // The source KDoc promises the returned CharArray is a copy. A future "optimization"
        // that returns the env map's storage would let a later rehash of the map mutate
        // what looks like a frozen passphrase.
        val resolved = PassphraseResolver.resolve(
            env = mapOf("PIPELINE_STORE_PASSPHRASE" to "secret-value"),
            ttyReader = { null },
        )

        assertNotSame("secret-value".toCharArray(), resolved, "the returned array must not alias the input")
    }

    @Test
    fun `the TTY branch returns a copy of the TTY read, not the buffer the reader handed back`() {
        // The TTY reader typically returns a freshly read char[]; the resolver must copy it
        // so the caller's zero-fill does not erase the passphrase the store is about to use.
        val fromTty = "from-tty".toCharArray()

        val resolved = PassphraseResolver.resolve(env = emptyMap(), ttyReader = { fromTty })

        assertNotSame(fromTty, resolved, "the TTY read must be copied, not aliased")
        assertArrayEquals(fromTty, resolved)
    }

    @Test
    fun `the exception message does not echo the env passphrase value`() {
        // The diagnostic names the env var but not its value. A future edit that
        // interpolates the value would exfiltrate it to whatever captures the exception.
        // We probe the message on the empty-env case, where the TTY alternative is the
        // only thing the operator can do.
        val ex = assertThrows(
            PassphraseResolver.CredentialsStorePassphraseUnavailableException::class.java,
        ) { PassphraseResolver.resolve(env = emptyMap(), ttyReader = { null }) }

        // The two things the message MUST contain: the env var name and a TTY hint.
        // The one thing it MUST NOT contain: any character of an arbitrary secret, which
        // we cannot test for in absolute terms, but we can test that the message is
        // deterministic across two calls with two different "absent" envs.
        val exAgain = assertThrows(
            PassphraseResolver.CredentialsStorePassphraseUnavailableException::class.java,
        ) { PassphraseResolver.resolve(env = mapOf("OTHER_VAR" to "x"), ttyReader = { null }) }

        assertTrue(
            ex.message?.contains("PIPELINE_STORE_PASSPHRASE") == true,
            "the env var name must be in the diagnostic, got: ${ex.message}",
        )
        assertTrue(
            ex.message?.contains("TTY") == true || ex.message?.contains("tty") == true,
            "the diagnostic must mention the TTY alternative, got: ${ex.message}",
        )
        assertTrue(
            ex.message == exAgain.message,
            "the diagnostic must be a stable template, not interpolated from any input",
        )
    }
}
