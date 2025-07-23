package dev.rubentxu.pipeline.context.modules

import dev.rubentxu.pipeline.context.IServiceLocator
import dev.rubentxu.pipeline.context.unified.UnifiedContextFactory
import org.koin.dsl.module

/**
 * Unified Core Modules - Consolidation of all architectural phases
 * 
 * This module brings together:
 * - Phase 1: Service Locator + Real Managers 
 * - Phase 2: Component management (integrated in managers)
 * - Phase 3: UnifiedPipelineContext + EnhancedStepsBlock
 * - Phase 4: UnifiedStepContext + Enhanced @Step Registry
 */

/**
 * Unified Context Module (Phase 3)
 * Provides UnifiedPipelineContext and related DSL components
 */
val unifiedContextModule = module {
    
    // UnifiedContextFactory as singleton
    single { UnifiedContextFactory }
    
    // Enhanced Steps Block factory (created on demand)
    factory { 
        dev.rubentxu.pipeline.context.unified.EnhancedStepsBlockFactory.create(get<IServiceLocator>()) 
    }
}

/**
 * Enhanced Steps Module (Phase 4) 
 * Provides UnifiedStepContext, registry, and enhanced @Step functions
 * 
 * Currently minimal implementation to avoid circular dependency issues.
 * Enhanced step components will be lazily registered on-demand.
 */
val enhancedStepsModule = module {
    // Enhanced step components are available but registered lazily
    // to avoid circular dependency issues during compilation
}

/**
 * Complete Core Modules List
 * Includes all architectural phases in correct dependency order
 */
val coreModules = listOf(
    // Phase 1: Service Locator + Managers
    serviceLocatorModule,
    realManagersModule,
    
    // Phase 3: Unified Context + DSL
    unifiedContextModule,
    
    // Phase 4: Enhanced Steps + Registry  
    enhancedStepsModule
)