package dev.rubentxu.pipeline.steps

import java.util.concurrent.ConcurrentHashMap

class EnvVars  {

    private val variables = ConcurrentHashMap<String, String>()

    operator fun String.unaryPlus() = variables[this]

    operator fun String.unaryMinus() = variables.remove(this)


    operator fun String.plusAssign(value: String) {
        variables[this] = value
    }

    fun getVariables(): Map<String, String> = variables

    operator fun get(key: String): String? {
        return variables[key]
    }

    fun setProperty(name: String, value: String) {
        variables[name] = value
    }

    fun getEnvironment(): EnvVars {
        return this
    }

    fun expand(s: String): String {
        val regex = Regex("\\$\\{([^}]*)\\}")
        val matches = regex.findAll(s)


        matches.forEach { matchResult ->
            val variableName = matchResult.groups[1]?.value
            val variableValue = variables[variableName]
                ?: throw Exception("Not Found Environment Var $variableName")
            return variableValue
        }

        return s
    }
}