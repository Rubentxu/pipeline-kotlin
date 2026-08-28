package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the L7 Jenkins top-step DSL builders.
 *
 * Verifies that StageScope has the 5 new DSL extension functions that produce
 * the corresponding StepSpec data classes:
 * - writeFile(file, text) / writeFile(file, text, encoding)
 * - readFile(file) / readFile(file, encoding)
 * - fileExists(file)
 * - withEnv(overrides, block)
 * - archiveArtifacts(artifacts) / archiveArtifacts(artifacts, allowEmptyArchive)
 *
 * RED: UnresolvedReference (methods don't exist yet)
 * GREEN: All 5 builder methods produce correct StepSpec variants
 */
@DisplayName("PipelineDsl top-steps builders")
class PipelineDslTopStepsTest {

    // =============================================================================
    // writeFile
    // =============================================================================

    @Test
    fun `writeFile_builder_produces_WriteFile_step`() {
        val scope = StageScope("test")
        scope.writeFile("output.txt", "hello world")
        val step = scope.steps().last()
        assertTrue(step is StepSpec.WriteFile, "Expected WriteFile, got ${step::class.simpleName}")
        val writeFile = step as StepSpec.WriteFile
        assertEquals("output.txt", writeFile.file)
        assertEquals("hello world", writeFile.text)
        assertEquals("UTF-8", writeFile.encoding)
    }

    @Test
    fun `writeFile_builder_with_encoding_produces_WriteFile_step`() {
        val scope = StageScope("test")
        scope.writeFile("output.bin", "aGVsbG8=", "Base64")
        val step = scope.steps().last()
        assertTrue(step is StepSpec.WriteFile)
        val writeFile = step as StepSpec.WriteFile
        assertEquals("output.bin", writeFile.file)
        assertEquals("aGVsbG8=", writeFile.text)
        assertEquals("Base64", writeFile.encoding)
    }

    // =============================================================================
    // readFile
    // =============================================================================

    @Test
    fun `readFile_builder_produces_ReadFile_step`() {
        val scope = StageScope("test")
        scope.readFile("input.txt")
        val step = scope.steps().last()
        assertTrue(step is StepSpec.ReadFile, "Expected ReadFile, got ${step::class.simpleName}")
        val readFile = step as StepSpec.ReadFile
        assertEquals("input.txt", readFile.file)
        assertEquals("UTF-8", readFile.encoding)
    }

    @Test
    fun `readFile_builder_with_encoding_produces_ReadFile_step`() {
        val scope = StageScope("test")
        scope.readFile("input.bin", "Base64")
        val step = scope.steps().last()
        assertTrue(step is StepSpec.ReadFile)
        val readFile = step as StepSpec.ReadFile
        assertEquals("input.bin", readFile.file)
        assertEquals("Base64", readFile.encoding)
    }

    // =============================================================================
    // fileExists
    // =============================================================================

    @Test
    fun `fileExists_builder_produces_FileExists_step`() {
        val scope = StageScope("test")
        scope.fileExists("config.xml")
        val step = scope.steps().last()
        assertTrue(step is StepSpec.FileExists, "Expected FileExists, got ${step::class.simpleName}")
        val fileExists = step as StepSpec.FileExists
        assertEquals("config.xml", fileExists.file)
    }

    // =============================================================================
    // withEnv
    // =============================================================================

    @Test
    fun `withEnv_captures_inner_steps`() {
        val scope = StageScope("test")
        scope.withEnv(mapOf("FOO" to "bar")) {
            sh("echo \$FOO")
            echo("done")
        }
        val step = scope.steps().last()
        assertTrue(step is StepSpec.WithEnv, "Expected WithEnv, got ${step::class.simpleName}")
        val withEnv = step as StepSpec.WithEnv
        assertEquals(listOf("FOO=bar"), withEnv.overrides)
        assertEquals(2, withEnv.steps.size)
        assertTrue(withEnv.steps[0] is StepSpec.Shell)
        assertTrue(withEnv.steps[1] is StepSpec.Echo)
    }

    @Test
    fun `withEnv_with_list_overrides_captures_inner_steps`() {
        val scope = StageScope("test")
        scope.withEnv(listOf("JAVA_HOME=/opt/jdk21", "PATH+EXTRA=/usr/local/bin")) {
            sh("echo \$JAVA_HOME")
        }
        val step = scope.steps().last()
        assertTrue(step is StepSpec.WithEnv)
        val withEnv = step as StepSpec.WithEnv
        assertEquals(2, withEnv.overrides.size)
        assertTrue(withEnv.overrides.contains("JAVA_HOME=/opt/jdk21"))
        assertTrue(withEnv.steps.size == 1)
    }

    @Test
    fun `withEnv_nested_blocks_capture_correctly`() {
        val scope = StageScope("test")
        scope.withEnv(mapOf("OUTER" to "1")) {
            sh("echo \$OUTER")
            withEnv(mapOf("INNER" to "2")) {
                sh("echo \$INNER")
            }
        }
        val outer = scope.steps().last() as StepSpec.WithEnv
        assertEquals(2, outer.steps.size)
        val inner = outer.steps[1] as StepSpec.WithEnv
        assertEquals(listOf("INNER=2"), inner.overrides)
    }

    // =============================================================================
    // archiveArtifacts
    // =============================================================================

    @Test
    fun `archiveArtifacts_builder_produces_ArchiveArtifacts_step`() {
        val scope = StageScope("test")
        scope.archiveArtifacts("build/**/*.jar")
        val step = scope.steps().last()
        assertTrue(step is StepSpec.ArchiveArtifacts, "Expected ArchiveArtifacts, got ${step::class.simpleName}")
        val archive = step as StepSpec.ArchiveArtifacts
        assertEquals("build/**/*.jar", archive.artifacts)
        assertEquals(false, archive.allowEmptyArchive)
        assertEquals(false, archive.fingerprint)
        assertEquals(false, archive.onlyIfSuccessful)
    }

    @Test
    fun `archiveArtifacts_builder_with_allowEmptyArchive_produces_ArchiveArtifacts_step`() {
        val scope = StageScope("test")
        scope.archiveArtifacts("target/*.war", allowEmptyArchive = true)
        val step = scope.steps().last()
        assertTrue(step is StepSpec.ArchiveArtifacts)
        val archive = step as StepSpec.ArchiveArtifacts
        assertEquals("target/*.war", archive.artifacts)
        assertEquals(true, archive.allowEmptyArchive)
    }

    // =============================================================================
    // Combined pipeline
    // =============================================================================

    @Test
    fun `pipeline_with_all_top_steps_compiles`() {
        // Smoke test: full pipeline with all L7 steps
        val spec = pipeline {
            stages {
                stage("Build") {
                    writeFile("output.txt", "hello")
                    fileExists("output.txt")
                    readFile("output.txt")
                    withEnv(mapOf("FOO" to "bar")) {
                        sh("echo \$FOO")
                    }
                    archiveArtifacts("build/**/*.jar")
                }
            }
        }
        val stage = spec.stages.single()
        assertEquals(5, stage.steps.size)
        assertTrue(stage.steps[0] is StepSpec.WriteFile)
        assertTrue(stage.steps[1] is StepSpec.FileExists)
        assertTrue(stage.steps[2] is StepSpec.ReadFile)
        assertTrue(stage.steps[3] is StepSpec.WithEnv)
        assertTrue(stage.steps[4] is StepSpec.ArchiveArtifacts)
    }
}
