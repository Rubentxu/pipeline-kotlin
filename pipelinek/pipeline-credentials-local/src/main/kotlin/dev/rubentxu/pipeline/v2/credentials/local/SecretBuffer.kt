package dev.rubentxu.pipeline.v2.credentials.local

/**
 * A buffer of secret bytes, with one owner and one way to forget them.
 *
 * ## Why this type exists
 *
 * A byte array that holds a key cannot be wiped by whoever happens to hold it: the
 * derivation returns a new array on every call, and the array that has to be wiped is
 * the one that derivation returned. The owner of the secret is not the owner of the
 * array, and that gap is where a key survives the operation that needed it.
 *
 * This type is that ownership. The buffer holds the bytes, hands them out while it
 * still has them, and [wipe] is the ONE operation that makes them unreadable.
 *
 * ## Why the owner is told
 *
 * [wipe] tells its owner that the buffer is gone. That is not a courtesy: it is what
 * makes «the bytes are wiped» and «the buffer is no longer held» **the same fact**.
 * A store that counted its buffers separately from wiping them could report that it
 * holds nothing while one of them was still full of key material, and the two
 * statements would drift apart without anybody noticing.
 *
 * ## What it does not promise
 *
 * A wiped buffer makes its bytes unreadable **for the owner**. It cannot reach into
 * the heap and clean a copy that somebody else took before the wipe, and it does not
 * pretend to: the guarantee belongs to whoever holds the bytes.
 */
internal class SecretBuffer private constructor(
    private var bytes: ByteArray?,
    private val onWiped: (SecretBuffer) -> Unit,
) {

    /**
     * The bytes, while this buffer still holds them.
     *
     * @return the array this buffer was given
     * @throws IllegalStateException when the buffer has been wiped
     */
    fun bytes(): ByteArray =
        checkNotNull(bytes) { "the secret buffer was wiped and holds no bytes" }

    /**
     * Makes the bytes unreadable, and tells the owner that it is done.
     *
     * Wiping a wiped buffer is not an error and does not tell the owner twice: the
     * second wipe has nothing to wipe and nobody to tell.
     */
    fun wipe() {
        val held = bytes ?: return
        held.fill(0)
        bytes = null
        onWiped(this)
    }

    companion object {

        /**
         * Takes ownership of [bytes].
         *
         * @param bytes the bytes to own
         * @param onWiped told once, when the buffer is wiped
         * @return a buffer that owns [bytes]
         */
        fun of(bytes: ByteArray, onWiped: (SecretBuffer) -> Unit = {}): SecretBuffer =
            SecretBuffer(bytes, onWiped)
    }
}
