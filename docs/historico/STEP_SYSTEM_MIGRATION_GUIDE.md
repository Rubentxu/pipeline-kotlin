# Guía de Migración al Sistema @Step

## Introducción

El sistema @Step representa una evolución fundamental de la pipeline DSL de Kotlin, reemplazando las extension functions con un sistema similar a @Composable de Jetpack Compose. Este sistema proporciona mejor seguridad, tipo safety y mantenibilidad.

## Conceptos Clave

### Del Patrón Extension Function al Patrón @Step

**Antes (Extension Functions - DEPRECATED):**
```kotlin
// Funciones de extensión en StepsBlock
pipeline {
    stages {
        stage("Build") {
            steps {
                sh("./gradlew build")  // Extension function
                echo("Build completed")
                readFile("output.log")
            }
        }
    }
}
```

**Ahora (Sistema @Step):**
```kotlin
// @Step functions con PipelineContext automático
pipeline {
    stages {
        stage("Build") {
            steps {
                sh("./gradlew build")  // @Step function con contexto inyectado
                echo("Build completed")
                readFile("output.log")
            }
        }
    }
}
```

### PipelineContext: El Corazón del Sistema

Similar a como `Composer` es el corazón de Jetpack Compose, `PipelineContext` es el corazón del sistema @Step:

```kotlin
interface PipelineContext {
    // Acceso controlado a recursos
    val pipeline: Pipeline
    val logger: IPipelineLogger
    val workingDirectory: Path
    val environment: Map<String, String>
    
    // Operaciones seguras
    suspend fun executeShell(command: String, options: ShellOptions): ShellResult
    suspend fun readFile(path: String): String
    suspend fun writeFile(path: String, content: String)
    
    // Gestión de estado (como Compose remember)
    fun <T> remember(key: Any, computation: () -> T): T
    
    // Seguridad y límites de recursos
    val securityLevel: SecurityLevel
    val resourceLimits: ResourceLimits
}
```

## Migración Paso a Paso

### 1. Actualizar el StepsBlock

**Antes:**
```kotlin
stage("Deploy") {
    steps {  // StepsBlock antiguo
        sh("kubectl apply -f deployment.yaml")
        echo("Deployment completed")
    }
}
```

**Después:**
```kotlin
stage("Deploy") {
    steps {  // StepsBlock
        sh("kubectl apply -f deployment.yaml")  // Misma sintaxis, nueva implementación
        echo("Deployment completed")
    }
}
```

### 2. Crear Steps Personalizados

**Antes (Extension Functions - DEPRECATED):**
```kotlin
// Extension function personalizada
fun StepsBlock.deployToK8s(manifest: String, namespace: String = "default") {
    sh("kubectl apply -f $manifest -n $namespace")
    echo("Deployed to $namespace")
}
```

**Después (@Step Functions):**
```kotlin
// @Step function con contexto automático
@Step(
    name = "deployToK8s",
    description = "Deploy to Kubernetes cluster",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun deployToK8s(manifest: String, namespace: String = "default") {
    // PipelineContext automáticamente disponible
    val context = LocalPipelineContext.current
    
    val result = context.executeShell("kubectl apply -f $manifest -n $namespace")
    if (result.success) {
        context.logger.info("Deployed to $namespace")
    } else {
        throw RuntimeException("Deployment failed: ${result.stderr}")
    }
}
```

### 3. Testing de Steps

**Antes:**
```kotlin
@Test
fun testCustomStep() {
    val pipeline = createTestPipeline()
    val stepsBlock = StepsBlock(pipeline)
    
    // Test manual
    stepsBlock.deployToK8s("manifest.yaml")
}
```

**Después:**
```kotlin
@Test
fun testCustomStep() = runTest {
    StepTestUtils.runStepTest {
        // Setup automático del contexto
        createTestFile("manifest.yaml", "apiVersion: v1...")
        
        // Mock de comandos shell si es necesario
        mockStep("kubectl") { "deployment created" }
        
        // Test de la step
        deployToK8s("manifest.yaml", "production")
        
        // Verificaciones
        executionRecorder.verifyStepExecuted("deployToK8s")
    }
}
```

## Beneficios del Sistema @Step

### 1. Seguridad Mejorada

```kotlin
// Niveles de seguridad automáticos
@Step(securityLevel = SecurityLevel.ISOLATED)
suspend fun untrustedOperation() {
    // Ejecutado en sandbox máximo
}

@Step(securityLevel = SecurityLevel.RESTRICTED)
suspend fun normalOperation() {
    // Ejecutado con límites de recursos
}

@Step(securityLevel = SecurityLevel.TRUSTED)
suspend fun systemOperation() {
    // Acceso completo (solo para steps del core)
}
```

### 2. Gestión de Recursos

```kotlin
// Límites automáticos por contexto
val context = PipelineContext.create(
    resourceLimits = ResourceLimits(
        maxMemoryMB = 512,
        maxCpuTimeSeconds = 300,
        maxWallTimeSeconds = 600
    )
)
```

### 3. Type Safety Mejorada

```kotlin
// Parámetros tipados y validados
@Step
suspend fun processFile(
    inputFile: String,
    outputFormat: OutputFormat = OutputFormat.JSON,
    compress: Boolean = false
): ProcessResult {
    // Validation automática de parámetros
    // Tipo de retorno garantizado
}
```

### 4. Composabilidad

```kotlin
@Step
suspend fun buildAndDeploy(
    environment: String,
    version: String
) {
    // Steps pueden llamar otros steps naturalmente
    val buildResult = buildProject(version)
    val testResult = runTests(buildResult.artifactPath)
    
    if (testResult.success) {
        deployToEnvironment(environment, buildResult.artifactPath)
    }
}
```

## Patrones de Migración Comunes

### 1. Steps con Estado

**Antes:**
```kotlin
fun StepsBlock.buildWithCache() {
    var cacheHit = false
    if (fileExists(".gradle/cache")) {
        cacheHit = true
        echo("Using cached dependencies")
    }
    // ... resto de la lógica
}
```

**Después:**
```kotlin
@Step
suspend fun buildWithCache() {
    val context = LocalPipelineContext.current
    
    // Usar remember para estado persistente
    val cacheStatus = context.remember("build-cache") {
        if (context.fileExists(".gradle/cache")) {
            CacheStatus.HIT
        } else {
            CacheStatus.MISS
        }
    }
    
    when (cacheStatus) {
        CacheStatus.HIT -> context.logger.info("Using cached dependencies")
        CacheStatus.MISS -> downloadDependencies()
    }
}
```

### 2. Steps con Configuración Compleja

**Antes:**
```kotlin
fun StepsBlock.configureDocker(
    image: String,
    ports: List<Int>,
    volumes: Map<String, String>
) {
    // Lógica compleja de configuración
}
```

**Después:**
```kotlin
@Step
suspend fun configureDocker(
    image: String,
    ports: List<Int> = emptyList(),
    volumes: Map<String, String> = emptyMap()
) {
    val config = DockerConfig(image, ports, volumes)
    config.validate() // Validation automática
    
    val context = LocalPipelineContext.current
    context.executeShell(config.generateDockerCommand())
}

data class DockerConfig(
    val image: String,
    val ports: List<Int>,
    val volumes: Map<String, String>
) {
    fun validate() {
        require(image.isNotBlank()) { "Docker image cannot be blank" }
        require(ports.all { it in 1..65535 }) { "Invalid port numbers" }
    }
    
    fun generateDockerCommand(): String {
        // Generación segura del comando
    }
}
```

### 3. Steps con Manejo de Errores

**Antes:**
```kotlin
fun StepsBlock.deployWithRetry(manifest: String, maxRetries: Int = 3) {
    retry(maxRetries) {
        sh("kubectl apply -f $manifest")
    }
}
```

**Después:**
```kotlin
@Step
suspend fun deployWithRetry(
    manifest: String, 
    maxRetries: Int = 3
) {
    val context = LocalPipelineContext.current
    
    retry(maxRetries) {
        val result = context.executeShell("kubectl apply -f $manifest")
        if (!result.success) {
            throw DeploymentException("Deployment failed: ${result.stderr}")
        }
        result.stdout
    }
}

class DeploymentException(message: String) : Exception(message)
```

## Mejores Prácticas

### 1. Naming Conventions

```kotlin
// ✅ Bueno: Verbos claros y específicos
@Step suspend fun deployToProduction()
@Step suspend fun validateConfiguration()
@Step suspend fun generateReport()

// ❌ Malo: Nombres vagos
@Step suspend fun doStuff()
@Step suspend fun process()
@Step suspend fun handle()
```

### 2. Gestión de Parámetros

```kotlin
// ✅ Bueno: Parámetros con defaults y validation
@Step
suspend fun publishArtifact(
    artifactPath: String,
    repository: String = "default",
    version: String? = null,
    metadata: ArtifactMetadata = ArtifactMetadata()
) {
    require(artifactPath.isNotBlank()) { "Artifact path required" }
    // ... implementation
}

// ❌ Malo: Demasiados parámetros sin estructura
@Step
suspend fun publishArtifact(
    path: String,
    repo: String,
    ver: String,
    name: String,
    desc: String,
    tags: String,
    // ... más parámetros
)
```

### 3. Manejo de Recursos

```kotlin
// ✅ Bueno: Cleanup automático con use
@Step
suspend fun processLargeFile(filePath: String) {
    val context = LocalPipelineContext.current
    
    context.provide(MemoryContextKey, HighMemoryConfig()) {
        // Procesamiento que requiere mucha memoria
        processFile(filePath)
    } // Cleanup automático
}

// ✅ Bueno: Validation de recursos
@Step
suspend fun downloadLargeFile(url: String) {
    val context = LocalPipelineContext.current
    
    if (context.resourceLimits.maxMemoryMB < 1024) {
        throw InsufficientResourcesException("Need at least 1GB memory")
    }
    
    // ... download logic
}
```

### 4. Testing Comprehensivo

```kotlin
class DeploymentStepsTest : FunSpec({
    test("deployToK8s should handle successful deployment") {
        StepTestUtils.runStepTest(securityLevel = SecurityLevel.RESTRICTED) {
            // Setup
            createTestFile("deployment.yaml", validDeploymentManifest)
            mockStep("kubectl") { "deployment.apps/myapp created" }
            
            // Execute
            deployToK8s("deployment.yaml", "production")
            
            // Verify
            executionRecorder.verifyStepExecuted("deployToK8s")
            executionRecorder.verifyStepExecutedTimes("kubectl", 1)
        }
    }
    
    test("deployToK8s should handle deployment failure") {
        StepTestUtils.runStepTest {
            mockStep("kubectl") { 
                throw RuntimeException("kubectl: error validating data")
            }
            
            shouldThrow<DeploymentException> {
                deployToK8s("invalid.yaml")
            }
        }
    }
})
```

## Roadmap y Futuro

### Fase Actual (DSL v1)
- ✅ Sistema @Step básico implementado
- ✅ PipelineContext con inyección manual
- ✅ Security manager integrado
- ✅ Testing framework

### Fase Futura (DSL v2) - Cuando K2 API sea estable
- 🔄 Plugin de compilador K2 completo
- 🔄 Inyección automática de PipelineContext
- 🔄 Validation en tiempo de compilación
- 🔄 Optimizaciones de rendimiento

### Migración Gradual
1. **Inmediato**: Empezar usando StepsBlock con sistema @Step
2. **Corto plazo**: Migrar steps personalizados a @Step
3. **Medio plazo**: Deprecar extension functions completamente
4. **Largo plazo**: Activar plugin K2 cuando esté estable

## Soporte y Recursos

- **Documentación técnica**: `docs/architecture/`
- **Ejemplos**: `lib-examples/src/main/kotlin/`
- **Tests de integración**: `core/src/test/kotlin/dev/rubentxu/pipeline/steps/`
- **Issues y feedback**: GitHub Issues

## Conclusión

El sistema @Step representa un salto evolutivo significativo en la pipeline DSL, proporcionando:

- **Mejor seguridad** mediante sandboxing automático
- **Type safety** mejorada con validation en compile-time
- **Mantenibilidad** a través de separación de responsabilidades
- **Composabilidad** natural entre steps
- **Testing** robusto con mocking y verification

La migración puede ser gradual, permitiendo que equipos adopten el nuevo sistema a su propio ritmo mientras mantienen compatibilidad con el código existente.