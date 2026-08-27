package dev.rubentxu.pipeline.v2.domain

import java.nio.charset.StandardCharsets

/**
 * Typed channel for secret values in the pipeline engine.
 *
 * ## Purpose
 *
 * [SecretHandle] is the single typed channel through which secret values flow.
 * It wraps the raw `ByteArray` containing the secret and provides:
 * - [use] for scoped access with automatic cleanup
 * - [close] for wipe-on-exit (fills internal buffer with zeros)
 * - [materialize] for the single coercion point at ProcessBuilder
 *
 * ## Design: Typed Channel Pattern
 *
 * The key insight is that `String` is the dangerous type - once a secret is
 * converted to String, it may appear in stack traces, logs, heap dumps, or
 * be subject to string interning. By keeping secrets as `ByteArray` and
 * providing a single [materialize] call that converts to String only at
 * the ProcessBuilder choke point, we minimize the window where secrets
 * exist as GC-eligible objects.
 *
 * ## Coercion Choke
 *
 * The ONLY place where [SecretHandle] bytes are converted to String is:
 * ```
 * val env: Map<String, SecretHandle> = ...
 * pb.environment().putAll(env.mapValues { it.value.materialize() })
 * ```
 * This happens in [dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.launch]
 * and [dev.rubentxu.pipeline.v2.application.durable.ShExecution.executeNonDurable].
 *
 * ## Wipe Contract
 *
 * When [close] is called, the internal byte array is filled with zeros.
 * This is verified by [SecretHandleContractTest] which asserts that after
 * close, `bytes.contentEquals(ByteArray(size))` returns true.
 *
 * ## JVM 21 MemorySegment Upgrade Path
 *
 * When the codebase upgrades to JVM 21, consider using JEP 454
 * (Foreign Function & Memory API) to use scoped MemorySegment handles
 * that are automatically closed when exiting a try-with-resources block.
 * This would provide stronger guarantees than the manual wipe pattern.
 *
 * @see dev.rubentxu.pipeline.v2.credentials.api.CredentialScope for the scope
 *      that manages handle lifetimes
 * @see dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor for
 *      the single coercion point
 */
class SecretHandle private constructor(
    @PublishedApi internal val bytes: ByteArray,
    private val masked: Boolean = false
) : AutoCloseable, Destroyable {

    /**
     * Factory for creating a SecretHandle for testing.
     * This is only visible for testing purposes.
     */
    @PublishedApi
    internal constructor(bytes: ByteArray) : this(bytes, false)

    /**
     * Executes a block with access to the secret bytes.
     *
     * After the block completes, the handle is closed and the
     * internal buffer is wiped to zeros.
     *
     * @param block The block to execute with access to the secret bytes
     * @return The result of the block
     */
    inline fun <R> use(block: (ByteArray) -> R): R {
        try {
            return block(bytes)
        } finally {
            close()
        }
    }

    /**
     * Wipes the internal byte array by filling it with zeros.
     *
     * This method is idempotent - calling it multiple times is safe.
     * The second call is a no-op because the bytes are already zeros.
     */
    override fun close() {
        wipeInternal()
    }

    /**
     * Alias for [close] - implements [Destroyable].
     */
    override fun destroy() {
        wipeInternal()
    }

    /**
     * Returns the size of the secret in bytes.
     */
    val sizeBytes: Int
        get() = bytes.size

    /**
     * Returns a string representation that does NOT expose the secret.
     * Shows only the size in bytes.
     */
    override fun toString(): String = "Secret(sizeBytes=$sizeBytes)"

    /**
     * Materializes the secret as a String.
     *
     * This should ONLY be called at the ProcessBuilder coercion choke.
     * After materialization, the internal buffer is NOT wiped - the
     * String is passed to the child process and the handle is later
     * closed by the [CredentialScope].
     *
     * ## Performance Note
     *
     * This creates a new String from the byte array. The String will
     * be subject to GC, string interning, and potentially stack traces.
     * This is unavoidable because ProcessBuilder.environment() requires
     * String values. The window of exposure is minimized by:
     * 1. Only materializing at the last possible moment (process spawn)
     * 2. Immediately closing the handle after ProcessBuilder consumes it
     * 3. Wiping the handle's internal buffer in a finally block
     *
     * @return The secret value as a String
     */
    fun materialize(): String {
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * Internal wipe implementation.
     * Fills the byte array with zeros.
     */
    private fun wipeInternal() {
        if (bytes.isNotEmpty()) {
            bytes.fill(0)
        }
    }

    companion object {
        /**
         * Factory for creating a SecretHandle from a plain String.
         * The string is encoded to UTF-8 bytes.
         *
         * @param value The plain text value
         * @return A new SecretHandle wrapping the UTF-8 encoded bytes
         */
        fun plain(value: String): SecretHandle {
            return SecretHandle(value.toByteArray(StandardCharsets.UTF_8), masked = false)
        }

        /**
         * Factory for creating a SecretHandle from raw bytes.
         * Use this for secrets that may contain non-UTF-8 data.
         *
         * @param value The raw secret bytes
         * @return A new SecretHandle wrapping the bytes
         */
        fun secret(value: ByteArray): SecretHandle {
            return SecretHandle(value, masked = false)
        }

        /**
         * Factory for creating a masked SecretHandle.
         * Masked handles are used for PATH and other environment
         * variables that should not be subject to redaction.
         *
         * @param value The masked value
         * @return A new masked SecretHandle
         */
        fun masked(value: String): SecretHandle {
            return SecretHandle(value.toByteArray(StandardCharsets.UTF_8), masked = true)
        }
    }
}

/**
 * Marker interface for types that support destroyable resources.
 * Used by [SecretHandle] to provide a common interface for cleanup.
 */
interface Destroyable {
    /**
     * Destroys the underlying resource.
     */
    fun destroy()
}
