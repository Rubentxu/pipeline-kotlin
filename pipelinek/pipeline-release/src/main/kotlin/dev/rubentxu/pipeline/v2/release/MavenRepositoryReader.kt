package dev.rubentxu.pipeline.v2.release

/**
 * What a repository holds at one path.
 *
 * The three states are three different facts, and the difference between them
 * is what a consumer needs to be told. A file the repository serves is a file
 * that resolves; a path the repository does not hold is a coordinate that
 * fails; and an answer that is neither of those is a channel that could not
 * be read, which is not the same as a channel that holds nothing.
 */
sealed interface MavenServedFile {

    /**
     * The repository served these bytes.
     *
     * [sha256] is the digest MEASURED from the bytes that arrived. It is
     * never a digest the repository claimed about itself: a registry that
     * answers with a checksum of its own is reporting a fact about the bytes
     * it holds, and the question here is whether those are the bytes.
     */
    data class Present(val sha256: String) : MavenServedFile

    /** The repository does not hold that path. */
    data object Absent : MavenServedFile

    /**
     * The repository answered something that is neither the file nor its
     * absence.
     *
     * [reason] says what happened, in the words of the answer: a channel
     * that answers 403 is a channel that needs a credential, and a channel
     * that answers 500 is a channel that is broken. Both are facts the
     * reader of the verdict needs, and neither is a file.
     */
    data class Unreadable(val reason: String) : MavenServedFile
}

/**
 * The port that reads a repository of Maven artifacts.
 *
 * The path is the one the file has inside the repository, which is the path
 * the publication manifest declares: `group/artifact/version/file`. The
 * reader resolves it against the repository it reads, so the caller does not
 * assemble a URL and does not have to know how the group is spelled.
 *
 * The port returns a digest and not the bytes: the decision compares digests,
 * and a port that handed over whole artifacts would make every caller hold
 * the bytes of a release it only needs to compare.
 */
interface MavenRepositoryReader {

    /** What the repository holds at [path]. */
    fun read(path: String): MavenServedFile
}

/**
 * [value] as it may be written in a report.
 *
 * A path that comes from a document this check did not write can carry a
 * newline, and a report a newline can forge is a report nobody can read: the
 * line the reader sees says one thing and the line the publisher quotes
 * says another. The control characters are replaced by a dot and nothing
 * else is changed, so the text still names the thing it names.
 */
internal fun quoteForReport(value: String): String = buildString(value.length) {
    value.forEach { character ->
        append(if (character.isISOControl()) '.' else character)
    }
}
