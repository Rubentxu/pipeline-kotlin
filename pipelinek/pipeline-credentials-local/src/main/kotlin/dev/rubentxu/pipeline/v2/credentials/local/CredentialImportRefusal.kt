package dev.rubentxu.pipeline.v2.credentials.local

/**
 * The refusals an imported credential can answer with.
 *
 * ## What a refusal may say
 *
 * It names the ceiling that was crossed, and it never says what the
 * credential held: not the bytes, not the name of an entry, not the size of a
 * secret. A refusal about a credential says how much was too much, and the
 * operator reads the value they typed themselves.
 *
 * ## Why it extends [IllegalArgumentException]
 *
 * Because that is what the command already answers with, and a caller that
 * knows only the exception type still gets the message and the exit the other
 * refusals of the command get.
 */
internal sealed class CredentialImportRefusal(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {

    /**
     * The bytes handed over are more than the ceiling allows.
     *
     * @param subject what was too large, in this module's own words.
     * @param limit the ceiling that was crossed, in bytes.
     */
    class InputTooLarge(val subject: String, val limit: Long) :
        CredentialImportRefusal("$subject is larger than the limit of $limit bytes")

    /** The archive holds more entries than the format can frame. */
    class TooManyEntries(val limit: Int) :
        CredentialImportRefusal("the archive holds more entries than the limit of $limit")

    /** The archive expands to more bytes in total than the ceiling allows. */
    class TooLargeInTotal(val limit: Long) :
        CredentialImportRefusal("the archive expands to more bytes than the limit of $limit")

    /** Two entries of the archive carry the same name. */
    class DuplicateEntryNames :
        CredentialImportRefusal("the archive names two entries the same way")

    /** An entry name is not one the format can carry. */
    class EntryNameNotCarried(val limit: Int) :
        CredentialImportRefusal("an entry name of the archive is not between 1 and $limit bytes long")

    /** The credential does not fit in the form the store keeps. */
    class LargerThanTheStoreHolds(val limit: Long) :
        CredentialImportRefusal("the credential is larger than the store can hold, whose limit is $limit bytes")

    /**
     * The form the store keeps cannot carry this credential at all.
     *
     * The cause is kept for the reader who has to know why, and the message
     * says nothing about it: the reason the form gives names the entry, and
     * the name of an entry is part of what the credential holds.
     */
    class NotCarriedByTheForm(cause: Throwable) :
        CredentialImportRefusal("the credential is not one the form the store keeps can carry", cause)
}
