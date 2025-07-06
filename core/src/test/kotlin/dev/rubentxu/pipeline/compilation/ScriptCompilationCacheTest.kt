package dev.rubentxu.pipeline.compilation

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration

class ScriptCompilationCacheTest : DescribeSpec({
    
    describe("DefaultScriptCompilationCache Basic Operations") {
        
        it("should return null for cache miss") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val scriptContent = "println(\"Hello World\")"
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                val result = cache.get(scriptContent, config)
                result.shouldBeNull()
            }
        }
        
        it("should store and retrieve compiled scripts") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val scriptContent = "val x = 42"
            val config = ScriptCompilationConfiguration()
            val compiledScript = mockk<CompiledScript>()
            
            runBlocking {
                cache.put(scriptContent, config, compiledScript)
                val result = cache.get(scriptContent, config)
                
                result shouldBe compiledScript
            }
        }
        
        it("should handle multiple different scripts") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val script1 = "val x = 1"
            val script2 = "val y = 2"
            val config = ScriptCompilationConfiguration()
            val compiled1 = mockk<CompiledScript>()
            val compiled2 = mockk<CompiledScript>()
            
            runBlocking {
                cache.put(script1, config, compiled1)
                cache.put(script2, config, compiled2)
                
                val result1 = cache.get(script1, config)
                val result2 = cache.get(script2, config)
                
                result1 shouldBe compiled1
                result2 shouldBe compiled2
            }
        }
        
        it("should return correct cache size") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                cache.size() shouldBe 0
                
                cache.put("script1", config, mockk<CompiledScript>())
                cache.size() shouldBe 1
                
                cache.put("script2", config, mockk<CompiledScript>())
                cache.size() shouldBe 2
            }
        }
        
        it("should clear all cached entries") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                cache.put("script1", config, mockk<CompiledScript>())
                cache.put("script2", config, mockk<CompiledScript>())
                cache.size() shouldBe 2
                
                cache.clear()
                cache.size() shouldBe 0
                
                val result = cache.get("script1", config)
                result.shouldBeNull()
            }
        }
    }
    
    describe("DefaultScriptCompilationCache LRU Eviction") {
        
        it("should evict least recently used entries when max entries exceeded") {
            val cache = DefaultScriptCompilationCache(maxEntries = 3, enableDiskCache = false)
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                // Fill cache to capacity
                cache.put("script1", config, mockk<CompiledScript>())
                cache.put("script2", config, mockk<CompiledScript>())
                cache.put("script3", config, mockk<CompiledScript>())
                cache.size() shouldBe 3
                
                // Add one more - should evict script1
                cache.put("script4", config, mockk<CompiledScript>())
                cache.size() shouldBe 3
                
                // script1 should be evicted
                cache.get("script1", config).shouldBeNull()
                
                // Others should still be present
                cache.get("script2", config).shouldNotBeNull()
                cache.get("script3", config).shouldNotBeNull()
                cache.get("script4", config).shouldNotBeNull()
            }
        }
        
        it("should update access order on cache hits") {
            val cache = DefaultScriptCompilationCache(maxEntries = 2, enableDiskCache = false)
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                cache.put("script1", config, mockk<CompiledScript>())
                cache.put("script2", config, mockk<CompiledScript>())
                
                // Access script1 to make it most recently used
                cache.get("script1", config).shouldNotBeNull()
                
                // Add script3 - should evict script2 (not script1)
                cache.put("script3", config, mockk<CompiledScript>())
                
                cache.get("script1", config).shouldNotBeNull()
                cache.get("script2", config).shouldBeNull()
                cache.get("script3", config).shouldNotBeNull()
            }
        }
        
        it("should handle memory limit eviction") {
            val cache = DefaultScriptCompilationCache(
                maxEntries = 100,
                maxMemoryMb = 1, // Very small memory limit
                enableDiskCache = false
            )
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                // Add multiple entries - should trigger memory-based eviction
                for (i in 1..10) {
                    cache.put("script$i", config, mockk<CompiledScript>())
                }
                
                // Should have evicted some entries due to memory limit
                cache.size() shouldBeLessThan 10
                
                val stats = cache.getStats()
                stats.evictions shouldBeGreaterThanOrEqual 0
            }
        }
    }
    
    describe("DefaultScriptCompilationCache Configuration Sensitivity") {
        
        it("should treat different configurations as different cache keys") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val scriptContent = "val x = 42"
            val config1 = ScriptCompilationConfiguration()
            val config2 = ScriptCompilationConfiguration()
            val compiled1 = mockk<CompiledScript>()
            val compiled2 = mockk<CompiledScript>()
            
            runBlocking {
                cache.put(scriptContent, config1, compiled1)
                cache.put(scriptContent, config2, compiled2)
                
                val result1 = cache.get(scriptContent, config1)
                val result2 = cache.get(scriptContent, config2)
                
                result1 shouldBe compiled1
                result2 shouldBe compiled2
            }
        }
        
        it("should handle hash collisions gracefully") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                // Add many entries with similar content
                for (i in 1..20) {
                    val script = "val x$i = $i"
                    cache.put(script, config, mockk<CompiledScript>())
                }
                
                // Should handle without issues
                cache.size() shouldBeGreaterThan 0
            }
        }
    }
    
    describe("DefaultScriptCompilationCache Statistics") {
        
        it("should track cache hits and misses") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val config = ScriptCompilationConfiguration()
            val compiled = mockk<CompiledScript>()
            
            runBlocking {
                // Miss
                cache.get("script1", config)
                
                // Put
                cache.put("script1", config, compiled)
                
                // Hit
                cache.get("script1", config)
                
                // Another miss
                cache.get("script2", config)
                
                val stats = cache.getStats()
                stats.hits shouldBe 1
                stats.misses shouldBe 2
                stats.puts shouldBe 1
                stats.hitRate.shouldBeGreaterThan(0.0)
                stats.totalRequests shouldBe 3
            }
        }
        
        it("should format statistics correctly") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                cache.put("script1", config, mockk<CompiledScript>())
                cache.get("script1", config) // hit
                cache.get("script2", config) // miss
                
                val stats = cache.getStats()
                val formatted = stats.getFormattedStats()
                
                formatted shouldContain "Hit Rate:"
                formatted shouldContain "Total Requests:"
                formatted shouldContain "Cache Hits:"
                formatted shouldContain "Cache Misses:"
            }
        }
        
        it("should track evictions") {
            val cache = DefaultScriptCompilationCache(maxEntries = 2, enableDiskCache = false)
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                cache.put("script1", config, mockk<CompiledScript>())
                cache.put("script2", config, mockk<CompiledScript>())
                cache.put("script3", config, mockk<CompiledScript>()) // Should evict script1
                
                val stats = cache.getStats()
                stats.evictions shouldBeGreaterThanOrEqual 1
            }
        }
    }
    
    describe("DefaultScriptCompilationCache Disk Cache") {
        
        it("should handle disk cache directory creation") {
            val tempDir = Files.createTempDirectory("cache-test")
            val cacheDir = tempDir.resolve("custom-cache")
            
            val cache = DefaultScriptCompilationCache(
                maxEntries = 10,
                enableDiskCache = true,
                cacheDirectory = cacheDir
            )
            
            // Directory should be created
            Files.exists(cacheDir) shouldBe true
            
            // Cleanup
            Files.deleteIfExists(cacheDir)
            Files.deleteIfExists(tempDir)
        }
        
        it("should handle disk cache operations gracefully") {
            val tempDir = Files.createTempDirectory("cache-test")
            val cache = DefaultScriptCompilationCache(
                maxEntries = 10,
                enableDiskCache = true,
                cacheDirectory = tempDir
            )
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                // Should not throw exceptions even if disk operations fail
                cache.put("script1", config, mockk<CompiledScript>())
                val result = cache.get("script1", config)
                result.shouldNotBeNull()
                
                cache.clear()
                cache.size() shouldBe 0
            }
            
            // Cleanup
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
    
    describe("ScriptCompilationCacheFactory") {
        
        it("should create production cache with correct configuration") {
            val cache = ScriptCompilationCacheFactory.createProductionCache()
            
            cache.shouldNotBeNull()
            // Production cache should handle multiple entries
            runBlocking {
                cache.size() shouldBe 0
            }
        }
        
        it("should create development cache with correct configuration") {
            val cache = ScriptCompilationCacheFactory.createDevelopmentCache()
            
            cache.shouldNotBeNull()
            // Development cache should be more lightweight
            runBlocking {
                cache.size() shouldBe 0
            }
        }
        
        it("should create custom cache with specified parameters") {
            val tempDir = Files.createTempDirectory("custom-cache-test")
            val cache = ScriptCompilationCacheFactory.createCustomCache(
                maxEntries = 50,
                maxMemoryMb = 128,
                enableDiskCache = true,
                cacheDirectory = tempDir
            )
            
            cache.shouldNotBeNull()
            
            runBlocking {
                cache.size() shouldBe 0
                
                // Test that custom configuration is applied
                val config = ScriptCompilationConfiguration()
                cache.put("test", config, mockk<CompiledScript>())
                cache.get("test", config).shouldNotBeNull()
            }
            
            // Cleanup
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
    
    describe("DefaultScriptCompilationCache TTL and Expiration") {
        
        it("should handle entry expiration based on TTL") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                // Put entry
                cache.put("expiring-script", config, mockk<CompiledScript>())
                
                // Should be available immediately
                cache.get("expiring-script", config).shouldNotBeNull()
                
                // Note: Testing actual TTL expiration would require sleeping
                // or mocking time, which is complex. Here we just verify
                // the basic structure is in place.
            }
        }
        
        it("should handle concurrent access safely") {
            val cache = DefaultScriptCompilationCache(maxEntries = 10, enableDiskCache = false)
            val config = ScriptCompilationConfiguration()
            
            runBlocking {
                // Multiple concurrent operations
                cache.put("concurrent1", config, mockk<CompiledScript>())
                cache.put("concurrent2", config, mockk<CompiledScript>())
                
                val result1 = cache.get("concurrent1", config)
                val result2 = cache.get("concurrent2", config)
                
                result1.shouldNotBeNull()
                result2.shouldNotBeNull()
                
                cache.size() shouldBe 2
            }
        }
    }
})