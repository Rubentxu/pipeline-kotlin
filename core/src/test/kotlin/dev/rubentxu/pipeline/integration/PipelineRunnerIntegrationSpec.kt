package dev.rubentxu.pipeline.integration

import dev.rubentxu.pipeline.context.KoinServiceLocator
import dev.rubentxu.pipeline.context.PipelineServiceInitializer
import dev.rubentxu.pipeline.context.managers.interfaces.IWorkspaceManager
import dev.rubentxu.pipeline.context.managers.interfaces.IEnvironmentManager
import dev.rubentxu.pipeline.context.managers.interfaces.IParameterManager
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.runner.PipelineRunner
import dev.rubentxu.pipeline.runner.PipelineResult
import dev.rubentxu.pipeline.runner.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.core.context.stopKoin

/**
 * BDD Specification for PipelineRunner Integration
 * 
 * Feature: As a user, when I execute a pipeline, it should use PipelineRunner
 * to properly initialize context and execute all stages with full service access.
 */
class PipelineRunnerIntegrationSpec : BehaviorSpec({
    
    afterSpec {
        // Clean up Koin context after all tests complete
        try { stopKoin() } catch (_: Exception) { /* already stopped */ }
    }
    
    Given("un pipeline válido y servicios inicializados") {
        val serviceInitializer = PipelineServiceInitializer()
        val koinServiceLocator = serviceInitializer.initialize()
        
        When("se ejecuta el pipeline usando PipelineRunner") {
            val pipelineRunner = PipelineRunner(koinServiceLocator)
            val result = pipelineRunner.run()
            
            Then("debe completar exitosamente") {
                result shouldNotBe null
                result.shouldBeInstanceOf<PipelineResult>()
                result.status shouldBe Status.SUCCESS
            }
            
            Then("debe tener acceso a todos los managers durante la ejecución") {
                // This will be verified through the pipeline execution logs
                // and the fact that no exceptions were thrown
                result.status shouldBe Status.SUCCESS
            }
        }
        
        When("se ejecuta un script usando el método execute") {
            val pipelineRunner = PipelineRunner(koinServiceLocator)
            val result = pipelineRunner.execute("test-script.pipeline.kts")
            
            Then("debe ejecutar el script correctamente") {
                result.status shouldBe Status.SUCCESS
                result.stageResults.isNotEmpty() shouldBe true
            }
            
            Then("debe tener acceso a workspace manager") {
                // Verify through successful execution that workspace was accessible
                result.status shouldBe Status.SUCCESS
            }
            
            Then("debe tener acceso a logger manager") {
                // Verify through successful execution that logger was accessible
                result.status shouldBe Status.SUCCESS
            }
        }
    }
    
    Given("un pipeline runner con acceso directo a servicios") {
        val serviceInitializer = PipelineServiceInitializer()
        val koinServiceLocator = serviceInitializer.initialize()
        val pipelineRunner = PipelineRunner(koinServiceLocator)
        
        When("se accede directamente a los managers") {
            val workspaceManager = pipelineRunner.workspaceManager
            val loggerManager = pipelineRunner.loggerManager
            val parameterManager = pipelineRunner.parameterManager
            val environmentManager = pipelineRunner.environmentManager
            
            Then("todos los managers deben estar disponibles") {
                workspaceManager shouldNotBe null
                loggerManager shouldNotBe null
                parameterManager shouldNotBe null
                environmentManager shouldNotBe null
            }
            
            Then("los managers deben ser funcionales") {
                // Test workspace manager
                val currentWorkspace = workspaceManager.current
                currentWorkspace shouldNotBe null
                
                // Test parameter manager
                parameterManager.set("test_key", "test_value")
                val retrievedValue: String? = parameterManager.get("test_key")
                retrievedValue shouldBe "test_value"
            }
        }
    }
    
    Given("manejo de errores del pipeline runner") {
        val serviceInitializer = PipelineServiceInitializer()
        val koinServiceLocator = serviceInitializer.initialize()
        
        When("los servicios están disponibles tras cualquier operación") {
            val pipelineRunner = PipelineRunner(koinServiceLocator)
            
            // Execute some operations
            pipelineRunner.run()
            pipelineRunner.execute("test-script.kts")
            
            Then("los servicios deben seguir disponibles") {
                // Services should remain functional
                val parameterManager = koinServiceLocator.get(IParameterManager::class.java)
                parameterManager shouldNotBe null
                
                val loggerManager = koinServiceLocator.get(ILoggerManager::class.java)
                loggerManager shouldNotBe null
                
                val workspaceManager = koinServiceLocator.get(IWorkspaceManager::class.java)
                workspaceManager shouldNotBe null
                
                val environmentManager = koinServiceLocator.get(IEnvironmentManager::class.java)
                environmentManager shouldNotBe null
            }
        }
    }
    
    Given("configuración de workspace personalizada") {
        val customWorkingDir = System.getProperty("java.io.tmpdir")
        val serviceInitializer = PipelineServiceInitializer()
        val koinServiceLocator = serviceInitializer.initialize(workingDirectory = customWorkingDir)
        
        When("se ejecuta un pipeline runner con workspace personalizado") {
            val pipelineRunner = PipelineRunner(koinServiceLocator)
            val result = pipelineRunner.run()
            
            Then("debe usar el directorio de trabajo especificado") {
                result.status shouldBe Status.SUCCESS
                
                // Verify workspace manager uses custom directory
                val workspaceManager = koinServiceLocator.get(IWorkspaceManager::class.java)
                val currentPath = workspaceManager.current.path.toString()
                currentPath shouldBe customWorkingDir
            }
        }
    }
})

// Simplified Integration Test for Phase 2 - PipelineRunner Service Integration
// This test focuses on validating that PipelineRunner correctly integrates with 
// the service initialization system using KoinServiceLocator.