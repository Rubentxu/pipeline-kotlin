Quiero un plan de acción detallado y completo para evolucionar tu sistema de DSL, centrándote en `PipelineDslEngine`. 
El objetivo es abandonar el patrón `executeStep("nombre", mapOf(...))` en favor de una experiencia de DSL verdaderamente nativa y segura en tipos, como `dockerBuild(tag = "my-app:1.0")`, 
donde el IDE ofrezca autocompletado, verificación de tipos y navegación, incluso para los pasos definidos en plugins.

La clave para lograr esto, como has intuido, es la **intervención en el proceso de compilación**. El `PipelineContext` es la pieza central que debe estar disponible de forma implícita para todos estos pasos.

Aquí tienes el plan detallado para construir esta solución.

### El Reto Fundamental: La Ceguera del Compilador

El compilador de Kotlin (y el IDE) no puede saber que una cadena como `"sh"` en `executeStep` corresponde a una función `sh(command: String)`. 
Para que el IDE y el compilador ofrezcan autocompletado y seguridad de tipos, necesitan "ver" una declaración de función real en el momento del análisis. 
No podemos escribir estáticamente estas funciones en `StepsBlock` porque provienen de plugins descubiertos en tiempo de ejecución.

### La Solución: Un Plugin de Compilador + Inyección de Contexto

La solución se basa en dos pilares que trabajan juntos:

1.  **Plugin de Compilador de Kotlin (K2):** Este plugin "enseñará" al compilador sobre los pasos de pipeline disponibles. 
2. Antes de compilar el script del usuario, escaneará los plugins, encontrará los pasos (`@Step`) y generará dinámicamente las declaraciones de función correspondientes (stubs) dentro del ámbito del script.
2.  **Propagación de `PipelineContext`:** El `PipelineContext` se proporcionará a estos pasos de forma implícita.
3. El plugin del compilador se asegurará de que cada llamada a un paso se transforme para recibir y utilizar este contexto.

---

### Plan de Implementación Detallado

#### Fase 1: Definir un Contrato Sólido para los Pasos

Necesitamos una forma estándar para que los plugins declaren sus pasos. Usaremos anotaciones y una interfaz base.

1.  **Usar la Anotación `@Step`:**
    Esta anotación marcará una función como un paso de pipeline ejecutable.

    ```kotlin
    package dev.rubentxu.pipeline.annotations
    
    /**
     * Anota una función como un paso de pipeline ejecutable.
     * El plugin del compilador de pipeline descubrirá estas funciones y las hará
     * disponibles en el DSL de 'steps { ... }'.
     *
     * @param name El nombre con el que se registrará el paso. Si está en blanco, se usará el nombre de la función.
     * @param description Una breve descripción de lo que hace el paso, para documentación.
     */
    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME) // RUNTIME para que el motor pueda inspeccionarlo también.
    annotation class Step(
        val name: String = "",
        val description: String
    )
    ```

2.  **Definir una Interfaz para los Pasos (Opcional, pero recomendado):**
    Para estandarizar la forma en que se cargan los conjuntos de pasos.

    ```kotlin
    package dev.rubentxu.pipeline.plugins
    
    /**
     * Interfaz para un conjunto de pasos de pipeline proporcionados por un plugin.
     * Los plugins pueden implementar esta interfaz para exponer sus funciones @Step.
     */
    interface StepProvider {
        // Esta interfaz puede estar vacía y servir solo como marcador,
        // o puede tener métodos para la inicialización si es necesario.
    }
    ```

    **Ejemplo de un paso en un plugin:**

    ```kotlin
    // En el plugin 'docker-steps'
    import dev.rubentxu.pipeline.annotations.Step
    import dev.rubentxu.pipeline.context.PipelineContext
    
    class DockerSteps : StepProvider {
        @Step(description = "Construye una imagen de Docker.")
        suspend fun dockerBuild(context: PipelineContext, tag: String, path: String = ".") {
            context.executeShell("docker build -t $tag $path")
        }
    }
    ```
    *Nota: Por ahora, el `PipelineContext` se pasa explícitamente. El plugin del compilador comprobara que lo lleve.*

#### Fase 2: Modificar `PipelineDslEngine` para Descubrir Pasos

El motor necesita saber qué pasos existen *antes* de compilar un script.

1.  **Mecanismo de Descubrimiento:**
    `PipelineDslEngine` (o `DslManager`) usará el `PluginManager` para encontrar todas las clases que implementen `StepProvider`. Luego, usará reflexión para encontrar todas las funciones anotadas con `@Step` dentro de esas clases.

2.  **Almacenar Metadatos de Pasos:**
    Crea una clase para almacenar la información de cada paso descubierto.

    ```kotlin
    data class StepMetadata(
        val name: String,
        val description: String,
        val declaringClass: KClass<*>,
        val function: KFunction<*>,
        val parameters: List<KParameter> 
    )
    ```

3.  **Integración en `PipelineDslEngine`:**
    El motor cargará estos metadatos y los pasará a la configuración de compilación del script.

    ```kotlin
    // En GenericKotlinDslEngine o una clase base
    private fun createCompilationConfiguration(
        // ... otros parámetros
        discoveredSteps: List<StepMetadata> // Nueva adición
    ): ScriptCompilationConfiguration {
        return ScriptCompilationConfiguration {
            // ... configuración existente
            
            // Pasa los metadatos de los pasos al plugin del compilador.
            compilerOptions.append("-P", "plugin:pipeline-compiler-plugin:steps=${serializeSteps(discoveredSteps)}")
            
            // Asegúrate de que el classpath del script incluya el plugin del compilador.
            updateClasspath(compilerPluginJar) 
        }
    }
    
    // `serializeSteps` convertiría la lista de metadatos a un formato (ej. Base64 JSON)
    // que se puede pasar como una opción del compilador.
    ```

#### Fase 3: el Plugin de Compilador de Kotlin (`pipeline-steps-systeml:compiler-plugin`)

Esta es la parte más compleja y poderosa. Usaremos la API del compilador K2.

1.  **Estructura del Proyecto del Plugin:**
   *   Un proyecto Gradle separado para el plugin. (pipeline-steps-systeml:gradle-plugin)
   *   Dependencias de `kotlin-compiler-embeddable`.

2.  **ComponentRegistrar:**
    El punto de entrada del plugin. Registra las extensiones que modificarán el proceso de compilación.

    ```kotlin
    class PipelineComponentRegistrar : ComponentRegistrar {
        override fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration) {
            val stepsEncoded = configuration.get("steps", "")
            if (stepsEncoded.isBlank()) return
            
            val steps = deserializeSteps(stepsEncoded)
            
            // Registra la extensión que generará las funciones
            FirExtensionRegistrarAdapter.registerExtension(project, PipelineFirExtension(steps))
        }
    }
    ```

3.  **FirExtensionRegistrar (`PipelineFirExtension`):**
    Aquí es donde ocurre la magia. Usaremos un `FirDeclarationGenerationExtension` para generar el código de las funciones de los pasos.

    ```kotlin
    class PipelineFirExtension(private val steps: List<StepMetadata>) : FirExtensionRegistrar() {
        override fun ExtensionRegistrarContext.configurePlugin() {
            // Genera nuevas declaraciones de funciones
            +::PipelineDeclarationGenerator
        }
    }
    
    class PipelineDeclarationGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
        
        // El compilador preguntará qué nombres de funciones puede generar este plugin
        override fun getTopLevelFunctionClassIds(): Set<CallableId> {
            return steps.map { CallableId(FqName("dev.rubentxu.pipeline.dsl"), Name.identifier(it.name)) }.toSet()
        }
    
        // Aquí generamos el "cuerpo" de la función (en realidad, un stub con la firma correcta)
        override fun generateFunctions(callableId: CallableId, context: MemberGenerationContext?): List<FirNamedFunctionSymbol> {
            val step = steps.find { it.name == callableId.callableName.identifier } ?: return emptyList()
            
            // Usando un SymbolTable y FirBuilder, construimos una nueva función.
            // Esto es código complejo y detallado, pero la idea es:
            return listOf(
                buildSimpleFunction {
                    // ...
                    name = callableId.callableName
                    // ¡CRÍTICO! Añadimos PipelineContext como un receptor de contexto.
                    contextReceivers.add(buildContextReceiver {
                        typeRef = buildResolvedTypeRef { type = pipelineContextType }
                    })
                    
                    // Añadir los parámetros del paso (tag, path, etc.)
                    step.parameters.forEach { param ->
                        valueParameters.add(buildValueParameter {
                            name = Name.identifier(param.name)
                            returnTypeRef = buildResolvedTypeRef { type = param.type.toFirType() }
                            if (param.isOptional) {
                                defaultValue = buildExpressionStub() // Indicar que hay un valor por defecto
                            }
                        })
                    }
                    // ...
                }.symbol
            )
        }
    }
    ```

4.  **Transformación de la Llamada (Opcional pero recomendado):**
    Podrías añadir un `FirExpressionResolutionExtension` para transformar la llamada `dockerBuild(...)` en la llamada real `pipelineContext.executeStep("dockerBuild", mapOf(...))`. 
    Esto desacopla completamente el DSL de la implementación subyacente.

#### Fase 4: Unificar Todo

1.  **Actualizar `StepsBlock`:**
    Elimina las implementaciones manuales de `sh`, `echo`, etc. El bloque ahora estará casi vacío, ya que las funciones de los pasos se inyectarán mágicamente.

    ```kotlin
    // @PipelineDsl (o anotación similar)
    open class StepsBlock(val pipeline: Pipeline) {
        // El PipelineContext que se hará disponible a través del plugin del compilador.
        internal val pipelineContext: PipelineContext = StepExecutionContext.create(
            pipeline = pipeline,
            // ...
        ).toPipelineContext()
    
        // El cuerpo de este bloque está intencionadamente vacío.
        // Las funciones como sh(), dockerBuild(), etc., son generadas por
        // el plugin del compilador y se resuelven contra el pipelineContext.
    }
    ```

2.  **Flujo de Ejecución en `PipelineDslEngine`:**
    Cuando se llama a `compileAndExecute`:
    a. `DslManager` pide al `PluginManager` los `StepProvider`.
    b. Se extraen los `StepMetadata` mediante reflexión.
    c. Los metadatos se serializan y se pasan a la `ScriptCompilationConfiguration`.
    d. Se invoca al compilador de scripts de Kotlin.
    e. Nuestro `pipeline-compiler-plugin` se activa.
    f. `PipelineDeclarationGenerator` genera las funciones `sh(...)`, `dockerBuild(...)`, etc., con `context(PipelineContext)` dentro del ámbito del script.
    g. El script del usuario, que ahora ve estas funciones, se compila con éxito.
    h. Durante la ejecución, se crea una instancia de `StepsBlock`.
    i. El `pipelineContext` de esa instancia se proporciona al script.
    j. Cuando se llama a `sh("...")`, el `PipelineContext` se resuelve implícitamente y la llamada se delega a la implementación real (por ejemplo, `pipelineContext.executeShell(...)`).

### Resumen del Resultado Final

Con este plan, cuando un usuario escriba dentro de un bloque `steps { ... }`:

```kotlin
steps {
    // 1. El IDE sugiere 'sh', 'dockerBuild', etc.
    // 2. Al escribir `dockerBuild(`, el IDE muestra los parámetros `tag` y `path`.
    // 3. `dockerBuild(tag = 123)` da un error de tipo porque se esperaba un String.
    // 4. Ctrl+Click en `dockerBuild` podría (con más trabajo en un plugin de IDE)
    //    navegar a la definición en el plugin de origen.
    sh("echo 'Construyendo imagen...'")
    dockerBuild(tag = "my-app:latest", path = "./app") 
}
```

Filosofía del Diseño
Simplicidad para el Creador de Plugins: Un desarrollador que añade pasos (ej. dockerBuild) lo hará en una clase normal, definiendo una función que simplemente recibe PipelineContext como su primer parámetro. Sin necesidad de conocer conceptos avanzados como context receivers.

Transparencia para el Usuario del DSL: El usuario final solo ve dockerBuild(tag = "..."). No sabe nada del contexto. El IDE le ofrece autocompletado, documentación y verificación de tipos en tiempo real.

Inteligencia en el Compilador: Toda la complejidad se aísla en un único lugar: el plugin de compilador de Kotlin. Él es el puente que conecta la simplicidad del creador con la magia del usuario.

Fase 1: El Contrato del Paso (La Perspectiva del Creador de Plugins) 📜
Definimos un contrato simple y claro para cualquier librería de terceros que quiera proveer pasos.

La Anotación @Step:
Marca una función como un paso de pipeline. Es el punto de entrada para el descubrimiento.

Kotlin

package dev.rubentxu.pipeline.annotations

/**
* Anota una función como un paso de pipeline ejecutable.
* @param name El nombre del paso en el DSL. Si está vacío, se usa el nombre de la función.
* @param description Descripción para la documentación del DSL.
  */
  @Target(AnnotationTarget.FUNCTION)
  @Retention(AnnotationRetention.RUNTIME) // Necesario para que el motor lo lea en tiempo de ejecución
  annotation class Step(
  val name: String = "",
  val description: String
  )
  La Interfaz StepProvider:
  Una interfaz marcadora para facilitar el descubrimiento de clases que contienen pasos.

Kotlin

package dev.rubentxu.pipeline.plugins

interface StepProvider {
// Interfaz marcadora para descubrir conjuntos de pasos.
}
Ejemplo Definitivo de Implementación de un Paso:
Así es como un desarrollador de un plugin docker-steps implementaría su paso. Nota la simplicidad: es solo una función en una clase.

Kotlin

// En el plugin 'docker-steps'
package com.example.dockerplugin

import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.plugins.StepProvider
import dev.rubentxu.pipeline.context.PipelineContext // El contexto se importa y se usa

class DockerSteps : StepProvider {

    @Step(description = "Construye una imagen de Docker.")
    suspend fun dockerBuild(
        // ¡CRÍTICO! El contexto es el primer parámetro, de forma explícita y simple.
        context: PipelineContext, 
        tag: String, 
        path: String = "."
    ) {
        println("Ejecutando dockerBuild desde el plugin")
        context.executeShell("docker build -t $tag $path")
    }

    @Step(name = "sh", description = "Ejecuta un comando de shell.")
    suspend fun shell(context: PipelineContext, command: String): String {
        return context.executeShell(command)
    }
}
Fase 2: El Motor de Pipeline (Descubrimiento y Preparación) ⚙️
El PipelineDslEngine es responsable de encontrar los pasos y preparar la información para el compilador.

Descubrimiento de Pasos:
Al iniciar, el motor usa un PluginManager para escanear el classpath (o usar ServiceLoader) en busca de todas las clases que implementen StepProvider.

Extracción de Metadatos:
Para cada clase encontrada, usa reflexión de Kotlin (kotlin-reflect) para encontrar todas las funciones anotadas con @Step. Para cada función, crea un objeto de metadatos.

Kotlin

// Objeto que captura todo lo que necesitamos saber sobre un paso
@Serializable // Para poder pasarlo al compilador
data class StepMetadata(
val dslName: String, // Nombre en el DSL (ej. "dockerBuild")
val description: String,
val providerClassFqName: String, // "com.example.dockerplugin.DockerSteps"
val functionName: String, // "dockerBuild"
val parameters: List<StepParameterMetadata> // Metadatos de los parámetros
)

@Serializable
data class StepParameterMetadata(
val name: String,
val typeFqName: String, // "kotlin.String"
val isOptional: Boolean
)
Preparación para la Compilación:
El motor serializa la lista de todos los StepMetadata encontrados a una cadena JSON (usando kotlinx.serialization), la codifica en Base64 para evitar problemas con caracteres especiales, y la pasa como una opción al plugin de compilador.

Kotlin

// En PipelineDslEngine.kt
private fun createCompilationConfiguration(discoveredSteps: List<StepMetadata>): ScriptCompilationConfiguration {
// 1. Serializar los metadatos
val jsonSteps = Json.encodeToString(discoveredSteps)
val encodedSteps = Base64.getEncoder().encodeToString(jsonSteps.toByteArray())

    return ScriptCompilationConfiguration {
        // ... otras configuraciones

        // 2. Pasar los metadatos al plugin de compilador
        compilerOptions.append("-P", "plugin:pipeline-compiler-plugin:steps=$encodedSteps")

        // 3. Asegurarse de que el JAR del plugin esté en el classpath de compilación
        updateClasspath(compilerPluginJar)
    }
}
Fase 3: El Plugin de Compilador (El Corazón de la Magia) ✨
Esta es la pieza más importante. Realiza un trabajo de dos partes para lograr la magia al estilo MapStruct.

Parte A: Generación de Declaraciones (Hacer los pasos visibles al IDE)
Esto hace que el IDE y el compilador "vean" las funciones del DSL antes de tiempo.

Componente: FirDeclarationGenerationExtension (para K2).

Funcionamiento:

El plugin se inicializa y decodifica la cadena de StepMetadata que le pasó el motor.

Cuando el compilador analiza el bloque steps { ... }, le pregunta al plugin: "¿Qué funciones puedes generar aquí?".

El plugin usa los metadatos para generar "stubs" o firmas de función. Crucialmente, estas firmas generadas NO incluyen el parámetro PipelineContext.

Kotlin

// Lógica conceptual dentro de PipelineDeclarationGenerator.kt
override fun generateFunctions(callableId: CallableId, ...): List<FirNamedFunctionSymbol> {
val stepMeta = steps.find { it.dslName == callableId.callableName.identifier } ?: return emptyList()

    // Construimos una función para el DSL
    return listOf(buildSimpleFunction {
        name = Name.identifier(stepMeta.dslName)

        // Añadimos los parámetros que el USUARIO ve (sin el contexto)
        stepMeta.parameters
            .filter { it.name != "context" } // ¡Excluimos el parámetro de contexto!
            .forEach { param ->
                valueParameters.add(buildValueParameter {
                    this.name = Name.identifier(param.name)
                    this.returnTypeRef = buildResolvedTypeRef { type = findTypeByFqName(param.typeFqName) }
                    if (param.isOptional) {
                        this.defaultValue = buildExpressionStub()
                    }
                })
            }
        // ... configurar visibilidad, tipo de retorno, etc.
    }.symbol)
}
Resultado: El IDE ve fun dockerBuild(tag: String, path: String = ".") y ofrece autocompletado y verificación de tipos perfectos. Un error como dockerBuild(tag = 123) se detecta instantáneamente.

Parte B: Transformación de la Llamada (Inyección Invisible del Contexto)
Esto conecta la llamada limpia del usuario con la función real que espera el contexto.

Componente: FirExpressionResolutionExtension (para K2).

Funcionamiento:

Después de que el compilador ha verificado que la llamada dockerBuild(tag = "...") es correcta (gracias a la Parte A), esta extensión la intercepta.

Reescribe el Árbol de Sintaxis Abstracto (AST) de la llamada.

La llamada original dockerBuild(tag = "my-app") se transforma en dockerBuild(this.pipelineContext, tag = "my-app").

¿Cómo sabe qué inyectar?
El DSL se ejecuta dentro de un StepsBlock. El this dentro de ese bloque tiene una propiedad pipelineContext. El transformador está diseñado para buscar esa propiedad en el ámbito actual y usarla como el primer argumento de la función real.

Este proceso de dos pasos es exactamente cómo los procesadores de anotaciones como MapStruct logran su integración: generan código que el compilador ve como si siempre hubiera estado allí.

Fase 4: La Ejecución (Uniendo Todo) 🚀
El flujo completo desde la escritura del script hasta su ejecución.

El StepsBlock se simplifica:
Su única responsabilidad es mantener la instancia del contexto. No contiene ninguna lógica de pasos.

Kotlin

open class StepsBlock(val pipeline: Pipeline) {
// La instancia del contexto que se inyectará mágicamente
internal val pipelineContext: PipelineContext = StepExecutionContext.create(pipeline, ...)

    // Este bloque está vacío. Las funciones las provee el compilador.
}
Flujo de Ejecución Completo:
a. El PipelineDslEngine se inicia.
b. Descubre los StepProvider (DockerSteps) y extrae los StepMetadata.
c. Prepara la configuración de compilación, pasando los metadatos serializados al plugin.
d. Se invoca al compilador de scripts de Kotlin para compilar el pipeline del usuario.
e. El plugin (Parte A) genera las firmas limpias: dockerBuild(tag: String, ...). El código del usuario se valida contra estas firmas. Cero errores de compilación.
f. El plugin (Parte B) transforma la llamada dockerBuild(...) en dockerBuild(this.pipelineContext, ...).
g. La compilación finaliza, generando un bytecode que apunta a la función original del plugin (DockerSteps.dockerBuild).
h. El motor ejecuta el script compilado, proporcionando una instancia de StepsBlock.
i. Cuando se llama a dockerBuild, se ejecuta el bytecode transformado. El pipelineContext de la instancia de StepsBlock se pasa como el primer argumento a la función real en DockerSteps, que a su vez ejecuta context.executeShell(...).

¡Listo! Has logrado un sistema extremadamente potente que cumple todos tus requisitos, manteniendo las partes del sistema limpias y centradas en su única responsabilidad.