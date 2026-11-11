package dev.rubentxu.pipeline.v2.sdk.files

/**
 * The quota the product reads a file under.
 *
 * ## The number
 *
 * Ten mebibytes: 10 485 760 bytes. It is the size the product already reads a
 * whole file under elsewhere — the limit of the `readFile` step — and it is
 * the limit the readers of JSON, of YAML and of the authorizations of the
 * local service share.
 *
 * ## Why ONE number
 *
 * Three readers that carry a limit each are three limits to keep in step, and
 * the first time one of them is raised without the others the product answers
 * one question in three ways. The number lives here, in the module that owns
 * the reading of files, and every reader takes it from here.
 *
 * ## Where an operator reads it
 *
 * The README of the product states it, and the KDoc of every reader names it.
 * A limit that nobody can read is a limit that nobody can plan around.
 *
 * ## What it does NOT bound
 *
 * It bounds ONE read. It is not a budget of the process and not a framework of
 * budgets: a file is read whole or not at all, and a file that does not fit is
 * refused rather than truncated.
 */
object ReadQuota {

    /**
     * The limit one read of a file runs under: ten mebibytes.
     *
     * A file over it is refused with the size it has and with this number,
     * and none of its content is produced.
     */
    const val MAX_FILE_BYTES: Long = 10L * 1024L * 1024L
}
