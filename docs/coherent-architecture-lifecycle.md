# Arquitectura Coherente del Ciclo de Vida - Pipeline DSL

## Resumen

Este documento describe la arquitectura modernizada del sistema Pipeline DSL, que implementa un flujo coherente y 100% Kotlin nativo para el ciclo de vida completo de la aplicación.

## Arquitectura Modernizada

### 1. **Flujo Coherente del Ciclo de Vida**

```
Usuario Script (.pipeline.kts)
    ↓
CLI (SimplePipelineCli)
    ↓
PipelineScriptRunner.evalWithDslManager()
    ↓
DslManager.buildPipeline()
    ↓
PipelineDslEngine<Pipeline>
    ↓
Pipeline Object
    ↓
JobExecutor.execute()
    ↓
PipelineResult
```

### 2. **Separación de Responsabilidades**

#### **DslEngine<T>**: Compilación y Construcción DSL
- **Responsabilidad**: Compilar scripts DSL y construir objetos tipados
- **Input**: Script content + contexts
- **Output**: Typed objects (Pipeline, Workflow, Config, etc.)
- **No ejecuta** el objeto construido

#### **DslManager**: Coordinación y Orquestación  
- **Responsabilidad**: Coordinar engines, security, cache, eventos
- **Funciones**: `buildPipeline()`, `executeFile()`, validación
- **Gestiona**: Registry, sandbox, resource limits

#### **JobExecutor**: Ejecución de Pipeline
- **Responsabilidad**: Ejecutar pipelines construidos
- **Input**: Pipeline object
- **Output**: PipelineResult
- **Maneja**: Stages, steps, agentes, env vars

#### **PipelineScriptRunner**: Entry Point Legacy-Compatible
- **Responsabilidad**: Bridge entre CLI legacy y sistema moderno
- **Flujo**: Config → DslManager → Pipeline → JobExecutor

### 3. **Eliminación de Dependencias Java Legacy**

#### **Antes (Problemático)**:
```kotlin
// ❌ Java legacy
javax.script.ScriptEngineManager
ScriptEngine.eval()
buildPipeline(pipelineDefinition) // Mezclaba responsabilidades
```

#### **Después (100% Kotlin Nativo)**:
```kotlin
// ✅ Kotlin nativo
kotlin.script.experimental.api.*
DslManager.buildPipeline() // Separación clara
PipelineDslEngine<Pipeline> // Tipo específico
```

### 4. **Mejora de Connascence**

#### **Connascence Reducida**:
- **Position → Type**: Contexts agrupados en objetos
- **Meaning → Name**: Lógica específica extraída de componentes genéricos
- **Identity → Name**: Dependencias por interfaz, no implementación

#### **Ejemplo de Mejora**:
```kotlin
// Antes: Connascence of Position + Meaning
class PipelineDslEngine : DslEngine<PipelineResult> {
    fun execute(script, context1, context2, context3): PipelineResult {
        val pipeline = buildPipeline() // Mixing concerns
        return executePipeline(pipeline) // Wrong responsibility
    }
}

// Después: Connascence of Name + Type
class PipelineDslEngine : DslEngine<Pipeline> {
    fun execute(script: CompiledScript, context: DslExecutionContext): DslExecutionResult<Pipeline> {
        val definition = extractPipelineDefinition()
        return DslExecutionResult.Success(definition.build(config))
    }
}
```

## Beneficios Obtenidos

### **🎯 Coherencia Arquitectural**
- **Flujo unidireccional**: Cada clase tiene una responsabilidad específica
- **Orden claro**: CLI → Runner → Manager → Engine → Executor
- **Sin bypass**: Todo pasa por la arquitectura diseñada

### **🚀 100% Kotlin Nativo**
- **Zero Java dependencies**: Eliminado `javax.script.*`
- **Modern Kotlin**: Uso de `kotlin.script.experimental.*`
- **Coroutines nativas**: Async/await pattern completo

### **🛡️ Separación de Responsabilidades**
- **DSL Engine**: Solo compila y construye objetos
- **Job Executor**: Solo ejecuta pipelines construidos  
- **Manager**: Solo coordina y orquesta
- **Runner**: Solo hace bridge legacy-compatible

### **🔧 Extensibilidad Genérica**
```kotlin
// Soporte futuro para múltiples DSL types
class WorkflowDslEngine : DslEngine<Workflow>
class ConfigDslEngine : DslEngine<Configuration>
class TestDslEngine : DslEngine<TestSuite>

// Registro automático y detección por extensión
dslManager.registerEngine(workflowEngine)
val workflow = dslManager.executeFile<Workflow>("deploy.workflow.kts")
```

### **⚡ Rendimiento y Recursos**
- **Object pooling**: Mantenido en engines
- **Compilation cache**: Integrado en manager
- **Resource limits**: Aplicados consistentemente
- **Event streaming**: Para monitoreo y debugging

## Validación de la Cadena

### **Verificación del Flujo Completo**:

1. **CLI invoca Runner** ✅
2. **Runner carga config** ✅  
3. **Runner crea DslManager** ✅
4. **Manager selecciona Engine correcto** ✅
5. **Engine compila script a Pipeline** ✅
6. **Runner ejecuta Pipeline via JobExecutor** ✅
7. **JobExecutor retorna PipelineResult** ✅

### **Interfaces Correctas**:
- `DslEngine<Pipeline>` ✅
- `DslManager.buildPipeline(): Pipeline` ✅
- `JobExecutor.execute(Pipeline): PipelineResult` ✅
- `ILogger` en toda la cadena ✅

### **Sin Java Legacy**:
- `javax.script.*` eliminado ✅
- `ScriptEngineManager` eliminado ✅
- Solo `kotlin.script.experimental.*` ✅

## Conclusion

La arquitectura modernizada implementa un ciclo de vida coherente donde cada componente tiene responsabilidades claras y todas las clases se usan en el orden correcto. El sistema es 100% Kotlin nativo, extensible para múltiples tipos de DSL, y mantiene alta performance mientras mejora significativamente la maintainability y testability.

Esta refactorización transforma el sistema de fuertemente acoplado (Strong Connascence) a débilmente acoplado (Weak Connascence), siguiendo principios SOLID y Clean Architecture.


---

aquí tienes una referencia a las clases e interfaces principales involucradas en los ficheros proporcionados, agrupadas por su funcionalidad:


1. Ejecución y Definición del Pipeline
   PipelineScriptRunner: (En pipeline-backend/src/main/kotlin/dev/rubentxu/pipeline/backend/PipelineScriptRunner.kt) La clase principal que parece orquestar la ejecución de los scripts de pipeline.
   PipelineDefinition: (En core/src/main/kotlin/dev/rubentxu/pipeline/model/pipeline/PipelineDefinition.kt) Representa la definición de un pipeline creada a partir de un bloque DSL.
   Pipeline: (Referenciada en StepsBlock.kt y PipelineDslEngine.kt) La clase que representa el pipeline ejecutable una vez construido.
   StepsBlock: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/StepsBlock.kt) Define un bloque de pasos (steps) dentro de una etapa del pipeline y proporciona el contexto para ejecutar comandos como sh.
2. Motores DSL (DSL Engines)
   DslEngine<TResult>: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/DslEngine.kt) Interfaz genérica para cualquier motor DSL, definiendo operaciones como compile, execute y validate.
   PipelineDslEngine: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/engines/PipelineDslEngine.kt) Una implementación específica de DslEngine para procesar los scripts .pipeline.kts.
   GenericKotlinDslEngine: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/engines/GenericKotlinDslEngine.kt) Un motor DSL genérico y configurable para ejecutar otras bibliotecas DSL basadas en Kotlin.
   DslManager: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/DslManager.kt) Una clase que parece gestionar la ejecución de scripts DSL de una manera simplificada.
3. Registro de Motores DSL
   DslEngineRegistry: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/DslEngine.kt) Interfaz para un registro que gestiona múltiples motores DSL.
   DefaultDslEngineRegistry: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/DefaultDslEngineRegistry.kt) La implementación por defecto del registro de motores, capaz de registrar, desregistrar y buscar motores por ID o extensión de fichero.
4. Contexto y Configuración de DSL
   Estas son principalmente clases de datos (data class) que encapsulan la configuración para la compilación y ejecución.


DslCompilationContext: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/DslEngine.kt) Contiene la configuración para la fase de compilación (imports, classpath, etc.).
DslExecutionContext: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/DslEngine.kt) Contiene la configuración para la fase de ejecución (variables, directorio de trabajo, límites de recursos).
DslSecurityPolicy, DslResourceLimits, DslExecutionPolicy: Clases que definen políticas de seguridad, límites de recursos y comportamiento de ejecución.
5. Resultados y Diagnósticos
   Son clases selladas (sealed class) que representan los posibles resultados de las operaciones del DSL.
   DslCompilationResult: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/DslEngine.kt) Representa el resultado de una compilación, que puede ser Success o Failure.
   DslExecutionResult: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/DslEngine.kt) Representa el resultado de una ejecución.
   DslValidationResult: (En core/src/main/kotlin/dev/rubentxu/pipeline/dsl/DslEngine.kt) Representa el resultado de una validación.
   DslError y DslWarning: Clases de datos para encapsular errores y advertencias con información detallada.