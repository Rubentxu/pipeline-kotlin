package dev.rubentxu.pipeline.backend.factories

import arrow.core.raise.Raise
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.optional
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.PropertiesError
import dev.rubentxu.pipeline.model.Res


interface PipelineDomainFactory<T> {
    val rootPath: String

    context(Raise<PropertiesError>)
    fun getRootPropertySet(data: PropertySet): PropertySet {
        return data.required<PropertySet>(rootPath)
    }

    context(Raise<PropertiesError>)
    fun getRootListPropertySet(data: PropertySet): List<PropertySet>? {
        return data.optional<List<PropertySet>>(rootPath)
    }


    suspend fun create(data: PropertySet): Res<T>
}