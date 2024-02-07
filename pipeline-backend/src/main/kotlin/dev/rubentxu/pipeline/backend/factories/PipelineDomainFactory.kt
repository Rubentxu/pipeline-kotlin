package dev.rubentxu.pipeline.backend.factories


import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.optional
import dev.rubentxu.pipeline.backend.mapper.required


interface PipelineDomainFactory<T> {
    val rootPath: String

    fun getRootPropertySet(data: PropertySet): PropertySet {
        return data.required<PropertySet>(rootPath).getOrThrow()
    }

    fun getRootListPropertySet(data: PropertySet): List<PropertySet>? {
        return data.optional<List<PropertySet>>(rootPath).getOrThrow()
    }

    suspend fun create(data: PropertySet): Result<T>
}