package dev.rubentxu.pipeline.annotations

/**
 * Marca una clase como un DSL de pipeline.
 * 
 * El procesador KSP buscará esta anotación como punto de entrada para generar
 * el framework de pruebas correspondiente. Las clases anotadas con @PipelineDsl
 * serán analizadas para identificar sus pasos ejecutables.
 * 
 * ## Ejemplo de uso:
 * ```kotlin
 * @PipelineDsl
 * class MyPipeline {
 *     @PipelineStep
 *     fun sh(command: String): Int { ... }
 *     
 *     @PipelineStep
 *     fun echo(message: String): Unit { ... }
 * }
 * ```
 * 
 * @since 1.0.0
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class PipelineDsl

/**
 * Marca una función dentro de una clase @PipelineDsl como un paso del pipeline
 * que debe ser mockeable en el framework de pruebas.
 * 
 * El procesador KSP generará métodos de mock correspondientes para cada función
 * anotada con @PipelineStep, permitiendo interceptar y verificar su comportamiento
 * durante las pruebas.
 * 
 * ## Características soportadas:
 * - Funciones con parámetros tipados
 * - Valores de retorno de cualquier tipo
 * - Funciones suspend
 * - Parámetros con valores por defecto
 * 
 * ## Ejemplo de uso:
 * ```kotlin
 * @PipelineStep
 * fun sh(command: String, returnStdout: Boolean = false): Any {
 *     // Implementación real del paso
 * }
 * ```
 * 
 * ## Código generado resultante:
 * ```kotlin
 * // En el framework de pruebas generado
 * fun onSh(handler: (String, Boolean) -> Any) {
 *     // Configuración del mock
 * }
 * ```
 * 
 * @param name Nombre personalizado para el paso (opcional, por defecto usa el nombre de la función)
 * @param description Descripción del paso para documentación (opcional)
 * 
 * @since 1.0.0
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
annotation class PipelineStep(
    val name: String = "",
    val description: String = ""
)

/**
 * Marca una función de extensión como un paso del pipeline que debe ser
 * interceptable durante las pruebas.
 * 
 * Esta anotación es específica para funciones de extensión que operan sobre
 * clases del DSL del pipeline (como extensiones de StepsBlock).
 * 
 * ## Ejemplo de uso:
 * ```kotlin
 * @ExtensionStep
 * fun StepsBlock.customBuild(target: String): String {
 *     // Implementación de paso personalizado
 * }
 * ```
 * 
 * @since 1.0.0
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
annotation class ExtensionStep(
    val name: String = "",
    val description: String = ""
)

/**
 * Configura el comportamiento de generación del framework de pruebas
 * para una clase @PipelineDsl específica.
 * 
 * ## Ejemplo de uso:
 * ```kotlin
 * @PipelineDsl
 * @TestFrameworkConfig(
 *     generateVerification = true,
 *     enableStateTracking = true,
 *     baseTestClass = "io.kotest.core.spec.style.FunSpec"
 * )
 * class MyPipeline { ... }
 * ```
 * 
 * @param generateVerification Si se deben generar métodos de verificación de llamadas
 * @param enableStateTracking Si se debe rastrear el estado entre pasos
 * @param baseTestClass Clase base para el framework de pruebas generado
 * @param packageName Paquete donde generar el framework (opcional)
 * 
 * @since 1.0.0
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class TestFrameworkConfig(
    val generateVerification: Boolean = true,
    val enableStateTracking: Boolean = false,
    val baseTestClass: String = "io.kotest.core.spec.style.FunSpec",
    val packageName: String = ""
)