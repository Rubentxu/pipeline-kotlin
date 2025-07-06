package dev.rubentxu.pipeline.library

import dev.rubentxu.pipeline.compiler.GradleCompiler
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.*
import java.io.File
import java.nio.file.Files

class SourceRetrieverSimpleTest : DescribeSpec({
    
    describe("LocalJar SourceRetriever") {
        
        it("should retrieve existing JAR file successfully") {
            val tempJar = Files.createTempFile("test", ".jar").toFile()
            val localJar = LocalJar()
            
            val config = LibraryConfiguration(
                name = "test-lib",
                sourcePath = tempJar.absolutePath,
                version = "1.0.0",
                retriever = localJar,
                credentialsId = null
            )
            
            val result = localJar.retrieve(config)
            
            result shouldBe tempJar
            result.exists() shouldBe true
            result.canRead() shouldBe true
            
            tempJar.delete()
        }
        
        it("should throw JarFileNotFoundException for non-existent JAR") {
            val localJar = LocalJar()
            val nonExistentPath = "/nonexistent/path/to/lib.jar"
            
            val config = LibraryConfiguration(
                name = "missing-lib",
                sourcePath = nonExistentPath,
                version = "1.0.0",
                retriever = localJar,
                credentialsId = null
            )
            
            shouldThrow<JarFileNotFoundException> {
                localJar.retrieve(config)
            }
        }
        
        it("should handle JAR files with different extensions") {
            val tempJar = Files.createTempFile("test", ".jar").toFile()
            val localJar = LocalJar()
            
            val config = LibraryConfiguration(
                name = "jar-test-lib",
                sourcePath = tempJar.absolutePath,
                version = "1.0.0",
                retriever = localJar,
                credentialsId = null
            )
            
            val result = localJar.retrieve(config)
            
            result shouldBe tempJar
            result.name shouldContain ".jar"
            
            tempJar.delete()
        }
    }
    
    describe("LocalSource SourceRetriever") {
        
        it("should compile local source to JAR successfully") {
            val tempSourceDir = Files.createTempDirectory("test-source").toFile()
            val srcDir = File(tempSourceDir, "src/main/kotlin")
            srcDir.mkdirs()
            
            val mockCompiler = mockk<GradleCompiler>()
            val expectedJar = File(tempSourceDir, "build/libs/compiled-1.0.0.jar")
            expectedJar.parentFile.mkdirs()
            expectedJar.createNewFile()
            
            every { mockCompiler.compileAndJar(any(), any()) } returns expectedJar
            
            val localSource = LocalSource(mockCompiler)
            val config = LibraryConfiguration(
                name = "test-source-lib",
                sourcePath = tempSourceDir.absolutePath,
                version = "1.0.0",
                retriever = localSource,
                credentialsId = null
            )
            
            val result = localSource.retrieve(config)
            
            result shouldBe expectedJar
            verify { mockCompiler.compileAndJar(tempSourceDir.absolutePath, config) }
            
            tempSourceDir.deleteRecursively()
        }
        
        it("should propagate compilation errors") {
            val tempSourceDir = Files.createTempDirectory("failing-source").toFile()
            
            val mockCompiler = mockk<GradleCompiler>()
            val compilationError = RuntimeException("Compilation failed")
            
            every { mockCompiler.compileAndJar(any(), any()) } throws compilationError
            
            val localSource = LocalSource(mockCompiler)
            val config = LibraryConfiguration(
                name = "failing-source-lib",
                sourcePath = tempSourceDir.absolutePath,
                version = "1.0.0",
                retriever = localSource,
                credentialsId = null
            )
            
            shouldThrow<RuntimeException> {
                localSource.retrieve(config)
            }
            
            tempSourceDir.deleteRecursively()
        }
    }
    
    describe("GitSource SourceRetriever") {
        
        it("should clone and compile Git repository successfully") {
            val mockCompiler = mockk<GradleCompiler>()
            val expectedJar = Files.createTempFile("git-compiled", ".jar").toFile()
            
            every { mockCompiler.compileAndJar(any(), any()) } returns expectedJar
            
            val gitSource = GitSource(mockCompiler)
            val config = LibraryConfiguration(
                name = "git-lib",
                sourcePath = "https://github.com/example/test-lib.git",
                version = "1.0.0",
                retriever = gitSource,
                credentialsId = null
            )
            
            val result = gitSource.retrieve(config)
            
            result shouldBe expectedJar
            verify { mockCompiler.compileAndJar(any(), config) }
            
            expectedJar.delete()
        }
        
        it("should handle Git compilation errors") {
            val mockCompiler = mockk<GradleCompiler>()
            val gitError = RuntimeException("Git compilation failed")
            
            every { mockCompiler.compileAndJar(any(), any()) } throws gitError
            
            val gitSource = GitSource(mockCompiler)
            val config = LibraryConfiguration(
                name = "invalid-git-lib",
                sourcePath = "https://invalid-git-url.com/repo.git",
                version = "1.0.0",
                retriever = gitSource,
                credentialsId = null
            )
            
            shouldThrow<RuntimeException> {
                gitSource.retrieve(config)
            }
        }
        
        it("should handle Git repositories with credentials") {
            val mockCompiler = mockk<GradleCompiler>()
            val expectedJar = Files.createTempFile("git-private", ".jar").toFile()
            
            every { mockCompiler.compileAndJar(any(), any()) } returns expectedJar
            
            val gitSource = GitSource(mockCompiler)
            val config = LibraryConfiguration(
                name = "git-private-lib",
                sourcePath = "https://github.com/private/test-lib.git",
                version = "1.0.0",
                retriever = gitSource,
                credentialsId = "test-credentials"
            )
            
            val result = gitSource.retrieve(config)
            
            result shouldBe expectedJar
            verify { mockCompiler.compileAndJar(any(), config) }
            
            expectedJar.delete()
        }
    }
})