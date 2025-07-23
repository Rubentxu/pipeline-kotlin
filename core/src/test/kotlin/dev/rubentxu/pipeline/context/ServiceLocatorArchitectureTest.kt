package dev.rubentxu.pipeline.context

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test for the Service Locator architecture in the core module.
 * This validates only the basic Service Locator functionality without
 * depending on excluded managers.
 */
class ServiceLocatorArchitectureTest {
    
    // Simple test interface and implementation
    interface ITestService {
        fun getName(): String
    }
    
    class TestService : ITestService {
        override fun getName(): String = "test-service"
    }
    
    private val testModule = module {
        single<ITestService> { TestService() }
    }
    
    @BeforeEach
    fun setup() {
        startKoin {
            modules(testModule)
        }
    }
    
    @AfterEach 
    fun cleanup() {
        stopKoin()
    }
    
    @Test
    fun `Service Locator can be created with Koin`() {
        // Given: Koin is initialized
        val koin = GlobalContext.get()
        
        // When: We create a service locator
        val serviceLocator = KoinServiceLocator(koin)
        
        // Then: Service locator should be created successfully
        assertNotNull(serviceLocator)
    }
    
    @Test
    fun `Service Locator can resolve services`() {
        // Given: A service locator
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        
        // When: We request a service
        val testService = serviceLocator.get(ITestService::class.java)
        
        // Then: The service should be resolved correctly
        assertNotNull(testService)
        assertEquals("test-service", testService.getName())
        assertTrue(testService is TestService)
    }
    
    @Test
    fun `Service Locator extension functions work`() {
        // Given: A service locator
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        
        // When: We use extension functions
        val testService1 = serviceLocator.get<ITestService>()
        val testService2 = serviceLocator.getOrNull<ITestService>()
        val lazyService = serviceLocator.lazy<ITestService>()
        
        // Then: All methods should work correctly
        assertNotNull(testService1)
        assertNotNull(testService2) 
        assertNotNull(lazyService)
        
        assertEquals("test-service", testService1.getName())
        assertEquals("test-service", testService2!!.getName())
        assertEquals("test-service", lazyService.value.getName())
    }
    
    @Test
    fun `Service Locator returns singletons correctly`() {
        // Given: A service locator
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        
        // When: We request the same service multiple times
        val service1 = serviceLocator.get<ITestService>()
        val service2 = serviceLocator.get<ITestService>()
        
        // Then: Should be the same instance (singleton)
        assertTrue(service1 === service2, "Services should be singleton")
    }
    
    @Test
    fun `Service Locator handles non-existent services gracefully`() {
        // Given: A service locator
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        
        // When: We request a non-existent service with getOrNull
        val result = serviceLocator.getOrNull<String>()
        
        // Then: Should return null without throwing
        assertEquals(null, result)
    }
}