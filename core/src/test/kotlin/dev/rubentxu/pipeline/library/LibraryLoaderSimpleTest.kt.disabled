package dev.rubentxu.pipeline.library

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class LibraryLoaderSimpleTest : DescribeSpec({
    
    describe("LibraryLoader Core Functionality") {
        
        it("should load library successfully when configuration exists") {
            val loader = LibraryLoader()
            val mockRetriever = mockk<SourceRetriever>()
            val testJar = Files.createTempFile("test-library", ".jar").toFile()
            
            every { mockRetriever.retrieve(any()) } returns testJar
            
            val libraryId = LibraryId("test-lib", "1.0.0")
            val config = LibraryConfiguration(
                name = "test-lib",
                sourcePath = "/path/to/source",
                version = "1.0.0",
                retriever = mockRetriever,
                credentialsId = null
            )
            
            loader.libraries[libraryId] = config
            
            val result = loader.loadLibrary(libraryId)
            
            result shouldBe testJar
            verify { mockRetriever.retrieve(config) }
            
            testJar.delete()
        }
        
        it("should throw LibraryNotFoundException when library not registered") {
            val loader = LibraryLoader()
            val libraryId = LibraryId("unknown-lib", "1.0.0")
            
            shouldThrow<LibraryNotFoundException> {
                loader.loadLibrary(libraryId)
            }
        }
        
        it("should propagate retriever exceptions") {
            val loader = LibraryLoader()
            val mockRetriever = mockk<SourceRetriever>()
            val exception = JarFileNotFoundException("Test JAR not found")
            
            every { mockRetriever.retrieve(any()) } throws exception
            
            val libraryId = LibraryId("failing-lib", "1.0.0")
            val config = LibraryConfiguration(
                name = "failing-lib",
                sourcePath = "/nonexistent/path",
                version = "1.0.0",
                retriever = mockRetriever,
                credentialsId = null
            )
            
            loader.libraries[libraryId] = config
            
            shouldThrow<JarFileNotFoundException> {
                loader.loadLibrary(libraryId)
            }
        }
        
        it("should handle multiple library registrations") {
            val loader = LibraryLoader()
            val mockRetriever1 = mockk<SourceRetriever>()
            val mockRetriever2 = mockk<SourceRetriever>()
            
            val testJar1 = Files.createTempFile("test-lib1", ".jar").toFile()
            val testJar2 = Files.createTempFile("test-lib2", ".jar").toFile()
            
            every { mockRetriever1.retrieve(any()) } returns testJar1
            every { mockRetriever2.retrieve(any()) } returns testJar2
            
            val lib1 = LibraryId("lib1", "1.0.0")
            val lib2 = LibraryId("lib2", "2.0.0")
            
            loader.libraries[lib1] = LibraryConfiguration("lib1", "/path1", "1.0.0", mockRetriever1, null)
            loader.libraries[lib2] = LibraryConfiguration("lib2", "/path2", "2.0.0", mockRetriever2, null)
            
            val result1 = loader.loadLibrary(lib1)
            val result2 = loader.loadLibrary(lib2)
            
            result1 shouldBe testJar1
            result2 shouldBe testJar2
            
            testJar1.delete()
            testJar2.delete()
        }
    }
    
    describe("Path Resolution Utilities") {
        
        it("should resolve relative paths to absolute paths") {
            val loader = LibraryLoader()
            val relativePath = "lib/test.jar"
            
            val result = loader.resolveAndNormalizeAbsolutePath(relativePath)
            
            result shouldNotBe relativePath
            result shouldContain "lib/test.jar"
            Path.of(result).isAbsolute shouldBe true
        }
        
        it("should normalize paths with .. and . segments") {
            val loader = LibraryLoader()
            val complexPath = "lib/../lib/./test.jar"
            
            val result = loader.resolveAndNormalizeAbsolutePath(complexPath)
            
            result shouldContain "lib/test.jar"
            result shouldNotBe complexPath
            Path.of(result).isAbsolute shouldBe true
        }
        
        it("should handle absolute paths correctly") {
            val loader = LibraryLoader()
            val absolutePath = "/absolute/path/to/lib.jar"
            
            val result = loader.resolveAndNormalizeAbsolutePath(absolutePath)
            
            result shouldBe Paths.get(absolutePath).toAbsolutePath().normalize().toString()
        }
    }
    
    describe("LibraryLoader Configuration Management") {
        
        it("should handle empty library registry") {
            val loader = LibraryLoader()
            
            loader.libraries.isEmpty() shouldBe true
            
            val libraryId = LibraryId("missing-lib", "1.0.0")
            
            shouldThrow<LibraryNotFoundException> {
                loader.loadLibrary(libraryId)
            }
        }
        
        it("should maintain library registry state") {
            val loader = LibraryLoader()
            val mockRetriever = mockk<SourceRetriever>()
            val testJar = Files.createTempFile("state-test", ".jar").toFile()
            
            every { mockRetriever.retrieve(any()) } returns testJar
            
            val lib1 = LibraryId("lib1", "1.0.0")
            val lib2 = LibraryId("lib2", "1.0.0")
            
            loader.libraries[lib1] = LibraryConfiguration("lib1", "/path1", "1.0.0", mockRetriever, null)
            loader.libraries[lib2] = LibraryConfiguration("lib2", "/path2", "1.0.0", mockRetriever, null)
            
            loader.libraries.size shouldBe 2
            loader.libraries.containsKey(lib1) shouldBe true
            loader.libraries.containsKey(lib2) shouldBe true
            
            testJar.delete()
        }
    }
})