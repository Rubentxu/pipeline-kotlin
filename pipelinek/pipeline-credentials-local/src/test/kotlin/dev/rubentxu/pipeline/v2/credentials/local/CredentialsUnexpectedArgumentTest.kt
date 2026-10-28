package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The credentials command refuses an argument its verb does not own.
 *
 * ## The guarantee
 *
 * A request that carries an argument the verb does not own is REFUSED: the
 * refusal names the token the operator has to remove, names the form the
 * operator wrote, and the verb does not do what it would have done — not even
 * ask for a passphrase.
 *
 * ## The capacity this serves
 *
 * The operator who writes `pipelinek credentials list extra` has to read a
 * refusal, not a credential listing. Before this, `list` DROPPED the extra
 * token and answered "No credentials stored." with exit code 0 — a verdict
 * that says the request was understood and carried out when it was not a
 * request of that verb at all. `add`, `rotate` and `remove` dropped it too:
 * `add` went on to ask for the passphrase, `remove` and `rotate` went on to
 * answer about the store, and none of them ever named the token that was
 * there.
 *
 * ## Why this instrument
 *
 * The cheapest instrument that can fail is the verb itself, called in
 * process: the verb is given the argument array, its exit code is read and
 * its output is captured. There is no CLI, no subprocess, no filesystem and
 * no timing in the way — the refusal the operator would read IS the
 * observable. The store is never touched, because the refusal comes before
 * it is.
 *
 * ## What each row asserts, and why all of them
 *
 * 1. The exit code is the module's own outcome for a request it does not
 *    carry out. A refusal that exits 0 is the false verdict this file exists
 *    to catch, and the outcome is the module's own so the row does not freeze
 *    a number.
 * 2. The offending token is named: the operator has to be able to read WHICH
 *    token to remove. The token is DATA the operator wrote, not a wording.
 * 3. The argument the verb OWNS is not named: the verb owns it, so naming it
 *    as the offending one would say the opposite of the truth.
 * 4. The form the operator wrote is named: there are four verbs, and a
 *    refusal about the wrong one sends the operator to the wrong command.
 * 5. The verb's ANSWER is not published. A refusal that also answers would be
 *    read as the answer.
 *
 * ## Why TWO rows and not one per verb
 *
 * The second row is the same question asked to the three verbs that own ONE
 * argument. The shape of the request is the same shape, so the three share
 * the same answer; a row per verb would be the same instrument three times.
 * The guarantee that the refusal comes BEFORE the store is opened is not a
 * third row: it is already read off the exit code of the `add` case, which
 * answers "a passphrase is needed" (the outcome for no key) when the
 * refusal is not in front of it, and the module's own outcome for a request
 * it does not carry out when it is.
 *
 * ## What this does not freeze
 *
 * The wording of the refusal is not frozen. What is frozen is the token, the
 * owned argument and the verb — all three are what the operator wrote — and
 * the fact that the answer is not published.
 *
 * ## What this instrument cannot see
 *
 * It cannot see that the verb went on to USE the argument it read: every row
 * stops at the refusal, which comes before the store is opened. That the id a
 * verb reads is the id it acts upon is covered by the E2E scenario under
 * `examples/security/`, which seeds credentials through this same command and
 * removes them by the id it was given.
 */
class CredentialsUnexpectedArgumentTest {

    @Test
    fun `list refuses an argument it owns none of`() {
        // `list` owns NOTHING: not a positional argument and not an option.
        // A request that carries one is not a request of this verb, and the
        // answer it used to give was the listing, with exit code 0.
        val result = capture { MainCredentialsCli.main(arrayOf("list", FOREIGN)) }

        assertRefusal(result, verb = "list", foreign = FOREIGN)
        // The listing is not published: the refusal replaces the answer, it
        // does not follow it.
        assertFalse(result.stdout.contains(NO_STORE_ANSWER), result.stdout)
    }

    @Test
    fun `a verb that owns the id refuses a second argument`() {
        // These three verbs own ONE argument, the credential id. The second
        // token is not a request of the verb, and each one used to drop it:
        // `add` went on to ask for the passphrase, `remove` and `rotate` went
        // on to answer about the store.
        val verbs = listOf("add", "remove", "rotate")

        for (verb in verbs) {
            val result = capture {
                MainCredentialsCli.main(arrayOf(verb, OWNED_ID, FOREIGN))
            }

            assertRefusal(result, verb = verb, foreign = FOREIGN)
            // The id is the one the verb OWNS: naming it as the offending
            // argument would send the operator to remove the wrong thing.
            assertFalse(result.stdout.contains(OWNED_ID), "$verb: ${result.stdout}")
            // The answer is not published either: the verb did not reach the
            // store, so it has nothing to report about it.
            assertFalse(result.stdout.contains(NO_STORE_ANSWER), "$verb: ${result.stdout}")
        }
    }

    /**
     * What every refusal of this family has to say.
     */
    private fun assertRefusal(result: Result, verb: String, foreign: String) {
        // The module's own outcome for a request it does not carry out.
        assertEquals(CredentialsOutcome.NOT_CARRIED_OUT.exitCode, result.exitCode, verb)
        // The token the operator has to remove is named.
        assertTrue(result.stdout.contains(foreign), "$verb: ${result.stdout}")
        // The form the operator wrote is named.
        assertTrue(result.stdout.contains(verb), "$verb: ${result.stdout}")
    }

    /** The id these verbs own. */
    private companion object {
        const val OWNED_ID = "owned-id-42"

        /** The token they do not own, spelled so that it contains no verb's name. */
        const val FOREIGN = "extra-arg-xyz"

        /** What the module answers when the store holds nothing. */
        const val NO_STORE_ANSWER = "No credentials stored."
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    private fun capture(block: () -> Int): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out, true, Charsets.UTF_8.name()))
            System.setErr(PrintStream(err, true, Charsets.UTF_8.name()))
            val exitCode = block()
            return Result(exitCode, out.toString(), err.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
