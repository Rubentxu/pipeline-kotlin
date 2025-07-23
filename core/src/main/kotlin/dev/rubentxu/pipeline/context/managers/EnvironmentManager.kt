package dev.rubentxu.pipeline.context.managers

import dev.rubentxu.pipeline.context.managers.interfaces.IEnvironmentManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.coroutines.CoroutineContext


/**
 * CoroutineContext Element to hold the active EnvironmentManager scope.
 */
class EnvironmentScopeContext(val scope: IEnvironmentManager) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<EnvironmentScopeContext>
    override val key: CoroutineContext.Key<*> = Key
}

/**
 * Helper to get the current EnvironmentManager from the coroutine context.
 */
suspend fun currentEnvironment(): IEnvironmentManager? = coroutineScope {
    coroutineContext[EnvironmentScopeContext.Key]?.scope
}

/**
 * Default implementation of IEnvironmentManager.
 */
class DefaultEnvironmentManager(
    private val parent: IEnvironmentManager? = null,
    initialVars: Map<String, String> = if (parent == null) System.getenv() else emptyMap()
) : IEnvironmentManager {

    private val scopeVars = ConcurrentHashMap(initialVars)
    private val _localStateFlow = MutableStateFlow(scopeVars.toMap())
    private val templatePattern = Pattern.compile("\\$\\{([^}]+)}")

    override suspend fun get(name: String): String? {
        return scopeVars[name] ?: parent?.get(name)
    }

    override suspend fun get(name: String, defaultValue: String): String = get(name) ?: defaultValue

    override fun set(name: String, value: String) {
        scopeVars[name] = value
        _localStateFlow.value = scopeVars.toMap()
    }

    override fun inject(envVars: Map<String, String>) {
        scopeVars.putAll(envVars)
        _localStateFlow.value = scopeVars.toMap()
    }

    override suspend fun getAll(): Map<String, String> {
        val parentVars = parent?.getAll() ?: emptyMap()
        return parentVars + scopeVars
    }

    override suspend fun resolve(template: String): String {
        val matcher = templatePattern.matcher(template)
        val sb = StringBuffer()
        while (matcher.find()) {
            val varName = matcher.group(1)
            val varValue = get(varName) ?: ""
            matcher.appendReplacement(sb, Matcher.quoteReplacement(varValue))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    override suspend fun has(name: String): Boolean {
        return scopeVars.containsKey(name) || (parent?.has(name) == true)
    }

    override fun remove(name: String): Boolean {
        val removed = scopeVars.remove(name) != null
        if (removed) {
            _localStateFlow.value = scopeVars.toMap()
        }
        return removed
    }

    override fun createScope(name: String): IEnvironmentManager {
        return DefaultEnvironmentManager(parent = this)
    }

    override suspend fun <T> withScope(
        scopeName: String,
        envVars: Map<String, String>,
        block: suspend IEnvironmentManager.() -> T
    ): T {
        val nestedScope = createScope(scopeName)
        nestedScope.inject(envVars)
        return withContext(EnvironmentScopeContext(nestedScope)) {
            nestedScope.block()
        }
    }

    override fun observe(): Flow<Map<String, String>> {
        val parentFlow = parent?.observe() ?: flowOf(emptyMap())
        return parentFlow.combine(_localStateFlow) { parentVars, localVars ->
            parentVars + localVars
        }
    }

    override fun observe(name: String): Flow<String?> {
        return observe().map { it[name] }.distinctUntilChanged()
    }
}