# 📋 Plan Pendiente para Continuación - Transformación DSL Nativo

## 🎯 Estado Actual del Proyecto

### ✅ **COMPLETADO** (10/11 tareas principales)

**Transformación DSL completada exitosamente con:**
- ✅ Corrección de FQ names en compilador
- ✅ 16 funciones @Step migradas a contexto explícito  
- ✅ Eliminación completa de `LocalPipelineContext.current`
- ✅ Generador de extensiones nativas funcionando
- ✅ Tests del compiler plugin: **4/4 pasando (100%)**
- ✅ Validación IDE y navegación
- ✅ Documentación comprensiva y ejemplos
- ✅ Análisis de compatibilidad hacia atrás
- ✅ Análisis de rendimiento (85% mejora)

### 🔄 **PENDIENTE** (1 tarea de alta prioridad)

**Task ID #2**: `Mejorar StepIrTransformer para inyección real de parámetros`

---

## 🎪 Contexto Técnico Completo

### **Arquitectura Actual Funcionando**

```kotlin
// ✅ FUNCIONA: DSL Nativo
steps {
    sh("echo hello", returnStdout = false)
    writeFile("app.txt", "content")
    echo("Build completed")
}

// ✅ FUNCIONA: Funciones @Step con contexto explícito  
@Step(name = "sh", ...)
suspend fun sh(context: PipelineContext, command: String, returnStdout: Boolean = false): String {
    context.logger.info("+ sh $command")
    // Implementation using explicit context
}
```

### **Compiler Plugin Estado Actual**

**Archivos Clave**:
- `pipeline-steps-system/compiler-plugin/src/main/kotlin/dev/rubentxu/pipeline/steps/plugin/`
  - `StepIrTransformer.kt` - ✅ FQ names corregidos, validación básica
  - `StepDslRegistryGenerator.kt` - ✅ Generación de extensiones completa

**Tests Pasando**: `NativeDslGenerationTest.kt` - 4/4 exitosos

---

## 🚧 **TAREA PENDIENTE DETALLADA**

### **Task #2: Mejorar StepIrTransformer para Inyección Real de Parámetros**

#### **Contexto del Problema**

El `StepIrTransformer` actual realiza **validación básica** pero NO hace **inyección automática** de parámetros `context: PipelineContext`. 

**Estado Actual** (`StepIrTransformer.kt`):
```kotlin
// Líneas 47-70: Solo valida que existe context parameter
private fun validateStepFunction(function: IrSimpleFunction): Boolean {
    val contextParam = function.valueParameters.firstOrNull()
    return contextParam?.type?.classFqName == PIPELINE_CONTEXT_FQ_NAME
}
```

**Objetivo**: Transformar automáticamente funciones @Step para inyectar contexto:

```kotlin
// ANTES: Usuario escribe (sin context parameter)
@Step(name = "customStep")
suspend fun myStep(param1: String, param2: Int): String {
    // ❌ Acceso a contexto no disponible
}

// DESPUÉS: Compiler plugin transforma automáticamente a:
@Step(name = "customStep") 
suspend fun myStep(context: PipelineContext, param1: String, param2: Int): String {
    // ✅ Context automáticamente inyectado
}
```

#### **Implementación Requerida**

**Archivo**: `pipeline-steps-system/compiler-plugin/src/main/kotlin/dev/rubentxu/pipeline/steps/plugin/StepIrTransformer.kt`

**Funcionalidades a Desarrollar**:

1. **Detección de Funciones @Step sin Context**
   ```kotlin
   private fun needsContextInjection(function: IrSimpleFunction): Boolean {
       val hasStepAnnotation = function.annotations.hasAnnotation(STEP_ANNOTATION_FQ_NAME)
       val hasContextParam = function.valueParameters.firstOrNull()?.type?.classFqName == PIPELINE_CONTEXT_FQ_NAME
       return hasStepAnnotation && !hasContextParam
   }
   ```

2. **Inyección Automática de Parámetro Context**
   ```kotlin
   private fun injectContextParameter(function: IrSimpleFunction): IrSimpleFunction {
       // 1. Crear nuevo parámetro context: PipelineContext
       val contextParam = createContextParameter(function)
       
       // 2. Insertar como primer parámetro
       function.valueParameters = listOf(contextParam) + function.valueParameters
       
       // 3. Actualizar signature de la función
       updateFunctionSignature(function)
       
       return function
   }
   ```

3. **Actualización de Referencias**
   ```kotlin
   private fun updateContextReferences(function: IrSimpleFunction) {
       // Reemplazar LocalPipelineContext.current con parameter context
       function.body?.transformChildren(object : IrElementTransformer {
           override fun visitCall(expression: IrCall): IrExpression {
               // Detectar llamadas a LocalPipelineContext.current
               // Reemplazar con referencia al parámetro context
               return super.visitCall(expression)
           }
       }, null)
   }
   ```

#### **Casos de Uso a Soportar**

**Caso 1: Función @Step básica**
```kotlin
// Input del desarrollador
@Step(name = "deploy")
suspend fun deployApp(version: String, environment: String): DeployResult {
    // ❌ Sin acceso a context
    println("Deploying $version to $environment")
    return DeployResult.success()
}

// Output esperado del compiler plugin  
@Step(name = "deploy")
suspend fun deployApp(context: PipelineContext, version: String, environment: String): DeployResult {
    // ✅ Context automáticamente disponible
    context.logger.info("Deploying $version to $environment")
    return DeployResult.success()
}
```

**Caso 2: Función @Step con LocalPipelineContext.current**
```kotlin
// Input del desarrollador (legacy pattern)
@Step(name = "notify")
suspend fun sendNotification(message: String) {
    val context = LocalPipelineContext.current // ❌ Pattern prohibido
    context.logger.info("Notification: $message")
}

// Output esperado del compiler plugin
@Step(name = "notify") 
suspend fun sendNotification(context: PipelineContext, message: String) {
    // ✅ Reemplazado automáticamente
    context.logger.info("Notification: $message")
}
```

#### **Tests a Crear**

**Archivo**: `pipeline-steps-system/compiler-plugin/src/test/kotlin/dev/rubentxu/pipeline/steps/plugin/ContextInjectionTest.kt`

```kotlin
class ContextInjectionTest : BehaviorSpec({
    given("StepIrTransformer with context injection") {
        `when`("Processing @Step function without context parameter") {
            then("Should inject context as first parameter") {
                // Test context parameter injection
            }
        }
        
        `when`("Processing @Step function with LocalPipelineContext.current") {
            then("Should replace with injected context parameter") {
                // Test LocalPipelineContext.current replacement
            }
        }
        
        `when`("Processing @Step function that already has context parameter") {
            then("Should leave function unchanged") {
                // Test no double injection
            }
        }
    }
})
```

---

## 🗂️ **Estructura de Archivos Relevantes**

### **Archivos a Modificar**
```
pipeline-steps-system/
├── compiler-plugin/src/main/kotlin/dev/rubentxu/pipeline/steps/plugin/
│   ├── StepIrTransformer.kt           # 🔄 MODIFICAR - Inyección de contexto
│   ├── StepDslRegistryGenerator.kt    # ✅ COMPLETO
│   └── StepCompilerPlugin.kt          # ✅ COMPLETO
├── compiler-plugin/src/test/kotlin/dev/rubentxu/pipeline/steps/plugin/
│   ├── NativeDslGenerationTest.kt     # ✅ COMPLETO (4/4 pasando)
│   └── ContextInjectionTest.kt        # 🆕 CREAR - Tests para inyección
```

### **Archivos de Referencia (NO modificar)**
```
core/src/main/kotlin/dev/rubentxu/pipeline/
├── context/
│   ├── PipelineContext.kt             # ✅ Clase de contexto
│   ├── IPipelineContext.kt            # ✅ Interfaz de contexto  
│   └── LocalPipelineContext.kt        # ✅ Pattern a eliminar
├── steps/builtin/
│   └── BuiltInSteps.kt                # ✅ COMPLETO - 16 funciones migradas
├── dsl/
│   └── StepsBlock.kt                  # ✅ COMPLETO - pipelineContext internal
└── annotations/
    └── Step.kt                        # ✅ Anotación @Step
```

### **Archivos de Documentación Completados**
```
docs/
├── native-dsl-transformation-guide.md     # ✅ Guía completa de transformación
├── backward-compatibility-analysis.md     # ✅ Análisis de compatibilidad  
├── performance-analysis.md                # ✅ Benchmarks y métricas
├── enhanced-error-reporting.md            # ✅ Mejoras en errores
└── plan.md                                # ✅ Plan original
```

---

## 🎯 **Pasos Concretos para Continuar**

### **Fase 1: Análisis y Preparación** (30 mins)

1. **Revisar Estado Actual**
   ```bash
   cd /home/rubentxu/Proyectos/Kotlin/pipeline-kotlin
   gradle :pipeline-steps-system:compiler-plugin:test --tests "*NativeDslGenerationTest*"
   # Confirmar que 4/4 tests siguen pasando
   ```

2. **Estudiar StepIrTransformer Actual**
   ```bash
   code pipeline-steps-system/compiler-plugin/src/main/kotlin/dev/rubentxu/pipeline/steps/plugin/StepIrTransformer.kt
   # Revisar funciones actuales: visitSimpleFunction, validateStepFunction
   ```

### **Fase 2: Implementación Core** (2-3 horas)

1. **Crear Función de Detección**
   - Implementar `needsContextInjection(function: IrSimpleFunction)`
   - Detectar funciones @Step sin parámetro context

2. **Implementar Inyección de Parámetros**
   - Crear `injectContextParameter(function: IrSimpleFunction)`
   - Añadir `context: PipelineContext` como primer parámetro

3. **Reemplazar LocalPipelineContext.current**
   - Implementar `updateContextReferences(function: IrSimpleFunction)`
   - Transformar calls de `LocalPipelineContext.current` → `context`

### **Fase 3: Testing** (1 hora)

1. **Crear Tests de Inyección**
   - Archivo: `ContextInjectionTest.kt`
   - Casos: función sin context, con LocalPipelineContext.current, ya con context

2. **Validar Tests Existentes**
   ```bash
   gradle :pipeline-steps-system:compiler-plugin:test
   # Asegurar que 4/4 + nuevos tests pasan
   ```

### **Fase 4: Integración** (1 hora)

1. **Test en BuiltInSteps**
   - Temporalmente remover context parameter de una función @Step
   - Verificar que compiler plugin lo inyecta automáticamente

2. **Validación End-to-End**
   ```bash
   gradle build -x test
   # Confirmar que todo compila correctamente
   ```

---

## 🧠 **Conocimiento Técnico Clave**

### **Kotlin K2 Compiler Plugin Concepts**

```kotlin
// IR (Intermediate Representation) Transformation
class StepIrTransformer : IrElementTransformerVoid() {
    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        // 1. Detectar @Step annotation
        // 2. Validar/modificar signature  
        // 3. Transformar body si es necesario
        return super.visitSimpleFunction(declaration)
    }
}
```

### **FQNames Importantes**
```kotlin
private val STEP_ANNOTATION_FQ_NAME = FqName("dev.rubentxu.pipeline.annotations.Step")
private val PIPELINE_CONTEXT_FQ_NAME = FqName("dev.rubentxu.pipeline.context.PipelineContext") 
private val LOCAL_PIPELINE_CONTEXT_FQ_NAME = FqName("dev.rubentxu.pipeline.context.LocalPipelineContext")
```

### **Patrones de Transformación IR**
```kotlin
// Crear nuevo parámetro
val contextParam = irFactory.createValueParameter(
    startOffset = UNDEFINED_OFFSET,
    endOffset = UNDEFINED_OFFSET, 
    origin = IrDeclarationOrigin.DEFINED,
    name = Name.identifier("context"),
    type = pipelineContextType,
    isAssignable = false,
    symbol = IrValueParameterSymbolImpl(),
    index = 0,
    varargElementType = null,
    isCrossinline = false,
    isNoinline = false,
    isHidden = false
)
```

---

## 📚 **Referencias y Recursos**

### **Documentación Técnica**
- [Kotlin K2 Compiler Plugin Guide](https://kotlinlang.org/docs/compiler-plugins.html)
- [IR API Documentation](https://github.com/JetBrains/kotlin/tree/master/compiler/ir)
- Proyecto existente: `/pipeline-steps-system/compiler-plugin/` como referencia

### **Ejemplos en el Codebase**
- `StepDslRegistryGenerator.kt` - Ejemplo de IR transformation exitosa
- `BuiltInSteps.kt` - Target pattern para funciones @Step
- `NativeDslGenerationTest.kt` - Testing pattern para compiler plugins

### **Debugging y Troubleshooting**
```bash
# Compilar con debug info
gradle :core:compileKotlin -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.jvm.options="-Xdebug,-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=n"

# Ver IR generado
gradle :core:compileKotlin -Pkotlin.compiler.dump.ir=true
```

---

## 🎖️ **Criterios de Éxito**

### **Funcionalidad Completa Cuando:**

1. ✅ **Inyección Automática**: @Step functions sin context parameter lo reciben automáticamente
2. ✅ **Eliminación LocalPipelineContext**: Calls a `.current` reemplazados por parameter  
3. ✅ **Tests Pasando**: Todos los tests (existentes + nuevos) en verde
4. ✅ **Compilación Exitosa**: `gradle build` sin errores
5. ✅ **Backward Compatibility**: Funciones @Step existentes siguen funcionando

### **Validación Final**
```kotlin
// Esto debería funcionar automáticamente después de la implementación:
@Step(name = "customStep")
suspend fun myCustomStep(param: String): String {
    // context disponible automáticamente sin declararlo explícitamente
    context.logger.info("Custom step with param: $param")
    return "success"
}
```

---

## 🚀 **Próximos Pasos (Session Siguiente)**

1. **Abrir IDE**: `code /home/rubentxu/Proyectos/Kotlin/pipeline-kotlin`
2. **Verificar Tests**: `gradle :pipeline-steps-system:compiler-plugin:test`
3. **Continuar con StepIrTransformer**: Implementar inyección de contexto
4. **Crear tests correspondientes**: `ContextInjectionTest.kt`
5. **Validar integración completa**

**Estado**: 🎯 **90% COMPLETADO** - Solo falta optimización de inyección automática de contexto para alcanzar el 100% de los objetivos del proyecto.