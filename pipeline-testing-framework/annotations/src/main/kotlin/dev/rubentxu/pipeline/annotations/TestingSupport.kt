package dev.rubentxu.pipeline.annotations

/**
 * Representa una invocación de paso capturada durante la ejecución de pruebas.
 */
data class StepInvocation(
    val stepName: String,
    val arguments: List<Any?>,
    val result: Any? = null,
    val exception: Throwable? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Configuración para el framework de pruebas.
 * Se usa en el constructor de los test frameworks generados.
 */
class TestConfiguration {
    var enableDetailedLogging: Boolean = false
    var captureStepArguments: Boolean = true
    var captureStepResults: Boolean = true
    var mockAllSteps: Boolean = false
    
    /**
     * Configura el comportamiento de logging detallado.
     */
    fun detailedLogging(enabled: Boolean = true) {
        enableDetailedLogging = enabled
    }
    
    /**
     * Configura si capturar argumentos de los pasos.
     */
    fun captureArguments(enabled: Boolean = true) {
        captureStepArguments = enabled
    }
    
    /**
     * Configura si capturar resultados de los pasos.
     */
    fun captureResults(enabled: Boolean = true) {
        captureStepResults = enabled
    }
    
    /**
     * Configura si mockear todos los pasos automáticamente.
     */
    fun mockAll(enabled: Boolean = true) {
        mockAllSteps = enabled
    }
}

/**
 * Scope para verificaciones de invocaciones de pasos.
 */
class VerificationScope(private val invocations: List<StepInvocation>) {
    
    /**
     * Verifica que un paso específico fue llamado.
     */
    fun stepWasCalled(stepName: String): Boolean {
        return invocations.any { it.stepName == stepName }
    }
    
    /**
     * Verifica que un paso fue llamado un número específico de veces.
     */
    fun stepWasCalledTimes(stepName: String, times: Int): Boolean {
        return invocations.count { it.stepName == stepName } == times
    }
    
    /**
     * Verifica que un paso fue llamado con argumentos específicos.
     */
    fun stepWasCalledWith(stepName: String, vararg arguments: Any?): Boolean {
        return invocations.any { invocation ->
            invocation.stepName == stepName && 
            invocation.arguments.size == arguments.size &&
            invocation.arguments.zip(arguments).all { (actual, expected) -> actual == expected }
        }
    }
    
    /**
     * Obtiene todas las invocaciones de un paso específico.
     */
    fun getInvocationsFor(stepName: String): List<StepInvocation> {
        return invocations.filter { it.stepName == stepName }
    }
    
    /**
     * Verifica que los pasos fueron llamados en un orden específico.
     */
    fun stepsWereCalledInOrder(vararg stepNames: String): Boolean {
        val calledSteps = invocations.map { it.stepName }
        var index = 0
        
        for (stepName in stepNames) {
            val nextIndex = calledSteps.subList(index, calledSteps.size).indexOf(stepName)
            if (nextIndex == -1) return false
            index = index + nextIndex + 1
        }
        
        return true
    }
    
    /**
     * Obtiene el número total de invocaciones.
     */
    val totalInvocations: Int get() = invocations.size
    
    /**
     * Obtiene todas las invocaciones.
     */
    val allInvocations: List<StepInvocation> get() = invocations.toList()
}