package dev.rubentxu.pipeline.v2.output.store

/**
 * One bounded, single-line description of a substrate failure.
 *
 * ## Why ONE function and not one per class
 *
 * The pin substrate had two private copies of this, one in the store and one in the adapter, and
 * they did not agree: one took the first line of the message and the other took the first 120
 * characters of it, so the same failure was reported two different ways depending on which class
 * caught it. A diagnostic that changes shape depending on the reader is a diagnostic nobody can
 * match, and matching a diagnostic is what a failing run is made of.
 *
 * ## What it is for
 *
 * A substrate failure has to reach the caller as TEXT, because the refusal types that carry it
 * ([PinRefusal.StorageError], [DirectoryPinAnswer.Unavailable]) hold a string rather than a
 * throwable. So the text has to survive the trip: bounded in length, on one line, and naming the
 * exception that failed rather than the stack that unwound it.
 */
internal fun Throwable.shortDiagnostic(): String {
    val cls = this::class.simpleName ?: javaClass.name
    val msg = message?.lineSequence()?.firstOrNull()?.take(MAX_DIAGNOSTIC_LEN)?.replace('\n', ' ')
    return if (msg.isNullOrBlank()) cls else "$cls: $msg"
}

/** The longest diagnostic kept; a longer failure is truncated rather than carried whole. */
private const val MAX_DIAGNOSTIC_LEN: Int = 200
