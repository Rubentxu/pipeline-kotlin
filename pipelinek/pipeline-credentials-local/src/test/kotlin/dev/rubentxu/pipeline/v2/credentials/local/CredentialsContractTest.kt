package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The contract of the credentials command, stated as two pure decisions: which
 * verb needs the key that opens the store, and what facts the command can end
 * on.
 *
 * Both decisions are the ones the command runs on, so a row that pins them
 * pins the behaviour rather than a copy of it. The consequences are not small:
 * a verb that asks for a key it does not need refuses a request it could have
 * answered, and a fact that ends on the wrong answer tells a script that
 * something it did not do went well.
 *
 * The CODE each fact is answered with is not pinned here: it is not this
 * module's to choose, and the CLI's taxonomy names the case of every fact in
 * the module that owns it.
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
    fun `the family names the facts it can end on, and no code`() {
        // These five facts are the contract of the command: the request was
        // carried out, it was not carried out, there was no key, the key did
        // not match, the store was tampered.
        //
        // The family used to name a CODE with each one, and the door
        // translated those numbers BY NUMBER, so the number that meant "the
        // request was not carried out" reached the process as the case that
        // means "the pipeline executed and produced a failure" — and nothing
        // had executed. The code belongs to the CLI's taxonomy, which names
        // the case of each fact in the module that owns it, and the two
        // passphrase facts share that case exactly as the run path already
        // shares it: the SENTENCES still tell them apart.
        assertEquals(
            listOf(
                CredentialsOutcome.ANSWERED,
                CredentialsOutcome.NOT_CARRIED_OUT,
                CredentialsOutcome.NO_PASSPHRASE,
                CredentialsOutcome.WRONG_PASSPHRASE,
                CredentialsOutcome.TAMPERED,
            ),
            CredentialsOutcome.entries.toList(),
            "the family names every fact it can end on, and no other",
        )
    }
}
