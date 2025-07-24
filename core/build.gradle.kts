plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotest)
    alias(libs.plugins.ksp)
}

group = "dev.rubentxu.pipeline.core"
version = "1.0-SNAPSHOT"

dependencies {
    // Plugin annotations for @Step and related annotations
    implementation(project(":pipeline-steps-system:plugin-annotations"))
    
    // Compiler plugin for @Step transformation (temporarily disabled due to IR errors)
    // kotlinCompilerPluginClasspath(project(":pipeline-steps-system:compiler-plugin"))

    implementation(libs.snakeyaml)
    implementation(libs.kotlin.serialization)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gradle.tooling.api)
    implementation(libs.jgit)
    implementation(libs.logback.classic)
    implementation(libs.bundles.kotlin.scripting)
    implementation(libs.bundles.docker)
//    compileOnly(libs.bundles.graalvm) // Only needed for compilation, not runtime
    implementation(libs.bundles.maven.resolver)
    
    // Kotlin reflection for step registry
    implementation(kotlin("reflect"))
    
    // Explicit dependency on Kotlin standard library
    implementation(libs.kotlin.stdlib)
    
    // Koin DI
    implementation(libs.bundles.koin)
    
    // JCTools for high-performance concurrent collections
    implementation(libs.jctools.core)

    // Kotest required for generated test frameworks
    implementation(libs.bundles.kotest)

    testImplementation(kotlin("test"))
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.bundles.koin.test)
    // GraalVM dependencies for tests that use sandbox functionality
//    testImplementation(libs.bundles.graalvm)
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
        exclude("**/disabled/**")
        
        // === TDD Phase 1: Exclude components not yet ready ===
        
        // DSL System - Enable only DslManager.kt (minimal implementation)
        exclude("**/dsl/AgentBlock.kt")
        exclude("**/dsl/Dsl.kt")
        exclude("**/dsl/DslEngine.kt") 
        exclude("**/dsl/DefaultDslEngineRegistry.kt")
        exclude("**/dsl/DslContextExtensions.kt")
        exclude("**/dsl/EnviromentBlock.kt")
        exclude("**/dsl/NodeDsl.kt")
        exclude("**/dsl/PipelineBlock.kt")
        exclude("**/dsl/PostExecutionBlock.kt")
        exclude("**/dsl/StageBlock.kt")
        exclude("**/dsl/StagesCollectionBlock.kt")
        exclude("**/dsl/Step.kt")
        exclude("**/dsl/StepsBlock.kt")
        exclude("**/dsl/WorkspaceDslExtensions.kt")
        exclude("**/dsl/engines/**")
        exclude("**/dsl/examples/**")
        exclude("**/dsl/interfaces/**")
        exclude("**/dsl/validation/**")
        exclude("**/dsl/MinimalDslManager.kt")  // Remove this since it's copied to DslManager.kt
        
        // Execution & Compilation - Depends on DSL and pipeline models
        exclude("**/execution/**")
        exclude("**/compilation/**")
        exclude("**/compiler/**")
        
        // Security System - Depends on DSL context and execution models  
        exclude("**/security/**")
        exclude("**/steps/security/StepSecurityManager.kt")
        
        // Plugin System - Complex loader with various dependencies
        exclude("**/plugins/**")
        
        // Library System - Git, local, and remote library loading
        exclude("**/library/**")
        
        // Step System - Depends on DSL and execution context
        exclude("**/steps/**")
        exclude("**/steps/builtin/BuiltInSteps.kt")
        
        // Pipeline Models - Core domain models not ready yet
        exclude("**/model/pipeline/**")
        exclude("**/model/job/**") 
        exclude("**/model/scm/**")
        exclude("**/model/GitTool.kt")
        exclude("**/model/Workspace.kt") // Conflicts with new workspace manager
        
        // Context System - Old/complex context implementations
        exclude("**/DefaultPipelineContext.kt")
        exclude("**/IPipelineContext.kt") 
        exclude("**/MinimalPipelineContext.kt")
        exclude("**/PipelineContextFactory.kt")
        exclude("**/context/PipelineContext.kt")
        exclude("**/context/StepExecutionContext.kt")
        exclude("**/context/unified/**") // Unified context system
        
        // Event System - Not ready yet
        exclude("**/events/**")
        
        // Legacy/Jenkins - Specific implementations not needed
        exclude("**/jenkins/**")
        
        // === Old/Conflicting Implementations ===
        exclude("**/context/managers/WorkingDirectoryManager.kt") // Replaced by DefaultWorkspaceManager
        exclude("**/context/modules/ManagersModule.kt") // Replaced by RealManagersModule  
        exclude("**/context/modules/PipelineCoreModules.kt") // Has dependencies on excluded managers
        exclude("**/context/modules/UnifiedCoreModules.kt") // Unified system not ready
    }
    compileTestKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
        exclude("**/disabled/**")
        
        // === Tests for excluded components ===
        // Note: These correspond to main code exclusions above
        // Tests will be enabled as components are reactivated in future phases
        
        // Context system tests - exclude problematic ones, include working ones
        exclude("**/context/**") 
        include("**/context/managers/RealManagersModuleTest.kt") // ✅ Working DI test
        
        // Integration tests - include current phase, exclude future phases  
        include("**/integration/PipelineServiceInitializationSpec.kt") // ✅ Phase 1 complete
        include("**/integration/PipelineRunnerIntegrationSpec.kt") // ✅ Phase 2 - in development
        exclude("**/integration/UnifiedContextIntegrationTest.kt") // Unified context not ready
        
        // Old/deleted implementations
        exclude("**/RealManagersTest.kt") // References deleted KoinParameterManager
    }

    test {
        useJUnitPlatform()
//        jvmArgs()
    }
}

tasks.withType<Test> {
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}

tasks
    .withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>()
    .configureEach {
        compilerOptions
            .languageVersion
            .set(
                org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
            )
    }