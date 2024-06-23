package dev.rubentxu.pipeline.core.models.pipeline

import dev.rubentxu.pipeline.core.models.interfaces.PipelineModel
import java.util.concurrent.ConcurrentHashMap

/**
 * `EnvVars` is a thread-safe class for storing and managing environment variables.
 * It provides various operators for convenient access and modification of the variables.
 */
class EnvVars(val map: Map<String, String>) : MutableMap<String, String> by ConcurrentHashMap(map), PipelineModel {


    /**
     * Retrieves all environment variables.
     *
     * @return A map of environment variables.
     */
    fun getVariables(): Map<String, String> = this


    /**
     * Expands a string containing environment variable references of the form `${VARIABLE}` to their corresponding values.
     *
     * @param s The string to expand.
     * @return The expanded string with all environment variable references replaced by their values.
     * @throws Exception If an environment variable found in the string does not exist.
     */
    fun expand(s: String): String {
        var result = s
        val regex = Regex("\\$\\{([^}]*)\\}")
        val matches = regex.findAll(s)

        matches.forEach { matchResult ->
            val variableName = matchResult.groups[1]?.value
            val variableValue = get(variableName)
                ?: throw Exception("Not Found Environment Var $variableName")
            result = result.replace("\${$variableName}", variableValue)
        }

        return result
    }

    override fun toMap(): Map<String, Any> {
        return this
    }
}