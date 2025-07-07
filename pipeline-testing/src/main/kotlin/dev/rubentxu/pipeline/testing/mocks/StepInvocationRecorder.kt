package dev.rubentxu.pipeline.testing.mocks

import dev.rubentxu.pipeline.testing.MockResult

/**
 * Records step invocations for later verification in tests
 * Similar to MethodInvocationRecorder in Groovy framework
 */
class StepInvocationRecorder {
    private val methodCalls = mutableMapOf<String, MutableList<StepInvocation<*>>>()
    
    /**
     * Record a step invocation with its arguments and result
     */
    fun <T> recordInvocation(methodName: String, args: T, result: MockResult): StepInvocation<T> {
        val invocation = when (args) {
            is Map<*, *> -> NamedArgsStepInvocation(methodName, args as Map<String, Any>, result)
            is List<*> -> PositionalArgsStepInvocation(methodName, args, result)
            else -> PositionalArgsStepInvocation(methodName, listOf(args), result)
        } as StepInvocation<T>
        
        methodCalls.getOrPut(methodName) { mutableListOf() }.add(invocation)
        return invocation
    }
    
    /**
     * Get all invocations for verification
     */
    fun getInvocations(): Map<String, List<StepInvocation<*>>> = methodCalls.toMap()
    
    /**
     * Check if a method was called
     */
    fun containsMethod(methodName: String): Boolean = methodCalls.containsKey(methodName)
    
    /**
     * Get invocations for a specific method
     */
    fun getMethodInvocations(methodName: String): List<StepInvocation<*>> = 
        methodCalls[methodName] ?: emptyList()
    
    /**
     * Get call count for a method
     */
    fun getCallCount(methodName: String): Int = getMethodInvocations(methodName).size
    
    /**
     * Clear all recorded invocations
     */
    fun clear() {
        methodCalls.clear()
    }
    
    override fun toString(): String {
        return "StepInvocationRecorder{methodCalls=$methodCalls}"
    }
}

/**
 * Base class for step invocations (similar to MethodInvocation in Groovy)
 */
abstract class StepInvocation<T>(
    val methodName: String,
    val args: T,
    val result: MockResult
) {
    /**
     * Verify if the given arguments match this invocation
     */
    abstract fun verifyArgs(expectedArgs: T): Boolean
    
    /**
     * Check if two arguments are equal, supporting wildcards and closures
     */
    protected fun checkArgsAreEqual(expected: Any?, actual: Any?): Boolean {
        return when {
            expected is Function1<*, *> -> {
                try {
                    (expected as Function1<Any?, Boolean>)(actual)
                } catch (e: Exception) {
                    false
                }
            }
            expected is Map<*, *> && expected.containsKey("value") -> expected["value"] == actual
            expected is Wildcard || expected == "_" -> true
            else -> expected == actual
        }
    }
    
    /**
     * Ensure condition is true, throw exception with message if false
     */
    protected fun ensure(condition: Boolean, message: String) {
        if (!condition) {
            throw AssertionError(message)
        }
    }
    
    override fun toString(): String {
        return "${this::class.simpleName}{methodName='$methodName', args=$args, result=$result}"
    }
}

/**
 * Step invocation with named arguments (Map-based)
 */
class NamedArgsStepInvocation(
    methodName: String,
    private val namedArgs: Map<String, Any>,
    result: MockResult
) : StepInvocation<Map<String, Any>>(methodName, namedArgs, result) {
    
    override fun verifyArgs(expectedNamedArgs: Map<String, Any>): Boolean {
        ensure(expectedNamedArgs.size <= namedArgs.size,
            """
            Number of arguments are not the same, Actual: ${namedArgs.size}, Expected: ${expectedNamedArgs.size}
              Actual: $namedArgs
              Expected: $expectedNamedArgs
            """.trimIndent()
        )
        
        expectedNamedArgs.forEach { (key, expectedValue) ->
            if (!isWildcard(expectedValue)) {
                val actualValue = namedArgs[key]
                ensure(checkArgsAreEqual(expectedValue, actualValue),
                    """Argument with name '$key' is not the expected,
                       Expected: >$expectedValue<
                       Actual:   >$actualValue<
                    """.trimIndent()
                )
            }
        }
        return true
    }
    
    override fun equals(other: Any?): Boolean {
        ensure(other is NamedArgsStepInvocation,
            """You are passing positional arguments and you are expected to use named arguments.
               Redefine method invocation 'steps.$methodName' with the correct arguments.
               For example:
               steps.$methodName(${namedArgs.map { (key, value) -> "$key: expectedValue" }.joinToString(", ")})
            """.trimIndent()
        )
        
        val that = other as NamedArgsStepInvocation
        return methodName == that.methodName && verifyArgs(that.namedArgs)
    }
    
    override fun hashCode(): Int = methodName.hashCode()
    
    private fun isWildcard(value: Any?): Boolean = value is Wildcard || value == "_"
}

/**
 * Step invocation with positional arguments (List-based)
 */
class PositionalArgsStepInvocation(
    methodName: String,
    private val positionalArgs: List<Any?>,
    result: MockResult
) : StepInvocation<List<Any?>>(methodName, positionalArgs, result) {
    
    override fun verifyArgs(expectedArgs: List<Any?>): Boolean {
        ensure(expectedArgs.size <= positionalArgs.size,
            """
            Number of arguments are not the same, Actual: ${positionalArgs.size}, Expected: ${expectedArgs.size}
              Actual: $positionalArgs
              Expected: $expectedArgs
            """.trimIndent()
        )
        
        expectedArgs.forEachIndexed { index, expectedArg ->
            if (!isWildcard(expectedArg)) {
                ensure(checkArgsAreEqual(expectedArg, positionalArgs[index]),
                    """Argument at index $index is not the expected,
                       Expected: >$expectedArg<
                       Actual:   >${positionalArgs[index]}<
                    """.trimIndent()
                )
            }
        }
        return true
    }
    
    override fun equals(other: Any?): Boolean {
        if (other !is PositionalArgsStepInvocation) return false
        return methodName == other.methodName && verifyArgs(other.positionalArgs)
    }
    
    override fun hashCode(): Int = methodName.hashCode()
    
    private fun isWildcard(value: Any?): Boolean = value is Wildcard || value == "_"
}

/**
 * Wildcard object for ignoring arguments in verification
 */
object Wildcard {
    override fun toString(): String = "_"
}

/**
 * Convenience property for wildcard
 */
val wildcard get() = Wildcard