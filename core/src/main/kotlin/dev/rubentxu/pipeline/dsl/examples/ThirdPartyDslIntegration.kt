package dev.rubentxu.pipeline.dsl.examples

import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.dsl.engines.genericKotlinDslEngine
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import kotlin.script.experimental.api.EvaluationResult

/**
 * Examples demonstrating how to integrate third-party DSL libraries
 * with the pipeline DSL system.
 */
object ThirdPartyDslIntegration {
    
    /**
     * Example: Integrating Gradle Kotlin DSL
     * This shows how you could integrate Gradle's Kotlin DSL as a build engine.
     */
    fun createGradleDslEngine(): DslEngine<Any> {
        return genericKotlinDslEngine {
            engineId("gradle-kotlin-dsl")
            engineName("Gradle Kotlin DSL Engine")
            engineVersion("8.5.0")
            supportedExtensions(".gradle.kts")
            scriptDefinitionClass(GradleScript::class)
            description("Gradle Kotlin DSL for build configurations")
            author("Gradle Team")
            
            defaultImports(
                "org.gradle.api.*",
                "org.gradle.kotlin.dsl.*"
            )
            
            capabilities(
                DslCapability.COMPILATION_CACHING,
                DslCapability.SYNTAX_VALIDATION,
                DslCapability.TYPE_CHECKING,
                DslCapability.INCREMENTAL_COMPILATION
            )
            
            resultExtractor { evaluationResult ->
                // Extract build result from Gradle execution
                when (val returnValue = evaluationResult.returnValue) {
                    is kotlin.script.experimental.api.ResultValue.Value -> returnValue.value ?: mapOf("status" to "success")
                    else -> mapOf("status" to "success")
                }
            }
        }
    }
    
    /**
     * Example: Integrating Docker Compose DSL
     * This shows how you could create a DSL for Docker Compose configurations.
     */
    fun createDockerComposeDslEngine(): DslEngine<Any> {
        return genericKotlinDslEngine {
            engineId("docker-compose-dsl")
            engineName("Docker Compose DSL Engine")
            supportedExtensions(".compose.kts")
            scriptDefinitionClass(DockerComposeScript::class)
            description("Kotlin DSL for Docker Compose configurations")
            author("DevOps Team")
            
            defaultImports(
                "com.example.docker.*",
                "com.example.compose.*"
            )
            
            capabilities(
                DslCapability.COMPILATION_CACHING,
                DslCapability.SYNTAX_VALIDATION
            )
            
            resultExtractor { evaluationResult ->
                // Extract Docker Compose configuration
                mapOf(
                    "services" to emptyList<String>(),
                    "networks" to emptyList<String>(),
                    "volumes" to emptyList<String>()
                )
            }
        }
    }
    
    /**
     * Example: Integrating Spring Boot Configuration DSL
     * This shows how you could create a DSL for Spring Boot application configurations.
     */
    fun createSpringBootDslEngine(): DslEngine<Any> {
        return genericKotlinDslEngine {
            engineId("spring-boot-dsl")
            engineName("Spring Boot Configuration DSL")
            supportedExtensions(".spring.kts")
            scriptDefinitionClass(SpringBootScript::class)
            description("Kotlin DSL for Spring Boot configurations")
            author("Spring Team")
            
            defaultImports(
                "org.springframework.boot.*",
                "org.springframework.context.*",
                "com.example.spring.*"
            )
            
            capabilities(
                DslCapability.COMPILATION_CACHING,
                DslCapability.SYNTAX_VALIDATION,
                DslCapability.TYPE_CHECKING,
                DslCapability.HOT_RELOAD
            )
            
            resultExtractor { evaluationResult ->
                // Extract Spring Boot configuration
                mapOf(
                    "profiles" to emptyList<String>(),
                    "properties" to emptyMap<String, Any>(),
                    "beans" to emptyList<String>()
                )
            }
        }
    }
    
    /**
     * Example: Integrating Terraform HCL-like DSL
     * This shows how you could create a Kotlin DSL that mimics Terraform's HCL.
     */
    fun createTerraformDslEngine(): DslEngine<Any> {
        return genericKotlinDslEngine {
            engineId("terraform-kotlin-dsl")
            engineName("Terraform Kotlin DSL Engine")
            supportedExtensions(".tf.kts")
            scriptDefinitionClass(TerraformScript::class)
            description("Kotlin DSL for Terraform-like infrastructure as code")
            author("Infrastructure Team")
            
            defaultImports(
                "com.example.terraform.*",
                "com.example.aws.*",
                "com.example.gcp.*"
            )
            
            capabilities(
                DslCapability.COMPILATION_CACHING,
                DslCapability.SYNTAX_VALIDATION,
                DslCapability.TYPE_CHECKING
            )
            
            resultExtractor { evaluationResult ->
                // Extract infrastructure configuration
                mapOf(
                    "resources" to emptyList<String>(),
                    "providers" to emptyList<String>(),
                    "outputs" to emptyMap<String, Any>()
                )
            }
        }
    }
    
    /**
     * Example: Complete integration workflow
     * This demonstrates how to set up a DSL manager with multiple third-party engines.
     */
    fun setupMultiEngineDslManager(): DslManager {
        val config = object : IPipelineConfig {
            // Simple implementation for demo purposes
        }
        val dslManager = DslManager(config)
        
        // Register all third-party engines
        dslManager.registerEngine(createGradleDslEngine())
        dslManager.registerEngine(createDockerComposeDslEngine())
        dslManager.registerEngine(createSpringBootDslEngine())
        dslManager.registerEngine(createTerraformDslEngine())
        
        return dslManager
    }
    
    /**
     * Example usage scenarios
     */
    suspend fun demonstrateUsageScenarios() {
        val dslManager = setupMultiEngineDslManager()
        
        try {
            // Example 1: Execute a Gradle build script
            val gradleResult = dslManager.executeContent<Any>(
                scriptContent = """
                    plugins {
                        kotlin("jvm") version "1.9.21"
                    }
                    
                    dependencies {
                        implementation("org.jetbrains.kotlin:kotlin-stdlib")
                    }
                """.trimIndent(),
                engineId = "gradle-kotlin-dsl",
                scriptName = "build.gradle.kts"
            )
            
            // Example 2: Execute a Docker Compose configuration
            val composeResult = dslManager.executeContent<Any>(
                scriptContent = """
                    services {
                        service("web") {
                            image = "nginx:latest"
                            ports = listOf("80:80")
                        }
                        
                        service("db") {
                            image = "postgres:13"
                            environment = mapOf(
                                "POSTGRES_PASSWORD" to "secret"
                            )
                        }
                    }
                """.trimIndent(),
                engineId = "docker-compose-dsl",
                scriptName = "docker-compose.kts"
            )
            
            // Example 3: Execute a Spring Boot configuration
            val springResult = dslManager.executeContent<Any>(
                scriptContent = """
                    application {
                        name = "my-spring-app"
                        profiles = listOf("dev", "prod")
                        
                        properties {
                            "server.port" to 8080
                            "spring.datasource.url" to "jdbc:h2:mem:testdb"
                        }
                    }
                """.trimIndent(),
                engineId = "spring-boot-dsl",
                scriptName = "application.spring.kts"
            )
            
            // Example 4: Execute a Terraform-like infrastructure script
            val terraformResult = dslManager.executeContent<Any>(
                scriptContent = """
                    provider("aws") {
                        region = "us-west-2"
                    }
                    
                    resource("aws_instance", "web") {
                        ami = "ami-0c55b159cbfafe1d0"
                        instanceType = "t2.micro"
                        
                        tags = mapOf(
                            "Name" to "WebServer"
                        )
                    }
                """.trimIndent(),
                engineId = "terraform-kotlin-dsl",
                scriptName = "infrastructure.tf.kts"
            )
            
            // Print results
            println("Gradle Result: $gradleResult")
            println("Compose Result: $composeResult")
            println("Spring Result: $springResult")
            println("Terraform Result: $terraformResult")
            
            // Generate and print report
            val report = dslManager.generateReport()
            println(report.getFormattedReport())
            
        } finally {
            dslManager.shutdown()
        }
    }
    
    /**
     * Example of validating multiple DSL types
     */
    suspend fun demonstrateValidation() {
        val dslManager = setupMultiEngineDslManager()
        
        try {
            // Validate different DSL types
            val validations = listOf(
                "gradle-kotlin-dsl" to """
                    plugins {
                        kotlin("jvm")
                    }
                """,
                "docker-compose-dsl" to """
                    services {
                        service("app") {
                            image = "myapp:latest"
                        }
                    }
                """,
                "spring-boot-dsl" to """
                    application {
                        name = "test-app"
                    }
                """,
                "terraform-kotlin-dsl" to """
                    resource("aws_s3_bucket", "bucket") {
                        bucket = "my-bucket"
                    }
                """
            )
            
            validations.forEach { (engineId, script) ->
                val result = dslManager.validateContent(script, engineId)
                println("Validation for $engineId: $result")
            }
            
        } finally {
            dslManager.shutdown()
        }
    }
}

// Mock script definition annotations for the examples
// In real scenarios, these would be provided by the respective libraries

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
annotation class GradleScript

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
annotation class DockerComposeScript

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
annotation class SpringBootScript

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
annotation class TerraformScript

/**
 * Example DSL builder classes that would be provided by third-party libraries
 */

// Mock DSL classes - in real scenarios these would be from actual libraries
class ServicesBuilder {
    fun service(name: String, config: ServiceBuilder.() -> Unit) {
        // Build service configuration
    }
}

class ServiceBuilder {
    var image: String = ""
    var ports: List<String> = emptyList()
    var environment: Map<String, String> = emptyMap()
}

class ApplicationBuilder {
    var name: String = ""
    var profiles: List<String> = emptyList()
    
    fun properties(config: MutableMap<String, Any>.() -> Unit) {
        // Build properties configuration
    }
}

class ProviderBuilder {
    var region: String = ""
}

class ResourceBuilder {
    var ami: String = ""
    var instanceType: String = ""
    var bucket: String = ""
    var tags: Map<String, String> = emptyMap()
}

// Extension functions that would be available in the DSL context
fun services(config: ServicesBuilder.() -> Unit) = ServicesBuilder().apply(config)
fun application(config: ApplicationBuilder.() -> Unit) = ApplicationBuilder().apply(config)
fun provider(name: String, config: ProviderBuilder.() -> Unit) = ProviderBuilder().apply(config)
fun resource(type: String, name: String, config: ResourceBuilder.() -> Unit) = ResourceBuilder().apply(config)