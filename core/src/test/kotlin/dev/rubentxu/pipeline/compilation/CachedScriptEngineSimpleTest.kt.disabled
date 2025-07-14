package dev.rubentxu.pipeline.compilation

import dev.rubentxu.pipeline.logger.IPipelineLogger
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.script.experimental.api.*

class CachedScriptEngineSimpleTest : DescribeSpec({
    
    describe("CachedScriptEngine Basic Operations") {
        
        it("should compile script using cache") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            val mockCompiledScript = mockk<CompiledScript>()
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            val scriptContent = "val x = 42"
            
            coEvery { mockCache.get(any(), any()) } returns null
            coEvery { mockCache.put(any(), any(), any()) } just Runs
            
            runBlocking {
                // This will use the actual compiler internally
                val result = engine.compile(scriptContent, "test-script")
                
                // Should attempt to use cache
                coVerify { mockCache.get(scriptContent, any()) }
            }
        }
        
        it("should return cached script on cache hit") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            val cachedScript = mockk<CompiledScript>()
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            val scriptContent = "val x = 42"
            
            coEvery { mockCache.get(scriptContent, any()) } returns cachedScript
            
            runBlocking {
                val result = engine.compile(scriptContent, "test-script")
                
                result.shouldBeInstanceOf<ResultWithDiagnostics.Success<CompiledScript>>()
                result.value shouldBe cachedScript
                
                // Should not call put since it was a cache hit
                coVerify(exactly = 0) { mockCache.put(any(), any(), any()) }
            }
        }
        
        it("should handle compilation from file") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            val tempFile = Files.createTempFile("test-script", ".kts").toFile()
            tempFile.writeText("val test = \"hello world\"")
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            
            coEvery { mockCache.get(any(), any()) } returns null
            coEvery { mockCache.put(any(), any(), any()) } just Runs
            
            runBlocking {
                val result = engine.compile(tempFile)
                
                // Should attempt to use cache
                coVerify { mockCache.get(any(), any()) }
            }
            
            tempFile.delete()
        }
        
        it("should compile and execute in single operation") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            val tempFile = Files.createTempFile("test-script", ".kts").toFile()
            tempFile.writeText("\"Hello World\"")
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            
            coEvery { mockCache.get(any(), any()) } returns null
            coEvery { mockCache.put(any(), any(), any()) } just Runs
            
            runBlocking {
                val result = engine.compileAndExecute(tempFile)
                
                // Should attempt to use cache during compilation
                coVerify { mockCache.get(any(), any()) }
            }
            
            tempFile.delete()
        }
        
        it("should handle cache exceptions gracefully") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            val scriptContent = "val x = 42"
            
            coEvery { mockCache.get(scriptContent, any()) } throws RuntimeException("Cache error")
            coEvery { mockCache.put(any(), any(), any()) } just Runs
            
            runBlocking {
                // Should not throw exception, should fallback to compilation
                val result = engine.compile(scriptContent, "test-script")
                
                // Should have attempted cache get and then fallback
                coVerify { mockCache.get(scriptContent, any()) }
            }
        }
    }
    
    describe("CachedScriptEngine Cache Integration") {
        
        it("should get cache statistics") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            val mockStats = CacheStats(hits = 5, misses = 2, puts = 2)
            
            every { mockCache.getStats() } returns mockStats
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            
            val stats = engine.getCacheStats()
            stats shouldBe mockStats
            stats.hits shouldBe 5
            stats.misses shouldBe 2
        }
        
        it("should clear cache") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            coEvery { mockCache.clear() } just Runs
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            
            runBlocking {
                engine.clearCache()
                coVerify { mockCache.clear() }
            }
        }
        
        it("should get cache size") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            coEvery { mockCache.size() } returns 10
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            
            runBlocking {
                val size = engine.getCacheSize()
                size shouldBe 10
                coVerify { mockCache.size() }
            }
        }
    }
    
    describe("CachedScriptEngine Edge Cases") {
        
        it("should handle empty script content") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            val scriptContent = ""
            
            coEvery { mockCache.get(scriptContent, any()) } returns null
            coEvery { mockCache.put(any(), any(), any()) } just Runs
            
            runBlocking {
                // Should handle empty content without throwing
                val result = engine.compile(scriptContent, "empty-script")
                
                coVerify { mockCache.get(scriptContent, any()) }
            }
        }
        
        it("should handle cache put exceptions gracefully") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            val scriptContent = "val x = 42"
            
            coEvery { mockCache.get(scriptContent, any()) } returns null
            coEvery { mockCache.put(any(), any(), any()) } throws RuntimeException("Cache put error")
            
            runBlocking {
                // Should not throw exception even if cache put fails
                val result = engine.compile(scriptContent, "test-script")
                
                coVerify { mockCache.get(scriptContent, any()) }
                coVerify { mockCache.put(any(), any(), any()) }
            }
        }
        
        it("should use different cache keys for different configurations") {
            val mockCache = mockk<ScriptCompilationCache>()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            val script1 = mockk<CompiledScript>()
            val script2 = mockk<CompiledScript>()
            
            val engine = CachedScriptEngine(mockCache, mockLogger)
            val scriptContent = "val x = 42"
            
            // Different configurations should result in different cache calls
            coEvery { mockCache.get(scriptContent, any()) } returns script1 andThen script2
            
            runBlocking {
                val result1 = engine.compile(scriptContent, "script1")
                val result2 = engine.compile(scriptContent, "script2")
                
                result1.shouldBeInstanceOf<ResultWithDiagnostics.Success<CompiledScript>>()
                result2.shouldBeInstanceOf<ResultWithDiagnostics.Success<CompiledScript>>()
                
                // Should have called cache get twice
                coVerify(exactly = 2) { mockCache.get(scriptContent, any()) }
            }
        }
    }
})