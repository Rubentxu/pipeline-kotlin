package dev.rubentxu.pipeline.v2.sdk.scm.git

/**
 * A checkout record the durable sink refused.
 *
 * ## What this is
 * The events a checkout emits ARE the run's memory of the checkout: without them
 * the run cannot say what happened, cannot replay it and cannot explain it. A
 * write the sink refuses is therefore not a nuisance to warn about — it is the
 * loss of a fact the run needed, and this exception is how that loss travels.
 *
 * ## Why the checkout fails because of it
 * A checkout that carried on would report a success the run cannot prove, after
 * doing work nobody can account for. Failing keeps the two together: what the
 * run could not record, the run did not do.
 *
 * @param eventKind The kind of the event the sink refused.
 * @param cause The failure the sink reported.
 */
class CheckoutRecordRefused(
    /** The kind of the event the sink refused. */
    val eventKind: String,
    cause: Throwable,
) : RuntimeException("the checkout record $eventKind could not be written: ${cause.message}", cause)
