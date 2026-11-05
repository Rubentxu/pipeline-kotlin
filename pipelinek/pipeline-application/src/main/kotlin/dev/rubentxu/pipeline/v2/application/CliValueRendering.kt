package dev.rubentxu.pipeline.v2.application

/**
 * How a refusal draws the value the operator wrote.
 *
 * ## The rule
 *
 * A value with visible characters is drawn as it is. A value with NO visible
 * characters is drawn between single quotes: the empty one as `''` and the one
 * of only spaces as `' '`.
 *
 * ## Why the empty value needs a form of its own
 *
 * A refusal names the value that is wrong because the value is what the
 * operator has to change, and a sentence that leaves it out leaves the
 * operator to guess. That is the whole purpose of naming it.
 *
 * A value that is drawn RAW stops doing that job the moment it is empty: the
 * sentence keeps its shape and loses its content, and the operator is left
 * reading a phrase with a hole where the data was:
 *
 *     Error: unexpected extra argument:
 *
 * The operator cannot tell a value that was empty from a message that was cut,
 * and cannot tell it from a value that was a space. The refusal says WHAT
 * class of mistake there was and not WHICH value caused it.
 *
 * ## Why the empty one is drawn `''` and the space one `' '`
 *
 * `''` is the notation this CLI already uses for a value that is not there:
 * `inspect --fields ""` answers `unknown token ''` and `saga inspect
 * --format ""` answers `got ''`. A blank of only spaces is NOT empty, so it is
 * not drawn `''`: it is drawn `' '`, and the operator sees the space they
 * typed instead of a lie about the value.
 *
 * ## Why a value with visible characters keeps its drawing
 *
 * Because a value that is already visible cannot be missing, and changing how
 * it is drawn would change the text of every refusal that names one — a
 * contract other people read. The rule only ever fills a hole; it never
 * rewrites a sentence that was already whole.
 */
internal fun renderValue(value: String): String = if (value.isBlank()) "'$value'" else value
