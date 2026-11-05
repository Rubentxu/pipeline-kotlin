package dev.rubentxu.pipeline.v2.credentials.local

/**
 * Reads the store passphrase from the operator's terminal.
 *
 * This is the IO end of the passphrase: the command asks for a passphrase
 * through a reader, and the reader that the product ships with is this one.
 * The two are separate so that a caller who drives the command can hand it a
 * reader of its own without opening a terminal, and so that a caller who CANNOT
 * ask — a pipeline run — simply does not offer one at all.
 *
 * A terminal that is not there is not an error: the reader answers null and
 * the caller decides what to do with that. A command that cannot ask says so
 * in its diagnostic; a run that cannot ask refuses without waiting.
 */
internal object ConsolePassphraseReader {

    /**
     * Reads one passphrase from the terminal, without echoing it.
     *
     * @return the passphrase, or null when there is no terminal to read from.
     */
    fun read(): CharArray? {
        val console = System.console() ?: return null
        return console.readPassword(PROMPT)
    }

    /**
     * What the operator is asked.
     *
     * The prompt is written by the console, not by the command's own stream:
     * it belongs to the terminal the operator is looking at, and it says what
     * is being asked for, because the answer is not echoed and an operator who
     * cannot see the prompt cannot tell a passphrase from a password.
     */
    private const val PROMPT = "Store passphrase: "
}
