package dev.rubentxu.pipeline.context.modules

import dev.rubentxu.pipeline.context.IServiceLocator
import dev.rubentxu.pipeline.context.KoinServiceLocator
import org.koin.dsl.module

/**
 * Koin module for Service Locator
 * 
 * Provides the Service Locator abstraction that allows components to access
 * dependency injection without being tied to Koin specifically.
 */
val serviceLocatorModule = module {
    
    // Service Locator - provides abstraction over DI framework
    single<IServiceLocator> { KoinServiceLocator(getKoin()) }
}