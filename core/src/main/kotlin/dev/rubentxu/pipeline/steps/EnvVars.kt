package dev.rubentxu.pipeline.steps

import java.util.concurrent.ConcurrentHashMap

/**
 * `EnvVars` is a thread-safe class for storing and managing environment variables.
 * It provides various operators for convenient access and modification of the variables.
 */
class EnvVars {

    /**
     * A concurrent hashmap storing the environment variables.
     */
    private val variables = ConcurrentHashMap<String, String>()

    /**
     * Sets the value of an environment variable.
     * Usage: `"VARIABLE_NAME" += "value"`
     */
    operator fun String.plusAssign(value: String) {
        variables[this] = value
    }
    fun String.assign(value: String) {
        variables[this] = value
    }

    /**
     * Retrieves all environment variables.
     *
     * @return A map of environment variables.
     */
    fun getVariables(): Map<String, String> = variables

    /**
     * Retrieves the value of an environment variable.
     *
     * @return The value of the environment variable.
     */
    operator fun get(key: String): String? {
        return variables[key]
    }

    /**
     * Sets a property in the environment variables.
     *
     * @param name The name of the property.
     * @param value The value of the property.
     */
    fun setProperty(name: String, value: String) {
        variables[name] = value
    }

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
            val variableValue = variables[variableName]
                ?: throw Exception("Not Found Environment Var $variableName")
            result = result.replace("\${$variableName}", variableValue)
        }

        return result
    }
}