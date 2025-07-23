package dev.rubentxu.pipeline.logger.model

import java.nio.ByteBuffer

/**
 * Ultra-lightweight binary log event reference using value class optimization.
 * 
 * This class packs position and size into a single Long to minimize allocation
 * and provide zero-copy access to binary log data stored in Direct ByteBuffer.
 * 
 * Memory layout: [position:32][size:32] packed into Long
 * 
 * Used by RingLogBuffer for high-performance logging with JCTools queue.
 */
@JvmInline
value class BinaryLogEvent(private val packed: Long) {
    
    /**
     * Buffer position where the log event starts (0-based index)
     */
    val position: Int get() = (packed shr 32).toInt()
    
    /**
     * Size of the log event in bytes
     */
    val size: Int get() = packed.toInt()
    
    /**
     * Constructor from position and size
     */
    constructor(position: Int, size: Int) : this(
        (position.toLong() shl 32) or (size.toLong() and 0xFFFFFFFFL)
    )
    
    /**
     * Check if this event reference is valid
     */
    fun isValid(): Boolean = position >= 0 && size > 0
    
    /**
     * Create a ByteBuffer slice for this event from the main buffer
     */
    fun slice(mainBuffer: ByteBuffer): ByteBuffer {
        return mainBuffer.slice(position, size)
    }
    
    override fun toString(): String = "BinaryLogEvent(position=$position, size=$size)"
    
    companion object {
        /**
         * Invalid/null event reference
         */
        val INVALID = BinaryLogEvent(-1, 0)
        
        /**
         * Maximum size supported (due to int packing)
         */
        const val MAX_SIZE = Int.MAX_VALUE
        
        /**
         * Maximum position supported (due to int packing)
         */
        const val MAX_POSITION = Int.MAX_VALUE
    }
}