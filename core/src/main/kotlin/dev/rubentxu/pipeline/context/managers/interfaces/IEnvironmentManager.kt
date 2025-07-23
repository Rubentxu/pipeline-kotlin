package dev.rubentxu.pipeline.context.managers.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * Manages plain-text environment variables for the pipeline.
 * Its sole responsibility is hierarchical key-value pair management.
 * It has no knowledge of secrets.
 */
interface IEnvironmentManager {
    suspend fun get(name: String): String?
    suspend fun get(name: String, defaultValue: String): String
    fun set(name: String, value: String)
    fun inject(envVars: Map<String, String>)
    suspend fun getAll(): Map<String, String>
    suspend fun resolve(template: String): String
    suspend fun has(name: String): Boolean
    fun remove(name: String): Boolean
    fun createScope(name: String): IEnvironmentManager
    suspend fun <T> withScope(
        scopeName: String,
        envVars: Map<String, String> = emptyMap(),
        block: suspend IEnvironmentManager.() -> T
    ): T
    fun observe(): Flow<Map<String, String>>
    fun observe(name: String): Flow<String?>
}