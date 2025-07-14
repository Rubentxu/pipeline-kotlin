package dev.rubentxu.pipeline.steps.builtin

import dev.rubentxu.pipeline.steps.testing.runStepTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Tests for built-in @Step functions using DSL v2 testing framework.
 */
class BuiltInStepsTest : FunSpec({
    
    test("echo step should print message") {
        runStepTest {
            mockEcho()
            
            testSteps {
                echo("Build started")
            }
            
            verifyStepCalled("echo")
            verifyStepCalledWith("echo", mapOf("message" to "Build started"))
        }
    }
    
    test("echo step should reject blank messages") {
        runStepTest {
            testSteps {
                shouldThrow<IllegalArgumentException> {
                    echo("")
                }.message shouldContain "Message cannot be blank"
                
                shouldThrow<IllegalArgumentException> {
                    echo("   ")
                }.message shouldContain "Message cannot be blank"
            }
        }
    }
    
    test("readFile step should read file content") {
        runStepTest {
            createTestFile("test.txt", "Hello from file")
            mockReadFile(mapOf("test.txt" to "Hello from file"))
            
            testSteps {
                val content = readFile("test.txt")
                content shouldBe "Hello from file"
            }
            
            verifyStepCalled("readFile")
        }
    }
    
    test("readFile step should handle encoding") {
        runStepTest {
            val unicodeContent = "Hello 世界 🌍"
            createTestFile("unicode.txt", unicodeContent)
            mockReadFile(mapOf("unicode.txt" to unicodeContent))
            
            testSteps {
                val content = readFile("unicode.txt")
                content shouldContain "世界"
                content shouldContain "🌍"
            }
            
            verifyStepCalled("readFile")
        }
    }
    
    test("writeFile step should create file with content") {
        runStepTest {
            val files = mutableMapOf<String, String>()
            mockReadFile { filePath -> files[filePath] ?: throw RuntimeException("File not found: $filePath") }
            mockWriteFile { filePath, content -> files[filePath] = content }
            
            testSteps {
                writeFile("output.txt", "Pipeline output data")
                
                // Verify file was created
                val content = readFile("output.txt")
                content shouldBe "Pipeline output data"
            }
            
            verifyStepCalled("writeFile")
            verifyStepCalled("readFile")
        }
    }
    
    test("writeFile step should handle encoding") {
        runStepTest {
            val unicodeContent = "Unicode test: 你好 🚀"
            val files = mutableMapOf<String, String>()
            mockReadFile { filePath -> files[filePath] ?: throw RuntimeException("File not found: $filePath") }
            mockWriteFile { filePath, content -> files[filePath] = content }
            
            testSteps {
                writeFile("unicode-out.txt", unicodeContent)
                
                val readContent = readFile("unicode-out.txt")
                readContent shouldBe unicodeContent
            }
            
            verifyStepCalled("writeFile")
            verifyStepCalled("readFile")
        }
    }
    
    test("fileExists step should check file existence") {
        runStepTest {
            createTestFile("existing.txt", "content")
            mockFileExists(setOf("existing.txt"))
            
            testSteps {
                fileExists("existing.txt") shouldBe true
                fileExists("nonexistent.txt") shouldBe false
            }
            
            verifyStepCalled("fileExists")
            verifyStepCalledTimes("fileExists", 2)
        }
    }
    
    test("sleep step should delay execution") {
        runStepTest {
            testSteps {
                val startTime = System.currentTimeMillis()
                sleep(100)
                val elapsed = System.currentTimeMillis() - startTime
                
                // Allow some tolerance for timing
                (elapsed >= 90) shouldBe true
            }
            
            verifyStepCalled("sleep")
        }
    }
    
    test("timestamp step should generate current timestamp") {
        runStepTest {
            testSteps {
                val timestamp1 = timestamp()
                val timestamp2 = timestamp(format = "EPOCH")
                val timestamp3 = timestamp(format = "CUSTOM", pattern = "yyyy-MM-dd")
                
                // Basic validation that timestamps are generated
                timestamp1.length shouldBe 19 // ISO_LOCAL_DATE_TIME length is 19 (e.g., 2024-06-01T12:34:56)
                timestamp2.toLong() > 0L shouldBe true // EPOCH should be positive
                timestamp3.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) shouldBe true // Custom pattern
            }
            
            verifyStepCalledTimes("timestamp", 3)
        }
    }
    
    test("generateUUID step should create unique identifiers") {
        runStepTest {
            testSteps {
                val uuid1 = generateUUID()
                val uuid2 = generateUUID()
                
                // UUIDs should be different
                uuid1 shouldNotBe uuid2
                
                // Should match UUID format
                uuid1.matches(Regex("[0-9a-f\\-]{36}")) shouldBe true
            }
            
            verifyStepCalledTimes("generateUUID", 2)
        }
    }
    
    test("getEnv step should retrieve environment variables") {
        runStepTest(environment = mapOf("TEST_VAR" to "test_value", "BUILD_ID" to "123")) {
            testSteps {
                val ctx = this@runStepTest.testableStepsBlock!!.context
                val testVar = getEnv(ctx, "TEST_VAR")
                val buildId = getEnv(ctx, "BUILD_ID")
                val defaultValue = getEnv(ctx, "NON_EXISTENT", "default")

                testVar shouldBe "test_value"
                buildId shouldBe "123"
                defaultValue shouldBe "default"
            }
            
            verifyStepCalledTimes("getEnv", 3)
        }
    }
    
    test("listFiles step should list directory contents") {
        runStepTest {
            // Create test directory structure
            createTestFile("file1.txt", "content1")
            createTestFile("subdir/file2.txt", "content2")
            createTestFile("subdir/nested/file3.txt", "content3")
            
            // Mock listFiles to return expected structure
            mockStep("listFiles") { params ->
                val path = params["path"] as? String ?: "."
                val recursive = params["recursive"] as? Boolean ?: false
                
                when {
                    path == "." && !recursive -> listOf("file1.txt", "subdir")
                    path == "." && recursive -> listOf("file1.txt", "subdir", "subdir/file2.txt", "subdir/nested", "subdir/nested/file3.txt")
                    path == "subdir" && !recursive -> listOf("file2.txt", "nested")
                    else -> emptyList()
                }
            }
            
            testSteps {
                val files = listFiles()
                val recursiveFiles = listFiles(recursive = true)
                val subdirFiles = listFiles("subdir")
                
                files shouldBe listOf("file1.txt", "subdir")
                recursiveFiles shouldBe listOf("file1.txt", "subdir", "subdir/file2.txt", "subdir/nested", "subdir/nested/file3.txt")
                subdirFiles shouldBe listOf("file2.txt", "nested")
            }
            
            verifyStepCalledTimes("listFiles", 3)
        }
    }
    
    test("mkdir step should create directories") {
        runStepTest {
            testSteps {
                mkdir("test-dir")
                mkdir("nested/deep/directory")
            }
            
            verifyStepCalledTimes("mkdir", 2)
            verifyStepCalledWith("mkdir", mapOf("path" to "test-dir"))
            verifyStepCalledWith("mkdir", mapOf("path" to "nested/deep/directory"))
        }
    }
    
    test("copyFile step should copy files and directories") {
        runStepTest {
            createTestFile("source.txt", "content")
            
            testSteps {
                copyFile("source.txt", "destination.txt")
                copyFile("source-dir", "dest-dir", recursive = true)
            }
            
            verifyStepCalledTimes("copyFile", 2)
            verifyStepCalledWith("copyFile", mapOf(
                "source" to "source.txt",
                "destination" to "destination.txt",
                "recursive" to true
            ))
        }
    }
    
    test("deleteFile step should remove files and directories") {
        runStepTest {
            createTestFile("to-delete.txt", "content")
            
            testSteps {
                deleteFile("to-delete.txt")
                deleteFile("directory-to-delete", recursive = true)
            }
            
            verifyStepCalledTimes("deleteFile", 2)
            verifyStepCalledWith("deleteFile", mapOf(
                "path" to "to-delete.txt",
                "recursive" to true
            ))
        }
    }
})