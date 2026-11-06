package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * What is known about the captured stdout of one durable shell execution.
 *
 * ## Why this exists
 *
 * The captured stdout is the typed VALUE of a `sh` step that asks for stdout. It reaches its
 * consumer through places that are not the same place, and at every one of them a reader has to
 * tell apart things that LOOK alike and are not:
 *
 *  - nobody recorded anything — the channel is absent;
 *  - the program printed nothing — the channel is there and holds an empty text;
 *  - the program printed something — the channel is there and holds a text;
 *  - the channel is there and could not be READ — there is no value at all;
 *  - the value was read and the channel could not be DISPOSED of — the value is known, and the
 *    protocol's promise that the channel is gone is broken.
 *
 * A reader that folds the last two into the first publishes no value at all, and the consumer
 * reads that silence as an empty value: it cannot tell a step whose program said nothing from a
 * step whose value was lost. Both look like the same successful step.
 *
 * ## The guarantee
 *
 * These are the facts a reader can know, and none of them is derived from another. None of them
 * is a value the reader did not read:
 *
 * ```text
 * the channel is absent            -> Absent
 * the channel holds ""             -> Present("")
 * the channel holds "hello\n"      -> Present("hello")
 * the channel cannot be read       -> Unreadable(reason)
 * the channel cannot be disposed   -> NotDisposed(text, reason)
 * ```
 *
 * An emptiness is a value: a program that printed nothing has printed nothing, and that is not the
 * same fact as a channel nobody wrote.
 *
 * ## The threat model
 *
 * The control directory lives under the runtime's control root, so whoever can read that root can
 * read this value. The value is protected by the permissions of the control root and by nothing
 * else.
 *
 * The value is NOT redacted, and that is a decision rather than an omission: a typed value that a
 * capture mode asked for has to stay exact, so it does not travel through the transcript redactor
 * that scrubs the console. The consequence is stated rather than hidden — an operator who can read
 * the control root, or the durable journal, reads this value in the clear.
 *
 * A permission that is not equivalent — the channel cannot be read, or it cannot be deleted — is
 * answered with a REFUSAL, never with a silent adaptation to an empty value. The refusal says what
 * the filesystem refused, and the terminal that carries it names the control directory, so the
 * operator can go and look at what is there.
 *
 * No protection is claimed beyond the permissions of the control root: this type does not encrypt
 * the value, does not store it, does not erase it securely, and does not change how the value
 * travels.
 *
 * ## Where the value lives
 *
 * The value this type carries is, in turn:
 *
 *  - the spool file of the control directory, whose name belongs to [ShellProtocolLayout], written
 *    by the child and read by the executor;
 *  - the captured stdout of the terminal that publishes it, held in memory while the terminal
 *    travels;
 *  - the encoded output of the step, a JSON envelope whose `value` field holds the text, written
 *    into the durable journal as a plain string — in the clear, because nothing redacts it and
 *    nothing encrypts it;
 *  - the stdout value the step projects to its caller.
 *
 * This type is the READING of those places, not a place of its own: it holds no value beyond the
 * one it read, and it writes nothing.
 *
 * ## What this does NOT own
 *
 *  - The channel's NAME, which belongs to [ShellProtocolLayout].
 *  - The channel's RETENTION, which belongs to the read-and-dispose the executor performs under
 *    [CaptureRetainPolicy].
 *  - The DECISION about what any of these facts means for an execution, which belongs to
 *    [ShellTerminalDecision].
 */
sealed interface CapturedStdoutRead {

    /**
     * Nobody recorded anything.
     *
     * The channel is absent, so there is no value to report. This is not the same fact as a
     * program that printed nothing: here nobody wrote, and the absence is the observation.
     */
    data object Absent : CapturedStdoutRead

    /**
     * The program printed this.
     *
     * The text may be EMPTY, and an empty text is a value: it says the program printed nothing.
     * The text is trimmed, which is the semantics every consumer of a captured stdout already
     * depends on.
     */
    data class Present(val text: String) : CapturedStdoutRead

    /**
     * The channel is there and could not be read.
     *
     * There is NO value here, and none may be invented: an empty text would say the program
     * printed nothing, and nobody knows that. [reason] says what the filesystem refused.
     */
    data class Unreadable(val reason: String) : CapturedStdoutRead

    /**
     * The value was read, and the channel could not be disposed of.
     *
     * [text] is the value that was read, and it is a value: the disposal was refused AFTER the
     * reading, so the reading is not in doubt. What is broken is the protocol's promise that the
     * channel is gone once it has been read, and [reason] says what the filesystem refused.
     */
    data class NotDisposed(val text: String, val reason: String) : CapturedStdoutRead
}
