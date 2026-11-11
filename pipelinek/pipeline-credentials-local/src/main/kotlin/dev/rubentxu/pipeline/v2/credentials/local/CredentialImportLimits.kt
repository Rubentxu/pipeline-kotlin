package dev.rubentxu.pipeline.v2.credentials.local

/**
 * The ceilings an imported credential runs under.
 *
 * ## What they bound
 *
 * The bytes of an imported credential come from outside the product: a file the
 * operator names, or a stream a pipeline writes. Nothing about them is bounded
 * by anything this module controls, so the ceilings are stated here and applied
 * where the bytes are read, while the bytes are being read.
 *
 * ## Where each number comes from
 *
 * - [maxEntries] is the number of parts the canonical form of a credential can
 *   frame: the count of the parts is written as one byte, and one of those
 *   parts carries the count of the entries itself.
 * - [maxEntryBytes] and [maxContentBytes] are the store's own ceiling of the
 *   secret an entry may hold. A value above it could never be stored, so
 *   refusing it at the boundary costs the operator nothing and the product a
 *   heap.
 * - [maxArchiveBytes] is the size of an archive as it is handed over. It is
 *   read whole, so it is the ceiling of that read; the bytes it expands to are
 *   bounded by the other two.
 *
 * ## Why they are values
 *
 * A test that has to build a megabyte to reach a ceiling is a slow test, and a
 * slow test is a test that stops being run. The ceilings are therefore
 * parameters, and [DEFAULT] carries the ones the product uses.
 */
internal data class CredentialImportLimits(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxEntryBytes: Long = DEFAULT_MAX_CONTENT_BYTES,
    val maxContentBytes: Long = DEFAULT_MAX_CONTENT_BYTES,
    val maxArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
) {
    companion object {

        /**
         * The entries an archive may carry. The count of the parts is one byte
         * and one part carries the count of the entries, so the entries are
         * one short of what a byte can hold.
         */
        const val DEFAULT_MAX_ENTRIES: Int = 254

        /** The bytes one credential may carry, which are the store's own ceiling. */
        const val DEFAULT_MAX_CONTENT_BYTES: Long = StoreCodec.MAX_PLAINTEXT_LENGTH

        /**
         * The bytes an archive may hold as it is handed over. It is read whole,
         * so this is the ceiling of that read, and it is four times the
         * ceiling of the content: an archive whose content does not compress
         * is a little larger than its content, and the framing of the archive
         * adds more.
         */
        const val DEFAULT_MAX_ARCHIVE_BYTES: Long = 4L * 1024L * 1024L

        /** The ceilings the product applies. */
        val DEFAULT = CredentialImportLimits()
    }
}
