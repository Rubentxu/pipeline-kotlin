package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The contract of the credentials command, stated as two pure decisions: which
 * verb needs the key that opens the store, and what each outcome costs the
 * caller.
 *
 * Both decisions are the ones the command runs on, so a row that pins them
 * pins the behaviour rather than a copy of it. The consequences are not small:
 * a verb that asks for a key it does not need refuses a request it could have
 * answered, and an outcome that costs the wrong exit code tells a script that
 * something it did not do went well.
*/
class CredentialsContractTest {

    @Test
    fun `a store that is not there needs no key to be asked about`() {
        // There is nothing inside to unlock, so asking for the key would be
        // asking for the key to a door that does not exist.
        assertFalse(CredentialsVerb.LIST.needsKey(storeExists = false))
        assertFalse(CredentialsVerb.REMOVE.needsKey(storeExists = false))
        assertFalse(CredentialsVerb.ROTATE.needsKey(storeExists = false))
    }

    @Test
    fun `a store that is there needs the key to be read or changed`() {
        // The passphrase is the authorization to touch what is inside.
        assertTrue(CredentialsVerb.LIST.needsKey(storeExists = true))
        assertTrue(CredentialsVerb.REMOVE.needsKey(storeExists = true))
        assertTrue(CredentialsVerb.ROTATE.needsKey(storeExists = true))
    }

    @Test
    fun `the verb that creates the store needs the key whether or not it is there`() {
        // The key is what seals the store, so the verb that creates it needs
        // one even when there is nothing to open yet.
        assertTrue(CredentialsVerb.ADD.needsKey(storeExists = false))
        assertTrue(CredentialsVerb.ADD.needsKey(storeExists = true))
    }

    @Test
    fun `each outcome costs the caller its own exit code`() {
        // A caller tells these apart by the code alone, so no two of them may
        // cost the same one.
        assertEquals(0, CredentialsOutcome.ANSWERED.exitCode)
        assertEquals(1, CredentialsOutcome.NOT_CARRIED_OUT.exitCode)
        assertEquals(2, CredentialsOutcome.NO_PASSPHRASE.exitCode)
        assertEquals(3, CredentialsOutcome.WRONG_PASSPHRASE.exitCode)
        assertEquals(4, CredentialsOutcome.TAMPERED.exitCode)
    }

    @Test
    fun `a request that went well and a request that did not are not the same answer`() {
        // The difference between "there was nothing to do" and "it did not
        // happen" is the difference between a script that continues and one
        // that retries, so the two must never share a code.
        assertTrue(CredentialsOutcome.ANSWERED.exitCode < CredentialsOutcome.NOT_CARRIED_OUT.exitCode)
        assertTrue(CredentialsOutcome.NOT_CARRIED_OUT.exitCode < CredentialsOutcome.NO_PASSPHRASE.exitCode)
        assertTrue(CredentialsOutcome.NO_PASSPHRASE.exitCode < CredentialsOutcome.WRONG_PASSPHRASE.exitCode)
        assertTrue(CredentialsOutcome.WRONG_PASSPHRASE.exitCode < CredentialsOutcome.TAMPERED.exitCode)
    }
}
