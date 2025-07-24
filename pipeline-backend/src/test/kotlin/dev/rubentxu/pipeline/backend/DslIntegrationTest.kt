package dev.rubentxu.pipeline.backend

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.*
import java.io.File

class DslIntegrationTest {
    
    @Test
    fun `DslManager debe compilar y estar disponible en pipeline-backend`() {
        // Test que PipelineScriptRunner puede instanciar DslManager
        assertDoesNotThrow {
            val runner = PipelineScriptRunner()
            assertNotNull(runner)
        }
    }
    
    @Test
    fun `executeScriptContent debe funcionar con DslManager`() {
        val runner = PipelineScriptRunner()
        
        val result = runner.executeScriptContent("pipeline test")
        
        assertTrue(result.contains("✅ Script ejecutado exitosamente"))
        assertTrue(result.contains("Pipeline built from script: inline"))
    }
    
    @Test
    fun `evalWithDslManager método estático debe funcionar`() {
        val result = PipelineScriptRunner.evalWithDslManager("/tmp/test.groovy", "/tmp/config.yaml")
        
        assertTrue(result.contains("❌ Error DSL: Script file not found"))
    }
    
    @Test
    fun `executeScript con archivo real debe funcionar`() {
        val tempScript = File.createTempFile("test_pipeline", ".groovy")
        tempScript.writeText("pipeline { stage('test') { echo 'Testing' } }")
        
        try {
            val runner = PipelineScriptRunner()
            val result = runner.executeScript(tempScript.absolutePath)
            
            assertTrue(result.contains("✅ Script ejecutado exitosamente"))
            assertTrue(result.contains("Pipeline built from script: ${tempScript.name}"))
        } finally {
            tempScript.delete()
        }
    }
}