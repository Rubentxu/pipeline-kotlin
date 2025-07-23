package dev.rubentxu.pipeline.integration

import dev.rubentxu.pipeline.context.KoinServiceLocator
import dev.rubentxu.pipeline.context.managers.interfaces.IWorkspaceManager
import dev.rubentxu.pipeline.context.managers.interfaces.IEnvironmentManager
import dev.rubentxu.pipeline.context.managers.interfaces.IParameterManager
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.context.PipelineServiceInitializer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.core.context.stopKoin

/**
 * BDD Specification for Pipeline Service Initialization
 * 
 * Feature: As a pipeline system, I need to initialize all services correctly
 * to ensure proper dependency injection and service location throughout
 * the pipeline execution lifecycle.
 */
class PipelineServiceInitializationSpec : BehaviorSpec({
    
    afterSpec {
        // Clean up Koin context after all tests complete
        try { stopKoin() } catch (_: Exception) { /* already stopped */ }
    }
    
    Given("un sistema pipeline sin inicializar") {
        When("se inicia la aplicación con PipelineServiceInitializer") {
            val initializer = PipelineServiceInitializer()
            val koinServiceLocator = initializer.initialize()
            
            Then("debe crear un KoinServiceLocator funcional") {
                koinServiceLocator shouldNotBe null
                koinServiceLocator.shouldBeInstanceOf<KoinServiceLocator>()
            }
            
            Then("debe registrar IParameterManager correctamente") {
                val parameterManager = koinServiceLocator.get(IParameterManager::class.java)
                parameterManager shouldNotBe null
                
                // Test basic functionality
                parameterManager.set("test_key", "test_value")
                val retrievedValue: String? = parameterManager.get("test_key")
                retrievedValue shouldBe "test_value"
            }
            
            Then("debe registrar IEnvironmentManager correctamente") {
                val environmentManager = koinServiceLocator.get(IEnvironmentManager::class.java)
                environmentManager shouldNotBe null
                
                // Test basic functionality
                kotlinx.coroutines.runBlocking {
                    val systemPath = environmentManager.get("PATH")
                    systemPath shouldNotBe null // PATH should exist on all systems
                }
            }
            
            Then("debe registrar ILoggerManager correctamente") {
                val loggerManager = koinServiceLocator.get(ILoggerManager::class.java)
                loggerManager shouldNotBe null
                
                // Test basic functionality - should not throw
                kotlinx.coroutines.runBlocking {
                    val logger = loggerManager.getLogger("test-logger")
                    logger shouldNotBe null
                    logger.info("Test initialization message")
                }
            }
            
            Then("debe registrar IWorkspaceManager correctamente") {
                val workspaceManager = koinServiceLocator.get(IWorkspaceManager::class.java)
                workspaceManager shouldNotBe null
                
                // Test basic functionality
                val currentWorkspace = workspaceManager.current
                currentWorkspace shouldNotBe null
                currentWorkspace.exists(".") shouldBe true // Current directory should exist
            }
        }
        
        When("se inicializa múltiples veces") {
            val initializer1 = PipelineServiceInitializer()
            val initializer2 = PipelineServiceInitializer()
            
            Then("debe manejar múltiples inicializaciones correctamente") {
                val koinServiceLocator1 = initializer1.initialize()
                val koinServiceLocator2 = initializer2.initialize()
                
                // Both should work correctly since Koin handles re-initialization
                koinServiceLocator1 shouldNotBe null
                koinServiceLocator2 shouldNotBe null
            }
        }
    }
    
    Given("servicios ya inicializados") {
        val initializer = PipelineServiceInitializer()
        val koinServiceLocator = initializer.initialize()
        
        When("se accede a múltiples servicios") {
            val parameterManager = koinServiceLocator.get(IParameterManager::class.java)
            val environmentManager = koinServiceLocator.get(IEnvironmentManager::class.java)
            val loggerManager = koinServiceLocator.get(ILoggerManager::class.java)
            val workspaceManager = koinServiceLocator.get(IWorkspaceManager::class.java)
            
            Then("todos los servicios deben estar disponibles simultáneamente") {
                parameterManager shouldNotBe null
                environmentManager shouldNotBe null  
                loggerManager shouldNotBe null
                workspaceManager shouldNotBe null
            }
            
            Then("los servicios deben ser singleton (misma instancia)") {
                val parameterManager2 = koinServiceLocator.get(IParameterManager::class.java)
                val loggerManager2 = koinServiceLocator.get(ILoggerManager::class.java)
                
                // KoinServiceLocator should return same instances (Koin singleton behavior)
                parameterManager shouldBe parameterManager2
                loggerManager shouldBe loggerManager2
            }
        }
        
        When("se crean loggers múltiples") {
            val loggerManager = koinServiceLocator.get(ILoggerManager::class.java)
            
            Then("debe crear loggers únicos por nombre") {
                kotlinx.coroutines.runBlocking {
                    val logger1 = loggerManager.getLogger("component1")
                    val logger2 = loggerManager.getLogger("component2")
                    val logger1Again = loggerManager.getLogger("component1")
                    
                    logger1 shouldNotBe null
                    logger2 shouldNotBe null
                    logger1 shouldBe logger1Again // Same name = same logger instance
                }
            }
        }
    }
    
    Given("configuración de workspace personalizada") {
        When("se inicializa con directorio específico") {
            val initializer = PipelineServiceInitializer()
            val customWorkingDir = System.getProperty("user.home")
            val koinServiceLocator = initializer.initialize(workingDirectory = customWorkingDir)
            
            Then("el workspace manager debe usar el directorio especificado") {
                val workspaceManager = koinServiceLocator.get(IWorkspaceManager::class.java)
                val currentPath = workspaceManager.current.path.toString()
                currentPath shouldBe customWorkingDir
            }
        }
    }
})