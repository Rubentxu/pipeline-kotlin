package dev.rubentxu.pipeline.core.interfaces

interface IConfigClient {

    fun addAdapter(adapter: IConfigAdapter)

    fun loadData(overrideConfig: Map<String, Any?>)

    fun refresh()

    fun <T> required(key: String, type: Class<T>): T

    fun <T> optional(key: String, defaultValue: T): T

}