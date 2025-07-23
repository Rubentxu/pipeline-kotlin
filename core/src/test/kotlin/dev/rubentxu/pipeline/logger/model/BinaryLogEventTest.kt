package dev.rubentxu.pipeline.logger.model

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.positiveInt
import io.kotest.property.checkAll
import java.nio.ByteBuffer

/**
 * Comprehensive test suite for BinaryLogEvent value class.
 * 
 * Tests the ultra-lightweight binary log event reference system including:
 * - Position/size packing and unpacking correctness
 * - Edge cases and boundary conditions
 * - ByteBuffer slice operations
 * - Zero-allocation value class behavior
 * - Companion object constants and utilities
 */
class BinaryLogEventTest : DescribeSpec({
    
    describe("BinaryLogEvent value class") {
        
        describe("constructor and property access") {
            
            it("should correctly pack and unpack position and size") {
                val position = 12345
                val size = 6789
                
                val event = BinaryLogEvent(position, size)
                
                event.position shouldBe position
                event.size shouldBe size
            }
            
            it("should handle zero values correctly") {
                val event = BinaryLogEvent(0, 0)
                
                event.position shouldBe 0
                event.size shouldBe 0
            }
            
            it("should handle maximum integer values") {
                val maxPosition = Int.MAX_VALUE
                val maxSize = Int.MAX_VALUE
                
                val event = BinaryLogEvent(maxPosition, maxSize)
                
                event.position shouldBe maxPosition
                event.size shouldBe maxSize
            }
            
            it("should preserve values through packing/unpacking with property testing") {
                checkAll(
                    Arb.int(0..Int.MAX_VALUE),
                    Arb.int(0..Int.MAX_VALUE)
                ) { position, size ->
                    val event = BinaryLogEvent(position, size)
                    event.position shouldBe position
                    event.size shouldBe size
                }
            }
        }
        
        describe("validation") {
            
            it("should validate positive position and size as valid") {
                val event = BinaryLogEvent(100, 50)
                event.isValid() shouldBe true
            }
            
            it("should validate zero position with positive size as valid") {
                val event = BinaryLogEvent(0, 50)
                event.isValid() shouldBe true
            }
            
            it("should invalidate negative position") {
                val event = BinaryLogEvent(-1, 50)
                event.isValid() shouldBe false
            }
            
            it("should invalidate zero size") {
                val event = BinaryLogEvent(100, 0)
                event.isValid() shouldBe false
            }
            
            it("should invalidate negative size") {
                val event = BinaryLogEvent(100, -1)
                event.isValid() shouldBe false
            }
            
            it("should invalidate both negative position and size") {
                val event = BinaryLogEvent(-1, -1)
                event.isValid() shouldBe false
            }
        }
        
        describe("ByteBuffer slice operations") {
            
            it("should create correct ByteBuffer slice from main buffer") {
                // Given: Main buffer with test data
                val mainBuffer = ByteBuffer.allocate(1000)
                val testData = "Hello, World!".toByteArray()
                val position = 100
                
                // Write test data at specific position
                mainBuffer.position(position)
                mainBuffer.put(testData)
                mainBuffer.rewind()
                
                // When: Create event and slice
                val event = BinaryLogEvent(position, testData.size)
                val slice = event.slice(mainBuffer)
                
                // Then: Slice should contain the test data
                val readData = ByteArray(testData.size)
                slice.get(readData)
                
                readData shouldBe testData
                slice.capacity() shouldBe testData.size
            }
            
            it("should handle slice at buffer start") {
                val mainBuffer = ByteBuffer.allocate(100)
                val testData = "Start".toByteArray()
                mainBuffer.put(testData)
                mainBuffer.rewind()
                
                val event = BinaryLogEvent(0, testData.size)
                val slice = event.slice(mainBuffer)
                
                val readData = ByteArray(testData.size)
                slice.get(readData)
                
                readData shouldBe testData
            }
            
            it("should handle slice at buffer end") {
                val bufferSize = 100
                val testData = "End".toByteArray()
                val position = bufferSize - testData.size
                
                val mainBuffer = ByteBuffer.allocate(bufferSize)
                mainBuffer.position(position)
                mainBuffer.put(testData)
                mainBuffer.rewind()
                
                val event = BinaryLogEvent(position, testData.size)
                val slice = event.slice(mainBuffer)
                
                val readData = ByteArray(testData.size)
                slice.get(readData)
                
                readData shouldBe testData
            }
            
            it("should handle single byte slice") {
                val mainBuffer = ByteBuffer.allocate(100)
                val position = 50
                val testByte: Byte = 42
                
                mainBuffer.position(position)
                mainBuffer.put(testByte)
                mainBuffer.rewind()
                
                val event = BinaryLogEvent(position, 1)
                val slice = event.slice(mainBuffer)
                
                slice.get() shouldBe testByte
            }
        }
        
        describe("toString representation") {
            
            it("should provide readable string representation") {
                val event = BinaryLogEvent(123, 456)
                val toString = event.toString()
                
                toString shouldBe "BinaryLogEvent(position=123, size=456)"
            }
            
            it("should handle zero values in string representation") {
                val event = BinaryLogEvent(0, 0)
                val toString = event.toString()
                
                toString shouldBe "BinaryLogEvent(position=0, size=0)"
            }
            
            it("should handle maximum values in string representation") {
                val event = BinaryLogEvent(Int.MAX_VALUE, Int.MAX_VALUE)
                val toString = event.toString()
                
                toString shouldBe "BinaryLogEvent(position=${Int.MAX_VALUE}, size=${Int.MAX_VALUE})"
            }
        }
        
        describe("companion object constants") {
            
            it("should provide INVALID constant") {
                BinaryLogEvent.INVALID.position shouldBe -1
                BinaryLogEvent.INVALID.size shouldBe 0
                BinaryLogEvent.INVALID.isValid() shouldBe false
            }
            
            it("should provide MAX_SIZE constant") {
                BinaryLogEvent.MAX_SIZE shouldBe Int.MAX_VALUE
            }
            
            it("should provide MAX_POSITION constant") {
                BinaryLogEvent.MAX_POSITION shouldBe Int.MAX_VALUE
            }
            
            it("should allow creating events up to maximum values") {
                val event = BinaryLogEvent(
                    BinaryLogEvent.MAX_POSITION, 
                    BinaryLogEvent.MAX_SIZE
                )
                
                event.position shouldBe BinaryLogEvent.MAX_POSITION
                event.size shouldBe BinaryLogEvent.MAX_SIZE
                event.isValid() shouldBe true
            }
        }
        
        describe("value class behavior and performance") {
            
            it("should maintain reference equality for same values") {
                val event1 = BinaryLogEvent(100, 200)
                val event2 = BinaryLogEvent(100, 200)
                
                // Value classes with same values should be equal
                event1 shouldBe event2
            }
            
            it("should differentiate different values") {
                val event1 = BinaryLogEvent(100, 200)
                val event2 = BinaryLogEvent(101, 200)
                val event3 = BinaryLogEvent(100, 201)
                
                event1 shouldNotBe event2
                event1 shouldNotBe event3
                event2 shouldNotBe event3
            }
            
            it("should handle edge case bit patterns correctly") {
                // Test potential bit shifting edge cases
                val positions = listOf(0, 1, 0x7FFFFFFF, 0x80000000.toInt())
                val sizes = listOf(0, 1, 0x7FFFFFFF, 0x80000000.toInt())
                
                for (pos in positions) {
                    for (size in sizes) {
                        if (pos >= 0 && size >= 0) { // Only test valid combinations
                            val event = BinaryLogEvent(pos, size)
                            event.position shouldBe pos
                            event.size shouldBe size
                        }
                    }
                }
            }
        }
        
        describe("boundary conditions and edge cases") {
            
            it("should handle alternating bit patterns") {
                // Test patterns that might expose bit manipulation issues
                val position = 0x55555555 // Alternating bits
                val size = 0x33333333     // Different alternating pattern
                
                val event = BinaryLogEvent(position, size)
                
                event.position shouldBe position
                event.size shouldBe size
            }
            
            it("should handle all-ones and all-zeros patterns") {
                val maxValue = Int.MAX_VALUE // 0x7FFFFFFF
                val zeroValue = 0
                
                val event1 = BinaryLogEvent(maxValue, zeroValue)
                event1.position shouldBe maxValue
                event1.size shouldBe zeroValue
                
                val event2 = BinaryLogEvent(zeroValue, maxValue)
                event2.position shouldBe zeroValue
                event2.size shouldBe maxValue
            }
            
            it("should properly handle signed integer boundaries") {
                // Test the boundary between positive and negative when cast
                val boundary = 0x7FFFFFFF // Int.MAX_VALUE
                
                val event = BinaryLogEvent(boundary, boundary)
                
                event.position shouldBe boundary
                event.size shouldBe boundary
                event.isValid() shouldBe true
            }
        }
    }
})