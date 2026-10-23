package dev.rubentxu.pipeline.v2.output.store

/**
 * Who owns a stream's directory.
 *
 * The plane files a stream's bytes under a FOLDED name: every character outside `[A-Za-z0-9._-]`
 * becomes `_`. The fold is lossy, and two DIFFERENT identities can fold to the same name — the
 * identity `run/op:a/stdout` and the identity `run/op_a/stdout` are two streams that land in one
 * directory.
 *
 * A directory that NAMES its owner turns that ambiguity into a fact the store can check. The
 * identity that wrote the bytes is recorded beside them, and an identity that does not match it
 * is refused instead of being answered with somebody else's bytes.
 *
 * The rule is pure: it is a function of what the directory records and of what the caller claims,
 * so it can be exercised without a filesystem, without a store and without a clock.
 */
internal sealed interface StreamDirectoryOwnership {

    /** The directory records no owner: the claimer may take it, and the claim is recorded. */
    data object Unclaimed : StreamDirectoryOwnership

    /** The directory records the claiming identity: the claimer owns it. */
    data object Owned : StreamDirectoryOwnership

    /** The directory records [owner], and [claimer] is a different identity. */
    data class Foreign(val owner: String, val claimer: String) : StreamDirectoryOwnership
}

/**
 * Who owns the directory, given what it records and who claims it.
 *
 * @param recorded the identity the directory records, or `null` when it records none.
 * @param claimed the identity that wants to use the directory.
 * @return [StreamDirectoryOwnership.Unclaimed] for a directory that names nobody,
 * [StreamDirectoryOwnership.Owned] for one that names the claimer, and
 * [StreamDirectoryOwnership.Foreign] for one that names somebody else.
 */
internal fun decideStreamDirectoryOwnership(
    recorded: String?,
    claimed: String,
): StreamDirectoryOwnership = when {
    recorded == null -> StreamDirectoryOwnership.Unclaimed
    recorded == claimed -> StreamDirectoryOwnership.Owned
    else -> StreamDirectoryOwnership.Foreign(owner = recorded, claimer = claimed)
}

/**
 * A stream directory that belongs to another identity.
 *
 * Two identities that fold to the same name are two streams, and the store refuses the second one
 * instead of letting it write into the first one's directory: bytes from two operations in one
 * directory cannot be attributed to either of them afterwards.
 */
internal class StreamDirectoryOwnedByAnotherIdentity(
    val owner: String,
    val claimer: String,
) : IllegalStateException(
    "stream $claimer: the directory that holds those bytes belongs to $owner; two identities " +
        "that fold to the same name must not share one directory",
)
